package io.github.northster.sekaitracker.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import io.github.northster.sekaitracker.data.Stats
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
import io.github.northster.sekaitracker.data.JacketStore
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
                        val parsed = ResultPipeline.withBetterTitle(ResultParser.parseResult(boxes, bmp.height), bmp, boxes)
                        val o = withContext(Dispatchers.IO) {
                            ResultPipeline.handle(context, parsed, bmp, Source.IMPORT).also { JacketStore.saveFromResult(parsed.title, bmp, boxes) }
                        }
                        when (o) {
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

    val summary = dbState(Triple(0, 0, 0 to 0)) {
        val saved = RecordDb.saved()
        val charts = Stats.allCharts(saved, Settings.current.weights)
        Triple(saved.size, charts.size, charts.count { it.fullCombo && !it.allPerfect } to charts.count { it.allPerfect })
    }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text("세카이 기록", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("프로젝트 세카이 플레이 기록", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (connected) item {
            val active = inGame && settings.autoStart
            val scheme = MaterialTheme.colorScheme
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
                Box(Modifier.fillMaxWidth().background(
                    if (active) Brush.linearGradient(listOf(scheme.primary, scheme.tertiary))
                    else Brush.linearGradient(listOf(scheme.surfaceContainerHigh, scheme.surfaceContainerHigh)),
                )) {
                    val fg = if (active) scheme.onPrimary else scheme.onSurface
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (active) Icons.Default.PlayArrow else Icons.Default.Notifications, null, tint = fg)
                            Text(
                                when { !settings.autoStart -> "자동 실행 꺼짐"; inGame -> "게임 실행 중"; else -> "게임을 기다리는 중" },
                                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = fg,
                                modifier = Modifier.weight(1f).padding(start = 10.dp),
                            )
                            Switch(settings.autoStart, { v -> Settings.update { it.copy(autoStart = v) } })
                        }
                        Text(
                            "프로세카가 켜지면 " + (if (settings.autoDetect) "결과 화면을 자동으로 찾고" else "기록 버튼이 뜨고") + ", 꺼지면 멈춰요.",
                            style = MaterialTheme.typography.bodyMedium, color = fg,
                        )
                        Surface(shape = RoundedCornerShape(50), color = fg.copy(alpha = 0.14f)) {
                            Text(status, style = MaterialTheme.typography.labelMedium, color = fg, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                        }
                    }
                }
            }
        } else item {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(20.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text("자동 실행 켜기", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(start = 10.dp))
                    }
                    Text(
                        "접근성에서 '세카이 기록'을 켜면 프로세카를 켤 때 자동으로 시작하고 끄면 멈춰요. 매번 화면 공유를 허용할 필요도 없어요. " +
                            "어떤 앱이 앞에 있는지와 화면 사진만 써요.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Button(onClick = { context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("접근성 설정 열기") }
                    Text(
                        "설치된 앱 > 세카이 기록 > 켜기. 회색으로 막혀 있으면 설정 > 애플리케이션 > 세카이 기록 > 오른쪽 위 ⋮ > '제한된 설정 허용' 후 다시 켜세요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f))
                    Text(
                        if (running) "화면 공유로 실행 중 · $status" else "또는 지금만 화면 공유로 시작 (게임을 끄고 켜도 따라가지 않아요)",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    if (running) OutlinedButton(onClick = onStop) { Text("화면 공유 중지") } else OutlinedButton(onClick = onStart) { Text("화면 공유로 시작") }
                }
            }
        }
        if (!connected && !canOverlay) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("화면 공유 방식에서 결과 창을 띄우려면 '다른 앱 위에 표시' 권한이 필요해요", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = {
                        context.startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                    }) { Text("허용") }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("플레이", "${summary.first}", Modifier.weight(1f))
                StatTile("곡·난이도", "${summary.second}", Modifier.weight(1f))
                StatTile("FC · AP", "${summary.third.first} · ${summary.third.second}", Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(enabled = !importing, onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Text(if (importing) "  읽는 중…" else "  스크린샷 가져오기")
                }
                FilledTonalButton(onClick = { onOpen(Route.OcrTest) }) {
                    Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                    Text("  인식 테스트")
                }
            }
        }
        if (pending.isNotEmpty()) {
            item { SectionHeader("확인이 필요한 기록 ${pending.size}개") }
            items(pending, key = { "p${it.id}" }) { r ->
                RecordRow(r, Modifier.clickable { onOpen(Route.Edit(r.id)) }, warning = r.issues.lineSequence().firstOrNull())
            }
        }
        item { SectionHeader("최근 기록") }
        if (recent.isEmpty()) item {
            Text("아직 기록이 없어요. 프로세카를 켜고 한 곡 플레이해 보세요.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
        }
        items(recent, key = { "r${it.id}" }) { r ->
            RecordRow(r, Modifier.clickable { onOpen(Route.Chart(r.title, r.difficulty)) })
        }
    }
}

@Composable
fun RecordRow(r: PlayRecord, modifier: Modifier = Modifier, warning: String? = null) {
    val weights by Settings.flow.collectAsState()
    Card(
        modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (warning != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Jacket(r.title, 56.dp, Modifier.padding(end = 12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(r.title.ifBlank { "(곡 이름 없음)" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DifficultyChip(r.difficulty, r.level)
                    Text(dateTime(r.playedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                JudgmentLine(r.judgments)
                warning?.let { Text(it, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall) }
            }
            AccuracyBadge(weights.weights.accuracy(r.judgments), r.fullCombo, r.allPerfect, Modifier.padding(start = 8.dp))
        }
    }
}
