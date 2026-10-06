package com.mobilecoder.ide.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 轻量 Markdown 解析：代码块 / 标题 / 列表 / 段落 / 行内样式（AI 回复渲染基础）。 */
class AiMarkdownTest {

    @Test
    fun `code fence captures language and body`() {
        val blocks = parseMarkdown("```kotlin\nval x = 1\nprintln(x)\n```")
        assertEquals(1, blocks.size)
        val code = blocks[0] as MdBlock.Code
        assertEquals("kotlin", code.lang)
        assertEquals("val x = 1\nprintln(x)", code.code)
    }

    @Test
    fun `unclosed fence still yields code block`() {
        val blocks = parseMarkdown("```\nhalf open")
        assertEquals(1, blocks.size)
        assertEquals("half open", (blocks[0] as MdBlock.Code).code)
    }

    @Test
    fun `headings capture level and text`() {
        val blocks = parseMarkdown("# 标题一\n\n### 标题三")
        assertEquals(2, blocks.size)
        val h1 = blocks[0] as MdBlock.Heading
        assertEquals(1, h1.level)
        assertEquals("标题一", plain(h1.spans))
        assertEquals(3, (blocks[1] as MdBlock.Heading).level)
    }

    @Test
    fun `consecutive bullets aggregate and ordered list detected`() {
        val bullets = parseMarkdown("- 甲\n- 乙")
        val b = bullets.single() as MdBlock.Bullet
        assertFalse(b.ordered)
        assertEquals(2, b.items.size)

        val ordered = parseMarkdown("1. first\n2. second")
        val o = ordered.single() as MdBlock.Bullet
        assertTrue(o.ordered)
        assertEquals("first", plain(o.items[0]))
    }

    @Test
    fun `paragraph keeps hard line breaks until blank line`() {
        val blocks = parseMarkdown("第一行\n第二行\n\n新段落")
        assertEquals(2, blocks.size)
        assertEquals("第一行\n第二行", plain((blocks[0] as MdBlock.Para).spans))
        assertEquals("新段落", plain((blocks[1] as MdBlock.Para).spans))
    }

    @Test
    fun `inline code bold and italic parsed`() {
        val spans = parseInline("使用 `read_file` 读取 **关键** 与 *斜体*")
        assertTrue(spans.any { it is MdSpan.Code && it.text == "read_file" })
        assertTrue(spans.any { it is MdSpan.Bold && it.text == "关键" })
        assertTrue(spans.any { it is MdSpan.Italic && it.text == "斜体" })
    }

    @Test
    fun `unmatched markers stay plain text`() {
        val spans = parseInline("1 + 1 ** 剩下的")
        assertTrue(spans.none { it is MdSpan.Bold })
        assertEquals("1 + 1 ** 剩下的", plain(spans))
    }

    @Test
    fun `mixed document order preserved`() {
        val blocks = parseMarkdown("# T\n\ntext\n\n```js\ncode\n```\n\n- a")
        assertEquals(
            listOf(true, true, true, true),
            listOf(
                blocks[0] is MdBlock.Heading,
                blocks[1] is MdBlock.Para,
                blocks[2] is MdBlock.Code,
                blocks[3] is MdBlock.Bullet,
            ),
        )
    }

    private fun plain(spans: List<MdSpan>): String = spans.joinToString("") {
        when (it) {
            is MdSpan.Plain -> it.text
            is MdSpan.Code -> it.text
            is MdSpan.Bold -> it.text
            is MdSpan.Italic -> it.text
        }
    }
}
