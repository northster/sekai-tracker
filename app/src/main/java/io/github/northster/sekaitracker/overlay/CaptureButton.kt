package io.github.northster.sekaitracker.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import io.github.northster.sekaitracker.R
import io.github.northster.sekaitracker.data.Settings
import kotlin.math.abs

/**
 * Small floating button: tap it on the result, song select or live prepare screen to read that screen once. Nothing is
 * captured until it is tapped, so it costs no battery while waiting. Drag it anywhere; the place is remembered.
 * Only the button itself takes touches, everything around it goes to the game.
 */
object CaptureButton {
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    /** hidden because the game is not in front, or while a frame is taken */
    private val hiddenFor = mutableSetOf<String>()

    fun show(context: Context, onTap: () -> Unit) {
        val app = context.applicationContext
        main.post {
            if (view != null || !AndroidSettings.canDrawOverlays(app)) return@post
            val wm = app.getSystemService(WindowManager::class.java)
            val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, app.resources.displayMetrics).toInt() }
            val size = dp(44f)
            val button = FrameLayout(app).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(170, 42, 32, 69))
                    setStroke(dp(2f), Color.argb(200, 127, 227, 214))
                }
                addView(ImageView(app).apply {
                    setImageResource(R.drawable.ic_notification)
                    setColorFilter(Color.WHITE)
                }, FrameLayout.LayoutParams(dp(24f), dp(24f), Gravity.CENTER))
                contentDescription = "세카이 기록: 지금 화면 읽기"
            }
            val bounds = wm.currentWindowMetrics.bounds
            val s = Settings.current
            val p = WindowManager.LayoutParams(
                size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = if (s.buttonX >= 0) s.buttonX else 0
                y = if (s.buttonY >= 0) s.buttonY else bounds.height() * 2 / 5
                title = "SekaiTrackerButton"
            }
            clamp(p, bounds.width(), bounds.height(), size)
            attachTouch(button, p, wm, onTap)
            runCatching { wm.addView(button, p) }.onSuccess {
                view = button; params = p
                applyVisibility()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouch(button: View, p: WindowManager.LayoutParams, wm: WindowManager, onTap: () -> Unit) {
        val slop = ViewConfiguration.get(button.context).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        button.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; dragging = false; v.alpha = 0.6f }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        p.x = startX + dx.toInt(); p.y = startY + dy.toInt()
                        val b = wm.currentWindowMetrics.bounds
                        clamp(p, b.width(), b.height(), v.width)
                        runCatching { wm.updateViewLayout(v, p) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.alpha = 1f
                    if (dragging) Settings.update { it.copy(buttonX = p.x, buttonY = p.y) } else onTap()
                }
                MotionEvent.ACTION_CANCEL -> v.alpha = 1f
            }
            true
        }
    }

    private fun clamp(p: WindowManager.LayoutParams, w: Int, h: Int, size: Int) {
        p.x = p.x.coerceIn(0, maxOf(0, w - size))
        p.y = p.y.coerceIn(0, maxOf(0, h - size))
    }

    /** keep the button on screen after folding / unfolding */
    fun onDisplayChanged() = main.post {
        val v = view ?: return@post
        val p = params ?: return@post
        val wm = v.context.getSystemService(WindowManager::class.java)
        val b = wm.currentWindowMetrics.bounds
        clamp(p, b.width(), b.height(), p.width)
        runCatching { wm.updateViewLayout(v, p) }
    }

    /** hide for a reason ("capture", "game") until [unhide] with the same reason */
    fun hide(reason: String) = main.post { hiddenFor += reason; applyVisibility() }
    fun unhide(reason: String) = main.post { hiddenFor -= reason; applyVisibility() }

    private fun applyVisibility() { view?.visibility = if (hiddenFor.isEmpty()) View.VISIBLE else View.INVISIBLE }

    fun remove() = main.post {
        val v = view ?: return@post
        view = null; params = null
        runCatching { v.context.getSystemService(WindowManager::class.java).removeView(v) }
    }
}
