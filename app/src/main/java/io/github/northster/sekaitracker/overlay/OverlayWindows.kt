package io.github.northster.sekaitracker.overlay

import android.content.Context
import android.provider.Settings
import android.view.WindowManager
import io.github.northster.sekaitracker.capture.SekaiAccessibilityService

/** Where our floating windows go, the window manager that holds them and the context to build their views with. */
class OverlayHost(val context: Context, val windowManager: WindowManager, val type: Int)

object OverlayWindows {
    /**
     * Accessibility overlays while that service runs (no "display over other apps" permission needed, and Android treats
     * them as trusted for touches), app overlays otherwise; null when neither is allowed.
     */
    fun host(context: Context): OverlayHost? {
        SekaiAccessibilityService.instance?.let {
            return OverlayHost(it, it.getSystemService(WindowManager::class.java), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        }
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) return null
        return OverlayHost(app, app.getSystemService(WindowManager::class.java), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
    }
}
