package com.example

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

object DocumentTreeHelper {

    private const val MAX_DEPTH = 6
    private const val MAX_TOTAL_FILES = 1200

    private val IGNORED_NAMES = setOf(
        ".git", ".gradle", "build", "node_modules", ".idea", ".vscode",
        "bin", "obj", ".dart_tool", "dist", ".next", ".cache", "__pycache__", ".DS_Store"
    )

    fun extractDisplayName(resolver: ContentResolver, treeUri: Uri): String {
        return try {
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
            val name = getDocumentDisplayName(resolver, docUri)
            if (!name.isNullOrBlank()) {
                name
            } else {
                parseNameFromDocId(rootDocId)
            }
        } catch (e: Exception) {
            parseNameFromUri(treeUri)
        }
    }

    private fun parseNameFromDocId(docId: String): String {
        val parts = docId.split(":")
        val name = if (parts.size > 1 && parts[1].isNotEmpty()) {
            val subParts = parts[1].split("/")
            subParts.lastOrNull { it.isNotEmpty() } ?: parts[1]
        } else {
            parts[0]
        }
        return if (name.isNotBlank()) name else "Project"
    }

    private fun parseNameFromUri(uri: Uri): String {
        val last = uri.lastPathSegment ?: "Project"
        return parseNameFromDocId(last)
    }

    fun buildDirectoryTree(context: Context, rootUri: Uri): JSONObject {
        val rootDocId = DocumentsContract.getTreeDocumentId(rootUri)

        val result = JSONObject()
        val displayName = extractDisplayName(context.contentResolver, rootUri)
        result.put("name", displayName)
        result.put("uri", rootUri.toString())
        result.put("documentId", rootDocId)
        result.put("isDirectory", true)
        result.put("path", "")

        val counter = java.util.concurrent.atomic.AtomicInteger(0)
        val children = readChildren(context, rootUri, rootDocId, "", 0, counter)
        result.put("children", children)
        return result
    }

    private fun readChildren(
        context: Context,
        rootUri: Uri,
        parentDocId: String,
        parentPath: String,
        depth: Int,
        counter: java.util.concurrent.atomic.AtomicInteger
    ): JSONArray {
        val childrenArray = JSONArray()
        if (depth >= MAX_DEPTH || counter.get() >= MAX_TOTAL_FILES) {
            return childrenArray
        }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, parentDocId)

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )

        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)

                while (cursor.moveToNext()) {
                    if (counter.incrementAndGet() > MAX_TOTAL_FILES) {
                        break
                    }
                    val docId = cursor.getString(idIndex)
                    val name = cursor.getString(nameIndex) ?: continue

                    val relPath = if (parentPath.isEmpty()) name else "$parentPath/$name"

                    // Skip hidden/system directories like .git, node_modules, etc.
                    // Exception: always expose the APK output folder (build/apk/)
                    // so generated installable APKs are visible in the explorer.
                    val isApkOutputPath = relPath == "build" || relPath == "build/apk" ||
                        relPath.startsWith("build/apk/")
                    if (!isApkOutputPath && (IGNORED_NAMES.contains(name) || name.startsWith(".git"))) {
                        continue
                    }
                    // Inside a root-level build/ directory, only descend into the
                    // APK output folder; other build intermediates stay hidden.
                    if (parentPath == "build" && name != "apk") {
                        continue
                    }

                    val mime = cursor.getString(mimeIndex)
                    val size = if (cursor.isNull(sizeIndex)) 0L else cursor.getLong(sizeIndex)
                    val isDir = DocumentsContract.Document.MIME_TYPE_DIR == mime

                    val fileObj = JSONObject()
                    fileObj.put("name", name)
                    fileObj.put("path", relPath)
                    fileObj.put("documentId", docId)
                    fileObj.put("mimeType", mime)
                    fileObj.put("size", size)
                    fileObj.put("isDirectory", isDir)
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, docId)
                    fileObj.put("uri", docUri.toString())

                    if (isDir) {
                        fileObj.put("children", readChildren(context, rootUri, docId, relPath, depth + 1, counter))
                    }

                    childrenArray.put(fileObj)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return childrenArray
    }

    fun getDocumentDisplayName(resolver: ContentResolver, uri: Uri): String? {
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        try {
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(0)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun readFileContent(context: Context, uri: Uri): String {
        val sb = StringBuilder()
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    sb.append(line).append("\n")
                    line = reader.readLine()
                }
            }
        } ?: throw IllegalStateException("Unable to open input stream for $uri")

        return if (sb.isNotEmpty() && sb.last() == '\n') {
            sb.substring(0, sb.length - 1)
        } else {
            sb.toString()
        }
    }

    fun writeFileContent(context: Context, uri: Uri, content: String) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { outputStream ->
            outputStream.write(content.toByteArray(Charsets.UTF_8))
            outputStream.flush()
        } ?: throw IllegalStateException("Unable to open output stream for $uri")
    }

    fun writeBinaryFileContent(context: Context, uri: Uri, bytes: ByteArray) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { outputStream ->
            outputStream.write(bytes)
            outputStream.flush()
        } ?: throw IllegalStateException("Unable to open output stream for $uri")
    }

    /**
     * Writes or creates a binary file (e.g. APK) in the project folder matching [relativePath].
     * Creates any intermediate directories if they do not exist.
     */
    fun writeRelativeBinaryFile(
        context: Context,
        rootUri: Uri,
        relativePath: String,
        bytes: ByteArray,
        mimeType: String? = null
    ): Uri {
        val normalizedPath = relativePath.trim().trimStart('/').replace('\\', '/')
        if (normalizedPath.isEmpty()) {
            throw IllegalArgumentException("File path cannot be empty")
        }

        val parts = normalizedPath.split('/')
        val resolver = context.contentResolver
        var currentParentDocId = DocumentsContract.getTreeDocumentId(rootUri)

        // Navigate / create intermediate directories
        for (i in 0 until parts.size - 1) {
            val dirName = parts[i]
            if (dirName.isEmpty()) continue

            val foundDirId = findChildId(resolver, rootUri, currentParentDocId, dirName, isDir = true)
            if (foundDirId != null) {
                currentParentDocId = foundDirId
            } else {
                val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, currentParentDocId)
                val newDirUri = DocumentsContract.createDocument(
                    resolver,
                    parentDocUri,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    dirName
                ) ?: throw IllegalStateException("Failed to create directory $dirName in project folder")
                currentParentDocId = DocumentsContract.getDocumentId(newDirUri)
            }
        }

        // Target file
        val fileName = parts.last()
        val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, currentParentDocId)
        val existingFileId = findChildId(resolver, rootUri, currentParentDocId, fileName, isDir = false)

        val targetFileUri = if (existingFileId != null) {
            DocumentsContract.buildDocumentUriUsingTree(rootUri, existingFileId)
        } else {
            DocumentsContract.createDocument(
                resolver,
                parentDocUri,
                mimeType ?: getMimeTypeForFilename(fileName),
                fileName
            ) ?: throw IllegalStateException("Failed to create file $fileName in project folder")
        }

        writeBinaryFileContent(context, targetFileUri, bytes)
        return targetFileUri
    }

    /**
     * Writes or creates a file in the project folder matching [relativePath].
     * Creates any intermediate directories if they do not exist.
     */
    fun writeRelativeFile(
        context: Context,
        rootUri: Uri,
        relativePath: String,
        content: String
    ): Uri {
        val normalizedPath = relativePath.trim().trimStart('/').replace('\\', '/')
        if (normalizedPath.isEmpty()) {
            throw IllegalArgumentException("File path cannot be empty")
        }

        val parts = normalizedPath.split('/')
        val resolver = context.contentResolver
        var currentParentDocId = DocumentsContract.getTreeDocumentId(rootUri)

        // Navigate / create intermediate directories
        for (i in 0 until parts.size - 1) {
            val dirName = parts[i]
            if (dirName.isEmpty()) continue

            val foundDirId = findChildId(resolver, rootUri, currentParentDocId, dirName, isDir = true)
            if (foundDirId != null) {
                currentParentDocId = foundDirId
            } else {
                val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, currentParentDocId)
                val newDirUri = DocumentsContract.createDocument(
                    resolver,
                    parentDocUri,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    dirName
                ) ?: throw IllegalStateException("Failed to create directory $dirName in project folder")
                currentParentDocId = DocumentsContract.getDocumentId(newDirUri)
            }
        }

        // Target file
        val fileName = parts.last()
        val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, currentParentDocId)
        val existingFileId = findChildId(resolver, rootUri, currentParentDocId, fileName, isDir = false)

        val targetFileUri = if (existingFileId != null) {
            DocumentsContract.buildDocumentUriUsingTree(rootUri, existingFileId)
        } else {
            val mimeType = getMimeTypeForFilename(fileName)
            DocumentsContract.createDocument(
                resolver,
                parentDocUri,
                mimeType,
                fileName
            ) ?: throw IllegalStateException("Failed to create file $fileName in project folder")
        }

        writeFileContent(context, targetFileUri, content)
        return targetFileUri
    }

    /**
     * Deletes a file inside the project directory matching [relativePath].
     */
    fun deleteRelativeFile(
        context: Context,
        rootUri: Uri,
        relativePath: String
    ): Boolean {
        val normalizedPath = relativePath.trim().trimStart('/').replace('\\', '/')
        if (normalizedPath.isEmpty()) return false

        val parts = normalizedPath.split('/')
        val resolver = context.contentResolver
        var currentParentDocId = DocumentsContract.getTreeDocumentId(rootUri)

        for (i in 0 until parts.size - 1) {
            val dirName = parts[i]
            if (dirName.isEmpty()) continue
            val foundDirId = findChildId(resolver, rootUri, currentParentDocId, dirName, isDir = true)
                ?: return true // Parent dir doesn't exist, file is already absent
            currentParentDocId = foundDirId
        }

        val targetName = parts.last()
        val foundId = findChildId(resolver, rootUri, currentParentDocId, targetName, isDir = null)
            ?: return true // File does not exist, treat as success

        val targetDocUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, foundId)
        return DocumentsContract.deleteDocument(resolver, targetDocUri)
    }

    /**
     * Reads a file content by its relative path inside the tree.
     */
    fun readRelativeFile(
        context: Context,
        rootUri: Uri,
        relativePath: String
    ): String {
        val normalizedPath = relativePath.trim().trimStart('/').replace('\\', '/')
        val parts = normalizedPath.split('/')
        val resolver = context.contentResolver
        var currentParentDocId = DocumentsContract.getTreeDocumentId(rootUri)

        for (i in 0 until parts.size - 1) {
            val dirName = parts[i]
            if (dirName.isEmpty()) continue
            currentParentDocId = findChildId(resolver, rootUri, currentParentDocId, dirName, isDir = true)
                ?: throw NoSuchFileException(java.io.File(relativePath), reason = "Directory $dirName not found")
        }

        val fileName = parts.last()
        val foundId = findChildId(resolver, rootUri, currentParentDocId, fileName, isDir = false)
            ?: throw NoSuchFileException(java.io.File(relativePath), reason = "File $fileName not found")

        val docUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, foundId)
        return readFileContent(context, docUri)
    }

    private fun findChildId(
        resolver: ContentResolver,
        rootUri: Uri,
        parentDocId: String,
        targetName: String,
        isDir: Boolean?
    ): String? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        try {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx)
                    if (name.equals(targetName, ignoreCase = false)) {
                        val mime = cursor.getString(mimeIdx)
                        val childIsDir = DocumentsContract.Document.MIME_TYPE_DIR == mime
                        if (isDir == null || isDir == childIsDir) {
                            return cursor.getString(idIdx)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun getMimeTypeForFilename(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "apk" -> "application/vnd.android.package-archive"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js", "mjs" -> "application/javascript"
            "json" -> "application/json"
            "py" -> "text/x-python"
            "kt", "kts" -> "text/x-kotlin"
            "java" -> "text/x-java-source"
            "md", "markdown" -> "text/markdown"
            "xml", "svg" -> "text/xml"
            "sh", "bash" -> "application/x-sh"
            "yaml", "yml" -> "text/yaml"
            "txt", "text" -> "text/plain"
            else -> "text/plain"
        }
    }
}
