package com.shakerecorder

import org.json.JSONObject

data class UploadItem(
    val id: String,
    val filePath: String,
    val destination: UploadDestination,
    val createdAt: Long,
    val retryCount: Int = 0,
    val lastError: String? = null
) {
    enum class UploadDestination { WEBHOOK, TELEGRAM }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("filePath", filePath)
        put("destination", destination.name)
        put("createdAt", createdAt)
        put("retryCount", retryCount)
        put("lastError", lastError ?: "")
    }

    companion object {
        fun fromJson(json: JSONObject): UploadItem = UploadItem(
            id = json.getString("id"),
            filePath = json.getString("filePath"),
            destination = UploadDestination.valueOf(json.getString("destination")),
            createdAt = json.getLong("createdAt"),
            retryCount = json.optInt("retryCount", 0),
            lastError = json.optString("lastError").ifEmpty { null }
        )
    }
}
