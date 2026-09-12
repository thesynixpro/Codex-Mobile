package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkBuildOptionsTest {

    @Test
    fun `valid package names pass`() {
        assertTrue(ApkBuildConfigValidation.validatePackageName("com.codex.app.demo").isEmpty())
        assertTrue(ApkBuildConfigValidation.validatePackageName("com.my_company.my-app_2".replace('-', '_')).isEmpty())
        assertTrue(ApkBuildConfigValidation.validatePackageName("org.example.app").isEmpty())
    }

    @Test
    fun `invalid package names fail`() {
        // Single segment.
        assertFalse(ApkBuildConfigValidation.validatePackageName("myapp").isEmpty())
        // Segment starting with a digit.
        assertFalse(ApkBuildConfigValidation.validatePackageName("com.1app.demo").isEmpty())
        // Illegal characters.
        assertFalse(ApkBuildConfigValidation.validatePackageName("com.my-app.demo").isEmpty())
        // Empty.
        assertFalse(ApkBuildConfigValidation.validatePackageName("").isEmpty())
        assertFalse(ApkBuildConfigValidation.validatePackageName(null).isEmpty())
    }

    @Test
    fun `reserved sample namespaces are rejected`() {
        assertFalse(ApkBuildConfigValidation.validatePackageName("com.example").isEmpty())
        assertFalse(ApkBuildConfigValidation.validatePackageName("com.example.demo").isEmpty())
        assertFalse(ApkBuildConfigValidation.validatePackageName("android").isEmpty())
    }

    @Test
    fun `version codes are range-checked`() {
        assertTrue(ApkBuildConfigValidation.validateVersionCode(1).isEmpty())
        assertTrue(ApkBuildConfigValidation.validateVersionCode(2100000000).isEmpty())
        assertTrue(ApkBuildConfigValidation.validateVersionCode(null).isEmpty())
        assertFalse(ApkBuildConfigValidation.validateVersionCode(0).isEmpty())
        assertFalse(ApkBuildConfigValidation.validateVersionCode(-5).isEmpty())
    }

    @Test
    fun `version names accept semver-like strings`() {
        assertTrue(ApkBuildConfigValidation.validateVersionName("1.0").isEmpty())
        assertTrue(ApkBuildConfigValidation.validateVersionName("2.3.4-beta").isEmpty())
        assertFalse(ApkBuildConfigValidation.validateVersionName("").isEmpty())
        assertFalse(ApkBuildConfigValidation.validateVersionName(null).isEmpty())
        assertFalse(ApkBuildConfigValidation.validateVersionName("1.0 (final)").isEmpty())
    }

    @Test
    fun `sdk combinations are validated`() {
        assertTrue(ApkBuildConfigValidation.validateSdks(24, 34).isEmpty())
        // minSdk above targetSdk.
        assertFalse(ApkBuildConfigValidation.validateSdks(34, 24).isEmpty())
        // Ancient minSdk unsupported by the generated shell.
        assertFalse(ApkBuildConfigValidation.validateSdks(16, 34).isEmpty())
        // Target beyond supported range.
        assertFalse(ApkBuildConfigValidation.validateSdks(24, 99).isEmpty())
    }

    @Test
    fun `resolve applies safe defaults`() {
        val config = ApkBuildConfigValidation.resolve("My Demo!", ApkBuildOptions())
        assertTrue(config.valid)
        assertEquals("com.codex.app.mydemo", config.applicationId)
        assertEquals(1, config.versionCode)
        assertEquals("1.0", config.versionName)
        assertEquals(24, config.minSdk)
        assertEquals(34, config.targetSdk)
        assertEquals("MyDemo-debug.apk", config.apkFileName)
    }

    @Test
    fun `resolve honors overrides and flags conflicts`() {
        val ok = ApkBuildConfigValidation.resolve(
            "Demo",
            ApkBuildOptions(
                buildType = ApkBuildType.RELEASE,
                applicationId = "com.acme.demo",
                versionCode = 7,
                versionName = "2.0.1",
                minSdk = 26,
                targetSdk = 34
            )
        )
        assertTrue(ok.valid)
        assertEquals("com.acme.demo", ok.applicationId)
        assertEquals("Demo-release.apk", ok.apkFileName)

        val bad = ApkBuildConfigValidation.resolve("Demo", ApkBuildOptions(applicationId = "com.example.demo"))
        assertFalse(bad.valid)
        assertTrue(bad.errors.any { it.contains("reserved") })
    }

    @Test
    fun `options parse from json`() {
        val opts = ApkBuildOptions.fromJson(
            """{"buildType":"release","applicationId":"com.acme.app","versionCode":3,"versionName":"1.2.0","minSdk":24,"targetSdk":34}"""
        )
        assertEquals(ApkBuildType.RELEASE, opts.buildType)
        assertEquals("com.acme.app", opts.applicationId)
        assertEquals(3, opts.versionCode)
        assertEquals("1.2.0", opts.versionName)

        val defaults = ApkBuildOptions.fromJson(null)
        assertEquals(ApkBuildType.DEBUG, defaults.buildType)
        assertEquals(null, defaults.applicationId)

        // Malformed JSON falls back to safe defaults instead of crashing.
        val broken = ApkBuildOptions.fromJson("{not json")
        assertEquals(ApkBuildType.DEBUG, broken.buildType)
    }
}
