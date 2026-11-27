package com.gta6subdub.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

class SubtitleCaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_STOP = "com.gta6subdub.app.ACTION_STOP"
        private const val CHANNEL_ID = "gta6_subdub_capture"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: PrefsStore
    private lateinit var overlay: FloatingSubtitleOverlay
    private lateinit var androidTts: AndroidTtsSpeaker

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    @Volatile
    private var isProcessingFrame = false
    private var lastFrameTimestampMs = 0L
    private val minFrameIntervalMs = 650L // ~1.5 fps

    private var lastRecognizedText = ""
    private var lastApiCallMs = 0L

    override fun onCreate() {
        super.onCreate()
        prefs = PrefsStore(this)
        overlay = FloatingSubtitleOverlay(this)
        androidTts = AndroidTtsSpeaker(this)
        createNotificationChannel()

        serviceScope.launch {
            SubtitleStateBus.styleVersion.collectLatest {
                overlay.applyStyle(prefs.subtitleFontSizeSp, prefs.overlayBrightness)
                if (prefs.isMutedSubOnly || !prefs.autoDubEnabled) {
                    ElevenLabsClient.stopPlayback(this@SubtitleCaptureService)
                    androidTts.stop()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning = true
        SubtitleStateBus.setCapturing(true)
        SubtitleStateBus.updateThaiOnly("กำลังรอซับไตเติลจากเกม...")
        overlay.show(prefs.subtitleFontSizeSp, prefs.overlayBrightness)
        startCapture(resultCode, data)

        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val width = (metrics.widthPixels / 2).coerceAtLeast(480)
        val height = (metrics.heightPixels / 2).coerceAtLeast(360)
        val density = metrics.densityDpi

        val thread = HandlerThread("ScreenCaptureThread").apply { start() }
        captureThread = thread
        captureHandler = Handler(thread.looper)

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            val now = System.currentTimeMillis()
            if (isProcessingFrame || now - lastFrameTimestampMs < minFrameIntervalMs) {
                image.close()
                return@setOnImageAvailableListener
            }

            isProcessingFrame = true
            lastFrameTimestampMs = now

            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width

                val fullBitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                fullBitmap.copyPixelsFromBuffer(buffer)
                image.close()

                val isPortrait = height >= width
                val cropTop = if (isPortrait) {
                    (height * 0.30f).toInt().coerceIn(0, height - 1)
                } else {
                    (height * 0.60f).toInt().coerceIn(0, height - 1)
                }
                val cropBottom = if (isPortrait) {
                    (height * 0.85f).toInt().coerceIn(cropTop + 1, height)
                } else {
                    height
                }
                val cropHeight = (cropBottom - cropTop).coerceAtLeast(1)
                val croppedBitmap = Bitmap.createBitmap(fullBitmap, 0, cropTop, width, cropHeight)
                fullBitmap.recycle()

                runOcr(croppedBitmap)
            } catch (e: Exception) {
                e.printStackTrace()
                image.close()
                isProcessingFrame = false
            }
        }, captureHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "GTA6SubDubCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            captureHandler
        )
    }

    private fun runOcr(bitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                bitmap.recycle()
                val normalized = visionText.text
                    .replace(Regex("\\s+"), " ")
                    .trim()

                if (!shouldProcessNewSubtitle(normalized)) {
                    isProcessingFrame = false
                    return@addOnSuccessListener
                }

                lastRecognizedText = normalized
                lastApiCallMs = System.currentTimeMillis()

                serviceScope.launch {
                    translateAndDub(normalized)
                    isProcessingFrame = false
                }
            }
            .addOnFailureListener { e ->
                bitmap.recycle()
                e.printStackTrace()
                isProcessingFrame = false
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
        if (geminiKey.isEmpty()) {
            val msg = "กรุณาใส่ Gemini API Key ในแอป"
            overlay.updateText(msg)
            SubtitleStateBus.updateSubtitles(englishText, msg)
            return
        }

        try {
            val thaiText = GeminiClient.translateSubtitle(
                englishText = englishText,
                apiKey = geminiKey,
                model = prefs.geminiModel
            )
            overlay.updateText(thaiText)
            SubtitleStateBus.updateSubtitles(englishText, thaiText)

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
                    } catch (ttsErr: Exception) {
                        ttsErr.printStackTrace()
                        androidTts.speak(thaiText)
                    }
                } else {
                    androidTts.speak(thaiText)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            val err = "Error: ${e.message}"
            overlay.updateText(err)
            SubtitleStateBus.updateThaiOnly(err)
        }
    }

    override fun onDestroy() {
        isRunning = false
        SubtitleStateBus.setCapturing(false)
        SubtitleStateBus.updateThaiOnly("ยังไม่ได้เริ่มแปล")
        ElevenLabsClient.stopPlayback(this)
        androidTts.shutdown()
        overlay.hide()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        captureThread?.quitSafely()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GTA6 SubDub Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GTA6 SubDub กำลังทำงาน")
            .setContentText("กำลังแปลซับไตเติลและพากย์เสียงภาษาไทย...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }
}
