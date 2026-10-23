package io.github.northster.sekaitracker.ui

import android.content.Intent
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.northster.sekaitracker.capture.ForegroundApp
import io.github.northster.sekaitracker.data.AppSettings
import io.github.northster.sekaitracker.data.CaptureQuality
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(modifier: Modifier, onOpen: (Route) -> Unit) {
    val context = LocalContext.current
    val s by Settings.flow.collectAsState()
    val scope = rememberCoroutineScope()
    var resumeCount by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeCount++ }
    val usageAccess = remember(resumeCount) { ForegroundApp.hasPermission(context) }
    val songCount = dbState(0) { RecordDb.songCount() }

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

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.height(8.dp))
        Section("정확도 가중치 (%)")
        Text("판정 하나가 PERFECT의 몇 %로 계산될지 정해요. 바꾸면 지난 기록도 모두 다시 계산돼요.", style = MaterialTheme.typography.bodySmall)
        WeightsEditor(s.weights) { w -> Settings.update { it.copy(weights = w) } }

        Section("화면 인식")
        Toggle("결과 화면 자동 감지", "끄면 빠른 설정 타일이나 알림의 [지금 캡처]를 눌렀을 때만 인식해요", s.autoDetect) { v -> Settings.update { it.copy(autoDetect = v) } }
        Text("확인 간격", style = MaterialTheme.typography.bodyMedium)
        Choices(listOf(500L, 1000L, 2000L, 3000L, 5000L), s.intervalMs, { "${it / 1000.0}초" }) { v -> Settings.update { it.copy(intervalMs = v) } }
        Text("캡처 해상도 (다시 시작하면 적용)", style = MaterialTheme.typography.bodyMedium)
        Choices(CaptureQuality.entries, s.quality, { it.label }) { v -> Settings.update { it.copy(quality = v) } }
        Toggle("확인할 때만 화면 가져오기", "확인 사이에는 화면 미러링을 멈춰서 배터리와 발열을 줄여요 (다시 시작하면 적용)", s.pauseMirroring) { v ->
            Settings.update { it.copy(pauseMirroring = v) }
        }
        Text("인식 언어", style = MaterialTheme.typography.bodyMedium)
        Choices(OcrLanguage.entries, s.ocrLanguage, { it.label }) { v -> Settings.update { it.copy(ocrLanguage = v) } }

        Toggle("게임 실행 중에만 확인", "다른 앱을 쓸 때는 화면을 보지 않아요. '사용 정보 접근' 권한이 필요해요", s.onlyInGame) { v ->
            Settings.update { it.copy(onlyInGame = v) }
            if (v && !usageAccess) context.startActivity(Intent(AndroidSettings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        if (s.onlyInGame) {
            if (!usageAccess) TextButton(onClick = { context.startActivity(Intent(AndroidSettings.ACTION_USAGE_ACCESS_SETTINGS)) }) {
                Text("사용 정보 접근 권한 허용하기")
            }
            var packages by remember { mutableStateOf(s.gamePackages.joinToString("\n")) }
            OutlinedTextField(packages, { packages = it; Settings.update { st -> st.copy(gamePackages = it.split('\n', ',').map(String::trim).filter(String::isNotEmpty)) } },
                Modifier.fillMaxWidth(), label = { Text("게임 패키지 이름 (한 줄에 하나)") })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("마지막으로 본 앱: ${ForegroundApp.lastPackage ?: "-"}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { packages = AppSettings.DEFAULT_GAME_PACKAGES.joinToString("\n"); Settings.update { it.copy(gamePackages = AppSettings.DEFAULT_GAME_PACKAGES) } }) {
                    Text("기본값")
                }
            }
        }

        Section("오버레이")
        Toggle("결과 오버레이 표시", "결과 화면에서 정확도와 최고 기록을 잠깐 보여줘요. 터치는 그대로 게임으로 가요", s.overlayEnabled) { v -> Settings.update { it.copy(overlayEnabled = v) } }
        Choices(OverlayCorner.entries, s.overlayCorner, { it.label }) { v -> Settings.update { it.copy(overlayCorner = v) } }
        Text("표시 시간", style = MaterialTheme.typography.bodyMedium)
        Choices(listOf(3, 5, 8, 12), s.overlaySeconds, { "${it}초" }) { v -> Settings.update { it.copy(overlaySeconds = v) } }

        Section("저장 전 확인")
        Choices(ReviewMode.entries, s.reviewMode, { it.label }) { v -> Settings.update { it.copy(reviewMode = v) } }
        Toggle("처음 보는 곡은 확인", "곡 이름을 한 번 고쳐 두면 다음부터는 자동으로 맞춰져요", s.reviewNewSongs) { v -> Settings.update { it.copy(reviewNewSongs = v) } }
        Toggle("인식한 화면 이미지 보관", "기록마다 결과 화면 사진을 앱 안에 남겨요 (확인이 필요한 기록은 항상 보관)", s.keepImages) { v -> Settings.update { it.copy(keepImages = v) } }

        Section("데이터")
        Text("곡 선택 화면에서 배운 곡: ${songCount}곡 (곡 이름 맞추기에 써요)", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                exporter.launch("sekai-records-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".csv")
            }) { Text("CSV 내보내기") }
            OutlinedButton(onClick = { onOpen(Route.OcrTest) }) { Text("인식 테스트") }
            OutlinedButton(onClick = { RecordDb.clearSongs() }) { Text("배운 곡 목록 지우기") }
        }
        Text("화면 캡처만 사용하고 게임 데이터·메모리는 읽거나 바꾸지 않아요. 글자 인식은 기기 안에서만 하고 인터넷을 쓰지 않아요.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Toggle(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
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
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
    }
    TextButton(onClick = {
        val d = Weights()
        listOf(d.perfect, d.great, d.good, d.bad, d.miss).forEachIndexed { i, v -> texts[i].value = trim(v) }
        onChange(d)
    }) { Text("기본값 (100 / 70 / 50 / 0 / 0)") }
}

private fun trim(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()
