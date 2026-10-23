package io.github.northster.sekaitracker.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import io.github.northster.sekaitracker.capture.CaptureService
import io.github.northster.sekaitracker.data.Difficulty

sealed class Route {
    data class Chart(val title: String, val difficulty: Difficulty?) : Route()
    data class Edit(val id: Long) : Route()
    data object OcrTest : Route()
}

enum class Tab(val label: String) { HOME("홈"), RECORDS("기록"), SETTINGS("설정") }

class MainActivity : ComponentActivity() {
    private var tab by mutableStateOf(Tab.HOME)
    private val stack = mutableStateListOf<Route>()

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "화면 캡처를 허용해야 기록할 수 있어요", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        ContextCompat.startForegroundService(this, Intent(this, CaptureService::class.java)
            .setAction(CaptureService.ACTION_START)
            .putExtra(CaptureService.EXTRA_RESULT_CODE, res.resultCode)
            .putExtra(CaptureService.EXTRA_DATA, data))
    }

    private val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { askProjection() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            SekaiTheme {
                val route = stack.lastOrNull()
                BackHandler(enabled = route != null) { stack.removeAt(stack.lastIndex) }
                if (route != null) {
                    when (route) {
                        is Route.Chart -> ChartScreen(route.title, route.difficulty, onBack = ::pop, onEdit = { push(Route.Edit(it)) },
                            onRenamed = { t -> stack[stack.lastIndex] = Route.Chart(t, route.difficulty) })
                        is Route.Edit -> EditScreen(route.id, onDone = ::pop)
                        Route.OcrTest -> OcrTestScreen(onBack = ::pop)
                    }
                    return@SekaiTheme
                }
                Scaffold(bottomBar = {
                    NavigationBar {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t, onClick = { tab = t }, label = { Text(t.label) },
                                icon = { Icon(when (t) { Tab.HOME -> Icons.Default.Home; Tab.RECORDS -> Icons.Default.List; Tab.SETTINGS -> Icons.Default.Settings }, null) },
                            )
                        }
                    }
                }) { padding ->
                    val m = Modifier.padding(padding)
                    when (tab) {
                        Tab.HOME -> HomeScreen(m, onStart = ::requestStart, onStop = ::stopCapture, onOpen = ::push)
                        Tab.RECORDS -> RecordsScreen(m, onOpen = ::push)
                        Tab.SETTINGS -> SettingsScreen(m, onOpen = ::push)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_REVIEW_ID, -1) ?: -1
        if (id >= 0) { stack.clear(); stack.add(Route.Edit(id)) }
        if (intent?.getBooleanExtra(EXTRA_START_CAPTURE, false) == true) requestStart()
    }

    private fun push(route: Route) { stack.add(route) }
    private fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }

    private fun requestStart() {
        if (CaptureService.running.value) return
        if (!AndroidSettings.canDrawOverlays(this))
            Toast.makeText(this, "오버레이 권한이 없으면 결과 알림 창 없이 기록만 해요", Toast.LENGTH_SHORT).show()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else askProjection()
    }

    private fun askProjection() {
        projectionLauncher.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }

    private fun stopCapture() {
        startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
    }

    companion object {
        const val EXTRA_REVIEW_ID = "review_id"
        const val EXTRA_START_CAPTURE = "start_capture"
    }
}
