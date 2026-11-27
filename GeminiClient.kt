package com.gta6subdub.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

object GeminiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    // Keeps last 4 dialogue turns so Gemini maintains pronoun & character context across lines
    private val recentHistory = ArrayDeque<Pair<String, String>>()

    fun clearHistory() {
        synchronized(recentHistory) {
            recentHistory.clear()
        }
    }

    suspend fun translateSubtitle(
        context: Context,
        englishText: String,
        apiKey: String,
        model: String = "gemini-3.8-flash",
        gameId: String = "gta6"
    ): String = withContext(Dispatchers.IO) {
        val safeKey = apiKey.trim()
        require(safeKey.isNotEmpty()) { "ยังไม่ได้ตั้งค่า Gemini API Key" }

        val rawModel = model.trim()
        val safeModel = if (rawModel.isEmpty() || rawModel == "gemini-2.5-flash") {
            "gemini-3.8-flash"
        } else {
            rawModel
        }
        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/$safeModel:generateContent?key=$safeKey"

        val systemPrompt = GamePackManager.buildSystemPrompt(context, gameId)

        val userPrompt = buildString {
            synchronized(recentHistory) {
                if (recentHistory.isNotEmpty()) {
                    append("บริบทบทพูดก่อนหน้า (เพื่อความต่อเนื่องของสรรพนาม):\n")
                    for ((en, th) in recentHistory) {
                        append("EN: ").append(en).append(" -> TH: ").append(th).append("\n")
                    }
                    append("\n")
                }
            }
            append("แปลประโยคนี้:\n").append(englishText)
        }

        val safetySettings = JSONArray().apply {
            listOf(
                "HARM_CATEGORY_HARASSMENT",
                "HARM_CATEGORY_HATE_SPEECH",
                "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                "HARM_CATEGORY_DANGEROUS_CONTENT"
            ).forEach { category ->
                put(
                    JSONObject().apply {
                        put("category", category)
                        put("threshold", "BLOCK_NONE")
                    }
                )
            }
        }

        val payload = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systemPrompt))
                )
            )
            put(
                "contents",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
                    }
                )
            )
            put("safetySettings", safetySettings)
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", 0.45)
                    put("maxOutputTokens", 140)
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
                .optJSONObject("content")
                ?.optJSONArray("parts")
                ?: throw IllegalStateException("Gemini ไม่ส่งข้อความ (อาจติด safety filter)")

            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                sb.append(parts.getJSONObject(i).optString("text", ""))
            }
            val result = sb.toString()
                .trim()
                .removePrefix("\"")
                .removeSuffix("\"")
                .trim()

            if (result.isEmpty()) {
                throw IllegalStateException("คำแปลว่างเปล่า")
            }

            synchronized(recentHistory) {
                if (recentHistory.size >= 4) recentHistory.removeFirst()
                recentHistory.addLast(englishText to result)
            }

            result
        }
    }
}
