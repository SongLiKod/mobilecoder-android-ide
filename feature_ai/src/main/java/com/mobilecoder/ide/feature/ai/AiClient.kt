package com.mobilecoder.ide.feature.ai

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI 兼容接口客户端（`POST {baseUrl}/chat/completions`，SSE 流式）。
 *
 * 纯 `HttpURLConnection` 实现（项目不引入 OkHttp），支持：
 *  - `stream=true`：逐 delta 回调 [onDelta]，同时累积 `tool_calls`；
 *  - 非标准服务端：若响应没有 SSE 行，整包按普通 JSON completion 解析（容错）；
 *  - 错误：状态码 + 服务端 message → 中文提示（401/404/429/5xx 分类）；
 *  - 取消：[cancelActive] 断开连接，阻塞读立即失败并结束本轮。
 */
object AiClient {

    /** 一次回复：完整文本 + 工具调用（按 index 归并）。 */
    data class Reply(val content: String, val toolCalls: List<ApiToolCall>)

    @Volatile
    private var active: HttpURLConnection? = null

    /** 取消当前请求（断开连接，阻塞读立即返回）。 */
    fun cancelActive() {
        runCatching { active?.disconnect() }
    }

    /**
     * 发起一次流式对话。
     *
     * @param config   接口配置
     * @param messages 完整上下文（含 system / user / assistant / tool）
     * @param tools    工具 schema 数组；空数组则不传 tools 字段
     * @param onDelta  文本增量回调（IO 线程）
     */
    suspend fun chat(
        config: AiConfig,
        messages: List<ApiMessage>,
        tools: JSONArray,
        onDelta: (String) -> Unit,
    ): Reply = withContext(Dispatchers.IO) {
        val payload = buildRequest(config, messages, tools)
        val conn = (URL(config.endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 300_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream, application/json")
            if (config.apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            }
        }
        active = conn
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
                throw IOException(humanError(code, body))
            }
            parseResponse(conn, onDelta)
        } finally {
            runCatching { conn.disconnect() }
            if (active === conn) active = null
        }
    }

    // ------------------------------------------------------------------
    // 请求体
    // ------------------------------------------------------------------

    private fun buildRequest(
        config: AiConfig,
        messages: List<ApiMessage>,
        tools: JSONArray,
    ): String {
        val root = JSONObject()
            .put("model", config.model)
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

    private fun parseResponse(conn: HttpURLConnection, onDelta: (String) -> Unit): Reply {
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
        return Reply(content.toString(), calls.values.map { it.build() })
    }

    private fun handleSseChunk(
        data: String,
        content: StringBuilder,
        calls: java.util.TreeMap<Int, CallAcc>,
        onDelta: (String) -> Unit,
    ) {
        val json = runCatching { JSONObject(data) }.getOrNull() ?: return
        if (json.has("error")) {
            throw IOException("接口返回错误：${errorText(json)}")
        }
        val choices = json.optJSONArray("choices") ?: return
        if (choices.length() == 0) return
        val delta = choices.getJSONObject(0).optJSONObject("delta") ?: return

        delta.optString("content").takeIf { it.isNotEmpty() }?.let {
            content.append(it)
            onDelta(it)
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
    private fun parsePlainBody(body: String, onDelta: (String) -> Unit): Reply {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw IOException("无法解析接口响应（既不是 SSE 也不是 JSON）")
        if (json.has("error")) throw IOException("接口返回错误：${errorText(json)}")
        val choices = json.optJSONArray("choices")
            ?: throw IOException("接口响应缺少 choices 字段")
        if (choices.length() == 0) throw IOException("接口返回空 choices")
        val message = choices.getJSONObject(0).optJSONObject("message")
            ?: throw IOException("接口响应缺少 message 字段")
        val text = message.optString("content")
        if (text.isNotEmpty()) onDelta(text)
        val calls = ArrayList<ApiToolCall>()
        val arr = message.optJSONArray("tool_calls") ?: return Reply(text, calls)
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
        return Reply(text, calls)
    }

    // ------------------------------------------------------------------
    // 错误
    // ------------------------------------------------------------------

    private fun errorText(json: JSONObject): String {
        val err = json.optJSONObject("error") ?: return json.toString()
        return err.optString("message").ifBlank { err.toString() }
    }

    private fun humanError(code: Int, body: String): String {
        val detail = runCatching { JSONObject(body) }.getOrNull()?.let { errorText(it) }
            ?: body.take(300)
        val hint = when (code) {
            401, 403 -> "API Key 无效或无权限（检查设置里的 API Key）"
            404 -> "接口地址或模型不存在（Base URL 需含 /v1，并确认模型名）"
            408, 429 -> "请求过于频繁或额度不足（稍后再试）"
            in 500..599 -> "服务端错误（稍后再试）"
            else -> "请求失败"
        }
        return "HTTP $code：$hint${if (detail.isNotBlank()) "；$detail" else ""}"
    }

    /** `tool_calls` 的流式累积器。 */
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
