package com.gta6subdub.app

import android.content.Context
import android.util.Base64

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

    // MARK: - 2nd PSN Account & Paired PS5 Credentials (for chiaki-ng)

    var psnAccountIdBase64: String
        get() = prefs.getString("psn_account_id_b64", "") ?: ""
        set(value) = prefs.edit().putString("psn_account_id_b64", value.trim()).apply()

    var psnOnlineId: String
        get() = prefs.getString("psn_online_id", "") ?: ""
        set(value) = prefs.edit().putString("psn_online_id", value.trim()).apply()

    var ps5Host: String
        get() = prefs.getString("ps5_host", "") ?: ""
        set(value) = prefs.edit().putString("ps5_host", value.trim()).apply()

    var ps5Nickname: String
        get() = prefs.getString("ps5_nickname", "PS5") ?: "PS5"
        set(value) = prefs.edit().putString("ps5_nickname", value.trim()).apply()

    var rpRegistKeyBase64: String
        get() = prefs.getString("rp_regist_key_b64", "") ?: ""
        set(value) = prefs.edit().putString("rp_regist_key_b64", value.trim()).apply()

    var rpKeyBase64: String
        get() = prefs.getString("rp_key_b64", "") ?: ""
        set(value) = prefs.edit().putString("rp_key_b64", value.trim()).apply()

    fun getPsnAccountIdBytes(): ByteArray? {
        val b64 = psnAccountIdBase64
        if (b64.isEmpty()) return null
        return try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            if (bytes.size == 8) bytes else null
        } catch (_: Exception) {
            null
        }
    }

    fun getRegistKeyBytes(): ByteArray? {
        val b64 = rpRegistKeyBase64
        if (b64.isEmpty()) return null
        return try {
            Base64.decode(b64, Base64.DEFAULT)
        } catch (_: Exception) {
            null
        }
    }

    fun getRpKeyBytes(): ByteArray? {
        val b64 = rpKeyBase64
        if (b64.isEmpty()) return null
        return try {
            Base64.decode(b64, Base64.DEFAULT)
        } catch (_: Exception) {
            null
        }
    }

    val isPsnLoggedIn: Boolean
        get() = getPsnAccountIdBytes() != null

    val isPs5Paired: Boolean
        get() = ps5Host.isNotEmpty() && getRegistKeyBytes() != null && getRpKeyBytes() != null
}
