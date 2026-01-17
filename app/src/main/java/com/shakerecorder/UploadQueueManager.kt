package com.shakerecorder

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import java.util.UUID

class UploadQueueManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("upload_queue", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_QUEUE = "pending_uploads"
        const val MAX_RETRIES = 5
    }

    fun addToQueue(filePath: String, destination: UploadItem.UploadDestination): UploadItem {
        val item = UploadItem(
            id = UUID.randomUUID().toString(),
            filePath = filePath,
            destination = destination,
            createdAt = System.currentTimeMillis()
        )

        val queue = getQueue().toMutableList()
        queue.add(item)
        saveQueue(queue)

        return item
    }

    fun getQueue(): List<UploadItem> {
        val json = prefs.getString(KEY_QUEUE, "[]") ?: "[]"
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { UploadItem.fromJson(array.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getPendingItems(): List<UploadItem> {
        return getQueue().filter { it.retryCount < MAX_RETRIES }
    }

    fun removeFromQueue(id: String) {
        val queue = getQueue().toMutableList()
        queue.removeAll { it.id == id }
        saveQueue(queue)
    }

    fun incrementRetry(id: String, error: String): UploadItem? {
        val queue = getQueue().toMutableList()
        val index = queue.indexOfFirst { it.id == id }
        if (index >= 0) {
            val updated = queue[index].copy(
                retryCount = queue[index].retryCount + 1,
                lastError = error
            )
            queue[index] = updated
            saveQueue(queue)
            return updated
        }
        return null
    }

    private fun saveQueue(queue: List<UploadItem>) {
        val array = JSONArray()
        queue.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_QUEUE, array.toString()).apply()
    }

    fun clearOldItems() {
        val cutoff = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
        val queue = getQueue().filter {
            it.createdAt > cutoff && it.retryCount < MAX_RETRIES
        }
        saveQueue(queue)
    }
}
