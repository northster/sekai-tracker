package io.github.northster.sekaitracker.capture

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick settings tile: reads the screen now. The tile opens [ManualCaptureActivity], which closes the notification shade
 * so the capture shows the game and not the shade.
 */
class CaptureTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = if (ScreenReader.ready) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = if (ScreenReader.ready) "눌러서 캡처" else "꺼짐 · 눌러서 시작"
            updateTile()
        }
    }

    override fun onClick() {
        val intent = Intent(this, ManualCaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

/** Invisible: asks for a capture once the shade is gone, or opens the app when nothing can capture. */
class ManualCaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScreenReader.requestNow(this, 900)
        finish()
    }
}
