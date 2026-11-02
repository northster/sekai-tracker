package io.github.northster.sekaitracker.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import io.github.northster.sekaitracker.ocr.OcrBox
import io.github.northster.sekaitracker.ocr.ResultParser
import io.github.northster.sekaitracker.ocr.TextNorm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Song jackets cut out of result screens, one small PNG per song, kept in the app's own storage. */
object JacketStore {
    private const val SIZE = 192
    private lateinit var dir: File
    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val changeCounter = MutableStateFlow(0)
    /** bumps when a jacket is added or moved, so lists show it */
    val changes: StateFlow<Int> get() = changeCounter

    fun init(context: Context) { dir = File(context.filesDir, "jackets").apply { mkdirs() } }

    private fun file(title: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(TextNorm.titleKey(title).toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.png")
    }

    fun has(title: String) = title.isNotBlank() && file(title).exists()

    /** cuts the jacket out of a result screen picture, unless this song already has one */
    fun saveFromResult(title: String, picture: Bitmap, boxes: List<OcrBox>) {
        if (title.isBlank() || has(title)) return
        val r = ResultParser.jacketRegion(boxes, picture.width, picture.height)
        val w = r[2] - r[0]
        val h = r[3] - r[1]
        if (w < 24 || h < 24) return
        val crop = Bitmap.createBitmap(picture, r[0], r[1], w, h)
        val small = Bitmap.createScaledBitmap(crop, SIZE, SIZE, true)
        if (small !== crop) crop.recycle()
        // a nearly single-colored crop means the jacket was not where expected
        if (looksEmpty(small)) { small.recycle(); return }
        runCatching { FileOutputStream(file(title)).use { small.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        cache[TextNorm.titleKey(title)] = small
        changeCounter.value++
    }

    fun load(title: String): Bitmap? {
        if (title.isBlank()) return null
        val key = TextNorm.titleKey(title)
        cache[key]?.let { return it }
        val f = file(title)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.absolutePath)?.also { cache[key] = it }
    }

    /** a song got another name: the new name gets the jacket too (unless it already has one) */
    fun copy(oldTitle: String, newTitle: String) {
        if (oldTitle.isBlank() || newTitle.isBlank() || TextNorm.titleKey(oldTitle) == TextNorm.titleKey(newTitle)) return
        val from = file(oldTitle)
        if (!from.exists() || has(newTitle)) return
        runCatching { from.copyTo(file(newTitle)) }
        changeCounter.value++
    }

    private fun looksEmpty(bmp: Bitmap): Boolean {
        var min = 255; var max = 0
        for (y in 0 until bmp.height step 16) for (x in 0 until bmp.width step 16) {
            val c = bmp.getPixel(x, y)
            val l = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
            if (l < min) min = l
            if (l > max) max = l
        }
        return max - min < 24
    }
}
