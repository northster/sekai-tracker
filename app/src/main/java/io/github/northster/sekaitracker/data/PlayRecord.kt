package io.github.northster.sekaitracker.data

enum class Difficulty(val label: String) {
    EASY("EASY"), NORMAL("NORMAL"), HARD("HARD"), EXPERT("EXPERT"), MASTER("MASTER"), APPEND("APPEND");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name }
    }
}

data class Judgments(val perfect: Int, val great: Int, val good: Int, val bad: Int, val miss: Int) {
    val total get() = perfect + great + good + bad + miss
    /** in Project SEKAI only PERFECT and GREAT keep the combo */
    val isFullCombo get() = total > 0 && good == 0 && bad == 0 && miss == 0
    val isAllPerfect get() = isFullCombo && great == 0
}

/** Judgment weights in percent, e.g. GREAT = 70 means a GREAT counts as 70% of a PERFECT. */
data class Weights(
    val perfect: Double = 100.0,
    val great: Double = 70.0,
    val good: Double = 50.0,
    val bad: Double = 0.0,
    val miss: Double = 0.0,
) {
    /** accuracy in percent (0..100), null when there are no notes */
    fun accuracy(j: Judgments): Double? {
        if (j.total == 0) return null
        val sum = j.perfect * perfect + j.great * great + j.good * good + j.bad * bad + j.miss * miss
        return sum / j.total
    }
}

enum class Source { AUTO, BUTTON, TILE, IMPORT, MANUAL }

data class PlayRecord(
    val id: Long = 0,
    val playedAt: Long,
    val title: String,
    val difficulty: Difficulty?,
    val level: Int?,
    val score: Long?,
    val highScore: Long?,
    val combo: Int?,
    val judgments: Judgments,
    val fast: Int?,
    val late: Int?,
    /** title as OCR read it, kept to learn corrections */
    val ocrTitle: String = "",
    val source: Source = Source.AUTO,
    /** true while waiting for the user to confirm it */
    val pending: Boolean = false,
    /** reasons the recognition looked unsure, one per line */
    val issues: String = "",
    val imagePath: String? = null,
) {
    val fullCombo get() = judgments.isFullCombo
    val allPerfect get() = judgments.isAllPerfect

    /** identical results are the same play seen twice */
    val signature get() = listOf(
        difficulty?.name, score, combo, judgments.perfect, judgments.great, judgments.good, judgments.bad, judgments.miss
    ).joinToString("|")
}
