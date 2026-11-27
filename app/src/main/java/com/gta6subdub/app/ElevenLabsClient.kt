package com.gta6subdub.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object ElevenLabsClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var mediaPlayer: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    @Volatile
    private var activeCompletion: CompletableDeferred<Unit>? = null

    suspend fun synthesizeAndPlay(
        context: Context,
        text: String,
        apiKey: String,
        voiceId: String
    ) = withContext(Dispatchers.IO) {
        val safeKey = apiKey.trim()
        val safeVoice = voiceId.trim()
        if (safeKey.isEmpty() || safeVoice.isEmpty()) return@withContext

        val url =
            "https://api.elevenlabs.io/v1/text-to-speech/$safeVoice?output_format=mp3_22050_32&optimize_streaming_latency=4"

        val payload = JSONObject().apply {
            put("text", text)
            put("model_id", "eleven_flash_v2_5")
            put(
                "voice_settings",
                JSONObject().apply {
                    put("stability", 0.45)
                    put("similarity_boost", 0.75)
                    put("style", 0.2)
                    put("use_speaker_boost", true)
                }
            )
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("xi-api-key", safeKey)
            .addHeader("Accept", "audio/mpeg")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val bytes = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val err = response.body?.string().orEmpty()
                throw IllegalStateException("ElevenLabs error (${response.code}): $err")
            }
            response.body?.bytes() ?: ByteArray(0)
        }

        if (bytes.isEmpty()) return@withContext

        val outFile = File(context.cacheDir, "tts_latest.mp3")
        outFile.writeBytes(bytes)

        val done = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {
            playFile(context, outFile, done)
        }
        withTimeoutOrNull(12000L) {
            done.await()
        }
    }

    private fun playFile(context: Context, file: File, done: CompletableDeferred<Unit>) {
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
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(file.absolutePath)
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
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            done.complete(Unit)
        }
    }

    fun stopPlayback(context: Context) {
        activeCompletion?.complete(Unit)
        activeCompletion = null
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}
