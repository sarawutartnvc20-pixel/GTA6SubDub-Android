package com.gta6subdub.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiClient {
    private const val SYSTEM_PROMPT =
        "แปลซับไตเติลเกม GTA 6 นี้เป็นภาษาไทยแนวภาษาพูด สตรีท และคำแสลงที่เป็นธรรมชาติ ให้กระชับเข้ากับบริบทตัวละคร ตอบกลับมาเฉพาะคำแปลภาษาไทยเท่านั้น ห้ามอธิบายเพิ่มเติม"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun translateSubtitle(
        englishText: String,
        apiKey: String,
        model: String = "gemini-2.5-flash"
    ): String = withContext(Dispatchers.IO) {
        val safeKey = apiKey.trim()
        require(safeKey.isNotEmpty()) { "ยังไม่ได้ตั้งค่า Gemini API Key" }

        val safeModel = model.trim().ifEmpty { "gemini-2.5-flash" }
        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/$safeModel:generateContent?key=$safeKey"

        val payload = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))
                )
            )
            put(
                "contents",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().put("text", englishText)))
                    }
                )
            )
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", 0.4)
                    put("maxOutputTokens", 120)
                }
            )
        }

        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Gemini API error (${response.code}): $bodyStr")
            }

            val root = JSONObject(bodyStr)
            val candidates = root.optJSONArray("candidates")
                ?: throw IllegalStateException("Gemini API ไม่ได้ส่งคำแปลกลับมา")
            if (candidates.length() == 0) {
                throw IllegalStateException("Gemini API ไม่ได้ส่งคำแปลกลับมา")
            }

            val parts = candidates.getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")

            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                sb.append(parts.getJSONObject(i).optString("text", ""))
            }
            val result = sb.toString().trim()
            if (result.isEmpty()) {
                throw IllegalStateException("คำแปลว่างเปล่า")
            }
            result
        }
    }
}
