package io.github.northster.sekaitracker.capture

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/** Which app is in front (optional "only while the game runs" check; needs the usage access permission). */
object ForegroundApp {
    @Volatile var lastPackage: String? = null
        private set

    fun hasPermission(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }

    /** the app that came to the front last, or the previous answer when nothing changed recently */
    fun current(context: Context): String? {
        if (!hasPermission(context)) return null
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - if (lastPackage == null) 3_600_000 else 60_000, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) lastPackage = e.packageName
        }
        return lastPackage
    }
}
