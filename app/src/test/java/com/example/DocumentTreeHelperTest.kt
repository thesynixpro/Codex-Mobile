package com.example

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DocumentTreeHelperTest {

    @Test
    fun `verify mime types for file extensions`() {
        assertEquals("text/html", DocumentTreeHelper.getMimeTypeForFilename("index.html"))
        assertEquals("text/css", DocumentTreeHelper.getMimeTypeForFilename("style.css"))
        assertEquals("application/javascript", DocumentTreeHelper.getMimeTypeForFilename("app.js"))
        assertEquals("application/json", DocumentTreeHelper.getMimeTypeForFilename("package.json"))
        assertEquals("text/x-python", DocumentTreeHelper.getMimeTypeForFilename("main.py"))
        assertEquals("text/x-kotlin", DocumentTreeHelper.getMimeTypeForFilename("Main.kt"))
        assertEquals("text/markdown", DocumentTreeHelper.getMimeTypeForFilename("README.md"))
    }
}
