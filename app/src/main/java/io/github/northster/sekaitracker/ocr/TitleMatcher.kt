package io.github.northster.sekaitracker.ocr

/**
 * Turns an OCR'd title into a known song title: corrections the user made before (aliases), then the closest title among
 * songs seen on the song select screen and in earlier records. The song selected last gets a lower bar, since it is
 * most likely the one just played.
 */
object TitleMatcher {
    data class Match(val title: String, val known: Boolean, val similarity: Double)

    fun match(
        ocrTitle: String,
        known: Collection<String>,
        aliases: Map<String, String>,
        lastSelected: String? = null,
        threshold: Double = 0.7,
    ): Match {
        val raw = ocrTitle.trim()
        aliases[TextNorm.titleKey(raw)]?.let { return Match(it, true, 1.0) }
        if (raw.isEmpty()) return lastSelected?.let { Match(it, true, 0.0) } ?: Match("", false, 0.0)
        lastSelected?.let { sel ->
            val s = TextNorm.titleSimilarity(raw, sel)
            if (s >= 0.5) return Match(sel, true, s)
        }
        val best = known.maxByOrNull { TextNorm.titleSimilarity(raw, it) }
        val sim = best?.let { TextNorm.titleSimilarity(raw, it) } ?: 0.0
        return if (best != null && sim >= threshold) Match(best, true, sim) else Match(raw, false, sim)
    }
}
