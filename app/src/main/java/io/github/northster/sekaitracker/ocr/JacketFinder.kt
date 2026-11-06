package io.github.northster.sekaitracker.ocr

/**
 * Finds the jacket's frame on the result screen: a strongly colored border (the difficulty color) between a gray header
 * and the picture. Scanning from outside the estimated square inward, the first strongly colored pixel is the frame.
 * Pure Kotlin on a pixel getter, so it can be tested on the screenshots.
 */
object JacketFinder {
    /** saturated and bright: the frame (and the badge), never the gray header or the dark navy background */
    fun isFrame(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        val max = maxOf(r, g, b); val min = minOf(r, g, b)
        return max > 150 && (max - min) > max / 2
    }

    private fun median(values: List<Int>) = values.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

    /**
     * [estimate] is the frame's outer square (left, top, right, bottom); returns the picture inside the frame, or null
     * when no frame of about that size is found there.
     */
    fun refine(estimate: IntArray, width: Int, height: Int, rightLimit: Int, pixel: (Int, Int) -> Int): IntArray? {
        val size = estimate[2] - estimate[0]
        if (size < 16) return null
        val reach = (size * 0.4f).toInt()
        val cx = ((estimate[0] + estimate[2]) / 2).coerceIn(0, width - 1)
        val cy = ((estimate[1] + estimate[3]) / 2).coerceIn(0, height - 1)
        val rows = listOf(-2, -1, 0, 1, 2).map { (cy + it * size / 6).coerceIn(0, height - 1) }
        val left = median(rows.mapNotNull { y -> ((estimate[0] - reach).coerceAtLeast(0)..cx).firstOrNull { isFrame(pixel(it, y)) } }) ?: return null
        val right = median(rows.mapNotNull { y ->
            (minOf(rightLimit, estimate[2] + reach, width - 1) downTo cx).firstOrNull { isFrame(pixel(it, y)) }
        }) ?: return null
        val mid = (left + right) / 2
        val cols = listOf(-1, 0, 1).map { (mid + it * size / 6).coerceIn(0, width - 1) }
        val top = median(cols.mapNotNull { x -> ((estimate[1] - reach).coerceAtLeast(0)..cy).firstOrNull { isFrame(pixel(x, it)) } }) ?: return null
        val bottom = median(cols.mapNotNull { x ->
            (minOf(estimate[3] + reach, height - 1) downTo cy).firstOrNull { isFrame(pixel(x, it)) }
        }) ?: return null
        val w = right - left
        val h = bottom - top
        if (w < size * 0.6f || w > size * 1.6f || kotlin.math.abs(w - h) > maxOf(w, h) * 0.15f) return null
        // skip the frame's thickness, measured on the middle row, plus a little for its rounded corners
        var thick = 0
        while (thick < w / 8 && isFrame(pixel(left + thick, cy))) thick++
        val inset = thick + (w * 0.03f).toInt()
        return intArrayOf(left + inset, top + inset, right - inset, bottom - inset)
    }
}
