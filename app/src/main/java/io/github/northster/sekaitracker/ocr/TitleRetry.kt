package io.github.northster.sekaitracker.ocr

import android.graphics.Bitmap
import io.github.northster.sekaitracker.data.OcrLanguage

/** Reads the song title again from only the title area, enlarged: small or thin title text is read much better this way. */
object TitleRetry {
    suspend fun read(bitmap: Bitmap, boxes: List<OcrBox>, lang: OcrLanguage): String? {
        val r = ResultParser.titleRegion(boxes, bitmap.width, bitmap.height)
        val w = r[2] - r[0]
        val h = r[3] - r[1]
        if (w < 16 || h < 8) return null
        val crop = Bitmap.createBitmap(bitmap, r[0], r[1], w, h)
        val scale = minOf(3f, 2400f / w)
        val big = if (scale > 1.05f) Bitmap.createScaledBitmap(crop, (w * scale).toInt(), (h * scale).toInt(), true) else crop
        if (big !== crop) crop.recycle()
        return try {
            ResultParser.titleFromCrop(Recognizer.read(big, lang)).ifBlank { null }
        } finally {
            big.recycle()
        }
    }
}
