package com.mobilecoder.ide.feature.ai

/**
 * AI 助手数据模型（PRD 扩展：接入 AI API Key，通过会话驱动项目修改）。
 *
 * 分三层：
 *  - [AiMessage]：**会话持久化层**（user / assistant 文本 + 工具执行事件 + 工具调用回放数据）；
 *  - [ApiMessage]：**协议层**（发送给 OpenAI 兼容接口的原始消息，含 tool_calls /
 *    role=tool 结果，仅在一次 `send` 循环内存在，不落盘）；
 *  - [AiProvider]/[AiEndpoint]：**多接口配置层**（多个接口、每个接口多个模型，
 *    额度用尽自动切换，见 [AiProvidersConfig.candidates]）。
 */

// ---------------------------------------------------------------------------
// 多接口 / 多模型配置
// ---------------------------------------------------------------------------

/** 同一接口下的一个模型条目。 */
data class AiModel(
    /** 发给接口的 `model` 字段（如 `gpt-4o-mini`）。 */
    val id: String,
    /** 是否参与自动容灾轮换（禁用的模型不会被选中）。 */
    val enabled: Boolean = true,
)

/** 一个 OpenAI 兼容接口（Base URL + Key + 该接口下的多个模型）。 */
data class AiProvider(
    val id: String,
    /** 显示名，如「DeepSeek」。 */
    val name: String,
    val baseUrl: String,
    /** 明文 Key（内存态；落盘经 CryptoBox 加密）。 */
    val apiKey: String = "",
    val enabled: Boolean = true,
    val models: List<AiModel> = emptyList(),
    /** 该接口当前选中的模型 id。 */
    val activeModelId: String = "",
) {
    /** 当前选中且可用的模型（选中的被禁用时回退到第一个可用模型）。 */
    val activeModel: AiModel?
        get() = models.firstOrNull { it.id == activeModelId && it.enabled }
            ?: models.firstOrNull { it.enabled }

    /** 是否已具备发起请求的最小条件。 */
    val looksReady: Boolean
        get() = enabled && baseUrl.isNotBlank() && models.any { it.enabled }
}

/** 多接口配置整体（含当前生效的接口）。 */
data class AiProvidersConfig(
    val providers: List<AiProvider> = emptyList(),
    /** 当前生效的接口 id（空 = 自动取第一个可用接口）。 */
    val activeProviderId: String = "",
) {
    /** 当前生效接口（不可用时回退到第一个可用接口）。 */
    val activeProvider: AiProvider?
        get() = providers.firstOrNull { it.id == activeProviderId && it.looksReady }
            ?: providers.firstOrNull { it.looksReady }

    /** 当前生效端点（无可用接口时为 null）。 */
    fun activeEndpoint(): AiEndpoint? =
        activeProvider?.let { p -> p.activeModel?.let { m -> AiEndpoint(p, m.id) } }

    /**
     * 容灾候选序列：当前接口的当前模型排最首，随后按设置顺序展开
     * （每个接口内：当前模型优先，其余可用模型按列表顺序）。
     * 禁用的接口 / 模型、缺 Base URL 的接口不参与。
     */
    fun candidates(): List<AiEndpoint> {
        val ordered = listOfNotNull(activeProvider) +
            providers.filter { it.enabled && it.id != activeProvider?.id }
        val out = ArrayList<AiEndpoint>()
        for (provider in ordered) {
            if (!provider.looksReady) continue
            val usable = provider.models.filter { it.enabled }
            if (usable.isEmpty()) continue
            val withActive = usable.filter { it.id == provider.activeModel?.id } +
                usable.filter { it.id != provider.activeModel?.id }
            for (model in withActive) out += AiEndpoint(provider, model.id)
        }
        return out
    }

    fun providerById(id: String): AiProvider? = providers.firstOrNull { it.id == id }
}

/** 一次请求的目标（接口地址 + 模型名 + Key）。 */
data class AiEndpoint(
    val providerId: String,
    val providerName: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
) {
    constructor(provider: AiProvider, modelId: String) : this(
        providerId = provider.id,
        providerName = provider.name,
        baseUrl = provider.baseUrl,
        model = modelId,
        apiKey = provider.apiKey,
    )

    /** `{baseUrl}/chat/completions`。 */
    val endpoint: String get() = baseUrl.trim().trimEnd('/') + "/chat/completions"

    /** 展示标签「接口 / 模型」。 */
    val label: String get() = "$providerName / $model"
}

/** 预设分组（设置页分两行展示）。 */
enum class PresetGroup {
    /** 国内直连（无需特殊网络环境）。 */
    DOMESTIC,

    /** 海外平台（需自行解决网络环境）。 */
    OVERSEAS,
}

/**
 * 接口预设（设置页一键填写）。
 *
 * @param group 展示分组（国内直连 / 海外）
 * @param freeTag 是否有免费模型（UI 显示「免费」标记；仅为引导，不承诺永久免费）
 * @param note 一行备注（注册方式 / 网络提示等）
 */
data class AiProviderPreset(
    val name: String,
    val baseUrl: String,
    val models: List<String>,
    val group: PresetGroup = PresetGroup.DOMESTIC,
    val freeTag: Boolean = false,
    val note: String = "",
)

/**
 * 待用户确认的 AI 写操作请求（delete_path 恒确认；开启「写入前确认」后写类操作也确认）。
 *
 * @param title 摘要标题，如「AI 将执行 3 项操作（含删除）」
 * @param items 逐项描述（每行一条，如「写入 src/Main.kt」）
 */
data class AiConfirmRequest(
    val title: String,
    val items: List<String>,
)

// ---------------------------------------------------------------------------
// 容灾轮换 / 进度事件
// ---------------------------------------------------------------------------

/** 一次自动切换的说明（额度用尽 → 已切换 …）。 */
data class AiSwitchInfo(
    /** 切换前的「接口 / 模型」标签。 */
    val from: String,
    /** 切换后的标签。 */
    val to: String,
    /** 中文原因（额度用尽 / Key 无效 / 模型不存在 / 超时…）。 */
    val reason: String,
)

/** 一次发送的进度阶段（按会话归属，防止串会话显示）。 */
enum class AiStage {
    /** 等待模型首字节。 */
    REQUESTING,

    /** 流式输出中文本。 */
    STREAMING,

    /** 执行工具调用中。 */
    TOOL,
}

/** 当前进度（null = 空闲）。[sessionId] 用于 UI 判断归属，防止切会话后错位显示。 */
data class AiProgress(
    val sessionId: String,
    val stage: AiStage,
    /** STREAMING 阶段的增量文本。 */
    val text: String = "",
    /** 阶段描述（REQUESTING：模型名；TOOL：正在读取 xxx）。 */
    val detail: String = "",
    /** 第几轮（1 起）。 */
    val round: Int = 0,
)

// ---------------------------------------------------------------------------
// 消息与工具调用
// ---------------------------------------------------------------------------

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

/** 一条工具回传（协议层 role=tool 的持久化形态，与 [ApiToolCall.id] 对应）。 */
data class AiToolOutcome(
    val toolCallId: String,
    /** 回传给模型的完整结果文本。 */
    val content: String,
    /** 用户拒绝执行时的占位结果（协议完整性保证，不单独建模型）。 */
    val rejected: Boolean = false,
)

/** 会话中持久化的一条消息。 */
data class AiMessage(
    /** "user" | "assistant" */
    val role: String,
    val content: String,
    /** 该条 assistant 消息携带的工具执行事件（user 恒为空）。 */
    val events: List<AiToolEvent> = emptyList(),
    /**
     * 该条 assistant 消息发起的工具调用（协议回放用）。
     * 与 [toolOutcomes] 一一对应；下一轮 send 时重建
     * `assistant(tool_calls) + role=tool` 序列，避免模型“失忆”。
     */
    val toolCalls: List<ApiToolCall> = emptyList(),
    val toolOutcomes: List<AiToolOutcome> = emptyList(),
    /** 该轮写操作的快照 id（AiSnapshotStore；非空 = 可“撤销本轮”），撤销后清空。 */
    val snapshotId: String? = null,
    /** 消息时间戳（毫秒；0 = 旧数据无时间，UI 隐藏）。 */
    val time: Long = System.currentTimeMillis(),
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
