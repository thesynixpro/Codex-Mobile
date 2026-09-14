package com.example

import android.content.Context
import android.net.Uri
import android.os.Build
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.example.db.ChatConversationEntity
import com.example.db.ChatMessageEntity
import com.example.db.CodexDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class AndroidBridge(
    private val activity: MainActivity,
    private val scope: CoroutineScope
) {

    @JavascriptInterface
    fun isNativeAndroid(): Boolean = true

    @JavascriptInterface
    fun requestProjectDirectory() {
        activity.runOnUiThread {
            activity.launchDirectoryPicker()
        }
    }

    @JavascriptInterface
    fun getPersistedProjectUri(): String {
        return activity.getPersistedProjectUri() ?: ""
    }

    @JavascriptInterface
    fun clearPersistedProject() {
        activity.clearPersistedProjectUri()
    }

    @JavascriptInterface
    fun rescanProject(rootUriStr: String, callbackId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val rootUri = Uri.parse(rootUriStr)
                val treeJson = DocumentTreeHelper.buildDirectoryTree(activity, rootUri)
                val treeEscaped = JSONObject.quote(treeJson.toString())
                evaluateJs("window.onAndroidProjectRescanned && window.onAndroidProjectRescanned(\"$callbackId\", true, $treeEscaped, null)")
            } catch (e: Exception) {
                val error = JSONObject.quote(e.message ?: "Failed to rescan directory")
                evaluateJs("window.onAndroidProjectRescanned && window.onAndroidProjectRescanned(\"$callbackId\", false, null, $error)")
            }
        }
    }

    @JavascriptInterface
    fun writeRelativeFile(rootUriStr: String, relativePath: String, content: String, callbackId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val rootUri = Uri.parse(rootUriStr)
                val fileUri = DocumentTreeHelper.writeRelativeFile(activity, rootUri, relativePath, content)
                val uriQuote = JSONObject.quote(fileUri.toString())
                evaluateJs("window.onAndroidFileWritten && window.onAndroidFileWritten(\"$callbackId\", true, $uriQuote, null)")
            } catch (e: Exception) {
                val error = JSONObject.quote(e.message ?: "Failed to write $relativePath")
                evaluateJs("window.onAndroidFileWritten && window.onAndroidFileWritten(\"$callbackId\", false, null, $error)")
            }
        }
    }

    @JavascriptInterface
    fun deleteRelativeFile(rootUriStr: String, relativePath: String, callbackId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val rootUri = Uri.parse(rootUriStr)
                val deleted = DocumentTreeHelper.deleteRelativeFile(activity, rootUri, relativePath)
                if (deleted) {
                    evaluateJs("window.onAndroidFileDeleted && window.onAndroidFileDeleted(\"$callbackId\", true, null)")
                } else {
                    evaluateJs("window.onAndroidFileDeleted && window.onAndroidFileDeleted(\"$callbackId\", false, \"File deletion was rejected by system\")")
                }
            } catch (e: Exception) {
                val error = JSONObject.quote(e.message ?: "Failed to delete $relativePath")
                evaluateJs("window.onAndroidFileDeleted && window.onAndroidFileDeleted(\"$callbackId\", false, $error)")
            }
        }
    }

    @JavascriptInterface
    fun readRelativeFile(rootUriStr: String, relativePath: String, callbackId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val rootUri = Uri.parse(rootUriStr)
                val content = DocumentTreeHelper.readRelativeFile(activity, rootUri, relativePath)
                val escapedContent = JSONObject.quote(content)
                evaluateJs("window.onAndroidFileRead && window.onAndroidFileRead(\"$callbackId\", true, $escapedContent, null)")
            } catch (e: Exception) {
                val error = JSONObject.quote(e.message ?: "Failed to read $relativePath")
                evaluateJs("window.onAndroidFileRead && window.onAndroidFileRead(\"$callbackId\", false, null, $error)")
            }
        }
    }

    /**
     * Atomically executes a batch of file proposals (CREATE, MODIFY, DELETE) directly inside the
     * project folder, and rescans the directory tree.
     */
    @JavascriptInterface
    fun applyBatchChanges(rootUriStr: String, changesJson: String, callbackId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val rootUri = Uri.parse(rootUriStr)
                val changesArray = JSONArray(changesJson)
                val appliedList = JSONArray()

                for (i in 0 until changesArray.length()) {
                    val change = changesArray.getJSONObject(i)
                    val action = change.optString("action", "MODIFY").uppercase()
                    val filePath = change.getString("filePath")

                    when (action) {
                        "CREATE", "MODIFY" -> {
                            val content = change.optString("content", "")
                            DocumentTreeHelper.writeRelativeFile(activity, rootUri, filePath, content)
                            appliedList.put(JSONObject().apply {
                                put("filePath", filePath)
                                put("action", action)
                            })
                        }
                        "DELETE" -> {
                            DocumentTreeHelper.deleteRelativeFile(activity, rootUri, filePath)
                            appliedList.put(JSONObject().apply {
                                put("filePath", filePath)
                                put("action", "DELETE")
                            })
                        }
                    }
                }

                // Rescan tree after all modifications successfully applied
                val updatedTree = DocumentTreeHelper.buildDirectoryTree(activity, rootUri)

                val resultObj = JSONObject().apply {
                    put("success", true)
                    put("applied", appliedList)
                    put("tree", updatedTree)
                }

                val resultStr = JSONObject.quote(resultObj.toString())
                evaluateJs("window.onAndroidBatchApplied && window.onAndroidBatchApplied(\"$callbackId\", true, $resultStr, null)")
            } catch (e: Exception) {
                e.printStackTrace()
                val error = JSONObject.quote(e.message ?: "Failed to apply changes to project directory")
                evaluateJs("window.onAndroidBatchApplied && window.onAndroidBatchApplied(\"$callbackId\", false, null, $error)")
            }
        }
    }

    @JavascriptInterface
    fun readFile(uriStr: String, callbackId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val uri = Uri.parse(uriStr)
                val content = DocumentTreeHelper.readFileContent(activity, uri)
                val escapedContent = JSONObject.quote(content)
                evaluateJs("window.onAndroidFileRead && window.onAndroidFileRead(\"$callbackId\", true, $escapedContent, null)")
            } catch (e: Exception) {
                val error = JSONObject.quote(e.message ?: "Unknown read error")
                evaluateJs("window.onAndroidFileRead && window.onAndroidFileRead(\"$callbackId\", false, null, $error)")
            }
        }
    }

    @JavascriptInterface
    fun writeFile(uriStr: String, content: String, callbackId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val uri = Uri.parse(uriStr)
                DocumentTreeHelper.writeFileContent(activity, uri, content)
                evaluateJs("window.onAndroidFileWritten && window.onAndroidFileWritten(\"$callbackId\", true, null, null)")
            } catch (e: Exception) {
                val error = JSONObject.quote(e.message ?: "Unknown write error")
                evaluateJs("window.onAndroidFileWritten && window.onAndroidFileWritten(\"$callbackId\", false, null, $error)")
            }
        }
    }

    @JavascriptInterface
    fun getSecureSecret(key: String): String {
        return SecureStorageHelper.getSecret(activity, key) ?: ""
    }

    @JavascriptInterface
    fun setSecureSecret(key: String, value: String): Boolean {
        return SecureStorageHelper.saveSecret(activity, key, value)
    }

    @JavascriptInterface
    fun removeSecureSecret(key: String) {
        SecureStorageHelper.removeSecret(activity, key)
    }

    @JavascriptInterface
    fun getDeviceInfo(): String {
        val obj = JSONObject()
        obj.put("os", "Android")
        obj.put("sdkInt", Build.VERSION.SDK_INT)
        obj.put("device", Build.MODEL)
        obj.put("manufacturer", Build.MANUFACTURER)
        obj.put("termuxSupported", true)
        return obj.toString()
    }

    @JavascriptInterface
    fun logToNative(message: String) {
        android.util.Log.d("CodexMobile", message)
    }

    // ==========================================
    // PERSISTENT LOCAL CHAT HISTORY (ROOM DB)
    // ==========================================

    @JavascriptInterface
    fun getChatConversations(callbackId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val db = CodexDatabase.getInstance(activity)
                val convos = db.chatDao().getAllConversations()
                val jsonArr = JSONArray()
                for (c in convos) {
                    jsonArr.put(JSONObject().apply {
                        put("id", c.id)
                        put("title", c.title)
                        put("createdAt", c.createdAt)
                        put("updatedAt", c.updatedAt)
                        put("projectUri", c.projectUri ?: "")
                        put("projectName", c.projectName ?: "")
                        put("model", c.model)
                    })
                }
                val escaped = JSONObject.quote(jsonArr.toString())
                evaluateJs("window.onAndroidChatConversationsLoaded && window.onAndroidChatConversationsLoaded(\"$callbackId\", true, $escaped, null)")
            } catch (e: Exception) {
                val err = JSONObject.quote(e.message ?: "Failed to load chat history")
                evaluateJs("window.onAndroidChatConversationsLoaded && window.onAndroidChatConversationsLoaded(\"$callbackId\", false, null, $err)")
            }
        }
    }

    @JavascriptInterface
    fun searchChatConversations(query: String, callbackId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val db = CodexDatabase.getInstance(activity)
                val convos = db.chatDao().searchConversations(query)
                val jsonArr = JSONArray()
                for (c in convos) {
                    jsonArr.put(JSONObject().apply {
                        put("id", c.id)
                        put("title", c.title)
                        put("createdAt", c.createdAt)
                        put("updatedAt", c.updatedAt)
                        put("projectUri", c.projectUri ?: "")
                        put("projectName", c.projectName ?: "")
                        put("model", c.model)
                    })
                }
                val escaped = JSONObject.quote(jsonArr.toString())
                evaluateJs("window.onAndroidChatConversationsLoaded && window.onAndroidChatConversationsLoaded(\"$callbackId\", true, $escaped, null)")
            } catch (e: Exception) {
                val err = JSONObject.quote(e.message ?: "Search failed")
                evaluateJs("window.onAndroidChatConversationsLoaded && window.onAndroidChatConversationsLoaded(\"$callbackId\", false, null, $err)")
            }
        }
    }

    @JavascriptInterface
    fun getChatMessages(conversationId: String, callbackId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val db = CodexDatabase.getInstance(activity)
                val messages = db.chatDao().getMessagesForConversation(conversationId)
                val jsonArr = JSONArray()
                for (m in messages) {
                    jsonArr.put(JSONObject().apply {
                        put("id", m.id)
                        put("conversationId", m.conversationId)
                        put("role", m.role)
                        put("content", m.content)
                        put("timestamp", m.timestamp)
                        put("proposalsJson", m.proposalsJson ?: "")
                        put("appliedStatus", m.appliedStatus)
                    })
                }
                val escaped = JSONObject.quote(jsonArr.toString())
                evaluateJs("window.onAndroidChatMessagesLoaded && window.onAndroidChatMessagesLoaded(\"$callbackId\", true, $escaped, null)")
            } catch (e: Exception) {
                val err = JSONObject.quote(e.message ?: "Failed to load messages")
                evaluateJs("window.onAndroidChatMessagesLoaded && window.onAndroidChatMessagesLoaded(\"$callbackId\", false, null, $err)")
            }
        }
    }

    @JavascriptInterface
    fun createChatConversation(
        title: String,
        projectUri: String,
        projectName: String,
        model: String,
        callbackId: String
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val id = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                val convo = ChatConversationEntity(
                    id = id,
                    title = title.ifEmpty { "New Conversation" },
                    createdAt = now,
                    updatedAt = now,
                    projectUri = projectUri.ifEmpty { null },
                    projectName = projectName.ifEmpty { null },
                    model = model.ifEmpty { "gpt-4o" }
                )
                val db = CodexDatabase.getInstance(activity)
                db.chatDao().insertConversation(convo)

                val res = JSONObject().apply {
                    put("id", id)
                    put("title", convo.title)
                    put("createdAt", now)
                    put("updatedAt", now)
                    put("projectUri", projectUri)
                    put("projectName", projectName)
                    put("model", model)
                }
                val escaped = JSONObject.quote(res.toString())
                evaluateJs("window.onAndroidChatConversationCreated && window.onAndroidChatConversationCreated(\"$callbackId\", true, $escaped, null)")
            } catch (e: Exception) {
                val err = JSONObject.quote(e.message ?: "Failed to create conversation")
                evaluateJs("window.onAndroidChatConversationCreated && window.onAndroidChatConversationCreated(\"$callbackId\", false, null, $err)")
            }
        }
    }

    @JavascriptInterface
    fun saveChatMessage(
        conversationId: String,
        role: String,
        content: String,
        proposalsJson: String,
        appliedStatus: Boolean,
        callbackId: String
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val db = CodexDatabase.getInstance(activity)
                val msgId = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                val msg = ChatMessageEntity(
                    id = msgId,
                    conversationId = conversationId,
                    role = role,
                    content = content,
                    timestamp = now,
                    proposalsJson = proposalsJson.ifEmpty { null },
                    appliedStatus = appliedStatus
                )
                db.chatDao().insertMessage(msg)
                db.chatDao().touchConversation(conversationId, now)
                evaluateJs("window.onAndroidChatMessageSaved && window.onAndroidChatMessageSaved(\"$callbackId\", true, \"$msgId\", null)")
            } catch (e: Exception) {
                val err = JSONObject.quote(e.message ?: "Failed to save message")
                evaluateJs("window.onAndroidChatMessageSaved && window.onAndroidChatMessageSaved(\"$callbackId\", false, null, $err)")
            }
        }
    }

    @JavascriptInterface
    fun updateChatTitle(conversationId: String, newTitle: String, callbackId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val db = CodexDatabase.getInstance(activity)
                db.chatDao().updateConversationTitle(conversationId, newTitle, System.currentTimeMillis())
                evaluateJs("window.onAndroidChatTitleUpdated && window.onAndroidChatTitleUpdated(\"$callbackId\", true, null)")
            } catch (e: Exception) {
                val err = JSONObject.quote(e.message ?: "Failed to update title")
                evaluateJs("window.onAndroidChatTitleUpdated && window.onAndroidChatTitleUpdated(\"$callbackId\", false, $err)")
            }
        }
    }

    @JavascriptInterface
    fun deleteChatConversation(conversationId: String, callbackId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val db = CodexDatabase.getInstance(activity)
                db.chatDao().deleteConversationById(conversationId)
                evaluateJs("window.onAndroidChatConversationDeleted && window.onAndroidChatConversationDeleted(\"$callbackId\", true, null)")
            } catch (e: Exception) {
                val err = JSONObject.quote(e.message ?: "Failed to delete conversation")
                evaluateJs("window.onAndroidChatConversationDeleted && window.onAndroidChatConversationDeleted(\"$callbackId\", false, $err)")
            }
        }
    }

    // ==========================================
    // BACKGROUND AI TASKS (FOREGROUND SERVICE)
    // ==========================================

    @JavascriptInterface
    fun startBackgroundAiTask(
        conversationId: String,
        prompt: String,
        fullContext: String,
        actionType: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        callbackId: String
    ) {
        val taskId = UUID.randomUUID().toString()
        val task = BackgroundAiTask(
            taskId = taskId,
            conversationId = conversationId,
            prompt = prompt,
            actionType = actionType,
            state = "QUEUED",
            progressMessage = "Task queued for background execution"
        )
        BackgroundTaskManager.addTask(task)

        AiBackgroundService.startTask(
            context = activity,
            taskId = taskId,
            conversationId = conversationId,
            prompt = prompt,
            fullContext = fullContext,
            actionType = actionType,
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            systemPrompt = systemPrompt
        )

        val res = JSONObject().apply {
            put("taskId", taskId)
            put("state", "QUEUED")
        }
        val escaped = JSONObject.quote(res.toString())
        evaluateJs("window.onAndroidBackgroundTaskStarted && window.onAndroidBackgroundTaskStarted(\"$callbackId\", true, $escaped, null)")
    }

    @JavascriptInterface
    fun getBackgroundTasks(callbackId: String) {
        val tasks = BackgroundTaskManager.getAllTasks()
        val arr = JSONArray()
        for (t in tasks) {
            arr.put(JSONObject().apply {
                put("taskId", t.taskId)
                put("conversationId", t.conversationId)
                put("prompt", t.prompt)
                put("actionType", t.actionType)
                put("state", t.state)
                put("progressMessage", t.progressMessage)
                put("resultText", t.resultText ?: "")
                put("errorMessage", t.errorMessage ?: "")
                put("startTime", t.startTime)
                put("finishTime", t.finishTime ?: 0)
            })
        }
        val escaped = JSONObject.quote(arr.toString())
        evaluateJs("window.onAndroidBackgroundTasksLoaded && window.onAndroidBackgroundTasksLoaded(\"$callbackId\", true, $escaped, null)")
    }

    @JavascriptInterface
    fun dismissBackgroundTask(taskId: String) {
        BackgroundTaskManager.removeTask(taskId)
    }

    // ==========================================
    // ANDROID APK BUILD SYSTEM (TERMUX / NATIVE)
    // ==========================================

    @JavascriptInterface
    fun checkTermuxToolchain(callbackId: String) {
        scope.launch(Dispatchers.IO) {
            val toolchain = ApkBuildHelper.checkToolchain(activity)
            val obj = JSONObject().apply {
                put("termuxInstalled", toolchain.termuxInstalled)
                put("openJdkAvailable", toolchain.openJdkAvailable)
                put("gradleAvailable", toolchain.gradleAvailable)
                put("buildToolsAvailable", toolchain.buildToolsAvailable)
                put("details", toolchain.details)
                put("setupScript", toolchain.setupScript)
            }
            val escaped = JSONObject.quote(obj.toString())
            evaluateJs("if (window.onAndroidTermuxToolchainChecked) window.onAndroidTermuxToolchainChecked(\"$callbackId\", true, $escaped, null); if (window.onAndroidToolchainChecked) window.onAndroidToolchainChecked(\"$callbackId\", true, $escaped, null);")
        }
    }

    @JavascriptInterface
    fun buildApk(projectName: String, filesJson: String, callbackId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val jsonObject = JSONObject(filesJson)
                val filesMap = mutableMapOf<String, ByteArray>()
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val textContent = jsonObject.getString(key)
                    filesMap[key] = textContent.toByteArray(Charsets.UTF_8)
                }

                val persistedUriStr = activity.getPersistedProjectUri()
                val persistedUri = if (!persistedUriStr.isNullOrBlank()) Uri.parse(persistedUriStr) else null

                val builtResult = ApkBuildHelper.buildWebApk(
                    context = activity,
                    projectName = projectName,
                    projectFilesMap = filesMap,
                    persistedRootUri = persistedUri
                ) { step, total, stepName, logLine ->
                    val stepNameEsc = JSONObject.quote(stepName)
                    val logLineEsc = JSONObject.quote(logLine)
                    val progressObj = JSONObject().apply {
                        put("step", step)
                        put("totalSteps", total)
                        put("stepName", stepName)
                        put("logLine", logLine)
                    }
                    val esc = JSONObject.quote(progressObj.toString())
                    evaluateJs("window.onAndroidApkBuildProgress && window.onAndroidApkBuildProgress(\"$callbackId\", $step, $total, $stepNameEsc, $logLineEsc, $esc)")
                }

                val builtApk = builtResult.file
                val apkPathEsc = JSONObject.quote(builtApk.absolutePath)
                val apkNameEsc = JSONObject.quote(builtApk.name)
                val apkSize = builtApk.length()
                val projectRelPathEsc = JSONObject.quote(builtResult.projectRelativePath)
                val downloadsPathEsc = JSONObject.quote(builtResult.downloadsPath)

                val resultObj = JSONObject().apply {
                    put("success", true)
                    put("apkPath", builtApk.absolutePath)
                    put("apkName", builtApk.name)
                    put("apkSize", apkSize)
                    put("projectPath", builtResult.projectRelativePath)
                    put("downloadsPath", builtResult.downloadsPath)
                }
                val esc = JSONObject.quote(resultObj.toString())
                evaluateJs("window.onAndroidApkBuildComplete && window.onAndroidApkBuildComplete(\"$callbackId\", true, $apkPathEsc, $apkNameEsc, $apkSize, null, $esc)")
            } catch (e: Exception) {
                e.printStackTrace()
                val err = JSONObject.quote(e.message ?: "Build failed")
                evaluateJs("window.onAndroidApkBuildComplete && window.onAndroidApkBuildComplete(\"$callbackId\", false, null, null, 0, $err)")
            }
        }
    }

    @JavascriptInterface
    fun saveApkToDownloads(apkPath: String, callbackId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                var file = File(apkPath)
                if (!file.exists()) {
                    val candidate = File(activity.cacheDir, "built_apks/${File(apkPath).name}")
                    if (candidate.exists()) file = candidate
                }
                if (!file.exists()) {
                    throw IllegalStateException("APK file not found at $apkPath")
                }
                val dest = ApkBuildHelper.saveApkToDownloads(activity, file)
                val destEsc = JSONObject.quote(dest)
                evaluateJs("window.onAndroidApkSavedToDownloads && window.onAndroidApkSavedToDownloads(\"$callbackId\", true, $destEsc, null)")
            } catch (e: Exception) {
                val err = JSONObject.quote(e.message ?: "Failed to save APK to Downloads")
                evaluateJs("window.onAndroidApkSavedToDownloads && window.onAndroidApkSavedToDownloads(\"$callbackId\", false, null, $err)")
            }
        }
    }

    @JavascriptInterface
    fun shareApk(apkPath: String, callbackId: String) {
        activity.runOnUiThread {
            try {
                var file = File(apkPath)
                if (!file.exists()) {
                    val candidate = File(activity.cacheDir, "built_apks/${File(apkPath).name}")
                    if (candidate.exists()) file = candidate
                }
                if (!file.exists()) {
                    throw IllegalStateException("APK file not found at $apkPath")
                }
                ApkBuildHelper.shareApk(activity, file)
                evaluateJs("window.onAndroidApkShared && window.onAndroidApkShared(\"$callbackId\", true, null)")
            } catch (e: Exception) {
                val err = JSONObject.quote(e.message ?: "Failed to share APK")
                evaluateJs("window.onAndroidApkShared && window.onAndroidApkShared(\"$callbackId\", false, $err)")
            }
        }
    }

    @JavascriptInterface
    fun installApk(apkPath: String, callbackId: String) {
        activity.runOnUiThread {
            try {
                var file = File(apkPath)
                if (!file.exists()) {
                    val candidate = File(activity.cacheDir, "built_apks/${File(apkPath).name}")
                    if (candidate.exists()) file = candidate
                }
                if (!file.exists()) {
                    throw IllegalStateException("APK file not found at $apkPath")
                }
                ApkBuildHelper.installApk(activity, file)
                evaluateJs("window.onAndroidApkInstalled && window.onAndroidApkInstalled(\"$callbackId\", true, null)")
            } catch (e: Exception) {
                val err = JSONObject.quote(e.message ?: "Failed to launch installer")
                evaluateJs("window.onAndroidApkInstalled && window.onAndroidApkInstalled(\"$callbackId\", false, $err)")
            }
        }
    }

    private fun evaluateJs(script: String) {
        activity.evaluateJavascript(script)
    }
}
