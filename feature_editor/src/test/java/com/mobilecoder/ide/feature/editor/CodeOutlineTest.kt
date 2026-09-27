package com.mobilecoder.ide.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [extractSymbols] 大纲 / 方法导航解析。 */
class CodeOutlineTest {

    private fun names(text: String, language: Language): List<CodeSymbol> = extractSymbols(text, language)

    @Test
    fun `Kotlin 类、方法与顶层函数`() {
        val source = """
            |package demo
            |
            |class Greeter(private val name: String) {
            |    fun greet(): String {
            |        return "hello"
            |    }
            |
            |    fun nested() {
            |        fun inner() = 1
            |        val local = 2
            |    }
            |}
            |
            |fun topLevel() = Unit
        """.trimMargin()

        val symbols = names(source, Language.KOTLIN)
        assertEquals(
            listOf(
                "CLASS:Greeter:3:0",
                "METHOD:greet:4:1",
                "METHOD:nested:8:1",
                "FUNCTION:inner:9:1",
                "FUNCTION:topLevel:14:0",
            ),
            symbols.map { "${it.kind}:${it.name}:${it.line}:${it.depth}" },
        )
        // 函数体内的局部变量不应进入大纲
        assertTrue(symbols.none { it.name == "local" || it.name == "name" })
    }

    @Test
    fun `Kotlin 接口、对象与枚举`() {
        val source = """
            |interface Clickable
            |object Registry
            |enum class Color { RED }
            |class Outer {
            |    val title = "t"
            |}
        """.trimMargin()

        val symbols = names(source, Language.KOTLIN)
        assertEquals(
            listOf(
                "INTERFACE:Clickable:1",
                "OBJECT:Registry:2",
                "ENUM:Color:3",
                "CLASS:Outer:4",
                "PROPERTY:title:5",
            ),
            symbols.map { "${it.kind}:${it.name}:${it.line}" },
        )
        assertTrue(symbols.any { it.kind == SymbolKind.PROPERTY && it.name == "title" })
    }

    @Test
    fun `Java 类、字段、方法且不误报控制流`() {
        val source = """
            |public class Main {
            |    private int count = 0;
            |
            |    public static void main(String[] args) {
            |        if (args.length > 0) {
            |            System.out.println("hi");
            |        }
            |    }
            |}
        """.trimMargin()

        val symbols = names(source, Language.JAVA)
        assertEquals(
            listOf("CLASS:Main:1", "FIELD:count:2", "METHOD:main:4"),
            symbols.map { "${it.kind}:${it.name}:${it.line}" },
        )
    }

    @Test
    fun `JavaScript 类、方法、箭头函数`() {
        val source = """
            |export class Api {
            |  constructor() { this.base = ""; }
            |  async get(id) { return id; }
            |}
            |const fetchAll = async (url) => fetch(url);
            |function helper() {
            |  return 1;
            |}
        """.trimMargin()

        val symbols = names(source, Language.JAVASCRIPT)
        assertEquals(
            listOf(
                "CLASS:Api:1",
                "METHOD:constructor:2",
                "METHOD:get:3",
                "FUNCTION:fetchAll:5",
                "FUNCTION:helper:6",
            ),
            symbols.map { "${it.kind}:${it.name}:${it.line}" },
        )
    }

    @Test
    fun `Markdown 标题层级且跳过代码块`() {
        val source = """
            |# Title
            |## Section
            |
            |```bash
            |## InCode
            |```
            |
            |### End
        """.trimMargin()

        val symbols = names(source, Language.MARKDOWN)
        assertEquals(
            listOf("Title:1:0", "Section:2:1", "End:8:2"),
            symbols.map { "${it.name}:${it.line}:${it.depth}" },
        )
    }

    @Test
    fun `HTML 标题与带 id 的节点`() {
        val source = """
            |<!DOCTYPE html>
            |<html>
            |<head>
            |  <title>App</title>
            |</head>
            |<body>
            |  <div id="root"></div>
            |  <h1>Hello</h1>
            |</body>
            |</html>
        """.trimMargin()

        val symbols = names(source, Language.XML)
        assertEquals(
            listOf("<html>:2", "<head>:3", "<body>:6", "div#root:7", "Hello:8"),
            symbols.map { "${it.name}:${it.line}" },
        )
    }

    @Test
    fun `JSON 取根层键`() {
        val source = """
            |{
            |  "name": "demo",
            |  "nested": {
            |    "deep": 1
            |  },
            |  "other": 2
            |}
        """.trimMargin()

        val symbols = names(source, Language.JSON)
        assertEquals(
            listOf("name:2", "nested:3", "deep:4", "other:6"),
            symbols.map { "${it.name}:${it.line}" },
        )
    }

    @Test
    fun `属性文件与 Shell 函数`() {
        val properties = """
            |# 注释
            |org.gradle.jvmargs=-Xmx2g
            |android.useAndroidX=true
        """.trimMargin()
        assertEquals(
            listOf("org.gradle.jvmargs:2", "android.useAndroidX:3"),
            names(properties, Language.PROPERTIES).map { "${it.name}:${it.line}" },
        )

        val shell = """
            |#!/bin/bash
            |deploy() {
            |  echo hi
            |}
        """.trimMargin()
        assertEquals(
            listOf("deploy:2"),
            names(shell, Language.SHELL).map { "${it.name}:${it.line}" },
        )
    }

    @Test
    fun `纯文本与空文本没有符号`() {
        assertTrue(names("", Language.KOTLIN).isEmpty())
        assertTrue(names("just some text\nanother line", Language.PLAIN).isEmpty())
    }

    @Test
    fun `analyzeCode 一并产出大纲符号`() {
        val result = analyzeCode("class A {\n    fun b() = 1\n}\n", Language.KOTLIN)
        assertEquals(listOf("A", "b"), result.symbols.map { it.name })
        assertTrue(result.symbols.none { it.name == "A" && it.kind != SymbolKind.CLASS })
    }
}
