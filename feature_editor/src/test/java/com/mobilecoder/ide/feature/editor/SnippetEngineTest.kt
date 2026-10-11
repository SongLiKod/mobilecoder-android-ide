package com.mobilecoder.ide.feature.editor

import com.mobilecoder.ide.core.storage.CodeSnippet
import com.mobilecoder.ide.core.storage.SnippetStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SnippetEngine] 纯逻辑：触发词解析、占位符展开、跳位光标映射、编辑后跳位平移。
 *
 * 只覆盖纯函数（org.json 在单测环境不可用，片段 JSON 持久化不在此测，见 SnippetStoreTest）。
 */
class SnippetEngineTest {

    // ---------------- 触发词解析 ----------------

    @Test
    fun `triggerAt 取光标前的标识符词干与起点`() {
        val trigger = SnippetEngine.triggerAt("val x = fori", cursor = 12)

        assertEquals("fori", trigger?.word)
        assertEquals(8, trigger?.start)
    }

    @Test
    fun `triggerAt 允许下划线与数字且换行后重新起词`() {
        assertEquals("a_1b", SnippetEngine.triggerAt("foo a_1b", cursor = 8)?.word)
        assertEquals("fun", SnippetEngine.triggerAt("class A\nfun", cursor = 11)?.word)
    }

    @Test
    fun `triggerAt 空格标点与行首不产生词干`() {
        assertNull(SnippetEngine.triggerAt("if (x)", cursor = 6))
        assertNull(SnippetEngine.triggerAt("fori", cursor = 0))
        assertNull(SnippetEngine.triggerAt("", cursor = 0))
    }

    @Test
    fun `triggerAt 越界光标回落到合法区间`() {
        assertEquals("fori", SnippetEngine.triggerAt("fori", cursor = 99)?.word)
        // 负光标夹到 0：词干为空，面板收起
        assertNull(SnippetEngine.triggerAt("fori", cursor = -3))
    }

    // ---------------- 占位符展开 ----------------

    @Test
    fun `expand 跳位按占位符出现顺序映射为插入文本内偏移`() {
        val expansion = SnippetEngine.expand("for (i in 0 until $1) {\n    $2\n}")

        assertEquals("for (i in 0 until ) {\n    \n}", expansion.text)
        assertEquals(listOf(18, 26), expansion.stops)
    }

    @Test
    fun `expand dollar0 是最终光标且缺省回落到末尾`() {
        val withZero = SnippetEngine.expand("val $1 = $2$0")
        assertEquals("val  = ", withZero.text)
        assertEquals(listOf(4, 7), withZero.stops)
        assertEquals(7, withZero.cursor)

        val withoutZero = SnippetEngine.expand("hello $1")
        assertEquals(6, withoutZero.cursor)
    }

    @Test
    fun `expand 同编号后续出现视为镜像并置空`() {
        val expansion = SnippetEngine.expand("$1 and $1")

        assertEquals(" and ", expansion.text)
        assertEquals(listOf(0), expansion.stops)
    }

    @Test
    fun `expand 反斜杠转义产生字面美元符`() {
        // 正文里的 `\$` 被转义为一个字面 `$`
        val expansion = SnippetEngine.expand("echo \\\$HOME")

        assertEquals("echo \$HOME", expansion.text)
        assertEquals(emptyList<Int>(), expansion.stops)
    }

    @Test
    fun `expand 非占位的美元符原样保留不吞字符`() {
        val expansion = SnippetEngine.expand("if [ -z \"$1\" ]; then echo $# fi")

        assertEquals("if [ -z \"\" ]; then echo $# fi", expansion.text)
        assertEquals(listOf(9), expansion.stops)
    }

    @Test
    fun `expand 多行按给定缩进续行且首行不加缩进`() {
        val expansion = SnippetEngine.expand("if ($1) {\n$2\n}", indent = "    ")

        assertEquals("if () {\n    \n    }", expansion.text)
        assertEquals(listOf(4, 12), expansion.stops)
    }

    @Test
    fun `expand 归一换行并去掉末尾空行`() {
        assertEquals("line1\nline2", SnippetEngine.expand("line1\r\nline2\r").text)
        assertEquals("only", SnippetEngine.expand("only\n\n").text)
    }

    // ---------------- 编辑后跳位平移 ----------------

    @Test
    fun `adjustStops 编辑在跳位之前后续跳位整体平移`() {
        // 在公共前缀 "aaa" 后插入 3 个字符，编辑区之后的两个跳位各右移 3
        val adjusted = SnippetEngine.adjustStops(
            prev = "aaaXYbbbZZ",
            cur = "aaaQQQXYbbbZZ",
            stops = listOf(5, 9),
        )

        assertEquals(listOf(8, 12), adjusted)
    }

    @Test
    fun `adjustStops 编辑在跳位之后跳位保持不动`() {
        val adjusted = SnippetEngine.adjustStops(
            prev = "aaaXYbbb",
            cur = "aaaXYbbb!!",
            stops = listOf(3, 5),
        )

        assertEquals(listOf(3, 5), adjusted)
    }

    @Test
    fun `adjustStops 编辑吃掉了跳位则夹到编辑区起点`() {
        // 删除 "aaXY"（原下标 2..5）：区间内的 4 夹回 2，区间后的 7 平移 -5 → 2
        val adjusted = SnippetEngine.adjustStops(
            prev = "aaXYbbbZZ",
            cur = "aaZZ",
            stops = listOf(4, 7),
        )

        assertEquals(listOf(2, 2), adjusted)
    }

    @Test
    fun `adjustStops 文本未变或无跳位时原样返回`() {
        assertEquals(listOf(1, 2), SnippetEngine.adjustStops("abc", "abc", listOf(1, 2)))
        assertEquals(emptyList<Int>(), SnippetEngine.adjustStops("a", "b", emptyList()))
    }

    // ---------------- 语言键与候选过滤 ----------------

    @Test
    fun `languageKeyOf 认识的语言映射到片段库语言键`() {
        assertEquals(SnippetStore.LANG_KOTLIN, SnippetEngine.languageKeyOf(Language.KOTLIN))
        assertEquals(SnippetStore.LANG_KOTLIN, SnippetEngine.languageKeyOf(Language.GRADLE))
        assertEquals(SnippetStore.LANG_JAVA, SnippetEngine.languageKeyOf(Language.JAVA))
        assertEquals(SnippetStore.LANG_JS, SnippetEngine.languageKeyOf(Language.JAVASCRIPT))
        assertEquals(SnippetStore.LANG_TS, SnippetEngine.languageKeyOf(Language.TYPESCRIPT))
        assertEquals(SnippetStore.LANG_HTML, SnippetEngine.languageKeyOf(Language.XML))
        assertEquals(SnippetStore.LANG_SHELL, SnippetEngine.languageKeyOf(Language.SHELL))
        // 识别不出的语言不过滤（面板只展示通用片段 + 全部语言）
        assertNull(SnippetEngine.languageKeyOf(Language.JSON))
    }

    @Test
    fun `candidates 前缀匹配且按语言过滤`() {
        val all = SnippetStore.builtin()
        val kotlinFori = SnippetEngine.candidates(all, "fori", SnippetStore.LANG_KOTLIN)
        val javaFori = SnippetEngine.candidates(all, "fori", SnippetStore.LANG_JAVA)

        assertEquals(listOf(SnippetStore.LANG_KOTLIN), kotlinFori.map { it.language }.distinct())
        assertEquals(listOf(SnippetStore.LANG_JAVA), javaFori.map { it.language }.distinct())
        // 通用片段（any）在任何语言下都参与匹配
        val todo = SnippetEngine.candidates(all, "to", SnippetStore.LANG_SHELL)
        assertTrue(todo.any { it.prefix == "todo" && it.language == SnippetStore.LANG_ANY })
    }

    @Test
    fun `candidates 空词干或无匹配返回空`() {
        val all = SnippetStore.builtin()
        assertEquals(emptyList<CodeSnippet>(), SnippetEngine.candidates(all, "", SnippetStore.LANG_KOTLIN))
        assertEquals(
            emptyList<CodeSnippet>(),
            SnippetEngine.candidates(all, "zzzz-no-such", null),
        )
    }
}
