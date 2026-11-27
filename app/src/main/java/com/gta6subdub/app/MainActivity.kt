package com.gta6subdub.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.gta6subdub.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PrefsStore

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsStore(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        binding.btnOpenSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.cardHeroStart.setOnClickListener {
            startActivity(Intent(this, LiveControlActivity::class.java))
        }

        binding.btnHeroPlay.setOnClickListener {
            startActivity(Intent(this, LiveControlActivity::class.java))
        }

        binding.tabModePs5.setOnClickListener {
            prefs.captureMode = "ps5"
            refreshDashboardStatus()
        }

        binding.tabModeCamera.setOnClickListener {
            prefs.captureMode = "camera"
            refreshDashboardStatus()
        }

        binding.btnSelectGame.setOnClickListener {
            startActivity(Intent(this, GameSelectActivity::class.java))
        }

        binding.btnQuickDubbing.setOnClickListener {
            prefs.autoDubEnabled = !prefs.autoDubEnabled
            if (prefs.autoDubEnabled) {
                prefs.isMutedSubOnly = false
            }
            refreshDashboardStatus()
        }

        binding.chipPsnAccount.setOnClickListener {
            startActivity(Intent(this, Ps5SetupWizardActivity::class.java))
        }

        binding.chipPs5Pair.setOnClickListener {
            startActivity(Intent(this, Ps5SetupWizardActivity::class.java))
        }

        binding.chipGemini.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.chipVoice.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDashboardStatus()
    }

    private fun refreshDashboardStatus() {
        val isCamera = prefs.captureMode == "camera"
        binding.tabModePs5.setBackgroundResource(if (!isCamera) R.drawable.bg_card_pink else 0)
        binding.tabModePs5.setTextColor(if (!isCamera) 0xFFFFFFFF.toInt() else 0xFFA79CB8.toInt())
        binding.tabModeCamera.setBackgroundResource(if (isCamera) R.drawable.bg_card_pink else 0)
        binding.tabModeCamera.setTextColor(if (isCamera) 0xFFFFFFFF.toInt() else 0xFFA79CB8.toInt())

        val psnReady = prefs.isPsnLoggedIn
        val ps5Paired = prefs.isPs5Paired
        val hasGemini = prefs.geminiApiKey.isNotEmpty()
        val voiceReady = prefs.isVoiceReady

        binding.tvCheckPsn.text = if (psnReady) "✓" else "●"
        binding.tvCheckPsn.setTextColor(if (psnReady) 0xFF4ADE80.toInt() else 0xFFFACC15.toInt())

        binding.tvCheckPs5.text = if (ps5Paired) "✓" else "●"
        binding.tvCheckPs5.setTextColor(if (ps5Paired) 0xFF4ADE80.toInt() else 0xFFFACC15.toInt())
        binding.tvPs5Label.text = if (ps5Paired) "PS5 (${prefs.ps5Host})" else "จับคู่ PS5"

        binding.tvCheckGemini.text = if (hasGemini) "✓" else "●"
        binding.tvCheckGemini.setTextColor(if (hasGemini) 0xFF4ADE80.toInt() else 0xFFFACC15.toInt())

        binding.tvCheckVoice.text = if (voiceReady) "✓" else "●"
        binding.tvCheckVoice.setTextColor(if (voiceReady) 0xFF4ADE80.toInt() else 0xFFFACC15.toInt())

        val readyToPlay = if (isCamera) hasGemini else (psnReady && ps5Paired && hasGemini)
        binding.tvReadyBadge.text = when {
            readyToPlay -> "✓ พร้อมใช้งาน"
            !hasGemini -> "● กรุณาใส่ Gemini API Key ก่อน"
            else -> "● แตะ 'จับคู่ PS5' หรือสลับเป็น 'กล้องส่องทีวี'"
        }
        binding.tvReadyBadge.setTextColor(
            if (readyToPlay) 0xFF4ADE80.toInt() else 0xFFFACC15.toInt()
        )

        // Game & Slang Pack status
        val gameProfile = GamePackManager.supportedGames.firstOrNull { it.id == prefs.selectedGameId }
            ?: GamePackManager.supportedGames.first()
        binding.tvSelectedGameName.text = gameProfile.title
        val packCount = GamePackManager.getInstalledWordCount(this, gameProfile.id)
        val packInstalled = GamePackManager.isPackInstalled(this, gameProfile.id)
        binding.tvSlangPackBadge.text = when {
            gameProfile.id == "auto" -> "แปลภาษาพูดทั่วไป (แตะเพื่อเปลี่ยนเกม)"
            packInstalled -> "✓ ชุดคำสแลงพร้อมใช้ ($packCount คำ · แปลดิบไม่เซ็นเซอร์)"
            else -> "แตะเพื่อดาวน์โหลดชุดคำสแลง $packCount คำ"
        }

        // Quick Dubbing card
        val engineName = when (prefs.voiceEngine) {
            "gemini" -> "Gemini TTS (${prefs.geminiTtsVoice})"
            "elevenlabs" -> "ElevenLabs"
            else -> "Android TTS (ฟรี)"
        }
        binding.tvQuickDubTitle.text = "พากย์เสียง: $engineName"
        val dubActive = prefs.autoDubEnabled && !prefs.isMutedSubOnly
        binding.tvQuickDubStatus.text = if (dubActive) {
            "เปิด · ไทย (แตะเพื่อสลับ)"
        } else {
            "ปิดเสียง · แสดงเฉพาะซับ (แตะเพื่อสลับ)"
        }
    }
}
