package io.github.northster.sekaitracker.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import io.github.northster.sekaitracker.data.ChartStats
import io.github.northster.sekaitracker.data.Difficulty
import io.github.northster.sekaitracker.data.OverlayCorner
import io.github.northster.sekaitracker.data.PlayRecord
import io.github.northster.sekaitracker.data.Settings
import io.github.northster.sekaitracker.data.Stats
import java.util.Locale

/**
 * Small box in a screen corner after a result was read. It can never get in the way of playing:
 * - it does not take touches (FLAG_NOT_TOUCHABLE) and its opacity stays at 0.8, the limit up to which Android 12+
 *   passes touches through windows of other apps
 * - it is only added for a few seconds after a screen was read, and removed as soon as the screen changes
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
            else -> "이전 최고 ${pct(cmp.previousBest)} · ${String.format(Locale.US, "%+.2f", (cmp.accuracy ?: 0.0) - cmp.previousBest)}%p"
        }
        val tag = when { record.allPerfect -> "ALL PERFECT"; record.fullCombo -> "FULL COMBO"; else -> null }
        show(context, listOfNotNull(head, "정확도 ${pct(cmp.accuracy)}" + (tag?.let { "  $it" } ?: ""), best),
            highlight = cmp.newBest && cmp.previousBest != null)
    }

    fun showReview(context: Context, record: PlayRecord, cmp: Stats.Comparison) {
        show(context, listOf(
            record.title.ifBlank { "결과 인식" },
            "정확도 ${pct(cmp.accuracy)} (확인 전)",
            cmp.previousBest?.let { "이전 최고 ${pct(it)}" } ?: "이전 기록 없음",
            "확인 필요 · 알림을 눌러 수정",
        ), highlight = false)
    }

    /** song select: the best of every difficulty played so far */
    fun showSongBest(context: Context, title: String, charts: List<ChartStats>) {
        val lines = listOf(title) + if (charts.isEmpty()) listOf("이 곡 기록 없음") else charts.flatMap { chartLines(it) }
        show(context, lines, highlight = false, boldLine = -1)
    }

    /** live prepare: the best of the difficulty about to be played */
    fun showChartBest(context: Context, title: String, difficulty: Difficulty, chart: ChartStats?) {
        show(context, listOf(title) + (chart?.let { chartLines(it) } ?: listOf("${difficulty.label} · 기록 없음")), highlight = false, boldLine = -1)
    }

    /** best accuracy, then the judgments of that best play */
    private fun chartLines(c: ChartStats) = listOfNotNull(
        "${c.difficulty?.label ?: "?"}${c.level?.let { " $it" } ?: ""} · 최고 ${pct(c.bestAccuracy)}" +
            (if (c.allPerfect) " AP" else if (c.fullCombo) " FC" else "") + " · ${c.plays}회",
        c.best?.judgments?.let { j -> "   P ${j.perfect} · G ${j.great} · GD ${j.good} · B ${j.bad} · M ${j.miss}" },
    )

    /** a mark next to one judgment count on the result screen, in screen pixels */
    class DeltaMark(val x: Int, val y: Int, val height: Int, val delta: Int, val higherIsBetter: Boolean)

    private val deltaViews = mutableListOf<View>()

    /** "-8" / "+3" / "±0" right of each judgment count: this play compared with the previous best play */
    fun showDeltas(context: Context, marks: List<DeltaMark>) {
        if (!Settings.current.overlayEnabled || marks.isEmpty()) return
        main.post {
            removeDeltas()
            val host = OverlayWindows.host(context) ?: return@post
            val app = host.context
            val pad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, app.resources.displayMetrics).toInt()
            marks.forEach { m ->
                val good = if (m.delta == 0) null else (m.delta > 0) == m.higherIsBetter
                val label = TextView(app).apply {
                    text = when { m.delta > 0 -> "+${m.delta}"; m.delta < 0 -> "${m.delta}"; else -> "±0" }
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, m.height * 0.62f)
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(when (good) { true -> Color.rgb(120, 235, 170); false -> Color.rgb(255, 120, 140); null -> Color.rgb(200, 200, 215) })
                    setPadding(pad * 2, 0, pad * 2, 0)
                    gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply { cornerRadius = pad * 2f; setColor(Color.argb(210, 30, 24, 54)) }
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, m.height, host.type,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    alpha = 0.8f
                    gravity = Gravity.TOP or Gravity.START
                    x = m.x; y = m.y
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    title = "SekaiTrackerDelta"
                }
                runCatching { host.windowManager.addView(label, params); deltaViews += label }
            }
        }
    }

    private fun removeDeltas() {
        deltaViews.forEach { v -> runCatching { v.context.getSystemService(WindowManager::class.java).removeView(v) } }
        deltaViews.clear()
    }

    fun showMessage(context: Context, text: String) = show(context, listOf(text), highlight = false, seconds = 2)

    private fun show(context: Context, lines: List<String>, highlight: Boolean, seconds: Int = Settings.current.overlaySeconds, boldLine: Int = 1) {
        val settings = Settings.current
        if (!settings.overlayEnabled) return
        main.post {
            removeBox()
            val host = OverlayWindows.host(context) ?: return@post
            val app = host.context
            val wm = host.windowManager
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
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (i == boldLine) 15f else 12f)
                        if (i == boldLine || i == 0) setTypeface(typeface, Typeface.BOLD)
                        maxLines = 1
                    })
                }
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                host.type,
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
            main.removeCallbacks(hideRunnable)
            main.postDelayed(hideRunnable, seconds * 1000L)
        }
    }

    val isShowing get() = view != null || deltaViews.isNotEmpty()

    fun hide() {
        main.removeCallbacks(hideRunnable)
        removeDeltas()
        removeBox()
    }

    private fun removeBox() {
        val v = view ?: return
        view = null
        runCatching { v.context.getSystemService(WindowManager::class.java).removeView(v) }
    }

    /** from any thread */
    fun hideAsync() = main.post { hide() }
}
