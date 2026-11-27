package com.gta6subdub.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
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
            SubtitleStateBus.notifyStyleChanged()
            refreshDashboardStatus()
        }

        binding.chipPsRemotePlay.setOnClickListener {
            val launchIntent = PsRemotePlayHelper.createLaunchIntent(this)
            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                Toast.makeText(
                    this,
                    "ยังไม่ได้ติดตั้งแอป PS Remote Play (ติดตั้งจาก Play Store ก่อนครับ)",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        binding.chipOverlay.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
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
        val psInstalled = isPsRemotePlayInstalled()
        val overlayGranted = Settings.canDrawOverlays(this)
        val hasGemini = prefs.geminiApiKey.isNotEmpty()
        val hasEleven = prefs.elevenLabsApiKey.isNotEmpty()

        binding.tvCheckPsRemote.text = if (psInstalled) "✓" else "✕"
        binding.tvCheckPsRemote.setTextColor(
            if (psInstalled) 0xFF4ADE80.toInt() else 0xFFF87171.toInt()
        )

        binding.tvCheckOverlay.text = if (overlayGranted) "✓" else "●"
        binding.tvCheckOverlay.setTextColor(
            if (overlayGranted) 0xFF4ADE80.toInt() else 0xFFFACC15.toInt()
        )

        binding.tvCheckGemini.text = if (hasGemini) "✓" else "●"
        binding.tvCheckGemini.setTextColor(
            if (hasGemini) 0xFF4ADE80.toInt() else 0xFFFACC15.toInt()
        )

        binding.tvCheckEleven.text = if (hasEleven) "✓" else "●"
        binding.tvCheckEleven.setTextColor(
            if (hasEleven) 0xFF4ADE80.toInt() else 0xFFFACC15.toInt()
        )

        val allReady = psInstalled && overlayGranted && hasGemini
        binding.tvReadyBadge.text = if (allReady) "✓ พร้อมใช้งาน" else "● ยังตั้งค่าไม่ครบ"
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

    private fun isPsRemotePlayInstalled(): Boolean {
        return PsRemotePlayHelper.isInstalled(this)
    }
}
