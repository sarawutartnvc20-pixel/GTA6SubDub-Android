package com.gta6subdub.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView

class FloatingSubtitleOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var subtitleView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    @SuppressLint("ClickableViewAccessibility")
    fun show(fontSizeSp: Float = 18f, brightness: Float = 0.85f) {
        if (subtitleView != null) {
            applyStyle(fontSizeSp, brightness)
            return
        }

        val tv = TextView(context).apply {
            text = "รอซับไตเติลจากเกม..."
            setTextColor(Color.rgb(255, 235, 90))
            textSize = fontSizeSp
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(36, 20, 36, 20)
            setShadowLayer(4f, 0f, 2f, Color.BLACK)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 90
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        tv.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(tv, params)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(tv, params)
        subtitleView = tv
        layoutParams = params
        applyStyle(fontSizeSp, brightness)
    }

    fun applyStyle(fontSizeSp: Float, brightness: Float) {
        subtitleView?.post {
            val tv = subtitleView ?: return@post
            tv.textSize = fontSizeSp.coerceIn(12f, 34f)
            val alpha = (brightness.coerceIn(0.2f, 1.0f) * 235).toInt()
            val textAlpha = (0.45f + brightness.coerceIn(0.2f, 1.0f) * 0.55f)
            tv.setTextColor(
                Color.argb((textAlpha * 255).toInt(), 255, 235, 90)
            )
            tv.background = GradientDrawable().apply {
                setColor(Color.argb(alpha, 14, 12, 24))
                cornerRadius = 28f
                setStroke(2, Color.argb(95, 255, 110, 180))
            }
        }
    }

    fun updateText(thaiText: String) {
        subtitleView?.post {
            subtitleView?.text = thaiText
        }
    }

    fun hide() {
        subtitleView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        subtitleView = null
        layoutParams = null
    }
}
