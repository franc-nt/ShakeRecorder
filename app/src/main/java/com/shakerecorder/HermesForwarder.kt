package com.shakerecorder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class HermesForwarder {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun forward(
        transcript: String,
        baseUrl: String,
        apiKey: String,
        instructions: String,
        sessionId: String
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val normalizedBase = baseUrl.trimEnd('/')
                val url = "$normalizedBase/v1/responses"

                val payload = JSONObject().apply {
                    put("input", transcript)
                    if (instructions.isNotBlank()) put("instructions", instructions)
                    put("store", true)
                }

                val builder = Request.Builder()
                    .url(url)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("Content-Type", "application/json")

                if (apiKey.isNotBlank()) {
                    builder.addHeader("Authorization", "Bearer $apiKey")
                }
                if (sessionId.isNotBlank()) {
                    builder.addHeader("X-Hermes-Session-Id", sessionId)
                }

                val response = client.newCall(builder.build()).execute()
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    Result.success("Hermes OK: ${response.code}")
                } else {
                    Result.failure(
                        Exception("Hermes error ${response.code}: ${responseBody.take(300)}")
                    )
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
