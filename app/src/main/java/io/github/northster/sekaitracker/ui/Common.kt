package io.github.northster.sekaitracker.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.northster.sekaitracker.data.Difficulty
import io.github.northster.sekaitracker.data.JacketStore
import io.github.northster.sekaitracker.data.Judgments
import io.github.northster.sekaitracker.data.RecordDb
import io.github.northster.sekaitracker.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Material 3 color roles built around Miku's teal (primary), with pink (secondary) and purple (tertiary) accents
private val LightColors = lightColorScheme(
    primary = Color(0xFF006A63), onPrimary = Color.White, primaryContainer = Color(0xFF72F7EB), onPrimaryContainer = Color(0xFF00201D),
    secondary = Color(0xFF9C3F68), onSecondary = Color.White, secondaryContainer = Color(0xFFFFD9E4), onSecondaryContainer = Color(0xFF3E0022),
    tertiary = Color(0xFF6750A4), onTertiary = Color.White, tertiaryContainer = Color(0xFFEADDFF), onTertiaryContainer = Color(0xFF21005D),
    error = Color(0xFFBA1A1A), onError = Color.White, errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF4FBF9), onBackground = Color(0xFF161D1C), surface = Color(0xFFF4FBF9), onSurface = Color(0xFF161D1C),
    surfaceVariant = Color(0xFFDAE5E2), onSurfaceVariant = Color(0xFF3F4947), outline = Color(0xFF6F7977), outlineVariant = Color(0xFFBEC9C6),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFEEF5F3), surfaceContainer = Color(0xFFE8EFED),
    surfaceContainerHigh = Color(0xFFE3EAE8), surfaceContainerHighest = Color(0xFFDDE4E2),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF51DBCF), onPrimary = Color(0xFF003733), primaryContainer = Color(0xFF00504A), onPrimaryContainer = Color(0xFF72F7EB),
    secondary = Color(0xFFFFB0CC), onSecondary = Color(0xFF5E1138), secondaryContainer = Color(0xFF7E2850), onSecondaryContainer = Color(0xFFFFD9E4),
    tertiary = Color(0xFFD0BCFF), onTertiary = Color(0xFF381E72), tertiaryContainer = Color(0xFF4F378B), onTertiaryContainer = Color(0xFFEADDFF),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1514), onBackground = Color(0xFFDDE4E2), surface = Color(0xFF0E1514), onSurface = Color(0xFFDDE4E2),
    surfaceVariant = Color(0xFF3F4947), onSurfaceVariant = Color(0xFFBEC9C6), outline = Color(0xFF899390), outlineVariant = Color(0xFF3F4947),
    surfaceContainerLowest = Color(0xFF090F0E), surfaceContainerLow = Color(0xFF161D1C), surfaceContainer = Color(0xFF1A2120),
    surfaceContainerHigh = Color(0xFF252B2A), surfaceContainerHighest = Color(0xFF2F3635),
)

@Composable
fun SekaiTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val settings by Settings.flow.collectAsState()
    val scheme = when {
        settings.dynamicColor && android.os.Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme) { Surface(color = MaterialTheme.colorScheme.background, content = content) }
}

/** the song's jacket cut from a result screen, or an empty tile until one was seen */
@Composable
fun Jacket(title: String, size: Dp, modifier: Modifier = Modifier) {
    val version by JacketStore.changes.collectAsState()
    val bitmap by produceState<Bitmap?>(null, title, version) { value = withContext(Dispatchers.IO) { JacketStore.load(title) } }
    val shape = RoundedCornerShape(size / 8)
    val b = bitmap
    if (b != null) Image(b.asImageBitmap(), null, modifier.size(size).clip(shape))
    else Box(modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        Text("♪", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
    }
}

/** Korean titles by original title, kept fresh like other database reads */
@Composable
fun rememberKoTitles(): Map<String, String> = dbState(emptyMap()) { RecordDb.koTitles() }

/** the Korean title when known, with the original title small under it */
@Composable
fun SongTitle(title: String, ko: String?, big: Boolean = false) {
    Column {
        Text(
            ko ?: title.ifBlank { "(곡 이름 없음)" },
            style = if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleSmall,
            fontWeight = if (big) FontWeight.Bold else FontWeight.SemiBold, maxLines = if (big) 3 else 1, overflow = TextOverflow.Ellipsis,
        )
        if (ko != null) Text(title, style = if (big) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** reads from the database off the main thread, again after every write */
@Composable
fun <T> dbState(initial: T, vararg keys: Any?, load: () -> T): T {
    val version by RecordDb.changes.collectAsState()
    val state by produceState(initial, version, *keys) { value = withContext(Dispatchers.IO) { load() } }
    return state
}

/** the game's difficulty colors */
fun difficultyColor(d: Difficulty?): Color = when (d) {
    Difficulty.EASY -> Color(0xFF3CB371)
    Difficulty.NORMAL -> Color(0xFF3D9BE9)
    Difficulty.HARD -> Color(0xFFE8A92E)
    Difficulty.EXPERT -> Color(0xFFEE4466)
    Difficulty.MASTER -> Color(0xFFB04FE0)
    Difficulty.APPEND -> Color(0xFFE77FD2)
    null -> Color.Gray
}

/** small pill: "MASTER 30" in the difficulty's color */
@Composable
fun DifficultyChip(d: Difficulty?, level: Int?, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(50), color = difficultyColor(d), modifier = modifier.padding(end = 6.dp)) {
        Text(
            "${d?.label ?: "?"}${level?.let { " $it" } ?: ""}",
            color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** "FC" / "AP" mark */
@Composable
fun ClearMark(fullCombo: Boolean, allPerfect: Boolean) {
    if (!fullCombo && !allPerfect) return
    val (text, bg, fg) = if (allPerfect) Triple("AP", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
    else Triple("FC", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
    Surface(shape = RoundedCornerShape(6.dp), color = bg) {
        Text(text, color = fg, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
    }
}

/** accuracy in large type with the clear mark under it */
@Composable
fun AccuracyBadge(accuracy: Double?, fullCombo: Boolean, allPerfect: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(pct(accuracy), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        ClearMark(fullCombo, allPerfect)
    }
}

/** P / G / GD / B / M counts in the game's judgment colors */
@Composable
fun JudgmentLine(j: Judgments, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(
            Triple("P", j.perfect, Color(0xFFE76FD0)), Triple("G", j.great, Color(0xFFF37AA8)), Triple("GD", j.good, Color(0xFF3FA8F5)),
            Triple("B", j.bad, Color(0xFF3FB878)), Triple("M", j.miss, Color(0xFF8A8FA3)),
        ).forEach { (label, n, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black)
                Text(" $n", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** label over a big value, on a tinted tile */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** section title with an optional action on the right */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** a titled group of settings on one card */
@Composable
fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

/** a setting with a switch on the right */
@Composable
fun SwitchRow(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onChange)
    }
}

fun pct(v: Double?) = v?.let { String.format(Locale.US, "%.2f%%", it) } ?: "-"

fun dateTime(t: Long): String = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(t))

suspend fun decodeImage(context: Context, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
}
