package io.github.northster.sekaitracker.ocr

import io.github.northster.sekaitracker.data.Difficulty
import io.github.northster.sekaitracker.data.Judgments
import kotlin.math.abs

enum class ScreenKind { RESULT, SONG_SELECT, LIVE_PREPARE, OTHER }

data class ParsedResult(
    val title: String,
    val difficulty: Difficulty?,
    val level: Int?,
    val combo: Int?,
    val perfect: Int?, val great: Int?, val good: Int?, val bad: Int?, val miss: Int?,
    val fast: Int?,
    val late: Int?,
    /** "FULL COMBO" / "ALL PERFECT" banner text seen on screen */
    val fullComboText: Boolean,
    val allPerfectText: Boolean,
    /** problems that should be checked by the user before saving */
    val issues: List<String>,
    /** softer notes (estimated values), shown in the review screen but no reason to stop auto saving */
    val notes: List<String>,
) {
    val judgments: Judgments? get() =
        if (perfect != null && great != null && good != null && bad != null && miss != null) Judgments(perfect, great, good, bad, miss) else null
    val isConfident get() = issues.isEmpty()
}

data class ParsedSongSelect(val title: String, val levels: Map<Difficulty, Int>)

/**
 * Reads the Project SEKAI result screen from OCR lines.
 *
 * The values are found next to their labels instead of at fixed coordinates, so the same code works on the Fold's inner
 * and cover screens and with letterboxing. Layout of the result screen (see screenshots/result.jpg):
 * - top left: song title, below it the difficulty badge (MASTER ...) and "楽曲Lv.31"
 * - "スコア" / "ハイスコア" with the scores (not read: accuracy comes from the judgment counts)
 * - left column: PERFECT / GREAT / GOOD / BAD / MISS with their counts on the right
 * - middle: "FULL COMBO" / "ALL PERFECT" banner, "COMBO 1187", LATE / FAST labels with counts below, FLICK
 */
object ResultParser {
    const val TITLE_MISSING = "곡 이름을 읽지 못함"
    private val judgmentLabels = listOf("PERFECT", "GREAT", "GOOD", "BAD", "MISS")
    private val levelRegex = Regex("""[Ll][VvＶｖ][.．。,]?\s*([0-9OoIl]{1,2})""")
    private val livePrepareText = Regex("""総合力|総合|종합력|おすすめ編成|추천\s*편성""")

    fun classify(boxes: List<OcrBox>): ScreenKind {
        val judgments = judgmentLabels.count { label -> boxes.any { isJudgmentLabel(it, label) } }
        if (judgments >= 4) return ScreenKind.RESULT
        val difficulties = Difficulty.entries.count { d -> boxes.any { TextNorm.matchesLabel(it.text, d.label) } }
        if (difficulties >= 4) return ScreenKind.SONG_SELECT
        // live prepare (screenshots/live_prepare.jpg): one difficulty badge next to the team, the AUTO switch below
        if (difficulties == 1 && boxes.any { TextNorm.letters(it.text) == "AUTO" || livePrepareText.containsMatchIn(it.text) }) return ScreenKind.LIVE_PREPARE
        return ScreenKind.OTHER
    }

    private fun isJudgmentLabel(box: OcrBox, label: String): Boolean {
        val l = TextNorm.labelLetters(box.text)
        // "ALL PERFECT" banner is not the PERFECT row; a label may carry its count in the same line ("PERFECT 1166")
        if (l.startsWith("ALL")) return false
        return TextNorm.matchesLabel(box.text, label) || TextNorm.matchesLabel(box.text.trim().split(Regex("""\s+""")).first(), label)
    }

    /** a count written in the same line as its label ("PERFECT 1166") */
    private fun inlineNumber(box: OcrBox): Long? {
        val parts = box.text.trim().split(Regex("""\s+"""))
        if (parts.size < 2) return null
        return TextNorm.parseNumber(parts.drop(1).joinToString(""))
    }

    private fun isNumeric(box: OcrBox) = TextNorm.parseNumber(box.text) != null

    /**
     * Joins number pieces written next to each other on one row (a count split in two boxes, "00" + "21").
     */
    internal fun mergeNumberPieces(boxes: List<OcrBox>): List<OcrBox> {
        val numbers = boxes.filter(::isNumeric).sortedBy { it.left }.toMutableList()
        val others = boxes.filterNot(::isNumeric)
        val merged = mutableListOf<OcrBox>()
        while (numbers.isNotEmpty()) {
            var cur = numbers.removeAt(0)
            while (true) {
                val next = numbers.firstOrNull { it.sameRow(cur) && it.left >= cur.right - cur.height * 0.2f &&
                    it.left - cur.right < 0.6f * maxOf(cur.height, it.height) &&
                    abs(it.height - cur.height) < 0.35f * maxOf(cur.height, it.height) } ?: break
                numbers.remove(next)
                cur = OcrBox(cur.text.trim() + next.text.trim(), cur.left, minOf(cur.top, next.top), next.right, maxOf(cur.bottom, next.bottom))
            }
            merged += cur
        }
        return others + merged
    }

    /** nearest number to the right of the label on the same row, without another label in between */
    private fun numberRightOf(label: OcrBox, boxes: List<OcrBox>): Long? {
        inlineNumber(label)?.let { return it }
        val row = boxes.filter { it !== label && it.sameRow(label) && it.left >= label.right - label.height * 0.3f }
            .sortedBy { it.left }
        val first = row.firstOrNull() ?: return null
        return TextNorm.parseNumber(first.text)
    }

    /** number shown under a label (LATE / FAST counts sit in a bar below their labels) */
    private fun numberBelow(label: OcrBox, numbers: List<OcrBox>): Long? = numbers
        .filter { it.cy > label.bottom - label.height * 0.2f && it.top < label.bottom + label.height * 2.5f &&
            abs(it.cx - label.cx) < label.width * 1.6f }
        .minByOrNull { abs(it.cx - label.cx) + abs(it.top - label.bottom) }
        ?.let { TextNorm.parseNumber(it.text) }

    fun parseResult(rawBoxes: List<OcrBox>, frameHeight: Int): ParsedResult {
        val boxes = mergeNumberPieces(rawBoxes)
        val numbers = boxes.filter(::isNumeric)
        val issues = mutableListOf<String>()
        val notes = mutableListOf<String>()

        // judgments: the left-most matching label for each (the banner and other text sit further right)
        val counts = judgmentLabels.associateWith { label ->
            val box = boxes.filter { isJudgmentLabel(it, label) }.minByOrNull { it.left }
            box?.let { numberRightOf(it, boxes) }?.toInt()?.takeIf { it in 0..9999 }
        }
        judgmentLabels.filter { counts[it] == null }.forEach { issues += "$it 개수를 읽지 못함" }

        // difficulty badge: the top-most difficulty word
        val diffBox = boxes.filter { b -> Difficulty.entries.any { TextNorm.matchesLabel(b.text, it.label) } }
            .minByOrNull { it.top }
        val difficulty = diffBox?.let { b -> Difficulty.entries.first { TextNorm.matchesLabel(b.text, it.label) } }
        if (difficulty == null) issues += "난이도를 읽지 못함"

        val level = boxes.mapNotNull { b -> levelRegex.find(b.text)?.let { b to it } }
            .minByOrNull { (b, _) -> if (diffBox == null) b.top.toFloat() else abs(b.cy - diffBox.cy) }
            ?.second?.groupValues?.get(1)?.let { TextNorm.parseNumber(it)?.toInt() }

        val title = diffBox?.let { findTitle(it, boxes) }.orEmpty()
        if (title.isBlank()) issues += TITLE_MISSING


        val comboLabel = boxes.firstOrNull { TextNorm.labelLetters(it.text) == "COMBO" || TextNorm.matchesLabel(it.text, "COMBO") }
        val combo = comboLabel?.let { numberRightOf(it, boxes) }?.toInt()

        val bannerLetters = boxes.map { TextNorm.letters(it.text) }
        val fullComboText = bannerLetters.any { it.contains("FULLCOMB") || it.contains("FULCOMB") || it.contains("ULLCOMBO") }
        val allPerfectText = bannerLetters.any { it.contains("ALLPERF") || it.contains("LLPERFECT") }

        val lateLabel = boxes.firstOrNull { TextNorm.matchesLabel(it.text, "LATE") }
        val fastLabel = boxes.firstOrNull { TextNorm.matchesLabel(it.text, "FAST") }
        var late = lateLabel?.let { numberBelow(it, numbers) }?.toInt()
        var fast = fastLabel?.let { numberBelow(it, numbers) }?.toInt()

        val judgments = if (counts.values.all { it != null })
            Judgments(counts["PERFECT"]!!, counts["GREAT"]!!, counts["GOOD"]!!, counts["BAD"]!!, counts["MISS"]!!) else null

        if (judgments != null) {
            val offTiming = judgments.great + judgments.good + judgments.bad
            // FAST + LATE count the GREAT / GOOD / BAD judgments; a missing one can be worked out from the other
            if (late == null && fast != null && offTiming - fast >= 0) { late = offTiming - fast; notes += "LATE는 계산한 값" }
            if (fast == null && late != null && offTiming - late >= 0) { fast = offTiming - late; notes += "FAST는 계산한 값" }
            if (fast != null && late != null && fast + late != offTiming && fast + late != offTiming + judgments.miss)
                notes += "FAST+LATE(${fast + late})가 GREAT+GOOD+BAD($offTiming)와 다름"

            if (combo != null && combo > judgments.total) issues += "콤보($combo)가 노트 수(${judgments.total})보다 많음"
            if (judgments.isFullCombo && combo != null && combo != judgments.total) issues += "풀콤보인데 콤보($combo)와 노트 수(${judgments.total})가 다름"
            if (fullComboText && !judgments.isFullCombo) issues += "FULL COMBO 표시와 판정 개수가 맞지 않음"
            if (allPerfectText && !judgments.isAllPerfect) issues += "ALL PERFECT 표시와 판정 개수가 맞지 않음"
            if (judgments.total == 0) issues += "노트 수가 0"
        }
        if (combo == null) notes += "콤보를 읽지 못함"

        return ParsedResult(
            title = title, difficulty = difficulty, level = level,
            combo = combo ?: judgments?.takeIf { it.isFullCombo }?.total,
            perfect = counts["PERFECT"], great = counts["GREAT"], good = counts["GOOD"], bad = counts["BAD"], miss = counts["MISS"],
            fast = fast, late = late, fullComboText = fullComboText, allPerfectText = allPerfectText,
            issues = issues, notes = notes,
        )
    }

    /** the title is the text line right above the difficulty badge, starting near its left edge */
    private fun findTitle(diff: OcrBox, boxes: List<OcrBox>): String? {
        val above = boxes.filter {
            it !== diff && it.bottom <= diff.top + diff.height * 0.4f && it.bottom > diff.top - diff.height * 3f &&
                it.left < diff.right && it.right > diff.left - diff.width && !isNumeric(it) && !levelRegex.containsMatchIn(it.text)
        }
        val nearest = above.maxByOrNull { it.bottom } ?: return null
        // long titles may come in pieces on one row
        return above.filter { it.sameRow(nearest) }.sortedBy { it.left }.joinToString(" ") { it.text.trim() }.trim()
    }

    /**
     * Where the song title is on the result screen, as left, top, right, bottom: above the difficulty badge, or the top
     * left corner when the badge was not read. Used to read the title again, enlarged, when the first OCR missed it.
     */
    fun titleRegion(boxes: List<OcrBox>, width: Int, height: Int): IntArray {
        val diff = boxes.filter { b -> Difficulty.entries.any { TextNorm.matchesLabel(b.text, it.label) } }.minByOrNull { it.top }
        val r = if (diff != null) intArrayOf(
            (diff.left - diff.width * 0.5f).toInt(), (diff.top - diff.height * 2.6f).toInt(),
            (diff.left + diff.width * 5f).toInt(), (diff.top - diff.height * 0.05f).toInt(),
        ) else intArrayOf((width * 0.15f).toInt(), 0, (width * 0.6f).toInt(), (height * 0.085f).toInt())
        return intArrayOf(r[0].coerceIn(0, width), r[1].coerceIn(0, height), r[2].coerceIn(0, width), r[3].coerceIn(0, height))
    }

    /** the title from OCR of the enlarged title area: the tallest text line, pieces on its row joined */
    fun titleFromCrop(boxes: List<OcrBox>): String {
        val text = boxes.filter {
            !isNumeric(it) && !levelRegex.containsMatchIn(it.text) && TextNorm.titleKey(it.text).isNotEmpty() &&
                Difficulty.entries.none { d -> TextNorm.matchesLabel(it.text, d.label) }
        }
        val tallest = text.maxByOrNull { it.height } ?: return ""
        return text.filter { it.sameRow(tallest) }.sortedBy { it.left }.joinToString(" ") { it.text.trim() }.trim()
    }

    /**
     * Song select screen (screenshots/song_select.jpg): the selected song's panel on the right shows the title above the
     * five difficulty circles with their levels, and the difficulty names under the circles.
     */
    fun parseSongSelect(boxes: List<OcrBox>, frameHeight: Int): ParsedSongSelect? {
        val labels = Difficulty.entries.mapNotNull { d ->
            boxes.filter { TextNorm.matchesLabel(it.text, d.label) }.maxByOrNull { it.left }?.let { d to it }
        }.toMap()
        if (labels.size < 4) return null
        val numbers = boxes.filter(::isNumeric)
        val levels = labels.mapNotNull { (d, l) ->
            numbers.filter { it.bottom <= l.top + l.height * 0.5f && it.bottom > l.top - l.height * 3f && abs(it.cx - l.cx) < l.width }
                .minByOrNull { abs(it.cx - l.cx) }
                ?.let { TextNorm.parseNumber(it.text)?.toInt() }?.takeIf { it in 1..40 }?.let { d to it }
        }.toMap()
        val panelLeft = labels.values.minOf { it.left } - labels.values.first().height * 2
        val panelRight = labels.values.maxOf { it.right }
        val rowTop = (levels.keys.mapNotNull { d -> numbers.firstOrNull { b -> TextNorm.parseNumber(b.text)?.toInt() == levels[d] && b.left >= panelLeft } }
            .minOfOrNull { it.top } ?: labels.values.minOf { it.top }).toFloat()
        val candidates = boxes.filter {
            it.left >= panelLeft && it.right <= panelRight + it.height && it.bottom < rowTop && it.top > rowTop - frameHeight * 0.25f &&
                !isNumeric(it) && TextNorm.titleKey(it.text).length >= 2 && !it.text.trim().startsWith("Vo", ignoreCase = true)
        }
        if (candidates.isEmpty()) return ParsedSongSelect("", levels)
        val maxH = candidates.maxOf { it.height }
        val title = candidates.filter { it.height >= maxH * 0.85f }.minByOrNull { it.top }!!.text.trim()
        return ParsedSongSelect(title, levels)
    }

    /** the difficulty shown on the live prepare screen */
    fun parseLivePrepare(boxes: List<OcrBox>): Difficulty? =
        boxes.firstNotNullOfOrNull { b -> Difficulty.entries.firstOrNull { TextNorm.matchesLabel(b.text, it.label) } }
}
