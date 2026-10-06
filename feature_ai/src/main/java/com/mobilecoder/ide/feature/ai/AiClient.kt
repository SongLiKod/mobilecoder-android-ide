package com.mobilecoder.ide.feature.ai

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI 兼容接口客户端（`POST {baseUrl}/chat/completions`，SSE 流式）。
 *
 * 纯 `HttpURLConnection` 实现（项目不引入 OkHttp），支持：
 *  - `stream=true`：走 delta 回调 [onDelta]，同时累积 `tool_calls`；
 *  - 非标准服务端：若响应没有 SSE 行，整包按普通 JSON completion 解析（容错）；
 *  - **结构化错误**：一律抛 [AiApiException]（含 [AiErrorKind] / 状态码 / 是否可重试），
 *    供 AiController 做额度用尽 → 自动切换接口与模型的容灾决策；
 *  - 取消：[cancelActive] 断开连接，阻塞读立即失败并结束本轮。
 */
object AiClient {

    @Volatile
    private var active: HttpURLConnection? = null

    /** 取消当前请求（断开连接，阻塞读立即返回）。 */
    fun cancelActive() {
        runCatching { active?.disconnect() }
    }

    /**
     * 发起一次流式对话。
     *
     * @param endpoint 目标接口（地址 + 模型 + Key）
     * @param messages 完整上下文（含 system / user / assistant / tool）
     * @param tools    工具 schema 数组；空数组则不传 tools 字段
     * @param onDelta  文本增量回调（IO 线程）
     * @throws AiApiException 分类后的失败（额度 / Key / 模型 / 超时 / 网络 / 服务端）
     */
    suspend fun chat(
        endpoint: AiEndpoint,
        messages: List<ApiMessage>,
        tools: JSONArray,
        onDelta: (String) -> Unit,
    ): AiReply = withContext(Dispatchers.IO) {
        val payload = buildRequest(endpoint, messages, tools)
        val conn = open(endpoint)
        try {
            conn.outputStream.use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val body = runCatching {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty()
                throw httpError(code, body)
            }
            parseResponse(conn, onDelta)
        } catch (e: AiApiException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw AiApiException(AiErrorKind.TIMEOUT, 0, "连接超时（${endpoint.label}）", retryable = true, cause = e)
        } catch (e: IOException) {
            // 取消（disconnect）与真实网络故障都表现为 IOException，
            // 取消由 AiController 的 cancelling 标志兜底，这里按可重试网络错误分类。
            throw AiApiException(
                AiErrorKind.NETWORK,
                0,
                "网络错误：${e.message ?: "连接失败"}",
                retryable = true,
                cause = e,
            )
        } finally {
            runCatching { conn.disconnect() }
            if (active === conn) active = null
        }
    }

    /**
     * 连通性测试：发一条 `max_tokens=1` 的最短非流式请求。
     *
     * @return 成功时返回服务端回显的简短结果（如 `ok`），失败抛 [AiApiException]。
     */
    suspend fun test(endpoint: AiEndpoint): String = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("model", endpoint.model)
            .put("stream", false)
            .put("max_tokens", 1)
            .put("messages", JSONArray(listOf(JSONObject().put("role", "user").put("content", "hi"))))
            .toString()
        val conn = open(endpoint, stream = false)
        try {
            conn.outputStream.use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            val code = conn.responseCode
            val body = runCatching {
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }
            }.getOrNull().orEmpty()
            if (code !in 200..299) throw httpError(code, body)
            val json = runCatching { JSONObject(body) }.getOrElse {
                throw AiApiException(AiErrorKind.PARSE, code, "响应不是合法 JSON", retryable = false, cause = it)
            }
            if (json.has("error")) {
                throw AiApiException(AiErrorKind.PARSE, code, errorText(json), retryable = false)
            }
            "连接正常"
        } catch (e: AiApiException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw AiApiException(AiErrorKind.TIMEOUT, 0, "连接超时", retryable = true, cause = e)
        } catch (e: IOException) {
            throw AiApiException(
                AiErrorKind.NETWORK,
                0,
                "网络错误：${e.message ?: "连接失败"}",
                retryable = true,
                cause = e,
            )
        } finally {
            runCatching { conn.disconnect() }
            if (active === conn) active = null
        }
    }

    // ------------------------------------------------------------------
    // 连接与请求体
    // ------------------------------------------------------------------

    private fun open(endpoint: AiEndpoint, stream: Boolean = true): HttpURLConnection {
        val conn = (URL(endpoint.endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 300_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty(
                "Accept",
                if (stream) "text/event-stream, application/json" else "application/json",
            )
            if (endpoint.apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${endpoint.apiKey}")
            }
        }
        active = conn
        return conn
    }

    private fun buildRequest(
        endpoint: AiEndpoint,
        messages: List<ApiMessage>,
        tools: JSONArray,
    ): String {
        val root = JSONObject()
            .put("model", endpoint.model)
            .put("stream", true)
            .put("temperature", 0.2)
            .put("messages", JSONArray(messages.map { toJson(it) }))
        if (tools.length() > 0) {
            root.put("tools", tools)
            root.put("tool_choice", "auto")
        }
        return root.toString()
    }

    private fun toJson(message: ApiMessage): JSONObject {
        val json = JSONObject().put("role", message.role)
        when (message.role) {
            "assistant" -> {
                json.put("content", message.content ?: JSONObject.NULL)
                if (message.toolCalls.isNotEmpty()) {
                    json.put(
                        "tool_calls",
                        JSONArray(
                            message.toolCalls.map { call ->
                                JSONObject()
                                    .put("id", call.id)
                                    .put("type", "function")
                                    .put(
                                        "function",
                                        JSONObject()
                                            .put("name", call.name)
                                            .put("arguments", call.arguments),
                                    )
                            },
                        ),
                    )
                }
            }

            "tool" -> {
                json.put("tool_call_id", message.toolCallId)
                json.put("content", message.content ?: "")
            }

            else -> json.put("content", message.content ?: "")
        }
        return json
    }

    // ------------------------------------------------------------------
    // 响应解析
    // ------------------------------------------------------------------

    private fun parseResponse(conn: HttpURLConnection, onDelta: (String) -> Unit): AiReply {
        val content = StringBuilder()
        val calls = java.util.TreeMap<Int, CallAcc>()
        var sawSse = false
        val raw = StringBuilder()

        conn.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                raw.append(line).append('\n')
                val trimmed = line.trim()
                if (!trimmed.startsWith("data:")) continue
                val data = trimmed.removePrefix("data:").trim()
                if (data.isEmpty() || data == "[DONE]") continue
                sawSse = true
                handleSseChunk(data, content, calls, onDelta)
            }
        }

        if (!sawSse) {
            // 容错：服务端未按 SSE 返回 → 整包当普通 completion 解析
            return parsePlainBody(raw.toString(), onDelta)
        }
        return AiReply(content.toString(), calls.values.map { it.build() })
    }

    private fun handleSseChunk(
        data: String,
        content: StringBuilder,
        calls: java.util.TreeMap<Int, CallAcc>,
        onDelta: (String) -> Unit,
    ) {
        val json = runCatching { JSONObject(data) }.getOrNull() ?: return
        if (json.has("error")) {
            throw aiErrorFromPayload(json)
        }
        val choices = json.optJSONArray("choices") ?: return
        if (choices.length() == 0) return
        val delta = choices.getJSONObject(0).optJSONObject("delta") ?: return

        // 注意：工具轮常见 `"content": null`，optString 会把 JSON null 变成字符串 "null"，
        // 必须只接受真实 String，否则回复被污染成 "null"/"nullnull"。
        val deltaContent = delta.opt("content")
        if (deltaContent is String && deltaContent.isNotEmpty()) {
            content.append(deltaContent)
            onDelta(deltaContent)
        }
        val toolCalls = delta.optJSONArray("tool_calls") ?: return
        for (i in 0 until toolCalls.length()) {
            val item = toolCalls.getJSONObject(i)
            val index = item.optInt("index", i)
            val acc = calls.getOrPut(index) { CallAcc() }
            item.optString("id").takeIf { it.isNotEmpty() }?.let { acc.id = it }
            val fn = item.optJSONObject("function") ?: continue
            fn.optString("name").takeIf { it.isNotEmpty() }?.let { acc.name = it }
            fn.optString("arguments").takeIf { it.isNotEmpty() }?.let { acc.arguments.append(it) }
        }
    }

    /** 非流式 JSON 响应解析（兼容不支持 stream 的代理/服务）。 */
    private fun parsePlainBody(body: String, onDelta: (String) -> Unit): AiReply {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw AiApiException(
                AiErrorKind.PARSE,
                0,
                "无法解析接口响应（既不是 SSE 也不是 JSON）",
                retryable = false,
            )
        if (json.has("error")) throw aiErrorFromPayload(json)
        val choices = json.optJSONArray("choices")
            ?: throw AiApiException(AiErrorKind.PARSE, 0, "接口响应缺少 choices 字段", retryable = false)
        if (choices.length() == 0) {
            throw AiApiException(AiErrorKind.PARSE, 0, "接口响应空 choices", retryable = false)
        }
        val message = choices.getJSONObject(0).optJSONObject("message")
            ?: throw AiApiException(AiErrorKind.PARSE, 0, "接口响应缺少 message 字段", retryable = false)
        // 同 handleSseChunk：content 为 JSON null 时 optString 会返回 "null"，只接受真实文本
        val text = (message.opt("content") as? String).orEmpty()
        if (text.isNotEmpty()) onDelta(text)
        val calls = ArrayList<ApiToolCall>()
        val arr = message.optJSONArray("tool_calls") ?: return AiReply(text, calls)
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val fn = item.optJSONObject("function") ?: continue
            calls.add(
                ApiToolCall(
                    id = item.optString("id").ifBlank { "call_$i" },
                    name = fn.optString("name"),
                    arguments = fn.optString("arguments"),
                ),
            )
        }
        return AiReply(text, calls)
    }

    // ------------------------------------------------------------------
    // 错误分类（容灾轮换的决策依据）
    // ------------------------------------------------------------------

    private fun errorText(json: JSONObject): String {
        val err = json.optJSONObject("error") ?: return json.toString()
        return err.optString("message").ifBlank { err.toString() }
    }

    /** 服务端 error 对象（含 type/code 字段）→ 结构化异常。 */
    private fun aiErrorFromPayload(json: JSONObject): AiApiException {
        val err = json.optJSONObject("error")
        val message = errorText(json)
        val type = (err?.optString("type").orEmpty() + " " + err?.optString("code").orEmpty()).lowercase()
        val kind = classifyText(message.lowercase() + " " + type)
        return AiApiException(kind, 0, message, retryable = kind == AiErrorKind.RATE_LIMIT)
    }

    /** HTTP 状态码 + 响应体 → 结构化异常。 */
    internal fun httpError(code: Int, body: String): AiApiException {
        val detail = runCatching { JSONObject(body) }.getOrNull()
            ?.let { runCatching { errorText(it) }.getOrNull() }
            ?: body.take(300)
        val kind: AiErrorKind = when (code) {
            401, 403 -> AiErrorKind.AUTH
            402 -> AiErrorKind.QUOTA
            429 -> if (classifyText(detail.lowercase()) == AiErrorKind.QUOTA) {
                AiErrorKind.QUOTA
            } else {
                AiErrorKind.RATE_LIMIT
            }

            404 -> AiErrorKind.NOT_FOUND
            408 -> AiErrorKind.TIMEOUT
            in 500..599 -> AiErrorKind.SERVER
            else -> classifyText(detail.lowercase())
        }
        return AiApiException(kind, code, humanMessage(code, kind, detail), retryable = retryable(kind))
    }

    /** 纯文本（无状态码）关键词分类，兜底 UNKNOWN。 */
    internal fun classifyText(text: String): AiErrorKind = when {
        "insufficient_quota" in text || "quota" in text -> AiErrorKind.QUOTA
        "rate limit" in text || "rate_limit" in text || "too many requests" in text -> AiErrorKind.RATE_LIMIT
        "api key" in text || "unauthorized" in text || "authentication" in text || "permission" in text ->
            AiErrorKind.AUTH

        "model" in text && ("not found" in text || "does not exist" in text || "invalid" in text) ->
            AiErrorKind.NOT_FOUND

        "context" in text && "length" in text -> AiErrorKind.CONTEXT
        else -> AiErrorKind.UNKNOWN
    }

    /** 同一候选是否允许原地重试（超时/网络/服务端/限流）。 */
    private fun retryable(kind: AiErrorKind): Boolean = when (kind) {
        AiErrorKind.TIMEOUT, AiErrorKind.NETWORK, AiErrorKind.SERVER, AiErrorKind.RATE_LIMIT -> true
        else -> false
    }

    private fun humanMessage(code: Int, kind: AiErrorKind, detail: String): String {
        val hint = when (kind) {
            AiErrorKind.AUTH -> "API Key 无效或无权限（检查该接口的 Key）"
            AiErrorKind.QUOTA -> "额度已用完（该接口/模型的配额耗尽）"
            AiErrorKind.RATE_LIMIT -> "请求过于频繁（稍后自动重试）"
            AiErrorKind.NOT_FOUND -> "接口地址或模型不存在（Base URL 需含 /v1，并确认模型名）"
            AiErrorKind.TIMEOUT -> "请求超时"
            AiErrorKind.SERVER -> "服务端错误（稍后再试）"
            AiErrorKind.CONTEXT -> "上下文过长（请新开会话或缩短输入）"
            AiErrorKind.NETWORK -> "网络连接失败"
            AiErrorKind.PARSE, AiErrorKind.UNKNOWN -> "请求失败"
        }
        val extra = if (detail.isNotBlank()) "：$detail" else ""
        return "HTTP $code：$hint$extra"
    }

    /** `tool_calls` 的流式累加器。 */
    private class CallAcc {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()
        fun build() = ApiToolCall(
            id = id.ifBlank { "call_${name.hashCode()}" },
            name = name,
            arguments = arguments.toString(),
        )
    }
}

/**
 * 结构化接口异常：供容灾轮换分类决策。
 *
 * @param kind     错误类别（额度 / Key / 模型 / 超时 / 网络 / 服务端 / 上下文）
 * @param status   HTTP 状态码（0 = 非 HTTP 层错误，如解析失败）
 * @param retryable 同一候选是否允许原地重试一次
 */
class AiApiException(
    val kind: AiErrorKind,
    val status: Int,
    override val message: String,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : IOException(message, cause)

/** 接口错误类别（决定「重试 / 换模型 / 换接口 / 直接报错」）。 */
enum class AiErrorKind {
    /** 401/403：Key 无效 → 该接口全部模型标记失效。 */
    AUTH,

    /** 配额耗尽（402 / insufficient_quota）→ 该模型标记失效。 */
    QUOTA,

    /** 限流（429 短时）→ 可原地重试一次，仍失败标记该模型。 */
    RATE_LIMIT,

    /** 404 模型或路径不存在 → 该模型标记失效。 */
    NOT_FOUND,

    /** 超时 → 可重试，不标记。 */
    TIMEOUT,

    /** 网络中断 → 可重试，不标记。 */
    NETWORK,

    /** 5xx → 可重试，不标记。 */
    SERVER,

    /** 上下文超长 → 换模型也难解决，直接报错提示。 */
    CONTEXT,

    /** 响应解析失败 → 换候选重试。 */
    PARSE,

    /** 未分类 → 换候选重试一次。 */
    UNKNOWN,
}
