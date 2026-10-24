package io.github.northster.sekaitracker.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import io.github.northster.sekaitracker.data.Settings
import io.github.northster.sekaitracker.data.Source
import io.github.northster.sekaitracker.ocr.Recognizer
import io.github.northster.sekaitracker.ocr.ResultParser
import io.github.northster.sekaitracker.ocr.ScreenKind
import io.github.northster.sekaitracker.overlay.CaptureButton
import io.github.northster.sekaitracker.overlay.ResultOverlay
import io.github.northster.sekaitracker.ui.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What happens with one frame of the screen, for both ways of getting it (accessibility screenshot or screen share):
 * find the game picture, decide whether it is worth reading, OCR it, and save the result or show the best records.
 */
object ScreenReader {
    /** a screen picture; [width] / [height] may be smaller than the bitmap (row padding of capture buffers) */
    class Frame(val bitmap: Bitmap, val width: Int, val height: Int, val recycleAfter: Boolean)

    private val lock = Mutex()
    private val gate = FrameGate()
    /** song whose best records were shown last, so auto mode does not show them again for the same song */
    private var lastShownSong: String? = null

    /** last thing that was seen, shown in the app */
    val status = MutableStateFlow("꺼짐")

    fun reset() { gate.reset(); lastShownSong = null }

    /** button / tile / notification: hide our own windows, take one frame and read it */
    suspend fun captureOnce(context: Context, delayMs: Long, grab: suspend () -> Frame?) {
        ResultOverlay.hideAsync()
        CaptureButton.hide("capture")
        delay(maxOf(delayMs, 150))
        try {
            read(context, force = true, grab = grab) { CaptureButton.unhide("capture") }
        } finally {
            CaptureButton.unhide("capture")
        }
    }

    /** auto mode: read the frame only when it is a new still picture */
    suspend fun readIfNew(context: Context, grab: suspend () -> Frame?) = read(context, force = false, grab = grab) {}

    private suspend fun read(context: Context, force: Boolean, grab: suspend () -> Frame?, afterGrab: () -> Unit) = lock.withLock {
        val frame = runCatching { grab() }.getOrNull()
        afterGrab()
        if (frame == null) {
            if (force) ResultOverlay.showMessage(context, "화면을 가져오지 못했어요")
            return@withLock
        }
        try {
            readFrame(context, frame, force)
        } finally {
            if (frame.recycleAfter) frame.bitmap.recycle()
        }
    }

    private suspend fun readFrame(context: Context, frame: Frame, force: Boolean) {
        val bmp = frame.bitmap
        val content = contentRect(bmp, frame.width.coerceAtMost(bmp.width), frame.height.coerceAtMost(bmp.height))
        val thumb = thumbnail(bmp, content)
        val decision = gate.offer(thumb)
        if (gate.leftCheckedScreen && ResultOverlay.isShowing) ResultOverlay.hideAsync()
        if (!force && decision == FrameGate.Decision.SKIP) return
        if (force) gate.markChecked(thumb)

        val crop = Bitmap.createBitmap(bmp, content.left, content.top, content.width(), content.height())
        try {
            val boxes = runCatching { Recognizer.read(crop, Settings.current.ocrLanguage) }.getOrElse {
                status.value = "${time()} 글자 인식 실패: ${it.message}"
                return
            }
            when (ResultParser.classify(boxes)) {
                ScreenKind.RESULT -> {
                    val parsed = ResultParser.parseResult(boxes, crop.height)
                    val outcome = ResultPipeline.handle(context, parsed, crop, if (force) Source.BUTTON else Source.AUTO)
                    status.value = "${time()} 결과 화면: " + when (outcome) {
                        is ResultPipeline.Outcome.Saved -> "저장함 (${outcome.record.title})"
                        is ResultPipeline.Outcome.NeedsReview -> "확인 필요 (${outcome.record.title.ifBlank { "?" }})"
                        is ResultPipeline.Outcome.Duplicate -> "이미 저장됨"
                    }
                }
                ScreenKind.SONG_SELECT -> {
                    val title = ResultParser.parseSongSelect(boxes, crop.height)?.let { ResultPipeline.onSongSelect(it) }
                    status.value = "${time()} 곡 선택 화면" + (title?.let { ": $it" } ?: "")
                    if (title == null) {
                        if (force) ResultOverlay.showMessage(context, "곡 이름을 읽지 못했어요")
                    } else if (force || title != lastShownSong) {
                        lastShownSong = title
                        ResultOverlay.showSongBest(context, title, ResultPipeline.charts(title))
                    }
                }
                ScreenKind.LIVE_PREPARE -> {
                    val difficulty = ResultParser.parseLivePrepare(boxes)
                    val title = ResultPipeline.selectedSong()
                    status.value = "${time()} 라이브 준비 화면"
                    if (difficulty != null && title != null) {
                        ResultOverlay.showChartBest(context, title, difficulty, ResultPipeline.charts(title).firstOrNull { it.difficulty == difficulty })
                    } else if (force) {
                        ResultOverlay.showMessage(context, "곡 선택 화면에서 먼저 한 번 눌러 주세요")
                    }
                }
                ScreenKind.OTHER -> {
                    status.value = "${time()} 결과 화면 아님"
                    if (force) ResultOverlay.showMessage(context, "결과·곡 선택 화면이 아니에요")
                }
            }
        } finally {
            crop.recycle()
        }
    }

    /** read the screen now with whatever is running: the accessibility service, the screen share, or open the app */
    fun requestNow(context: Context, delayMs: Long) {
        val accessibility = SekaiAccessibilityService.instance
        when {
            accessibility != null -> accessibility.captureNow(delayMs)
            CaptureService.running.value -> context.startService(Intent(context, CaptureService::class.java)
                .setAction(CaptureService.ACTION_CAPTURE_NOW).putExtra(CaptureService.EXTRA_DELAY_MS, delayMs))
            else -> context.startActivity(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_START_CAPTURE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** true when something can take a screen picture now */
    val ready get() = SekaiAccessibilityService.instance != null || CaptureService.running.value

    internal fun time(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    /** area without black letterbox bars, so positions are relative to the game picture */
    fun contentRect(bmp: Bitmap, w: Int, h: Int): Rect {
        fun dark(x: Int, y: Int): Boolean {
            val c = bmp.getPixel(x, y)
            return Color.red(c) < 12 && Color.green(c) < 12 && Color.blue(c) < 12
        }
        fun rowDark(y: Int) = (0 until 24).all { dark((it * (w - 1)) / 23, y) }
        fun colDark(x: Int) = (0 until 24).all { dark(x, (it * (h - 1)) / 23) }
        var top = 0; while (top < h / 3 && rowDark(top)) top += 2
        var bottom = h - 1; while (bottom > h * 2 / 3 && rowDark(bottom)) bottom -= 2
        var left = 0; while (left < w / 3 && colDark(left)) left += 2
        var right = w - 1; while (right > w * 2 / 3 && colDark(right)) right -= 2
        return Rect(left, top, right + 1, bottom + 1)
    }

    /** gray 32x20 of the left middle of the picture, where the result numbers are */
    fun thumbnail(bmp: Bitmap, r: Rect): IntArray {
        val out = IntArray(FrameGate.THUMB_W * FrameGate.THUMB_H)
        for (ty in 0 until FrameGate.THUMB_H) for (tx in 0 until FrameGate.THUMB_W) {
            val x = r.left + ((0.02f + 0.53f * tx / (FrameGate.THUMB_W - 1)) * r.width()).toInt()
            val y = r.top + ((0.25f + 0.6f * ty / (FrameGate.THUMB_H - 1)) * r.height()).toInt()
            val c = bmp.getPixel(x.coerceIn(0, bmp.width - 1), y.coerceIn(0, bmp.height - 1))
            out[ty * FrameGate.THUMB_W + tx] = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        }
        return out
    }
}
