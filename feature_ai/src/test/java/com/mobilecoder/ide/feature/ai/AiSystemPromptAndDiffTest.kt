package com.mobilecoder.ide.feature.ai

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** system prompt 组装与行级 Diff（写保护 / 可读性两块纯逻辑）。 */
class AiSystemPromptAndDiffTest {

    // ---------------- system prompt ----------------

    @Test
    fun `default prompt contains project root and no leftover placeholders`() {
        val root = File("C:/projects/MyApp")
        val prompt = AiController.systemPrompt(root)
        assertTrue(prompt.contains(root.absolutePath))
        assertTrue(prompt.contains("MyApp"))
        assertFalse(prompt.contains("{project}"))
        assertFalse(prompt.contains("{projectName}"))
        assertTrue(prompt.contains("search_replace"))
        assertTrue(prompt.contains("简体中文"))
    }

    // ---------------- diff（第二返回值为“是否因超限截断”，小输入恒为 false） ----------------

    @Test
    fun `identical files produce context only and no truncation`() {
        val lines = listOf("a", "b", "c")
        val (rows, truncated) = diffLines(lines, lines)
        assertFalse(truncated)
        assertTrue(rows.all { it.type == AiDiffRowType.CONTEXT })
        assertEquals(3, rows.size)
    }

    @Test
    fun `inserted line shows as ADD`() {
        val (rows, _) = diffLines(listOf("a", "c"), listOf("a", "b", "c"))
        val add = rows.single { it.type == AiDiffRowType.ADD }
        assertEquals("b", add.text)
        assertEquals(null, add.oldNo)
        assertTrue(rows.any { it.type == AiDiffRowType.CONTEXT })
    }

    @Test
    fun `removed line shows as DEL`() {
        val (rows, _) = diffLines(listOf("a", "b", "c"), listOf("a", "c"))
        val del = rows.single { it.type == AiDiffRowType.DEL }
        assertEquals("b", del.text)
        assertEquals(null, del.newNo)
    }

    @Test
    fun `wholesale rewrite keeps row numbers aligned`() {
        val (rows, _) = diffLines(listOf("old1", "old2"), listOf("new1"))
        val first = rows.first()
        assertEquals(AiDiffRowType.DEL, first.type)
        assertEquals(1, first.oldNo)
        val add = rows.first { it.type == AiDiffRowType.ADD }
        assertEquals(1, add.newNo)
        assertEquals("new1", add.text)
    }

    @Test
    fun `oversize input reports truncation`() {
        val big = (1..801).map { "line$it" }
        val (_, truncated) = diffLines(big, big)
        assertTrue(truncated)
    }
}
