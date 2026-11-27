package com.gta6subdub.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
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

object GeminiTtsClient {
    private val ttsModels = listOf(
        "gemini-3.8-flash-tts",
        "gemini-3.8-flash-lite-tts",
        "gemini-2.5-flash-preview-tts"
    )
    private const val SAMPLE_RATE = 24000

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var currentTrack: AudioTrack? = null
    @Volatile
    private var currentPlayer: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    suspend fun synthesizeAndPlay(
        context: Context,
        thaiText: String,
        apiKey: String,
        voiceName: String = "Fenrir"
    ) = withContext(Dispatchers.IO) {
        val safeKey = apiKey.trim()
        require(safeKey.isNotEmpty()) { "ยังไม่ได้ตั้งค่า Gemini API Key" }

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
            "Read aloud naturally in raw, expressive Thai street-talk like a GTA game character (read only the Thai text): $thaiText"

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

        var lastError: Exception? = null
        var decodedAudio: ByteArray? = null
        var mimeType = ""

        for (model in ttsModels) {
            val url =
                "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$safeKey"
            val request = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            try {
                client.newCall(request).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (resp.code == 404) {
                        lastError = IllegalStateException("Model $model not found (404)")
                        return@use
                    }
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

                    for (i in 0 until parts.length()) {
                        val inlineData = parts.getJSONObject(i).optJSONObject("inlineData")
                        val data = inlineData?.optString("data", "")
                        if (!data.isNullOrEmpty()) {
                            mimeType = inlineData.optString("mimeType", "")
                            decodedAudio = Base64.decode(data, Base64.DEFAULT)
                            break
                        }
                    }
                }
                if (decodedAudio != null) break
            } catch (e: Exception) {
                lastError = e
            }
        }

        val audioBytes = decodedAudio
            ?: throw (lastError ?: IllegalStateException("ไม่พบข้อมูลเสียงจาก Gemini TTS"))

        val isWavOrEncoded = isRiffWav(audioBytes) ||
            mimeType.contains("wav", ignoreCase = true) ||
            mimeType.contains("mpeg", ignoreCase = true) ||
            mimeType.contains("ogg", ignoreCase = true)

        if (isWavOrEncoded) {
            playEncodedAudio(context, audioBytes)
        } else {
            playPcm24k(context, audioBytes)
        }
    }

    private fun isRiffWav(bytes: ByteArray): Boolean {
        return bytes.size >= 4 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()
    }

    private suspend fun playEncodedAudio(context: Context, audioBytes: ByteArray) {
        val outFile = File(context.cacheDir, "gemini_tts_latest.wav")
        outFile.writeBytes(audioBytes)

        withContext(Dispatchers.Main) {
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

            try {
                currentPlayer = MediaPlayer().apply {
                    setAudioAttributes(attrs)
                    setDataSource(outFile.absolutePath)
                    setOnCompletionListener {
                        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                    }
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                stopPlayback(context)
            }
        }
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

        try {
            currentPlayer?.stop()
            currentPlayer?.release()
        } catch (_: Exception) {
        }
        currentPlayer = null

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}
