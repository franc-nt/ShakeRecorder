package com.shakerecorder

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File

class UploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "ShakeRecorder"
        const val KEY_ITEM_ID = "item_id"
    }

    private val settingsManager = SettingsManager(applicationContext)
    private val queueManager = UploadQueueManager(applicationContext)
    private val webhookUploader = WebhookUploader()
    private val telegramUploader = TelegramUploader()

    override suspend fun doWork(): Result {
        val itemId = inputData.getString(KEY_ITEM_ID)

        return if (itemId != null) {
            processItem(itemId)
        } else {
            processAllPending()
        }
    }

    private suspend fun processItem(itemId: String): Result {
        val item = queueManager.getQueue().find { it.id == itemId }
            ?: return Result.success()

        return uploadItem(item)
    }

    private suspend fun processAllPending(): Result {
        val pending = queueManager.getPendingItems()
        var allSuccess = true

        for (item in pending) {
            val result = uploadItem(item)
            if (result != Result.success()) {
                allSuccess = false
            }
        }

        return if (allSuccess) Result.success() else Result.retry()
    }

    private suspend fun uploadItem(item: UploadItem): Result {
        val file = File(item.filePath)
        if (!file.exists()) {
            Log.w(TAG, "File no longer exists: ${item.filePath}")
            queueManager.removeFromQueue(item.id)
            return Result.success()
        }

        val result = when (item.destination) {
            UploadItem.UploadDestination.WEBHOOK -> {
                val url = settingsManager.webhookUrl
                if (url.isBlank()) {
                    queueManager.removeFromQueue(item.id)
                    return Result.success()
                }
                webhookUploader.uploadFile(item.filePath, url)
            }
            UploadItem.UploadDestination.TELEGRAM -> {
                val token = settingsManager.telegramBotToken
                val chatId = settingsManager.telegramChatId
                if (token.isBlank() || chatId.isBlank()) {
                    queueManager.removeFromQueue(item.id)
                    return Result.success()
                }
                telegramUploader.uploadAudio(item.filePath, token, chatId)
            }
        }

        return result.fold(
            onSuccess = {
                Log.d(TAG, "Upload successful: ${item.destination}")
                queueManager.removeFromQueue(item.id)
                Result.success()
            },
            onFailure = { error ->
                Log.w(TAG, "Upload failed: ${error.message}")
                val updated = queueManager.incrementRetry(item.id, error.message ?: "Unknown error")

                if (updated != null && updated.retryCount >= UploadQueueManager.MAX_RETRIES) {
                    Log.e(TAG, "Max retries reached for ${item.id}")
                    Result.success()
                } else {
                    Result.retry()
                }
            }
        )
    }
}
