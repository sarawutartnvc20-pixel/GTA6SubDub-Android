package com.gta6subdub.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Zero-cost emergency fallback translator used automatically when Gemini Free Tier
 * hits 429 RESOURCE_EXHAUSTED so live subtitles & Thai TTS never stop mid-game.
 */
object FreeTranslateFallback {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun translateEnToTh(englishText: String): String = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(englishText.trim(), "UTF-8")
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=th&dt=t&q=$encoded"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .get()
            .build()

        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("Fallback translate error (${resp.code})")
            }
            val outer = JSONArray(body)
            val sentences = outer.getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until sentences.length()) {
                val seg = sentences.optJSONArray(i)?.optString(0, "") ?: ""
                sb.append(seg)
            }
            sb.toString().trim().ifEmpty { englishText }
        }
    }
}
