package io.github.northster.sekaitracker.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import io.github.northster.sekaitracker.data.Settings
import io.github.northster.sekaitracker.ocr.Recognizer
import io.github.northster.sekaitracker.overlay.CaptureButton
import io.github.northster.sekaitracker.overlay.ResultOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Starts and stops with the game: it only listens for "which window is in front" events from the system, and when
 * Project SEKAI comes to the front it shows the capture button (or starts auto mode); when the game is closed or left,
 * everything stops again. Screen pictures are taken with the accessibility screenshot, so there is no screen share
 * prompt and nothing is mirrored. It never reads or changes anything inside the game, it only takes screenshots.
 */
class SekaiAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var checkJob: Job? = null
    private var autoJob: Job? = null
    /** package of the last window that came to the front, used when window contents can not be read */
    private var lastFrontPackage: String? = null
    private var lastStatus: String? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        // folding / unfolding: keep the button on screen
        override fun onDisplayChanged(displayId: Int) { if (displayId == Display.DEFAULT_DISPLAY) CaptureButton.onDisplayChanged() }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        connectedState.value = true
        ScreenReader.status.value = "게임을 기다리는 중"
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, null)
        scope.launch { Settings.flow.collect { apply() } }
        checkFront()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (pkg != null && pkg != packageName && pkg != "com.android.systemui") {
                lastFrontPackage = pkg
                if (recentState.value.firstOrNull() != pkg) recentState.value = (listOf(pkg) + recentState.value.filter { it != pkg }).take(6)
            }
        }
        // events come in bursts while apps switch; look once things settled
        checkJob?.cancel()
        checkJob = scope.launch { delay(400); checkFront() }
    }

    override fun onInterrupt() {}

    /** is a game window on screen? (also in split screen / pop-up view) */
    private fun checkFront() {
        val games = Settings.current.gamePackages
        val appWindows = runCatching { windows }.getOrDefault(emptyList())
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val packages = appWindows.mapNotNull { w -> runCatching { w.root?.packageName?.toString() }.getOrNull() }
        val inGame = if (packages.isNotEmpty()) packages.any { it in games } else lastFrontPackage in games
        if (inGame != gameState.value) {
            gameState.value = inGame
            if (!inGame) ScreenReader.reset()
        }
        apply()
    }

    /** show / hide the button and start / stop auto mode for the current state and settings */
    private fun apply() {
        val s = Settings.current
        val active = gameState.value && s.autoStart
        val base = when {
            !s.autoStart -> "자동 실행 꺼짐"
            !gameState.value -> "게임을 기다리는 중"
            s.autoDetect -> "게임 실행 중 · 자동으로 확인"
            else -> "게임 실행 중 · 버튼을 누르면 읽어요"
        }
        // keep showing what was last read while the game stays in front
        if (!active || ScreenReader.status.value == lastStatus || lastStatus == null) ScreenReader.status.value = base
        lastStatus = base
        if (active && s.showButton) CaptureButton.show(this) { captureNow(0) } else CaptureButton.remove()
        if (active && s.autoDetect) startAuto() else stopAuto()
        if (!active) ResultOverlay.hideAsync()
    }

    private fun startAuto() {
        if (autoJob?.isActive == true) return
        autoJob = scope.launch(Dispatchers.Default) {
            val power = getSystemService(PowerManager::class.java)
            while (isActive) {
                delay(Settings.current.intervalMs)
                if (!power.isInteractive) continue
                ScreenReader.readIfNew(this@SekaiAccessibilityService) { screenshot() }
            }
        }
    }

    private fun stopAuto() { autoJob?.cancel(); autoJob = null }

    /** button, tile or notification: read the screen once */
    fun captureNow(delayMs: Long) {
        scope.launch(Dispatchers.Default) { ScreenReader.captureOnce(this@SekaiAccessibilityService, delayMs) { screenshot() } }
    }

    /** a screenshot scaled to the capture quality, as a software bitmap for OCR */
    private suspend fun screenshot(): ScreenReader.Frame? = suspendCancellableCoroutine { cont ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val frame = runCatching {
                    val buffer = result.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    buffer.close()
                    soft?.let { scaled(it) }
                }.getOrNull()
                if (cont.isActive) cont.resume(frame?.let { ScreenReader.Frame(it, it.width, it.height, recycleAfter = true) })
            }

            override fun onFailure(errorCode: Int) {
                if (cont.isActive) cont.resume(null)
            }
        })
    }

    private fun scaled(bmp: Bitmap): Bitmap {
        val scale = minOf(1f, Settings.current.quality.longSide.toFloat() / maxOf(bmp.width, bmp.height))
        if (scale >= 1f) return bmp
        val out = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).roundToInt(), (bmp.height * scale).roundToInt(), true)
        bmp.recycle()
        return out
    }

    override fun onDestroy() {
        instance = null
        connectedState.value = false
        gameState.value = false
        scope.cancel()
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        CaptureButton.remove()
        ResultOverlay.hideAsync()
        if (!CaptureService.running.value) { Recognizer.release(); ScreenReader.status.value = "꺼짐" }
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: SekaiAccessibilityService? = null
            private set
        private val connectedState = MutableStateFlow(false)
        private val gameState = MutableStateFlow(false)
        /** the accessibility service is turned on in the system settings */
        val connected: StateFlow<Boolean> get() = connectedState
        /** the game is on screen */
        val gameActive: StateFlow<Boolean> get() = gameState
        private val recentState = MutableStateFlow<List<String>>(emptyList())
        /** apps that came to the front lately, newest first: to add the game's package name in the settings */
        val recentPackages: StateFlow<List<String>> get() = recentState
    }
}
