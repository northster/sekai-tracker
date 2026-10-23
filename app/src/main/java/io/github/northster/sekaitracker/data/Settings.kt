package io.github.northster.sekaitracker.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ReviewMode(val label: String) {
    WHEN_UNSURE("애매할 때만 확인"), ALWAYS("항상 확인 후 저장"), NEVER("확인 없이 저장")
}

enum class OverlayCorner(val label: String) {
    TOP_LEFT("왼쪽 위"), TOP_RIGHT("오른쪽 위"), BOTTOM_LEFT("왼쪽 아래"), BOTTOM_RIGHT("오른쪽 아래")
}

enum class OcrLanguage(val label: String) { JAPANESE("일본어 (일본 서버)"), KOREAN("한국어 (한국 서버)") }

/** capture resolution: longest side of the frame given to OCR */
enum class CaptureQuality(val label: String, val longSide: Int) {
    LOW("낮음 (배터리 우선)", 1280), NORMAL("보통", 1600), HIGH("높음 (인식 우선)", 2200)
}

data class AppSettings(
    val weights: Weights = Weights(),
    val autoDetect: Boolean = true,
    val intervalMs: Long = 1000,
    val quality: CaptureQuality = CaptureQuality.NORMAL,
    /** detach the capture surface between checks so the screen is not mirrored all the time */
    val pauseMirroring: Boolean = true,
    val onlyInGame: Boolean = false,
    val gamePackages: List<String> = DEFAULT_GAME_PACKAGES,
    val overlayEnabled: Boolean = true,
    val overlayCorner: OverlayCorner = OverlayCorner.BOTTOM_LEFT,
    val overlaySeconds: Int = 5,
    val reviewMode: ReviewMode = ReviewMode.WHEN_UNSURE,
    val reviewNewSongs: Boolean = true,
    val ocrLanguage: OcrLanguage = OcrLanguage.JAPANESE,
    val keepImages: Boolean = false,
) {
    companion object {
        /** Japanese, global, Korean and Taiwanese releases; editable in the settings */
        val DEFAULT_GAME_PACKAGES = listOf("com.sega.pjsekai", "com.sega.ColorfulStage.en", "com.pjsekai.kr", "com.hermes.mk.asia")
    }
}

object Settings {
    private lateinit var prefs: SharedPreferences
    private val state = MutableStateFlow(AppSettings())
    val flow: StateFlow<AppSettings> get() = state
    val current get() = state.value

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        state.value = load()
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        fun dbl(key: String, def: Double) = prefs.getFloat(key, def.toFloat()).toDouble()
        return AppSettings(
            weights = Weights(dbl("w_perfect", d.weights.perfect), dbl("w_great", d.weights.great), dbl("w_good", d.weights.good),
                dbl("w_bad", d.weights.bad), dbl("w_miss", d.weights.miss)),
            autoDetect = prefs.getBoolean("auto_detect", d.autoDetect),
            intervalMs = prefs.getLong("interval_ms", d.intervalMs),
            quality = enumOr(prefs.getString("quality", null), d.quality),
            pauseMirroring = prefs.getBoolean("pause_mirroring", d.pauseMirroring),
            onlyInGame = prefs.getBoolean("only_in_game", d.onlyInGame),
            gamePackages = prefs.getString("game_packages", null)?.split(',', '\n')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: d.gamePackages,
            overlayEnabled = prefs.getBoolean("overlay", d.overlayEnabled),
            overlayCorner = enumOr(prefs.getString("overlay_corner", null), d.overlayCorner),
            overlaySeconds = prefs.getInt("overlay_seconds", d.overlaySeconds),
            reviewMode = enumOr(prefs.getString("review_mode", null), d.reviewMode),
            reviewNewSongs = prefs.getBoolean("review_new_songs", d.reviewNewSongs),
            ocrLanguage = enumOr(prefs.getString("ocr_language", null), d.ocrLanguage),
            keepImages = prefs.getBoolean("keep_images", d.keepImages),
        )
    }

    fun update(change: (AppSettings) -> AppSettings) {
        val s = change(state.value)
        prefs.edit {
            putFloat("w_perfect", s.weights.perfect.toFloat()); putFloat("w_great", s.weights.great.toFloat())
            putFloat("w_good", s.weights.good.toFloat()); putFloat("w_bad", s.weights.bad.toFloat()); putFloat("w_miss", s.weights.miss.toFloat())
            putBoolean("auto_detect", s.autoDetect); putLong("interval_ms", s.intervalMs); putString("quality", s.quality.name)
            putBoolean("pause_mirroring", s.pauseMirroring); putBoolean("only_in_game", s.onlyInGame)
            putString("game_packages", s.gamePackages.joinToString(","))
            putBoolean("overlay", s.overlayEnabled); putString("overlay_corner", s.overlayCorner.name); putInt("overlay_seconds", s.overlaySeconds)
            putString("review_mode", s.reviewMode.name); putBoolean("review_new_songs", s.reviewNewSongs)
            putString("ocr_language", s.ocrLanguage.name); putBoolean("keep_images", s.keepImages)
        }
        state.value = s
    }

    /** every stored setting, for the backup file */
    fun raw(): Map<String, Any?> = prefs.all

    /** settings from a backup file; values of the wrong type are ignored */
    fun restore(values: Map<String, Any?>) {
        prefs.edit {
            values.forEach { (k, v) ->
                when (v) {
                    is Boolean -> putBoolean(k, v)
                    is Int -> putInt(k, v)
                    is Long -> putLong(k, v)
                    is Float -> putFloat(k, v)
                    is String -> putString(k, v)
                }
            }
        }
        state.value = load()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, def: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: def
}
