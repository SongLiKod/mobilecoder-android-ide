package com.mobilecoder.ide.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SnippetStore] 纯逻辑：内置库自洽、归一化校验、覆盖合并规则、前缀过滤排序。
 *
 * 只覆盖纯函数——`upsert` / `importJson` 走 org.json 与文件 IO，
 * 单测环境（mockable jar）里 org.json 不可用，不在此测。
 */
class SnippetStoreTest {

    private fun snippet(
        prefix: String,
        language: String = SnippetStore.LANG_KOTLIN,
        body: String = "body",
        builtin: Boolean = false,
        id: String = "id-$language-$prefix",
    ) = CodeSnippet(
        id = id,
        prefix = prefix,
        description = prefix,
        language = language,
        body = body,
        builtin = builtin,
    )

    // ---------------- 内置库自洽 ----------------

    @Test
    fun `builtin 覆盖全部语言且 id 唯一`() {
        val all = SnippetStore.builtin()

        assertTrue("内置库不应为空", all.isNotEmpty())
        assertEquals("内置 id 必须唯一", all.size, all.map { it.id }.distinct().size)
        val covered = all.map { it.language }.distinct()
        // 6 种代码语言 + 通用
        assertTrue("内置库应覆盖 Kotlin / Java / JS / TS / HTML / Shell / 通用", covered.size >= 7)
        assertTrue(SnippetStore.LANG_KOTLIN in covered)
        assertTrue(SnippetStore.LANG_ANY in covered)
    }

    @Test
    fun `builtin 每条都是内置标记且触发词合法非空`() {
        SnippetStore.builtin().forEach { s ->
            assertTrue("${s.id} 应标记为内置", s.builtin)
            assertTrue("${s.id} 触发词非法: ${s.prefix}", s.prefix.all { it.isLetterOrDigit() || it == '_' })
            assertTrue("${s.id} 正文不应为空", s.body.isNotBlank())
        }
    }

    // ---------------- 归一化 ----------------

    @Test
    fun `normalizeSnippet 去空白并回落语言与说明`() {
        val normalized = SnippetStore.normalizeSnippet(
            prefix = "  fori  ",
            description = "   ",
            language = "KOTLIN",
            body = "text\n",
        )

        assertNotNull(normalized)
        assertEquals("fori", normalized?.prefix)
        assertEquals("fori", normalized?.description) // 说明空白时回落为触发词
        assertEquals(SnippetStore.LANG_KOTLIN, normalized?.language) // 语言键大小写不敏感
        assertEquals("text", normalized?.body) // 末尾换行去掉
    }

    @Test
    fun `normalizeSnippet 未知语言回落到通用`() {
        val normalized = SnippetStore.normalizeSnippet("x", "d", "rust", "b")

        assertEquals(SnippetStore.LANG_ANY, normalized?.language)
    }

    @Test
    fun `normalizeSnippet 触发词非法或正文为空返回 null`() {
        assertNull(SnippetStore.normalizeSnippet("for i", "d", SnippetStore.LANG_KOTLIN, "b"))
        assertNull(SnippetStore.normalizeSnippet("for-i", "d", SnippetStore.LANG_KOTLIN, "b"))
        assertNull(SnippetStore.normalizeSnippet("for(i)", "d", SnippetStore.LANG_KOTLIN, "b"))
        assertNull(SnippetStore.normalizeSnippet("   ", "d", SnippetStore.LANG_KOTLIN, "b"))
        assertNull(SnippetStore.normalizeSnippet("ok", "d", SnippetStore.LANG_KOTLIN, "\n"))
    }

    @Test
    fun `key 大小写不敏感的覆盖判定键`() {
        assertEquals(
            snippet("ForI", SnippetStore.LANG_KOTLIN).key,
            snippet("fori", SnippetStore.LANG_KOTLIN).key,
        )
        // 语言不同则 key 不同（不互相覆盖）
        assertFalse(
            snippet("fori", SnippetStore.LANG_KOTLIN).key ==
                snippet("fori", SnippetStore.LANG_JAVA).key,
        )
    }

    // ---------------- 覆盖合并 ----------------

    @Test
    fun `mergeCustom 同语言同触发词的自定义覆盖内置且保持展示位置`() {
        val builtin = listOf(
            snippet("fori", SnippetStore.LANG_KOTLIN, builtin = true, id = "builtin:kotlin:fori"),
            snippet("fun", SnippetStore.LANG_KOTLIN, builtin = true, id = "builtin:kotlin:fun"),
        )
        val override = snippet("fori", SnippetStore.LANG_KOTLIN, body = "custom", id = "cus-1")

        val merged = SnippetStore.mergeCustom(builtin, listOf(override))

        assertEquals(2, merged.size)
        assertEquals("custom", merged[0].body)
        assertFalse("覆盖者以自定义身份展示（可编辑可删除）", merged[0].builtin)
        assertEquals("cus-1", merged[0].id)
        assertTrue("未被覆盖的内置保持内置", merged[1].builtin)
    }

    @Test
    fun `mergeCustom 删掉自定义后内置自动恢复`() {
        val builtin = listOf(snippet("fori", builtin = true, id = "builtin:kotlin:fori"))
        val withOverride = listOf(snippet("fori", body = "custom", id = "cus-1"))

        val restored = SnippetStore.mergeCustom(builtin, emptyList())

        assertEquals(1, restored.size)
        assertEquals("builtin:kotlin:fori", restored[0].id)
        assertTrue(restored[0].builtin)
        // 对照：合并且覆盖时内置那条被替换
        assertEquals("custom", SnippetStore.mergeCustom(builtin, withOverride)[0].body)
    }

    @Test
    fun `mergeCustom 非内置触发词追加在后且乱序入参结果稳定`() {
        val builtin = listOf(
            snippet("fori", builtin = true, id = "builtin:kotlin:fori"),
            snippet("fun", builtin = true, id = "builtin:kotlin:fun"),
        )
        val custom = listOf(
            snippet("zzz", body = "z", id = "cus-z"),
            snippet("aaa", body = "a", id = "cus-a"),
        )

        val merged = SnippetStore.mergeCustom(builtin, custom)

        assertEquals(listOf("fori", "fun", "zzz", "aaa"), merged.map { it.prefix })
    }

    @Test
    fun `mergeCustom 同 key 的自定义去重只保留第一条`() {
        val builtin = emptyList<CodeSnippet>()
        val duplicated = listOf(
            snippet("fori", body = "first", id = "cus-1"),
            snippet("FORI", body = "second", id = "cus-2"),
        )

        val merged = SnippetStore.mergeCustom(builtin, duplicated)

        assertEquals(1, merged.size)
        assertEquals("first", merged[0].body)
    }

    // ---------------- 前缀过滤 ----------------

    @Test
    fun `filterByPrefix 大小写不敏感前缀匹配`() {
        val list = listOf(
            snippet("composable"),
            snippet("class"),
            snippet("catch"),
        )

        val matched = SnippetStore.filterByPrefix(list, "CoM", null)

        assertEquals(listOf("composable"), matched.map { it.prefix })
    }

    @Test
    fun `filterByPrefix 语言过滤保留通用片段`() {
        val list = listOf(
            snippet("fori", SnippetStore.LANG_KOTLIN),
            snippet("fori", SnippetStore.LANG_JAVA),
            snippet("fort", SnippetStore.LANG_ANY),
        )

        val matched = SnippetStore.filterByPrefix(list, "for", SnippetStore.LANG_KOTLIN)

        assertEquals(
            listOf(SnippetStore.LANG_KOTLIN, SnippetStore.LANG_ANY),
            matched.map { it.language },
        )
    }

    @Test
    fun `filterByPrefix 短触发词优先且内置优先`() {
        val list = listOf(
            snippet("for", body = "custom"),
            snippet("fori", body = "custom2"),
            snippet("for", body = "builtin", builtin = true),
        )

        val matched = SnippetStore.filterByPrefix(list, "for", null)

        assertEquals(listOf("for", "for", "fori"), matched.map { it.prefix })
        assertTrue("同长度时内置排前", matched.first().builtin)
        assertFalse(matched[1].builtin)
        assertEquals("builtin", matched.first().body)
    }

    @Test
    fun `filterByPrefix 空词干返回空且最多 limit 条`() {
        val list = (1..20).map { snippet("s$it") }

        assertEquals(emptyList<CodeSnippet>(), SnippetStore.filterByPrefix(list, "", null))
        assertEquals(8, SnippetStore.filterByPrefix(list, "s", null).size)
        assertEquals(3, SnippetStore.filterByPrefix(list, "s", null, limit = 3).size)
    }
}
