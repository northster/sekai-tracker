package io.github.northster.sekaitracker.capture

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.northster.sekaitracker.R
import io.github.northster.sekaitracker.SekaiApp
import io.github.northster.sekaitracker.data.Judgments
import io.github.northster.sekaitracker.data.PlayRecord
import io.github.northster.sekaitracker.data.RecordDb
import io.github.northster.sekaitracker.data.ReviewMode
import io.github.northster.sekaitracker.data.Settings
import io.github.northster.sekaitracker.data.Source
import io.github.northster.sekaitracker.data.Stats
import io.github.northster.sekaitracker.ocr.ParsedResult
import io.github.northster.sekaitracker.ocr.ParsedSongSelect
import io.github.northster.sekaitracker.ocr.TitleMatcher
import io.github.northster.sekaitracker.overlay.ResultOverlay
import io.github.northster.sekaitracker.ui.MainActivity
import java.io.File
import java.io.FileOutputStream

/** What happens with a recognized result screen: title matching, duplicate check, saving or asking, the overlay. */
object ResultPipeline {
    sealed class Outcome {
        data class Saved(val record: PlayRecord, val comparison: Stats.Comparison) : Outcome()
        data class NeedsReview(val record: PlayRecord) : Outcome()
        data class Duplicate(val record: PlayRecord) : Outcome()
    }

    /** the song selected last on the song select screen, the likely one on the next result screen */
    @Volatile private var lastSelected: Pair<String, Long>? = null
    private const val SELECTION_VALID_MS = 20 * 60_000L

    fun onSongSelect(parsed: ParsedSongSelect) {
        if (parsed.title.isBlank()) return
        val match = TitleMatcher.match(parsed.title, RecordDb.titles(), RecordDb.aliases(), threshold = 0.85)
        val title = if (match.known) match.title else parsed.title
        if (parsed.levels.size >= 4) RecordDb.saveSong(title, parsed.levels)
        lastSelected = title to System.currentTimeMillis()
    }

    /** builds the record from OCR, without saving it */
    fun toRecord(parsed: ParsedResult, source: Source, now: Long = System.currentTimeMillis()): PlayRecord {
        val selected = lastSelected?.takeIf { now - it.second < SELECTION_VALID_MS }?.first
        val match = TitleMatcher.match(parsed.title, RecordDb.titles(), RecordDb.aliases(), selected)
        val issues = parsed.issues.toMutableList()
        if (!match.known && match.title.isNotBlank() && Settings.current.reviewNewSongs) issues += "처음 보는 곡 이름: 맞는지 확인"
        val level = parsed.level ?: parsed.difficulty?.let { RecordDb.songLevels(match.title)[it] }
        return PlayRecord(
            playedAt = now,
            title = match.title,
            difficulty = parsed.difficulty,
            level = level,
            score = parsed.score,
            highScore = parsed.highScore,
            combo = parsed.combo,
            judgments = parsed.judgments ?: Judgments(parsed.perfect ?: 0, parsed.great ?: 0, parsed.good ?: 0, parsed.bad ?: 0, parsed.miss ?: 0),
            fast = parsed.fast,
            late = parsed.late,
            ocrTitle = parsed.title,
            source = source,
            issues = (issues + parsed.notes.map { "참고: $it" }).joinToString("\n"),
        ).let { it.copy(pending = issues.isNotEmpty()) }
    }

    fun handle(context: Context, parsed: ParsedResult, frame: Bitmap?, source: Source): Outcome {
        val settings = Settings.current
        var record = toRecord(parsed, source)
        if (RecordDb.isDuplicate(record)) {
            if (source != Source.AUTO) ResultOverlay.showMessage(context, "이미 저장된 결과예요")
            return Outcome.Duplicate(record)
        }
        val unsure = record.pending || parsed.judgments == null
        val review = source == Source.IMPORT || when (settings.reviewMode) {
            ReviewMode.ALWAYS -> true
            ReviewMode.WHEN_UNSURE -> unsure
            ReviewMode.NEVER -> parsed.judgments == null || record.title.isBlank()
        }
        if (review || settings.keepImages) record = record.copy(imagePath = frame?.let { saveImage(context, it, record.playedAt) })

        if (review) {
            val id = RecordDb.insert(record.copy(pending = true))
            record = record.copy(id = id, pending = true)
            if (source != Source.IMPORT) {
                notifyReview(context, record)
                ResultOverlay.showReview(context, record, Stats.compare(record, RecordDb.forChart(record.title, record.difficulty), settings.weights))
            }
            return Outcome.NeedsReview(record)
        }
        val earlier = RecordDb.forChart(record.title, record.difficulty)
        val id = RecordDb.insert(record.copy(pending = false))
        record = record.copy(id = id, pending = false)
        val comparison = Stats.compare(record, earlier, settings.weights)
        ResultOverlay.showResult(context, record, comparison)
        return Outcome.Saved(record, comparison)
    }

    /** the user checked a pending record in the review screen */
    fun confirm(record: PlayRecord) {
        if (record.ocrTitle.isNotBlank() && record.title.isNotBlank()) RecordDb.addAlias(record.ocrTitle, record.title)
        val keep = Settings.current.keepImages
        if (!keep) record.imagePath?.let { File(it).delete() }
        RecordDb.update(record.copy(pending = false, issues = "", imagePath = if (keep) record.imagePath else null))
    }

    private fun saveImage(context: Context, bitmap: Bitmap, time: Long): String? = runCatching {
        val dir = File(context.filesDir, "captures").apply { mkdirs() }
        val scale = minOf(1f, 1400f / maxOf(bitmap.width, bitmap.height))
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        val file = File(dir, "$time.jpg")
        FileOutputStream(file).use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (small !== bitmap) small.recycle()
        file.absolutePath
    }.getOrNull()

    private fun notifyReview(context: Context, record: PlayRecord) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val intent = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_REVIEW_ID, record.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(context, record.id.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, SekaiApp.CHANNEL_REVIEW)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("기록 확인 필요: ${record.title.ifBlank { "곡 이름 없음" }}")
            .setContentText(record.issues.lineSequence().firstOrNull { !it.startsWith("참고") } ?: "인식 결과를 확인해 주세요")
            .setStyle(NotificationCompat.BigTextStyle().bigText(record.issues))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(REVIEW_NOTIFICATION_BASE + record.id.toInt(), n)
    }

    const val REVIEW_NOTIFICATION_BASE = 1000
}
