package com.example

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.io.File

/**
 * Host-side tests for [GradleWrapperProject]. Generation is pure JVM
 * (no Android framework), including the hand-encoded launcher PNGs.
 */
class GradleWrapperProjectTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "wrapper-${System.nanoTime()}")
        check(dir.mkdirs())
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun debugInput(): WrapperProjectInput {
        return WrapperProjectInput(
            applicationId = "com.test.demoapp",
            appName = "DemoApp",
            versionCode = 3,
            versionName = "1.2.0",
            minSdk = 24,
            targetSdk = 34,
            buildType = ApkBuildType.DEBUG,
            entryHtml = "index.html",
            assets = mapOf(
                "index.html" to "<html><body>hi</body></html>".toByteArray(),
                "css/app.css" to "body{}".toByteArray()
            ),
            sdkDir = "/tmp/fake-sdk"
        )
    }

    @Test
    fun `wrapper contains a complete gradle project`() {
        GradleWrapperProject.generate(File(dir, "wrapper"), debugInput())

        val root = File(dir, "wrapper")
        assertTrue(File(root, "settings.gradle").isFile)
        assertTrue(File(root, "build.gradle").isFile)
        assertTrue(File(root, "gradle.properties").isFile)
        assertTrue(File(root, "local.properties").isFile)
        assertTrue(File(root, "app/build.gradle").isFile)
        assertTrue(File(root, "app/src/main/AndroidManifest.xml").isFile)
        assertTrue(File(root, "app/src/main/java/com/test/demoapp/MainActivity.java").isFile)
        assertTrue(File(root, "app/src/main/res/values/strings.xml").isFile)
        assertTrue(File(root, "app/src/main/assets/www/index.html").isFile)
        assertTrue(File(root, "app/src/main/assets/www/css/app.css").isFile)
    }

    @Test
    fun `gradle files pin the compatible toolchain and app identity`() {
        GradleWrapperProject.generate(File(dir, "wrapper"), debugInput())
        val root = File(dir, "wrapper")

        val settings = File(root, "settings.gradle").readText()
        assertTrue(settings.contains("google()"))
        assertTrue(settings.contains("include ':app'"))

        val rootGradle = File(root, "build.gradle").readText()
        assertTrue(rootGradle.contains(AndroidBuildVersions.AGP))

        val appGradle = File(root, "app/build.gradle").readText()
        assertTrue(appGradle.contains("namespace 'com.test.demoapp'"))
        assertTrue(appGradle.contains("applicationId 'com.test.demoapp'"))
        assertTrue(appGradle.contains("minSdk 24"))
        assertTrue(appGradle.contains("targetSdk 34"))
        assertTrue(appGradle.contains("versionCode 3"))
        assertTrue(appGradle.contains("versionName '1.2.0'"))
        assertTrue(appGradle.contains("compileSdk ${AndroidBuildVersions.COMPILE_SDK}"))

        val localProps = File(root, "local.properties").readText()
        assertTrue(localProps.contains("sdk.dir=/tmp/fake-sdk"))
    }

    @Test
    fun `manifest and shell wire the web entry point`() {
        GradleWrapperProject.generate(File(dir, "wrapper"), debugInput())
        val root = File(dir, "wrapper")

        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.INTERNET"))
        assertTrue(manifest.contains("android.intent.action.MAIN"))
        assertTrue(manifest.contains("android.intent.category.LAUNCHER"))
        assertTrue(manifest.contains(".MainActivity"))
        // Namespace comes from app/build.gradle on modern AGP, not the manifest.
        assertFalse(manifest.contains("package=\"com.test.demoapp\""))

        val activity = File(root, "app/src/main/java/com/test/demoapp/MainActivity.java").readText()
        assertTrue(activity.contains("package com.test.demoapp;"))
        assertTrue(activity.contains("file:///android_asset/www/index.html"))
        assertTrue(activity.contains("setJavaScriptEnabled(true)"))
    }

    @Test
    fun `release build embeds the provided signing config`() {
        val input = debugInput().copy(
            buildType = ApkBuildType.RELEASE,
            releaseKeystore = ReleaseKeystore(
                path = "/keys/release.jks",
                alias = "upload",
                storePassword = "s3cret",
                keyPassword = "s3cret"
            )
        )
        GradleWrapperProject.generate(File(dir, "wrapper"), input)
        val appGradle = File(dir, "wrapper/app/build.gradle").readText()
        assertTrue(appGradle.contains("signingConfigs"))
        assertTrue(appGradle.contains("storeFile file('/keys/release.jks')"))
        assertTrue(appGradle.contains("keyAlias 'upload'"))
        assertTrue(appGradle.contains("signingConfig signingConfigs.release"))
    }

    @Test
    fun `release without keystore is rejected`() {
        try {
            GradleWrapperProject.generate(File(dir, "wrapper"), debugInput().copy(buildType = ApkBuildType.RELEASE))
            fail("Expected release build without keystore to be rejected")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("keystore"))
        }
    }

    @Test
    fun `unsafe asset paths are rejected`() {
        try {
            GradleWrapperProject.generate(
                File(dir, "wrapper"),
                debugInput().copy(assets = mapOf("../evil.txt" to "x".toByteArray()))
            )
            fail("Expected unsafe asset path to be rejected")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("unsafe"))
        }
    }

    @Test
    fun `launcher icons are valid pngs at every density`() {
        GradleWrapperProject.generate(File(dir, "wrapper"), debugInput())
        val expected = mapOf(
            "mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192
        )
        for ((density, size) in expected) {
            val png = File(dir, "wrapper/app/src/main/res/mipmap-$density/ic_launcher.png")
            assertTrue("$density icon missing", png.isFile)
            val (w, h) = pngDimensions(png)
            assertEquals("$density width", size, w)
            assertEquals("$density height", size, h)
        }
    }

    private fun pngDimensions(png: File): Pair<Int, Int> {
        DataInputStream(png.inputStream()).use { input ->
            val sig = ByteArray(8)
            input.readFully(sig)
            assertEquals(137, sig[0].toInt() and 0xFF)
            assertEquals(80, sig[1].toInt() and 0xFF) // 'P'
            val length = input.readInt()
            val type = ByteArray(4)
            input.readFully(type)
            assertEquals("IHDR", String(type, Charsets.US_ASCII))
            assertEquals(13, length)
            val width = input.readInt()
            val height = input.readInt()
            return width to height
        }
    }
}
