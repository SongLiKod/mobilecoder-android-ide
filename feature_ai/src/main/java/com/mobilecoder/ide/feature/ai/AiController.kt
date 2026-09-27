package com.mobilecoder.ide.feature.ai

import android.content.Context
import com.mobilecoder.ide.core.storage.AppStorage
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 助手控制器（进程级单例）：会话持久化 + OpenAI 兼容接口的工具调用循环。
 *
 * 一次 [send] 的流程：
 *  1. 追加 user 消息并落盘；
 *  2. 流式请求模型（SSE，文本边到边显示在 [_streaming]）；
 *  3. 若返回 `tool_calls`：逐个执行项目工具（list/read/write/search_replace/delete），
 *     把结果以 role=tool 回传，并把 [AiToolEvent] 挂到该条 assistant 消息上（界面可见）；
 *  4. 循环直到模型给出纯文本回复（最多 12 轮）或出错 / 被取消。
 *
 * 所有状态都是 StateFlow，UI（AiScreen）只负责订阅与输入。
 */
object AiController {

    /** 单次发送允许的最大工具调用轮数。 */
    private const val MAX_ROUNDS = 12

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var sessionsFile: File? = null

    @Volatile
    private var loaded = false

    @Volatile
    private var cancelling = false

    private var job: Job? = null

    private val _sessions = MutableStateFlow<List<AiSession>>(emptyList())
    val sessions: StateFlow<List<AiSession>> = _sessions.asStateFlow()

    private val _currentId = MutableStateFlow<String?>(null)
    val currentId: StateFlow<String?> = _currentId.asStateFlow()

    /** 当前会话（由 sessions + currentId 派生）。 */
    val current: StateFlow<AiSession?> = combine(_sessions, _currentId) { list, id ->
        list.firstOrNull { it.id == id }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 当前流式回复文本（空串 = 无）。 */
    private val _streaming = MutableStateFlow("")
    val streaming: StateFlow<String> = _streaming.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _config = MutableStateFlow(AiConfig())
    val config: StateFlow<AiConfig> = _config.asStateFlow()

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    fun init(context: Context) {
        runCatching {
            sessionsFile = File(File(context.filesDir, "ai"), "sessions.json")
        }
        scope.launch {
            runCatching { ensureLoaded() }
            runCatching { refreshConfig() }
        }
    }

    /** 首次访问时加载会话文件（幂等）。 */
    suspend fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val file = sessionsFile ?: return
        val parsed = runCatching {
            if (!file.exists()) return
            val array = JSONArray(file.readText(Charsets.UTF_8))
            (0 until array.length()).map { parseSession(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        _sessions.value = parsed.sortedByDescending { it.updatedAt }
        if (_currentId.value == null) _currentId.value = parsed.firstOrNull()?.id
    }

    /** 从应用设置读取接口配置。 */
    suspend fun refreshConfig() {
        val prefs = AppStorage.preferences
        _config.value = AiConfig(
            baseUrl = runCatching { prefs.aiBaseUrl() }.getOrDefault("https://api.openai.com/v1"),
            model = runCatching { prefs.aiModel() }.getOrDefault("gpt-4o-mini"),
            apiKey = runCatching { prefs.aiApiKey() }.getOrDefault(""),
        )
    }

    /** 保存接口配置（API Key 加密落盘）。 */
    suspend fun saveConfig(baseUrl: String, model: String, apiKey: String) {
        val prefs = AppStorage.preferences
        runCatching { prefs.setAiBaseUrl(baseUrl.ifBlank { "https://api.openai.com/v1" }) }
        runCatching { prefs.setAiModel(model.ifBlank { "gpt-4o-mini" }) }
        runCatching { prefs.setAiApiKey(apiKey.trim()) }
        refreshConfig()
    }

    // ------------------------------------------------------------------
    // 会话管理
    // ------------------------------------------------------------------

    fun select(id: String) {
        _currentId.value = id
    }

    fun newSession(): AiSession {
        val session = AiSession(
            id = System.currentTimeMillis().toString(36) + (0..999).random().toString(),
            title = "新会话",
            updatedAt = System.currentTimeMillis(),
        )
        _sessions.value = listOf(session) + _sessions.value
        _currentId.value = session.id
        _error.value = null
        scope.launch { runCatching { persist() } }
        return session
    }

    fun deleteSession(id: String) {
        _sessions.value = _sessions.value.filterNot { it.id == id }
        if (_currentId.value == id) _currentId.value = _sessions.value.firstOrNull()?.id
        scope.launch { runCatching { persist() } }
    }

    // ------------------------------------------------------------------
    // 发送
    // ------------------------------------------------------------------

    /** 是否有会话正在进行请求。 */
    fun isBusy(): Boolean = _busy.value

    /** 停止当前请求（断开连接 + 取消协程）。 */
    fun cancel() {
        cancelling = true
        runCatching { AiClient.cancelActive() }
        job?.cancel()
    }

    fun clearError() {
        _error.value = null
    }

    /**
     * 发送一条消息并执行工具调用循环。
     *
     * @param projectRoot 项目根目录（工具的沙箱边界）
     */
    fun send(projectRoot: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank() || _busy.value) return
        cancelling = false
        _error.value = null
        job = scope.launch {
            _busy.value = true
            try {
                runCatching { ensureLoaded() }
                runCatching { refreshConfig() }
                val config = _config.value
                if (!config.looksReady) {
                    _error.value = "请先在右上角「设置」里填写接口地址与模型名"
                    return@launch
                }

                var sessionId = _currentId.value
                if (sessionId == null || _sessions.value.none { it.id == sessionId }) {
                    sessionId = newSession().id
                }
                appendMessage(sessionId, AiMessage("user", trimmed))

                // 协议层上下文：只用落盘的 user / assistant 文本
                val working = ArrayList<ApiMessage>()
                sessionById(sessionId)?.messages?.forEach { m ->
                    working += if (m.role == "user") {
                        ApiMessage("user", m.content)
                    } else {
                        ApiMessage("assistant", m.content.ifEmpty { null })
                    }
                }

                val root = File(projectRoot)
                var rounds = 0
                while (rounds < MAX_ROUNDS && !cancelling) {
                    rounds++
                    _streaming.value = ""
                    val reply = AiClient.chat(config, working, AiTools.schemas()) { delta ->
                        _streaming.value += delta
                    }
                    working += ApiMessage(
                        role = "assistant",
                        content = reply.content.ifEmpty { null },
                        toolCalls = reply.toolCalls,
                    )

                    if (reply.toolCalls.isEmpty()) {
                        _streaming.value = ""
                        if (reply.content.isNotBlank()) {
                            appendMessage(sessionId, AiMessage("assistant", reply.content))
                        }
                        break
                    }

                    // 先把这条 assistant 消息挂出来（内容可能为空，工具事件随后补齐）
                    val index = appendMessage(
                        sessionId,
                        AiMessage("assistant", reply.content, events = emptyList()),
                    )
                    _streaming.value = ""
                    val events = ArrayList<AiToolEvent>()
                    for (call in reply.toolCalls) {
                        if (cancelling) break
                        val result = runCatching {
                            AiTools.execute(call.name, call.arguments, root)
                        }.getOrElse { t ->
                            AiToolResult(
                                ok = false,
                                target = "",
                                summary = t.message ?: "执行失败",
                                content = "工具执行异常：${t.message}",
                            )
                        }
                        events += AiToolEvent(
                            name = call.name,
                            target = result.target,
                            ok = result.ok,
                            summary = result.summary,
                        )
                        working += ApiMessage(
                            role = "tool",
                            content = result.content,
                            toolCallId = call.id,
                        )
                    }
                    updateMessage(sessionId, index) { it.copy(events = events) }
                }

                if (rounds >= MAX_ROUNDS && !cancelling) {
                    _error.value = "已达单次最大工具调用轮数（$MAX_ROUNDS），如需继续请再次发送"
                }
            } catch (e: CancellationException) {
                _error.value = "已停止"
                throw e
            } catch (t: Throwable) {
                _error.value = if (cancelling) "已停止" else (t.message ?: "请求失败")
            } finally {
                _streaming.value = ""
                _busy.value = false
                cancelling = false
            }
        }
    }

    // ------------------------------------------------------------------
    // 内部：会话读写 + 持久化
    // ------------------------------------------------------------------

    private fun sessionById(id: String?): AiSession? =
        _sessions.value.firstOrNull { it.id == id }

    /** 追加一条消息，返回其在 messages 中的下标。 */
    private fun appendMessage(sessionId: String, message: AiMessage): Int {
        val list = _sessions.value
        val index = list.indexOfFirst { it.id == sessionId }
        if (index < 0) return -1
        val session = list[index]
        val messages = session.messages + message
        val title = if (session.title == "新会话" && message.role == "user") {
            message.content.replace(Regex("\\s+"), " ").trim().take(24)
                .ifBlank { session.title }
        } else {
            session.title
        }
        val updated = session.copy(
            messages = messages,
            title = title,
            updatedAt = System.currentTimeMillis(),
        )
        _sessions.value = list.toMutableList().also { it[index] = updated }
        scope.launch { runCatching { persist() } }
        return messages.size - 1
    }

    private fun updateMessage(sessionId: String, index: Int, transform: (AiMessage) -> AiMessage) {
        if (index < 0) return
        val list = _sessions.value
        val sIndex = list.indexOfFirst { it.id == sessionId }
        if (sIndex < 0) return
        val session = list[sIndex]
        if (index >= session.messages.size) return
        val messages = session.messages.toMutableList().also { it[index] = transform(it[index]) }
        _sessions.value = list.toMutableList().also {
            it[sIndex] = session.copy(messages = messages)
        }
        scope.launch { runCatching { persist() } }
    }

    private suspend fun persist() {
        val file = sessionsFile ?: return
        val array = JSONArray()
        _sessions.value.forEach { session ->
            val messages = JSONArray()
            session.messages.forEach { m ->
                val events = JSONArray()
                m.events.forEach { e ->
                    events.put(
                        JSONObject()
                            .put("name", e.name)
                            .put("target", e.target)
                            .put("ok", e.ok)
                            .put("summary", e.summary),
                    )
                }
                messages.put(
                    JSONObject()
                        .put("role", m.role)
                        .put("content", m.content)
                        .put("events", events),
                )
            }
            array.put(
                JSONObject()
                    .put("id", session.id)
                    .put("title", session.title)
                    .put("updatedAt", session.updatedAt)
                    .put("messages", messages),
            )
        }
        runCatching {
            file.parentFile?.let { if (!it.exists()) it.mkdirs() }
            file.writeText(array.toString(), Charsets.UTF_8)
        }
    }

    private fun parseSession(json: JSONObject): AiSession {
        val messagesArr = json.optJSONArray("messages") ?: JSONArray()
        val messages = (0 until messagesArr.length()).map { i ->
            val m = messagesArr.getJSONObject(i)
            val eventsArr = m.optJSONArray("events") ?: JSONArray()
            AiMessage(
                role = m.optString("role", "user"),
                content = m.optString("content"),
                events = (0 until eventsArr.length()).map { j ->
                    val e = eventsArr.getJSONObject(j)
                    AiToolEvent(
                        name = e.optString("name"),
                        target = e.optString("target"),
                        ok = e.optBoolean("ok", true),
                        summary = e.optString("summary"),
                    )
                },
            )
        }
        return AiSession(
            id = json.optString("id"),
            title = json.optString("title", "会话"),
            updatedAt = json.optLong("updatedAt", 0L),
            messages = messages,
        )
    }
}
