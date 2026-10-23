package io.github.northster.sekaitracker.ocr

/** One recognized text line with its position in the captured frame (pixels). Pure Kotlin so the parser can be unit tested. */
data class OcrBox(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val cx get() = (left + right) / 2f
    val cy get() = (top + bottom) / 2f

    /** same text row: vertical centers close compared to the taller box */
    fun sameRow(o: OcrBox) = kotlin.math.abs(cy - o.cy) < 0.6f * maxOf(height, o.height)

    companion object {
        /** test fixtures: "text\tleft\ttop\tright\tbottom" per line */
        fun parseTsv(tsv: String): List<OcrBox> = tsv.lines().filter { it.isNotBlank() }.map { line ->
            val p = line.split('\t')
            OcrBox(p[0], p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4].toInt())
        }

        fun toTsv(boxes: List<OcrBox>) = boxes.joinToString("\n") { "${it.text}\t${it.left}\t${it.top}\t${it.right}\t${it.bottom}" }
    }
}
