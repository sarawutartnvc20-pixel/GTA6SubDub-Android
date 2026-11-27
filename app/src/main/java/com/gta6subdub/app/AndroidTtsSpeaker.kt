package com.gta6subdub.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class AndroidTtsSpeaker(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = TextToSpeech(appContext, this)
    @Volatile
    private var isReady = false
    private var focusRequest: AudioFocusRequest? = null
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val thai = Locale("th", "TH")
            val result = tts?.setLanguage(thai)
            isReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            if (!isReady) {
                tts?.language = Locale.getDefault()
                isReady = true
            }
            // 1.22x speed: natural & clear Thai while finishing before the next subtitle line arrives
            tts?.setSpeechRate(1.22f)
            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    utteranceId?.let { waiters.remove(it)?.complete(Unit) }
                    abandonFocus()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    utteranceId?.let { waiters.remove(it)?.complete(Unit) }
                    abandonFocus()
                }
            })
        }
    }

    fun speak(text: String) {
        if (!isReady || text.isBlank()) return
        val id = "gta6_${System.currentTimeMillis()}"
        enqueueUtterance(text, id)
    }

    suspend fun speakAndWait(text: String) {
        if (!isReady || text.isBlank()) return
        val id = "gta6_${System.currentTimeMillis()}"
        val deferred = CompletableDeferred<Unit>()
        waiters[id] = deferred
        enqueueUtterance(text, id)
        withTimeoutOrNull(9000L) {
            deferred.await()
        }
        waiters.remove(id)
    }

    private fun enqueueUtterance(text: String, utteranceId: String) {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()
        focusRequest = req
        audioManager.requestAudioFocus(req)

        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    fun stop() {
        tts?.stop()
        waiters.values.forEach { it.complete(Unit) }
        waiters.clear()
        abandonFocus()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}
