package com.gta6subdub.app

import android.content.Context

class VoiceRouter(private val context: Context) {
    private val prefs = PrefsStore(context)
    private val androidTts = AndroidTtsSpeaker(context)

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
                        androidTts.speak(thaiText)
                        "Android TTS (สำรอง: ${e.message?.take(36)})"
                    }
                } else {
                    androidTts.speak(thaiText)
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
                                return "Gemini TTS (สำรองหลัง ElevenLabs error)"
                            } catch (_: Exception) {
                            }
                        }
                        androidTts.speak(thaiText)
                        "Android TTS (สำรอง)"
                    }
                } else {
                    androidTts.speak(thaiText)
                    "Android TTS (ยังไม่ได้ใส่ ElevenLabs Key)"
                }
            }

            else -> {
                androidTts.speak(thaiText)
                "Android TTS (ฟรีในเครื่อง)"
            }
        }
    }

    fun stopAll() {
        GeminiTtsClient.stopPlayback(context)
        ElevenLabsClient.stopPlayback(context)
        androidTts.stop()
    }

    fun shutdown() {
        stopAll()
        androidTts.shutdown()
    }
}
