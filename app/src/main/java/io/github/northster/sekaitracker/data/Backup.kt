package io.github.northster.sekaitracker.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One JSON file with everything the app keeps: play records (pending ones too), songs learned from the song select
 * screen, title corrections and settings. Captured images are left out. Restoring adds to what is already there.
 */
object Backup {
    private const val FORMAT = "sekai-tracker-backup"
    private const val VERSION = 1

    fun export(): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("records", JSONArray().apply { RecordDb.all().forEach { put(recordJson(it)) } })
        root.put("songs", JSONObject().apply { RecordDb.songsRaw().forEach { (k, v) -> put(k, v) } })
        root.put("aliases", JSONObject().apply { RecordDb.aliases().forEach { (k, v) -> put(k, v) } })
        root.put("settings", JSONObject().apply {
            Settings.raw().forEach { (k, v) ->
                val type = when (v) { is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is String -> "s"; else -> null }
                if (type != null) put(k, JSONObject().put("t", type).put("v", v))
            }
        })
        return root.toString(1)
    }

    data class Result(val added: Int, val skipped: Int, val songs: Int, val settingsRestored: Boolean)

    /** throws IllegalArgumentException with a message for the user when the file is not a backup of this app */
    fun restore(json: String, withSettings: Boolean): Result {
        val root = runCatching { JSONObject(json) }.getOrElse { throw IllegalArgumentException("백업 파일이 아니에요") }
        if (root.optString("format") != FORMAT) throw IllegalArgumentException("세카이 기록 백업 파일이 아니에요")
        if (root.optInt("version") > VERSION) throw IllegalArgumentException("더 새 버전 앱에서 만든 백업이에요. 앱을 업데이트해 주세요")

        val records = root.optJSONArray("records")?.let { arr -> (0 until arr.length()).mapNotNull { runCatching { record(arr.getJSONObject(it)) }.getOrNull() } }.orEmpty()
        val songs = root.optJSONObject("songs")?.let { o -> o.keys().asSequence().associateWith { o.getString(it) } }.orEmpty()
        val aliases = root.optJSONObject("aliases")?.let { o -> o.keys().asSequence().associateWith { o.getString(it) } }.orEmpty()
        val count = RecordDb.restore(records, songs, aliases)

        val settings = root.optJSONObject("settings")
        if (withSettings && settings != null) {
            Settings.restore(settings.keys().asSequence().mapNotNull { k ->
                val e = settings.optJSONObject(k) ?: return@mapNotNull null
                val v: Any? = when (e.optString("t")) {
                    "b" -> e.optBoolean("v")
                    "i" -> e.optInt("v")
                    "l" -> e.optLong("v")
                    "f" -> e.optDouble("v").toFloat()
                    "s" -> e.optString("v")
                    else -> null
                }
                v?.let { k to it }
            }.toMap())
        }
        return Result(count.added, count.skipped, songs.size, withSettings && settings != null)
    }

    private fun recordJson(r: PlayRecord) = JSONObject().apply {
        put("playedAt", r.playedAt); put("title", r.title); put("difficulty", r.difficulty?.name ?: JSONObject.NULL)
        put("level", r.level ?: JSONObject.NULL); put("score", r.score ?: JSONObject.NULL); put("highScore", r.highScore ?: JSONObject.NULL)
        put("combo", r.combo ?: JSONObject.NULL)
        put("perfect", r.judgments.perfect); put("great", r.judgments.great); put("good", r.judgments.good)
        put("bad", r.judgments.bad); put("miss", r.judgments.miss)
        put("fast", r.fast ?: JSONObject.NULL); put("late", r.late ?: JSONObject.NULL)
        put("ocrTitle", r.ocrTitle); put("source", r.source.name); put("pending", r.pending); put("issues", r.issues)
    }

    private fun JSONObject.intOrNull(k: String) = if (isNull(k)) null else optInt(k)
    private fun JSONObject.longOrNull(k: String) = if (isNull(k)) null else optLong(k)

    private fun record(o: JSONObject) = PlayRecord(
        playedAt = o.getLong("playedAt"),
        title = o.getString("title"),
        difficulty = Difficulty.of(o.optString("difficulty")),
        level = o.intOrNull("level"),
        score = o.longOrNull("score"),
        highScore = o.longOrNull("highScore"),
        combo = o.intOrNull("combo"),
        judgments = Judgments(o.getInt("perfect"), o.getInt("great"), o.getInt("good"), o.getInt("bad"), o.getInt("miss")),
        fast = o.intOrNull("fast"),
        late = o.intOrNull("late"),
        ocrTitle = o.optString("ocrTitle"),
        source = Source.entries.firstOrNull { it.name == o.optString("source") } ?: Source.MANUAL,
        pending = o.optBoolean("pending"),
        issues = o.optString("issues"),
    )
}
