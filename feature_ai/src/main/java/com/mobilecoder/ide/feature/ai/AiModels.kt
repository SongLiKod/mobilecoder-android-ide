package com.mobilecoder.ide.feature.ai

/**
 * AI 助手数据模型（PRD 扩展：接入 AI API Key，通过会话驱动项目修改）。
 *
 * 分两层：
 *  - [AiMessage]：**会话持久化层**（只存 user / assistant 文本 + 工具执行事件）；
 *  - [ApiMessage]：**协议层**（发送给 OpenAI 兼容接口的原始消息，含 tool_calls /
 *    role=tool 结果，仅在一次 `send` 循环内存在，不落盘）。
 */

/** 模型接口配置（OpenAI 兼容：`{baseUrl}/chat/completions`）。 */
data class AiConfig(
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-4o-mini",
    val apiKey: String = "",
) {
    val endpoint: String get() = baseUrl.trim().trimEnd('/') + "/chat/completions"

    /** 是否已可发起请求（本地 Ollama 等免 key 服务允许 key 为空）。 */
    val looksReady: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank()
}

/** 一次工具调用的展示记录（会话里可见“AI 改了哪些文件”）。 */
data class AiToolEvent(
    /** 工具名：list_files / read_file / write_file / search_replace / delete_path。 */
    val name: String,
    /** 目标路径（项目内相对路径）。 */
    val target: String,
    val ok: Boolean,
    /** 一句话结果（中文）。 */
    val summary: String,
)

/** 会话中持久化的一条消息。 */
data class AiMessage(
    /** "user" | "assistant" */
    val role: String,
    val content: String,
    /** 该条 assistant 消息携带的工具执行事件（user 恒为空）。 */
    val events: List<AiToolEvent> = emptyList(),
)

/** 一个 AI 会话（= 一段项目修改对话）。 */
data class AiSession(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messages: List<AiMessage> = emptyList(),
)

/** 协议层：发给模型的原始消息。 */
data class ApiMessage(
    /** "system" | "user" | "assistant" | "tool" */
    val role: String,
    val content: String? = null,
    val toolCalls: List<ApiToolCall> = emptyList(),
    /** role=tool 时对应 tool_call id。 */
    val toolCallId: String = "",
)

/** 模型返回的一次工具调用。 */
data class ApiToolCall(
    val id: String,
    val name: String,
    /** 原始 JSON 参数串。 */
    val arguments: String,
)

/** 模型的一轮回复：文本 + 可能的工具调用。 */
data class AiReply(
    val content: String,
    val toolCalls: List<ApiToolCall> = emptyList(),
)

/** 工具执行结果（content 回传模型，summary 展示给用户）。 */
data class AiToolResult(
    val ok: Boolean,
    val target: String,
    val summary: String,
    val content: String,
)
