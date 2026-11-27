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
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build()

    // Verified live Gemini 3.x models (3.1-flash-lite is ultra-fast with much higher free quota)
    private val fallbackModels = listOf(
        "gemini-3.1-flash-lite",
        "gemini-3.8-flash"
    )

    @Volatile
    var lastUsedEngine: String = "Gemini"
        private set

    // Keeps last 2 dialogue turns (compact for faster speed & lower token usage)
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
        model: String = "gemini-3.1-flash-lite",
        gameId: String = "gta6"
    ): String = withContext(Dispatchers.IO) {
        val safeKey = apiKey.trim()
        require(safeKey.isNotEmpty()) { "ยังไม่ได้ตั้งค่า Gemini API Key" }

        val preferred = model.trim().let {
            when {
                it.isEmpty() || it == "gemini-2.5-flash" || it == "gemini-2.0-flash" -> "gemini-3.1-flash-lite"
                it == "gemini-3.8-flash-lite" || it == "gemini-2.5-flash-lite" -> "gemini-3.1-flash-lite"
                else -> it
            }
        }
        val modelsToTry = (listOf(preferred) + fallbackModels).distinct()

        val systemPrompt = GamePackManager.buildSystemPrompt(context, gameId)
        val userPrompt = buildString {
            synchronized(recentHistory) {
                if (recentHistory.isNotEmpty()) {
                    append("ก่อนหน้า:\n")
                    for ((en, th) in recentHistory) {
                        append("EN: ").append(en).append(" -> ").append(th).append("\n")
                    }
                    append("\n")
                }
            }
            append(englishText)
        }

        var primaryError: Exception? = null
        var sawRateLimit = false

        for ((index, candidateModel) in modelsToTry.withIndex()) {
            try {
                val result = callGenerateContent(
                    apiKey = safeKey,
                    model = candidateModel,
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    useFastThinking = true
                )
                lastUsedEngine = candidateModel
                rememberHistory(englishText, result)
                return@withContext result
            } catch (e: HttpStatusException) {
                if (index == 0) primaryError = e
                if (e.code == 429) {
                    sawRateLimit = true
                    continue
                }
                if (e.code == 400) {
                    try {
                        val retryResult = callGenerateContent(
                            apiKey = safeKey,
                            model = candidateModel,
                            systemPrompt = systemPrompt,
                            userPrompt = userPrompt,
                            useFastThinking = false
                        )
                        lastUsedEngine = candidateModel
                        rememberHistory(englishText, retryResult)
                        return@withContext retryResult
                    } catch (retryErr: Exception) {
                        if (index == 0) primaryError = retryErr
                    }
                }
                if (e.code == 404) {
                    continue
                }
            } catch (e: Exception) {
                if (index == 0) primaryError = e
                continue
            }
        }

        // If Gemini Free Tier quota (429) is exhausted, fall back seamlessly to free translation
        // so live subtitles & Thai voice keep running without stopping mid-game.
        if (sawRateLimit) {
            try {
                val fallbackThai = FreeTranslateFallback.translateEnToTh(englishText)
                lastUsedEngine = "แปลฟรีสำรอง (โควต้า Gemini ฟรีเต็ม)"
                rememberHistory(englishText, fallbackThai)
                return@withContext fallbackThai
            } catch (_: Exception) {
            }
        }

        throw (primaryError ?: IllegalStateException("ไม่สามารถเชื่อมต่อ Gemini API ได้"))
    }

    private fun rememberHistory(en: String, th: String) {
        synchronized(recentHistory) {
            if (recentHistory.size >= 2) recentHistory.removeFirst()
            recentHistory.addLast(en to th)
        }
    }

    private class HttpStatusException(val code: Int, message: String) : IllegalStateException(message)

    private fun callGenerateContent(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        useFastThinking: Boolean
    ): String {
        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

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

        val genConfig = JSONObject().apply {
            put("temperature", 0.45)
            put("maxOutputTokens", 1024)
            if (useFastThinking && model.startsWith("gemini-3.8")) {
                put("thinkingConfig", JSONObject().put("thinkingLevel", "LOW"))
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
            put("generationConfig", genConfig)
        }

        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw HttpStatusException(
                    response.code,
                    "Gemini API (${response.code} / $model): $bodyStr"
                )
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
                val part = parts.getJSONObject(i)
                if (part.optBoolean("thought", false)) continue
                sb.append(part.optString("text", ""))
            }
            val result = sb.toString()
                .trim()
                .removePrefix("\"")
                .removeSuffix("\"")
                .trim()

            if (result.isEmpty()) {
                throw IllegalStateException("คำแปลว่างเปล่า")
            }
            return result
        }
    }
}
