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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import io.github.northster.sekaitracker.capture.ScreenReader
import io.github.northster.sekaitracker.capture.SekaiAccessibilityService
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
    val status by ScreenReader.status.collectAsState()
    val connected by SekaiAccessibilityService.connected.collectAsState()
    val inGame by SekaiAccessibilityService.gameActive.collectAsState()
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
                    ScreenKind.LIVE_PREPARE, ScreenKind.OTHER -> "결과 화면을 찾지 못했어요"
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
        if (connected) item {
            Card(colors = CardDefaults.cardColors(
                containerColor = if (inGame && settings.autoStart) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp).fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when { !settings.autoStart -> "자동 실행 꺼짐"; inGame -> "게임 실행 중"; else -> "게임을 기다리는 중" },
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                        )
                        Switch(settings.autoStart, { v -> Settings.update { it.copy(autoStart = v) } })
                    }
                    Text(
                        "프로세카가 켜지면 " + (if (settings.autoDetect) "결과 화면을 자동으로 찾고" else "기록 버튼이 뜨고") +
                            ", 꺼지면 멈춰요. 화면 공유 허용은 필요 없어요.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else item {
            Card {
                Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("자동 실행 켜기", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "접근성에서 '세카이 기록'을 켜면 프로세카를 켤 때 자동으로 시작하고 끄면 멈춰요. 매번 화면 공유를 허용할 필요도 없어요. " +
                            "어떤 앱이 앞에 있는지와 버튼을 눌렀을 때의 화면 사진만 써요.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("접근성 설정 열기") }
                    Text(
                        "설치된 앱 > 세카이 기록 > 켜기. 회색으로 막혀 있으면 설정 > 애플리케이션 > 세카이 기록 > 오른쪽 위 ⋮ > '제한된 설정 허용' 후 다시 켜세요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Text(
                        if (running) "화면 공유로 실행 중 · $status" else "또는 지금만 화면 공유로 시작 (게임을 끄고 켜도 자동으로 따라가지 않아요)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (running) OutlinedButton(onClick = onStop) { Text("화면 공유 중지") } else OutlinedButton(onClick = onStart) { Text("화면 공유로 시작") }
                }
            }
        }
        if (!connected && !canOverlay) item {
            Card {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("화면 공유 방식에서 버튼과 결과 창을 띄우려면 '다른 앱 위에 표시' 권한이 필요해요", Modifier.weight(1f))
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
