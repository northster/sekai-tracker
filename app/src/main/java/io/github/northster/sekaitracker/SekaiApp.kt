package io.github.northster.sekaitracker

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import io.github.northster.sekaitracker.data.RecordDb
import io.github.northster.sekaitracker.data.Settings

class SekaiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        RecordDb.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_SERVICE, "화면 인식 실행 중", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_REVIEW, "기록 확인 요청", NotificationManager.IMPORTANCE_DEFAULT))
    }

    companion object {
        const val CHANNEL_SERVICE = "service"
        const val CHANNEL_REVIEW = "review"
    }
}
