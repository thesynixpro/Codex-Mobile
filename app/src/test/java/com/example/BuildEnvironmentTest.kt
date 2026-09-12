package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildEnvironmentTest {

    @Test
    fun `gradle version parses from full --version output`() {
        val output = """
            ------------------------------------------------------------
            Gradle 8.7
            ------------------------------------------------------------

            Build time: 2024-03-22
            Revision: abc123

            Kotlin: 1.9.22
            Groovy: 3.0.17
            JVM: 17.0.10
        """.trimIndent()
        assertEquals("8.7", AndroidBuildVersions.parseGradleVersion(output))
    }

    @Test
    fun `gradle version keeps patch component`() {
        assertEquals("8.7.1", AndroidBuildVersions.parseGradleVersion("Gradle 8.7.1"))
        assertEquals("8.10", AndroidBuildVersions.parseGradleVersion("8.10"))
        assertNull(AndroidBuildVersions.parseGradleVersion("no version here"))
        assertNull(AndroidBuildVersions.parseGradleVersion(""))
    }

    @Test
    fun `gradle compatibility follows the pinned matrix`() {
        assertTrue(AndroidBuildVersions.isGradleCompatible("8.7"))
        assertTrue(AndroidBuildVersions.isGradleCompatible("8.10.2"))
        assertFalse(AndroidBuildVersions.isGradleCompatible("8.5"))
        assertFalse(AndroidBuildVersions.isGradleCompatible("7.6.4"))
        assertFalse(AndroidBuildVersions.isGradleCompatible("9.0"))
        assertFalse(AndroidBuildVersions.isGradleCompatible(null))
        assertFalse(AndroidBuildVersions.isGradleCompatible("not-a-version"))
    }

    @Test
    fun `java major parses modern and legacy outputs`() {
        assertEquals(17, AndroidBuildVersions.parseJavaMajor("openjdk version \"17.0.9\" 2023-10-17\nOpenJDK Runtime Environment"))
        assertEquals(21, AndroidBuildVersions.parseJavaMajor("openjdk version \"21.0.2\" 2024-01-16"))
        assertEquals(8, AndroidBuildVersions.parseJavaMajor("java version \"1.8.0_382\""))
        assertNull(AndroidBuildVersions.parseJavaMajor("no java here"))
        assertNull(AndroidBuildVersions.parseJavaMajor(""))
    }

    @Test
    fun `pinned versions are mutually consistent`() {
        // AGP 8.5.x requires Gradle 8.7+ running on JDK 17.
        assertTrue(AndroidBuildVersions.isGradleCompatible(AndroidBuildVersions.GRADLE))
        assertTrue(AndroidBuildVersions.JDK_MIN_MAJOR >= 17)
        assertEquals(34, AndroidBuildVersions.PLATFORM_API)
        assertEquals(AndroidBuildVersions.COMPILE_SDK, AndroidBuildVersions.PLATFORM_API)
    }
}
