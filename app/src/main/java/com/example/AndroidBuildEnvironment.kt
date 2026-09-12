package com.example

import android.content.Context
import android.content.pm.PackageManager
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Version matrix for the on-device Android build environment. These versions
 * are mutually compatible (AGP <-> Gradle <-> JDK) and are pinned so builds
 * are reproducible and SDK components can be cached between builds.
 */
object AndroidBuildVersions {
    const val AGP = "8.5.2"
    const val GRADLE = "8.7"
    const val GRADLE_MAJOR_REQUIRED = 8
    const val GRADLE_MINOR_MIN = 7
    const val JDK_MIN_MAJOR = 17
    const val PLATFORM_API = 34
    const val BUILD_TOOLS = "34.0.0"
    const val COMPILE_SDK = 34

    const val CMDLINE_TOOLS_URL =
        "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
    const val GRADLE_DIST_URL =
        "https://services.gradle.org/distributions/gradle-8.7-bin.zip"

    const val SDK_PLATFORM_PKG = "platforms;android-34"
    const val SDK_BUILD_TOOLS_PKG = "build-tools;34.0.0"

    /** Extracts "8.7" / "8.7.1" from `gradle --version` output (or a bare version). */
    fun parseGradleVersion(output: String): String? {
        val m = Regex("Gradle\\s+(\\d+)\\.(\\d+)(?:\\.(\\d+))?").find(output)
            ?: Regex("^\\s*(\\d+)\\.(\\d+)(?:\\.(\\d+))?\\s*$").find(output)
            ?: return null
        val major = m.groupValues[1]
        val minor = m.groupValues[2]
        val patch = m.groupValues[3]
        return if (patch.isNotEmpty()) "$major.$minor.$patch" else "$major.$minor"
    }

    /** True for Gradle 8.7+ (below 9.x), which is what AGP 8.5.2 requires. */
    fun isGradleCompatible(version: String?): Boolean {
        if (version == null) return false
        val parts = version.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size < 2) return false
        if (parts[0] != GRADLE_MAJOR_REQUIRED) return false
        return parts[1] >= GRADLE_MINOR_MIN
    }

    /**
     * Major version from `java -version` output, e.g. `openjdk version "17.0.9"`
     * -> 17, legacy `java version "1.8.0_382"` -> 8. Null when unparseable.
     */
    fun parseJavaMajor(output: String): Int? {
        val m = Regex("version\\s+\"(\\d+)(?:\\.(\\d+))?").find(output) ?: return null
        val first = m.groupValues[1].toIntOrNull() ?: return null
        if (first == 1) return m.groupValues[2].toIntOrNull()
        return first
    }
}

/** Raw result of a shell invocation. Never throws for content problems. */
internal data class ShellResult(val exitCode: Int, val output: String)

/**
 * Minimal shell runner. All build/setup commands go through here so output
 * is captured and surfaced (never a silent or fake success).
 */
internal object Shell {
    fun exec(
        exe: String,
        args: List<String>,
        workDir: File,
        env: Map<String, String> = emptyMap(),
        timeoutSec: Long = 300,
        stdin: String? = null
    ): ShellResult {
        val pb = ProcessBuilder(listOf(exe) + args).directory(workDir).redirectErrorStream(true)
        if (env.isNotEmpty()) pb.environment().putAll(env)
        val proc = pb.start()
        if (stdin != null) {
            try {
                proc.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(stdin) }
            } catch (e: Exception) {
                // Process may have exited already; ignore.
            }
        }
        val sb = StringBuilder()
        val t = Thread {
            try {
                proc.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    synchronized(sb) {
                        if (sb.length < 12000) sb.append(line).append('\n')
                    }
                }
            } catch (e: Exception) {
                // Reader interrupted; ignore.
            }
        }
        t.isDaemon = true
        t.start()
        val done = try {
            proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        } catch (e: Exception) {
            false
        }
        if (!done) {
            try { proc.destroyForcibly() } catch (e: Exception) { /* ignore */ }
            throw IOException("Command timed out after ${timeoutSec}s: $exe ${args.joinToString(" ")}")
        }
        try { t.join(5000) } catch (e: Exception) { /* ignore */ }
        return ShellResult(proc.exitValue(), synchronized(sb) { sb.toString() })
    }

    fun requireOk(result: ShellResult, what: String): String {
        if (result.exitCode != 0) {
            throw IOException("$what failed (exit ${result.exitCode}):\n${result.output.takeLast(3000).trim()}")
        }
        return result.output
    }

    /** True when this process can spawn shell commands at all. */
    fun probe(): Boolean {
        return try {
            exec("sh", listOf("-c", "echo ok"), File("/"), timeoutSec = 10).exitCode == 0
        } catch (e: Exception) {
            false
        }
    }
}

/** Point-in-time view of the on-device Android build environment. */
data class BuildEnvStatus(
    val shellAvailable: Boolean,
    val termuxInstalled: Boolean,
    val jdkBinDir: String?,
    val jdkMajor: Int?,
    val gradleBin: String?,
    val gradleVersion: String?,
    val sdkDir: String?,
    val androidJar: String?,
    val platformInstalled: Boolean,
    val buildToolsInstalled: Boolean,
    val ready: Boolean,
    val missing: List<String>
)

/**
 * Detects, provisions and caches a complete Android build environment:
 * JDK 17+, a compatible Gradle distribution, and an SDK with platform 34 +
 * build-tools 34.0.0 under one consistent location
 * (`filesDir/codex-android-env`), so nothing is re-downloaded every build.
 *
 * Everything the app itself can provision (SDK cmdline tools, SDK packages,
 * Gradle distribution) is installed automatically. A JDK cannot be
 * downloaded as a plain archive for Android (it must come from Termux
 * packages), so a missing JDK produces a one-command guided Termux setup
 * instead of a bare "binary missing" error.
 */
object AndroidBuildEnvironment {

    const val ENV_DIR_NAME = "codex-android-env"
    private const val READY_MARKER = ".ready-v1"

    fun envRoot(context: Context): File = File(context.filesDir, ENV_DIR_NAME)

    fun sdkDir(context: Context): File = File(envRoot(context), "sdk")

    fun gradleHome(context: Context): File = File(envRoot(context), "gradle-home")

    fun androidUserHome(context: Context): File = File(envRoot(context), ".android")

    fun cachedGradleBin(context: Context): File =
        File(envRoot(context), "gradle-${AndroidBuildVersions.GRADLE}/bin/gradle")

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
                // keep searching
            }
        }
        // Well-known Termux location even when it is not on PATH.
        try {
            val termuxBin = File("/data/data/com.termux/files/usr/bin", binaryName)
            if (termuxBin.isFile && termuxBin.canExecute()) return termuxBin.absolutePath
        } catch (e: Exception) {
            // ignore
        }
        return null
    }

    fun isTermuxInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.termux", PackageManager.GET_ACTIVITIES)
            true
        } catch (e: Exception) {
            File("/data/data/com.termux/files/usr/bin/bash").exists()
        }
    }

    /** Highest android.jar across the managed SDK plus well-known locations. */
    fun findAndroidJar(context: Context): String? {
        val roots = mutableListOf<String>()
        roots.add(sdkDir(context).absolutePath)
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

    fun shBin(): String = findTool("sh") ?: "/system/bin/sh"

    fun detect(context: Context): BuildEnvStatus {
        val shellAvailable = Shell.probe()
        val termuxInstalled = isTermuxInstalled(context)

        var jdkBinDir: String? = null
        var jdkMajor: Int? = null
        findTool("java")?.let { javaBin ->
            try {
                val out = Shell.exec(javaBin, listOf("-version"), context.cacheDir, timeoutSec = 20).output
                val major = AndroidBuildVersions.parseJavaMajor(out)
                if (major != null && major >= AndroidBuildVersions.JDK_MIN_MAJOR) {
                    jdkBinDir = File(javaBin).parent
                    jdkMajor = major
                }
            } catch (e: Exception) {
                // java unusable; treated as missing below
            }
        }

        var gradleBin: String? = null
        var gradleVersion: String? = null
        val candidates = mutableListOf<String>()
        findTool("gradle")?.let { candidates.add(it) }
        cachedGradleBin(context).takeIf { it.isFile }?.let { candidates.add(it.absolutePath) }
        for (candidate in candidates.distinct()) {
            try {
                val out = Shell.exec(candidate, listOf("--version"), context.cacheDir, timeoutSec = 60).output
                val v = AndroidBuildVersions.parseGradleVersion(out)
                if (AndroidBuildVersions.isGradleCompatible(v)) {
                    gradleBin = candidate
                    gradleVersion = v
                    break
                }
            } catch (e: Exception) {
                // try next candidate
            }
        }

        val sdk = sdkDir(context)
        val platformJar = File(sdk, "platforms/android-${AndroidBuildVersions.PLATFORM_API}/android.jar")
        val platformInstalled = platformJar.isFile && platformJar.length() > 0
        val btAapt2 = File(sdk, "build-tools/${AndroidBuildVersions.BUILD_TOOLS}/aapt2")
        val buildToolsInstalled = btAapt2.isFile
        val sdkDirStr = if (File(sdk, "cmdline-tools/latest/bin/sdkmanager").isFile || platformInstalled) {
            sdk.absolutePath
        } else {
            null
        }
        val androidJar = findAndroidJar(context)

        val missing = mutableListOf<String>()
        if (jdkBinDir == null) missing.add("OpenJDK 17+ (needs one Termux command)")
        if (gradleBin == null) missing.add("Gradle ${AndroidBuildVersions.GRADLE} (auto-installs)")
        if (!platformInstalled) missing.add("SDK platform android-${AndroidBuildVersions.PLATFORM_API} (auto-installs)")
        if (!buildToolsInstalled) missing.add("SDK build-tools ${AndroidBuildVersions.BUILD_TOOLS} (auto-installs)")

        return BuildEnvStatus(
            shellAvailable = shellAvailable,
            termuxInstalled = termuxInstalled,
            jdkBinDir = jdkBinDir,
            jdkMajor = jdkMajor,
            gradleBin = gradleBin,
            gradleVersion = gradleVersion,
            sdkDir = sdkDirStr,
            androidJar = androidJar,
            platformInstalled = platformInstalled,
            buildToolsInstalled = buildToolsInstalled,
            ready = missing.isEmpty(),
            missing = missing
        )
    }

    fun isMarkerFresh(context: Context): Boolean {
        return try {
            val marker = File(envRoot(context), READY_MARKER)
            marker.isFile && marker.readText().contains(AndroidBuildVersions.AGP) &&
                marker.readText().contains(AndroidBuildVersions.GRADLE)
        } catch (e: Exception) {
            false
        }
    }

    private fun writeMarker(context: Context) {
        try {
            File(envRoot(context), READY_MARKER).writeText(
                "agp=${AndroidBuildVersions.AGP}\n" +
                    "gradle=${AndroidBuildVersions.GRADLE}\n" +
                    "platform=${AndroidBuildVersions.PLATFORM_API}\n" +
                    "build-tools=${AndroidBuildVersions.BUILD_TOOLS}\n"
            )
        } catch (e: Exception) {
            // Marker is best-effort caching only.
        }
    }

    /**
     * Guided one-command Termux setup used when automatic provisioning is
     * impossible (e.g. no JDK and no shell access). Saved next to the build
     * output and shown in-app; the user runs it once in Termux.
     */
    fun guidedManualScript(sdkPath: String): String {
        return """
            # Codex Mobile - one-time Android build environment setup (run in Termux)
            pkg update -y
            pkg install -y openjdk-17 git unzip
            export ANDROID_HOME="$sdkPath"
            export ANDROID_SDK_ROOT="$sdkPath"
            mkdir -p "${'$'}ANDROID_HOME/cmdline-tools"
            cd "${'$'}ANDROID_HOME/cmdline-tools"
            curl -o tools.zip ${AndroidBuildVersions.CMDLINE_TOOLS_URL}
            unzip -q tools.zip -d latest-tmp && mkdir -p latest && mv latest-tmp/cmdline-tools/* latest/ && rm -rf latest-tmp tools.zip
            yes | latest/bin/sdkmanager --sdk_root="${'$'}ANDROID_HOME" --licenses
            latest/bin/sdkmanager --sdk_root="${'$'}ANDROID_HOME" "${AndroidBuildVersions.SDK_PLATFORM_PKG}" "${AndroidBuildVersions.SDK_BUILD_TOOLS_PKG}"
            echo "Setup complete. Rebuild the APK from the app."
        """.trimIndent()
    }

    /**
     * Automatically provisions every missing component the app can install
     * itself. Progress is reported via [onEvent] as (phase, message) pairs.
     * Returns a fresh [BuildEnvStatus]; throws [IOException] with the real
     * cause (plus guided Termux steps when automation is impossible).
     */
    @Throws(IOException::class)
    fun setup(context: Context, onEvent: (phase: String, message: String) -> Unit): BuildEnvStatus {
        var status = detect(context)
        if (status.ready && isMarkerFresh(context)) {
            onEvent("environment", "Cached build environment is ready (no downloads needed).")
            return status
        }
        if (!status.shellAvailable) {
            throw IOException(
                "Automatic setup needs shell access, which this device blocks.\n" +
                    "Run this once in Termux instead (one command each), then rebuild:\n" +
                    guidedManualScript(sdkDir(context).absolutePath)
            )
        }

        // 1. JDK (can only come from Termux packages on Android).
        if (status.jdkBinDir == null) {
            onEvent("environment", "JDK 17+ not found. Trying automatic install via Termux packages...")
            val pkg = findTool("pkg")
            if (pkg == null) {
                throw IOException(
                    "OpenJDK 17+ is required but was not found, and the Termux `pkg` installer is not reachable.\n" +
                        "Run this once in Termux, then rebuild:\n" +
                        guidedManualScript(sdkDir(context).absolutePath)
                )
            }
            val out = Shell.exec(pkg, listOf("install", "-y", "openjdk-17"), context.cacheDir, timeoutSec = 600)
            if (out.exitCode != 0) {
                throw IOException("Automatic OpenJDK install failed:\n${out.output.takeLast(2000)}")
            }
            status = detect(context)
            if (status.jdkBinDir == null) {
                throw IOException("OpenJDK install finished but `java -version` is still unusable. Please verify the Termux JDK manually.")
            }
            onEvent("environment", "OpenJDK installed (major ${status.jdkMajor}).")
        } else {
            onEvent("environment", "OpenJDK found (major ${status.jdkMajor}).")
        }

        // 2. SDK command-line tools (downloaded + cached by the app itself).
        val sdk = sdkDir(context)
        val sdkmanager = File(sdk, "cmdline-tools/latest/bin/sdkmanager")
        if (!sdkmanager.isFile) {
            onEvent("environment", "Downloading Android SDK command-line tools (one-time, ~150 MB)...")
            val zip = File(envRoot(context), "cmdline-tools.zip")
            downloadFile(AndroidBuildVersions.CMDLINE_TOOLS_URL, zip) { read, total ->
                if (total > 0) onEvent("environment", "Downloading SDK tools: ${read * 100 / total}%")
            }
            onEvent("environment", "Unpacking SDK command-line tools...")
            val tmp = File(sdk, ".tmp-ctl")
            if (tmp.exists()) tmp.deleteRecursively()
            unzip(zip, tmp)
            val latest = File(sdk, "cmdline-tools/latest")
            if (latest.exists()) latest.deleteRecursively()
            latest.parentFile?.mkdirs()
            val nested = File(tmp, "cmdline-tools")
            if (!nested.isDirectory || !nested.renameTo(latest)) {
                throw IOException("SDK tools archive had an unexpected layout; setup cannot continue.")
            }
            tmp.deleteRecursively()
            try { zip.delete() } catch (e: Exception) { /* ignore */ }
            makeExecutableRecursive(File(latest, "bin"))
        } else {
            onEvent("environment", "SDK command-line tools present.")
        }

        // 3. Licenses (written directly so sdkmanager never blocks on input).
        writeSdkLicenses(sdk)

        // 4. Platform + build-tools via sdkmanager.
        status = detect(context)
        if (!status.platformInstalled || !status.buildToolsInstalled) {
            onEvent("environment", "Installing SDK platform + build-tools (one-time download)...")
            val sh = shBin()
            val sm = File(sdk, "cmdline-tools/latest/bin/sdkmanager").absolutePath
            val out = Shell.exec(
                sh,
                listOf(sm, "--sdk_root=${sdk.absolutePath}", AndroidBuildVersions.SDK_PLATFORM_PKG, AndroidBuildVersions.SDK_BUILD_TOOLS_PKG),
                context.cacheDir,
                timeoutSec = 1500
            )
            val tail = out.output.takeLast(1500)
            if (tail.isNotBlank()) onEvent("environment", tail.lines().takeLast(4).joinToString("\n"))
            if (out.exitCode != 0) {
                throw IOException("SDK package install failed:\n${out.output.takeLast(2500)}")
            }
        }
        status = detect(context)
        if (!status.platformInstalled || !status.buildToolsInstalled) {
            throw IOException("SDK packages did not install correctly; please run the guided Termux setup shown in the environment card.")
        }
        onEvent("environment", "SDK platform android-${AndroidBuildVersions.PLATFORM_API} + build-tools ready.")

        // 5. Gradle distribution (downloaded + cached by the app itself).
        if (status.gradleBin == null) {
            onEvent("environment", "Downloading Gradle ${AndroidBuildVersions.GRADLE} (one-time, ~120 MB)...")
            val zip = File(envRoot(context), "gradle-dist.zip")
            downloadFile(AndroidBuildVersions.GRADLE_DIST_URL, zip) { read, total ->
                if (total > 0) onEvent("environment", "Downloading Gradle: ${read * 100 / total}%")
            }
            onEvent("environment", "Unpacking Gradle...")
            unzip(zip, envRoot(context))
            try { zip.delete() } catch (e: Exception) { /* ignore */ }
            makeExecutableRecursive(File(cachedGradleBin(context).parentFile?.absolutePath ?: ""))
            status = detect(context)
            if (status.gradleBin == null) {
                throw IOException("Gradle download finished but the distribution is unusable.")
            }
        }
        onEvent("environment", "Gradle ready (${status.gradleVersion}).")

        writeMarker(context)
        onEvent("environment", "Build environment ready and cached.")
        return detect(context)
    }

    // ---------- File helpers ----------

    @Throws(IOException::class)
    fun downloadFile(url: String, dest: File, onProgress: (read: Long, total: Long) -> Unit) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parent, dest.name + ".part")
        if (tmp.exists()) tmp.delete()
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = true
            conn.connect()
            if (conn.responseCode !in 200..299) {
                throw IOException("Download failed: HTTP ${conn.responseCode} for $url")
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(65536)
                    var read = 0L
                    var lastPct = -1L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = read * 100 / total
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(read, total)
                            }
                        } else {
                            onProgress(read, total)
                        }
                    }
                }
            }
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { /* ignore */ }
        }
        if (dest.exists() && !dest.delete()) throw IOException("Cannot replace $dest")
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    @Throws(IOException::class)
    fun unzip(zip: File, destDir: File) {
        destDir.mkdirs()
        ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = File(destDir, entry.name)
                if (!out.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) {
                    throw IOException("Unsafe archive path: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    fun makeExecutableRecursive(dir: File) {
        try {
            dir.walkTopDown().forEach { f ->
                try {
                    if (f.isFile) f.setExecutable(true)
                } catch (e: Exception) { /* ignore */ }
            }
        } catch (e: Exception) { /* ignore */ }
    }

    private fun writeSdkLicenses(sdk: File) {
        try {
            val licenses = File(sdk, "licenses")
            licenses.mkdirs()
            // Well-known public SDK license hashes (same text sdkmanager writes on `yes | --licenses`).
            File(licenses, "android-sdk-license").writeText(
                "8933bad161af4178b1185d1a37fbf41ea5269c55\n" +
                    "d56f5187479451eabfb3366815c9858a9f3e733\n" +
                    "24333f8a63e8e5da33651efae74a5bb68c6c64\n"
            )
            File(licenses, "android-sdk-preview-license").writeText(
                "84831b9409646a918e5909a294b1c3964619c2d\n"
            )
        } catch (e: Exception) {
            // Best effort; sdkmanager will prompt otherwise and fail loudly.
        }
    }
}
