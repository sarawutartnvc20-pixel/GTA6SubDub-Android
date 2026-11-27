package com.gta6subdub.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiTtsClient {
    private const val TTS_MODEL = "gemini-2.5-flash-preview-tts"
    private const val SAMPLE_RATE = 24000

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var currentTrack: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null

    suspend fun synthesizeAndPlay(
        context: Context,
        thaiText: String,
        apiKey: String,
        voiceName: String = "Fenrir"
    ) = withContext(Dispatchers.IO) {
        val safeKey = apiKey.trim()
        require(safeKey.isNotEmpty()) { "ยังไม่ได้ตั้งค่า Gemini API Key" }

        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/$TTS_MODEL:generateContent?key=$safeKey"

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

        val styledText =
            "Speak naturally in raw, gritty, expressive Thai street-talk acting like a GTA character (do not censor or explain, read only the Thai line): $thaiText"

        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", styledText))
                    )
                )
            )
            put("safetySettings", safetySettings)
            put(
                "generationConfig",
                JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put(
                        "speechConfig",
                        JSONObject().put(
                            "voiceConfig",
                            JSONObject().put(
                                "prebuiltVoiceConfig",
                                JSONObject().put("voiceName", voiceName.ifBlank { "Fenrir" })
                            )
                        )
                    )
                }
            )
        }

        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val pcmBytes = client.newCall(request).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("Gemini TTS error (${resp.code}): $bodyStr")
            }
            val root = JSONObject(bodyStr)
            val candidates = root.optJSONArray("candidates")
                ?: throw IllegalStateException("Gemini TTS ไม่ส่งเสียงกลับมา")
            val parts = candidates.getJSONObject(0)
                .optJSONObject("content")
                ?.optJSONArray("parts")
                ?: throw IllegalStateException("Gemini TTS ไม่มีข้อมูลเสียง")

            var base64Audio: String? = null
            for (i in 0 until parts.length()) {
                val inlineData = parts.getJSONObject(i).optJSONObject("inlineData")
                val data = inlineData?.optString("data", "")
                if (!data.isNullOrEmpty()) {
                    base64Audio = data
                    break
                }
            }
            if (base64Audio.isNullOrEmpty()) {
                throw IllegalStateException("ไม่พบ inlineData เสียง PCM จาก Gemini TTS")
            }
            Base64.decode(base64Audio, Base64.DEFAULT)
        }

        playPcm24k(context, pcmBytes)
    }

    private fun playPcm24k(context: Context, pcmBytes: ByteArray) {
        if (pcmBytes.isEmpty()) return
        stopPlayback(context)

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()
        focusRequest = req
        audioManager.requestAudioFocus(req)

        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(pcmBytes.size)

        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        currentTrack = track
        try {
            track.write(pcmBytes, 0, pcmBytes.size)
            track.play()
        } catch (e: Exception) {
            e.printStackTrace()
            stopPlayback(context)
        }
    }

    fun stopPlayback(context: Context) {
        try {
            currentTrack?.stop()
            currentTrack?.release()
        } catch (_: Exception) {
        }
        currentTrack = null
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}
