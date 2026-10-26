package com.mobilecoder.ide.feature.ai

/**
 * 一次性请求通道：**不进会话、不带工具**，只问一句、拿一段文本回来。
 *
 * 「行内补全」与「AI 重构」共用——两者都要的是「小请求 → 纯文本」，
 * 与 [AiController.send] 的工具调用循环解耦（不占 busy、不写会话、不弹确认）。
 *
 * 复用对话同款的容灾思路：按 [AiProvidersConfig.candidates] 顺序尝试，
 * 额度 / Key / 模型 / 服务端失效时换下一个候选（最多 [MAX_CANDIDATES] 个）。
 */
internal object AiOneShot {

    /** 单次最多尝试的候选接口数（补全要快，不无限轮换）。 */
    private const val MAX_CANDIDATES = 3

    /**
     * 发一次非流式小请求。
     *
     * @param system    系统提示（补全引擎 / 重构引擎的角色约束）
     * @param user      用户内容（代码上下文）
     * @param maxTokens 输出上限（补全给 64 一类的小值，重构给大值）
     * @param isCompletion 是否走「行内补全」通道（见 [AiClient.complete]）
     * @throws AiApiException 所有候选都失败（或首个候选遇到不可切换的错误）
     */
    suspend fun ask(
        system: String,
        user: String,
        maxTokens: Int,
        temperature: Double = 0.2,
        isCompletion: Boolean = false,
    ): String {
        val candidates = AiController.providers.value.candidates().take(MAX_CANDIDATES)
        if (candidates.isEmpty()) {
            throw AiApiException(
                AiErrorKind.NOT_FOUND,
                0,
                "尚未配置可用接口：到 AI 页右上角「设置」添加接口与模型",
                retryable = false,
            )
        }
        val messages = listOf(
            ApiMessage("system", system),
            ApiMessage("user", user),
        )
        var last: AiApiException? = null
        for (endpoint in candidates) {
            try {
                return AiClient.complete(endpoint, messages, maxTokens, temperature, isCompletion)
            } catch (e: AiApiException) {
                if (!switchable(e.kind)) throw e
                last = e
            }
        }
        throw last ?: AiApiException(AiErrorKind.UNKNOWN, 0, "请求失败", retryable = false)
    }

    /** 这类失败值得「换下一个候选」继续试（其余如解析失败直接抛出）。 */
    private fun switchable(kind: AiErrorKind): Boolean = when (kind) {
        AiErrorKind.QUOTA,
        AiErrorKind.AUTH,
        AiErrorKind.NOT_FOUND,
        AiErrorKind.RATE_LIMIT,
        AiErrorKind.SERVER,
        AiErrorKind.TIMEOUT,
        AiErrorKind.NETWORK,
        -> true

        else -> false
    }
}
