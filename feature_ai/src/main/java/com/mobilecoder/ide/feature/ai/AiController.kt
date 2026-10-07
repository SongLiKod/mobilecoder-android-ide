package com.mobilecoder.ide.feature.ai

import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 助手控制器（进程级单例）：会话持久化 + 多接口容灾轮换 + OpenAI 兼容工具调用循环。
 *
 * 一次 [send] 的流程：
 *  1. 追加 user 消息并落盘，注入 system prompt（项目上下文 + 工具纪律）；
 *  2. 按 [AiProvidersConfig.candidates] 取候选（当前接口/模型优先，跳过冷却中的）；
 *  3. 流式请求（SSE，文本边到边显示在 [progress]）；失败按 [AiErrorKind] 决策：
 *     可重试→原地重试一次；额度/Key/模型失效→标记冷却并自动切换下一候选，
 *     **同一份协议上下文原样重发**（上下文不丢、已执行工具不重复）；
 *  4. 返回 `tool_calls`：（必要时）用户确认 → 快照备份 → 逐个执行项目工具，
 *     结果以 role=tool 回传；事件与回放数据挂到该条 assistant 消息上；
 *  5. 循环直到纯文本回复（轮数上限可在设置里自定义，默认 10 万、0 = 不限制）
 *     或全部候选失效 / 被取消。
 *
 * 所有状态都是 StateFlow，UI（AiScreen）只负责订阅与输入。
 */
object AiController {

    /** 单次发送允许的最大工具调用轮数默认值（0 = 不限制，可在设置里改）。 */
    private const val DEFAULT_ROUND_LIMIT = 100_000

    /** 单次发送的最大请求数基础上限（含失败重试与切换，防失控）。 */
    private const val MAX_ATTEMPTS = 30

    /** 实际请求上限：随轮数上限放大（轮数 0 = 不限制时不限请求数）。 */
    private fun attemptCap(roundLimit: Int): Int =
        if (roundLimit == 0) Int.MAX_VALUE else (roundLimit * 2 + MAX_ATTEMPTS).coerceAtLeast(MAX_ATTEMPTS)

    /** 额度/Key/模型失效的冷却时长（额度会重置，到期自动恢复）。 */
    private const val FAIL_COOLDOWN_MS = 10 * 60_000L

    /** 短时限流的冷却时长。 */
    private const val RATE_COOLDOWN_MS = 60_000L

    /** 操作确认的最长等待（超时按拒绝处理）。 */
    private const val CONFIRM_TIMEOUT_MS = 5 * 60_000L

    /** 内置默认 system prompt 模板（设置界面「填入默认」用；`{project}` 发送前替换）。 */
    const val DEFAULT_SYSTEM_PROMPT = """你是「移动码匠」Android IDE 内置的 AI 编程助手，通过工具直接读写用户当前打开的项目。
项目根目录：{projectName}（{project}）
语言要求：始终用简体中文回复（代码、命令与标识符除外）。
引用约定：消息中的 @相对路径 表示对该项目文件或目录的引用；需要查看内容时先用 read_file 按该路径读取。
工具使用纪律：
1. 修改文件前必须先用 read_file 读取当前内容；局部修改优先 search_replace，整文件重写才用 write_file。
2. search_replace 的 old_str 必须与文件内容逐字符一致（包括空格与缩进），不要凭记忆猜测。
3. 路径一律相对项目根，不使用绝对路径，不使用 ..。
4. 不要删除文件或目录，除非用户在对话里明确要求。
5. 工具返回失败原因时，先按提示修正参数再重试，不要原样重复调用。
6. 结束后用一两句中文总结改了什么；没有要改的就直接回答。"""

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var sessionsFile: File? = null

    @Volatile
    private var loaded = false

    @Volatile
    private var cancelling = false

    /** 写操作确认回调（AiScreen 挂载弹窗；null = 全部放行）。 */
    @Volatile
    var confirmHandler: (suspend (AiConfirmRequest) -> Boolean)? = null

    private var job: Job? = null

    /** 「写入前确认」开关（delete_path 恒确认），来自 AppPreferences。 */
    private val _confirmWrites = MutableStateFlow(false)
    val confirmWrites: StateFlow<Boolean> = _confirmWrites.asStateFlow()

    /** 单次发送的最大工具调用轮数（0 = 不限制），来自 AppPreferences。 */
    private val _roundLimit = MutableStateFlow(DEFAULT_ROUND_LIMIT)
    val roundLimit: StateFlow<Int> = _roundLimit.asStateFlow()

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

    /** 当前进度（null = 空闲；含会话归属，切会话不会串台）。 */
    private val _progress = MutableStateFlow<AiProgress?>(null)
    val progress: StateFlow<AiProgress?> = _progress.asStateFlow()

    /** 最近一次自动切换（UI 显示「额度用尽 → 已切换 …」后自动清除）。 */
    private val _switch = MutableStateFlow<AiSwitchInfo?>(null)
    val switch: StateFlow<AiSwitchInfo?> = _switch.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 中性提示（已停止 / 已撤销…）：不以错误色展示。 */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _providers = MutableStateFlow(AiProvidersConfig())
    val providers: StateFlow<AiProvidersConfig> = _providers.asStateFlow()

    /** 自定义 system prompt（"" = 用 [DEFAULT_SYSTEM_PROMPT]），来自 AppPreferences。 */
    private val _systemPrompt = MutableStateFlow("")
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    /** 失效冷却追踪（额度/Key/模型失效 → 自动换候选，到期恢复）。 */
    private val failures = AiFailureTracker(FAIL_COOLDOWN_MS, RATE_COOLDOWN_MS)

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

    /** 读取多接口配置（含旧单配置迁移）、「写入前确认」开关、自定义 system prompt 与轮数上限。 */
    suspend fun refreshConfig() {
        _providers.value = runCatching { AiProviderStore.load() }.getOrDefault(AiProvidersConfig())
        _confirmWrites.value = runCatching { AppStorage.preferences.aiConfirmWrites() }.getOrDefault(false)
        _systemPrompt.value = runCatching { AppStorage.preferences.aiSystemPrompt() }.getOrDefault("")
        _roundLimit.value = runCatching { AppStorage.preferences.aiRoundLimit() }.getOrDefault(DEFAULT_ROUND_LIMIT)
    }

    /** 保存单次发送最大工具调用轮数（0 = 不限制）。 */
    suspend fun setRoundLimit(value: Int) {
        runCatching { AppStorage.preferences.setAiRoundLimit(value) }
        _roundLimit.value = value.coerceIn(0, 1_000_000)
    }

    /** 保存自定义 system prompt（空 = 恢复内置默认）。 */
    suspend fun saveSystemPrompt(value: String) {
        runCatching { AppStorage.preferences.setAiSystemPrompt(value) }
        _systemPrompt.value = value
    }

    /** 切换「写入前逐轮确认」开关。 */
    suspend fun setConfirmWrites(value: Boolean) {
        runCatching { AppStorage.preferences.setAiConfirmWrites(value) }
        _confirmWrites.value = value
    }

    /** 保存多接口配置（落盘 + 生效 + 清除全部失效冷却）。 */
    suspend fun saveProviders(config: AiProvidersConfig) {
        runCatching { AiProviderStore.save(config) }
        _providers.value = config
        failures.clear()
    }

    /** 连通性测试（对该接口当前模型发 max_tokens=1 请求）。 */
    suspend fun test(endpoint: AiEndpoint): Result<String> = runCatching { AiClient.test(endpoint) }

    // ------------------------------------------------------------------
    // 会话管理（busy 期间锁切换/新建/删除，防止进度串台）
    // ------------------------------------------------------------------

    fun select(id: String) {
        if (_busy.value) return
        _currentId.value = id
    }

    fun newSession(): AiSession? {
        if (_busy.value) return null
        return newSessionInternal()
    }

    private fun newSessionInternal(): AiSession {
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
        if (_busy.value) return
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

    fun clearNotice() {
        _notice.value = null
    }

    fun clearSwitch() {
        _switch.value = null
    }

    /**
     * 重试最近一条「未获回复」的用户消息：先移除该条（避免重复气泡），再原样重发。
     * 若最后一条不是 user（已有回复）则无动作。
     */
    fun retryLast(projectRoot: String) {
        if (_busy.value) return
        val session = current.value ?: return
        val last = session.messages.lastOrNull() ?: return
        if (last.role != "user") return
        val list = _sessions.value
        val sIndex = list.indexOfFirst { it.id == session.id }
        if (sIndex >= 0) {
            val trimmed = session.copy(messages = session.messages.dropLast(1))
            _sessions.value = list.toMutableList().also { it[sIndex] = trimmed }
            scope.launch { runCatching { persist() } }
        }
        send(projectRoot, last.content)
    }

    /**
     * 发送一条消息并执行工具调用循环（含多接口容灾轮换）。
     *
     * @param projectRoot 项目根目录（工具的沙箱边界）
     */
    fun send(projectRoot: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank() || _busy.value) return
        cancelling = false
        _error.value = null
        _notice.value = null
        _switch.value = null
        job = scope.launch {
            _busy.value = true
            try {
                runCatching { ensureLoaded() }
                runCatching { refreshConfig() }

                val config = _providers.value
                val all = config.candidates()
                val endpoints = all.filterNot { isFailed(it) }
                if (endpoints.isEmpty()) {
                    _error.value = when {
                        all.isEmpty() ->
                            "尚未配置可用接口：点右上角「设置」添加接口与模型（支持多个接口、每个接口多个模型）"

                        else -> "全部接口处于冷却中（额度/Key 异常），约 10 分钟后自动恢复；也可到设置里调整"
                    }
                    return@launch
                }

                var sessionId = _currentId.value
                if (sessionId == null || _sessions.value.none { it.id == sessionId }) {
                    sessionId = newSessionInternal().id
                }
                appendMessage(sessionId, AiMessage("user", trimmed))

                // 协议上下文：system + 落盘消息回放（含 tool_calls / tool 结果）
                val working = ArrayList<ApiMessage>()
                working += ApiMessage("system", systemPrompt(File(projectRoot)))
                rebuildContext(sessionId, working)

                val root = File(projectRoot)
                var successRounds = 0
                var attempts = 0
                var ci = 0
                var retriedSame = false
                val failures = ArrayList<String>()
                var fatal: String? = null
                val roundLimit = _roundLimit.value

                sendLoop@ while (
                    (roundLimit == 0 || successRounds < roundLimit) &&
                    attempts < attemptCap(roundLimit) &&
                    !cancelling
                ) {
                    attempts++
                    val endpoint = endpoints[ci]
                    Log.d("AiSend", "attempt=$attempts round=${successRounds + 1} -> ${endpoint.label}")
                    val stream = StringBuilder()
                    _progress.value = AiProgress(
                        sessionId = sessionId,
                        stage = AiStage.REQUESTING,
                        detail = endpoint.label,
                        round = successRounds + 1,
                    )

                    val reply: AiReply = try {
                        AiClient.chat(endpoint, working, AiTools.schemas()) { delta ->
                            stream.append(delta)
                            _progress.value = AiProgress(
                                sessionId = sessionId,
                                stage = AiStage.STREAMING,
                                text = stream.toString(),
                                detail = endpoint.label,
                                round = successRounds + 1,
                            )
                        }
                    } catch (c: CancellationException) {
                        throw c
                    } catch (e: AiApiException) {
                        if (cancelling) break@sendLoop
                        failures += "${endpoint.label}：${e.message}"
                        Log.w(
                            "AiSend",
                            "attempt=$attempts ${endpoint.label} failed kind=${e.kind} retryable=${e.retryable}: ${e.message}",
                        )
                        when {
                            // 上下文超长换模型也难解决 → 直接报错
                            e.kind == AiErrorKind.CONTEXT -> {
                                fatal = "上下文过长：请新建会话或缩短输入（${e.message}）"
                                break@sendLoop
                            }

                            // 可重试（超时/网络/5xx/限流）→ 同一候选原地重试一次
                            e.retryable && !retriedSame -> {
                                retriedSame = true
                                continue
                            }
                        }
                        // 标记失效并切换下一候选（同一份 working 原样重发）
                        markFailed(e, endpoint)
                        val next = (ci + 1 until endpoints.size).firstOrNull { !isFailed(endpoints[it]) }
                        if (next == null) {
                            fatal = aggregateFailures(failures)
                            break@sendLoop
                        }
                        // 首条切换（通常是“你选的首选模型”失败）保留 from/reason，
                        // 后续连锁切换只更新 to —— 横幅始终回答“首选为何没走通、现在在用谁”。
                        val prevSwitch = _switch.value
                        _switch.value = AiSwitchInfo(
                            from = prevSwitch?.from ?: endpoint.label,
                            to = endpoints[next].label,
                            reason = prevSwitch?.reason ?: kindLabel(e.kind),
                        )
                        ci = next
                        retriedSame = false
                        continue
                    }
                    retriedSame = false
                    successRounds++

                    if (reply.toolCalls.isEmpty()) {
                        if (reply.content.isNotBlank()) {
                            appendMessage(sessionId, AiMessage("assistant", reply.content))
                        }
                        break
                    }
                    runRound(
                        sessionId = sessionId,
                        root = root,
                        reply = reply,
                        working = working,
                        needWriteConfirm = _confirmWrites.value,
                    )
                }

                fatal?.let { _error.value = it }
                if (fatal == null && roundLimit != 0 && successRounds >= roundLimit && !cancelling) {
                    _error.value = "已达单次最大工具调用轮数（$roundLimit），如需继续请再次发送"
                }
                if (cancelling) _notice.value = "已停止"
            } catch (c: CancellationException) {
                _notice.value = "已停止"
                throw c
            } catch (t: Throwable) {
                _error.value = if (cancelling) "已停止" else (t.message ?: "请求失败")
            } finally {
                _progress.value = null
                _busy.value = false
                cancelling = false
            }
        }
    }

    /**
     * 执行一轮工具调用：（必要时）用户确认 → 快照备份 → 逐个执行 → 回写消息。
     * 结果同时追加到 [working]（role=tool）。
     */
    private suspend fun runRound(
        sessionId: String,
        root: File,
        reply: AiReply,
        working: ArrayList<ApiMessage>,
        needWriteConfirm: Boolean,
    ) {
        // 先把这条 assistant 消息挂出来（内容可能为空，工具事件随后补齐）
        val index = appendMessage(
            sessionId,
            AiMessage(
                role = "assistant",
                content = reply.content,
                toolCalls = reply.toolCalls,
            ),
        )
        working += ApiMessage(
            role = "assistant",
            content = reply.content.ifEmpty { null },
            toolCalls = reply.toolCalls,
        )

        val mutating = reply.toolCalls.filter { AiTools.isMutating(it.name) }
        var allowedIds: Set<String>? = null // null = 全部放行
        if (mutating.isNotEmpty()) {
            val hasDelete = mutating.any { it.name == "delete_path" }
            if (hasDelete || needWriteConfirm) {
                val ok = requestConfirm(
                    AiConfirmRequest(
                        title = if (hasDelete) {
                            "AI 将执行 ${mutating.size} 项操作（含删除）"
                        } else {
                            "AI 将执行 ${mutating.size} 项写入操作"
                        },
                        items = mutating.map { "${AiTools.displayName(it.name)} ${pathOf(it)}" },
                    ),
                )
                if (!ok) allowedIds = emptySet()
            }
        }

        // 快照：仅备份将真正执行的写类目标（P0 写保护，可整轮撤销）
        var snapshotId: String? = null
        val toRun = mutating.filter { allowedIds == null || it.id in allowedIds }
        if (toRun.isNotEmpty()) {
            snapshotId = runCatching {
                AiSnapshotStore.capture(
                    projectRoot = root,
                    sessionId = sessionId,
                    paths = toRun.mapNotNull { pathOf(it) },
                )?.id
            }.getOrNull()
        }

        val events = ArrayList<AiToolEvent>()
        val outcomes = ArrayList<AiToolOutcome>()
        for (call in reply.toolCalls) {
            val path = pathOf(call)
            if (cancelling) break
            val rejected = AiTools.isMutating(call.name) && allowedIds != null && call.id !in allowedIds
            if (rejected) {
                events += AiToolEvent(call.name, path, ok = false, summary = "已拒绝执行（用户取消）")
                outcomes += AiToolOutcome(call.id, "用户拒绝执行该操作：$path", rejected = true)
                working += ApiMessage("tool", "用户拒绝执行该操作：$path", toolCallId = call.id)
                continue
            }
            _progress.value = AiProgress(
                sessionId = sessionId,
                stage = AiStage.TOOL,
                detail = "${AiTools.displayName(call.name)} $path",
            )
            val result = runCatching {
                AiTools.execute(call.name, call.arguments, root)
            }.getOrElse { t ->
                AiToolResult(
                    ok = false,
                    target = path,
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
            outcomes += AiToolOutcome(call.id, result.content)
            working += ApiMessage("tool", result.content, toolCallId = call.id)
        }
        // 取消导致未执行的调用：补齐占位结果，保证 assistant(tool_calls) 后每个 id 都有 tool 回传
        while (outcomes.size < reply.toolCalls.size) {
            val call = reply.toolCalls[outcomes.size]
            outcomes += AiToolOutcome(call.id, "操作已取消，无结果", rejected = true)
            working += ApiMessage("tool", "操作已取消，无结果", toolCallId = call.id)
        }

        updateMessage(sessionId, index) {
            it.copy(events = events, toolOutcomes = outcomes, snapshotId = snapshotId)
        }
    }

    // ------------------------------------------------------------------
    // 容灾：失效标记与候选
    // ------------------------------------------------------------------

    private fun markFailed(e: AiApiException, endpoint: AiEndpoint) {
        failures.mark(e.kind, endpoint)
    }

    private fun isFailed(endpoint: AiEndpoint): Boolean = failures.isFailed(endpoint)

    private fun aggregateFailures(failures: List<String>): String {
        val head = "已尝试的接口全部失败（稍后会自动恢复冷却，也可到设置里补充接口）："
        val body = failures.take(4).joinToString("\n") { "• $it" }
        val more = if (failures.size > 4) "\n…等 ${failures.size} 项" else ""
        return head + "\n" + body + more
    }

    private fun kindLabel(kind: AiErrorKind): String = when (kind) {
        AiErrorKind.AUTH -> "Key 无效"
        AiErrorKind.QUOTA -> "额度已用完"
        AiErrorKind.RATE_LIMIT -> "请求限流"
        AiErrorKind.NOT_FOUND -> "模型不存在"
        AiErrorKind.TIMEOUT -> "连接超时"
        AiErrorKind.NETWORK -> "网络错误"
        AiErrorKind.SERVER -> "服务端错误"
        AiErrorKind.CONTEXT -> "上下文过长"
        AiErrorKind.PARSE -> "响应异常"
        AiErrorKind.UNKNOWN -> "请求失败"
    }

    // ------------------------------------------------------------------
    // 上下文
    // ------------------------------------------------------------------

    /**
     * system prompt：项目上下文 + 工具纪律（P0：模型不再“不知道自己在哪”）。
     *
     * 用户可在 AI 设置里修改（[saveSystemPrompt]）；支持 `{project}` / `{projectName}`
     * 占位符。自定义文本若不含项目根路径，自动补一行根目录说明，保证工具路径可用。
     */
    internal fun systemPrompt(projectRoot: File): String {
        val body = _systemPrompt.value.ifBlank { DEFAULT_SYSTEM_PROMPT }
            .replace("{project}", projectRoot.absolutePath)
            .replace("{projectName}", projectRoot.name)
        return if (projectRoot.absolutePath in body) {
            body
        } else {
            "项目根目录：${projectRoot.name}（${projectRoot.absolutePath}）\n$body"
        }
    }

    /**
     * 从落盘消息重建协议上下文（P0：补齐 tool_calls 与 role=tool 结果，模型不“失忆”）。
     *
     * 同一条 assistant 消息若带 tool_calls，则按序补上每条调用的回传；
     * 缺失的回传（旧数据 / 中途取消）用占位文本补齐，保证协议合法。
     */
    private fun rebuildContext(sessionId: String, working: ArrayList<ApiMessage>) {
        sessionById(sessionId)?.messages?.forEach { m ->
            if (m.role == "user") {
                working += ApiMessage("user", m.content)
                return@forEach
            }
            // 旧数据里存在“纯工具轮”落盘为空 content 的 assistant 消息：跳过（无 tool_calls 不可单发）
            if (m.content.isBlank() && m.toolCalls.isEmpty()) return@forEach
            working += ApiMessage(
                role = "assistant",
                content = m.content.ifBlank { null },
                toolCalls = m.toolCalls,
            )
            m.toolCalls.forEachIndexed { i, call ->
                val outcome = m.toolOutcomes.getOrNull(i)
                working += ApiMessage(
                    role = "tool",
                    content = outcome?.content ?: "（该操作已被取消，无结果）",
                    toolCallId = outcome?.toolCallId ?: call.id,
                )
            }
        }
    }

    private fun pathOf(call: ApiToolCall): String = runCatching {
        JSONObject(call.arguments).optString("path").ifBlank { "" }
    }.getOrDefault("")

    // ------------------------------------------------------------------
    // 确认 / 撤销
    // ------------------------------------------------------------------

    private suspend fun requestConfirm(request: AiConfirmRequest): Boolean {
        val handler = confirmHandler ?: return true
        return runCatching {
            withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { handler(request) }
        }.getOrNull() ?: false
    }

    /**
     * 撤销某轮 AI 修改（写回快照 + 删除本轮创建的文件）。
     * busy 期间不允许（AI 可能正在继续写同一批文件）。
     */
    fun undoRound(projectRoot: String, sessionId: String, snapshotId: String) {
        if (_busy.value) {
            _notice.value = "AI 正在执行，请先停止后再撤销"
            return
        }
        scope.launch {
            val result = AiSnapshotStore.undo(File(projectRoot), snapshotId)
            if (result == null) {
                _error.value = "撤销失败：快照不存在或已被清理"
                return@launch
            }
            clearSnapshotRef(sessionId, snapshotId)
            val head = "已撤销本轮修改（恢复 ${result.restored} 项，删除 ${result.deleted} 项）"
            _notice.value = if (result.unrecovered.isEmpty()) {
                head
            } else {
                head + "；${result.unrecovered.size} 项过大未备份，未能恢复"
            }
        }
    }

    private fun clearSnapshotRef(sessionId: String, snapshotId: String) {
        val list = _sessions.value
        val sIndex = list.indexOfFirst { it.id == sessionId }
        if (sIndex < 0) return
        val session = list[sIndex]
        val mIndex = session.messages.indexOfFirst { it.snapshotId == snapshotId }
        if (mIndex < 0) return
        val messages = session.messages.toMutableList().also { it[mIndex] = it[mIndex].copy(snapshotId = null) }
        _sessions.value = list.toMutableList().also { it[sIndex] = session.copy(messages = messages) }
        scope.launch { runCatching { persist() } }
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
                val toolCalls = JSONArray()
                m.toolCalls.forEach { c ->
                    toolCalls.put(
                        JSONObject()
                            .put("id", c.id)
                            .put("name", c.name)
                            .put("arguments", c.arguments),
                    )
                }
                val outcomes = JSONArray()
                m.toolOutcomes.forEach { o ->
                    outcomes.put(
                        JSONObject()
                            .put("toolCallId", o.toolCallId)
                            .put("content", o.content)
                            .put("rejected", o.rejected),
                    )
                }
                messages.put(
                    JSONObject()
                        .put("role", m.role)
                        .put("content", m.content)
                        .put("time", m.time)
                        .put("events", events)
                        .put("toolCalls", toolCalls)
                        .put("toolOutcomes", outcomes)
                        .put("snapshotId", m.snapshotId ?: ""),
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
            val toolCallsArr = m.optJSONArray("toolCalls") ?: JSONArray()
            val outcomesArr = m.optJSONArray("toolOutcomes") ?: JSONArray()
            AiMessage(
                role = m.optString("role", "user"),
                content = m.optString("content").cleanJsonNullArtifact(),
                events = (0 until eventsArr.length()).map { j ->
                    val e = eventsArr.getJSONObject(j)
                    AiToolEvent(
                        name = e.optString("name"),
                        target = e.optString("target"),
                        ok = e.optBoolean("ok", true),
                        summary = e.optString("summary"),
                    )
                },
                toolCalls = (0 until toolCallsArr.length()).map { j ->
                    val c = toolCallsArr.getJSONObject(j)
                    ApiToolCall(
                        id = c.optString("id"),
                        name = c.optString("name"),
                        arguments = c.optString("arguments"),
                    )
                },
                toolOutcomes = (0 until outcomesArr.length()).map { j ->
                    val o = outcomesArr.getJSONObject(j)
                    AiToolOutcome(
                        toolCallId = o.optString("toolCallId"),
                        content = o.optString("content"),
                        rejected = o.optBoolean("rejected", false),
                    )
                },
                snapshotId = m.optString("snapshotId").ifBlank { null },
                time = m.optLong("time", 0L),
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

/**
 * 清洗旧落盘数据里的 "null"/"nullnull" 脏内容（历史 bug：SSE `"content": null`
 * 被 optString 变成字符串 "null" 累积后存盘；源头 [AiClient] 已修，此处兜底旧会话）。
 */
internal fun String.cleanJsonNullArtifact(): String =
    if (trim().matches(Regex("^(?:null)+$"))) "" else this
