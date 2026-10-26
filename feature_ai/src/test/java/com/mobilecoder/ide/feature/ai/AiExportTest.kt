package com.mobilecoder.ide.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 会话导出 Markdown：文档头、问答正文、**工具调用记录**与参数 JSON、文件名安全化。 */
class AiExportTest {

    private fun session(vararg messages: AiMessage, title: String = "会话") = AiSession(
        id = "s1",
        title = title,
        updatedAt = 1_700_000_000_000,
        messages = messages.toList(),
    )

    private fun assistant(
        content: String,
        events: List<AiToolEvent> = emptyList(),
        toolCalls: List<ApiToolCall> = emptyList(),
        snapshotId: String? = null,
    ) = AiMessage(
        role = "assistant",
        content = content,
        events = events,
        toolCalls = toolCalls,
        snapshotId = snapshotId,
        time = 1_700_000_000_000,
    )

    @Test
    fun `header carries title project endpoint and message count`() {
        val md = session(
            AiMessage(role = "user", content = "你好", time = 1_700_000_000_000),
            assistant("在的"),
            title = "登录页重构",
        ).toMarkdown(projectName = "Demo", endpointLabel = "deepseek/deepseek-chat", now = 1_700_000_000_000)

        assertTrue(md.contains("# AI 会话：登录页重构"))
        assertTrue(md.contains("- 项目：Demo"))
        assertTrue(md.contains("- 接口 / 模型：deepseek/deepseek-chat"))
        assertTrue(md.contains("- 消息数：2 条"))
        assertTrue(md.contains("导出时间"))
        assertTrue(md.contains("## 提问"))
        assertTrue(md.contains("## 回复"))
        assertTrue(md.contains("你好"))
        assertTrue(md.contains("在的"))
        assertTrue(md.contains("由 MobileCoder IDE 导出"))
    }

    @Test
    fun `empty session is explained instead of blank page`() {
        val md = session(title = "空会话").toMarkdown(now = 1_700_000_000_000)
        assertTrue(md.contains("该会话还没有任何消息"))
        assertFalse(md.contains("## 提问"))
    }

    @Test
    fun `tool events are exported with ok flag target and summary`() {
        val md = session(
            assistant(
                content = "改好了",
                events = listOf(
                    AiToolEvent(name = "write_file", target = "app/Main.kt", ok = true, summary = "写入 12 行"),
                    AiToolEvent(name = "read_file", target = "missing.txt", ok = false, summary = "文件不存在"),
                ),
            ),
        ).toMarkdown(now = 1_700_000_000_000)

        assertTrue(md.contains("**工具调用**"))
        assertTrue(md.contains("1. ✅ `write_file` · `app/Main.kt` — 写入 12 行"))
        assertTrue(md.contains("2. ❌ `read_file` · `missing.txt` — 文件不存在"))
    }

    @Test
    fun `tool call arguments are exported as json block`() {
        val md = session(
            assistant(
                content = "",
                toolCalls = listOf(
                    ApiToolCall(id = "c1", name = "search_replace", arguments = """{"path":"a.kt","old":"x","new":"y"}"""),
                ),
            ),
        ).toMarkdown(now = 1_700_000_000_000)

        assertTrue(md.contains("**调用参数**"))
        assertTrue(md.contains("```json"))
        assertTrue(md.contains("\"name\": \"search_replace\""))
        assertTrue(md.contains("\"path\": \"a.kt\""))
        // 没有文字内容也要显式占位，避免导出空章节
        assertTrue(md.contains("（无文字内容）"))
    }

    @Test
    fun `invalid tool arguments survive as raw string`() {
        val json = argumentsJson(listOf(ApiToolCall(id = "c1", name = "run_command", arguments = "ls -la")))
        assertTrue(json.contains("run_command"))
        assertTrue(json.contains("ls -la"))
    }

    @Test
    fun `snapshot id is mentioned so undo stays discoverable`() {
        val md = session(
            assistant(content = "已修改", snapshotId = "snap-42"),
        ).toMarkdown(now = 1_700_000_000_000)
        assertTrue(md.contains("snap-42"))
        assertTrue(md.contains("撤销"))
    }

    @Test
    fun `tool outcomes are not dumped into the document`() {
        val md = session(
            AiMessage(
                role = "assistant",
                content = "读完了",
                toolCalls = listOf(ApiToolCall(id = "c1", name = "read_file", arguments = "{}")),
                toolOutcomes = listOf(AiToolOutcome(toolCallId = "c1", content = "x".repeat(5000))),
                time = 1_700_000_000_000,
            ),
        ).toMarkdown(now = 1_700_000_000_000)
        assertFalse(md.contains("xxxx"))
    }

    @Test
    fun `missing project and endpoint are omitted from header`() {
        val md = session(AiMessage(role = "user", content = "hi", time = 1)).toMarkdown(now = 1)
        assertFalse(md.contains("- 项目："))
        assertFalse(md.contains("- 接口 / 模型："))
    }

    // ------------------------------------------------------------------
    // 文件名 / 时间
    // ------------------------------------------------------------------

    @Test
    fun `export file name is sanitized and stamped`() {
        val name = exportFileName("登录/页:重构?  v2", 1_700_000_000_000)
        assertTrue(name.startsWith("ai-"))
        assertTrue(name.endsWith(".md"))
        assertFalse(name.substring(3, name.length - 3).contains(Regex("""[\\/:*?"<>|\s]""")))
        assertTrue(name.contains("-"))
    }

    @Test
    fun `export file name falls back when title is blank`() {
        val name = exportFileName("   ", 1_700_000_000_000)
        assertTrue(name.startsWith("ai-session-"))
        assertTrue(name.endsWith(".md"))
        assertFalse(name.contains("  "))
    }

    @Test
    fun `export file name truncates very long titles`() {
        val name = exportFileName("长".repeat(200), 1_700_000_000_000)
        assertTrue(name.length < 70)
    }

    @Test
    fun `blank clock is hidden and real clock is formatted`() {
        assertEquals("", formatClock(0))
        assertEquals("", formatClock(-1))
        assertTrue(Regex("""^\d{2}:\d{2}$""").matches(formatClock(1_700_000_000_000)))
    }
}
