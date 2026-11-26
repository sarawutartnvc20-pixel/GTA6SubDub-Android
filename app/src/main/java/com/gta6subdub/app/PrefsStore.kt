package com.gta6subdub.app

import android.content.Context

class PrefsStore(context: Context) {
    private val prefs = context.getSharedPreferences("gta6_sub_dub_prefs", Context.MODE_PRIVATE)

    var geminiApiKey: String
        get() = prefs.getString("gemini_api_key", "") ?: ""
        set(value) = prefs.edit().putString("gemini_api_key", value.trim()).apply()

    var geminiModel: String
        get() = prefs.getString("gemini_model", "gemini-2.5-flash")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "gemini-2.5-flash"
        set(value) = prefs.edit().putString(
            "gemini_model",
            value.trim().ifEmpty { "gemini-2.5-flash" }
        ).apply()

    var elevenLabsApiKey: String
        get() = prefs.getString("elevenlabs_api_key", "") ?: ""
        set(value) = prefs.edit().putString("elevenlabs_api_key", value.trim()).apply()

    var elevenLabsVoiceId: String
        get() = prefs.getString("elevenlabs_voice_id", "21m00Tcm4TlvDq8ikWAM")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "21m00Tcm4TlvDq8ikWAM"
        set(value) = prefs.edit().putString("elevenlabs_voice_id", value.trim()).apply()

    var autoDubEnabled: Boolean
        get() = prefs.getBoolean("auto_dub_enabled", true)
        set(value) = prefs.edit().putBoolean("auto_dub_enabled", value).apply()

    var isMutedSubOnly: Boolean
        get() = prefs.getBoolean("is_muted_sub_only", false)
        set(value) = prefs.edit().putBoolean("is_muted_sub_only", value).apply()

    var subtitleFontSizeSp: Float
        get() = prefs.getFloat("subtitle_font_size_sp", 18f).coerceIn(12f, 34f)
        set(value) = prefs.edit().putFloat("subtitle_font_size_sp", value.coerceIn(12f, 34f)).apply()

    var overlayBrightness: Float
        get() = prefs.getFloat("overlay_brightness", 0.85f).coerceIn(0.2f, 1.0f)
        set(value) = prefs.edit().putFloat("overlay_brightness", value.coerceIn(0.2f, 1.0f)).apply()
}
