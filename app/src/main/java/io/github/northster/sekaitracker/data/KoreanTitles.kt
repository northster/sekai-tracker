package io.github.northster.sekaitracker.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Korean song titles: Gemini searches Google for the name Korean players use most (the Korean server's official
 * title, Namu Wiki, communities). This is the only part of the app that uses the internet, and only with the user's own
 * API key; just the song title is sent.
 */
object KoreanTitles {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val inFlight = mutableSetOf<String>()

    /** progress of "translate all": done to total, null when not running */
    private val batchState = MutableStateFlow<Pair<Int, Int>?>(null)
    val batch: StateFlow<Pair<Int, Int>?> get() = batchState

    class Failure(message: String) : Exception(message)

    private fun prompt(title: String) = """
        너는 리듬게임 "프로젝트 세카이 컬러풀 스테이지! feat. 하츠네 미쿠"의 수록곡 제목을 한국어로 찾아 주는 도우미야.
        구글 검색으로 곡 "$title"의 한국어 제목을 찾아.
        한국 서버(프로젝트 세카이 한국판)의 공식 표기, 나무위키 문서 제목, 한국 팬 커뮤니티에서 가장 많이 쓰이는 한글 곡명 하나를 골라.
        원제가 영어 등이라 한국에서도 원제를 그대로 쓰면 원제를 그대로 써.
        설명은 짧게 하고, 마지막 줄은 반드시 다음 형식으로만 써:
        한글 제목: <곡명>
    """.trimIndent()

    /** asks Gemini; throws [Failure] with a message for the user */
    suspend fun lookUp(title: String): String = withContext(Dispatchers.IO) {
        val key = SecretStore.geminiKey ?: throw Failure("설정에서 Gemini API 키를 넣어 주세요")
        val model = URLEncoder.encode(Settings.current.geminiModel.trim(), "UTF-8")
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt(title))))))
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault("HTTP $code")
                throw Failure(when (code) {
                    400, 401, 403 -> "API 키를 확인해 주세요 ($message)"
                    404 -> "모델 이름을 확인해 주세요 ($message)"
                    429 -> "요청 한도를 넘었어요. 잠시 뒤에 다시 해 주세요"
                    else -> "Gemini 오류: $message"
                })
            }
            val parts = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            val answer = (0 until (parts?.length() ?: 0)).joinToString("\n") { parts!!.getJSONObject(it).optString("text") }
            parseAnswer(answer) ?: throw Failure("한글 제목을 찾지 못했어요")
        } finally {
            conn.disconnect()
        }
    }

    /** the title from the "한글 제목: ..." line */
    fun parseAnswer(answer: String): String? {
        val line = Regex("""한글\s*제목\s*[:：]\s*(.+)""").findAll(answer).lastOrNull()?.groupValues?.get(1) ?: return null
        val cleaned = line.trim().trim('*', '`', '"', '\'', '“', '”', '「', '」', '『', '』', '<', '>', ' ').trim()
        return cleaned.takeIf { it.isNotEmpty() && it != "없음" && it.length <= 80 }
    }

    /** look the title up and store it; returns the Korean title or throws [Failure] */
    suspend fun fetchAndSave(title: String): String {
        val ko = lookUp(title)
        RecordDb.setKoTitle(title, ko)
        return ko
    }

    /** after a song is saved: fetch its Korean title in the background when that is turned on and not known yet */
    fun ensure(title: String) {
        if (title.isBlank() || !Settings.current.autoKoTitle || !SecretStore.hasGeminiKey) return
        scope.launch {
            lock.withLock { if (!inFlight.add(title)) return@launch }
            try {
                if (RecordDb.koTitle(title) == null) runCatching { fetchAndSave(title) }
            } finally {
                lock.withLock { inFlight.remove(title) }
            }
        }
    }

    /** every played song without a Korean title, one after another (spaced out for the free API limits) */
    fun translateAll(onDone: (String) -> Unit) {
        if (batchState.value != null) return
        scope.launch {
            val known = RecordDb.koTitles().keys
            val todo = RecordDb.titles().filter { it !in known }
            var ok = 0
            var error: String? = null
            batchState.value = 0 to todo.size
            for ((i, title) in todo.withIndex()) {
                try { fetchAndSave(title); ok++ } catch (e: Failure) {
                    error = e.message
                    if (e.message?.startsWith("API 키") == true || e.message?.startsWith("모델") == true) break
                } catch (e: Exception) { error = e.message }
                batchState.value = (i + 1) to todo.size
                delay(4_500)
            }
            batchState.value = null
            withContext(Dispatchers.Main) {
                onDone(if (todo.isEmpty()) "번역할 곡이 없어요" else "${ok}곡의 한글 제목을 찾았어요" + (error?.let { " · 마지막 오류: $it" } ?: ""))
            }
        }
    }
}
