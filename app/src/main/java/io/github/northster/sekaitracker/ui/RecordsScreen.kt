package io.github.northster.sekaitracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.northster.sekaitracker.data.ChartStats
import io.github.northster.sekaitracker.data.Difficulty
import io.github.northster.sekaitracker.data.PlayRecord
import io.github.northster.sekaitracker.data.RecordDb
import io.github.northster.sekaitracker.data.Settings
import io.github.northster.sekaitracker.data.Stats
import io.github.northster.sekaitracker.ocr.TextNorm
import java.util.Locale

private enum class Sort(val label: String) { RECENT("최근"), ACCURACY("정확도"), LEVEL("레벨"), TITLE("곡명") }

/** list of songs; on a wide screen (Fold inner screen) the selected song's chart is shown next to it */
@Composable
fun RecordsScreen(modifier: Modifier, onOpen: (Route) -> Unit) {
    val settings by Settings.flow.collectAsState()
    val all = dbState(emptyList<PlayRecord>()) { RecordDb.saved() }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(Sort.RECENT) }
    var selected by remember { mutableStateOf<Pair<String, Difficulty?>?>(null) }
    val charts = remember(all, settings.weights, query, sort) {
        Stats.allCharts(all, settings.weights)
            .filter { query.isBlank() || TextNorm.titleKey(it.title).contains(TextNorm.titleKey(query)) }
            .let { list ->
                when (sort) {
                    Sort.RECENT -> list
                    Sort.ACCURACY -> list.sortedByDescending { it.bestAccuracy ?: 0.0 }
                    Sort.LEVEL -> list.sortedWith(compareByDescending<ChartStats> { it.level ?: 0 }.thenBy { it.title })
                    Sort.TITLE -> list.sortedBy { it.title }
                }
            }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 700.dp
        val listWidth = maxWidth * 0.42f
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.then(if (wide) Modifier.width(listWidth) else Modifier.fillMaxWidth()).fillMaxHeight().padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("곡 이름 검색") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Sort.entries.forEach { s -> FilterChip(selected = sort == s, onClick = { sort = s }, label = { Text(s.label) }) }
                }
                Text("${charts.size}곡 · ${all.size}플레이", style = MaterialTheme.typography.bodySmall)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    items(charts, key = { "${it.title}|${it.difficulty}" }) { c ->
                        ChartRow(c, Modifier.clickable {
                            if (wide) selected = c.title to c.difficulty else onOpen(Route.Chart(c.title, c.difficulty))
                        })
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
            if (wide) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    val sel = selected ?: charts.firstOrNull()?.let { it.title to it.difficulty }
                    if (sel != null) ChartContent(sel.first, sel.second, onEdit = { onOpen(Route.Edit(it)) }, onRenamed = { selected = it to sel.second })
                    else Text("기록이 없어요", Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

@Composable
private fun ChartRow(c: ChartStats, modifier: Modifier) {
    Card(modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DifficultyChip(c.difficulty, c.level)
                    Text("${c.plays}회 · 평균 ${pct(c.averageAccuracy)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(pct(c.bestAccuracy), fontWeight = FontWeight.Bold)
                Row {
                    c.recentTrend?.let { Text(trend(it), style = MaterialTheme.typography.bodySmall) }
                    if (c.allPerfect) Text(" AP", color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
                    else if (c.fullCombo) Text(" FC", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun trend(v: Double) = (if (v >= 0) "▲" else "▼") + String.format(Locale.US, "%.2f", kotlin.math.abs(v))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartScreen(title: String, difficulty: Difficulty?, onBack: () -> Unit, onEdit: (Long) -> Unit, onRenamed: (String) -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TopAppBar(title = { Text(title, maxLines = 1) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "뒤로") }
        })
        ChartContent(title, difficulty, onEdit, onRenamed)
    }
}

@Composable
fun ChartContent(title: String, difficulty: Difficulty?, onEdit: (Long) -> Unit, onRenamed: (String) -> Unit) {
    val settings by Settings.flow.collectAsState()
    val records = dbState(emptyList<PlayRecord>(), title, difficulty) { RecordDb.forChart(title, difficulty) }
    val stats = remember(records, settings.weights) { Stats.forChart(records, settings.weights) }
    var showScore by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, "곡 이름 고치기") }
            }
            DifficultyChip(difficulty, stats?.level)
        }
        if (stats != null) item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatBox("최고", pct(stats.bestAccuracy))
                StatBox("평균", pct(stats.averageAccuracy))
                StatBox("최근 추이", stats.recentTrend?.let { trend(it) + "%p" } ?: "-")
                StatBox("플레이", "${stats.plays}회")
            }
            Text("최고 점수 ${stats.bestScore ?: "-"}" + (if (stats.allPerfect) " · ALL PERFECT" else if (stats.fullCombo) " · FULL COMBO" else ""),
                style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = !showScore, onClick = { showScore = false }, label = { Text("정확도") })
                FilterChip(selected = showScore, onClick = { showScore = true }, label = { Text("점수") })
            }
            val values = records.map { if (showScore) it.score?.toDouble() else settings.weights.accuracy(it.judgments) }
            LineChart(values, Modifier.fillMaxWidth().height(200.dp), percent = !showScore)
        }
        item { Text("플레이 기록 (눌러서 수정)", style = MaterialTheme.typography.titleSmall) }
        items(records.reversed(), key = { it.id }) { r -> RecordRow(r, Modifier.clickable { onEdit(r.id) }) }
        item { Spacer(Modifier.height(16.dp)) }
    }

    if (renaming) {
        var text by remember { mutableStateOf(title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("곡 이름 고치기") },
            text = {
                Column {
                    Text("이 난이도의 모든 기록 이름이 바뀌고, 다음부터 같은 인식 결과는 자동으로 고쳐져요.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(text, { text = it }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    RecordDb.renameChart(title, difficulty, text.trim()); renaming = false; onRenamed(text.trim())
                }) { Text("바꾸기") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun StatBox(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

/** plays in order, with a dashed line at the best value */
@Composable
fun LineChart(values: List<Double?>, modifier: Modifier, percent: Boolean) {
    val points = values.withIndex().filter { it.value != null }.map { it.index to it.value!! }
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val bestColor = MaterialTheme.colorScheme.tertiary
    if (points.isEmpty()) { Box(modifier) { Text("기록이 없어요", Modifier.align(Alignment.Center)) }; return }
    val maxV = points.maxOf { it.second }
    val minV = points.minOf { it.second }
    val (lo, hi) = if (percent) {
        val lo = (minOf(minV, maxV - 1.0) - 0.5).coerceAtLeast(0.0)
        lo to minOf(100.0, maxOf(maxV + 0.5, lo + 1.0))
    } else {
        val pad = maxOf((maxV - minV) * 0.1, 1.0)
        (minV - pad) to (maxV + pad)
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            Text(if (percent) String.format(Locale.US, "%.1f%%", hi) else hi.toLong().toString(), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text("최고 " + if (percent) pct(maxV) else maxV.toLong().toString(), style = MaterialTheme.typography.labelSmall, color = bestColor)
        }
        Canvas(Modifier.fillMaxWidth().weight(1f).padding(vertical = 4.dp)) {
            val n = values.size
            fun x(i: Int) = if (n <= 1) size.width / 2 else size.width * i / (n - 1)
            fun y(v: Double) = (size.height * (1 - (v - lo) / (hi - lo))).toFloat()
            for (k in 0..4) {
                val gy = size.height * k / 4
                drawLine(grid, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
            }
            drawLine(bestColor, Offset(0f, y(maxV)), Offset(size.width, y(maxV)), strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
            val path = Path()
            points.forEachIndexed { k, (i, v) -> if (k == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
            drawPath(path, line, style = Stroke(width = 4f))
            points.forEach { (i, v) -> drawCircle(line, radius = 6f, center = Offset(x(i), y(v))) }
        }
        Text(if (percent) String.format(Locale.US, "%.1f%%", lo) else lo.toLong().toString(), style = MaterialTheme.typography.labelSmall)
    }
}
