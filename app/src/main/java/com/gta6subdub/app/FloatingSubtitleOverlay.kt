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
    fun show() {
        if (subtitleView != null) return

        val bg = GradientDrawable().apply {
            setColor(Color.argb(215, 16, 16, 22))
            cornerRadius = 28f
            setStroke(2, Color.argb(90, 255, 255, 255))
        }

        val tv = TextView(context).apply {
            text = "รอซับไตเติลจากเกม..."
            setTextColor(Color.rgb(255, 235, 90))
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(36, 20, 36, 20)
            background = bg
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

        // Allow dragging the floating subtitle box anywhere on screen
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
