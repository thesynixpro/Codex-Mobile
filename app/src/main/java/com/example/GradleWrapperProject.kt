package com.example

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Release signing material supplied by the user for `release` builds.
 * Debug builds never need this (AGP signs them with its debug key).
 */
data class ReleaseKeystore(
    val path: String,
    val alias: String,
    val storePassword: String,
    val keyPassword: String
)

/** Inputs for staging a temporary Android wrapper project. */
data class WrapperProjectInput(
    val applicationId: String,
    val appName: String,
    val versionCode: Int,
    val versionName: String,
    val minSdk: Int,
    val targetSdk: Int,
    val buildType: ApkBuildType,
    val entryHtml: String,
    /** Web files to bundle, relative paths -> bytes. Must already exclude build outputs. */
    val assets: Map<String, ByteArray>,
    val sdkDir: String,
    val releaseKeystore: ReleaseKeystore? = null
)

/**
 * HopWeb-style wrapper staging: builds a minimal, self-contained Android
 * Gradle project around a web project in a TEMPORARY directory (the user's
 * sources are only read, never modified). The project uses the pinned
 * [AndroidBuildVersions.AGP] plugin so signing/alignment are handled by the
 * real Android Gradle build.
 *
 * Pure JVM (no Android framework APIs) so generation is unit-testable.
 */
object GradleWrapperProject {

    /** Density qualifier -> launcher icon size in px. */
    val ICON_DENSITIES = linkedMapOf(
        "mdpi" to 48,
        "hdpi" to 72,
        "xhdpi" to 96,
        "xxhdpi" to 144,
        "xxxhdpi" to 192
    )

    @Throws(IOException::class)
    fun generate(projectDir: File, input: WrapperProjectInput) {
        if (input.buildType == ApkBuildType.RELEASE && input.releaseKeystore == null) {
            throw IllegalStateException(
                "Release build requested but no signing keystore was provided. " +
                    "Supply releaseKeystorePath/alias/passwords or build a debug APK instead."
            )
        }
        val safeEntry = input.entryHtml.replace('\\', '/').trimStart('/')
        if (safeEntry.isEmpty() || safeEntry == "." || safeEntry.startsWith("../") || safeEntry.contains("/../")) {
            throw IllegalStateException("Unsafe web entry point: \"${input.entryHtml}\"")
        }

        projectDir.mkdirs()
        write(projectDir, "settings.gradle", settingsGradle(input.appName))
        write(projectDir, "build.gradle", rootBuildGradle())
        write(projectDir, "gradle.properties", gradleProperties())
        write(projectDir, "local.properties", "sdk.dir=${input.sdkDir.replace('\\', '/')}\n")
        write(projectDir, "app/build.gradle", appBuildGradle(input))

        val appMain = File(projectDir, "app/src/main")
        write(projectDir, "app/src/main/AndroidManifest.xml", manifestXml(input.appName))
        val packagePath = "app/src/main/java/" + input.applicationId.replace('.', '/')
        write(projectDir, "$packagePath/MainActivity.java", shellActivityJava(input.applicationId, safeEntry))
        write(projectDir, "app/src/main/res/values/strings.xml", stringsXml(input.appName))

        for ((density, sizePx) in ICON_DENSITIES) {
            File(appMain, "res/mipmap-$density").mkdirs()
            File(appMain, "res/mipmap-$density/ic_launcher.png")
                .writeBytes(LauncherIconPng.render(sizePx))
        }

        val www = File(appMain, "assets/www")
        www.mkdirs()
        for ((relPath, content) in input.assets) {
            val normalized = relPath.trim().replace('\\', '/').trimStart('/')
            if (normalized.isEmpty() || normalized == "." || normalized.startsWith("../") || normalized.contains("/../")) {
                throw IllegalStateException("Refusing to stage unsafe asset path: \"$relPath\"")
            }
            val dest = File(www, normalized)
            if (!dest.canonicalPath.startsWith(www.canonicalPath + File.separator)) {
                throw IllegalStateException("Refusing to write outside staging dir: \"$relPath\"")
            }
            dest.parentFile?.mkdirs()
            dest.writeBytes(content)
        }
    }

    private fun write(projectDir: File, relativePath: String, content: String) {
        val dest = File(projectDir, relativePath)
        dest.parentFile?.mkdirs()
        dest.writeText(content, Charsets.UTF_8)
    }

    private fun groovyQuote(s: String): String {
        return s.replace("\\", "\\\\").replace("'", "\\'")
    }

    private fun xmlEscape(s: String): String {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")
    }

    private fun javaStringEscape(s: String): String {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    fun settingsGradle(appName: String): String {
        val safeName = appName.replace(Regex("[^a-zA-Z0-9_-]"), "").ifEmpty { "WebApp" }
        return """pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = '$safeName'
include ':app'
"""
    }

    fun rootBuildGradle(): String {
        return """// Generated by Codex Mobile - do not edit by hand.
plugins {
    id 'com.android.application' version '${AndroidBuildVersions.AGP}' apply false
}
"""
    }

    fun gradleProperties(): String {
        return """# Generated by Codex Mobile.
org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
org.gradle.caching=true
org.gradle.workers.max=2
android.nonTransitiveRClass=true
"""
    }

    fun appBuildGradle(input: WrapperProjectInput): String {
        val sb = StringBuilder()
        sb.append("// Generated by Codex Mobile - do not edit by hand.\n")
        sb.append("plugins {\n    id 'com.android.application'\n}\n\n")
        sb.append("android {\n")
        sb.append("    namespace '${groovyQuote(input.applicationId)}'\n")
        sb.append("    compileSdk ${AndroidBuildVersions.COMPILE_SDK}\n\n")
        sb.append("    defaultConfig {\n")
        sb.append("        applicationId '${groovyQuote(input.applicationId)}'\n")
        sb.append("        minSdk ${input.minSdk}\n")
        sb.append("        targetSdk ${input.targetSdk}\n")
        sb.append("        versionCode ${input.versionCode}\n")
        sb.append("        versionName '${groovyQuote(input.versionName)}'\n")
        sb.append("    }\n\n")
        if (input.buildType == ApkBuildType.RELEASE && input.releaseKeystore != null) {
            val ks = input.releaseKeystore
            sb.append("    signingConfigs {\n")
            sb.append("        release {\n")
            sb.append("            storeFile file('${groovyQuote(ks.path)}')\n")
            sb.append("            storePassword '${groovyQuote(ks.storePassword)}'\n")
            sb.append("            keyAlias '${groovyQuote(ks.alias)}'\n")
            sb.append("            keyPassword '${groovyQuote(ks.keyPassword)}'\n")
            sb.append("        }\n")
            sb.append("    }\n\n")
        }
        sb.append("    buildTypes {\n")
        sb.append("        debug {}\n")
        if (input.buildType == ApkBuildType.RELEASE) {
            sb.append("        release {\n")
            sb.append("            signingConfig signingConfigs.release\n")
            sb.append("            minifyEnabled false\n")
            sb.append("        }\n")
        } else {
            sb.append("        release {\n")
            sb.append("            signingConfig signingConfigs.debug\n")
            sb.append("            minifyEnabled false\n")
            sb.append("        }\n")
        }
        sb.append("    }\n\n")
        sb.append("    compileOptions {\n")
        sb.append("        sourceCompatibility JavaVersion.VERSION_17\n")
        sb.append("        targetCompatibility JavaVersion.VERSION_17\n")
        sb.append("    }\n")
        sb.append("}\n\n")
        sb.append("dependencies {\n}\n")
        return sb.toString()
    }

    fun manifestXml(appName: String): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <application
        android:label="@string/app_name"
        android:icon="@mipmap/ic_launcher"
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

    fun stringsXml(appName: String): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">${xmlEscape(appName)}</string>
</resources>
"""
    }

    fun shellActivityJava(applicationId: String, entryHtml: String): String {
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
        webView.loadUrl("file:///android_asset/www/${javaStringEscape(entryHtml)}");
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
}

/**
 * Generates simple launcher-icon PNGs without any graphics framework: a
 * solid tile with a contrasting centered glyph block, hand-encoded as
 * 8-bit truecolor PNG (pure JVM: Deflater + CRC32 only).
 */
object LauncherIconPng {

    // ARGB colors as signed Ints (0xFF1D4ED8, 0xFFFFFFFF, 0xFF93C5FD).
    private const val BG = -14856488
    private const val FG = -1
    private const val ACCENT = -7092739

    fun render(sizePx: Int): ByteArray {
        require(sizePx >= 24) { "Icon size too small: $sizePx" }
        val px = IntArray(sizePx * sizePx) { BG }
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
            for (y in y0.coerceAtLeast(0) until y1.coerceAtMost(sizePx)) {
                for (x in x0.coerceAtLeast(0) until x1.coerceAtMost(sizePx)) {
                    px[y * sizePx + x] = color
                }
            }
        }
        val m = (sizePx * 0.22).toInt()
        rect(m, m, sizePx - m, sizePx - m, FG)
        val m2 = (sizePx * 0.38).toInt()
        rect(m2, m2, sizePx - m2, sizePx - m2, BG)
        val m3 = (sizePx * 0.46).toInt()
        rect(m3, m3, sizePx - m3, sizePx - m3, ACCENT)

        val raw = ByteArrayOutputStream(sizePx * (sizePx * 3 + 1))
        for (y in 0 until sizePx) {
            raw.write(0) // filter: none
            for (x in 0 until sizePx) {
                val c = px[y * sizePx + x]
                raw.write((c ushr 16) and 0xFF)
                raw.write((c ushr 8) and 0xFF)
                raw.write(c and 0xFF)
            }
        }
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        val compressed: ByteArray
        try {
            deflater.setInput(raw.toByteArray())
            deflater.finish()
            val bos = ByteArrayOutputStream(raw.size())
            val buf = ByteArray(8192)
            while (!deflater.finished()) {
                bos.write(buf, 0, deflater.deflate(buf))
            }
            compressed = bos.toByteArray()
        } finally {
            deflater.end()
        }

        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        val ihdr = ByteArrayOutputStream()
        writeBe32(ihdr, sizePx.toLong())
        writeBe32(ihdr, sizePx.toLong())
        ihdr.write(8) // bit depth
        ihdr.write(2) // truecolor
        ihdr.write(0) // compression
        ihdr.write(0) // filter
        ihdr.write(0) // interlace
        writeChunk(out, "IHDR", ihdr.toByteArray())
        writeChunk(out, "IDAT", compressed)
        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun writeBe32(out: ByteArrayOutputStream, v: Long) {
        out.write(((v ushr 24) and 0xFF).toInt())
        out.write(((v ushr 16) and 0xFF).toInt())
        out.write(((v ushr 8) and 0xFF).toInt())
        out.write((v and 0xFF).toInt())
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        writeBe32(out, data.size.toLong())
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        writeBe32(out, crc.value)
    }
}
