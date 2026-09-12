package com.example

/**
 * Build variant for generated APKs. Debug APKs are signed with an auto-generated
 * debug key so they install directly on the user's own device. Release APKs
 * require a caller-supplied signing keystore and are never signed implicitly.
 */
enum class ApkBuildType {
    DEBUG,
    RELEASE;

    companion object {
        fun fromString(value: String?): ApkBuildType {
            return if (value != null && value.equals("release", ignoreCase = true)) RELEASE else DEBUG
        }
    }
}

/**
 * Caller-supplied overrides for an APK build. Any null field falls back to a
 * validated default derived from the project name.
 *
 * JSON contract (all fields optional):
 * {
 *   "buildType": "debug" | "release",
 *   "applicationId": "com.example.myapp",
 *   "versionCode": 3,
 *   "versionName": "1.2.0",
 *   "minSdk": 24,
 *   "targetSdk": 34,
 *   "projectRootUri": "content://...",
 *   "releaseKeystorePath": "/path/to/key.jks",
 *   "releaseKeyAlias": "upload",
 *   "releaseStorePassword": "...",
 *   "releaseKeyPassword": "..."
 * }
 */
data class ApkBuildOptions(
    val buildType: ApkBuildType = ApkBuildType.DEBUG,
    val applicationId: String? = null,
    val versionCode: Int? = null,
    val versionName: String? = null,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    val projectRootUri: String? = null,
    val releaseKeystorePath: String? = null,
    val releaseKeyAlias: String? = null,
    val releaseStorePassword: String? = null,
    val releaseKeyPassword: String? = null
) {
    companion object {
        fun fromJson(json: String?): ApkBuildOptions {
            if (json.isNullOrBlank()) return ApkBuildOptions()
            return try {
                ApkBuildOptions(
                    buildType = ApkBuildType.fromString(optString(json, "buildType")),
                    applicationId = optString(json, "applicationId"),
                    versionCode = optInt(json, "versionCode"),
                    versionName = optString(json, "versionName"),
                    minSdk = optInt(json, "minSdk"),
                    targetSdk = optInt(json, "targetSdk"),
                    projectRootUri = optString(json, "projectRootUri"),
                    releaseKeystorePath = optString(json, "releaseKeystorePath"),
                    releaseKeyAlias = optString(json, "releaseKeyAlias"),
                    releaseStorePassword = optString(json, "releaseStorePassword"),
                    releaseKeyPassword = optString(json, "releaseKeyPassword")
                )
            } catch (e: Exception) {
                ApkBuildOptions()
            }
        }

        // Minimal flat-JSON readers. Deliberately dependency-free (no org.json)
        // so this class stays usable from plain JVM unit tests.
        private fun optString(json: String, key: String): String? {
            val match = Regex("\"$key\"\\s*:\\s*\"((?:\\\\\"|[^\"])*)\"").find(json) ?: return null
            val unescaped = match.groupValues[1]
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
            return if (unescaped.isEmpty()) null else unescaped
        }

        private fun optInt(json: String, key: String): Int? {
            return Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(json)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
        }
    }
}

/**
 * Fully resolved, validated build configuration. [errors] is empty when the
 * configuration is safe to build; otherwise the build must be aborted and the
 * errors surfaced to the user instead of producing an artifact.
 */
data class ResolvedApkConfig(
    val projectDirName: String,
    val applicationId: String,
    val versionCode: Int,
    val versionName: String,
    val minSdk: Int,
    val targetSdk: Int,
    val buildType: ApkBuildType,
    val apkFileName: String,
    val errors: List<String> = emptyList()
) {
    val valid: Boolean get() = errors.isEmpty()
}

/**
 * Validation for generated-APK configuration. Catches the most common
 * "App not installed" causes up front: conflicting/invalid package names,
 * invalid version codes, and unsupported SDK configurations.
 */
object ApkBuildConfigValidation {

    const val DEFAULT_MIN_SDK = 24
    const val DEFAULT_TARGET_SDK = 34

    /** Highest targetSdk this builder officially supports. */
    const val MAX_SUPPORTED_TARGET_SDK = 36

    /** Lowest minSdk the generated WebView shell supports. */
    const val MIN_SUPPORTED_MIN_SDK = 21

    const val MAX_VERSION_CODE = 2100000000

    private val PACKAGE_SEGMENT = Regex("[a-zA-Z][a-zA-Z0-9_]*")
    private val VERSION_NAME = Regex("[A-Za-z0-9._-]{1,64}")

    // Package namespaces known to collide with samples, the OS, or this host app.
    private val CONFLICTING_PACKAGES = setOf(
        "com.example",
        "android",
        "com.android",
        "com.google.android"
    )

    fun sanitizeProjectName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9_-]"), "").ifEmpty { "WebApp" }
    }

    fun defaultApplicationId(projectName: String): String {
        val clean = sanitizeProjectName(projectName).lowercase()
        val suffix = clean.replace(Regex("[^a-z0-9_]"), "").ifEmpty { "webapp" }
        return "com.codex.app.$suffix"
    }

    fun validatePackageName(pkg: String?): List<String> {
        if (pkg.isNullOrBlank()) return listOf("Application ID is empty.")
        val errors = mutableListOf<String>()
        if (pkg.length > 255) errors.add("Application ID exceeds 255 characters.")
        val segments = pkg.split(".")
        if (segments.size < 2) {
            errors.add("Application ID \"$pkg\" must contain at least two segments (e.g. com.mycompany.myapp).")
        }
        for (segment in segments) {
            if (!PACKAGE_SEGMENT.matches(segment)) {
                errors.add("Application ID segment \"$segment\" is invalid: each part must start with a letter and contain only letters, digits, or underscores.")
                break
            }
        }
        if (CONFLICTING_PACKAGES.any { pkg == it || pkg.startsWith("$it.") }) {
            errors.add("Application ID \"$pkg\" conflicts with a reserved/sample namespace. Choose a unique ID (e.g. com.yourname.${segments.lastOrNull() ?: "app"}).")
        }
        return errors
    }

    fun validateVersionCode(code: Int?): List<String> {
        if (code == null) return emptyList()
        if (code in 1..MAX_VERSION_CODE) return emptyList()
        return listOf("Version code $code is invalid: it must be an integer between 1 and $MAX_VERSION_CODE. Higher codes are required for Play updates; invalid codes cause install failures.")
    }

    fun validateVersionName(name: String?): List<String> {
        if (name.isNullOrBlank()) return listOf("Version name is empty.")
        if (!VERSION_NAME.matches(name)) {
            return listOf("Version name \"$name\" is invalid: use only letters, digits, dots, underscores, or dashes (e.g. 1.0.0).")
        }
        return emptyList()
    }

    fun validateSdks(minSdk: Int, targetSdk: Int): List<String> {
        val errors = mutableListOf<String>()
        if (minSdk < MIN_SUPPORTED_MIN_SDK) {
            errors.add("minSdk $minSdk is unsupported: the generated shell requires minSdk >= $MIN_SUPPORTED_MIN_SDK.")
        }
        if (targetSdk > MAX_SUPPORTED_TARGET_SDK) {
            errors.add("targetSdk $targetSdk is unsupported: this builder supports targetSdk up to $MAX_SUPPORTED_TARGET_SDK.")
        }
        if (minSdk > targetSdk) {
            errors.add("minSdk ($minSdk) must not be greater than targetSdk ($targetSdk).")
        }
        return errors
    }

    /**
     * Resolves user overrides against safe defaults. Returned config carries
     * every problem in [ResolvedApkConfig.errors]; callers must abort when
     * [ResolvedApkConfig.valid] is false.
     */
    fun resolve(projectName: String, options: ApkBuildOptions): ResolvedApkConfig {
        val dirName = sanitizeProjectName(projectName)
        val rawId = options.applicationId?.trim()
        val applicationId = if (rawId.isNullOrEmpty()) defaultApplicationId(projectName) else rawId
        val rawVersion = options.versionName?.trim()
        val versionName = if (rawVersion.isNullOrEmpty()) "1.0" else rawVersion
        val versionCode = options.versionCode ?: 1
        val minSdk = options.minSdk ?: DEFAULT_MIN_SDK
        val targetSdk = options.targetSdk ?: DEFAULT_TARGET_SDK

        val errors = mutableListOf<String>()
        errors.addAll(validatePackageName(applicationId))
        errors.addAll(validateVersionCode(versionCode))
        errors.addAll(validateVersionName(versionName))
        errors.addAll(validateSdks(minSdk, targetSdk))

        val variantSuffix = if (options.buildType == ApkBuildType.RELEASE) "release" else "debug"
        val apkFileName = "$dirName-$variantSuffix.apk"

        return ResolvedApkConfig(
            projectDirName = dirName,
            applicationId = applicationId,
            versionCode = versionCode,
            versionName = versionName,
            minSdk = minSdk,
            targetSdk = targetSdk,
            buildType = options.buildType,
            apkFileName = apkFileName,
            errors = errors
        )
    }
}
