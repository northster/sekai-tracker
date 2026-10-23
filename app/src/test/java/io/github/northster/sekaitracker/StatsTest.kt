package io.github.northster.sekaitracker

import io.github.northster.sekaitracker.capture.FrameGate
import io.github.northster.sekaitracker.data.Csv
import io.github.northster.sekaitracker.data.Difficulty
import io.github.northster.sekaitracker.data.Judgments
import io.github.northster.sekaitracker.data.PlayRecord
import io.github.northster.sekaitracker.data.Stats
import io.github.northster.sekaitracker.data.Weights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class StatsTest {
    private fun rec(id: Long, at: Long, p: Int, g: Int, miss: Int = 0, title: String = "メモリア") = PlayRecord(
        id = id, playedAt = at, title = title, difficulty = Difficulty.EXPERT, level = 31, score = 1_000_000L + p,
        highScore = null, combo = p + g, judgments = Judgments(p, g, 0, 0, miss), fast = null, late = null,
    )

    @Test fun accuracyWithWeights() {
        val j = Judgments(1166, 21, 0, 0, 0)
        assertEquals((1166 * 100.0 + 21 * 70.0) / 1187, Weights().accuracy(j)!!, 1e-9)
        assertEquals((1166 * 100.0 + 21 * 90.0) / 1187, Weights(great = 90.0).accuracy(j)!!, 1e-9)
        assertEquals(null, Weights().accuracy(Judgments(0, 0, 0, 0, 0)))
    }

    @Test fun chartStats() {
        val w = Weights()
        val records = listOf(rec(1, 1, 90, 10), rec(2, 2, 95, 5), rec(3, 3, 100, 0), rec(4, 4, 50, 0, title = "other"))
        val charts = Stats.allCharts(records, w)
        assertEquals(2, charts.size)
        val m = charts.first { it.title == "メモリア" }
        assertEquals(3, m.plays)
        assertEquals(100.0, m.bestAccuracy!!, 1e-9)
        assertEquals((97.0 + 98.5 + 100.0) / 3, m.averageAccuracy!!, 1e-9)
        assertTrue(m.allPerfect)

        val cmp = Stats.compare(records[2], records.take(2), w)
        assertTrue(cmp.newBest)
        assertEquals(98.5, cmp.previousBest!!, 1e-9)
        assertFalse(Stats.compare(records[0], records.drop(1).take(2), w).newBest)
    }

    @Test fun csv() {
        val out = Csv.export(listOf(rec(1, 0, 90, 10, title = "a,\"b\"")), Weights(), TimeZone.getTimeZone("UTC"))
        assertTrue(out.startsWith("﻿날짜,곡"))
        assertTrue(out.contains("1970-01-01 00:00:00,\"a,\"\"b\"\"\",EXPERT,31,"))
        assertTrue(out.contains(",97.00,AUTO"))
    }

    @Test fun gateRunsOcrOncePerStillPicture() {
        val gate = FrameGate()
        val a = IntArray(16) { 100 }
        val b = IntArray(16) { 200 }
        assertEquals(FrameGate.Decision.SKIP, gate.offer(a)) // first frame: not known to be still yet
        assertEquals(FrameGate.Decision.OCR, gate.offer(a))
        assertEquals(FrameGate.Decision.SKIP, gate.offer(a)) // same picture again
        assertEquals(FrameGate.Decision.SKIP, gate.offer(b)) // changed (moving)
        assertTrue(gate.leftCheckedScreen)
        assertEquals(FrameGate.Decision.OCR, gate.offer(b))
    }
}
