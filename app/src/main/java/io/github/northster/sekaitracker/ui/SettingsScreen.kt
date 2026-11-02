package io.github.northster.sekaitracker.ui

import android.content.Intent
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.northster.sekaitracker.capture.ForegroundApp
import io.github.northster.sekaitracker.capture.SekaiAccessibilityService
import io.github.northster.sekaitracker.data.AppSettings
import io.github.northster.sekaitracker.data.CaptureQuality
import io.github.northster.sekaitracker.data.Backup
import io.github.northster.sekaitracker.data.Csv
import io.github.northster.sekaitracker.data.OcrLanguage
import io.github.northster.sekaitracker.data.OverlayCorner
import io.github.northster.sekaitracker.data.RecordDb
import io.github.northster.sekaitracker.data.ReviewMode
import io.github.northster.sekaitracker.data.Settings
import io.github.northster.sekaitracker.data.Weights
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier, onOpen: (Route) -> Unit) {
    val context = LocalContext.current
    val s by Settings.flow.collectAsState()
    val scope = rememberCoroutineScope()
    var resumeCount by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeCount++ }
    val usageAccess = remember(resumeCount) { ForegroundApp.hasPermission(context) }
    val songCount = dbState(0) { RecordDb.songCount() }

    val backupSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)!!.use { it.write(Backup.export().toByteArray()) } }.isSuccess
            }
            Toast.makeText(context, if (ok) "백업 파일을 만들었어요" else "백업하지 못했어요", Toast.LENGTH_SHORT).show()
        }
    }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri = uri }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)!!.use { it.write(Csv.export(RecordDb.saved(), Settings.current.weights).toByteArray()) }
                }.isSuccess
            }
            Toast.makeText(context, if (ok) "내보냈어요" else "내보내지 못했어요", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("설정", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        SettingsGroup("화면 인식") {
            SwitchRow("게임 켜지면 자동 실행", "접근성 서비스가 켜져 있으면 프로세카가 화면에 있을 때만 동작하고, 꺼지면 멈춰요", s.autoStart) { v ->
                Settings.update { it.copy(autoStart = v) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("읽는 방식", style = MaterialTheme.typography.bodyLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(true to "자동 인식", false to "버튼으로만").forEachIndexed { i, (value, label) ->
                    SegmentedButton(
                        selected = s.autoDetect == value, onClick = { Settings.update { it.copy(autoDetect = value) } },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                    ) { Text(label) }
                }
            }
            Text(
                if (s.autoDetect) "게임 중에 일정 간격으로 화면을 한 장씩 보고, 멈춘 새 화면일 때만 글자를 읽어요. 결과·곡 선택 화면을 알아서 찾아요."
                else "떠 있는 버튼(또는 빠른 설정 타일, 알림의 [지금 캡처])을 누를 때만 화면을 한 장 읽어요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (s.autoDetect) {
                Text("확인 간격", style = MaterialTheme.typography.bodyMedium)
                Choices(listOf(1000L, 2000L, 3000L, 5000L), s.intervalMs, { "${it / 1000}초" }) { v -> Settings.update { it.copy(intervalMs = v) } }
            }
            SwitchRow("떠 있는 캡처 버튼", "누르면 지금 화면을 바로 읽어요. 끌어서 옮길 수 있어요", s.showButton) { v ->
                Settings.update { it.copy(showButton = v) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("캡처 해상도", style = MaterialTheme.typography.bodyMedium)
            Choices(CaptureQuality.entries, s.quality, { it.label }) { v -> Settings.update { it.copy(quality = v) } }
            Text("인식 언어", style = MaterialTheme.typography.bodyMedium)
            Choices(OcrLanguage.entries, s.ocrLanguage, { it.label }) { v -> Settings.update { it.copy(ocrLanguage = v) } }
        }

        SettingsGroup("정확도 가중치 (%)") {
            Text("판정 하나가 PERFECT의 몇 %로 계산될지 정해요. 바꾸면 지난 기록도 모두 다시 계산돼요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            WeightsEditor(s.weights) { w -> Settings.update { it.copy(weights = w) } }
        }

        SettingsGroup("오버레이") {
            SwitchRow("결과 오버레이 표시", "정확도, 이전 최고, 판정 옆 +-를 잠깐 보여줘요. 터치는 그대로 게임으로 가요", s.overlayEnabled) { v ->
                Settings.update { it.copy(overlayEnabled = v) }
            }
            Text("위치", style = MaterialTheme.typography.bodyMedium)
            Choices(OverlayCorner.entries, s.overlayCorner, { it.label }) { v -> Settings.update { it.copy(overlayCorner = v) } }
            Text("표시 시간", style = MaterialTheme.typography.bodyMedium)
            Choices(listOf(3, 5, 8, 12), s.overlaySeconds, { "${it}초" }) { v -> Settings.update { it.copy(overlaySeconds = v) } }
        }

        SettingsGroup("저장") {
            Text("저장 전 확인", style = MaterialTheme.typography.bodyMedium)
            Choices(ReviewMode.entries, s.reviewMode, { it.label }) { v -> Settings.update { it.copy(reviewMode = v) } }
            SwitchRow("인식한 화면 이미지 보관", "기록마다 결과 화면 사진을 앱 안에 남겨요 (확인이 필요한 기록은 항상 보관)", s.keepImages) { v ->
                Settings.update { it.copy(keepImages = v) }
            }
        }

        SettingsGroup("게임 앱") {
            Text("이 앱이 화면에 있으면 프로세카로 봐요. 자동 실행이 안 되면 프로세카를 한 번 켰다가 와서 '최근 앱'에서 눌러 추가하세요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            var packages by remember { mutableStateOf(s.gamePackages.joinToString("\n")) }
            // added from a chip or reset: show it in the field (typing keeps its own text, line breaks included)
            LaunchedEffect(s.gamePackages) {
                if (packages.split('\n', ',').map(String::trim).filter(String::isNotEmpty) != s.gamePackages) packages = s.gamePackages.joinToString("\n")
            }
            OutlinedTextField(packages, { packages = it; Settings.update { st -> st.copy(gamePackages = it.split('\n', ',').map(String::trim).filter(String::isNotEmpty)) } },
                Modifier.fillMaxWidth(), label = { Text("게임 패키지 이름 (한 줄에 하나)") }, shape = RoundedCornerShape(12.dp))
            val recent by SekaiAccessibilityService.recentPackages.collectAsState()
            val addable = recent.filter { it !in s.gamePackages }
            if (addable.isNotEmpty()) {
                Text("최근 앱 (눌러서 추가)", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    addable.forEach { pkg -> SuggestionChip(onClick = { Settings.update { it.copy(gamePackages = it.gamePackages + pkg) } }, label = { Text(pkg) }) }
                }
            }
            TextButton(onClick = { Settings.update { it.copy(gamePackages = AppSettings.DEFAULT_GAME_PACKAGES) } }) { Text("기본값으로") }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SwitchRow("화면 공유 방식: 게임 중에만 동작", "접근성 서비스 없이 화면 공유로 쓸 때만 해당돼요. '사용 정보 접근' 권한이 필요해요", s.onlyInGame) { v ->
                Settings.update { it.copy(onlyInGame = v) }
                if (v && !usageAccess) context.startActivity(Intent(AndroidSettings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            if (s.onlyInGame && !usageAccess) TextButton(onClick = { context.startActivity(Intent(AndroidSettings.ACTION_USAGE_ACCESS_SETTINGS)) }) {
                Text("사용 정보 접근 권한 허용하기")
            }
            if (s.autoDetect) SwitchRow("화면 공유 방식: 확인할 때만 화면 가져오기", "확인 사이에는 화면 미러링을 멈춰서 배터리를 아껴요 (다시 시작하면 적용)", s.pauseMirroring) { v ->
                Settings.update { it.copy(pauseMirroring = v) }
            }
        }

        SettingsGroup("화면") {
            SwitchRow("기기 배경화면 색 사용", "앱 색을 배경화면에서 가져와요 (끄면 세카이 청록 테마)", s.dynamicColor) { v ->
                Settings.update { it.copy(dynamicColor = v) }
            }
        }

        SettingsGroup("데이터") {
            Text("곡 선택 화면에서 배운 곡 ${songCount}곡", style = MaterialTheme.typography.bodyMedium)
            ActionRow(Icons.Default.Share, "CSV 내보내기", "엑셀에서 열 수 있는 표로 저장") {
                exporter.launch("sekai-records-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".csv")
            }
            ActionRow(Icons.Default.Done, "백업 파일 만들기", "기록·배운 곡·곡 이름 수정·설정 (화면 이미지 제외)") {
                backupSaver.launch("sekai-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".json")
            }
            ActionRow(Icons.Default.Refresh, "백업에서 불러오기", "지금 기록에 더해지고, 이미 있는 판은 건너뛰어요") {
                backupPicker.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
            }
            ActionRow(Icons.Default.Search, "인식 테스트", "샘플이나 고른 이미지로 글자 인식 확인") { onOpen(Route.OcrTest) }
            ActionRow(Icons.Default.Delete, "배운 곡 목록 지우기", null) { RecordDb.clearSongs() }
        }

        Text("화면 캡처만 사용하고 게임 데이터·메모리는 읽거나 바꾸지 않아요. 글자 인식은 기기 안에서만 하고 인터넷을 쓰지 않아요.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
    }
    restoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { restoreUri = null },
            title = { Text("백업에서 불러오기") },
            text = { Text("기록은 지금 기록에 더해져요. 설정(가중치, 오버레이 등)도 백업 때 값으로 바꿀까요?") },
            confirmButton = { TextButton(onClick = { restoreUri = null; restore(context, scope, uri, true) }) { Text("설정도 불러오기") } },
            dismissButton = { TextButton(onClick = { restoreUri = null; restore(context, scope, uri, false) }) { Text("기록만") } },
        )
    }
}

private fun restore(context: android.content.Context, scope: kotlinx.coroutines.CoroutineScope, uri: Uri, withSettings: Boolean) {
    scope.launch {
        val message = withContext(Dispatchers.IO) {
            runCatching {
                val json = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                val r = Backup.restore(json, withSettings)
                "기록 ${r.added}개를 불러왔어요" + (if (r.skipped > 0) " (이미 있던 ${r.skipped}개는 건너뜀)" else "") +
                    (if (r.settingsRestored) " · 설정도 불러옴" else "")
            }.getOrElse { (it as? IllegalArgumentException)?.message ?: "불러오지 못했어요: ${it.message}" }
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}

/** a tappable row with an icon, for actions */
@Composable
private fun ActionRow(icon: ImageVector, title: String, summary: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Icon(icon, null, Modifier.padding(8.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) }) }
    }
}

@Composable
private fun WeightsEditor(weights: Weights, onChange: (Weights) -> Unit) {
    val fields = listOf("PERFECT" to weights.perfect, "GREAT" to weights.great, "GOOD" to weights.good, "BAD" to weights.bad, "MISS" to weights.miss)
    val texts = remember { fields.map { mutableStateOf(trim(it.second)) } }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        fields.forEachIndexed { i, (label, _) ->
            OutlinedTextField(texts[i].value, { v ->
                texts[i].value = v.filter { it.isDigit() || it == '.' }
                val n = texts.map { it.value.toDoubleOrNull() }
                if (n.all { it != null && it in 0.0..100.0 }) onChange(Weights(n[0]!!, n[1]!!, n[2]!!, n[3]!!, n[4]!!))
            }, Modifier.weight(1f), label = { Text(label, maxLines = 1, style = MaterialTheme.typography.labelSmall) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(12.dp))
        }
    }
    TextButton(onClick = {
        val d = Weights()
        listOf(d.perfect, d.great, d.good, d.bad, d.miss).forEachIndexed { i, v -> texts[i].value = trim(v) }
        onChange(d)
    }) { Text("기본값 (100 / 70 / 50 / 0 / 0)") }
}

private fun trim(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()
