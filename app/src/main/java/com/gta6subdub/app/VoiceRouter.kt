package com.gta6subdub.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Smart Speech Queue:
 * - Never cuts off the currently playing sentence mid-word.
 * - Keeps at most 1 pending next sentence (latest wins), so audio never falls
 *   multiple sentences behind live gameplay.
 */
class VoiceRouter(private val context: Context) {
    private val prefs = PrefsStore(context)
    private val androidTts = AndroidTtsSpeaker(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val queueLock = Any()
    private var pendingText: String? = null
    private var pendingCallback: ((String) -> Unit)? = null
    private var workerJob: Job? = null

    fun enqueueSpeak(thaiText: String, onEngineUsed: ((String) -> Unit)? = null) {
        if (thaiText.isBlank()) return
        if (!prefs.autoDubEnabled || prefs.isMutedSubOnly) return

        synchronized(queueLock) {
            // Overwrite any older waiting sentence so queue depth never exceeds 1
            pendingText = thaiText
            pendingCallback = onEngineUsed
            if (workerJob?.isActive != true) {
                workerJob = scope.launch {
                    drainQueue()
                }
            }
        }
    }

    private suspend fun drainQueue() {
        while (true) {
            val nextPair: Pair<String, ((String) -> Unit)?>? = synchronized(queueLock) {
                val text = pendingText
                val cb = pendingCallback
                pendingText = null
                pendingCallback = null
                if (text == null) null else (text to cb)
            }
            if (nextPair == null) break

            val (text, callback) = nextPair
            if (!prefs.autoDubEnabled || prefs.isMutedSubOnly) break

            val used = speak(text, forceEvenIfMuted = false)
            callback?.invoke(used)
        }
    }

    suspend fun speak(thaiText: String, forceEvenIfMuted: Boolean = false): String {
        if (thaiText.isBlank()) return "empty"
        if (!forceEvenIfMuted && (!prefs.autoDubEnabled || prefs.isMutedSubOnly)) {
            return "muted"
        }

        return when (prefs.voiceEngine) {
            "gemini" -> {
                val key = prefs.geminiApiKey
                if (key.isNotEmpty()) {
                    try {
                        GeminiTtsClient.synthesizeAndPlay(
                            context = context,
                            thaiText = thaiText,
                            apiKey = key,
                            voiceName = prefs.geminiTtsVoice
                        )
                        "Gemini TTS (${prefs.geminiTtsVoice})"
                    } catch (e: Exception) {
                        e.printStackTrace()
                        androidTts.speakAndWait(thaiText)
                        "Android TTS (สำรอง: ${e.message?.take(32)})"
                    }
                } else {
                    androidTts.speakAndWait(thaiText)
                    "Android TTS (ยังไม่ได้ใส่ Gemini Key)"
                }
            }

            "elevenlabs" -> {
                val elKey = prefs.elevenLabsApiKey
                val voiceId = prefs.elevenLabsVoiceId
                if (elKey.isNotEmpty() && voiceId.isNotEmpty()) {
                    try {
                        ElevenLabsClient.synthesizeAndPlay(
                            context = context,
                            text = thaiText,
                            apiKey = elKey,
                            voiceId = voiceId
                        )
                        "ElevenLabs TTS"
                    } catch (e: Exception) {
                        e.printStackTrace()
                        val gKey = prefs.geminiApiKey
                        if (gKey.isNotEmpty()) {
                            try {
                                GeminiTtsClient.synthesizeAndPlay(context, thaiText, gKey, prefs.geminiTtsVoice)
                                return "Gemini TTS (สำรอง)"
                            } catch (_: Exception) {
                            }
                        }
                        androidTts.speakAndWait(thaiText)
                        "Android TTS (สำรอง)"
                    }
                } else {
                    androidTts.speakAndWait(thaiText)
                    "Android TTS (ยังไม่ได้ใส่ ElevenLabs Key)"
                }
            }

            else -> {
                androidTts.speakAndWait(thaiText)
                "Android TTS (ฟรีในเครื่อง 1.22x)"
            }
        }
    }

    fun stopAll() {
        synchronized(queueLock) {
            pendingText = null
            pendingCallback = null
            workerJob?.cancel()
            workerJob = null
        }
        GeminiTtsClient.stopPlayback(context)
        ElevenLabsClient.stopPlayback(context)
        androidTts.stop()
    }

    fun shutdown() {
        stopAll()
        scope.cancel()
        androidTts.shutdown()
    }
}
