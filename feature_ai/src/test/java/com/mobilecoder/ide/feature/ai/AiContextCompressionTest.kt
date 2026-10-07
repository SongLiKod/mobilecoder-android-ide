package com.mobilecoder.ide.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 上下文两级压缩的纯函数：占位结构安全 / 切分点 / 摘要输入 / token 估算。 */
class AiContextCompressionTest {

    private fun toolChain(count: Int): List<ApiMessage> = buildList {
        add(ApiMessage("system", "sys"))
        repeat(count) { i ->
            add(ApiMessage("user", "u$i"))
            add(ApiMessage("assistant", content = null, toolCalls = listOf(ApiToolCall("c$i", "read_file", "{}"))))
            add(ApiMessage("tool", content = "FILE_CONTENT_$i", toolCallId = "c$i"))
        }
        add(ApiMessage("assistant", "final"))
    }

    @Test
    fun `estimateTokens is positive and grows with text`() {
        assertTrue(estimateTokens("") >= 1)
        assertTrue(estimateTokens("abc") < estimateTokens("a".repeat(350)))
    }

    @Test
    fun `placeholder keeps recent tools and structure intact`() {
        val original = toolChain(10)
        val result = placeholderOldTools(original, keepRecent = 3)

        val origTools = original.filter { it.role == "tool" }
        val newTools = result.filter { it.role == "tool" }
        assertEquals(origTools.size, newTools.size)
        // 最近 keepRecent 条原文保留
        origTools.takeLast(3).forEachIndexed { i, t ->
            assertEquals(t.content, newTools[newTools.size - 3 + i].content)
            assertEquals(t.toolCallId, newTools[newTools.size - 3 + i].toolCallId)
        }
        // 更早的被占位，且 toolCallId 配对不破坏
        newTools.dropLast(3).forEachIndexed { i, t ->
            assertEquals(TOOL_OMITTED, t.content)
            assertEquals(origTools[i].toolCallId, t.toolCallId)
        }
        // 非 tool 消息不受影响
        assertEquals(
            original.filter { it.role != "tool" },
            result.filter { it.role != "tool" },
        )
    }

    @Test
    fun `placeholder is no-op when tool count within keep`() {
        val original = toolChain(3)
        assertEquals(original, placeholderOldTools(original, keepRecent = 8))
    }

    @Test
    fun `compactionCutIndex keeps last user segments`() {
        val msgs = listOf(
            AiMessage("user", "第一问"),
            AiMessage("assistant", "第一答"),
            AiMessage("user", "第二问"),
            AiMessage("assistant", "第二答"),
            AiMessage("user", "第三问"),
            AiMessage("assistant", "第三答"),
            AiMessage("user", "第四问"),
        )
        // 保留最近 2 个 user 段（第三问、第四问）→ 切在第二问（index 2）
        assertEquals(2, compactionCutIndex(msgs, minUpto = 0, keepUserSegs = 2))
        // minUpto 已越过切点时不再回退
        assertEquals(5, compactionCutIndex(msgs, minUpto = 5, keepUserSegs = 2))
        // user 段不足时不压
        assertEquals(4, compactionCutIndex(msgs.take(2), minUpto = 4, keepUserSegs = 2))
    }

    @Test
    fun `digestText renders roles and truncates long content`() {
        val msgs = listOf(
            AiMessage("user", "修 bug"),
            AiMessage(
                "assistant",
                content = "x".repeat(5000),
                toolCalls = listOf(ApiToolCall("c1", "write_file", "{}")),
            ),
        )
        val digest = digestText(msgs, 0, 2)
        assertTrue(digest.startsWith("用户：修 bug"))
        assertTrue(digest.contains("调用工具 1 次"))
        assertTrue(digest.length <= 24_000)
        // 单条内容被截到 800 字内
        assertTrue(digest.contains("助手：" + "x".repeat(800)))
        assertTrue(!digest.contains("x".repeat(801)))
    }

    @Test
    fun `estimateProtocol counts content and tool arguments`() {
        val msgs = listOf(
            ApiMessage("system", "s".repeat(350)),
            ApiMessage("tool", content = "", toolCallId = "c"),
            ApiMessage(
                "assistant",
                content = null,
                toolCalls = listOf(ApiToolCall("c", "n", "arg".repeat(350))),
            ),
        )
        val total = estimateProtocol(msgs)
        assertTrue(total >= estimateTokens("s".repeat(350)) + estimateTokens("arg".repeat(350)))
    }

    @Test
    fun `compression defaults match documented industry baselines`() {
        val cfg = AiCompressionConfig()
        // 32k 窗口留 25% 余量（参照 Claude Code ~92% / Cline 80% 触发线）
        assertEquals(24_000, cfg.budgetTokens)
        assertEquals(8, cfg.toolKeepRecent)
        assertEquals(2, cfg.keepUserSegments)
        assertEquals(30, cfg.timeoutSec)
        assertEquals(4_000, cfg.summaryMaxChars)
        assertEquals(DEFAULT_COMPACT_PROMPT, cfg.systemPrompt)
        assertTrue(DEFAULT_COMPACT_PROMPT.contains("未完成事项"))
    }
}
