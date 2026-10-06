package com.mobilecoder.ide.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `@` 引用：触发提取与项目文件模糊匹配排序。 */
class AiMentionTest {

    private val index = listOf(
        "README.md",
        "app/src/main/java/Main.kt",
        "app/src/main/java/util/Helper.kt",
        "lib/src/notes.md",
        "docs/guide/getting-started.md",
        "settings.gradle.kts",
        "src/App.kt",
        "src/AppTest.kt",
        "a/b/c/deep.txt",
        "xyz/abacus.md",
    )

    @Test
    fun `mention triggers at start and after whitespace`() {
        assertEquals(0 to "foo", extractMention("@foo"))
        assertEquals(3 to "foo", extractMention("hi @foo"))
        assertEquals(3 to "foo", extractMention("hi\n@foo"))
    }

    @Test
    fun `mention ignored without preceding whitespace`() {
        assertNull(extractMention("mail@host"))
        assertNull(extractMention("a@b"))
    }

    @Test
    fun `mention query ends at whitespace and only last at counts`() {
        assertNull(extractMention("@foo bar"))
        val hit = extractMention("x @a y @b")
        assertEquals(7 to "b", hit)
        assertEquals(0 to "", extractMention("@"))
    }

    @Test
    fun `basename prefix ranks first`() {
        val result = suggestPaths(index, "read")
        assertEquals("README.md", result.first())
    }

    @Test
    fun `path prefix ranks above deep contains`() {
        val result = suggestPaths(index, "src/")
        assertTrue(result.isNotEmpty())
        // src/App.kt / src/AppTest.kt 是路径前缀，应排在 app/src/... 之前
        assertEquals("src/App.kt", result.first())
    }

    @Test
    fun `contains and subsequence both match`() {
        val contains = suggestPaths(index, "helper")
        assertTrue(contains.any { it.endsWith("Helper.kt") })

        val sub = suggestPaths(index, "abc")
        assertEquals(listOf("xyz/abacus.md", "a/b/c/deep.txt"), sub)
    }

    @Test
    fun `no match returns empty and results capped at 8`() {
        assertTrue(suggestPaths(index, "zzzzzz").isEmpty())
        assertTrue(suggestPaths(emptyList(), "a").isEmpty())
        assertTrue(suggestPaths(index, "").size <= 8)
    }

    @Test
    fun `subsequence helper`() {
        assertTrue(isSubsequence("abc", "a/b/c"))
        assertTrue(isSubsequence("ab", "ab"))
        assertTrue(isSubsequence("", "anything"))
        assertTrue(!isSubsequence("acb", "abc"))
    }
}
