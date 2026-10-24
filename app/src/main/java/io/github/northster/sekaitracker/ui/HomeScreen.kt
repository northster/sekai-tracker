package io.github.northster.sekaitracker.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.northster.sekaitracker.capture.CaptureService
import io.github.northster.sekaitracker.capture.ResultPipeline
import io.github.northster.sekaitracker.data.PlayRecord
import io.github.northster.sekaitracker.data.RecordDb
import io.github.northster.sekaitracker.data.Settings
import io.github.northster.sekaitracker.data.Source
import io.github.northster.sekaitracker.ocr.Recognizer
import io.github.northster.sekaitracker.ocr.ResultParser
import io.github.northster.sekaitracker.ocr.ScreenKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(modifier: Modifier, onStart: () -> Unit, onStop: () -> Unit, onOpen: (Route) -> Unit) {
    val context = LocalContext.current
    val running by CaptureService.running.collectAsState()
    val status by CaptureService.status.collectAsState()
    val settings by Settings.flow.collectAsState()
    val pending = dbState(emptyList<PlayRecord>()) { RecordDb.pending() }
    val recent = dbState(emptyList<PlayRecord>()) { RecordDb.saved().take(20) }
    var resumeCount by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeCount++ }
    val canOverlay = remember(resumeCount) { AndroidSettings.canDrawOverlays(context) }
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val message = runCatching {
                val bmp = decodeImage(context, uri)
                val boxes = Recognizer.read(bmp, Settings.current.ocrLanguage)
                when (ResultParser.classify(boxes)) {
                    ScreenKind.RESULT -> {
                        val parsed = ResultParser.parseResult(boxes, bmp.height)
                        when (val o = withContext(Dispatchers.IO) { ResultPipeline.handle(context, parsed, bmp, Source.IMPORT) }) {
                            is ResultPipeline.Outcome.NeedsReview -> { onOpen(Route.Edit(o.record.id)); null }
                            is ResultPipeline.Outcome.Duplicate -> "이미 저장된 결과예요"
                            is ResultPipeline.Outcome.Saved -> "저장했어요"
                        }
                    }
                    ScreenKind.SONG_SELECT -> {
                        ResultParser.parseSongSelect(boxes, bmp.height)?.let { withContext(Dispatchers.IO) { ResultPipeline.onSongSelect(it) } }
                        "곡 선택 화면에서 곡 정보를 읽었어요"
                    }
                    ScreenKind.OTHER -> "결과 화면을 찾지 못했어요"
                }
            }.getOrElse { "읽지 못했어요: ${it.message}" }
            importing = false
            message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
        }
    }

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("세카이 기록", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))
        }
        item {
            Card(colors = CardDefaults.cardColors(
                containerColor = if (running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp).fillMaxWidth()) {
                    Text(if (running) "인식 중" else "꺼져 있음", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        if (!running) "시작하면 게임 위에 작은 버튼이 떠요. 결과 화면에서 누르면 기록하고, 곡 선택 화면에서 누르면 이전 최고 기록을 보여줘요. 화면 캡처만 쓰고 게임 데이터는 건드리지 않아요."
                        else if (settings.autoDetect) "자동: ${settings.intervalMs / 1000}초마다 화면을 확인 · $status"
                        else "버튼을 누를 때만 화면을 읽어요 · $status",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.padding(4.dp))
                    if (running) OutlinedButton(onClick = onStop) { Text("중지") } else Button(onClick = onStart) { Text("시작") }
                }
            }
        }
        if (!canOverlay) item {
            Card {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("결과 오버레이를 보려면 '다른 앱 위에 표시' 권한이 필요해요", Modifier.weight(1f))
                    TextButton(onClick = {
                        context.startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                    }) { Text("허용") }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !importing, onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text(if (importing) "읽는 중…" else "스크린샷 가져오기") }
                OutlinedButton(onClick = { onOpen(Route.OcrTest) }) { Text("인식 테스트") }
            }
        }
        if (pending.isNotEmpty()) {
            item { Text("확인이 필요한 기록 ${pending.size}개", style = MaterialTheme.typography.titleMedium) }
            items(pending, key = { "p${it.id}" }) { r ->
                RecordRow(r, Modifier.clickable { onOpen(Route.Edit(r.id)) }, warning = r.issues.lineSequence().firstOrNull())
            }
        }
        item { Text("최근 기록", style = MaterialTheme.typography.titleMedium) }
        if (recent.isEmpty()) item { Text("아직 기록이 없어요", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(recent, key = { "r${it.id}" }) { r ->
            RecordRow(r, Modifier.clickable { onOpen(Route.Chart(r.title, r.difficulty)) })
        }
        item { Spacer(Modifier.padding(8.dp)) }
    }
}

@Composable
fun RecordRow(r: PlayRecord, modifier: Modifier = Modifier, warning: String? = null) {
    val weights by Settings.flow.collectAsState()
    Card(modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.title.ifBlank { "(곡 이름 없음)" }, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DifficultyChip(r.difficulty, r.level)
                    Text(dateTime(r.playedAt), style = MaterialTheme.typography.bodySmall)
                }
                val j = r.judgments
                Text("${j.perfect} / ${j.great} / ${j.good} / ${j.bad} / ${j.miss}",
                    style = MaterialTheme.typography.bodySmall)
                warning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(pct(weights.weights.accuracy(r.judgments)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                when {
                    r.allPerfect -> Text("AP", color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
                    r.fullCombo -> Text("FC", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
