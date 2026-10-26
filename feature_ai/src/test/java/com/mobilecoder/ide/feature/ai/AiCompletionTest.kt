package com.mobilecoder.ide.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 行内补全：提示词结构 + 各种模型「怪输出」的清洗规则（幽灵文本必须能直接插入光标）。 */
class AiCompletionTest {

    // ------------------------------------------------------------------
    // 提示词
    // ------------------------------------------------------------------

    @Test
    fun `user prompt keeps cursor prefix and suffix sections`() {
        val prompt = AiCompletion.userPrompt("val x = ", "\nprintln(x)")
        assertTrue(prompt.contains("【光标前】"))
        assertTrue(prompt.contains("val x = "))
        assertTrue(prompt.contains("【光标后】"))
        assertTrue(prompt.contains("\nprintln(x)"))
        // 前后顺序不能颠倒，否则模型会续写错位置
        assertTrue(prompt.indexOf("【光标前】") < prompt.indexOf("【光标后】"))
    }

    @Test
    fun `system prompt names language and falls back to plain code`() {
        assertTrue(AiCompletion.systemPrompt("Kotlin").contains("Kotlin"))
        assertTrue(AiCompletion.systemPrompt("").contains("代码"))
        assertTrue(AiCompletion.systemPrompt("JavaScript").contains("JavaScript"))
    }

    // ------------------------------------------------------------------
    // stripFence
    // ------------------------------------------------------------------

    @Test
    fun `strip fence returns inner body`() {
        assertEquals("val a = 1", AiCompletion.stripFence("```kotlin\nval a = 1\n```"))
    }

    @Test
    fun `strip fence drops nothing when no fence present`() {
        assertEquals("val a = 1", AiCompletion.stripFence("val a = 1"))
    }

    @Test
    fun `strip fence keeps content of truncated fence`() {
        // 被 max_tokens 截断的半截围栏：只剩开栏，正文全部保留
        assertEquals("val a = 1\nval b = 2", AiCompletion.stripFence("```kotlin\nval a = 1\nval b = 2"))
    }

    // ------------------------------------------------------------------
    // stripTokenEcho
    // ------------------------------------------------------------------

    @Test
    fun `token echo drops duplicated partial stem`() {
        // 光标停在 `resu`，模型按整词输出 `result = 1`
        assertEquals("lt = 1", AiCompletion.stripTokenEcho("val resu", "result = 1"))
    }

    @Test
    fun `token echo keeps text that does not start with stem`() {
        assertEquals(" = 1", AiCompletion.stripTokenEcho("val resu", " = 1"))
    }

    @Test
    fun `token echo ignores stem shorter than two chars`() {
        assertEquals("x", AiCompletion.stripTokenEcho("val ", "x"))
    }

    // ------------------------------------------------------------------
    // clean（端到端清洗）
    // ------------------------------------------------------------------

    @Test
    fun `clean extracts fenced suggestion and trims surrounding blanks`() {
        val out = AiCompletion.clean("\n```kotlin\nval a = 1\n```\n", "val b = ")
        assertEquals("val a = 1", out)
    }

    @Test
    fun `clean drops text after closing fence`() {
        val out = AiCompletion.clean("```\nval a = 1\n```\n模型的多余解释", "")
        assertEquals("val a = 1", out)
    }

    @Test
    fun `clean caps line count`() {
        val raw = (1..20).joinToString("\n") { "line$it" }
        val out = AiCompletion.clean(raw, "")
        assertEquals(AiCompletion.MAX_LINES, out.lines().size)
        assertEquals("line1", out.lines().first())
    }

    @Test
    fun `clean caps total characters`() {
        val raw = "a".repeat(AiCompletion.MAX_CHARS + 100)
        val out = AiCompletion.clean(raw, "")
        assertTrue(out.length <= AiCompletion.MAX_CHARS)
    }

    @Test
    fun `clean removes leading blank lines when cursor sits at line start`() {
        val out = AiCompletion.clean("\n\nval a = 1", "    ")
        // 光标行只有缩进：模型的空行与重复缩进都要去掉
        assertEquals("val a = 1", out)
    }

    @Test
    fun `clean does not repeat indentation of current line`() {
        val out = AiCompletion.clean("    println(1)", "    ")
        assertEquals("println(1)", out)
    }

    @Test
    fun `clean returns empty for blank or whitespace output`() {
        assertEquals("", AiCompletion.clean("   \n  ", "val x = "))
        assertEquals("", AiCompletion.clean("```\n```", "val x = "))
    }

    @Test
    fun `clean normalizes windows line endings`() {
        assertEquals("a\nb", AiCompletion.clean("a\r\nb", ""))
    }

    @Test
    fun `clean strips echoed stem in real flow`() {
        val out = AiCompletion.clean("result = compute()", "val resu")
        assertEquals("lt = compute()", out)
    }

    @Test
    fun `clean suggestion never contains fence markers`() {
        val out = AiCompletion.clean("```kotlin\nval a = 1\n```", "")
        assertFalse(out.contains("```"))
    }
}
