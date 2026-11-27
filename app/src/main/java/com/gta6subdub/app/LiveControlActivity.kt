package com.gta6subdub.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class LiveControlActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private lateinit var binding: ActivityLiveControlBinding
    private lateinit var prefs: PrefsStore
    private lateinit var voiceRouter: VoiceRouter

    private var chiakiSession: Session? = null
    private var streamSurface: Surface? = null

    // Camera2 fields for Camera OCR mode
    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var isRunning = false
    private val ocrHandler = Handler(Looper.getMainLooper())
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var isOcrBusy = false
    private var lastRecognizedText = ""
    private var lastApiCallMs = 0L
    private var ttsJob: Job? = null

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCameraCapture()
            } else {
                Toast.makeText(this, "ต้องอนุญาตใช้กล้องเพื่อส่องทีวีครับ", Toast.LENGTH_LONG).show()
            }
        }

    private val ocrLoopRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            captureFrameAndRunOcr()
            ocrHandler.postDelayed(this, 650L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLiveControlBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsStore(this)
        voiceRouter = VoiceRouter(this)
        binding.texturePs5Stream.surfaceTextureListener = this

        initSliders()
        refreshModeUI()
        refreshAudioButtons()
        updateStreamUI(false, "● หยุดแล้ว")

        binding.btnCloseLive.setOnClickListener { finish() }

        binding.btnToggleSliders.setOnClickListener {
            binding.layoutSliders.visibility =
                if (binding.layoutSliders.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        binding.btnSwitchSource.setOnClickListener {
            if (isRunning) stopActiveSource()
            prefs.captureMode = if (prefs.captureMode == "ps5") "camera" else "ps5"
            refreshModeUI()
        }

        binding.tvQualityBadge.setOnClickListener {
            if (prefs.captureMode == "camera") return@setOnClickListener
            val order = listOf("360p", "540p", "720p", "1080p")
            val next = order[(order.indexOf(prefs.ps5Resolution) + 1) % order.size]
            prefs.ps5Resolution = next
            refreshModeUI()
            Toast.makeText(
                this,
                "ปรับความละเอียด PS5 เป็น $next (กดหยุดแล้วเริ่มใหม่เพื่อให้มีผล)",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnMuteToggle.setOnClickListener {
            prefs.isMutedSubOnly = !prefs.isMutedSubOnly
            if (prefs.isMutedSubOnly) {
                ttsJob?.cancel()
                voiceRouter.stopAll()
            }
            refreshAudioButtons()
        }

        binding.btnVoiceDubToggle.setOnClickListener {
            prefs.autoDubEnabled = !prefs.autoDubEnabled
            if (prefs.autoDubEnabled) {
                prefs.isMutedSubOnly = false
            } else {
                ttsJob?.cancel()
                voiceRouter.stopAll()
            }
            refreshAudioButtons()
        }

        binding.btnStartStopLive.setOnClickListener {
            if (isRunning) {
                stopActiveSource()
            } else {
                startActiveSource()
            }
        }
    }

    private fun refreshModeUI() {
        val isCamera = prefs.captureMode == "camera"
        binding.btnSwitchSource.text = if (isCamera) "โหมด: กล้อง" else "โหมด: PS5"
        binding.tvQualityBadge.text = if (isCamera) "CAM" else prefs.ps5Resolution
        if (!isRunning) {
            binding.tvVideoPlaceholder.text = if (isCamera) {
                "โหมดกล้องส่องทีวี: กด ▶ เริ่ม แล้วหันกล้องส่องซับไตเติลบนทีวี"
            } else {
                "โหมด PS5 Remote (${prefs.ps5Resolution}): กด ▶ เริ่ม เพื่อเชื่อมภาพ\n(แตะปุ่ม ${prefs.ps5Resolution} ด้านล่างเพื่อปรับ 360p / 540p / 720p / 1080p)"
            }
        }
    }

    private fun startActiveSource() {
        if (prefs.geminiApiKey.isEmpty()) {
            Toast.makeText(this, "กรุณากรอก Gemini API Key ในหน้าตั้งค่าก่อนครับ", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        GeminiClient.clearHistory()
        lastRecognizedText = ""

        if (prefs.captureMode == "camera") {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                startCameraCapture()
            }
        } else {
            startPs5Stream()
        }
    }

    private fun stopActiveSource() {
        isRunning = false
        ocrHandler.removeCallbacks(ocrLoopRunnable)
        ttsJob?.cancel()
        voiceRouter.stopAll()
        stopPs5Session()
        stopCameraCapture()
        updateStreamUI(false, "● หยุดแล้ว")
        binding.tvVideoPlaceholder.visibility = View.VISIBLE
    }

    // MARK: - Mode 1: Embedded PS5 Remote Play (Chiaki)

    private fun startPs5Stream() {
        val registKey = prefs.getRegistKeyBytes()
        val rpKey = prefs.getRpKeyBytes()
        if (!prefs.isPsnLoggedIn || !prefs.isPs5Paired || registKey == null || rpKey == null) {
            Toast.makeText(this, "ยังไม่ได้จับคู่ PS5 — เปิดหน้าเชื่อมต่อ 3 ขั้นตอนให้ครับ", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, Ps5SetupWizardActivity::class.java))
            return
        }

        try {
            updateStreamUI(true, "● กำลังเชื่อม PS5...")
            binding.tvVideoPlaceholder.visibility = View.GONE
            binding.tvLiveThai.text = "กำลังเชื่อมต่อ PS5 (${prefs.ps5Host} · ${prefs.ps5Resolution})..."

            val (resPreset, fpsPreset) = when (prefs.ps5Resolution) {
                "360p" -> VideoResolutionPreset.RES_360P to VideoFPSPreset.FPS_30
                "540p" -> VideoResolutionPreset.RES_540P to VideoFPSPreset.FPS_30
                "1080p" -> VideoResolutionPreset.RES_1080P to VideoFPSPreset.FPS_60
                else -> VideoResolutionPreset.RES_720P to VideoFPSPreset.FPS_30
            }

            val profile = ConnectVideoProfile.preset(
                resPreset,
                fpsPreset,
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
                            stopActiveSource()
                            binding.tvLiveThai.text = if (msg.contains("already in use", ignoreCase = true)) {
                                "PS5 กำลังปิดการเชื่อมต่อเดิม รอ 10-15 วินาทีแล้วกด ▶ เริ่ม ใหม่ครับ"
                            } else {
                                "หลุดการเชื่อมต่อ: $msg"
                            }
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
            isRunning = true
            ocrHandler.removeCallbacks(ocrLoopRunnable)
            ocrHandler.postDelayed(ocrLoopRunnable, 1000L)
        } catch (e: Exception) {
            e.printStackTrace()
            stopActiveSource()
            Toast.makeText(this, "เชื่อมต่อ PS5 ไม่สำเร็จ: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopPs5Session() {
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
    }

    // MARK: - Mode 2: Rear Camera OCR (TV / Monitor)

    @SuppressLint("MissingPermission")
    private fun startCameraCapture() {
        if (!binding.texturePs5Stream.isAvailable) {
            Toast.makeText(this, "หน้าจอกล้องยังไม่พร้อม กรุณากดเริ่มอีกครั้ง", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val backCameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull()
                ?: throw IllegalStateException("ไม่พบกล้องในเครื่อง")

            val thread = HandlerThread("CameraOcrThread").apply { start() }
            cameraThread = thread
            cameraHandler = Handler(thread.looper)

            binding.tvVideoPlaceholder.visibility = View.GONE
            updateStreamUI(true, "● กำลังแปล (กล้อง)")
            binding.tvLiveThai.text = "หันกล้องไปที่ซับไตเติลบนทีวี..."

            manager.openCamera(backCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    val surfaceTexture = binding.texturePs5Stream.surfaceTexture ?: return
                    surfaceTexture.setDefaultBufferSize(1280, 720)
                    streamSurface?.release()
                    val previewSurface = Surface(surfaceTexture)
                    streamSurface = previewSurface

                    val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    requestBuilder.addTarget(previewSurface)

                    @Suppress("DEPRECATION")
                    camera.createCaptureSession(
                        listOf(previewSurface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                cameraCaptureSession = session
                                try {
                                    session.setRepeatingRequest(requestBuilder.build(), null, cameraHandler)
                                    isRunning = true
                                    ocrHandler.removeCallbacks(ocrLoopRunnable)
                                    ocrHandler.postDelayed(ocrLoopRunnable, 800L)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                runOnUiThread {
                                    stopActiveSource()
                                    Toast.makeText(this@LiveControlActivity, "เปิดกล้องไม่สำเร็จ", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        cameraHandler
                    )
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    runOnUiThread { stopActiveSource() }
                }
            }, cameraHandler)
        } catch (e: Exception) {
            e.printStackTrace()
            stopActiveSource()
            Toast.makeText(this, "เปิดกล้องไม่สำเร็จ: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopCameraCapture() {
        try {
            cameraCaptureSession?.close()
            cameraDevice?.close()
            cameraThread?.quitSafely()
        } catch (_: Exception) {
        }
        cameraCaptureSession = null
        cameraDevice = null
        cameraThread = null
        cameraHandler = null
    }

    // MARK: - OCR -> Gemini (GTA 6 Slang Pack) -> VoiceRouter (Gemini TTS / ElevenLabs / Android)

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
        if (text.length < 6) return false
        if (text.equals(lastRecognizedText, ignoreCase = true)) return false

        // Ignore ALL-CAPS HUD prompts / button legends (e.g., "SELECT BACK O", "OTRENTSID)")
        val hasLowercase = text.any { it in 'a'..'z' }
        val wordCount = text.split(" ").count { word -> word.count { it.isLetter() } >= 2 }
        if (!hasLowercase || wordCount < 2) {
            return false
        }

        val elapsed = System.currentTimeMillis() - lastApiCallMs
        if (elapsed < 1500L && isMinorFlicker(lastRecognizedText, text)) {
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
                context = this,
                englishText = englishText,
                apiKey = geminiKey,
                model = prefs.geminiModel,
                gameId = prefs.selectedGameId
            )
            withContext(Dispatchers.Main) {
                binding.tvLiveThai.text = thaiText
            }

            if (prefs.autoDubEnabled && !prefs.isMutedSubOnly) {
                ttsJob?.cancel()
                ttsJob = lifecycleScope.launch {
                    val used = voiceRouter.speak(thaiText)
                    withContext(Dispatchers.Main) {
                        binding.tvAudioModeHint.text = "เสียงล่าสุด: $used"
                    }
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                binding.tvLiveThai.text = "Error: ${e.message}"
            }
        }
    }

    // MARK: - Sliders & Controls

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
        val lp = window.attributes
        lp.screenBrightness = prefs.overlayBrightness
        window.attributes = lp
    }

    private fun refreshAudioButtons() {
        val muted = prefs.isMutedSubOnly
        val dubOn = prefs.autoDubEnabled && !muted

        binding.btnMuteToggle.text = if (muted) "🔇" else "🔊"
        binding.btnVoiceDubToggle.setBackgroundResource(
            if (dubOn) R.drawable.bg_card_pink else R.drawable.bg_pill_dark
        )

        val engineLabel = when (prefs.voiceEngine) {
            "gemini" -> "Gemini TTS (${prefs.geminiTtsVoice})"
            "elevenlabs" -> "ElevenLabs"
            else -> "Android TTS"
        }

        binding.tvAudioModeHint.text = when {
            muted -> "โหมด: ปิดเสียง (แสดงเฉพาะซับไตเติลภาษาไทย)"
            dubOn -> "โหมด: ซับไตเติล + พากย์เสียง $engineLabel"
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
        stopActiveSource()
        voiceRouter.shutdown()
        super.onDestroy()
    }
}
