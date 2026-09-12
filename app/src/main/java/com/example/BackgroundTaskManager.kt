package com.example

import java.util.concurrent.ConcurrentHashMap

data class BackgroundAiTask(
    val taskId: String,
    val conversationId: String,
    val prompt: String,
    val actionType: String,
    var state: String, // "QUEUED", "RUNNING", "COMPLETED", "FAILED"
    var progressMessage: String,
    var resultText: String? = null,
    var errorMessage: String? = null,
    val startTime: Long = System.currentTimeMillis(),
    var finishTime: Long? = null
)

object BackgroundTaskManager {
    private val tasks = ConcurrentHashMap<String, BackgroundAiTask>()
    private val listeners = mutableListOf<(BackgroundAiTask) -> Unit>()

    fun addTask(task: BackgroundAiTask) {
        tasks[task.taskId] = task
        notifyListeners(task)
    }

    fun getTask(taskId: String): BackgroundAiTask? = tasks[taskId]

    fun getAllTasks(): List<BackgroundAiTask> = tasks.values.toList().sortedByDescending { it.startTime }

    fun updateTaskState(
        taskId: String,
        state: String,
        progressMessage: String,
        resultText: String? = null,
        errorMessage: String? = null
    ) {
        val task = tasks[taskId] ?: return
        task.state = state
        task.progressMessage = progressMessage
        if (resultText != null) task.resultText = resultText
        if (errorMessage != null) task.errorMessage = errorMessage
        if (state == "COMPLETED" || state == "FAILED") {
            task.finishTime = System.currentTimeMillis()
        }
        notifyListeners(task)
    }

    fun addListener(listener: (BackgroundAiTask) -> Unit) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: (BackgroundAiTask) -> Unit) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    private fun notifyListeners(task: BackgroundAiTask) {
        synchronized(listeners) {
            for (listener in listeners) {
                try {
                    listener(task)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun removeTask(taskId: String) {
        tasks.remove(taskId)
    }
}
