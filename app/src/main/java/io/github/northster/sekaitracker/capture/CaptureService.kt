package io.github.northster.sekaitracker.capture

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.service.quicksettings.TileService
import android.view.Display
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.github.northster.sekaitracker.R
import io.github.northster.sekaitracker.SekaiApp
import io.github.northster.sekaitracker.data.Settings
import io.github.northster.sekaitracker.ocr.Recognizer
import io.github.northster.sekaitracker.overlay.CaptureButton
import io.github.northster.sekaitracker.overlay.ResultOverlay
import io.github.northster.sekaitracker.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Looks at the screen through MediaProjection (screen capture only: the game's data and memory are never touched).
 *
 * Button mode (default): nothing is captured until the floating button, the quick settings tile or the notification
 * action is tapped; the screen is not mirrored in between, so waiting costs no battery.
 * Auto mode: every interval the capture surface is attached, one frame is taken, and the surface is detached again.
 * OCR only runs when [FrameGate] sees a new still picture.
 */
class CaptureService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var scope: CoroutineScope

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var width = 0
    private var height = 0
    private var surfaceAttached = false
    /** set when detaching the surface does not work on this device (no frame arrives after attaching) */
    private var keepAttached = false
    private var timeouts = 0
    private var frame: Bitmap? = null
    private var screenOn = true
    private var loopJob: Job? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn = intent.action != Intent.ACTION_SCREEN_OFF
            if (!screenOn) handler.post { detachSurface(); ScreenReader.reset() }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        // folding / unfolding switches the default display between the inner and the cover screen
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            handler.post { resizeTo(screenSize()) }
            CaptureButton.onDisplayChanged()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("capture", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        handler = Handler(thread.looper)
        scope = CoroutineScope(SupervisorJob() + handler.asCoroutineDispatcher())
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, handler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
                if (data == null || projection != null) return START_NOT_STICKY
                handler.post { start(code, data) }
            }
            ACTION_CAPTURE_NOW -> {
                if (projection == null) { stopSelf(); return START_NOT_STICKY }
                requestCapture(intent.getLongExtra(EXTRA_DELAY_MS, 0))
            }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(code: Int, data: Intent) {
        val p = runCatching { getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data) }.getOrNull()
        if (p == null) { stopSelf(); return }
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
            override fun onCapturedContentResize(width: Int, height: Int) { handler.post { resizeTo(width to height) } }
        }, handler)
        projection = p
        val (w, h) = scaled(screenSize())
        width = w; height = h
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        keepAttached = Settings.current.autoDetect && !Settings.current.pauseMirroring
        virtualDisplay = p.createVirtualDisplay("SekaiTracker", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, if (keepAttached) r.surface else null, null, handler)
        surfaceAttached = keepAttached
        runningState.value = true
        ScreenReader.status.value = "화면 공유로 실행 중"
        TileService.requestListeningState(this, ComponentName(this, CaptureTileService::class.java))
        scope.launch {
            Settings.flow.collect { s ->
                // with the accessibility service on, it owns the button
                if (s.showButton && SekaiAccessibilityService.instance == null) CaptureButton.show(this@CaptureService) { requestCapture(0) }
                else if (SekaiAccessibilityService.instance == null) CaptureButton.remove()
                // switched to button mode while running: stop mirroring
                if (!s.autoDetect && keepAttached) { keepAttached = false; detachSurface() }
            }
        }
        loopJob = scope.launch {
            while (isActive) {
                val s = Settings.current
                // button mode only checks which app is in front (to hide the button outside the game), without capturing
                delay(if (s.autoDetect) s.intervalMs else 2000)
                if (!screenOn) continue
                if (s.onlyInGame && ForegroundApp.hasPermission(this@CaptureService)) {
                    val pkg = ForegroundApp.current(this@CaptureService)
                    if (pkg != null && pkg !in s.gamePackages) {
                        CaptureButton.hide("game")
                        if (s.autoDetect) { detachSurface(); ScreenReader.reset() }
                        continue
                    }
                    CaptureButton.unhide("game")
                }
                if (s.autoDetect) ScreenReader.readIfNew(this@CaptureService) { grabFrame(800) }
            }
        }
    }

    /** read the screen once now (button, tile, notification) */
    private fun requestCapture(delayMs: Long) {
        scope.launch { ScreenReader.captureOnce(this@CaptureService, delayMs) { grabFrame(1500) } }
    }

    /** one frame as the shared reader wants it; the bitmap is reused for the next frame, so it is not recycled */
    private suspend fun grabFrame(waitMs: Long): ScreenReader.Frame? =
        grabBitmap(waitMs)?.let { ScreenReader.Frame(it, width, height, recycleAfter = false) }

    private fun screenSize(): Pair<Int, Int> {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val wm = createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            .getSystemService(WindowManager::class.java)
        val b = wm.maximumWindowMetrics.bounds
        return b.width() to b.height()
    }

    /** the capture size: the screen scaled down so the long side matches the quality setting */
    private fun scaled(size: Pair<Int, Int>): Pair<Int, Int> {
        val (w, h) = size
        val scale = minOf(1f, Settings.current.quality.longSide.toFloat() / maxOf(w, h))
        return (w * scale).roundToInt().coerceAtLeast(2) to (h * scale).roundToInt().coerceAtLeast(2)
    }

    private fun resizeTo(size: Pair<Int, Int>) {
        val vd = virtualDisplay ?: return
        val (w, h) = scaled(size)
        if (w == width && h == height) return
        width = w; height = h
        val old = reader
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        vd.resize(w, h, resources.displayMetrics.densityDpi)
        vd.surface = if (surfaceAttached) r.surface else null
        old?.close()
        frame = null
        ScreenReader.reset()
    }

    private fun detachSurface() {
        if (!surfaceAttached || keepAttached) return
        reader?.acquireLatestImage()?.close()
        virtualDisplay?.surface = null
        surfaceAttached = false
    }

    /** one frame from the screen, or null */
    private suspend fun grabBitmap(waitMs: Long): Bitmap? {
        val r = reader ?: return null
        val image = if (keepAttached) {
            // mirroring all the time: no new image means the screen did not change
            r.acquireLatestImage() ?: return frame
        } else {
            val attachedAt = System.nanoTime()
            virtualDisplay?.surface = r.surface
            surfaceAttached = true
            var img: android.media.Image? = null
            val deadline = attachedAt + waitMs * 1_000_000L
            while (img == null && System.nanoTime() < deadline) {
                img = r.acquireLatestImage()
                if (img != null && img.timestamp in 1L until attachedAt - 50_000_000L) { img.close(); img = null } // left from before
                if (img == null) delay(40)
            }
            detachSurface()
            if (img == null) {
                // some devices only send a frame when something moves; then keep mirroring instead
                if (Settings.current.autoDetect && ++timeouts >= 3) { keepAttached = true; virtualDisplay?.surface = r.surface; surfaceAttached = true }
                return frame
            }
            timeouts = 0
            img
        }
        image.use { img ->
            val plane = img.planes[0]
            val rowWidth = plane.rowStride / plane.pixelStride
            val bmp = frame?.takeIf { it.width == rowWidth && it.height == img.height }
                ?: Bitmap.createBitmap(rowWidth, img.height, Bitmap.Config.ARGB_8888)
            plane.buffer.rewind()
            bmp.copyPixelsFromBuffer(plane.buffer)
            frame = bmp
        }
        return frame
    }

    override fun onDestroy() {
        runningState.value = false
        if (SekaiAccessibilityService.instance == null) ScreenReader.status.value = "꺼짐"
        loopJob?.cancel()
        scope.cancel()
        handler.post {
            virtualDisplay?.release(); virtualDisplay = null
            reader?.close(); reader = null
            projection?.stop(); projection = null
            if (SekaiAccessibilityService.instance == null) Recognizer.release()
            thread.quitSafely()
        }
        runCatching { unregisterReceiver(screenReceiver) }
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        ResultOverlay.hideAsync()
        if (SekaiAccessibilityService.instance == null) CaptureButton.remove()
        TileService.requestListeningState(this, ComponentName(this, CaptureTileService::class.java))
        super.onDestroy()
    }

    private fun notification() = NotificationCompat.Builder(this, SekaiApp.CHANNEL_SERVICE)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("세카이 기록 인식 중")
        .setContentText(if (Settings.current.autoDetect) "결과 화면을 자동으로 찾고 있어요" else "떠 있는 버튼이나 [지금 캡처]를 누를 때만 화면을 읽어요")
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, "지금 캡처", PendingIntent.getActivity(this, 1, Intent(this, ManualCaptureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, "중지", PendingIntent.getService(this, 2, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE))
        .build()

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_CAPTURE_NOW = "capture_now"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_DELAY_MS = "delay_ms"
        private const val NOTIFICATION_ID = 1

        private val runningState = MutableStateFlow(false)
        val running: StateFlow<Boolean> get() = runningState
    }
}
