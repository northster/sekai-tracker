package io.github.northster.sekaitracker.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Csv {
    private val header = listOf(
        "날짜", "곡", "난이도", "레벨", "점수", "하이스코어", "콤보", "PERFECT", "GREAT", "GOOD", "BAD", "MISS",
        "FAST", "LATE", "풀콤보", "올퍼펙트", "정확도(%)", "입력",
    )

    private fun cell(v: Any?): String {
        val s = v?.toString() ?: ""
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    /** UTF-8 with BOM so Excel shows Korean / Japanese titles correctly */
    fun export(records: List<PlayRecord>, weights: Weights, timeZone: TimeZone = TimeZone.getDefault()): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { this.timeZone = timeZone }
        val sb = StringBuilder("﻿")
        sb.append(header.joinToString(",")).append("\r\n")
        records.filterNot { it.pending }.sortedBy { it.playedAt }.forEach { r ->
            val j = r.judgments
            sb.append(listOf(
                fmt.format(Date(r.playedAt)), r.title, r.difficulty?.label, r.level, r.score, r.highScore, r.combo,
                j.perfect, j.great, j.good, j.bad, j.miss, r.fast, r.late,
                if (r.fullCombo) "Y" else "", if (r.allPerfect) "Y" else "",
                weights.accuracy(j)?.let { String.format(Locale.US, "%.2f", it) }, r.source.name,
            ).joinToString(",") { cell(it) }).append("\r\n")
        }
        return sb.toString()
    }
}
