package com.mobilecoder.ide.feature.ai

import java.util.concurrent.ConcurrentHashMap

/**
 * 容灾失效追踪：按错误类别给「接口 / 模型」打冷却标记，到期自动恢复。
 *
 *  - AUTH → 该接口全部模型（`providerId|*`）；
 *  - QUOTA / NOT_FOUND → 该接口该模型（`providerId|model`）；
 *  - RATE_LIMIT → 同上但冷却更短；
 *  - 超时 / 网络 / 5xx / 解析失败 → 不标记（仅本轮换下一个候选）。
 *
 * 所有方法显式传入 `now`，便于单元测试注入时间。
 */
internal class AiFailureTracker(
    private val failCooldownMs: Long,
    private val rateCooldownMs: Long,
) {
    private val failedUntil = ConcurrentHashMap<String, Long>()

    /** 记录一次失败；瞬时错误类别不标记。 */
    fun mark(kind: AiErrorKind, endpoint: AiEndpoint, now: Long = System.currentTimeMillis()) {
        when (kind) {
            AiErrorKind.AUTH ->
                failedUntil["${endpoint.providerId}|*"] = now + failCooldownMs

            AiErrorKind.QUOTA, AiErrorKind.NOT_FOUND ->
                failedUntil["${endpoint.providerId}|${endpoint.model}"] = now + failCooldownMs

            AiErrorKind.RATE_LIMIT ->
                failedUntil["${endpoint.providerId}|${endpoint.model}"] = now + rateCooldownMs

            else -> Unit
        }
    }

    /** 该端点是否处于冷却中（到期项顺带清理）。 */
    fun isFailed(endpoint: AiEndpoint, now: Long = System.currentTimeMillis()): Boolean {
        val providerKey = "${endpoint.providerId}|*"
        val modelKey = "${endpoint.providerId}|${endpoint.model}"
        val providerUntil = failedUntil[providerKey] ?: 0L
        if (providerUntil > now) return true
        val modelUntil = failedUntil[modelKey] ?: 0L
        if (modelUntil > now) return true
        if (providerUntil in 1 until now) failedUntil.remove(providerKey)
        if (modelUntil in 1 until now) failedUntil.remove(modelKey)
        return false
    }

    /** 清除全部冷却（保存配置 / 手动重置时）。 */
    fun clear() = failedUntil.clear()
}
