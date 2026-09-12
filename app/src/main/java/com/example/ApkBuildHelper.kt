package com.example

import java.io.File

object ApkBuildHelper {

    /**
     * Parses build output logs to extract clean failure messages.
     */
    fun extractBuildError(logText: String): String {
        val lines = logText.lines()
        val start = lines.indexOfFirst { it.contains("FAILURE: Build failed with an exception.") }
        
        if (start == -1) {
            return logText.takeLast(1000)
        }

        // Fixed compiler error: iterate over indices so lines[index] (String) is checked instead of index (Int)
        val end = (start until lines.size).firstOrNull { index ->
            lines[index].contains("* Try:") || lines[index].contains("BUILD FAILED")
        }?.let { it + 1 } ?: lines.size

        return lines.subList(start, end).joinToString("\n")
    }

    /**
     * Checks whether an execution exit code indicates build success.
     */
    fun isBuildSuccessful(exitCode: Int): Boolean {
        return exitCode == 0
    }

    /**
     * Locates the generated APK in the output directory.
     */
    fun findApkFile(buildOutputDir: File): File? {
        if (!buildOutputDir.exists() || !buildOutputDir.isDirectory) {
            return null
        }
        return buildOutputDir.walkTopDown()
            .firstOrNull { it.isFile && it.extension == "apk" }
    }
}
