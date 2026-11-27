package com.gta6subdub.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.gta6subdub.app.databinding.ActivityLiveControlBinding
import com.metallic.chiaki.lib.Codec
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.ConnectVideoProfile
import com.metallic.chiaki.lib.ConnectedEvent
import com.metallic.chiaki.lib.QuitEvent
import com.metallic.chiaki.lib.Session
import com.metallic.chiaki.lib.VideoFPSPreset
import com.metallic.chiaki.lib.VideoResolutionPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class LiveControlActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private lateinit var binding: ActivityLiveControlBinding
    private lateinit var prefs: PrefsStore
    private lateinit var androidTts: AndroidTtsSpeaker

    private var chiakiSession: Session? = null
    private var streamSurface: Surface? = null
    private var isStreaming = false

    private val ocrHandler = Handler(Looper.getMainLooper())
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var isOcrBusy = false
    private var lastRecognizedText = ""
    private var lastApiCallMs = 0L

    private val ocrLoopRunnable = object : Runnable {
        override fun run() {
            if (!isStreaming) return
            captureFrameAndRunOcr()
            ocrHandler.postDelayed(this, 650L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLiveControlBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsStore(this)
        androidTts = AndroidTtsSpeaker(this)
        binding.texturePs5Stream.surfaceTextureListener = this

        initSliders()
        refreshAudioButtons()
        updateStreamUI(false, "● หยุดแล้ว")

        binding.btnCloseLive.setOnClickListener { finish() }

        binding.btnToggleSliders.setOnClickListener {
            binding.layoutSliders.visibility =
                if (binding.layoutSliders.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        binding.btnPairShortcut.setOnClickListener {
            startActivity(Intent(this, Ps5PairActivity::class.java))
        }

        binding.btnMuteToggle.setOnClickListener {
            prefs.isMutedSubOnly = !prefs.isMutedSubOnly
            if (prefs.isMutedSubOnly) {
                ElevenLabsClient.stopPlayback(this)
                androidTts.stop()
            }
            refreshAudioButtons()
        }

        binding.btnVoiceDubToggle.setOnClickListener {
            prefs.autoDubEnabled = !prefs.autoDubEnabled
            if (prefs.autoDubEnabled) {
                prefs.isMutedSubOnly = false
            } else {
                ElevenLabsClient.stopPlayback(this)
                androidTts.stop()
            }
            refreshAudioButtons()
        }

        binding.btnStartStopLive.setOnClickListener {
            if (isStreaming) {
                stopPs5Stream()
            } else {
                startPs5Stream()
            }
        }
    }

    private fun startPs5Stream() {
        if (!prefs.isPsnLoggedIn) {
            Toast.makeText(this, "กรุณาล็อกอินบัญชี PSN ที่สองก่อนครับ", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, PsnAuthActivity::class.java))
            return
        }

        val registKey = prefs.getRegistKeyBytes()
        val rpKey = prefs.getRpKeyBytes()
        if (!prefs.isPs5Paired || registKey == null || rpKey == null) {
            Toast.makeText(this, "กรุณาจับคู่เครื่อง PS5 (PIN 8 หลัก) ก่อนครับ", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, Ps5PairActivity::class.java))
            return
        }

        if (prefs.geminiApiKey.isEmpty()) {
            Toast.makeText(this, "กรุณากรอก Gemini API Key ในหน้าตั้งค่าก่อนครับ", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        try {
            updateStreamUI(true, "● กำลังเชื่อม PS5...")
            binding.tvVideoPlaceholder.visibility = View.GONE
            binding.tvLiveThai.text = "กำลังเชื่อมต่อ PS5 (${prefs.ps5Host})..."

            val profile = ConnectVideoProfile.preset(
                VideoResolutionPreset.RES_720P,
                VideoFPSPreset.FPS_30,
                Codec.CODEC_H264
            )
            val connectInfo = ConnectInfo(
                ps5 = true,
                host = prefs.ps5Host,
                registKey = registKey,
                morning = rpKey,
                videoProfile = profile
            )

            val session = Session(connectInfo, null, false)
            session.eventCallback = { event ->
                runOnUiThread {
                    when (event) {
                        is ConnectedEvent -> {
                            updateStreamUI(true, "● กำลังแปล")
                            binding.tvLiveThai.text = "เชื่อมต่อ PS5 สำเร็จ! รอซับไตเติล..."
                        }
                        is QuitEvent -> {
                            val msg = event.reasonString ?: event.reason.toString()
                            stopPs5Stream()
                            binding.tvLiveThai.text = "หลุดการเชื่อมต่อ: $msg"
                        }
                        else -> {}
                    }
                }
            }

            if (binding.texturePs5Stream.isAvailable) {
                streamSurface?.release()
                val surf = Surface(binding.texturePs5Stream.surfaceTexture)
                streamSurface = surf
                session.setSurface(surf)
            }

            val err = session.start()
            if (!err.isSuccess) {
                session.dispose()
                throw IllegalStateException("Session start failed: $err")
            }

            chiakiSession = session
            isStreaming = true
            ocrHandler.removeCallbacks(ocrLoopRunnable)
            ocrHandler.postDelayed(ocrLoopRunnable, 1000L)
        } catch (e: Exception) {
            e.printStackTrace()
            stopPs5Stream()
            Toast.makeText(this, "เชื่อมต่อ PS5 ไม่สำเร็จ: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopPs5Stream() {
        isStreaming = false
        ocrHandler.removeCallbacks(ocrLoopRunnable)
        ElevenLabsClient.stopPlayback(this)
        androidTts.stop()

        val s = chiakiSession
        chiakiSession = null
        if (s != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    s.stop()
                    s.dispose()
                } catch (_: Exception) {
                }
            }
        }

        updateStreamUI(false, "● หยุดแล้ว")
        binding.tvVideoPlaceholder.visibility = View.VISIBLE
    }

    private fun captureFrameAndRunOcr() {
        if (isOcrBusy || !binding.texturePs5Stream.isAvailable) return
        val fullBitmap = binding.texturePs5Stream.bitmap ?: return
        if (fullBitmap.width < 10 || fullBitmap.height < 10) {
            fullBitmap.recycle()
            return
        }

        isOcrBusy = true
        val cropTop = (fullBitmap.height * 0.62f).toInt().coerceIn(0, fullBitmap.height - 1)
        val cropHeight = (fullBitmap.height - cropTop).coerceAtLeast(1)
        val cropped = Bitmap.createBitmap(fullBitmap, 0, cropTop, fullBitmap.width, cropHeight)
        fullBitmap.recycle()

        val inputImage = InputImage.fromBitmap(cropped, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                cropped.recycle()
                val normalized = visionText.text
                    .replace(Regex("\\s+"), " ")
                    .trim()

                if (!shouldProcessNewSubtitle(normalized)) {
                    isOcrBusy = false
                    return@addOnSuccessListener
                }

                lastRecognizedText = normalized
                lastApiCallMs = System.currentTimeMillis()
                binding.tvLiveEnglish.text = "EN: $normalized"

                lifecycleScope.launch {
                    translateAndDub(normalized)
                    isOcrBusy = false
                }
            }
            .addOnFailureListener {
                cropped.recycle()
                isOcrBusy = false
            }
    }

    private fun shouldProcessNewSubtitle(text: String): Boolean {
        if (text.length < 4) return false
        if (text.equals(lastRecognizedText, ignoreCase = true)) return false

        val elapsed = System.currentTimeMillis() - lastApiCallMs
        if (elapsed < 1200L && isMinorFlicker(lastRecognizedText, text)) {
            return false
        }
        return true
    }

    private fun isMinorFlicker(old: String, new: String): Boolean {
        if (old.isEmpty()) return false
        val oldL = old.lowercase()
        val newL = new.lowercase()
        return (oldL.contains(newL) || newL.contains(oldL)) && abs(oldL.length - newL.length) <= 3
    }

    private suspend fun translateAndDub(englishText: String) {
        val geminiKey = prefs.geminiApiKey
        if (geminiKey.isEmpty()) return

        try {
            val thaiText = GeminiClient.translateSubtitle(
                englishText = englishText,
                apiKey = geminiKey,
                model = prefs.geminiModel
            )
            withContext(Dispatchers.Main) {
                binding.tvLiveThai.text = thaiText
            }

            if (prefs.autoDubEnabled && !prefs.isMutedSubOnly) {
                val elevenKey = prefs.elevenLabsApiKey
                val voiceId = prefs.elevenLabsVoiceId
                if (elevenKey.isNotEmpty() && voiceId.isNotEmpty()) {
                    try {
                        ElevenLabsClient.synthesizeAndPlay(
                            context = this,
                            text = thaiText,
                            apiKey = elevenKey,
                            voiceId = voiceId
                        )
                    } catch (_: Exception) {
                        androidTts.speak(thaiText)
                    }
                } else {
                    androidTts.speak(thaiText)
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                binding.tvLiveThai.text = "Error: ${e.message}"
            }
        }
    }

    private fun initSliders() {
        val currentFontProgress = (prefs.subtitleFontSizeSp - 12f).toInt().coerceIn(0, 22)
        binding.seekFontSize.progress = currentFontProgress
        applyPreviewStyle()

        binding.seekFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                prefs.subtitleFontSizeSp = 12f + progress
                applyPreviewStyle()
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
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun applyPreviewStyle() {
        binding.tvLiveThai.textSize = prefs.subtitleFontSizeSp
        val brightness = prefs.overlayBrightness
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

    private fun updateStreamUI(running: Boolean, pillText: String) {
        binding.tvLiveStatusPill.text = pillText
        binding.tvLiveStatusPill.setTextColor(
            if (running) 0xFF4ADE80.toInt() else 0xFFD1C7E0.toInt()
        )
        binding.btnStartStopLive.text = if (running) "⏸  หยุด" else "▶  เริ่ม"
    }

    // MARK: - TextureView.SurfaceTextureListener

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        streamSurface?.release()
        val surf = Surface(surfaceTexture)
        streamSurface = surf
        chiakiSession?.setSurface(surf)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        chiakiSession?.setSurface(null)
        streamSurface?.release()
        streamSurface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    override fun onDestroy() {
        stopPs5Stream()
        androidTts.shutdown()
        super.onDestroy()
    }
}
