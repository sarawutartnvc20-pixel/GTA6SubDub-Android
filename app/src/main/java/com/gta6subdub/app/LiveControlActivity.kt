package com.gta6subdub.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
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
    private var pendingCandidateText = ""
    private var translateJob: Job? = null

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
            ocrHandler.postDelayed(this, 250L)
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
                voiceRouter.stopAll()
            }
            refreshAudioButtons()
        }

        binding.btnVoiceDubToggle.setOnClickListener {
            prefs.autoDubEnabled = !prefs.autoDubEnabled
            if (prefs.autoDubEnabled) {
                prefs.isMutedSubOnly = false
            } else {
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
        binding.cameraScanGuide.visibility = if (isCamera) View.VISIBLE else View.GONE
        if (!isCamera) {
            binding.texturePs5Stream.setTransform(Matrix())
        }
        if (!isRunning) {
            binding.tvVideoPlaceholder.text = if (isCamera) {
                "โหมดกล้องส่องทีวี: กด ▶ เริ่ม แล้ววางซับอังกฤษให้อยู่ในแถบสีเหลือง"
            } else {
                "โหมด PS5 Remote (${prefs.ps5Resolution}): กด ▶ เริ่ม เพื่อเชื่อมภาพ\n(แตะปุ่ม ${prefs.ps5Resolution} ด้านล่างเพื่อปรับ 360p / 540p / 720p / 1080p)"
            }
        }
    }

    private fun startActiveSource() {
        if (prefs.geminiModel != "instant_free" && prefs.geminiApiKey.isEmpty()) {
            Toast.makeText(this, "กรุณากรอก Gemini API Key (หรือเลือกโหมดแปลด่วนฟรี) ในหน้าตั้งค่าครับ", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        GeminiClient.clearHistory()
        lastRecognizedText = ""
        pendingCandidateText = ""

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
        translateJob?.cancel()
        translateJob = null
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
            binding.texturePs5Stream.setTransform(Matrix())
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
            ocrHandler.postDelayed(ocrLoopRunnable, 800L)
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

    // MARK: - Mode 2: Rear Camera OCR (Unsquashed Aspect-Ratio + Continuous Autofocus)

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

            val characteristics = manager.getCameraCharacteristics(backCameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val previewSize = chooseBestPreviewSize(map?.getOutputSizes(SurfaceTexture::class.java))

            val thread = HandlerThread("CameraOcrThread").apply { start() }
            cameraThread = thread
            cameraHandler = Handler(thread.looper)

            binding.tvVideoPlaceholder.visibility = View.GONE
            binding.cameraScanGuide.visibility = View.VISIBLE
            updateStreamUI(true, "● กำลังแปล (กล้อง)")
            binding.tvLiveThai.text = "เล็งซับไตเติลให้อยู่ในแถบสีเหลือง..."

            configureCameraTransform(
                viewWidth = binding.texturePs5Stream.width,
                viewHeight = binding.texturePs5Stream.height,
                previewSize = previewSize
            )

            manager.openCamera(backCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    val surfaceTexture = binding.texturePs5Stream.surfaceTexture ?: return
                    surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
                    streamSurface?.release()
                    val previewSurface = Surface(surfaceTexture)
                    streamSurface = previewSurface

                    val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    requestBuilder.addTarget(previewSurface)
                    requestBuilder.set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                    )
                    requestBuilder.set(
                        CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON
                    )

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
                                    ocrHandler.postDelayed(ocrLoopRunnable, 500L)
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

    private fun chooseBestPreviewSize(sizes: Array<Size>?): Size {
        if (sizes.isNullOrEmpty()) return Size(1280, 720)
        // Prefer crisp 16:9 size closest to 1280x720
        val candidates = sizes.filter { it.width >= 960 && it.width <= 1920 }
        val pool = if (candidates.isNotEmpty()) candidates else sizes.toList()
        return pool.minByOrNull { abs(it.width - 1280) + abs(it.height - 720) } ?: Size(1280, 720)
    }

    /**
     * Prevents the portrait camera buffer (e.g. 720x1280) from being squashed flat inside
     * the wide 16:9 TextureView by applying a 1:1 aspect-preserving center-crop Matrix.
     */
    private fun configureCameraTransform(viewWidth: Int, viewHeight: Int, previewSize: Size) {
        if (viewWidth <= 0 || viewHeight <= 0) return

        // On portrait phone screens, Camera2 rotates the sensor buffer so rendered aspect is (min / max)
        val isDevicePortrait = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_PORTRAIT
        val bufW = if (isDevicePortrait) minOf(previewSize.width, previewSize.height).toFloat()
        else maxOf(previewSize.width, previewSize.height).toFloat()
        val bufH = if (isDevicePortrait) maxOf(previewSize.width, previewSize.height).toFloat()
        else minOf(previewSize.width, previewSize.height).toFloat()

        val viewW = viewWidth.toFloat()
        val viewH = viewHeight.toFloat()

        val currentScaleX = viewW / bufW
        val currentScaleY = viewH / bufH
        val targetScale = maxOf(currentScaleX, currentScaleY)

        val scaleX = targetScale / currentScaleX
        val scaleY = targetScale / currentScaleY

        val matrix = Matrix()
        matrix.setScale(scaleX, scaleY, viewW / 2f, viewH / 2f)
        binding.texturePs5Stream.setTransform(matrix)
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

    // MARK: - OCR -> Gemini (GTA 6 Slang Pack) -> Smart Speech Queue

    private fun captureFrameAndRunOcr() {
        if (isOcrBusy || !binding.texturePs5Stream.isAvailable) return
        val fullBitmap = binding.texturePs5Stream.bitmap ?: return
        if (fullBitmap.width < 10 || fullBitmap.height < 10) {
            fullBitmap.recycle()
            return
        }

        isOcrBusy = true
        val isCamera = prefs.captureMode == "camera"
        // Crop bottom subtitle zone (35% in Camera mode for forgiving aim, 32% in PS5 mode)
        val cropStartRatio = if (isCamera) 0.65f else 0.68f
        val cropTop = (fullBitmap.height * cropStartRatio).toInt().coerceIn(0, fullBitmap.height - 1)
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

                // In Camera mode, show live raw OCR feedback immediately so user knows aiming works
                if (isCamera && normalized.length >= 4) {
                    binding.tvLiveEnglish.text = "EN: $normalized"
                }

                if (!isLikelyEnglishSubtitle(normalized, relaxed = isCamera)) {
                    pendingCandidateText = ""
                    isOcrBusy = false
                    return@addOnSuccessListener
                }

                if (!shouldProcessNewSubtitle(normalized)) {
                    isOcrBusy = false
                    return@addOnSuccessListener
                }

                // In Camera mode OR clear full sentences (>= 4 words), translate immediately on 1st frame!
                // Only require 2-frame stability for short 2-3 word fragments in PS5 mode.
                val wordCount = normalized.split(" ").count { it.length >= 2 }
                val needsTwoFrames = !isCamera && wordCount < 4
                if (needsTwoFrames && !isSimilarText(pendingCandidateText, normalized)) {
                    pendingCandidateText = normalized
                    isOcrBusy = false
                    return@addOnSuccessListener
                }

                pendingCandidateText = ""
                lastRecognizedText = normalized
                binding.tvLiveEnglish.text = "EN: $normalized"

                // CRITICAL: Release OCR immediately (~25ms) so camera/PS5 never freezes waiting for API!
                isOcrBusy = false

                // Cancel any stale in-flight translation and start translating the newest subtitle immediately
                translateJob?.cancel()
                translateJob = lifecycleScope.launch {
                    translateAndDub(normalized)
                }
            }
            .addOnFailureListener {
                cropped.recycle()
                isOcrBusy = false
            }
    }

    private fun isLikelyEnglishSubtitle(text: String, relaxed: Boolean): Boolean {
        if (text.length < 6) return false
        val lower = text.lowercase()
        if (lower.contains("http") || lower.contains("www.") || lower.contains(".net") ||
            lower.contains(".com") || lower.contains("remoteplay") || lower.contains("playstation")
        ) {
            return false
        }

        val asciiRatio = text.count { it.code in 32..126 }.toFloat() / text.length
        val minAscii = if (relaxed) 0.84f else 0.92f
        if (asciiRatio < minAscii) return false

        val hasLowercase = text.any { it in 'a'..'z' }
        if (!hasLowercase) return false

        val words = text.split(" ").filter { it.isNotBlank() }
        val cleanEnglishWords = words.count { w ->
            val letters = w.count { it in 'A'..'Z' || it in 'a'..'z' }
            letters >= 2 && letters.toFloat() / w.length >= 0.60f
        }
        return cleanEnglishWords >= 2 && cleanEnglishWords.toFloat() / words.size >= 0.45f
    }

    private fun shouldProcessNewSubtitle(text: String): Boolean {
        if (text.equals(lastRecognizedText, ignoreCase = true)) return false
        if (isSimilarText(lastRecognizedText, text)) return false
        return true
    }

    private fun isSimilarText(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val aWords = a.lowercase().split(" ").filter { it.length >= 2 }.toSet()
        val bWords = b.lowercase().split(" ").filter { it.length >= 2 }.toSet()
        if (aWords.isEmpty() || bWords.isEmpty()) return false
        val intersection = aWords.intersect(bWords).size
        val union = aWords.union(bWords).size
        return intersection.toFloat() / union.toFloat() >= 0.75f
    }

    private suspend fun translateAndDub(englishText: String) {
        val model = prefs.geminiModel
        val geminiKey = prefs.geminiApiKey

        // Stage 0: Instant 0ms local cache or downloaded game pack hit
        val cached = GeminiClient.getCachedTranslation(this, prefs.selectedGameId, englishText)
        if (cached != null) {
            withContext(Dispatchers.Main) {
                binding.tvLiveThai.text = cached
                binding.tvAudioModeHint.text = "แปล: ${GeminiClient.lastUsedEngine}"
            }
            enqueueVoiceIfEnabled(cached)
            return
        }

        // Fast free-only mode (~0.2s total)
        if (model == "instant_free") {
            try {
                val fastThai = GeminiClient.translateSubtitle(
                    context = this,
                    englishText = englishText,
                    apiKey = geminiKey,
                    model = "instant_free",
                    gameId = prefs.selectedGameId
                )
                withContext(Dispatchers.Main) {
                    binding.tvLiveThai.text = fastThai
                    binding.tvAudioModeHint.text = "แปล: ${GeminiClient.lastUsedEngine}"
                }
                enqueueVoiceIfEnabled(fastThai)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvLiveThai.text = "Error: ${e.message}"
                }
            }
            return
        }

        if (geminiKey.isEmpty()) return

        // Two-Stage Instant Subtitle:
        // Stage 1: Fire fast ~0.25s draft in parallel so player sees Thai immediately while Gemini polishes GTA slang
        var geminiFinished = false
        val draftJob = lifecycleScope.launch {
            try {
                val draftThai = FreeTranslateFallback.translateEnToTh(englishText)
                if (!geminiFinished) {
                    withContext(Dispatchers.Main) {
                        if (!geminiFinished) {
                            binding.tvLiveThai.text = draftThai
                            binding.tvAudioModeHint.text = "⚡ แปลด่วน (~0.2 วิ) · กำลังเกลาสำนวน GTA..."
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // Stage 2: Raw uncensored GTA 6 slang translation from Gemini + Smart Queue voice
        try {
            val thaiText = GeminiClient.translateSubtitle(
                context = this,
                englishText = englishText,
                apiKey = geminiKey,
                model = model,
                gameId = prefs.selectedGameId
            )
            geminiFinished = true
            draftJob.cancel()

            withContext(Dispatchers.Main) {
                binding.tvLiveThai.text = thaiText
                binding.tvAudioModeHint.text = "แปล: ${GeminiClient.lastUsedEngine}"
            }
            enqueueVoiceIfEnabled(thaiText)
        } catch (e: Exception) {
            geminiFinished = true
            draftJob.cancel()
            withContext(Dispatchers.Main) {
                binding.tvLiveThai.text = "Error: ${e.message}"
            }
        }
    }

    private fun enqueueVoiceIfEnabled(thaiText: String) {
        if (prefs.autoDubEnabled && !prefs.isMutedSubOnly) {
            voiceRouter.enqueueSpeak(thaiText) { used ->
                runOnUiThread {
                    binding.tvAudioModeHint.text = "แปล: ${GeminiClient.lastUsedEngine} · เสียง: $used"
                }
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
            else -> "Android TTS (1.22x)"
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

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        chiakiSession?.setSurface(null)
        streamSurface?.release()
        streamSurface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}

    override fun onDestroy() {
        stopActiveSource()
        voiceRouter.shutdown()
        super.onDestroy()
    }
}
