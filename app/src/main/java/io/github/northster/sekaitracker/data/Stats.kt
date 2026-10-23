package io.github.northster.sekaitracker.data

/** Statistics of one song + difficulty. Accuracy is always computed from the raw counts with the current weights. */
data class ChartStats(
    val title: String,
    val difficulty: Difficulty?,
    val level: Int?,
    val plays: Int,
    val bestAccuracy: Double?,
    val averageAccuracy: Double?,
    /** average of the last [RECENT] plays minus the average of the plays before them, null without enough plays */
    val recentTrend: Double?,
    val bestScore: Long?,
    val fullCombo: Boolean,
    val allPerfect: Boolean,
    val lastPlayedAt: Long,
) {
    companion object { const val RECENT = 5 }
}

object Stats {
    fun forChart(records: List<PlayRecord>, weights: Weights): ChartStats? {
        val saved = records.filterNot { it.pending }.sortedBy { it.playedAt }
        val first = saved.firstOrNull() ?: return null
        val acc = saved.mapNotNull { weights.accuracy(it.judgments) }
        val trend = if (acc.size > ChartStats.RECENT) {
            acc.takeLast(ChartStats.RECENT).average() - acc.dropLast(ChartStats.RECENT).average()
        } else if (acc.size >= 2) acc.last() - acc.dropLast(1).average() else null
        return ChartStats(
            title = first.title,
            difficulty = first.difficulty,
            level = saved.mapNotNull { it.level }.lastOrNull(),
            plays = saved.size,
            bestAccuracy = acc.maxOrNull(),
            averageAccuracy = acc.takeIf { it.isNotEmpty() }?.average(),
            recentTrend = trend,
            bestScore = saved.mapNotNull { it.score }.maxOrNull(),
            fullCombo = saved.any { it.fullCombo },
            allPerfect = saved.any { it.allPerfect },
            lastPlayedAt = saved.last().playedAt,
        )
    }

    /** one entry per song + difficulty, most recently played first */
    fun allCharts(records: List<PlayRecord>, weights: Weights): List<ChartStats> = records.filterNot { it.pending }
        .groupBy { it.title to it.difficulty }
        .values.mapNotNull { forChart(it, weights) }
        .sortedByDescending { it.lastPlayedAt }

    /** what the overlay shows after a play */
    data class Comparison(val accuracy: Double?, val previousBest: Double?, val newBest: Boolean, val previousBestScore: Long?, val newBestScore: Boolean)

    fun compare(play: PlayRecord, earlier: List<PlayRecord>, weights: Weights): Comparison {
        val acc = weights.accuracy(play.judgments)
        val prev = earlier.filter { !it.pending && it.id != play.id }
        val prevBest = prev.mapNotNull { weights.accuracy(it.judgments) }.maxOrNull()
        val prevBestScore = prev.mapNotNull { it.score }.maxOrNull()
        return Comparison(
            accuracy = acc,
            previousBest = prevBest,
            newBest = acc != null && (prevBest == null || acc > prevBest + 1e-9),
            previousBestScore = prevBestScore,
            newBestScore = play.score != null && (prevBestScore == null || play.score > prevBestScore),
        )
    }
}
