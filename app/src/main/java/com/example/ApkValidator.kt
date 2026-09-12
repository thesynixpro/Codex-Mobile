package com.example

import java.io.File
import java.io.RandomAccessFile
import java.util.Collections
import java.util.jar.JarFile
import java.util.zip.ZipFile

/** Single automated validation check for a generated APK. */
data class ApkCheck(val name: String, val passed: Boolean, val detail: String)

/** Full validation outcome. [valid] is true only when every check passed. */
data class ApkValidationResult(val valid: Boolean, val checks: List<ApkCheck>) {
    val errors: List<String>
        get() = checks.filter { !it.passed }.map { "${it.name}: ${it.detail}" }
}

/**
 * Automated post-build validation for generated APKs.
 *
 * Detects the common causes of "App not installed" before any success is
 * reported: missing/corrupt ZIP structure, malformed manifests, missing
 * compiled classes or resources, missing signatures, and misaligned
 * uncompressed entries. Never throws for content problems — every problem
 * is reported as a failed [ApkCheck].
 */
object ApkValidator {

    /**
     * STRUCTURE verifies that signing files exist and are well-formed.
     * STRICT additionally runs full JAR signature verification over every
     * entry (tampered or improperly signed content fails).
     */
    enum class SignatureMode { STRUCTURE, STRICT }

    private val DEX_MAGIC_PREFIX = byteArrayOf(
        'd'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte()
    )

    fun validate(
        apk: File,
        expectedPackage: String? = null,
        signatureMode: SignatureMode = SignatureMode.STRICT
    ): ApkValidationResult {
        val checks = mutableListOf<ApkCheck>()

        // 1. File sanity.
        if (!apk.exists()) {
            checks.add(ApkCheck("APK file present", false, "Output file does not exist: ${apk.absolutePath}"))
            return ApkValidationResult(false, checks)
        }
        if (!apk.isFile || apk.length() <= 0) {
            checks.add(ApkCheck("APK file present", false, "Output file is empty or not a regular file."))
            return ApkValidationResult(false, checks)
        }
        checks.add(ApkCheck("APK file present", true, "${apk.name} (${apk.length()} bytes)"))

        // 2. ZIP integrity (reading every entry verifies stored CRCs).
        val entryNames: Set<String>
        try {
            ZipFile(apk).use { zip ->
                val entries = Collections.list(zip.entries())
                if (entries.isEmpty()) {
                    checks.add(ApkCheck("ZIP integrity", false, "Archive contains no entries; the build output is incomplete."))
                    return ApkValidationResult(false, checks)
                }
                for (entry in entries) {
                    if (!entry.isDirectory) {
                        zip.getInputStream(entry).use { it.readBytes() }
                    }
                }
                entryNames = entries.map { it.name }.toSet()
            }
            checks.add(ApkCheck("ZIP integrity", true, "${entryNames.size} entries, all CRCs verified"))
        } catch (e: Exception) {
            checks.add(ApkCheck("ZIP integrity", false, "Archive is corrupted or not a valid ZIP: ${e.message}"))
            return ApkValidationResult(false, checks)
        }

        // 3. AndroidManifest.xml
        checks.add(checkManifest(apk, entryNames, expectedPackage))

        // 4. Compiled classes.
        checks.add(checkDex(apk, entryNames))

        // 5. Compiled resources.
        if (entryNames.contains("resources.arsc")) {
            checks.add(ApkCheck("Compiled resources", true, "resources.arsc present"))
        } else {
            checks.add(ApkCheck("Compiled resources", false, "resources.arsc is missing; resources were not compiled with aapt2 (incomplete build)."))
        }

        // 6. Signature.
        checks.add(checkSignature(apk, entryNames, signatureMode))

        // 7. Alignment of uncompressed native/resources entries.
        checks.add(checkAlignment(apk))

        return ApkValidationResult(checks.all { it.passed }, checks)
    }

    /** Human-readable multi-line summary, used in error dialogs and logs. */
    fun summarize(result: ApkValidationResult): String {
        return result.checks.joinToString("\n") { c ->
            "${if (c.passed) "[OK] " else "[FAIL] "}${c.name}: ${c.detail}"
        }
    }

    private fun checkManifest(apk: File, entryNames: Set<String>, expectedPackage: String?): ApkCheck {
        if (!entryNames.contains("AndroidManifest.xml")) {
            return ApkCheck("AndroidManifest", false, "AndroidManifest.xml is missing from the package.")
        }
        return try {
            val bytes = ZipFile(apk).use { zip ->
                zip.getInputStream(zip.getEntry("AndroidManifest.xml")).use { it.readBytes() }
            }
            if (bytes.isEmpty()) {
                return ApkCheck("AndroidManifest", false, "AndroidManifest.xml is empty.")
            }
            if (isBinaryManifest(bytes)) {
                ApkCheck("AndroidManifest", true, "Binary AndroidManifest.xml present (${bytes.size} bytes)")
            } else {
                val text = bytes.toString(Charsets.UTF_8).trimStart()
                if (!text.startsWith("<") || !text.contains("<manifest")) {
                    ApkCheck("AndroidManifest", false, "AndroidManifest.xml is malformed (not binary AXML and not valid XML).")
                } else {
                    val pkg = Regex("package\\s*=\\s*\"([^\"]+)\"").find(text)?.groupValues?.getOrNull(1)
                    if (expectedPackage != null && pkg != null && pkg != expectedPackage) {
                        ApkCheck("AndroidManifest", false, "Manifest package \"$pkg\" does not match expected \"$expectedPackage\" (conflicting package name).")
                    } else {
                        ApkCheck("AndroidManifest", true, "XML manifest present${if (pkg != null) " (package $pkg)" else ""}; aapt2 output should normally be binary")
                    }
                }
            }
        } catch (e: Exception) {
            ApkCheck("AndroidManifest", false, "Could not read AndroidManifest.xml: ${e.message}")
        }
    }

    private fun isBinaryManifest(bytes: ByteArray): Boolean {
        return bytes.size >= 8 &&
            bytes[0] == 0x03.toByte() && bytes[1] == 0x00.toByte() &&
            bytes[2] == 0x08.toByte() && bytes[3] == 0x00.toByte()
    }

    private fun checkDex(apk: File, entryNames: Set<String>): ApkCheck {
        if (!entryNames.contains("classes.dex")) {
            return ApkCheck("Compiled classes", false, "classes.dex is missing; no compiled code is packaged (incomplete build).")
        }
        return try {
            val bytes = ZipFile(apk).use { zip ->
                val entry = zip.getEntry("classes.dex")
                if (entry.size <= 0) {
                    return ApkCheck("Compiled classes", false, "classes.dex is empty.")
                }
                zip.getInputStream(entry).use { it.readBytes() }
            }
            if (bytes.size < 40) {
                return ApkCheck("Compiled classes", false, "classes.dex is truncated (${bytes.size} bytes).")
            }
            val magicOk = bytes[0] == DEX_MAGIC_PREFIX[0] && bytes[1] == DEX_MAGIC_PREFIX[1] &&
                bytes[2] == DEX_MAGIC_PREFIX[2] && bytes[3] == DEX_MAGIC_PREFIX[3] &&
                bytes[4] == '0'.code.toByte() && bytes[5] == '3'.code.toByte() &&
                bytes[6] in '5'.code.toByte()..'9'.code.toByte() && bytes[7] == 0.toByte()
            if (!magicOk) {
                return ApkCheck("Compiled classes", false, "classes.dex has an invalid DEX header magic; the file is not a real DEX artifact.")
            }
            val headerSize = readU32Le(bytes, 32)
            if (headerSize != bytes.size.toLong()) {
                return ApkCheck("Compiled classes", false, "classes.dex header size ($headerSize) does not match actual size (${bytes.size}); file is corrupted.")
            }
            ApkCheck("Compiled classes", true, "classes.dex valid (${bytes.size} bytes)")
        } catch (e: Exception) {
            ApkCheck("Compiled classes", false, "Could not read classes.dex: ${e.message}")
        }
    }

    private fun checkSignature(apk: File, entryNames: Set<String>, mode: SignatureMode): ApkCheck {
        val metaInf = entryNames.filter { it.startsWith("META-INF/") }.map { it.uppercase() }
        if (!metaInf.contains("META-INF/MANIFEST.MF")) {
            return ApkCheck("APK signature", false, "META-INF/MANIFEST.MF is missing; the APK is not signed and Android will refuse to install it.")
        }
        val hasSf = metaInf.any { it.endsWith(".SF") }
        val hasCert = metaInf.any { it.endsWith(".RSA") || it.endsWith(".DSA") || it.endsWith(".EC") }
        if (!hasSf || !hasCert) {
            return ApkCheck(
                "APK signature", false,
                "Signing block incomplete (found MANIFEST.MF but ${if (!hasSf) "no .SF signature file" else "no .RSA/.DSA/.EC certificate"}); the APK is not properly signed."
            )
        }
        if (mode == SignatureMode.STRUCTURE) {
            return ApkCheck("APK signature", true, "v1 signing files present (MANIFEST.MF + signature + certificate)")
        }
        return try {
            JarFile(apk, true).use { jar ->
                val entries = Collections.list(jar.entries())
                for (entry in entries) {
                    if (!entry.isDirectory && !entry.name.startsWith("META-INF/")) {
                        jar.getInputStream(entry).use { it.readBytes() }
                    }
                }
            }
            ApkCheck("APK signature", true, "JAR signature verification passed over all entries")
        } catch (e: SecurityException) {
            ApkCheck("APK signature", false, "Signature verification failed (tampered or improperly signed content): ${e.message}")
        } catch (e: Exception) {
            ApkCheck("APK signature", false, "Could not verify APK signature: ${e.message}")
        }
    }

    private fun checkAlignment(apk: File): ApkCheck {
        return try {
            val entries = readCentralDirectory(apk)
            if (entries.isEmpty()) {
                return ApkCheck("ZIP alignment", false, "Could not read ZIP central directory.")
            }
            val misaligned = mutableListOf<String>()
            var checked = 0
            for (entry in entries) {
                val needsAlignment = entry.method == 0 &&
                    (entry.name == "resources.arsc" || entry.name.endsWith(".so"))
                if (!needsAlignment) continue
                checked++
                val dataOffset = localEntryDataOffset(apk, entry.localHeaderOffset)
                if (dataOffset < 0) {
                    misaligned.add("${entry.name} (unreadable local header)")
                } else if (dataOffset % 4 != 0L) {
                    misaligned.add("${entry.name} (data offset $dataOffset not 4-byte aligned)")
                }
            }
            if (misaligned.isNotEmpty()) {
                ApkCheck("ZIP alignment", false, "Uncompressed entries not page-aligned (install/runtime failures possible): ${misaligned.joinToString("; ")}")
            } else {
                ApkCheck("ZIP alignment", true, if (checked > 0) "$checked uncompressed librar${if (checked == 1) "y" else "ies"}/resource table 4-byte aligned" else "No uncompressed native libraries; nothing to align")
            }
        } catch (e: Exception) {
            ApkCheck("ZIP alignment", false, "Could not verify ZIP alignment: ${e.message}")
        }
    }

    // ---------- Minimal ZIP parsing (central directory + local headers) ----------

    private data class CentralEntry(val name: String, val method: Int, val localHeaderOffset: Long)

    private fun readCentralDirectory(apk: File): List<CentralEntry> {
        RandomAccessFile(apk, "r").use { raf ->
            val fileLen = raf.length()
            if (fileLen < 22) return emptyList()
            // Locate End-Of-Central-Directory by scanning backwards (max 64KB comment).
            val scanSize = minOf(fileLen, 22 + 65535L).toInt()
            val tail = ByteArray(scanSize)
            raf.seek(fileLen - scanSize)
            raf.readFully(tail)
            var eocdPos = -1
            var i = tail.size - 22
            while (i >= 0) {
                if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() &&
                    tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte()
                ) {
                    eocdPos = (fileLen - scanSize + i).toInt()
                    break
                }
                i--
            }
            if (eocdPos < 0) return emptyList()
            raf.seek(eocdPos.toLong())
            val eocd = ByteArray(22)
            raf.readFully(eocd)
            val entryCount = readU16Le(eocd, 10)
            val centralOffset = readU32Le(eocd, 16)
            raf.seek(centralOffset)
            val result = mutableListOf<CentralEntry>()
            repeat(entryCount) {
                val header = ByteArray(46)
                raf.readFully(header)
                if (!(header[0] == 0x50.toByte() && header[1] == 0x4b.toByte() &&
                        header[2] == 0x01.toByte() && header[3] == 0x02.toByte())
                ) {
                    return result
                }
                val method = readU16Le(header, 10)
                val nameLen = readU16Le(header, 28)
                val extraLen = readU16Le(header, 30)
                val commentLen = readU16Le(header, 32)
                val localOffset = readU32Le(header, 42)
                val nameBytes = ByteArray(nameLen)
                raf.readFully(nameBytes)
                raf.skipBytes(extraLen + commentLen)
                result.add(CentralEntry(String(nameBytes, Charsets.UTF_8), method, localOffset))
            }
            return result
        }
    }

    /** Absolute file offset where an entry's data starts, or -1 if unreadable. */
    private fun localEntryDataOffset(apk: File, localHeaderOffset: Long): Long {
        return try {
            RandomAccessFile(apk, "r").use { raf ->
                raf.seek(localHeaderOffset)
                val header = ByteArray(30)
                raf.readFully(header)
                if (!(header[0] == 0x50.toByte() && header[1] == 0x4b.toByte() &&
                        header[2] == 0x03.toByte() && header[3] == 0x04.toByte())
                ) {
                    return -1
                }
                val nameLen = readU16Le(header, 26)
                val extraLen = readU16Le(header, 28)
                localHeaderOffset + 30 + nameLen + extraLen
            }
        } catch (e: Exception) {
            -1
        }
    }

    private fun readU16Le(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readU32Le(bytes: ByteArray, offset: Int): Long {
        return (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }
}
