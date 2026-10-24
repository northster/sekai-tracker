package io.github.northster.sekaitracker.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.northster.sekaitracker.data.Difficulty
import io.github.northster.sekaitracker.data.Settings
import io.github.northster.sekaitracker.ocr.OcrBox
import io.github.northster.sekaitracker.ocr.Recognizer
import io.github.northster.sekaitracker.ocr.ResultParser
import io.github.northster.sekaitracker.ocr.ScreenKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs the on-device OCR on the bundled sample result screen (screenshots/result.jpg) and compares every field with the
 * known values, or on any picked screenshot to see what is read. Nothing is saved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var lines by remember { mutableStateOf<List<Pair<String, Boolean?>>>(emptyList()) }
    var raw by remember { mutableStateOf<List<OcrBox>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    fun test(load: suspend () -> Bitmap, expected: Map<String, String>?) {
        busy = true
        scope.launch {
            val out = mutableListOf<Pair<String, Boolean?>>()
            runCatching {
                val bmp = load()
                val start = SystemClock.elapsedRealtime()
                val boxes = Recognizer.read(bmp, Settings.current.ocrLanguage)
                val ms = SystemClock.elapsedRealtime() - start
                raw = boxes
                val kind = ResultParser.classify(boxes)
                out += "${bmp.width}×${bmp.height} · 인식 ${ms}ms · 글자 줄 ${boxes.size}개 · 화면: ${kindLabel(kind)}" to null
                if (kind == ScreenKind.RESULT) {
                    val p = ResultParser.parseResult(boxes, bmp.height)
                    val got = linkedMapOf(
                        "곡" to p.title, "난이도" to (p.difficulty?.label ?: "-"), "레벨" to (p.level?.toString() ?: "-"),
                        "콤보" to (p.combo?.toString() ?: "-"),
                        "PERFECT" to (p.perfect?.toString() ?: "-"), "GREAT" to (p.great?.toString() ?: "-"), "GOOD" to (p.good?.toString() ?: "-"),
                        "BAD" to (p.bad?.toString() ?: "-"), "MISS" to (p.miss?.toString() ?: "-"),
                        "FAST" to (p.fast?.toString() ?: "-"), "LATE" to (p.late?.toString() ?: "-"),
                        "풀콤보" to (p.judgments?.isFullCombo?.toString() ?: "-"), "올퍼펙트" to (p.judgments?.isAllPerfect?.toString() ?: "-"),
                    )
                    got.forEach { (k, v) ->
                        val e = expected?.get(k)
                        out += (if (e == null) "$k: $v" else "$k: $v (정답 $e)") to e?.let { it == v }
                    }
                    expected?.let { exp -> out.add(1, "정답 ${got.count { (k, v) -> exp[k] == v }} / ${exp.size}" to null) }
                    p.issues.forEach { out += "확인 필요: $it" to false }
                    p.notes.forEach { out += "참고: $it" to null }
                } else if (kind == ScreenKind.SONG_SELECT) {
                    ResultParser.parseSongSelect(boxes, bmp.height)?.let { s ->
                        out += "곡: ${s.title}" to null
                        s.levels.forEach { (d, l) -> out += "${d.label}: $l" to null }
                    }
                }
            }.onFailure { out += "실패: ${it.message}" to false }
            lines = out
            busy = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let { u -> test({ decodeImage(context, u) }, null) }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TopAppBar(title = { Text("인식 테스트") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "뒤로") } })
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = {
                test({ withContext(Dispatchers.IO) { context.assets.open("samples/result_master.jpg").use { BitmapFactory.decodeStream(it) } } }, SAMPLE)
            }) { Text("샘플 결과 화면") }
            OutlinedButton(enabled = !busy, onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Text("이미지 고르기")
            }
        }
        Text("인식 언어: ${Settings.current.ocrLanguage.label} · 저장하지 않아요", Modifier.padding(16.dp, 4.dp), style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(lines) { (text, ok) ->
                Text(text, color = when (ok) { true -> Color(0xFF2E9D57); false -> MaterialTheme.colorScheme.error; null -> Color.Unspecified })
            }
            if (raw.isNotEmpty()) {
                item { Text("읽은 글자 (왼쪽, 위 좌표)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp)) }
                items(raw.sortedWith(compareBy({ it.top / 20 }, { it.left }))) { b ->
                    Text("${b.left},${b.top}  ${b.text}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun kindLabel(k: ScreenKind) = when (k) { ScreenKind.RESULT -> "결과"; ScreenKind.SONG_SELECT -> "곡 선택"; ScreenKind.LIVE_PREPARE -> "라이브 준비"; ScreenKind.OTHER -> "기타" }

/** values on screenshots/result.jpg */
private val SAMPLE = mapOf(
    "곡" to "ももいろの鍵", "난이도" to Difficulty.MASTER.label, "레벨" to "31",
    "콤보" to "1187", "PERFECT" to "1166", "GREAT" to "21", "GOOD" to "0", "BAD" to "0", "MISS" to "0",
    "FAST" to "17", "LATE" to "4", "풀콤보" to "true", "올퍼펙트" to "false",
)
