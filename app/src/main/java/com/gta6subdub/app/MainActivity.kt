package com.gta6subdub.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
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

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, SubtitleCaptureService::class.java).apply {
                    putExtra(SubtitleCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(SubtitleCaptureService.EXTRA_RESULT_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
                updateCaptureButtonState(true)
            } else {
                Toast.makeText(this, "ไม่ได้รับอนุญาตให้จับภาพหน้าจอ", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsStore(this)
        loadSavedSettings()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        binding.btnSave.setOnClickListener {
            saveSettings()
            Toast.makeText(this, "บันทึกการตั้งค่าแล้ว ✓", Toast.LENGTH_SHORT).show()
        }

        binding.btnOverlayPermission.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        binding.btnToggleCapture.setOnClickListener {
            if (SubtitleCaptureService.isRunning) {
                val stopIntent = Intent(this, SubtitleCaptureService::class.java).apply {
                    action = SubtitleCaptureService.ACTION_STOP
                }
                startService(stopIntent)
                updateCaptureButtonState(false)
                return@setOnClickListener
            }

            saveSettings()

            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "กรุณาอนุญาตแสดงหน้าต่างลอย (Overlay) ก่อนครับ", Toast.LENGTH_LONG).show()
                binding.btnOverlayPermission.performClick()
                return@setOnClickListener
            }

            if (prefs.geminiApiKey.isEmpty()) {
                Toast.makeText(this, "กรุณากรอก Gemini API Key ก่อนครับ", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }

    override fun onResume() {
        super.onResume()
        val overlayGranted = Settings.canDrawOverlays(this)
        binding.btnOverlayPermission.text = if (overlayGranted) {
            "1. อนุญาตหน้าต่างลอยแล้ว ✓"
        } else {
            "1. อนุญาตแสดงหน้าต่างลอย (Overlay)"
        }
        updateCaptureButtonState(SubtitleCaptureService.isRunning)
    }

    private fun updateCaptureButtonState(running: Boolean) {
        binding.btnToggleCapture.text = if (running) {
            "หยุดแปลซับไตเติล + พากย์เสียง"
        } else {
            "2. เริ่มแปลซับไตเติล + พากย์เสียง"
        }
        binding.tvStatus.text = if (running) {
            "สถานะ: กำลังทำงาน (สลับไปเปิด PS Remote Play ได้เลย)"
        } else {
            "สถานะ: พร้อมใช้งาน"
        }
    }

    private fun loadSavedSettings() {
        binding.etGeminiKey.setText(prefs.geminiApiKey)
        binding.etGeminiModel.setText(prefs.geminiModel)
        binding.etElevenKey.setText(prefs.elevenLabsApiKey)
        binding.etVoiceId.setText(prefs.elevenLabsVoiceId)
        binding.switchAutoDub.isChecked = prefs.autoDubEnabled
    }

    private fun saveSettings() {
        prefs.geminiApiKey = binding.etGeminiKey.text?.toString().orEmpty()
        prefs.geminiModel = binding.etGeminiModel.text?.toString().orEmpty()
        prefs.elevenLabsApiKey = binding.etElevenKey.text?.toString().orEmpty()
        prefs.elevenLabsVoiceId = binding.etVoiceId.text?.toString().orEmpty()
        prefs.autoDubEnabled = binding.switchAutoDub.isChecked
    }
}
