package com.shakerecorder

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class GeminiTranscriber {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun transcribe(
        filePath: String,
        apiKey: String,
        model: String,
        prompt: String
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val file = File(filePath)
                if (!file.exists()) {
                    return@withContext Result.failure(Exception("File not found: $filePath"))
                }

                val base64Audio = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)

                val inlineData = JSONObject().apply {
                    put("mime_type", "audio/mp4")
                    put("data", base64Audio)
                }
                val audioPart = JSONObject().apply { put("inline_data", inlineData) }
                val textPart = JSONObject().apply { put("text", prompt) }
                val parts = JSONArray().apply {
                    put(audioPart)
                    put(textPart)
                }
                val content = JSONObject().apply { put("parts", parts) }
                val contents = JSONArray().apply { put(content) }
                val generationConfig = JSONObject().apply { put("temperature", 0.0) }

                val body = JSONObject().apply {
                    put("contents", contents)
                    put("generationConfig", generationConfig)
                }

                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                val request = Request.Builder()
                    .url(url)
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("Gemini error ${response.code}: ${responseBody.take(300)}")
                    )
                }

                val transcript = parseTranscript(responseBody)
                if (transcript.isNullOrBlank()) {
                    Result.failure(Exception("Gemini retornou resposta vazia"))
                } else {
                    Result.success(transcript.trim())
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun parseTranscript(json: String): String? {
        return try {
            val root = JSONObject(json)
            val candidates = root.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            val parts = candidates.getJSONObject(0)
                .optJSONObject("content")
                ?.optJSONArray("parts")
                ?: return null
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val text = parts.getJSONObject(i).optString("text", "")
                if (text.isNotEmpty()) sb.append(text)
            }
            sb.toString()
        } catch (e: Exception) {
            null
        }
    }
}
