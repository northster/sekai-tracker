package io.github.northster.sekaitracker.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import io.github.northster.sekaitracker.data.OcrLanguage
import kotlinx.coroutines.tasks.await

/**
 * On-device OCR with ML Kit. The models are bundled in the APK, so nothing is downloaded and no network is used.
 * The Japanese and Korean models both read Latin letters and digits as well.
 */
object Recognizer {
    private var language: OcrLanguage? = null
    private var recognizer: TextRecognizer? = null

    @Synchronized
    private fun get(lang: OcrLanguage): TextRecognizer {
        recognizer?.takeIf { language == lang }?.let { return it }
        recognizer?.close()
        return when (lang) {
            OcrLanguage.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            OcrLanguage.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        }.also { recognizer = it; language = lang }
    }

    /** text lines with their boxes, in the bitmap's pixel coordinates */
    suspend fun read(bitmap: Bitmap, lang: OcrLanguage): List<OcrBox> {
        val text = get(lang).process(InputImage.fromBitmap(bitmap, 0)).await()
        return text.textBlocks.flatMap { block -> block.lines }.flatMap { line ->
            // keep titles as whole lines, but split lines that run labels or numbers together ("EASY NORMAL HARD", "7 12 18")
            val pieces = if (line.elements.size > 1 && (line.elements.any { TextNorm.labelLetters(it.text) in splitWords } ||
                    line.elements.all { TextNorm.parseNumber(it.text) != null }))
                line.elements.map { it.text to it.boundingBox } else listOf(line.text to line.boundingBox)
            pieces.mapNotNull { (t, r) -> r?.let { OcrBox(t, it.left, it.top, it.right, it.bottom) } }
        }
    }

    private val splitWords = setOf("PERFECT", "GREAT", "GOOD", "BAD", "MISS", "COMBO", "LATE", "FAST", "FLICK",
        "EASY", "NORMAL", "HARD", "EXPERT", "MASTER", "APPEND")

    @Synchronized
    fun release() { recognizer?.close(); recognizer = null; language = null }
}
