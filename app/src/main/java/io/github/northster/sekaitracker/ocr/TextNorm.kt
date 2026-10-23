package io.github.northster.sekaitracker.ocr

import java.text.Normalizer

/** Text clean-up for OCR output: digit look-alikes, label matching and title similarity. */
object TextNorm {
    private val digitLookAlikes = mapOf(
        'O' to '0', 'o' to '0', 'D' to '0', 'Q' to '0', 'U' to '0',
        'I' to '1', 'l' to '1', '|' to '1', 'i' to '1', '!' to '1', 'L' to '1',
        'S' to '5', 's' to '5', 'B' to '8', 'Z' to '2', 'z' to '2', 'g' to '9', 'q' to '9',
    )

    /**
     * Reads a count or score from OCR text. Look-alike letters are mapped to digits, but the text must have at least one
     * real digit or consist only of zero look-alikes ("OOOO"), so label words never turn into numbers.
     */
    fun parseNumber(raw: String): Long? {
        val s = Normalizer.normalize(raw, Normalizer.Form.NFKC).filterNot { it == ' ' || it == ',' || it == '.' }
        if (s.isEmpty() || s.length > 9) return null
        val hasDigit = s.any { it.isDigit() }
        if (!hasDigit && !s.all { it == 'O' || it == 'o' }) return null
        val mapped = s.map { c -> if (c.isDigit()) c else digitLookAlikes[c] ?: return null }.joinToString("")
        return mapped.toLongOrNull()
    }

    /** upper case latin letters only, for label matching ("✦FULL COMBO!✦" -> "FULLCOMBO") */
    fun letters(raw: String) = Normalizer.normalize(raw, Normalizer.Form.NFKC).uppercase().filter { it in 'A'..'Z' }

    /** letters with digit look-alikes turned back into letters (OCR sometimes reads "B" as "8" in labels) */
    fun labelLetters(raw: String) = Normalizer.normalize(raw, Normalizer.Form.NFKC).uppercase()
        .map { when (it) { '8' -> 'B'; '0' -> 'O'; '1' -> 'I'; '5' -> 'S'; else -> it } }
        .filter { it in 'A'..'Z' }.joinToString("")

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** label equality that tolerates one wrong letter for words of 4+ letters */
    fun matchesLabel(raw: String, label: String): Boolean {
        val l = labelLetters(raw)
        if (l == label) return true
        return label.length >= 4 && kotlin.math.abs(l.length - label.length) <= 1 && levenshtein(l, label) <= 1
    }

    /** key for comparing song titles: width/case folded, spaces and punctuation removed */
    fun titleKey(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase()
        .filter { it.isLetterOrDigit() }

    /** 0..1, 1 = same title */
    fun titleSimilarity(a: String, b: String): Double {
        val ka = titleKey(a); val kb = titleKey(b)
        if (ka.isEmpty() || kb.isEmpty()) return 0.0
        if (ka == kb) return 1.0
        val d = levenshtein(ka, kb)
        return 1.0 - d.toDouble() / maxOf(ka.length, kb.length)
    }
}
