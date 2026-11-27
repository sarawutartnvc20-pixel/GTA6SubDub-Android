package com.gta6subdub.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gta6subdub.app.databinding.ActivityGameSelectBinding
import kotlinx.coroutines.launch

class GameSelectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGameSelectBinding
    private lateinit var prefs: PrefsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsStore(this)
        binding.etCustomPackUrl.setText(prefs.customSlangPackUrl)

        binding.btnCloseGameSelect.setOnClickListener {
            prefs.customSlangPackUrl = binding.etCustomPackUrl.text.toString().trim()
            finish()
        }

        binding.cardGta6.setOnClickListener {
            selectGame("gta6")
        }

        binding.cardGta5.setOnClickListener {
            selectGame("gta5")
        }

        binding.cardAutoGame.setOnClickListener {
            selectGame("auto")
        }

        binding.btnDownloadGta6Pack.setOnClickListener {
            downloadPack("gta6")
        }

        binding.btnDownloadGta5Pack.setOnClickListener {
            downloadPack("gta5")
        }

        refreshUI()
    }

    private fun selectGame(gameId: String) {
        prefs.selectedGameId = gameId
        GeminiClient.clearHistory()
        refreshUI()
    }

    private fun downloadPack(gameId: String) {
        val customUrl = binding.etCustomPackUrl.text.toString().trim()
        prefs.customSlangPackUrl = customUrl
        prefs.selectedGameId = gameId

        val statusView = if (gameId == "gta6") binding.tvGta6PackStatus else binding.tvGta5PackStatus
        statusView.text = "กำลังดาวน์โหลดและติดตั้งชุดคำสแลง..."

        lifecycleScope.launch {
            try {
                val count = GamePackManager.downloadOrInstallPack(
                    context = this@GameSelectActivity,
                    gameId = gameId,
                    remoteUrl = customUrl.ifBlank { null }
                )
                GeminiClient.clearHistory()
                Toast.makeText(
                    this@GameSelectActivity,
                    "ติดตั้งชุดคำสแลงสำเร็จ ($count คำ) ✓",
                    Toast.LENGTH_SHORT
                ).show()
                refreshUI()
            } catch (e: Exception) {
                Toast.makeText(
                    this@GameSelectActivity,
                    "ดาวน์โหลดไม่สำเร็จ: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                refreshUI()
            }
        }
    }

    private fun refreshUI() {
        val selected = prefs.selectedGameId
        binding.tvCheckGta6.text = if (selected == "gta6") "✓" else ""
        binding.tvCheckGta5.text = if (selected == "gta5") "✓" else ""
        binding.tvCheckAuto.text = if (selected == "auto") "✓" else ""

        binding.cardGta6.setBackgroundResource(
            if (selected == "gta6") R.drawable.bg_card_pink else R.drawable.bg_card_dark
        )
        binding.cardGta5.setBackgroundResource(
            if (selected == "gta5") R.drawable.bg_card_pink else R.drawable.bg_card_dark
        )
        binding.cardAutoGame.setBackgroundResource(
            if (selected == "auto") R.drawable.bg_card_pink else R.drawable.bg_card_dark
        )

        val gta6Installed = GamePackManager.isPackInstalled(this, "gta6")
        val gta6Count = GamePackManager.getInstalledWordCount(this, "gta6")
        binding.tvGta6PackStatus.text = if (gta6Installed) {
            "✓ ติดตั้งแล้ว ($gta6Count คำสแลง GTA 6)"
        } else {
            "พร้อมติดตั้งชุดคำสแลง ($gta6Count คำ)"
        }
        binding.tvGta6PackStatus.setTextColor(
            if (gta6Installed) 0xFF4ADE80.toInt() else 0xFFFACC15.toInt()
        )

        val gta5Installed = GamePackManager.isPackInstalled(this, "gta5")
        val gta5Count = GamePackManager.getInstalledWordCount(this, "gta5")
        binding.tvGta5PackStatus.text = if (gta5Installed) {
            "✓ ติดตั้งแล้ว ($gta5Count คำสแลง GTA 5)"
        } else {
            "พร้อมติดตั้งชุดคำสแลง"
        }
        binding.tvGta5PackStatus.setTextColor(
            if (gta5Installed) 0xFF4ADE80.toInt() else 0xFFA79CB8.toInt()
        )
    }
}
