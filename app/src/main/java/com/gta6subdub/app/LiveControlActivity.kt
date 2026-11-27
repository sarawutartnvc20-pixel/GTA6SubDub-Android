package com.gta6subdub.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.gta6subdub.app.databinding.ActivityLiveControlBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LiveControlActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLiveControlBinding
    private lateinit var prefs: PrefsStore

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, SubtitleCaptureService::class.java).apply {
                    putExtra(SubtitleCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(SubtitleCaptureService.EXTRA_RESULT_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
            } else {
                Toast.makeText(this, "ไม่ได้รับอนุญาตให้จับภาพหน้าจอ", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLiveControlBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsStore(this)
        initSliders()
        refreshAudioButtons()

        binding.btnCloseLive.setOnClickListener { finish() }

        binding.btnToggleSliders.setOnClickListener {
            binding.layoutSliders.visibility =
                if (binding.layoutSliders.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        binding.btnOpenPsRemote.setOnClickListener {
            val launchIntent = PsRemotePlayHelper.createLaunchIntent(this)
            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                Toast.makeText(this, "ไม่พบแอป PS Remote Play ในเครื่อง", Toast.LENGTH_SHORT).show()
            }
        }

        // Mute / Subtitles-only toggle (ปิดเสียงให้ขึ้นแค่ซับ / เปิดเสียง)
        binding.btnMuteToggle.setOnClickListener {
            prefs.isMutedSubOnly = !prefs.isMutedSubOnly
            SubtitleStateBus.notifyStyleChanged()
            refreshAudioButtons()
        }

        // Real-time voice dubbing toggle (เปิด/ปิดพากย์เสียงเรียลไทม์)
        binding.btnVoiceDubToggle.setOnClickListener {
            prefs.autoDubEnabled = !prefs.autoDubEnabled
            if (prefs.autoDubEnabled) {
                prefs.isMutedSubOnly = false
            }
            SubtitleStateBus.notifyStyleChanged()
            refreshAudioButtons()
        }

        // Start / Stop translation & capture
        binding.btnStartStopLive.setOnClickListener {
            if (SubtitleCaptureService.isRunning) {
                val stopIntent = Intent(this, SubtitleCaptureService::class.java).apply {
                    action = SubtitleCaptureService.ACTION_STOP
                }
                startService(stopIntent)
                return@setOnClickListener
            }

            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "กรุณาอนุญาตแสดงหน้าต่างลอย (Overlay) ก่อนครับ", Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
                return@setOnClickListener
            }

            if (prefs.geminiApiKey.isEmpty()) {
                Toast.makeText(this, "กรุณากรอก Gemini API Key ในหน้าตั้งค่าก่อนครับ", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, SettingsActivity::class.java))
                return@setOnClickListener
            }

            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
        }

        observeState()
    }

    private fun initSliders() {
        val currentFontProgress = (prefs.subtitleFontSizeSp - 12f).toInt().coerceIn(0, 22)
        binding.seekFontSize.progress = currentFontProgress
        applyPreviewStyle()

        binding.seekFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                prefs.subtitleFontSizeSp = 12f + progress
                applyPreviewStyle()
                SubtitleStateBus.notifyStyleChanged()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val currentBrightnessProgress = ((prefs.overlayBrightness - 0.2f) * 100f).toInt().coerceIn(0, 80)
        binding.seekBrightness.progress = currentBrightnessProgress

        binding.seekBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                prefs.overlayBrightness = 0.2f + (progress / 100f)
                applyPreviewStyle()
                SubtitleStateBus.notifyStyleChanged()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun applyPreviewStyle() {
        binding.tvLiveThai.textSize = prefs.subtitleFontSizeSp
        val brightness = prefs.overlayBrightness
        binding.previewSubtitleBox.alpha = (0.4f + brightness * 0.6f).coerceIn(0.4f, 1.0f)

        val lp = window.attributes
        lp.screenBrightness = brightness
        window.attributes = lp
    }

    private fun refreshAudioButtons() {
        val muted = prefs.isMutedSubOnly
        val dubOn = prefs.autoDubEnabled && !muted

        binding.btnMuteToggle.text = if (muted) "🔇" else "🔊"
        binding.btnVoiceDubToggle.setBackgroundResource(
            if (dubOn) R.drawable.bg_card_pink else R.drawable.bg_pill_dark
        )

        binding.tvAudioModeHint.text = when {
            muted -> "โหมด: ปิดเสียง (แสดงเฉพาะซับไตเติลภาษาไทย)"
            dubOn -> "โหมด: ซับไตเติล + พากย์เสียงภาษาไทยเรียลไทม์"
            else -> "โหมด: แสดงซับไตเติล (ปิดพากย์เสียง)"
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            SubtitleStateBus.isCapturing.collectLatest { running ->
                binding.tvLiveStatusPill.text = if (running) "● กำลังแปล" else "● หยุดแล้ว"
                binding.tvLiveStatusPill.setTextColor(
                    if (running) 0xFF4ADE80.toInt() else 0xFFD1C7E0.toInt()
                )
                binding.btnStartStopLive.text = if (running) "⏸  หยุด" else "▶  เริ่ม"
            }
        }

        lifecycleScope.launch {
            SubtitleStateBus.latestEnglishText.collectLatest { eng ->
                binding.tvLiveEnglish.text = if (eng.isEmpty()) "EN: —" else "EN: $eng"
            }
        }

        lifecycleScope.launch {
            SubtitleStateBus.latestThaiText.collectLatest { thai ->
                binding.tvLiveThai.text = thai
            }
        }
    }
}
