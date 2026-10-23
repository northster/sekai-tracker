package io.github.northster.sekaitracker.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import io.github.northster.sekaitracker.data.OverlayCorner
import io.github.northster.sekaitracker.data.PlayRecord
import io.github.northster.sekaitracker.data.Settings
import io.github.northster.sekaitracker.data.Stats
import java.util.Locale

/**
 * Small box in a screen corner after a result was read. It can never get in the way of playing:
 * - it does not take touches (FLAG_NOT_TOUCHABLE) and its opacity stays at 0.8, the limit up to which Android 12+
 *   passes touches through windows of other apps
 * - it is only added for a few seconds after a result screen, and removed as soon as the screen changes
 */
object ResultOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private val hideRunnable = Runnable { hide() }

    private fun pct(v: Double?) = v?.let { String.format(Locale.US, "%.2f%%", it) } ?: "-"

    fun showResult(context: Context, record: PlayRecord, cmp: Stats.Comparison) {
        val head = "${record.title} · ${record.difficulty?.label ?: "?"}${record.level?.let { " $it" } ?: ""}"
        val best = when {
            cmp.previousBest == null -> "첫 기록"
            cmp.newBest -> "최고 기록 갱신! (이전 ${pct(cmp.previousBest)})"
            else -> "최고 ${pct(cmp.previousBest)} · ${String.format(Locale.US, "%+.2f", (cmp.accuracy ?: 0.0) - cmp.previousBest)}%p"
        }
        val tag = when { record.allPerfect -> "ALL PERFECT"; record.fullCombo -> "FULL COMBO"; else -> null }
        show(context, listOfNotNull(head, "정확도 ${pct(cmp.accuracy)}" + (tag?.let { "  $it" } ?: ""), best),
            highlight = cmp.newBest && cmp.previousBest != null)
    }

    fun showReview(context: Context, record: PlayRecord, cmp: Stats.Comparison) {
        show(context, listOf(record.title.ifBlank { "결과 인식" }, "정확도 ${pct(cmp.accuracy)} (확인 전)", "확인 필요 · 알림을 눌러 수정"), highlight = false)
    }

    fun showMessage(context: Context, text: String) = show(context, listOf(text), highlight = false, seconds = 2)

    private fun show(context: Context, lines: List<String>, highlight: Boolean, seconds: Int = Settings.current.overlaySeconds) {
        val settings = Settings.current
        if (!settings.overlayEnabled || !AndroidSettings.canDrawOverlays(context)) return
        val app = context.applicationContext
        main.post {
            hide()
            val wm = app.getSystemService(WindowManager::class.java)
            val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, app.resources.displayMetrics) }
            val box = LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12f).toInt(), dp(8f).toInt(), dp(12f).toInt(), dp(8f).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = dp(12f)
                    setColor(Color.argb(230, 30, 24, 54))
                    if (highlight) setStroke(dp(2f).toInt(), Color.rgb(255, 120, 190))
                }
                lines.forEachIndexed { i, line ->
                    addView(TextView(app).apply {
                        text = line
                        setTextColor(if (i == 2 && highlight) Color.rgb(255, 150, 205) else Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (i == 1) 15f else 12f)
                        if (i == 1) setTypeface(typeface, Typeface.BOLD)
                        maxLines = 1
                    })
                }
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                alpha = 0.8f
                gravity = when (settings.overlayCorner) {
                    OverlayCorner.TOP_LEFT -> Gravity.TOP or Gravity.START
                    OverlayCorner.TOP_RIGHT -> Gravity.TOP or Gravity.END
                    OverlayCorner.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
                    OverlayCorner.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
                }
                x = dp(16f).toInt(); y = dp(16f).toInt()
                title = "SekaiTrackerOverlay"
            }
            runCatching { wm.addView(box, params); view = box }
            main.postDelayed(hideRunnable, seconds * 1000L)
        }
    }

    val isShowing get() = view != null

    fun hide() {
        main.removeCallbacks(hideRunnable)
        val v = view ?: return
        view = null
        runCatching { v.context.getSystemService(WindowManager::class.java).removeView(v) }
    }

    /** from any thread */
    fun hideAsync() = main.post { hide() }
}
