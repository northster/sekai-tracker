package io.github.northster.sekaitracker.ui

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.northster.sekaitracker.capture.ResultPipeline
import io.github.northster.sekaitracker.data.Difficulty
import io.github.northster.sekaitracker.data.Judgments
import io.github.northster.sekaitracker.data.PlayRecord
import io.github.northster.sekaitracker.data.RecordDb
import io.github.northster.sekaitracker.data.Settings
import io.github.northster.sekaitracker.ocr.Recognizer
import io.github.northster.sekaitracker.ocr.TextNorm
import io.github.northster.sekaitracker.ocr.TitleMatcher
import io.github.northster.sekaitracker.ocr.TitleRetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Check a recognized result before it is saved, or fix a saved one. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun EditScreen(id: Long, onDone: () -> Unit) {
    var record by remember { mutableStateOf<PlayRecord?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(id) { record = withContext(Dispatchers.IO) { RecordDb.get(id) }; loaded = true }
    val r = record
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TopAppBar(
            title = { Text(if (r?.pending == true) "기록 확인" else "기록 수정") },
            navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Default.ArrowBack, "뒤로") } },
        )
        if (r == null) {
            if (loaded) Text("기록을 찾을 수 없어요", Modifier.padding(16.dp))
            return@Column
        }
        EditForm(r, onDone)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EditForm(r: PlayRecord, onDone: () -> Unit) {
    val settings by Settings.flow.collectAsState()
    var title by remember { mutableStateOf(r.title) }
    var difficulty by remember { mutableStateOf(r.difficulty) }
    var level by remember { mutableStateOf(r.level?.toString().orEmpty()) }
    var combo by remember { mutableStateOf(r.combo?.toString().orEmpty()) }
    var perfect by remember { mutableStateOf(r.judgments.perfect.toString()) }
    var great by remember { mutableStateOf(r.judgments.great.toString()) }
    var good by remember { mutableStateOf(r.judgments.good.toString()) }
    var bad by remember { mutableStateOf(r.judgments.bad.toString()) }
    var miss by remember { mutableStateOf(r.judgments.miss.toString()) }
    var fast by remember { mutableStateOf(r.fast?.toString().orEmpty()) }
    var late by remember { mutableStateOf(r.late?.toString().orEmpty()) }
    val known = dbState(emptySet<String>()) { RecordDb.titles() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rereading by remember { mutableStateOf(false) }
    val image = remember(r.imagePath) { r.imagePath?.let { runCatching { BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull() } }

    fun int(s: String) = s.trim().toIntOrNull()
    val judgments = Judgments(int(perfect) ?: 0, int(great) ?: 0, int(good) ?: 0, int(bad) ?: 0, int(miss) ?: 0)
    val valid = title.isNotBlank() && listOf(perfect, great, good, bad, miss).all { int(it) != null && int(it)!! >= 0 } && judgments.total > 0

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (r.issues.isNotBlank()) Text(r.issues, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        image?.let { Image(it, "인식한 화면", Modifier.fillMaxWidth().heightIn(max = 260.dp), contentScale = ContentScale.Fit) }

        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("곡 이름") }, singleLine = true)
        val suggestions = remember(title, known) {
            if (title.isBlank()) emptyList() else known.filter { it != title }
                .sortedByDescending { TextNorm.titleSimilarity(title, it) }.take(3).filter { TextNorm.titleSimilarity(title, it) > 0.3 }
        }
        if (suggestions.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            suggestions.forEach { s -> SuggestionChip(onClick = { title = s }, label = { Text(s) }) }
        }
        if (r.ocrTitle.isNotBlank() && r.ocrTitle != title) Text("인식한 글자: ${r.ocrTitle}", style = MaterialTheme.typography.bodySmall)
        if (r.imagePath != null) {
            OutlinedButton(enabled = !rereading, onClick = {
                rereading = true
                scope.launch {
                    val again = withContext(Dispatchers.Default) {
                        runCatching {
                            val bmp = BitmapFactory.decodeFile(r.imagePath) ?: return@runCatching null
                            val lang = Settings.current.ocrLanguage
                            TitleRetry.read(bmp, Recognizer.read(bmp, lang), lang)
                        }.getOrNull()
                    }
                    rereading = false
                    if (again == null) Toast.makeText(context, "곡 이름을 찾지 못했어요. 아래 목록에서 골라 주세요", Toast.LENGTH_SHORT).show()
                    else title = withContext(Dispatchers.IO) { TitleMatcher.match(again, RecordDb.titles(), RecordDb.aliases()).title }
                }
            }) { Text(if (rereading) "읽는 중…" else "곡 이름 다시 읽기") }
        }
        // pick instead of typing: songs with this difficulty's level (learned on the song select screen), recent songs
        val atLevel = dbState(emptyList<String>(), difficulty, level) {
            val d = difficulty; val l = level.trim().toIntOrNull()
            if (d != null && l != null) RecordDb.songsAt(d, l) else emptyList()
        }
        val recentTitles = dbState(emptyList<String>()) {
            (listOfNotNull(ResultPipeline.selectedSong()) + RecordDb.recentTitles(10)).distinct()
        }
        if (atLevel.isNotEmpty()) {
            Text("${difficulty?.label} $level 곡", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                atLevel.forEach { t -> FilterChip(selected = t == title, onClick = { title = t }, label = { Text(t) }) }
            }
        }
        if (recentTitles.isNotEmpty()) {
            Text("최근 곡", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                recentTitles.forEach { t -> FilterChip(selected = t == title, onClick = { title = t }, label = { Text(t) }) }
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Difficulty.entries.forEach { d -> FilterChip(selected = difficulty == d, onClick = { difficulty = d }, label = { Text(d.label) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("레벨", level, Modifier.weight(1f)) { level = it }
            NumberField("콤보", combo, Modifier.weight(1.3f)) { combo = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("PERFECT", perfect, Modifier.weight(1.3f)) { perfect = it }
            NumberField("GREAT", great, Modifier.weight(1f)) { great = it }
            NumberField("GOOD", good, Modifier.weight(1f)) { good = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("BAD", bad, Modifier.weight(1f)) { bad = it }
            NumberField("MISS", miss, Modifier.weight(1f)) { miss = it }
            NumberField("FAST", fast, Modifier.weight(1f)) { fast = it }
            NumberField("LATE", late, Modifier.weight(1f)) { late = it }
        }
        Text(
            "정확도 ${pct(settings.weights.accuracy(judgments))} · 노트 ${judgments.total}" +
                (if (judgments.isAllPerfect) " · ALL PERFECT" else if (judgments.isFullCombo) " · FULL COMBO" else ""),
            style = MaterialTheme.typography.titleMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = valid, onClick = {
                val updated = r.copy(
                    title = title.trim(), difficulty = difficulty, level = int(level),
                    combo = int(combo), judgments = judgments, fast = int(fast), late = int(late),
                )
                if (r.pending) ResultPipeline.confirm(updated) else {
                    if (updated.title != r.title && r.ocrTitle.isNotBlank()) RecordDb.addAlias(r.ocrTitle, updated.title)
                    RecordDb.update(updated)
                }
                onDone()
            }) { Text(if (r.pending) "저장" else "수정 저장") }
            OutlinedButton(onClick = { RecordDb.delete(r); onDone() }) { Text(if (r.pending) "버리기" else "삭제") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(value, { v -> onChange(v.filter { it.isDigit() }) }, modifier, label = { Text(label, maxLines = 1) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}
