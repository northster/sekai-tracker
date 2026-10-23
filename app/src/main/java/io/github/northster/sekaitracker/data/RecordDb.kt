package io.github.northster.sekaitracker.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.northster.sekaitracker.ocr.TextNorm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Play records, the song list learned from the song select screen, and title corrections.
 * Plain SQLite: the data is small, and [changes] tells the UI to read again after every write.
 */
object RecordDb {
    private lateinit var helper: Helper
    private val changeCounter = MutableStateFlow(0)
    val changes: StateFlow<Int> get() = changeCounter

    fun init(context: Context) { helper = Helper(context.applicationContext) }

    private class Helper(context: Context) : SQLiteOpenHelper(context, "records.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE records (
                id INTEGER PRIMARY KEY AUTOINCREMENT, played_at INTEGER NOT NULL, title TEXT NOT NULL, difficulty TEXT,
                level INTEGER, score INTEGER, high_score INTEGER, combo INTEGER,
                perfect INTEGER NOT NULL, great INTEGER NOT NULL, good INTEGER NOT NULL, bad INTEGER NOT NULL, miss INTEGER NOT NULL,
                fast INTEGER, late INTEGER, ocr_title TEXT, source TEXT, pending INTEGER NOT NULL DEFAULT 0,
                issues TEXT, image_path TEXT, signature TEXT)""")
            db.execSQL("CREATE INDEX records_chart ON records(title, difficulty)")
            db.execSQL("CREATE TABLE songs (title TEXT PRIMARY KEY, levels TEXT, updated_at INTEGER)")
            db.execSQL("CREATE TABLE aliases (ocr_key TEXT PRIMARY KEY, title TEXT NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    private fun notifyChanged() { changeCounter.value++ }

    private fun PlayRecord.values() = ContentValues().apply {
        put("played_at", playedAt); put("title", title); put("difficulty", difficulty?.name); put("level", level)
        put("score", score); put("high_score", highScore); put("combo", combo)
        put("perfect", judgments.perfect); put("great", judgments.great); put("good", judgments.good)
        put("bad", judgments.bad); put("miss", judgments.miss); put("fast", fast); put("late", late)
        put("ocr_title", ocrTitle); put("source", source.name); put("pending", if (pending) 1 else 0)
        put("issues", issues); put("image_path", imagePath); put("signature", signature)
    }

    private fun Cursor.intOrNull(col: String) = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getInt(it) }
    private fun Cursor.longOrNull(col: String) = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.str(col: String) = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }

    private fun Cursor.toRecord() = PlayRecord(
        id = getLong(getColumnIndexOrThrow("id")),
        playedAt = getLong(getColumnIndexOrThrow("played_at")),
        title = str("title").orEmpty(),
        difficulty = Difficulty.of(str("difficulty")),
        level = intOrNull("level"), score = longOrNull("score"), highScore = longOrNull("high_score"), combo = intOrNull("combo"),
        judgments = Judgments(intOrNull("perfect") ?: 0, intOrNull("great") ?: 0, intOrNull("good") ?: 0, intOrNull("bad") ?: 0, intOrNull("miss") ?: 0),
        fast = intOrNull("fast"), late = intOrNull("late"),
        ocrTitle = str("ocr_title").orEmpty(),
        source = Source.entries.firstOrNull { it.name == str("source") } ?: Source.AUTO,
        pending = intOrNull("pending") == 1,
        issues = str("issues").orEmpty(),
        imagePath = str("image_path"),
    )

    private fun query(where: String? = null, args: Array<String>? = null, order: String = "played_at DESC"): List<PlayRecord> =
        helper.readableDatabase.query("records", null, where, args, null, null, order).use { c ->
            buildList { while (c.moveToNext()) add(c.toRecord()) }
        }

    fun all(): List<PlayRecord> = query()
    fun saved(): List<PlayRecord> = query("pending = 0")
    fun pending(): List<PlayRecord> = query("pending = 1")
    fun get(id: Long): PlayRecord? = query("id = ?", arrayOf(id.toString())).firstOrNull()

    fun forChart(title: String, difficulty: Difficulty?): List<PlayRecord> =
        if (difficulty == null) query("title = ? AND difficulty IS NULL AND pending = 0", arrayOf(title), "played_at ASC")
        else query("title = ? AND difficulty = ? AND pending = 0", arrayOf(title, difficulty.name), "played_at ASC")

    /** a play with the same numbers saved (or waiting) shortly before is the same result screen seen again */
    fun isDuplicate(record: PlayRecord, withinMs: Long = 30 * 60_000L): Boolean =
        helper.readableDatabase.rawQuery(
            "SELECT 1 FROM records WHERE signature = ? AND played_at > ? LIMIT 1",
            arrayOf(record.signature, (record.playedAt - withinMs).toString()),
        ).use { it.moveToFirst() }

    fun insert(record: PlayRecord): Long = helper.writableDatabase.insert("records", null, record.values()).also { notifyChanged() }

    fun update(record: PlayRecord) {
        helper.writableDatabase.update("records", record.values(), "id = ?", arrayOf(record.id.toString()))
        notifyChanged()
    }

    fun delete(record: PlayRecord) {
        helper.writableDatabase.delete("records", "id = ?", arrayOf(record.id.toString()))
        record.imagePath?.let { File(it).delete() }
        notifyChanged()
    }

    fun titles(): Set<String> {
        val db = helper.readableDatabase
        val out = HashSet<String>()
        db.rawQuery("SELECT DISTINCT title FROM records WHERE pending = 0", null).use { while (it.moveToNext()) out += it.getString(0) }
        db.rawQuery("SELECT title FROM songs", null).use { while (it.moveToNext()) out += it.getString(0) }
        return out.filter { it.isNotBlank() }.toSet()
    }

    /** songs seen on the song select screen, with their levels per difficulty */
    fun saveSong(title: String, levels: Map<Difficulty, Int>) {
        if (TextNorm.titleKey(title).isEmpty()) return
        helper.writableDatabase.insertWithOnConflict("songs", null, ContentValues().apply {
            put("title", title)
            put("levels", levels.entries.joinToString(";") { "${it.key.name}=${it.value}" })
            put("updated_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun songLevels(title: String): Map<Difficulty, Int> =
        helper.readableDatabase.rawQuery("SELECT levels FROM songs WHERE title = ?", arrayOf(title)).use { c ->
            if (!c.moveToFirst()) return emptyMap()
            c.getString(0).orEmpty().split(';').mapNotNull { e ->
                val (k, v) = e.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
                Difficulty.of(k)?.let { d -> v.toIntOrNull()?.let { d to it } }
            }.toMap()
        }

    fun songCount(): Int = helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM songs", null).use { it.moveToFirst(); it.getInt(0) }

    fun clearSongs() { helper.writableDatabase.delete("songs", null, null); notifyChanged() }

    fun aliases(): Map<String, String> = helper.readableDatabase.rawQuery("SELECT ocr_key, title FROM aliases", null).use { c ->
        buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
    }

    /** remember that OCR text [ocrTitle] means [title], so the next recognition fixes itself */
    fun addAlias(ocrTitle: String, title: String) {
        val key = TextNorm.titleKey(ocrTitle)
        if (key.isEmpty() || key == TextNorm.titleKey(title)) return
        helper.writableDatabase.insertWithOnConflict("aliases", null, ContentValues().apply {
            put("ocr_key", key); put("title", title)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** rename a song in all records (fixing an OCR mistake after the fact) */
    fun renameChart(oldTitle: String, difficulty: Difficulty?, newTitle: String) {
        helper.writableDatabase.update("records", ContentValues().apply { put("title", newTitle) },
            if (difficulty == null) "title = ?" else "title = ? AND difficulty = ?",
            if (difficulty == null) arrayOf(oldTitle) else arrayOf(oldTitle, difficulty.name))
        addAlias(oldTitle, newTitle)
        notifyChanged()
    }
}
