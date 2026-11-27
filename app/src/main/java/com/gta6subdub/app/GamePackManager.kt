package com.gta6subdub.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class GameProfile(
    val id: String,
    val title: String,
    val subtitle: String,
    val hasDownloadablePack: Boolean
)

object GamePackManager {
    val supportedGames = listOf(
        GameProfile(
            id = "gta6",
            title = "Grand Theft Auto VI",
            subtitle = "AI + ชุดคำสแลง Vice City / Leonida (ดิบ เถื่อน ไม่เซ็นเซอร์)",
            hasDownloadablePack = true
        ),
        GameProfile(
            id = "gta5",
            title = "Grand Theft Auto V",
            subtitle = "AI + ชุดคำสแลง Los Santos สตรีท",
            hasDownloadablePack = true
        ),
        GameProfile(
            id = "auto",
            title = "อัตโนมัติ (ทุกเกม)",
            subtitle = "แปลภาษาพูดธรรมชาติเข้ากับบริบทเกมทั่วไป",
            hasDownloadablePack = false
        )
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun packFile(context: Context, gameId: String): File {
        val dir = File(context.filesDir, "game_packs")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "${gameId}_slang_pack.json")
    }

    fun isPackInstalled(context: Context, gameId: String): Boolean {
        if (gameId == "auto") return true
        return packFile(context, gameId).exists()
    }

    fun getInstalledWordCount(context: Context, gameId: String): Int {
        val json = loadPackJson(context, gameId) ?: return 0
        return json.optJSONObject("glossary")?.length() ?: 0
    }

    /**
     * Downloads the slang pack JSON from a custom/remote URL if provided,
     * or installs the built-in high-accuracy GTA 6 slang pack into local storage
     * so it works reliably offline as well.
     */
    suspend fun downloadOrInstallPack(
        context: Context,
        gameId: String,
        remoteUrl: String? = null
    ): Int = withContext(Dispatchers.IO) {
        val targetFile = packFile(context, gameId)
        var rawJson: String? = null

        if (!remoteUrl.isNullOrBlank()) {
            try {
                val req = Request.Builder().url(remoteUrl.trim()).get().build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        if (body.trim().startsWith("{")) {
                            JSONObject(body) // validate JSON
                            rawJson = body
                        }
                    }
                }
            } catch (_: Exception) {
                // Fall back to bundled pack below
            }
        }

        if (rawJson == null) {
            rawJson = context.assets.open("gta6_slang_pack.json").bufferedReader().use { it.readText() }
            if (gameId == "gta5") {
                val obj = JSONObject(rawJson!!)
                obj.put("id", "gta5")
                obj.put("name", "Grand Theft Auto V")
                val glossary = obj.optJSONObject("glossary") ?: JSONObject()
                glossary.put("Los Santos", "ลอสซานโตส")
                glossary.put("LSPD", "ตำรวจแอลเอส")
                glossary.put("Franklin", "แฟรงคลิน")
                glossary.put("Michael", "ไมเคิล")
                glossary.put("Trevor", "เทรเวอร์")
                glossary.put("Lamar", "ลามาร์")
                obj.put("glossary", glossary)
                rawJson = obj.toString(2)
            }
        }

        targetFile.writeText(rawJson!!)
        val parsed = JSONObject(rawJson!!)
        parsed.optJSONObject("glossary")?.length() ?: 0
    }

    private fun loadPackJson(context: Context, gameId: String): JSONObject? {
        val file = packFile(context, gameId)
        return try {
            if (file.exists()) {
                JSONObject(file.readText())
            } else if (gameId == "gta6" || gameId == "gta5") {
                val text = context.assets.open("gta6_slang_pack.json").bufferedReader().use { it.readText() }
                JSONObject(text)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Checks if the downloaded game pack has an instant pre-translated EN -> TH line (0ms lookup).
     */
    fun lookupExactLine(context: Context, gameId: String, englishText: String): String? {
        val pack = loadPackJson(context, gameId) ?: return null
        val lines = pack.optJSONObject("lines") ?: return null
        val direct = lines.optString(englishText.trim(), "")
        if (direct.isNotEmpty()) return direct
        val lowerKey = englishText.trim().lowercase()
        val keys = lines.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.lowercase() == lowerKey) {
                return lines.optString(k, "").takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    fun buildSystemPrompt(context: Context, gameId: String): String {
        val pack = loadPackJson(context, gameId)
            ?: return "แปลซับไตเติลเกมนี้เป็นภาษาไทยแนวภาษาพูดที่เป็นธรรมชาติ กระชับ ตรงอารมณ์ตัวละคร ตอบเฉพาะคำแปลภาษาไทยเท่านั้น ห้ามอธิบายเพิ่มเติม"

        val sb = StringBuilder()
        val rules = pack.optJSONArray("style_rules")
        if (rules != null) {
            for (i in 0 until rules.length()) {
                sb.append("- ").append(rules.getString(i)).append("\n")
            }
        }

        val glossary = pack.optJSONObject("glossary")
        if (glossary != null && glossary.length() > 0) {
            sb.append("\nพจนานุกรมคำสแลงและศัพท์เฉพาะของเกม (ให้ยึดตามบริบทนี้):\n")
            val keys = glossary.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                sb.append("- ").append(key).append(" = ").append(glossary.optString(key)).append("\n")
            }
        }

        return sb.toString().trim()
    }
}
