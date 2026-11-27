package com.gta6subdub.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object GeminiTtsClient {
    // Prioritize faster lite-tts first for lower real-time latency, then full 3.8-flash-tts
    private val ttsModels = listOf(
        "gemini-3.8-flash-lite-tts",
        "gemini-3.8-flash-tts"
    )
    private const val SAMPLE_RATE = 24000

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var currentTrack: AudioTrack? = null
    @Volatile
    private var currentPlayer: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    @Volatile
    private var activeCompletion: CompletableDeferred<Unit>? = null

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
            "Read aloud naturally and briskly in raw, expressive Thai street-talk like a GTA character (read only the Thai text): $thaiText"

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

        var primaryError: Exception? = null
        var decodedAudio: ByteArray? = null
        var mimeType = ""

        for ((idx, model) in ttsModels.withIndex()) {
            val url =
                "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$safeKey"
            val request = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            try {
                client.newCall(request).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (resp.code == 404 || resp.code == 429) {
                        if (idx == 0) primaryError = IllegalStateException("Gemini TTS ($model ${resp.code})")
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
                if (idx == 0) primaryError = e
            }
        }

        val audioBytes = decodedAudio
            ?: throw (primaryError ?: IllegalStateException("ไม่พบข้อมูลเสียงจาก Gemini TTS"))

        val isWavOrEncoded = isRiffWav(audioBytes) ||
            mimeType.contains("wav", ignoreCase = true) ||
            mimeType.contains("mpeg", ignoreCase = true) ||
            mimeType.contains("ogg", ignoreCase = true)

        if (isWavOrEncoded) {
            playEncodedAudioAndWait(context, audioBytes)
        } else {
            playPcm24kAndWait(context, audioBytes)
        }
    }

    private fun isRiffWav(bytes: ByteArray): Boolean {
        return bytes.size >= 4 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()
    }

    private suspend fun playEncodedAudioAndWait(context: Context, audioBytes: ByteArray) {
        val outFile = File(context.cacheDir, "gemini_tts_latest.wav")
        outFile.writeBytes(audioBytes)

        val done = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {
            stopPlayback(context)
            activeCompletion = done
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
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
                    setVolume(1.0f, 1.0f)
                    setOnCompletionListener {
                        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                        done.complete(Unit)
                    }
                    setOnErrorListener { _, _, _ ->
                        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                        done.complete(Unit)
                        true
                    }
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                stopPlayback(context)
                done.complete(Unit)
            }
        }
        withTimeoutOrNull(12000L) {
            done.await()
        }
    }

    private suspend fun playPcm24kAndWait(context: Context, pcmBytes: ByteArray) {
        if (pcmBytes.isEmpty()) return
        stopPlayback(context)

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
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
            track.setVolume(AudioTrack.getMaxVolume())
            track.write(pcmBytes, 0, pcmBytes.size)
            track.play()
            // Wait for PCM duration (24kHz * 2 bytes/sample = 48,000 bytes/sec)
            val durationMs = ((pcmBytes.size.toLong() * 1000L) / (SAMPLE_RATE * 2L)).coerceIn(250L, 10000L)
            delay(durationMs + 80L)
        } catch (e: Exception) {
            e.printStackTrace()
            stopPlayback(context)
        }
    }

    fun stopPlayback(context: Context) {
        activeCompletion?.complete(Unit)
        activeCompletion = null
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
