package io.github.northster.sekaitracker

import io.github.northster.sekaitracker.data.Difficulty
import io.github.northster.sekaitracker.ocr.OcrBox
import io.github.northster.sekaitracker.ocr.ResultParser
import io.github.northster.sekaitracker.ocr.ScreenKind
import io.github.northster.sekaitracker.ocr.TextNorm
import io.github.northster.sekaitracker.ocr.TitleMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are OCR output of the screenshots in screenshots/:
 * - *_rapidocr.tsv: a desktop OCR (Chinese model, so the kana are missing or wrong) on the real images
 * - *_mlkit_like.tsv: the same boxes with the Japanese text ML Kit is expected to read, the score's grey leading zero split
 *   off and the small LATE count present
 */
class ResultParserTest {
    private fun load(name: String) = OcrBox.parseTsv(javaClass.classLoader!!.getResource(name)!!.readText())

    @Test fun resultScreenFromRealOcr() {
        val boxes = load("result_rapidocr.tsv")
        assertEquals(ScreenKind.RESULT, ResultParser.classify(boxes))
        val r = ResultParser.parseResult(boxes, 1248)
        assertEquals(Difficulty.MASTER, r.difficulty)
        assertEquals(31, r.level)
        assertEquals(1187, r.combo)
        assertEquals(listOf(1166, 21, 0, 0, 0), listOf(r.perfect, r.great, r.good, r.bad, r.miss))
        assertEquals(17, r.fast)
        // the small "4" was not read, it is worked out from GREAT + GOOD + BAD - FAST
        assertEquals(4, r.late)
        assertTrue(r.fullComboText)
        assertFalse(r.allPerfectText)
        assertTrue(r.judgments!!.isFullCombo)
        assertFalse(r.judgments!!.isAllPerfect)
        assertTrue(r.issues.toString(), r.isConfident)
    }

    @Test fun resultScreenMlKitLike() {
        val boxes = load("result_mlkit_like.tsv")
        assertEquals(ScreenKind.RESULT, ResultParser.classify(boxes))
        val r = ResultParser.parseResult(boxes, 1248)
        assertEquals("ももいろの鍵", r.title)
        assertEquals(Difficulty.MASTER, r.difficulty)
        assertEquals(31, r.level)
        assertEquals(1187, r.combo)
        assertEquals(listOf(1166, 21, 0, 0, 0), listOf(r.perfect, r.great, r.good, r.bad, r.miss))
        assertEquals(4, r.late)
        assertEquals(17, r.fast)
        assertTrue(r.notes.toString(), r.notes.isEmpty())
        assertTrue(r.issues.toString(), r.isConfident)
    }

    @Test fun inconsistentCountsNeedReview() {
        // OCR read MISS "0000" as "0008" while the banner says FULL COMBO
        val boxes = load("result_mlkit_like.tsv").map {
            if (it.text == "0000" && it.top == 949) it.copy(text = "0008") else it
        }
        val r = ResultParser.parseResult(boxes, 1248)
        assertFalse(r.isConfident)
    }

    @Test fun labelAndNumberInOneLine() {
        val boxes = listOf(
            OcrBox("曲名", 100, 40, 300, 70), OcrBox("EXPERT", 100, 100, 220, 130),
            OcrBox("PERFECT 500", 100, 400, 300, 430), OcrBox("GREAT 1O", 100, 450, 300, 480),
            OcrBox("GOOD 0", 100, 500, 300, 530), OcrBox("BAD 0", 100, 550, 300, 580), OcrBox("MISS 2", 100, 600, 300, 630),
            OcrBox("987654", 200, 200, 500, 260),
        )
        val r = ResultParser.parseResult(boxes, 800)
        assertEquals(listOf(500, 10, 0, 0, 2), listOf(r.perfect, r.great, r.good, r.bad, r.miss))
        assertEquals(Difficulty.EXPERT, r.difficulty)
        assertEquals("曲名", r.title)
    }

    @Test fun allPerfectBannerIsNotThePerfectRow() {
        val boxes = load("result_mlkit_like.tsv").map {
            when {
                it.text.contains("FULL") -> it.copy(text = "ALL PERFECT")
                it.text == "0021" -> it.copy(text = "0000")
                it.text == "1166" -> it.copy(text = "1187")
                it.text == "17" -> it.copy(text = "0")
                it.text == "4" -> it.copy(text = "0")
                else -> it
            }
        }
        val r = ResultParser.parseResult(boxes, 1248)
        assertEquals(1187, r.perfect)
        assertTrue(r.allPerfectText)
        assertTrue(r.judgments!!.isAllPerfect)
        assertTrue(r.issues.toString(), r.isConfident)
    }

    @Test fun songSelectScreen() {
        val real = load("song_select_rapidocr.tsv")
        assertEquals(ScreenKind.SONG_SELECT, ResultParser.classify(real))
        val s = ResultParser.parseSongSelect(load("song_select_mlkit_like.tsv"), 1248)!!
        assertEquals("メモリア", s.title)
        assertEquals(
            mapOf(Difficulty.EASY to 9, Difficulty.NORMAL to 15, Difficulty.HARD to 23, Difficulty.EXPERT to 31, Difficulty.MASTER to 34),
            s.levels,
        )
    }

    @Test fun livePrepareScreen() {
        val boxes = load("live_prepare_rapidocr.tsv")
        assertEquals(ScreenKind.LIVE_PREPARE, ResultParser.classify(boxes))
        assertEquals(Difficulty.EXPERT, ResultParser.parseLivePrepare(boxes))
    }

    @Test fun otherScreens() {
        assertEquals(ScreenKind.OTHER, ResultParser.classify(listOf(OcrBox("ガチャ", 0, 0, 10, 10), OcrBox("EXPERT", 0, 20, 10, 30))))
    }

    @Test fun numbers() {
        assertEquals(21L, TextNorm.parseNumber("0021"))
        assertEquals(0L, TextNorm.parseNumber("OOOO"))
        assertEquals(1505482L, TextNorm.parseNumber("15O5482"))
        assertNull(TextNorm.parseNumber("BAD"))
        assertNull(TextNorm.parseNumber("MISS"))
        assertNull(TextNorm.parseNumber("LATE"))
    }

    @Test fun titleMatching() {
        val known = listOf("ももいろの鍵", "メモリア", "初音狂奏曲第01番「彗煌」")
        assertEquals("ももいろの鍵", TitleMatcher.match("もも いろの键", known, emptyMap()).title)
        assertEquals("初音狂奏曲第01番「彗煌」", TitleMatcher.match("初音狂奏曲第01番「彗惺」", known, emptyMap()).title)
        assertFalse(TitleMatcher.match("ロキ", known, emptyMap()).known)
        assertEquals("ロキ", TitleMatcher.match("口キ", known, mapOf(TextNorm.titleKey("口キ") to "ロキ")).title)
    }
}
