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
            val missingArr = org.json.JSONArray()
            for (tool in toolchain.missingComponents) missingArr.put(tool)
            val obj = JSONObject().apply {
                put("termuxInstalled", toolchain.termuxInstalled)
                put("shellAvailable", toolchain.shellAvailable)
                put("openJdkAvailable", toolchain.openJdkAvailable)
                put("jdkVersion", toolchain.jdkVersion ?: JSONObject.NULL)
                put("gradleAvailable", toolchain.gradleAvailable)
                put("gradleVersion", toolchain.gradleVersion ?: JSONObject.NULL)
                put("buildToolsAvailable", toolchain.buildToolsAvailable)
                put("sdkDir", toolchain.sdkDir ?: JSONObject.NULL)
                put("platformInstalled", toolchain.platformInstalled)
                put("buildToolsInstalled", toolchain.buildToolsInstalled)
                put("ready", toolchain.ready)
                put("missingTools", missingArr)
                put("details", toolchain.details)
                put("setupScript", toolchain.setupScript)
            }
            val escaped = JSONObject.quote(obj.toString())
            // Canonical callback name consumed by bridge.js.
            evaluateJs("window.onAndroidTermuxToolchainChecked && window.onAndroidTermuxToolchainChecked(\"$callbackId\", true, $escaped, null)")
            // Legacy alias kept for older cached web bundles that listen on the old name.
            evaluateJs("window.onAndroidToolchainChecked && window.onAndroidToolchainChecked(\"$callbackId\", true, $escaped, null)")
        }
    }

    /**
     * Runs automatic build-environment setup (JDK check, SDK + Gradle
     * provisioning with caching). Progress streams via
     * `onAndroidEnvSetupProgress`; completion via `onAndroidEnvSetupComplete`.
     */
    @JavascriptInterface
    fun setupBuildEnvironment(callbackId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val status = AndroidBuildEnvironment.setup(activity) { phase, message ->
                    message.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                        val progressObj = JSONObject().apply {
                            put("phase", phase)
                            put("message", line.trim().take(300))
                        }
                        val esc = JSONObject.quote(progressObj.toString())
                        evaluateJs("window.onAndroidEnvSetupProgress && window.onAndroidEnvSetupProgress(\"$callbackId\", $esc)")
                    }
                }
                val summary = JSONObject().apply {
                    put("ready", status.ready)
                    put("sdkDir", status.sdkDir ?: JSONObject.NULL)
                    put("jdkMajor", status.jdkMajor ?: JSONObject.NULL)
                    put("gradleVersion", status.gradleVersion ?: JSONObject.NULL)
                    val missingArr = org.json.JSONArray()
                    for (tool in status.missing) missingArr.put(tool)
                    put("missing", missingArr)
                }
                val esc = JSONObject.quote(summary.toString())
                evaluateJs("window.onAndroidEnvSetupComplete && window.onAndroidEnvSetupComplete(\"$callbackId\", true, $esc, null)")
            } catch (e: Exception) {
                e.printStackTrace()
                val err = JSONObject.quote(e.message ?: "Environment setup failed")
                evaluateJs("window.onAndroidEnvSetupComplete && window.onAndroidEnvSetupComplete(\"$callbackId\", false, null, $err)")
            }
        }
    }

    @JavascriptInterface
    fun openTermuxApp(callbackId: String) {
        activity.runOnUiThread {
            try {
                if (ApkBuildHelper.openTermuxApp(activity)) {
                    evaluateJs("window.onAndroidTermuxOpened && window.onAndroidTermuxOpened(\"$callbackId\", true, null)")
                } else {
                    evaluateJs("window.onAndroidTermuxOpened && window.onAndroidTermuxOpened(\"$callbackId\", false, \"Termux app is not installed\")")
                }
            } catch (e: Exception) {
                val err = JSONObject.quote(e.message ?: "Could not open Termux")
                evaluateJs("window.onAndroidTermuxOpened && window.onAndroidTermuxOpened(\"$callbackId\", false, $err)")
            }
        }
    }

    @JavascriptInterface
    fun openDownloads(callbackId: String) {
        activity.runOnUiThread {
            try {
                if (ApkBuildHelper.openDownloads(activity)) {
                    evaluateJs("window.onAndroidDownloadsOpened && window.onAndroidDownloadsOpened(\"$callbackId\", true, null)")
                } else {
                    evaluateJs("window.onAndroidDownloadsOpened && window.onAndroidDownloadsOpened(\"$callbackId\", false, \"No app can open Downloads\")")
                }
            } catch (e: Exception) {
                val err = JSONObject.quote(e.message ?: "Could not open Downloads")
                evaluateJs("window.onAndroidDownloadsOpened && window.onAndroidDownloadsOpened(\"$callbackId\", false, $err)")
            }
        }
    }

    @JavascriptInterface
    fun openProjectLocation(projectRootUriStr: String, callbackId: String) {
        activity.runOnUiThread {
            try {
                if (projectRootUriStr.isNotBlank() && ApkBuildHelper.openProjectLocation(activity, projectRootUriStr)) {
                    evaluateJs("window.onAndroidLocationOpened && window.onAndroidLocationOpened(\"$callbackId\", true, null)")
                } else if (ApkBuildHelper.openDownloads(activity)) {
                    evaluateJs("window.onAndroidLocationOpened && window.onAndroidLocationOpened(\"$callbackId\", true, null)")
                } else {
                    evaluateJs("window.onAndroidLocationOpened && window.onAndroidLocationOpened(\"$callbackId\", false, \"No file manager available\")")
                }
            } catch (e: Exception) {
                val err = JSONObject.quote(e.message ?: "Could not open APK location")
                evaluateJs("window.onAndroidLocationOpened && window.onAndroidLocationOpened(\"$callbackId\", false, $err)")
            }
        }
    }

    @JavascriptInterface
    fun buildApk(projectName: String, filesJson: String, callbackId: String) {
        buildApkInternal(projectName, filesJson, callbackId, null, null)
    }

    @JavascriptInterface
    fun buildApk(projectName: String, filesJson: String, callbackId: String, projectRootUriStr: String) {
        buildApkInternal(projectName, filesJson, callbackId, projectRootUriStr, null)
    }

    /**
     * Options-driven build. [optionsJson] follows the [ApkBuildOptions] JSON
     * contract (buildType debug|release, applicationId, versionCode,
     * versionName, minSdk, targetSdk, projectRootUri, release keystore...).
     * Success is reported ONLY for a signed APK that passes automated
     * installability validation; any failure surfaces the real error.
     */
    @JavascriptInterface
    fun buildApkWithOptions(projectName: String, filesJson: String, callbackId: String, optionsJson: String) {
        buildApkInternal(projectName, filesJson, callbackId, null, optionsJson)
    }

    private fun buildApkInternal(
        projectName: String,
        filesJson: String,
        callbackId: String,
        projectRootUriStr: String?,
        optionsJson: String?
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val options = ApkBuildOptions.fromJson(optionsJson)
                val jsonObject = JSONObject(filesJson)
                val filesMap = mutableMapOf<String, ByteArray>()
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val textContent = jsonObject.getString(key)
                    filesMap[key] = textContent.toByteArray(Charsets.UTF_8)
                }

                // Resolve the SAF project folder: explicit URI from options/JS wins,
                // otherwise fall back to the persisted project URI.
                val explicitRoot = options.projectRootUri?.takeIf { it.isNotBlank() }
                    ?: projectRootUriStr?.takeIf { it.isNotBlank() }
                val resolvedRootUri: Uri? = try {
                    val explicit = explicitRoot?.let { Uri.parse(it) }
                    explicit ?: activity.getPersistedProjectUri()?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
                } catch (e: Exception) {
                    null
                }

                val result = ApkBuildHelper.buildWebApk(
                    context = activity,
                    projectName = projectName,
                    projectFilesMap = filesMap,
                    projectRootUri = resolvedRootUri,
                    options = options,
                    onProgress = { step, total, stepName, logLine ->
                        val progressObj = JSONObject().apply {
                            put("step", step)
                            put("totalSteps", total)
                            put("stepName", stepName)
                            put("logLine", logLine)
                        }
                        val esc = JSONObject.quote(progressObj.toString())
                        evaluateJs("window.onAndroidApkBuildProgress && window.onAndroidApkBuildProgress(\"$callbackId\", $esc)")
                    }
                )

                val builtApk = result.cacheFile
                // Guarantee non-null contract: never emit null apkName/apkSize
                // (the previous 6-arg bridge contract produced nulls in JS).
                val safeName = builtApk.name.ifBlank { "${projectName.ifBlank { "WebApp" }}-debug.apk" }
                val checksArr = org.json.JSONArray()
                for (check in result.validationChecks) {
                    checksArr.put(JSONObject().apply {
                        put("name", check.name)
                        put("passed", check.passed)
                        put("detail", check.detail)
                    })
                }
                val validationObj = JSONObject().apply {
                    put("valid", result.validationChecks.isNotEmpty() && result.validationChecks.all { it.passed })
                    put("checks", checksArr)
                }
                val resultObj = JSONObject().apply {
                    put("success", true)
                    put("apkPath", builtApk.absolutePath)
                    put("apkName", safeName)
                    put("apkSize", builtApk.length())
                    put("savedToProject", result.projectUri != null)
                    put("projectRelativePath", result.projectRelativePath ?: JSONObject.NULL)
                    put("projectUri", result.projectUri ?: JSONObject.NULL)
                    put("downloadPath", result.downloadFile?.absolutePath ?: JSONObject.NULL)
                    put("buildType", result.buildType)
                    put("packageName", result.applicationId)
                    put("versionCode", result.versionCode)
                    put("versionName", result.versionName)
                    put("validation", validationObj)
                }
                val esc = JSONObject.quote(resultObj.toString())
                evaluateJs("window.onAndroidApkBuildComplete && window.onAndroidApkBuildComplete(\"$callbackId\", true, $esc, null)")
            } catch (e: Exception) {
                e.printStackTrace()
                val err = JSONObject.quote(e.message ?: "Build failed")
                evaluateJs("window.onAndroidApkBuildComplete && window.onAndroidApkBuildComplete(\"$callbackId\", false, null, $err)")
            }
        }
    }

    @JavascriptInterface
    fun shareApk(apkPath: String, callbackId: String) {
        activity.runOnUiThread {
            try {
                val file = File(apkPath)
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
                val file = File(apkPath)
                if (!file.exists()) {
                    throw IllegalStateException("APK file not found at $apkPath. Please rebuild the APK.")
                }
                // Never hand a corrupt/unsigned APK to the system installer:
                // that path only ever ends in "App not installed".
                val readinessError = ApkBuildHelper.preInstallCheck(file)
                if (readinessError != null) {
                    throw IllegalStateException(readinessError)
                }
                ApkBuildHelper.installApk(activity, file)
                evaluateJs("window.onAndroidApkInstalled && window.onAndroidApkInstalled(\"$callbackId\", true, null)")
            } catch (e: Exception) {
                val err = JSONObject.quote(e.message ?: "Failed to launch installer")
                evaluateJs("window.onAndroidApkInstalled && window.onAndroidApkInstalled(\"$callbackId\", false, $err)")
            }
        }
    }

    /**
     * Whether the system will allow this app to trigger APK installs right
     * now (Android 8+ "install unknown apps" gate). Synchronous so the web UI
     * can guide the user to Settings before opening the installer.
     */
    @JavascriptInterface
    fun canRequestPackageInstalls(): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                activity.packageManager.canRequestPackageInstalls()
            } else {
                true
            }
        } catch (e: Exception) {
            true
        }
    }

    /** Opens the system "Install unknown apps" screen for this app. */
    @JavascriptInterface
    fun openUnknownSourcesSettings() {
        activity.runOnUiThread {
            try {
                activity.openUnknownSourcesSettings()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun evaluateJs(script: String) {
        activity.evaluateJavascript(script)
    }
}
