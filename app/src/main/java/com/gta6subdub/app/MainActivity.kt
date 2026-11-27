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

        binding.btnQuickLiveControl.setOnClickListener {
            startActivity(Intent(this, LiveControlActivity::class.java))
        }

        binding.btnQuickDubbing.setOnClickListener {
            prefs.autoDubEnabled = !prefs.autoDubEnabled
            if (prefs.autoDubEnabled) {
                prefs.isMutedSubOnly = false
            }
            refreshDashboardStatus()
        }

        // Chip 1: 2nd PSN Account Login
        binding.chipPsRemotePlay.setOnClickListener {
            startActivity(Intent(this, PsnAuthActivity::class.java))
        }

        // Chip 2: Pair PS5 via PIN
        binding.chipOverlay.setOnClickListener {
            startActivity(Intent(this, Ps5PairActivity::class.java))
        }

        binding.chipGemini.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.chipElevenLabs.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDashboardStatus()
    }

    private fun refreshDashboardStatus() {
        val psnReady = prefs.isPsnLoggedIn
        val ps5Paired = prefs.isPs5Paired
        val hasGemini = prefs.geminiApiKey.isNotEmpty()
        val hasEleven = prefs.elevenLabsApiKey.isNotEmpty()

        binding.tvCheckPsRemote.text = if (psnReady) "✓" else "●"
        binding.tvCheckPsRemote.setTextColor(
            if (psnReady) 0xFF4ADE80.toInt() else 0xFFFACC15.toInt()
        )

        binding.tvCheckOverlay.text = if (ps5Paired) "✓" else "●"
        binding.tvCheckOverlay.setTextColor(
            if (ps5Paired) 0xFF4ADE80.toInt() else 0xFFFACC15.toInt()
        )

        binding.tvCheckGemini.text = if (hasGemini) "✓" else "●"
        binding.tvCheckGemini.setTextColor(
            if (hasGemini) 0xFF4ADE80.toInt() else 0xFFFACC15.toInt()
        )

        binding.tvCheckEleven.text = if (hasEleven) "✓" else "●"
        binding.tvCheckEleven.setTextColor(
            if (hasEleven) 0xFF4ADE80.toInt() else 0xFFFACC15.toInt()
        )

        val allReady = psnReady && ps5Paired && hasGemini
        binding.tvReadyBadge.text = if (allReady) "✓ พร้อมใช้งาน" else "● แตะด้านล่างเพื่อตั้งค่า PSN / PS5"
        binding.tvReadyBadge.setTextColor(
            if (allReady) 0xFF4ADE80.toInt() else 0xFFFACC15.toInt()
        )

        val dubActive = prefs.autoDubEnabled && !prefs.isMutedSubOnly
        binding.tvQuickDubStatus.text = if (dubActive) {
            "เปิด · ไทย (แตะเพื่อสลับ)"
        } else {
            "ปิดเสียง · แสดงเฉพาะซับ (แตะเพื่อสลับ)"
        }
    }
}
