package io.github.northster.sekaitracker.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.northster.sekaitracker.data.Difficulty
import io.github.northster.sekaitracker.data.RecordDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SekaiTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = if (android.os.Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = scheme) { Surface(color = MaterialTheme.colorScheme.background, content = content) }
}

/** reads from the database off the main thread, again after every write */
@Composable
fun <T> dbState(initial: T, vararg keys: Any?, load: () -> T): T {
    val version by RecordDb.changes.collectAsState()
    val state by produceState(initial, version, *keys) { value = withContext(Dispatchers.IO) { load() } }
    return state
}

fun difficultyColor(d: Difficulty?): Color = when (d) {
    Difficulty.EASY -> Color(0xFF3CB371)
    Difficulty.NORMAL -> Color(0xFF3D9BE9)
    Difficulty.HARD -> Color(0xFFE8A92E)
    Difficulty.EXPERT -> Color(0xFFEE4466)
    Difficulty.MASTER -> Color(0xFFB04FE0)
    Difficulty.APPEND -> Color(0xFFE77FD2)
    null -> Color.Gray
}

@Composable
fun DifficultyChip(d: Difficulty?, level: Int?, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = {},
        label = { Text("${d?.label ?: "?"}${level?.let { " $it" } ?: ""}") },
        colors = AssistChipDefaults.assistChipColors(containerColor = difficultyColor(d), labelColor = Color.White),
        border = null,
        modifier = modifier.padding(end = 4.dp),
    )
}

fun pct(v: Double?) = v?.let { String.format(Locale.US, "%.2f%%", it) } ?: "-"

fun dateTime(t: Long): String = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(t))

suspend fun decodeImage(context: Context, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
}
