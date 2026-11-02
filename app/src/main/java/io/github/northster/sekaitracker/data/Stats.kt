package io.github.northster.sekaitracker.data

/** Statistics of one song + difficulty. Accuracy is always computed from the raw counts with the current weights. */
data class ChartStats(
    val title: String,
    val difficulty: Difficulty?,
    val level: Int?,
    val plays: Int,
    val bestAccuracy: Double?,
    /** the play with the best accuracy */
    val best: PlayRecord?,
    val fullCombo: Boolean,
    val allPerfect: Boolean,
    val lastPlayedAt: Long,
)

object Stats {
    fun forChart(records: List<PlayRecord>, weights: Weights): ChartStats? {
        val saved = records.filterNot { it.pending }.sortedBy { it.playedAt }
        val first = saved.firstOrNull() ?: return null
        val best = bestPlay(saved, weights)
        return ChartStats(
            title = first.title,
            difficulty = first.difficulty,
            level = saved.mapNotNull { it.level }.lastOrNull(),
            plays = saved.size,
            bestAccuracy = best?.let { weights.accuracy(it.judgments) },
            best = best,
            fullCombo = saved.any { it.fullCombo },
            allPerfect = saved.any { it.allPerfect },
            lastPlayedAt = saved.last().playedAt,
        )
    }

    /** the play with the best accuracy; on a tie the earlier one */
    fun bestPlay(records: List<PlayRecord>, weights: Weights): PlayRecord? = records.filterNot { it.pending }
        .filter { weights.accuracy(it.judgments) != null }
        .sortedBy { it.playedAt }
        .maxByOrNull { weights.accuracy(it.judgments)!! }

    /** this play's counts minus the previous best play's (PERFECT -8 = eight fewer PERFECTs than the best) */
    fun deltas(play: Judgments, best: Judgments) = Judgments(
        play.perfect - best.perfect, play.great - best.great, play.good - best.good, play.bad - best.bad, play.miss - best.miss,
    )

    /** one entry per song + difficulty, most recently played first */
    fun allCharts(records: List<PlayRecord>, weights: Weights): List<ChartStats> = records.filterNot { it.pending }
        .groupBy { it.title to it.difficulty }
        .values.mapNotNull { forChart(it, weights) }
        .sortedByDescending { it.lastPlayedAt }

    /** what the overlay shows after a play */
    data class Comparison(val accuracy: Double?, val previousBest: Double?, val newBest: Boolean, val previousBestPlay: PlayRecord?)

    fun compare(play: PlayRecord, earlier: List<PlayRecord>, weights: Weights): Comparison {
        val acc = weights.accuracy(play.judgments)
        // the same play seen again (same numbers) is not its own previous best
        val bestPlay = bestPlay(earlier.filter { it.id != play.id && it.signature != play.signature }, weights)
        val prevBest = bestPlay?.let { weights.accuracy(it.judgments) }
        return Comparison(
            accuracy = acc,
            previousBest = prevBest,
            newBest = acc != null && (prevBest == null || acc > prevBest + 1e-9),
            previousBestPlay = bestPlay,
        )
    }
}
