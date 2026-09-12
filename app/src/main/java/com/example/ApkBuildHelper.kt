package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

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

    /**
     * Build-environment status for the UI. Legacy flag names
     * ([openJdkAvailable], [gradleAvailable], [buildToolsAvailable]) are kept
     * so existing callers keep working; prefer [ready]/[missingComponents].
     */
    data class ToolchainInfo(
        val termuxInstalled: Boolean,
        val shellAvailable: Boolean = false,
        val openJdkAvailable: Boolean,
        val jdkVersion: String? = null,
        val gradleAvailable: Boolean,
        val gradleVersion: String? = null,
        val buildToolsAvailable: Boolean,
        val sdkDir: String? = null,
        val platformInstalled: Boolean = false,
        val buildToolsInstalled: Boolean = false,
        val ready: Boolean = false,
        val missingComponents: List<String> = emptyList(),
        val details: String,
        val setupScript: String
    )

    fun checkToolchain(context: Context): ToolchainInfo {
        val s = AndroidBuildEnvironment.detect(context)
        val details = buildString {
            append("Build shell: ").append(if (s.shellAvailable) "Available" else "Blocked on this device (guided Termux setup offered)").append("\n")
            append("Termux App: ").append(if (s.termuxInstalled) "Installed" else "Not detected").append("\n")
            append("OpenJDK: ").append(s.jdkMajor?.let { "major $it" } ?: "Missing (one Termux command)").append("\n")
            append("Gradle: ").append(s.gradleVersion ?: "Missing (auto-installs on first build)").append("\n")
            append("Android SDK: ").append(s.sdkDir ?: "Not provisioned yet (auto-installs)").append("\n")
            append("SDK platform android-${AndroidBuildVersions.PLATFORM_API}: ").append(if (s.platformInstalled) "Installed" else "Missing (auto-installs)").append("\n")
            append("SDK build-tools ${AndroidBuildVersions.BUILD_TOOLS}: ").append(if (s.buildToolsInstalled) "Installed" else "Missing (auto-installs)").append("\n")
            append("Gradle pipeline (AGP ${AndroidBuildVersions.AGP}): ").append(if (s.ready) "READY" else "SETUP NEEDED (${s.missing.joinToString(", ")})")
        }
        return ToolchainInfo(
            termuxInstalled = s.termuxInstalled,
            shellAvailable = s.shellAvailable,
            openJdkAvailable = s.jdkMajor != null,
            jdkVersion = s.jdkMajor?.let { "major $it" },
            gradleAvailable = s.gradleBin != null,
            gradleVersion = s.gradleVersion,
            buildToolsAvailable = s.platformInstalled && s.buildToolsInstalled,
            sdkDir = s.sdkDir,
            platformInstalled = s.platformInstalled,
            buildToolsInstalled = s.buildToolsInstalled,
            ready = s.ready,
            missingComponents = s.missing,
            details = details,
            setupScript = AndroidBuildEnvironment.guidedManualScript(
                AndroidBuildEnvironment.sdkDir(context).absolutePath
            )
        )
    }

    // ------------------------------------------------------------------
    // HopWeb-style Gradle build pipeline
    // ------------------------------------------------------------------

    /**
     * Builds a genuine signed APK: validates config, ensures the Android
     * build environment (auto-setup when needed), stages a temporary Gradle
     * wrapper project around the web files (user sources are never modified),
     * runs the real Gradle build, validates the APK, and saves it into the
     * project folder.
     *
     * Success is reported ONLY for an APK that passes automated
     * installability validation. Any failure throws with the real error —
     * a fake APK is never created or reported.
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
        val configErrors = config.errors.toMutableList()
        var releaseKeystore: ReleaseKeystore? = null
        if (config.buildType == ApkBuildType.RELEASE) {
            val ksPath = options.releaseKeystorePath?.ifBlank { null }
                ?: File(context.filesDir, "release.keystore").takeIf { it.isFile }?.absolutePath
            if (ksPath == null || !File(ksPath).isFile) {
                configErrors.add(
                    "Release build requested but no signing keystore is configured. " +
                        "Provide releaseKeystorePath (+ alias + passwords) or build a debug APK instead."
                )
            } else if (options.releaseStorePassword.isNullOrBlank()) {
                configErrors.add("Release build is missing the keystore (store) password.")
            } else {
                releaseKeystore = ReleaseKeystore(
                    path = ksPath,
                    alias = options.releaseKeyAlias?.ifBlank { null } ?: "upload",
                    storePassword = options.releaseStorePassword,
                    keyPassword = options.releaseKeyPassword?.ifBlank { null } ?: options.releaseStorePassword
                )
            }
        }
        if (configErrors.isNotEmpty()) {
            throw IllegalStateException(
                "Invalid APK configuration; refusing to build:\n- " + configErrors.joinToString("\n- ")
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

        // The wrapper project lives in cache, fully separate from user sources.
        // The provisioned SDK caps the target platform; clamp with an explicit note.
        val gradleTarget = minOf(config.targetSdk, AndroidBuildVersions.COMPILE_SDK)
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

        // ---- Step 2: environment detect + automatic setup when needed ----
        onProgress(2, totalSteps, "Build Environment", "Checking Android build environment (JDK, Gradle, SDK)...")
        val env = try {
            var status = AndroidBuildEnvironment.detect(context)
            if (!status.ready) {
                onProgress(2, totalSteps, "Build Environment", "Missing: ${status.missing.joinToString(", ")}. Running automatic setup (cached afterwards)...")
                status = AndroidBuildEnvironment.setup(context) { _, message ->
                    message.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                        onProgress(2, totalSteps, "Build Environment", line.trim().take(300))
                    }
                }
            } else {
                onProgress(2, totalSteps, "Build Environment", "Cached build environment ready (JDK ${status.jdkMajor}, Gradle ${status.gradleVersion}). No downloads needed.")
            }
            status
        } catch (e: IOException) {
            throw IllegalStateException("Build environment is not ready and automatic setup failed:\n${e.message}")
        }
        if (!env.ready) {
            throw IllegalStateException(
                "Build environment is still incomplete (${env.missing.joinToString(", ")}). " +
                    "Complete the guided Termux setup, then rebuild."
            )
        }
        val sdkDir = env.sdkDir ?: AndroidBuildEnvironment.sdkDir(context).absolutePath
        onProgress(2, totalSteps, "Build Environment", "Environment ready: JDK ${env.jdkMajor}, Gradle ${env.gradleVersion}, SDK ${sdkDir}")
        delay(200)

        // ---- Step 3: stage the temporary Gradle wrapper project ----
        onProgress(3, totalSteps, "Wrapper Project", "Packaging web project into a temporary Android project...")
        val wrapperDir = File(buildDir, "wrapper")
        if (wrapperDir.exists()) wrapperDir.deleteRecursively()
        try {
            GradleWrapperProject.generate(
                wrapperDir,
                WrapperProjectInput(
                    applicationId = config.applicationId,
                    appName = config.projectDirName,
                    versionCode = config.versionCode,
                    versionName = config.versionName,
                    minSdk = config.minSdk,
                    targetSdk = gradleTarget,
                    buildType = config.buildType,
                    entryHtml = entryHtml,
                    assets = packagingFiles,
                    sdkDir = sdkDir,
                    releaseKeystore = releaseKeystore
                )
            )
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Could not stage the Android wrapper project: ${e.message}")
        }
        if (gradleTarget != config.targetSdk) {
            onProgress(3, totalSteps, "Wrapper Project", "Note: targetSdk ${config.targetSdk} clamped to provisioned platform $gradleTarget.")
        }
        onProgress(3, totalSteps, "Wrapper Project", "Wrapper ready: ${config.applicationId}, AGP ${AndroidBuildVersions.AGP}, entry $entryHtml")
        delay(200)

        // ---- Step 4: run the real Gradle build ----
        onProgress(4, totalSteps, "Gradle Build", "Starting :app:assemble${variant.replaceFirstChar { it.uppercase() }} (dependencies are cached after the first build)...")
        val gradleLog = runGradleBuild(context, env, wrapperDir, variant) { taskLine ->
            onProgress(4, totalSteps, "Gradle Build", taskLine)
        }
        onProgress(4, totalSteps, "Gradle Build", "Gradle build finished successfully.")
        delay(200)

        // ---- Step 5: locate the genuine APK (AGP signs + aligns it) ----
        onProgress(5, totalSteps, "Locate APK", "Locating built APK under app/build/outputs/apk/$variant/...")
        val builtApk = findBuiltApk(wrapperDir, variant)
            ?: throw IllegalStateException(
                "Gradle reported success but no APK was produced under app/build/outputs/apk/$variant/.\n" +
                    gradleLog.takeLast(3000)
            )
        onProgress(5, totalSteps, "Locate APK", "Found ${builtApk.name} (${builtApk.length()} bytes)")
        val signedApk = File(outputApkDir, config.apkFileName)
        if (signedApk.exists()) signedApk.delete()
        builtApk.copyTo(signedApk)
        delay(200)

        // ---- Step 6: validate + save (success ONLY if valid) ----
        onProgress(6, totalSteps, "Validate & Save", "Running automated installability validation...")
        delay(250)
        val validation = ApkValidator.validate(signedApk, config.applicationId, ApkValidator.SignatureMode.STRICT)
        for (check in validation.checks) {
            onProgress(
                6, totalSteps, "Validate & Save",
                "${if (check.passed) "PASS" else "FAIL"} ${check.name}: ${check.detail}"
            )
        }
        if (!validation.valid) {
            try { signedApk.delete() } catch (e: Exception) { /* ignore */ }
            throw IllegalStateException(
                "APK validation failed; refusing to report success (this APK would show 'App not installed'):\n" +
                    ApkValidator.summarize(validation)
            )
        }
        onProgress(6, totalSteps, "Validate & Save", "Validation passed: installable ${config.apkFileName} (${signedApk.length()} bytes)")

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
                onProgress(6, totalSteps, "Validate & Save", "Saved APK into project folder: $savedProjectRelPath")
            } catch (e: Exception) {
                onProgress(6, totalSteps, "Validate & Save", "Warning: could not save APK into project folder (${e.message}). Cache + Download copies retained.")
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
            onProgress(6, totalSteps, "Validate & Save", "Saved visible copy: ${dest.absolutePath}")
        } catch (e: Exception) {
            onProgress(6, totalSteps, "Validate & Save", "Warning: could not write Download copy (${e.message}).")
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
     * Backwards-compatible entry point. Routes to the validated Gradle
     * pipeline with default (debug) options.
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
    // Gradle execution
    // ------------------------------------------------------------------

    /**
     * Runs `:app:assemble<Variant>` inside the staged wrapper project with a
     * fully configured environment (ANDROID_HOME, JAVA_HOME, GRADLE_USER_HOME,
     * ANDROID_USER_HOME). Streams `> Task` lines to [onTaskLine] for live
     * in-app progress. Returns the full log tail for diagnostics. Throws with
     * the real Gradle error section when the build fails.
     */
    @Throws(IllegalStateException::class, IOException::class)
    private fun runGradleBuild(
        context: Context,
        env: BuildEnvStatus,
        wrapperDir: File,
        variant: String,
        onTaskLine: (String) -> Unit
    ): String {
        val gradleBin = env.gradleBin
            ?: throw IllegalStateException("No compatible Gradle distribution is available.")
        val sdkDir = env.sdkDir ?: AndroidBuildEnvironment.sdkDir(context).absolutePath
        val jdkHome = env.jdkBinDir?.let { File(it).parent } ?: ""
        val pathParts = mutableListOf<String>()
        env.jdkBinDir?.let { pathParts.add(it) }
        pathParts.add(File(sdkDir, "cmdline-tools/latest/bin").absolutePath)
        try {
            System.getenv("PATH")?.let { pathParts.add(it) }
        } catch (e: Exception) { /* ignore */ }
        val processEnv = mapOf(
            "ANDROID_HOME" to sdkDir,
            "ANDROID_SDK_ROOT" to sdkDir,
            "JAVA_HOME" to jdkHome,
            "ANDROID_USER_HOME" to AndroidBuildEnvironment.androidUserHome(context).absolutePath,
            "GRADLE_USER_HOME" to AndroidBuildEnvironment.gradleHome(context).absolutePath,
            "PATH" to pathParts.joinToString(":")
        )
        AndroidBuildEnvironment.gradleHome(context).mkdirs()
        AndroidBuildEnvironment.androidUserHome(context).mkdirs()

        val task = ":app:assemble${variant.replaceFirstChar { it.uppercase() }}"
        val args = listOf("-p", wrapperDir.absolutePath, task, "--console=plain")
        val fullLog = StringBuilder()
        var taskCount = 0
        val proc = try {
            ProcessBuilder(listOf(gradleBin) + args).directory(wrapperDir).redirectErrorStream(true).apply {
                environment().putAll(processEnv)
            }.start()
        } catch (e: Exception) {
            throw IllegalStateException("Could not launch the Gradle build (is the build environment executable on this device?): ${e.message}")
        }
        val reader = Thread {
            try {
                proc.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { raw ->
                    val line = raw.trimEnd()
                    synchronized(fullLog) {
                        if (fullLog.length < 60000) fullLog.append(line).append('\n')
                    }
                    val trimmed = line.trim()
                    if (trimmed.startsWith("> Task :app:")) {
                        taskCount++
                        onTaskLine(trimmed.take(220))
                    } else if (trimmed.startsWith("BUILD ")) {
                        onTaskLine(trimmed.take(220))
                    } else if (taskCount % 15 == 0 && trimmed.isNotEmpty() && trimmed.length < 160 &&
                        (trimmed.startsWith("Download") || trimmed.startsWith("Configuration"))
                    ) {
                        onTaskLine(trimmed)
                    }
                }
            } catch (e: Exception) { /* reader interrupted */ }
        }
        reader.isDaemon = true
        reader.start()
        val finished = try {
            proc.waitFor(1500, TimeUnit.SECONDS)
        } catch (e: Exception) {
            false
        }
        if (!finished) {
            try { proc.destroyForcibly() } catch (e: Exception) { /* ignore */ }
            throw IllegalStateException("Gradle build timed out after 25 minutes. Check the device resources and retry.")
        }
        try { reader.join(5000) } catch (e: Exception) { /* ignore */ }
        val log = synchronized(fullLog) { fullLog.toString() }
        if (proc.exitValue() != 0) {
            throw IllegalStateException("Gradle build failed:\n${extractGradleError(log)}")
        }
        return log.takeLast(4000)
    }

    /** Pulls the actionable `* What went wrong:` section out of Gradle output. */
    fun extractGradleError(log: String): String {
        val lines = log.lines()
        val start = lines.indexOfFirst { it.contains("* What went wrong:") || it.startsWith("FAILURE:") }
        if (start >= 0) {
            val end = (start until lines.size).firstOrNull {
                it.contains("* Try:") || it.contains("BUILD FAILED")
            }?.let { it + 1 } ?: lines.size
            return lines.subList(start, minOf(end, start + 60)).joinToString("\n").trim().take(4000)
        }
        val failed = lines.filter { it.contains("FAILED") || it.contains("error:") || it.contains("Exception") }
        if (failed.isNotEmpty()) return failed.takeLast(20).joinToString("\n").take(4000)
        return lines.takeLast(40).joinToString("\n").trim().take(4000)
    }

    private fun findBuiltApk(wrapperDir: File, variant: String): File? {
        val outDir = File(wrapperDir, "app/build/outputs/apk/$variant")
        val apks = try {
            outDir.listFiles { f -> f.isFile && f.name.endsWith(".apk") }?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        return apks.filter { it.length() > 0 }.maxByOrNull { it.length() }
    }

    // ------------------------------------------------------------------
    // Share / install / location entry points (system UI always confirms)
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

    /** Opens the system Downloads UI (where the visible APK copy lives). */
    fun openDownloads(context: Context): Boolean {
        return try {
            val intent = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Launches the Termux app so the user can run the guided setup script. */
    fun openTermuxApp(context: Context): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage("com.termux")
                ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Best-effort "reveal in Files" for the SAF project folder holding the
     * APK. Returns false when the system Files app cannot open the location
     * (callers fall back to Downloads).
     */
    fun openProjectLocation(context: Context, projectRootUriStr: String): Boolean {
        return try {
            val uri = Uri.parse(projectRootUriStr)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "vnd.android.document/directory")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            try {
                val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(projectRootUriStr)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
                true
            } catch (e2: Exception) {
                false
            }
        }
    }
}
