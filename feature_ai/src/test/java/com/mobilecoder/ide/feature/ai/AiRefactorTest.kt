package com.mobilecoder.ide.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI 重构：提示词两种模式、模型输出提取、区间替换与落盘前的截断提醒。 */
class AiRefactorTest {

    // ------------------------------------------------------------------
    // extract
    // ------------------------------------------------------------------

    @Test
    fun `extract takes fenced body with language tag`() {
        val code = extract("```kotlin\nval a = 1\nval b = 2\n```")
        assertEquals("val a = 1\nval b = 2", code)
    }

    @Test
    fun `extract takes truncated fence body`() {
        assertEquals("val a = 1", extract("```kotlin\nval a = 1"))
    }

    @Test
    fun `extract treats plain output as code`() {
        assertEquals("val a = 1", extract("val a = 1"))
    }

    @Test
    fun `extract normalizes crlf and trims blank edges`() {
        assertEquals("a\nb", extract("\r\n\r\na\r\nb\r\n\r\n"))
    }

    @Test
    fun `extract keeps inner blank lines`() {
        assertEquals("a\n\nb", extract("```\na\n\nb\n```"))
    }

    // ------------------------------------------------------------------
    // splice
    // ------------------------------------------------------------------

    @Test
    fun `splice replaces target range`() {
        assertEquals("pre NEW post", splice("pre OLD post", 4, 7, "NEW"))
    }

    @Test
    fun `splice inserts when range is empty`() {
        assertEquals("aXb", splice("ab", 1, 1, "X"))
    }

    @Test
    fun `splice clamps out of bound indices`() {
        assertEquals("Xbc", splice("abc", -5, 1, "X"))
        assertEquals("abcX", splice("abc", 99, 99, "X"))
    }

    // ------------------------------------------------------------------
    // userPrompt
    // ------------------------------------------------------------------

    @Test
    fun `whole file mode asks for complete file output`() {
        val prompt = userPrompt("拆分大函数", "Kotlin", "app/Main.kt", "val a = 1", -1, -1)
        assertTrue(prompt.contains("任务：拆分大函数"))
        assertTrue(prompt.contains("app/Main.kt"))
        assertTrue(prompt.contains("Kotlin"))
        assertTrue(prompt.contains("【完整文件】"))
        assertTrue(prompt.contains("输出**完整文件**"))
        assertTrue(prompt.contains("val a = 1"))
    }

    @Test
    fun `empty task falls back to default purpose`() {
        val prompt = userPrompt("   ", "Kotlin", "a.kt", "x", -1, -1)
        assertTrue(prompt.contains("重构代码，保持行为不变"))
    }

    @Test
    fun `slice mode asks for selection only and carries context`() {
        val source = "a\nb\nc"
        val prompt = userPrompt("重命名", "Kotlin", "a.kt", source, 2, 3)
        assertTrue(prompt.contains("【选中的代码】"))
        assertTrue(prompt.contains("b"))
        assertTrue(prompt.contains("只输出**选中片段**"))
        assertTrue(prompt.contains("【上文】"))
        assertTrue(prompt.contains("【下文】"))
        assertTrue(prompt.contains("c"))
        assertTrue(!prompt.contains("【完整文件】"))
    }

    @Test
    fun `slice mode without leading context omits before section`() {
        val source = "b\nc"
        // 选中第一行：没有上文
        val prompt = userPrompt("重命名", "Kotlin", "a.kt", source, 0, 1)
        assertTrue(prompt.contains("【选中的代码】"))
        assertTrue(!prompt.contains("【上文】"))
    }

    // ------------------------------------------------------------------
    // warningOf
    // ------------------------------------------------------------------

    @Test
    fun `warning triggers when whole file shrinks drastically`() {
        val old = (1..40).joinToString("\n") { "line$it" }
        val new = "only one"
        val warning = warningOf(old, new, sliced = false)
        assertTrue(warning.contains("40 行"))
        assertTrue(warning.contains("1 行"))
    }

    @Test
    fun `no warning for similar length or small file`() {
        val old = (1..40).joinToString("\n") { "line$it" }
        val new = (1..38).joinToString("\n") { "line$it" }
        assertEquals("", warningOf(old, new, sliced = false))
        assertEquals("", warningOf("a\nb", "c", sliced = false))
    }

    @Test
    fun `no warning for slice mode`() {
        val old = (1..40).joinToString("\n") { "line$it" }
        assertEquals("", warningOf(old, "x", sliced = true))
    }

    // ------------------------------------------------------------------
    // oversizedError（整文件体积闸门）
    // ------------------------------------------------------------------

    @Test
    fun `oversized whole file is blocked with hint to select a slice`() {
        val error = oversizedError(MAX_SOURCE_CHARS + 1, sliced = false)
        assertTrue(error != null && error.contains("文件过大"))
        assertTrue(error!!.contains("选中"))
    }

    @Test
    fun `whole file within limit passes`() {
        assertEquals(null, oversizedError(MAX_SOURCE_CHARS, sliced = false))
        assertEquals(null, oversizedError(1_000, sliced = false))
    }

    @Test
    fun `slice mode is not limited by file size`() {
        assertEquals(null, oversizedError(MAX_SOURCE_CHARS * 10, sliced = true))
    }
}
