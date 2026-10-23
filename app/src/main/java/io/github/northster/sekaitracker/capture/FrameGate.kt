package io.github.northster.sekaitracker.capture

import kotlin.math.abs

/**
 * Decides when a frame is worth running OCR on, to keep CPU use (and heat) low.
 *
 * It compares a tiny gray thumbnail of the left part of the screen, where the result screen shows its numbers. During
 * play the notes keep that area moving, so OCR never runs; the result screen stands still once the numbers have counted
 * up (the character on the right keeps moving, which is why only the left part is used). OCR runs once per new still
 * picture, not again while it stays the same.
 */
class FrameGate(private val threshold: Double = 6.0) {
    private var previous: IntArray? = null
    private var lastChecked: IntArray? = null
    private var stableTicks = 0

    enum class Decision { SKIP, OCR }

    /** true when the picture changed a lot since the last OCR: the screen the overlay belongs to is gone */
    var leftCheckedScreen = false
        private set

    fun offer(thumb: IntArray): Decision {
        val prev = previous
        previous = thumb
        stableTicks = if (prev != null && diff(prev, thumb) < threshold) stableTicks + 1 else 0
        leftCheckedScreen = lastChecked?.let { diff(it, thumb) >= threshold * 2 } ?: false
        if (stableTicks < 1) return Decision.SKIP
        if (lastChecked?.let { diff(it, thumb) < threshold } == true) return Decision.SKIP
        lastChecked = thumb
        return Decision.OCR
    }

    /** after a forced capture, so the same picture is not read again automatically */
    fun markChecked(thumb: IntArray) { lastChecked = thumb; previous = thumb }

    fun reset() { previous = null; lastChecked = null; stableTicks = 0; leftCheckedScreen = false }

    companion object {
        const val THUMB_W = 32
        const val THUMB_H = 20

        /** mean absolute difference of two thumbnails, 0..255 */
        fun diff(a: IntArray, b: IntArray): Double {
            if (a.size != b.size) return 255.0
            var sum = 0L
            for (i in a.indices) sum += abs(a[i] - b[i])
            return sum.toDouble() / a.size
        }
    }
}
