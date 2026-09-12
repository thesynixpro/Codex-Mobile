package com.example

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-side tests for the pure helper logic in [ApkBuildHelper]
 * (Gradle error extraction). Everything else in the helper needs Android.
 */
class ApkBuildHelperTest {

    @Test
    fun `error extraction prefers what-went-wrong section`() {
        val log = """
            > Task :app:preBuild UP-TO-DATE
            > Task :app:mergeDebugResources FAILED

            FAILURE: Build failed with an exception.

            * What went wrong:
            Execution failed for task ':app:mergeDebugResources'.
            > A failure occurred while executing com.android.build.gradle.tasks.MergeResources
               > The file name must end with .xml

            * Try:
            > Run with --stacktrace for details.

            BUILD FAILED in 12s
        """.trimIndent()
        val error = ApkBuildHelper.extractGradleError(log)
        assertTrue(error.contains("What went wrong"))
        assertTrue(error.contains("mergeDebugResources"))
        assertTrue(error.contains(".xml"))
    }

    @Test
    fun `error extraction falls back to failure lines`() {
        val log = "> Task :app:compileDebugJavaWithJavac FAILED\nSome other noise\nerror: cannot find symbol\n"
        val error = ApkBuildHelper.extractGradleError(log)
        assertTrue(error.contains("FAILED"))
    }

    @Test
    fun `error extraction falls back to log tail`() {
        val log = "line one\nline two\n"
        val error = ApkBuildHelper.extractGradleError(log)
        assertTrue(error.contains("line two"))
    }
}
