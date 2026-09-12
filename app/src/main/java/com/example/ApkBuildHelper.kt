package com.example

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Calendar
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipFile

object ApkBuildHelper {

    /** Final, validated build output plus where it was persisted. */
    data class BuiltApkResult(
        val cacheFile: File,
        /** URI string of the copy saved inside the SAF project folder, or null. */
        val projectUri: String? = null,
        /** Relative path inside the project folder (e.g. "build/apk/Demo-debug.apk"). */
        val projectRelativePath: String? = null,
        /** Absolute path of the copy in the app-external Download dir, or null. */
        val downloadFile: File? = null,
        val applicationId: String = "",
        val versionCode: Int = 1,
        val versionName: String = "1.0",
        val buildType: String = "debug",
        val validationChecks: List<ApkCheck> = emptyList()
    )

    data class ToolchainInfo(
        val termuxInstalled: Boolean,
        val openJdkAvailable: Boolean,
        val gradleAvailable: Boolean,
        val buildToolsAvailable: Boolean,
        val details: String,
        val setupScript: String,
        // Extended per-tool detection (all defaulted so existing callers compile).
        val aapt2Path: String? = null,
        val javacPath: String? = null,
        val d8Path: String? = null,
        val zipalignPath: String? = null,
        val apksignerPath: String? = null,
        val keytoolPath: String? = null,
        val androidJarPath: String? = null,
        val ready: Boolean = false,
        val missingTools: List<String> = emptyList()
    )

    // ------------------------------------------------------------------
    // Toolchain detection
    // ------------------------------------------------------------------

    fun checkToolchain(context: Context): ToolchainInfo {
        var termuxInstalled = false
        try {
            context.packageManager.getPackageInfo("com.termux", PackageManager.GET_ACTIVITIES)
            termuxInstalled = true
        } catch (e: Exception) {
            termuxInstalled = File("/data/data/com.termux/files/usr/bin/bash").exists()
        }

        val aapt2 = findTool("aapt2")
        val javac = findTool("javac") ?: findTool("javac.exe")
        val java = findTool("java")
        val d8 = findTool("d8")
        val zipalign = findTool("zipalign")
        val apksigner = findTool("apksigner")
        val keytool = findTool("keytool")
        val gradle = findTool("gradle")
        val androidJar = findAndroidJar()

        val openJdkAvailable = javac != null || java != null
        val gradleAvailable = gradle != null
        val buildToolsAvailable = aapt2 != null && d8 != null && apksigner != null

        val missing = mutableListOf<String>()
        if (aapt2 == null) missing.add("aapt2")
        if (javac == null) missing.add("javac (OpenJDK)")
        if (d8 == null) missing.add("d8")
        if (apksigner == null) missing.add("apksigner")
        if (keytool == null) missing.add("keytool (OpenJDK)")
        if (androidJar == null) missing.add("android.jar (Android SDK platform)")
        val ready = missing.isEmpty()

        val details = buildString {
            append("Termux App: ").append(if (termuxInstalled) "Installed" else "Not detected").append("\n")
            append("OpenJDK (javac/java): ").append(if (openJdkAvailable) "Available" else "Missing").append("\n")
            append("aapt2: ").append(aapt2 ?: "Missing").append("\n")
            append("d8: ").append(d8 ?: "Missing").append("\n")
            append("zipalign: ").append(zipalign ?: "Missing (manual alignment fallback will be used)").append("\n")
            append("apksigner: ").append(apksigner ?: "Missing").append("\n")
            append("keytool: ").append(keytool ?: "Missing").append("\n")
            append("android.jar: ").append(androidJar ?: "Missing").append("\n")
            append("Gradle: ").append(if (gradleAvailable) "Available" else "Not required for direct APK builds").append("\n")
            append("Real signed-APK builds: ").append(if (ready) "READY" else "NOT READY (${missing.joinToString(", ")})")
        }

        val setupScript = """
            # 1. Open Termux on your Android device
            pkg update -y
            # 2. Install OpenJDK and Android build tools
            pkg install -y openjdk-17 aapt2 d8 apksigner zipalign git
            # 3. Install an Android SDK platform (provides android.jar), e.g.:
            #    sdkmanager "platforms;android-34"   (or unzip a platform package under ~/android-sdk/platforms/)
            # 4. Verify toolchain
            java -version
            aapt2 version
            apksigner --version
        """.trimIndent()

        return ToolchainInfo(
            termuxInstalled = termuxInstalled,
            openJdkAvailable = openJdkAvailable,
            gradleAvailable = gradleAvailable,
            buildToolsAvailable = buildToolsAvailable,
            details = details,
            setupScript = setupScript,
            aapt2Path = aapt2,
            javacPath = javac,
            d8Path = d8,
            zipalignPath = zipalign,
            apksignerPath = apksigner,
            keytoolPath = keytool,
            androidJarPath = androidJar,
            ready = ready,
            missingTools = missing
        )
    }

    /** Absolute path of [binaryName] on PATH/Termux/system dirs, or null. */
    fun findTool(binaryName: String): String? {
        val dirs = mutableListOf<String>()
        try {
            System.getenv("PATH")?.split(":")?.let { dirs.addAll(it) }
        } catch (e: Exception) {
            // ignore
        }
        dirs.add("/data/data/com.termux/files/usr/bin")
        dirs.add("/system/bin")
        dirs.add("/system/xbin")
        for (dir in dirs.distinct()) {
            try {
                val f = File(dir, binaryName)
                if (f.isFile && f.canExecute()) return f.absolutePath
            } catch (e: Exception) {
                // ignore and keep searching
            }
        }
        return null
    }

    /** Locates android.jar from the highest installed SDK platform, or null. */
    fun findAndroidJar(): String? {
        val roots = mutableListOf<String>()
        for (env in listOf("ANDROID_HOME", "ANDROID_SDK_ROOT", "ANDROID_SDK_HOME")) {
            try {
                System.getenv(env)?.ifBlank { null }?.let { roots.add(it) }
            } catch (e: Exception) {
                // ignore
            }
        }
        try {
            System.getenv("HOME")?.let {
                roots.add("$it/android-sdk")
                roots.add("$it/sdk")
            }
        } catch (e: Exception) {
            // ignore
        }
        roots.add("/data/data/com.termux/files/usr/share/android-sdk")
        roots.add("/data/data/com.termux/files/home/android-sdk")
        roots.add("/sdcard/android-sdk")

        var bestApi = -1
        var bestJar: String? = null
        for (root in roots.distinct()) {
            val children = try {
                File(root, "platforms").listFiles()
            } catch (e: Exception) {
                null
            } ?: continue
            for (child in children) {
                val api = child.name.removePrefix("android-").toIntOrNull() ?: continue
                val jar = File(child, "android.jar")
                if (jar.isFile && jar.length() > 0 && api > bestApi) {
                    bestApi = api
                    bestJar = jar.absolutePath
                }
            }
        }
        return bestJar
    }

    // ------------------------------------------------------------------
    // Shell execution
    // ------------------------------------------------------------------

    private data class CommandResult(val exitCode: Int, val output: String)

    @Throws(IllegalStateException::class)
    private fun runCommand(exe: String, args: List<String>, workDir: File, timeoutSec: Long = 240): CommandResult {
        val proc = try {
            ProcessBuilder(listOf(exe) + args).directory(workDir).redirectErrorStream(true).start()
        } catch (e: Exception) {
            throw IllegalStateException("Could not launch $exe: ${e.message}")
        }
        val output = StringBuilder()
        val readerThread = Thread {
            try {
                proc.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    synchronized(output) {
                        if (output.length < 8000) output.append(line).append('\n')
                    }
                }
            } catch (e: Exception) {
                // reader interrupted; ignore
            }
        }
        readerThread.isDaemon = true
        readerThread.start()
        val finished = try {
            proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        } catch (e: Exception) {
            false
        }
        if (!finished) {
            try { proc.destroyForcibly() } catch (e: Exception) { /* ignore */ }
            throw IllegalStateException("Command timed out after ${timeoutSec}s: $exe ${args.joinToString(" ")}")
        }
        try { readerThread.join(5000) } catch (e: Exception) { /* ignore */ }
        val out = synchronized(output) { output.toString() }
        return CommandResult(proc.exitValue(), out)
    }

    @Throws(IllegalStateException::class)
    private fun requireSuccess(result: CommandResult, exe: String, args: List<String>): String {
        if (result.exitCode != 0) {
            val tail = result.output.takeLast(3000).trim()
            throw IllegalStateException(
                "Command failed (exit ${result.exitCode}): $exe ${args.joinToString(" ")}\n$tail"
            )
        }
        return result.output
    }

    // ------------------------------------------------------------------
    // Real APK build pipeline (aapt2 + javac + d8 + apksigner)
    // ------------------------------------------------------------------

    /**
     * Builds a real, signed, validated APK from web project files.
     *
     * Requires aapt2, javac, d8, apksigner, keytool and android.jar on the
     * device (Termux). When any required tool is missing the build FAILS with
     * an actionable error — it never reports success for a renamed ZIP or an
     * incomplete artifact. Debug builds are signed with an auto-generated
     * debug key; release builds require a caller-supplied keystore.
     */
    suspend fun buildWebApk(
        context: Context,
        projectName: String,
        projectFilesMap: Map<String, ByteArray>,
        onProgress: (step: Int, totalSteps: Int, stepName: String, logLine: String) -> Unit,
        projectRootUri: Uri? = null,
        options: ApkBuildOptions = ApkBuildOptions()
    ): BuiltApkResult {
        val totalSteps = 6
        val config = ApkBuildConfigValidation.resolve(projectName, options)
        val variant = if (config.buildType == ApkBuildType.RELEASE) "release" else "debug"

        // ---- Step 1: configuration + asset verification, stale cleanup ----
        onProgress(1, totalSteps, "Verify Config & Assets", "Validating application ID, versions, and SDK levels...")
        delay(250)
        if (!config.valid) {
            throw IllegalStateException(
                "Invalid APK configuration; refusing to build:\n- " + config.errors.joinToString("\n- ")
            )
        }
        onProgress(1, totalSteps, "Verify Config & Assets", "Config OK: ${config.applicationId} v${config.versionName} (${config.versionCode}), minSdk ${config.minSdk}, targetSdk ${config.targetSdk}, $variant")

        val excluded = mutableListOf<String>()
        val packagingFiles = LinkedHashMap<String, ByteArray>()
        for ((rawPath, content) in projectFilesMap) {
            val relPath = rawPath.trim().replace('\\', '/').trimStart('/')
            if (relPath.isEmpty() || relPath == "." || relPath == ".." ||
                relPath.startsWith("../") || relPath.contains("/../")
            ) {
                throw IllegalStateException("Refusing to package unsafe file path: \"$rawPath\"")
            }
            // Never package previous build outputs back into the APK.
            if (relPath.startsWith("build/") || relPath.lowercase().endsWith(".apk")) {
                excluded.add(relPath)
                continue
            }
            packagingFiles[relPath] = content
        }
        if (excluded.isNotEmpty()) {
            onProgress(1, totalSteps, "Verify Config & Assets", "Excluded ${excluded.size} previous build artifact(s) from packaging (build/, *.apk).")
        }
        val hasHtml = packagingFiles.keys.any { it.endsWith(".html", ignoreCase = true) || it.endsWith(".htm", ignoreCase = true) }
        if (!hasHtml) {
            throw IllegalStateException("No HTML file found in project. A web project requires at least one .html file (e.g. index.html) to build an Android APK.")
        }
        val entryHtml = if (packagingFiles.containsKey("index.html")) "index.html"
        else packagingFiles.keys.first { it.endsWith(".html", ignoreCase = true) || it.endsWith(".htm", ignoreCase = true) }
        onProgress(1, totalSteps, "Verify Config & Assets", "Entry point: $entryHtml (${packagingFiles.size} files to package)")

        // Clean stale artifacts so old/corrupt files are never reused.
        val buildDir = File(context.cacheDir, "codex_apk_build/${config.projectDirName}")
        if (buildDir.exists()) buildDir.deleteRecursively()
        buildDir.mkdirs()
        val outputApkDir = File(context.cacheDir, "built_apks")
        outputApkDir.mkdirs()
        val staleCache = File(outputApkDir, config.apkFileName)
        if (staleCache.exists()) staleCache.delete()
        if (projectRootUri != null) {
            try {
                DocumentTreeHelper.deleteRelativeFile(context, projectRootUri, "build/apk/${config.apkFileName}")
            } catch (e: Exception) {
                // Best effort; a missing stale file is fine.
            }
        }
        delay(200)

        // ---- Step 2: manifest + WebView shell synthesis ----
        onProgress(2, totalSteps, "Manifest & Shell", "Generating AndroidManifest.xml for ${config.applicationId}...")
        delay(250)
        val manifestFile = File(buildDir, "AndroidManifest.xml")
        manifestFile.writeText(buildManifestXml(config, config.projectDirName))
        val javaSrcDir = File(buildDir, "src")
        val packageDir = File(javaSrcDir, config.applicationId.replace('.', '/'))
        packageDir.mkdirs()
        File(packageDir, "MainActivity.java").writeText(buildShellActivityJava(config.applicationId, entryHtml))
        val resDir = File(buildDir, "res/values")
        resDir.mkdirs()
        File(resDir, "strings.xml").writeText(buildStringsXml(config.projectDirName))
        onProgress(2, totalSteps, "Manifest & Shell", "WebView shell + resources synthesized (entry: $entryHtml)")
        delay(200)

        // ---- Step 3: stage web assets ----
        onProgress(3, totalSteps, "Stage Assets", "Staging ${packagingFiles.size} files into assets/www/...")
        val assetsDir = File(buildDir, "assets/www")
        assetsDir.mkdirs()
        for ((relPath, content) in packagingFiles) {
            val destFile = File(assetsDir, relPath)
            if (!destFile.canonicalPath.startsWith(assetsDir.canonicalPath + File.separator)) {
                throw IllegalStateException("Refusing to write outside staging dir: \"$relPath\"")
            }
            destFile.parentFile?.mkdirs()
            destFile.writeBytes(content)
        }
        onProgress(3, totalSteps, "Stage Assets", "Assets staged successfully")
        delay(250)

        // ---- Step 4: toolchain resolution (fail fast, never fake it) ----
        onProgress(4, totalSteps, "Toolchain Check", "Resolving aapt2 / javac / d8 / apksigner / android.jar...")
        val toolchain = resolveBuildToolchain(context, config.minSdk)
        onProgress(4, totalSteps, "Toolchain Check", "Toolchain ready (aapt2, javac, d8, apksigner, android.jar)")
        delay(250)

        // ---- Step 5: compile resources, shell, dex; assemble package ----
        onProgress(5, totalSteps, "Compile & Package", "Compiling resources with aapt2...")
        val genDir = File(buildDir, "gen").apply { mkdirs() }
        val compiledRes = File(buildDir, "compiled.zip")
        requireSuccess(
            runCommand(toolchain.aapt2, listOf("compile", "--dir", File(buildDir, "res").absolutePath, "-o", compiledRes.absolutePath), buildDir),
            toolchain.aapt2, listOf("compile")
        )
        onProgress(5, totalSteps, "Compile & Package", "Linking base package (binary manifest + resources.arsc)...")
        val baseApk = File(buildDir, "base.apk")
        requireSuccess(
            runCommand(
                toolchain.aapt2,
                listOf(
                    "link", "-o", baseApk.absolutePath,
                    "-I", toolchain.androidJar,
                    "--manifest", manifestFile.absolutePath,
                    "--java", genDir.absolutePath,
                    "--min-sdk-version", config.minSdk.toString(),
                    "--target-sdk-version", config.targetSdk.toString(),
                    "--version-code", config.versionCode.toString(),
                    "--version-name", config.versionName,
                    "-A", File(buildDir, "assets").absolutePath,
                    compiledRes.absolutePath
                ),
                buildDir
            ),
            toolchain.aapt2, listOf("link")
        )
        onProgress(5, totalSteps, "Compile & Package", "Compiling WebView shell with javac...")
        val classesDir = File(buildDir, "classes").apply { mkdirs() }
        val javaFiles = (javaSrcDir.walkTopDown() + genDir.walkTopDown())
            .filter { it.isFile && it.extension == "java" }
            .map { it.absolutePath }
            .toList()
        if (javaFiles.isEmpty()) throw IllegalStateException("No Java sources found to compile; shell synthesis failed.")
        requireSuccess(
            runCommand(
                toolchain.javac,
                listOf("-source", "8", "-target", "8", "-classpath", toolchain.androidJar, "-d", classesDir.absolutePath) + javaFiles,
                buildDir
            ),
            toolchain.javac, listOf("javac")
        )
        onProgress(5, totalSteps, "Compile & Package", "Assembling classes.dex with d8...")
        val dexDir = File(buildDir, "dex").apply { mkdirs() }
        val classFiles = classesDir.walkTopDown().filter { it.isFile && it.extension == "class" }.map { it.absolutePath }.toList()
        if (classFiles.isEmpty()) throw IllegalStateException("Java compilation produced no class files; cannot assemble classes.dex.")
        requireSuccess(
            runCommand(
                toolchain.d8,
                listOf("--min-api", config.minSdk.toString(), "--lib", toolchain.androidJar, "--output", dexDir.absolutePath) + classFiles,
                buildDir
            ),
            toolchain.d8, listOf("d8")
        )
        val dexFile = File(dexDir, "classes.dex")
        if (!dexFile.isFile || dexFile.length() <= 0) {
            throw IllegalStateException("d8 did not produce classes.dex; DEX assembly failed.")
        }
        onProgress(5, totalSteps, "Compile & Package", "Assembling APK container (aligned entries)...")
        val unsignedApk = File(buildDir, "unsigned.apk")
        assembleFinalApk(baseApk, dexFile, unsignedApk)
        val alignedApk = File(buildDir, "aligned.apk")
        if (toolchain.zipalign != null) {
            requireSuccess(
                runCommand(toolchain.zipalign, listOf("-f", "4", unsignedApk.absolutePath, alignedApk.absolutePath), buildDir),
                toolchain.zipalign, listOf("zipalign")
            )
        } else {
            unsignedApk.copyTo(alignedApk, overwrite = true)
            onProgress(5, totalSteps, "Compile & Package", "zipalign not installed; used built-in 4-byte alignment writer.")
        }
        delay(250)

        // ---- Step 6: sign + validate (success is reported ONLY if valid) ----
        onProgress(6, totalSteps, "Sign & Validate", "Signing $variant APK...")
        val signedApk = File(outputApkDir, config.apkFileName)
        if (signedApk.exists()) signedApk.delete()
        alignedApk.copyTo(signedApk)
        signApk(context, toolchain, config, options, signedApk, buildDir)
        onProgress(6, totalSteps, "Sign & Validate", "Running automated installability validation...")
        delay(300)
        val validation = ApkValidator.validate(signedApk, config.applicationId, ApkValidator.SignatureMode.STRICT)
        for (check in validation.checks) {
            onProgress(
                6, totalSteps, "Sign & Validate",
                "${if (check.passed) "PASS" else "FAIL"} ${check.name}: ${check.detail}"
            )
        }
        // Extra safety: ask apksigner itself to verify the final artifact.
        try {
            val verifyOut = requireSuccess(
                runCommand(toolchain.apksigner, listOf("verify", "--print-certs", signedApk.absolutePath), buildDir),
                toolchain.apksigner, listOf("verify")
            )
            val firstCert = verifyOut.lineSequence().firstOrNull { it.contains("Signer #1") || it.contains("certificate") }
            onProgress(6, totalSteps, "Sign & Validate", "apksigner verify: signature OK${if (firstCert != null) " (${firstCert.trim().take(120)})" else ""}")
        } catch (e: IllegalStateException) {
            throw IllegalStateException("apksigner rejected the built APK (it would show 'App not installed'):\n${e.message}")
        }
        if (!validation.valid) {
            try { signedApk.delete() } catch (e: Exception) { /* ignore */ }
            throw IllegalStateException(
                "APK validation failed; refusing to report success (this APK would show 'App not installed'):\n" +
                    ApkValidator.summarize(validation)
            )
        }
        onProgress(6, totalSteps, "Sign & Validate", "Validation passed: installable ${config.apkFileName} (${signedApk.length()} bytes)")

        // Persist validated output: project build/apk/ + visible Download copy.
        var savedProjectUri: String? = null
        var savedProjectRelPath: String? = null
        var savedDownloadFile: File? = null
        if (projectRootUri != null) {
            try {
                savedProjectRelPath = "build/apk/${config.apkFileName}"
                val savedUri = DocumentTreeHelper.writeRelativeBinaryFile(
                    context, projectRootUri, savedProjectRelPath,
                    signedApk.readBytes(), "application/vnd.android.package-archive"
                )
                savedProjectUri = savedUri.toString()
                onProgress(6, totalSteps, "Sign & Validate", "Saved APK into project folder: $savedProjectRelPath")
            } catch (e: Exception) {
                onProgress(6, totalSteps, "Sign & Validate", "Warning: could not save APK into project folder (${e.message}). Cache + Download copies retained.")
                savedProjectRelPath = null
            }
        }
        try {
            val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(context.filesDir, "Download")
            if (!downloadDir.exists()) downloadDir.mkdirs()
            val dest = File(downloadDir, config.apkFileName)
            if (dest.exists()) dest.delete()
            signedApk.copyTo(dest, overwrite = true)
            savedDownloadFile = dest
            onProgress(6, totalSteps, "Sign & Validate", "Saved visible copy: ${dest.absolutePath}")
        } catch (e: Exception) {
            onProgress(6, totalSteps, "Sign & Validate", "Warning: could not write Download copy (${e.message}).")
        }

        return BuiltApkResult(
            cacheFile = signedApk,
            projectUri = savedProjectUri,
            projectRelativePath = savedProjectRelPath,
            downloadFile = savedDownloadFile,
            applicationId = config.applicationId,
            versionCode = config.versionCode,
            versionName = config.versionName,
            buildType = variant,
            validationChecks = validation.checks
        )
    }

    /**
     * Backwards-compatible entry point. Routes to the real validated pipeline
     * with default (debug) options.
     */
    suspend fun buildWebApkLegacy(
        context: Context,
        projectName: String,
        projectFilesMap: Map<String, ByteArray>,
        onProgress: (step: Int, totalSteps: Int, stepName: String, logLine: String) -> Unit
    ): File {
        return buildWebApk(context, projectName, projectFilesMap, onProgress, null, ApkBuildOptions()).cacheFile
    }

    /**
     * Lightweight readiness gate used before launching the system installer:
     * returns null when the APK looks installable, otherwise a user-facing
     * error describing why installation must not proceed.
     */
    fun preInstallCheck(apkFile: File): String? {
        if (!apkFile.isFile || apkFile.length() <= 0) {
            return "APK file is missing at ${apkFile.absolutePath}. Please rebuild the APK."
        }
        val result = ApkValidator.validate(apkFile, null, ApkValidator.SignatureMode.STRUCTURE)
        if (result.valid) return null
        return "APK failed pre-install validation (not launching installer):\n" + ApkValidator.summarize(result)
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private data class ResolvedToolchain(
        val aapt2: String,
        val javac: String,
        val d8: String,
        val apksigner: String,
        val keytool: String,
        val zipalign: String?,
        val androidJar: String
    )

    @Throws(IllegalStateException::class)
    private fun resolveBuildToolchain(context: Context, minSdk: Int): ResolvedToolchain {
        val aapt2 = findTool("aapt2")
        val javac = findTool("javac")
        val d8 = findTool("d8")
        val apksigner = findTool("apksigner")
        val keytool = findTool("keytool")
        val zipalign = findTool("zipalign")
        val androidJar = findAndroidJar()

        val missing = mutableListOf<String>()
        if (aapt2 == null) missing.add("aapt2")
        if (javac == null) missing.add("javac (OpenJDK)")
        if (d8 == null) missing.add("d8")
        if (apksigner == null) missing.add("apksigner")
        if (keytool == null) missing.add("keytool (OpenJDK)")
        if (androidJar == null) missing.add("android.jar (Android SDK platform)")
        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "Cannot produce an installable APK: required build tools are missing (${missing.joinToString(", ")}).\n" +
                    "Install them in Termux on your phone, then rebuild:\n" +
                    "  pkg update -y && pkg install -y openjdk-17 aapt2 d8 apksigner zipalign git\n" +
                    "  # then install an Android SDK platform providing android.jar\n" +
                    "  # (e.g. sdkmanager \"platforms;android-34\") and rebuild.\n" +
                    "No fake or unsigned artifact was produced."
            )
        }
        return ResolvedToolchain(aapt2!!, javac!!, d8!!, apksigner!!, keytool!!, zipalign, androidJar!!)
    }

    @Throws(IllegalStateException::class)
    private fun signApk(
        context: Context,
        toolchain: ResolvedToolchain,
        config: ResolvedApkConfig,
        options: ApkBuildOptions,
        apkFile: File,
        workDir: File
    ) {
        if (config.buildType == ApkBuildType.RELEASE) {
            val ksPath = options.releaseKeystorePath?.ifBlank { null }
                ?: File(context.filesDir, "release.keystore").takeIf { it.isFile }?.absolutePath
                ?: throw IllegalStateException(
                    "Release build requested but no signing keystore is configured.\n" +
                        "Provide releaseKeystorePath (+ alias + passwords) in the build options, or place a keystore at " +
                        "${File(context.filesDir, "release.keystore").absolutePath}."
                )
            val ksFile = File(ksPath)
            if (!ksFile.isFile) throw IllegalStateException("Release keystore not found at $ksPath.")
            val alias = options.releaseKeyAlias?.ifBlank { null } ?: "upload"
            val storePass = options.releaseStorePassword
                ?: throw IllegalStateException("Release build is missing the keystore (store) password.")
            val keyPass = options.releaseKeyPassword ?: storePass
            requireSuccess(
                runCommand(
                    toolchain.apksigner,
                    listOf(
                        "sign", "--ks", ksFile.absolutePath,
                        "--ks-key-alias", alias,
                        "--ks-pass", "pass:$storePass",
                        "--key-pass", "pass:$keyPass",
                        "--v1-signing-enabled", "true",
                        "--v2-signing-enabled", "true",
                        apkFile.absolutePath
                    ),
                    workDir
                ),
                toolchain.apksigner, listOf("sign")
            )
        } else {
            val ks = ensureDebugKeystore(context, toolchain.keytool)
            requireSuccess(
                runCommand(
                    toolchain.apksigner,
                    listOf(
                        "sign", "--ks", ks.absolutePath,
                        "--ks-key-alias", "androiddebugkey",
                        "--ks-pass", "pass:android",
                        "--key-pass", "pass:android",
                        "--v1-signing-enabled", "true",
                        "--v2-signing-enabled", "true",
                        apkFile.absolutePath
                    ),
                    workDir
                ),
                toolchain.apksigner, listOf("sign")
            )
        }
    }

    @Throws(IllegalStateException::class)
    private fun ensureDebugKeystore(context: Context, keytool: String): File {
        val ks = File(context.filesDir, "codex-debug.keystore")
        if (ks.isFile && ks.length() > 0) return ks
        if (ks.exists()) ks.delete()
        requireSuccess(
            runCommand(
                keytool,
                listOf(
                    "-genkeypair", "-v",
                    "-keystore", ks.absolutePath,
                    "-storepass", "android",
                    "-alias", "androiddebugkey",
                    "-keypass", "android",
                    "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "10950",
                    "-dname", "CN=Android Debug,O=Android,C=US"
                ),
                context.filesDir
            ),
            keytool, listOf("-genkeypair")
        )
        if (!ks.isFile || ks.length() <= 0) {
            throw IllegalStateException("keytool did not create the debug keystore; cannot sign the APK.")
        }
        return ks
    }

    // ---------- Generated shell sources ----------

    private fun escapeXml(s: String): String {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")
    }

    private fun buildManifestXml(config: ResolvedApkConfig, label: String): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="${config.applicationId}"
    android:versionCode="${config.versionCode}"
    android:versionName="${escapeXml(config.versionName)}">

    <uses-sdk android:minSdkVersion="${config.minSdk}" android:targetSdkVersion="${config.targetSdk}" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <application
        android:label="@string/app_name"
        android:allowBackup="true"
        android:supportsRtl="true"
        android:hardwareAccelerated="true"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|keyboardHidden"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
"""
    }

    private fun buildStringsXml(label: String): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">${escapeXml(label)}</string>
</resources>
"""
    }

    private fun buildShellActivityJava(applicationId: String, entryHtml: String): String {
        val safeEntry = entryHtml.replace("\"", "").replace("\\", "/").trimStart('/')
        return """package $applicationId;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Auto-generated WebView shell. Loads the bundled web app from assets. */
public class MainActivity extends Activity {
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        webView.setWebViewClient(new WebViewClient());
        setContentView(webView);
        webView.loadUrl("file:///android_asset/www/$safeEntry");
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
"""
    }

    // ---------- APK assembly with guaranteed 4-byte alignment ----------

    private data class PendingEntry(val name: String, val method: Int, val time: Long, val bytes: ByteArray)

    /**
     * Merges aapt2's base package with classes.dex into the final unsigned
     * APK. Uncompressed entries that Android requires aligned
     * (resources.arsc, *.so) are stored with padding so their data starts on
     * a 4-byte boundary. Re-running zipalign afterwards stays supported.
     */
    @Throws(IllegalStateException::class)
    private fun assembleFinalApk(baseApk: File, classesDex: File, outApk: File) {
        if (!baseApk.isFile || baseApk.length() <= 0) {
            throw IllegalStateException("aapt2 link did not produce a base package; packaging failed.")
        }
        val pending = mutableListOf<PendingEntry>()
        try {
            ZipFile(baseApk).use { zip ->
                for (entry in Collections.list(zip.entries())) {
                    if (entry.isDirectory) continue
                    if (entry.name == "classes.dex") continue
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    pending.add(PendingEntry(entry.name, entry.method, entry.time, bytes))
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException("Could not read aapt2 base package: ${e.message}")
        }
        pending.add(PendingEntry("classes.dex", 8, System.currentTimeMillis(), classesDex.readBytes()))

        if (outApk.exists()) outApk.delete()
        try {
            FileOutputStream(outApk).use { fos ->
                BufferedOutputStream(fos).use { bos ->
                    writeAlignedZip(bos, pending)
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException("Could not assemble final APK container: ${e.message}")
        }
        if (!outApk.isFile || outApk.length() <= 0) {
            throw IllegalStateException("APK assembly produced an empty file.")
        }
    }

    private fun needsAlignment(name: String): Boolean {
        return name == "resources.arsc" || name.endsWith(".so")
    }

    private data class WrittenEntry(
        val name: String, val method: Int, val time: Long,
        val crc: Long, val data: ByteArray, val uncompressedSize: Long,
        val localOffset: Long, val extra: ByteArray
    )

    private fun writeAlignedZip(out: OutputStream, pending: List<PendingEntry>) {
        val written = mutableListOf<WrittenEntry>()
        var offset = 0L
        for (p in pending) {
            val nameBytes = p.name.toByteArray(Charsets.UTF_8)
            val align = needsAlignment(p.name)
            val method = if (align) 0 else p.method
            val data: ByteArray
            val crc: Long
            if (method == 0) {
                data = p.bytes
                val c = CRC32()
                c.update(data)
                crc = c.value
            } else {
                data = deflate(p.bytes)
                val c = CRC32()
                c.update(p.bytes)
                crc = c.value
            }
            // Padding so data starts 4-byte aligned (STORED entries only).
            var extra = ByteArray(0)
            if (align) {
                val pad = ((4 - ((offset + 30 + nameBytes.size) % 4)) % 4).toInt()
                if (pad > 0) extra = ByteArray(pad)
            }
            val useUtf8 = nameBytes.any { it < 0 }
            val flags = if (useUtf8) 0x0800 else 0
            val (dosTime, dosDate) = dosDateTime(p.time)
            val localOffset = offset
            writeLe32(out, 0x04034b50L)
            writeLe16(out, 20)
            writeLe16(out, flags)
            writeLe16(out, method)
            writeLe16(out, dosTime)
            writeLe16(out, dosDate)
            writeLe32(out, crc)
            writeLe32(out, data.size.toLong())
            writeLe32(out, p.bytes.size.toLong())
            writeLe16(out, nameBytes.size)
            writeLe16(out, extra.size)
            out.write(nameBytes)
            out.write(extra)
            out.write(data)
            offset += 30 + nameBytes.size + extra.size + data.size
            written.add(WrittenEntry(p.name, method, p.time, crc, data, p.bytes.size.toLong(), localOffset, extra))
        }
        val centralStart = offset
        var centralSize = 0L
        for (w in written) {
            val nameBytes = w.name.toByteArray(Charsets.UTF_8)
            val useUtf8 = nameBytes.any { it < 0 }
            val flags = if (useUtf8) 0x0800 else 0
            val (dosTime, dosDate) = dosDateTime(w.time)
            writeLe32(out, 0x02014b50L)
            writeLe16(out, 20)
            writeLe16(out, 20)
            writeLe16(out, flags)
            writeLe16(out, w.method)
            writeLe16(out, dosTime)
            writeLe16(out, dosDate)
            writeLe32(out, w.crc)
            writeLe32(out, w.data.size.toLong())
            writeLe32(out, w.uncompressedSize)
            writeLe16(out, nameBytes.size)
            writeLe16(out, w.extra.size)
            writeLe16(out, 0)
            writeLe16(out, 0)
            writeLe16(out, 0)
            writeLe32(out, 0)
            writeLe32(out, w.localOffset)
            out.write(nameBytes)
            out.write(w.extra)
            centralSize += 46 + nameBytes.size + w.extra.size
        }
        writeLe32(out, 0x06054b50L)
        writeLe16(out, 0)
        writeLe16(out, 0)
        writeLe16(out, written.size)
        writeLe16(out, written.size)
        writeLe32(out, centralSize)
        writeLe32(out, centralStart)
        writeLe16(out, 0)
        out.flush()
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        try {
            deflater.setInput(bytes)
            deflater.finish()
            val bos = ByteArrayOutputStream(bytes.size)
            val buf = ByteArray(8192)
            while (!deflater.finished()) {
                bos.write(buf, 0, deflater.deflate(buf))
            }
            return bos.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun dosDateTime(millis: Long): Pair<Int, Int> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = if (millis < 0) System.currentTimeMillis() else millis
        var year = cal.get(Calendar.YEAR) - 1980
        if (year < 0) year = 0
        if (year > 127) year = 127
        val time = (cal.get(Calendar.HOUR_OF_DAY) shl 11) or
            (cal.get(Calendar.MINUTE) shl 5) or
            (cal.get(Calendar.SECOND) / 2)
        val date = (year shl 9) or
            ((cal.get(Calendar.MONTH) + 1) shl 5) or
            cal.get(Calendar.DAY_OF_MONTH)
        return time to date
    }

    private fun writeLe16(out: OutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
    }

    private fun writeLe32(out: OutputStream, v: Long) {
        out.write((v and 0xFF).toInt())
        out.write(((v ushr 8) and 0xFF).toInt())
        out.write(((v ushr 16) and 0xFF).toInt())
        out.write(((v ushr 24) and 0xFF).toInt())
    }

    // ------------------------------------------------------------------
    // Share / install entry points (system UI always confirms)
    // ------------------------------------------------------------------

    fun shareApk(context: Context, apkFile: File) {
        val contentUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_SUBJECT, "Generated Android APK: ${apkFile.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "Share APK with...")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    fun installApk(context: Context, apkFile: File) {
        val contentUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
    }
}
