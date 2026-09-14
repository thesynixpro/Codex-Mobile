package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.db.ChatMessageEntity
import com.example.db.CodexDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class AiBackgroundService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        const val CHANNEL_ID = "codex_ai_background_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_TASK = "com.example.ACTION_START_TASK"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        const val EXTRA_PROMPT = "extra_prompt"
        const val EXTRA_FULL_CONTEXT = "extra_full_context"
        const val EXTRA_ACTION_TYPE = "extra_action_type"
        const val EXTRA_BASE_URL = "extra_base_url"
        const val EXTRA_API_KEY = "extra_api_key"
        const val EXTRA_MODEL = "extra_model"
        const val EXTRA_SYSTEM_PROMPT = "extra_system_prompt"

        fun startTask(
            context: Context,
            taskId: String,
            conversationId: String,
            prompt: String,
            fullContext: String,
            actionType: String,
            baseUrl: String,
            apiKey: String,
            model: String,
            systemPrompt: String
        ) {
            val intent = Intent(context, AiBackgroundService::class.java).apply {
                action = ACTION_START_TASK
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
                putExtra(EXTRA_PROMPT, prompt)
                putExtra(EXTRA_FULL_CONTEXT, fullContext)
                putExtra(EXTRA_ACTION_TYPE, actionType)
                putExtra(EXTRA_BASE_URL, baseUrl)
                putExtra(EXTRA_API_KEY, apiKey)
                putExtra(EXTRA_MODEL, model)
                putExtra(EXTRA_SYSTEM_PROMPT, systemPrompt)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_TASK) {
            val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: UUID.randomUUID().toString()
            val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: ""
            val prompt = intent.getStringExtra(EXTRA_PROMPT) ?: ""
            val fullContext = intent.getStringExtra(EXTRA_FULL_CONTEXT) ?: prompt
            val actionType = intent.getStringExtra(EXTRA_ACTION_TYPE) ?: "custom"
            val baseUrl = intent.getStringExtra(EXTRA_BASE_URL) ?: "https://api.openai.com/v1"
            val apiKey = intent.getStringExtra(EXTRA_API_KEY) ?: ""
            val model = intent.getStringExtra(EXTRA_MODEL) ?: "gpt-4o"
            val systemPrompt = intent.getStringExtra(EXTRA_SYSTEM_PROMPT) ?: ""

            startForegroundWithNotification("Codex AI Task Started", "Processing AI request in background...")

            serviceScope.launch {
                executeAiRequest(
                    taskId,
                    conversationId,
                    prompt,
                    fullContext,
                    actionType,
                    baseUrl,
                    apiKey,
                    model,
                    systemPrompt
                )
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Codex AI Background Tasks",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors ongoing background AI generation, code analysis, and build operations."
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification(title: String, content: String) {
        val notification = buildNotification(title, content, ongoing = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(title: String, content: String, ongoing: Boolean): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(title: String, content: String, ongoing: Boolean) {
        val notification = buildNotification(title, content, ongoing)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private suspend fun executeAiRequest(
        taskId: String,
        conversationId: String,
        userPrompt: String,
        fullContext: String,
        actionType: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String
    ) {
        BackgroundTaskManager.updateTaskState(
            taskId = taskId,
            state = "RUNNING",
            progressMessage = "Connecting to $model at $baseUrl..."
        )
        updateNotification("Codex AI: $model", "Generating code and analyzing workspace...", true)

        try {
            val cleanBaseUrl = baseUrl.trim().replace(Regex("/+$"), "")
            val chatUrl = "$cleanBaseUrl/chat/completions"

            val messagesArray = JSONArray()
            if (systemPrompt.isNotEmpty()) {
                messagesArray.put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
            }
            messagesArray.put(JSONObject().apply {
                put("role", "user")
                put("content", fullContext)
            })

            val requestBodyJson = JSONObject().apply {
                put("model", model)
                put("messages", messagesArray)
                put("temperature", 0.2)
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBuilder = Request.Builder()
                .url(chatUrl)
                .post(requestBodyJson.toString().toRequestBody(mediaType))

            if (apiKey.isNotEmpty()) {
                requestBuilder.addHeader("Authorization", "Bearer $apiKey")
            }

            val request = requestBuilder.build()
            val response = httpClient.newCall(request).execute()

            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                var errMsg = "HTTP ${response.code} ${response.message}"
                try {
                    val errJson = JSONObject(responseBody)
                    if (errJson.has("error") && errJson.getJSONObject("error").has("message")) {
                        errMsg = errJson.getJSONObject("error").getString("message")
                    }
                } catch (ignored: Exception) {}

                BackgroundTaskManager.updateTaskState(
                    taskId = taskId,
                    state = "FAILED",
                    progressMessage = "AI request failed",
                    errorMessage = errMsg
                )
                updateNotification("Codex AI Task Failed", errMsg, false)
                stopForegroundSafely()
                return
            }

            val jsonResponse = JSONObject(responseBody)
            val choices = jsonResponse.optJSONArray("choices")
            val assistantText = if (choices != null && choices.length() > 0) {
                choices.getJSONObject(0).optJSONObject("message")?.optString("content", "") ?: ""
            } else {
                "No output generated by model."
            }

            // Save completed message into Room DB if conversationId is set
            if (conversationId.isNotEmpty()) {
                try {
                    val db = CodexDatabase.getInstance(applicationContext)
                    val assistantMsg = ChatMessageEntity(
                        id = UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        role = "assistant",
                        content = assistantText,
                        timestamp = System.currentTimeMillis()
                    )
                    db.chatDao().insertMessage(assistantMsg)
                    db.chatDao().touchConversation(conversationId, System.currentTimeMillis())
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            BackgroundTaskManager.updateTaskState(
                taskId = taskId,
                state = "COMPLETED",
                progressMessage = "Completed successfully",
                resultText = assistantText
            )
            updateNotification("Codex AI: Task Completed", "New code proposals ready in workspace", false)
        } catch (e: Exception) {
            e.printStackTrace()
            val msg = e.message ?: "Background AI execution interrupted"
            BackgroundTaskManager.updateTaskState(
                taskId = taskId,
                state = "FAILED",
                progressMessage = "Failed: $msg",
                errorMessage = msg
            )
            updateNotification("Codex AI Task Failed", msg, false)
        } finally {
            stopForegroundSafely()
        }
    }

    private fun stopForegroundSafely() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}
