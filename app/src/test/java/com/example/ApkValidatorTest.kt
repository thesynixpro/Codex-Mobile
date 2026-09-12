package com.example

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Host-side unit tests for [ApkValidator]. All fixtures are built in-memory
 * with java.util.zip so no Android framework or external tools are needed —
 * except [strict accepts a genuinely signed apk], which needs a JDK's
 * keytool/jarsigner and skips otherwise.
 */
class ApkValidatorTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "apkvalidator-${System.nanoTime()}")
        check(dir.mkdirs())
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // ---------- Fixture builders ----------

    private fun writeZip(name: String, specs: List<Triple<String, ByteArray, Int>>): File {
        val f = File(dir, name)
        ZipOutputStream(f.outputStream()).use { zos ->
            for ((entryName, bytes, method) in specs) {
                val entry = ZipEntry(entryName)
                if (method == ZipEntry.STORED) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    val crc = CRC32()
                    crc.update(bytes)
                    entry.crc = crc.value
                }
                zos.putNextEntry(entry)
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return f
    }

    private fun manifestXml(pkg: String = "com.test.app"): ByteArray {
        return """<?xml version="1.0" encoding="utf-8"?><manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$pkg"></manifest>"""
            .toByteArray(Charsets.UTF_8)
    }

    private fun binaryManifestStub(): ByteArray {
        // Binary AXML magic (0x00080003 LE) + padding.
        return byteArrayOf(0x03, 0x00, 0x08, 0x00, 0x10, 0x00, 0x00, 0x00, 0x01, 0x02, 0x03, 0x04)
    }

    private fun validDexBytes(payloadSize: Int = 64): ByteArray {
        val total = 40 + payloadSize
        val buf = ByteArray(total)
        "dex\n035\u0000".toByteArray(Charsets.US_ASCII).copyInto(buf)
        buf[32] = (total and 0xFF).toByte()
        buf[33] = ((total ushr 8) and 0xFF).toByte()
        buf[34] = ((total ushr 16) and 0xFF).toByte()
        buf[35] = ((total ushr 24) and 0xFF).toByte()
        return buf
    }

    private fun manifestMf(): ByteArray {
        return "Manifest-Version: 1.0\r\nCreated-By: Codex Test\r\n\r\n".toByteArray(Charsets.UTF_8)
    }

    private fun dummySf(): ByteArray {
        return "Signature-Version: 1.0\r\nCreated-By: Codex Test\r\nSHA-256-Digest-Manifest: QUJD\r\n\r\n"
            .toByteArray(Charsets.UTF_8)
    }

    private fun dummyRsa(): ByteArray {
        // Deliberately NOT a real PKCS7 block: must fail STRICT verification.
        return "not-a-real-certificate".toByteArray(Charsets.UTF_8)
    }

    private fun checkByName(result: ApkValidationResult, name: String): ApkCheck {
        return result.checks.first { it.name == name }
    }

    // ---------- Tests ----------

    @Test
    fun `missing file is invalid`() {
        val result = ApkValidator.validate(File(dir, "nope.apk"))
        assertFalse(result.valid)
        assertFalse(checkByName(result, "APK file present").passed)
    }

    @Test
    fun `non-zip bytes fail integrity`() {
        val f = File(dir, "fake.apk")
        f.writeBytes("this is definitely not a zip file".toByteArray())
        val result = ApkValidator.validate(f)
        assertFalse(result.valid)
        assertFalse(checkByName(result, "ZIP integrity").passed)
    }

    @Test
    fun `empty archive is invalid`() {
        val f = writeZip("empty.apk", emptyList())
        val result = ApkValidator.validate(f)
        assertFalse(result.valid)
        assertFalse(checkByName(result, "ZIP integrity").passed)
    }

    @Test
    fun `missing manifest fails`() {
        val f = writeZip(
            "nomanifest.apk",
            listOf(Triple("classes.dex", validDexBytes(), ZipEntry.DEFLATED))
        )
        val result = ApkValidator.validate(f)
        assertFalse(result.valid)
        assertFalse(checkByName(result, "AndroidManifest").passed)
    }

    @Test
    fun `missing classes dex fails`() {
        val f = writeZip(
            "nodex.apk",
            listOf(
                Triple("AndroidManifest.xml", manifestXml(), ZipEntry.DEFLATED),
                Triple("resources.arsc", ByteArray(16), ZipEntry.DEFLATED)
            )
        )
        val result = ApkValidator.validate(f)
        assertFalse(result.valid)
        assertFalse(checkByName(result, "Compiled classes").passed)
    }

    @Test
    fun `bad dex magic fails`() {
        val f = writeZip(
            "baddex.apk",
            listOf(
                Triple("AndroidManifest.xml", manifestXml(), ZipEntry.DEFLATED),
                Triple("classes.dex", ByteArray(128), ZipEntry.DEFLATED)
            )
        )
        val result = ApkValidator.validate(f)
        assertFalse(result.valid)
        val check = checkByName(result, "Compiled classes")
        assertFalse(check.passed)
        assertTrue(check.detail.contains("magic"))
    }

    @Test
    fun `dex header size mismatch fails`() {
        val dex = validDexBytes()
        dex[32] = 0x01 // corrupt file_size field
        val f = writeZip(
            "sizemismatch.apk",
            listOf(
                Triple("AndroidManifest.xml", manifestXml(), ZipEntry.DEFLATED),
                Triple("classes.dex", dex, ZipEntry.DEFLATED)
            )
        )
        val result = ApkValidator.validate(f)
        assertFalse(result.valid)
        assertFalse(checkByName(result, "Compiled classes").passed)
    }

    @Test
    fun `missing resources arsc fails`() {
        val f = writeZip(
            "noarsc.apk",
            listOf(
                Triple("AndroidManifest.xml", manifestXml(), ZipEntry.DEFLATED),
                Triple("classes.dex", validDexBytes(), ZipEntry.DEFLATED),
                Triple("META-INF/MANIFEST.MF", manifestMf(), ZipEntry.DEFLATED),
                Triple("META-INF/CERT.SF", dummySf(), ZipEntry.DEFLATED),
                Triple("META-INF/CERT.RSA", dummyRsa(), ZipEntry.DEFLATED)
            )
        )
        val result = ApkValidator.validate(f, signatureMode = ApkValidator.SignatureMode.STRUCTURE)
        assertFalse(result.valid)
        assertFalse(checkByName(result, "Compiled resources").passed)
    }

    @Test
    fun `unsigned package fails signature`() {
        val f = writeZip(
            "unsigned.apk",
            listOf(
                Triple("AndroidManifest.xml", manifestXml(), ZipEntry.DEFLATED),
                Triple("classes.dex", validDexBytes(), ZipEntry.DEFLATED),
                Triple("resources.arsc", ByteArray(16), ZipEntry.DEFLATED)
            )
        )
        val result = ApkValidator.validate(f, signatureMode = ApkValidator.SignatureMode.STRUCTURE)
        assertFalse(result.valid)
        val check = checkByName(result, "APK signature")
        assertFalse(check.passed)
        assertTrue(check.detail.contains("not signed"))
    }

    @Test
    fun `manifest without cert file fails signature`() {
        val f = writeZip(
            "nocert.apk",
            listOf(
                Triple("AndroidManifest.xml", manifestXml(), ZipEntry.DEFLATED),
                Triple("classes.dex", validDexBytes(), ZipEntry.DEFLATED),
                Triple("resources.arsc", ByteArray(16), ZipEntry.DEFLATED),
                Triple("META-INF/MANIFEST.MF", manifestMf(), ZipEntry.DEFLATED)
            )
        )
        val result = ApkValidator.validate(f, signatureMode = ApkValidator.SignatureMode.STRUCTURE)
        assertFalse(result.valid)
        assertFalse(checkByName(result, "APK signature").passed)
    }

    @Test
    fun `structure mode accepts a complete package`() {
        val f = writeZip(
            "good.apk",
            listOf(
                Triple("AndroidManifest.xml", manifestXml("com.test.app"), ZipEntry.DEFLATED),
                Triple("classes.dex", validDexBytes(), ZipEntry.DEFLATED),
                Triple("resources.arsc", ByteArray(32), ZipEntry.DEFLATED),
                Triple("assets/www/index.html", "<html></html>".toByteArray(), ZipEntry.DEFLATED),
                Triple("META-INF/MANIFEST.MF", manifestMf(), ZipEntry.DEFLATED),
                Triple("META-INF/CERT.SF", dummySf(), ZipEntry.DEFLATED),
                Triple("META-INF/CERT.RSA", dummyRsa(), ZipEntry.DEFLATED)
            )
        )
        val result = ApkValidator.validate(f, "com.test.app", ApkValidator.SignatureMode.STRUCTURE)
        assertTrue(ApkValidator.summarize(result), result.valid)
    }

    @Test
    fun `conflicting manifest package is reported`() {
        val f = writeZip(
            "conflict.apk",
            listOf(
                Triple("AndroidManifest.xml", manifestXml("com.other.app"), ZipEntry.DEFLATED),
                Triple("classes.dex", validDexBytes(), ZipEntry.DEFLATED)
            )
        )
        val result = ApkValidator.validate(f, "com.test.app", ApkValidator.SignatureMode.STRUCTURE)
        assertFalse(result.valid)
        val check = checkByName(result, "AndroidManifest")
        assertFalse(check.passed)
        assertTrue(check.detail.contains("does not match"))
    }

    @Test
    fun `binary manifest is accepted`() {
        val f = writeZip(
            "binary.apk",
            listOf(
                Triple("AndroidManifest.xml", binaryManifestStub(), ZipEntry.DEFLATED),
                Triple("classes.dex", validDexBytes(), ZipEntry.DEFLATED)
            )
        )
        val result = ApkValidator.validate(f, null, ApkValidator.SignatureMode.STRUCTURE)
        assertTrue(checkByName(result, "AndroidManifest").passed)
    }

    @Test
    fun `strict rejects a fake certificate`() {
        val f = writeZip(
            "fakecert.apk",
            listOf(
                Triple("AndroidManifest.xml", manifestXml(), ZipEntry.DEFLATED),
                Triple("classes.dex", validDexBytes(), ZipEntry.DEFLATED),
                Triple("resources.arsc", ByteArray(32), ZipEntry.DEFLATED),
                Triple("META-INF/MANIFEST.MF", manifestMf(), ZipEntry.DEFLATED),
                Triple("META-INF/CERT.SF", dummySf(), ZipEntry.DEFLATED),
                Triple("META-INF/CERT.RSA", dummyRsa(), ZipEntry.DEFLATED)
            )
        )
        val result = ApkValidator.validate(f, null, ApkValidator.SignatureMode.STRICT)
        assertFalse(result.valid)
        assertFalse(checkByName(result, "APK signature").passed)
    }

    @Test
    fun `misaligned resources table fails alignment`() {
        // Layout: "a"(STORED,1B) -> "bb"(STORED,2B) -> resources.arsc(STORED).
        // arsc data lands at offset 110 => 110 % 4 == 2 (misaligned).
        val f = writeZip(
            "misaligned.apk",
            listOf(
                Triple("a", ByteArray(1), ZipEntry.STORED),
                Triple("bb", ByteArray(2), ZipEntry.STORED),
                Triple("resources.arsc", ByteArray(32), ZipEntry.STORED)
            )
        )
        val result = ApkValidator.validate(f, null, ApkValidator.SignatureMode.STRUCTURE)
        val check = checkByName(result, "ZIP alignment")
        assertFalse(check.passed)
        assertTrue(check.detail.contains("resources.arsc"))
    }

    @Test
    fun `aligned resources table passes alignment`() {
        // Layout: "aaa"(STORED,3B) -> resources.arsc(STORED).
        // arsc data lands at offset 80 => 80 % 4 == 0 (aligned).
        val f = writeZip(
            "aligned.apk",
            listOf(
                Triple("aaa", ByteArray(3), ZipEntry.STORED),
                Triple("resources.arsc", ByteArray(32), ZipEntry.STORED)
            )
        )
        val result = ApkValidator.validate(f, null, ApkValidator.SignatureMode.STRUCTURE)
        assertTrue(checkByName(result, "ZIP alignment").passed)
    }

    @Test
    fun `summarize lists failures`() {
        val result = ApkValidator.validate(File(dir, "missing.apk"))
        val summary = ApkValidator.summarize(result)
        assertTrue(summary.contains("[FAIL]"))
        assertTrue(result.errors.isNotEmpty())
    }

    // ---------- Genuine-signature test (needs a JDK on the test machine) ----------

    private fun findJdkTool(name: String): String? {
        val candidates = mutableListOf<String>()
        (System.getenv("PATH") ?: "").split(File.pathSeparator).forEach { candidates.add("$it/$name") }
        val javaHome = System.getProperty("java.home")
        candidates.add("$javaHome/bin/$name")
        File(javaHome).parent?.let { candidates.add("$it/bin/$name") }
        return candidates.map { File(it) }.firstOrNull { it.isFile && it.canExecute() }?.absolutePath
    }

    private fun runTool(exe: String, vararg args: String) {
        val proc = ProcessBuilder(listOf(exe) + args).directory(dir).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        val finished = proc.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)
        check(finished && proc.exitValue() == 0) { "$exe failed: $output" }
    }

    @Test
    fun `strict accepts a genuinely signed apk`() {
        val keytool = findJdkTool("keytool")
        val jarsigner = findJdkTool("jarsigner")
        assumeTrue("JDK keytool/jarsigner required", keytool != null && jarsigner != null)

        val unsigned = writeZip(
            "unsigned.apk",
            listOf(
                Triple("AndroidManifest.xml", manifestXml("com.test.signed"), ZipEntry.DEFLATED),
                Triple("classes.dex", validDexBytes(), ZipEntry.DEFLATED),
                Triple("resources.arsc", ByteArray(32), ZipEntry.DEFLATED)
            )
        )
        val ks = File(dir, "test.keystore")
        runTool(
            keytool!!, "-genkeypair", "-keystore", ks.absolutePath,
            "-storepass", "android", "-alias", "test", "-keypass", "android",
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "30",
            "-dname", "CN=Test,O=Test,C=US"
        )
        runTool(
            jarsigner!!, "-keystore", ks.absolutePath,
            "-storepass", "android", "-keypass", "android",
            unsigned.absolutePath, "test"
        )
        val result = ApkValidator.validate(unsigned, "com.test.signed", ApkValidator.SignatureMode.STRICT)
        assertTrue(ApkValidator.summarize(result), result.valid)
        assertEquals(7, result.checks.size)
    }
}
