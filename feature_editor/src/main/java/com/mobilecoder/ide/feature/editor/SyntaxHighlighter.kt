package com.mobilecoder.ide.feature.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import com.mobilecoder.ide.core.common.theme.AppColor
import com.mobilecoder.ide.core.common.theme.AppPalette

/** 主题色（0xRRGGBB）→ Compose 颜色（拆分 RGB 通道，不引入任何硬编码色值）。 */
fun AppColor.toComposeColor(): Color = Color(
    red = (rgb shr 16 and 255) / 255f,
    green = (rgb shr 8 and 255) / 255f,
    blue = (rgb and 255) / 255f,
)

/** 编辑器支持的文件语言（PRD 2.2：Java / Kotlin / Dart / JS / XML / Gradle 等）。 */
enum class Language(val label: String) {
    KOTLIN("Kotlin"),
    JAVA("Java"),
    XML("XML"),
    GRADLE("Gradle"),
    PROPERTIES("属性文件"),
    JSON("JSON"),
    JAVASCRIPT("JavaScript"),
    TYPESCRIPT("TypeScript"),
    DART("Dart"),
    SHELL("Shell"),
    MARKDOWN("Markdown"),
    PLAIN("纯文本");

    /** 是否按括号 / 块注释做结构分析（折叠、括号检查）。 */
    val structured: Boolean get() = this in STRUCTURED

    companion object {
        private val STRUCTURED = setOf(
            KOTLIN, JAVA, JAVASCRIPT, TYPESCRIPT, DART, GRADLE, JSON, SHELL, PLAIN,
        )

        /** 依据文件名识别语言。 */
        fun of(fileName: String): Language {
            val lower = fileName.lowercase()
            return when {
                lower.endsWith(".gradle") || lower.endsWith(".gradle.kts") -> GRADLE
                lower.endsWith(".kt") -> KOTLIN
                lower.endsWith(".java") -> JAVA
                lower.endsWith(".xml") || lower.endsWith(".html") || lower.endsWith(".manifest") -> XML
                lower.endsWith(".json") -> JSON
                lower.endsWith(".tsx") || lower.endsWith(".ts") -> TYPESCRIPT
                lower.endsWith(".jsx") || lower.endsWith(".js") -> JAVASCRIPT
                lower.endsWith(".dart") -> DART
                lower.endsWith(".sh") || lower.endsWith(".bash") || lower.endsWith(".zsh") -> SHELL
                lower.endsWith(".md") || lower.endsWith(".markdown") -> MARKDOWN
                lower.endsWith(".properties") || lower.endsWith(".pro") || lower.endsWith(".cfg") ||
                    lower.endsWith(".ini") || lower.endsWith(".yml") || lower.endsWith(".yaml") ||
                    lower.endsWith(".toml") -> PROPERTIES
                else -> PLAIN
            }
        }
    }
}

/**
 * 高亮配色：全部派生自 [AppPalette]，随深浅主题自动切换。
 * 关键字=primary、字符串=success、注释=onSurfaceVariant、数字=warning，
 * 标签 / 属性 / 类型 / 函数取自终端派生色，保持同一色系。
 */
data class HighlightColors(
    val base: Color,
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val type: Color,
    val function: Color,
    val tag: Color,
    val attribute: Color,
    val variable: Color,
    val annotation: Color,
    val heading: Color,
    val link: Color,
    val code: Color,
    val findMatch: Color,
    val findCurrent: Color,
    val foldMarker: Color,
    val foldMarkerBackground: Color,
    val selection: Color,
    val error: Color,
    val warning: Color,
)

/** 由当前主题调色板派生编辑器高亮配色。 */
fun highlightColorsOf(palette: AppPalette): HighlightColors = HighlightColors(
    base = palette.onBackground.toComposeColor(),
    keyword = palette.primary.toComposeColor(),
    string = palette.success.toComposeColor(),
    comment = palette.onSurfaceVariant.toComposeColor(),
    number = palette.warning.toComposeColor(),
    type = palette.terminalMagenta.toComposeColor(),
    function = palette.terminalCyan.toComposeColor(),
    tag = palette.terminalBlue.toComposeColor(),
    attribute = palette.terminalYellow.toComposeColor(),
    variable = palette.terminalCyan.toComposeColor(),
    annotation = palette.warning.toComposeColor(),
    heading = palette.primary.toComposeColor(),
    link = palette.terminalBlue.toComposeColor(),
    code = palette.success.toComposeColor(),
    findMatch = palette.primary.toComposeColor().copy(alpha = 0.28f),
    findCurrent = palette.warning.toComposeColor().copy(alpha = 0.45f),
    foldMarker = palette.onSurfaceVariant.toComposeColor(),
    foldMarkerBackground = palette.surfaceVariant.toComposeColor().copy(alpha = 0.65f),
    selection = palette.terminalSelection.toComposeColor(),
    error = palette.error.toComposeColor(),
    warning = palette.warning.toComposeColor(),
)

/**
 * 自写轻量语法高亮扫描器（离线环境不引入任何三方高亮库）。
 *
 * 覆盖 Kotlin / Java / XML / Gradle / properties / JSON / JS / TS / Dart / Shell / Markdown。
 * 单次线性扫描，按 [MAX_HIGHLIGHT_CHARS] 截断防止大文件卡顿。
 */
class SyntaxHighlighter(private val colors: HighlightColors) {

    private var cachedText: String? = null
    private var cachedLang: Language = Language.PLAIN
    private var cachedResult: AnnotatedString? = null

    fun highlight(text: String, language: Language): AnnotatedString {
        val cached = cachedResult
        if (cached != null && cachedText == text && cachedLang == language) return cached
        val result = if (text.length > MAX_HIGHLIGHT_CHARS) {
            AnnotatedString(text)
        } else {
            scan(text, language)
        }
        cachedText = text
        cachedLang = language
        cachedResult = result
        return result
    }

    private fun scan(text: String, language: Language): AnnotatedString {
        val builder = AnnotatedString.Builder()
        builder.append(text)
        when (language) {
            Language.XML -> scanXml(text, builder)
            Language.MARKDOWN -> scanMarkdown(text, builder)
            Language.JSON -> scanJson(text, builder)
            Language.PROPERTIES -> scanProperties(text, builder)
            else -> scanGeneric(text, language, builder)
        }
        return builder.toAnnotatedString()
    }

    // ------------------------------------------------------------------
    // 通用 C 风格语言（Kotlin / Java / JS / TS / Dart / Gradle / Shell / 文本）
    // ------------------------------------------------------------------

    private fun scanGeneric(text: String, language: Language, b: AnnotatedString.Builder) {
        val keywords = keywordsOf(language)
        val lineComments = lineCommentsOf(language)
        val block = language.blockComment()
        val triple = language == Language.KOTLIN
        val charLiterals = language in CHAR_LANGUAGES
        val shellVars = language == Language.SHELL
        val n = text.length
        var i = 0
        while (i < n) {
            val c = text[i]
            when {
                c == '\n' -> i++
                lineComments.any { text.startsWith(it, i) } -> {
                    val end = text.indexOf('\n', i).let { if (it < 0) n else it }
                    b.addStyle(SpanStyle(color = colors.comment), i, end)
                    i = end
                }
                block != null && text.startsWith(block.first, i) -> {
                    val close = block.second
                    val rel = text.indexOf(close, i + block.first.length)
                    val end = if (rel < 0) n else rel + close.length
                    b.addStyle(SpanStyle(color = colors.comment), i, end)
                    i = end
                }
                triple && text.startsWith("\"\"\"", i) -> {
                    val rel = text.indexOf("\"\"\"", i + 3)
                    val end = if (rel < 0) n else rel + 3
                    b.addStyle(SpanStyle(color = colors.string), i, end)
                    i = end
                }
                c == '"' || (c == '\'' && (charLiterals || shellVars)) -> {
                    val end = scanQuoted(text, i, c)
                    b.addStyle(SpanStyle(color = colors.string), i, end)
                    i = end
                }
                shellVars && c == '$' -> {
                    val end = scanShellVar(text, i)
                    b.addStyle(SpanStyle(color = colors.variable), i, end)
                    i = end
                }
                c == '@' && language in ANNOTATION_LANGUAGES && i + 1 < n && text[i + 1].isLetter() -> {
                    var j = i + 1
                    while (j < n && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '.')) j++
                    b.addStyle(SpanStyle(color = colors.annotation), i, j)
                    i = j
                }
                c.isDigit() -> {
                    var j = i
                    if (c == '0' && i + 1 < n && (text[i + 1] == 'x' || text[i + 1] == 'X')) {
                        j = i + 2
                        while (j < n && text[j].isLetterOrDigit()) j++
                    } else {
                        while (j < n && (text[j].isDigit() || text[j] == '.' || text[j] == '_')) j++
                    }
                    b.addStyle(SpanStyle(color = colors.number), i, j)
                    i = j
                }
                c.isLetter() || c == '_' || c == '$' -> {
                    var j = i
                    while (j < n && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '$')) j++
                    val word = text.substring(i, j)
                    val style = when {
                        word in keywords -> SpanStyle(color = colors.keyword)
                        j < n && text[j] == '(' -> SpanStyle(color = colors.function)
                        word.first().isUpperCase() -> SpanStyle(color = colors.type)
                        else -> null
                    }
                    if (style != null) b.addStyle(style, i, j)
                    i = j
                }
                else -> i++
            }
        }
    }

    // ------------------------------------------------------------------
    // XML（标签 / 属性 / 属性值）
    // ------------------------------------------------------------------

    private fun scanXml(text: String, b: AnnotatedString.Builder) {
        val n = text.length
        val tagStyle = SpanStyle(color = colors.tag)
        val attrStyle = SpanStyle(color = colors.attribute)
        val strStyle = SpanStyle(color = colors.string)
        val commentStyle = SpanStyle(color = colors.comment)
        var i = 0
        while (i < n) {
            val c = text[i]
            when {
                text.startsWith("<!--", i) -> {
                    val rel = text.indexOf("-->", i + 4)
                    val end = if (rel < 0) n else rel + 3
                    b.addStyle(commentStyle, i, end)
                    i = end
                }
                text.startsWith("<?", i) || text.startsWith("<!", i) -> {
                    val rel = text.indexOf('>', i + 2)
                    val end = if (rel < 0) n else rel + 1
                    b.addStyle(tagStyle, i, end)
                    i = end
                }
                c == '<' -> i = scanXmlTag(text, i, b)
                c == '"' || c == '\'' -> {
                    val end = scanQuoted(text, i, c)
                    b.addStyle(strStyle, i, end)
                    i = end
                }
                else -> i++
            }
        }
    }

    private fun scanXmlTag(text: String, start: Int, b: AnnotatedString.Builder): Int {
        val n = text.length
        val tagStyle = SpanStyle(color = colors.tag)
        val attrStyle = SpanStyle(color = colors.attribute)
        val strStyle = SpanStyle(color = colors.string)
        var j = start + 1
        if (j < n && text[j] == '/') j++
        val nameStart = j
        while (j < n && (text[j].isLetterOrDigit() || text[j] in "-_:.")) j++
        if (j == nameStart) return start + 1
        b.addStyle(tagStyle, start, nameStart)
        b.addStyle(tagStyle, nameStart, j)
        while (j < n && text[j] != '>') {
            when {
                text[j].isWhitespace() -> j++
                text[j] == '/' && j + 1 < n && text[j + 1] == '>' -> {
                    b.addStyle(tagStyle, j, j + 2)
                    j += 2
                }
                text[j] == '"' || text[j] == '\'' -> {
                    val q = text[j]
                    val s = j
                    j++
                    while (j < n && text[j] != q) j++
                    if (j < n) j++
                    b.addStyle(strStyle, s, j)
                }
                else -> {
                    val aStart = j
                    while (j < n && text[j] != '=' && text[j] != '>' && text[j] != '/' && !text[j].isWhitespace()) j++
                    if (j == aStart) {
                        j++
                    } else {
                        b.addStyle(attrStyle, aStart, j)
                        while (j < n && text[j].isWhitespace()) j++
                        if (j < n && text[j] == '=') {
                            j++
                            while (j < n && text[j].isWhitespace()) j++
                            if (j < n && (text[j] == '"' || text[j] == '\'')) {
                                val q = text[j]
                                val s = j
                                j++
                                while (j < n && text[j] != q) j++
                                if (j < n) j++
                                b.addStyle(strStyle, s, j)
                            }
                        }
                    }
                }
            }
        }
        if (j < n && text[j] == '>') {
            b.addStyle(tagStyle, j, j + 1)
            return j + 1
        }
        return j
    }

    // ------------------------------------------------------------------
    // JSON（键 = 属性色，字符串 / 数字 / 字面量）
    // ------------------------------------------------------------------

    private fun scanJson(text: String, b: AnnotatedString.Builder) {
        val n = text.length
        var i = 0
        while (i < n) {
            val c = text[i]
            when {
                c == '"' -> {
                    val end = scanQuoted(text, i, '"')
                    var k = end
                    while (k < n && text[k].isWhitespace()) k++
                    val style = if (k < n && text[k] == ':') colors.attribute else colors.string
                    b.addStyle(SpanStyle(color = style), i, end)
                    i = end
                }
                c.isDigit() -> {
                    var j = i
                    while (j < n && (text[j].isDigit() || text[j] == '.' || text[j] == 'e' || text[j] == 'E' || text[j] == '+' || text[j] == '-')) j++
                    b.addStyle(SpanStyle(color = colors.number), i, j)
                    i = j
                }
                c.isLetter() -> {
                    var j = i
                    while (j < n && text[j].isLetter()) j++
                    val word = text.substring(i, j)
                    if (word == "true" || word == "false" || word == "null") {
                        b.addStyle(SpanStyle(color = colors.keyword), i, j)
                    }
                    i = j
                }
                else -> i++
            }
        }
    }

    // ------------------------------------------------------------------
    // properties / yml（# ! 注释，键 = 属性色）
    // ------------------------------------------------------------------

    private fun scanProperties(text: String, b: AnnotatedString.Builder) {
        val n = text.length
        var i = 0
        while (i < n) {
            val lineEnd = text.indexOf('\n', i).let { if (it < 0) n else it }
            val line = text.substring(i, lineEnd)
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("#") || trimmed.startsWith("!") ->
                    b.addStyle(SpanStyle(color = colors.comment), i, lineEnd)
                else -> {
                    val eq = line.indexOf('=').let { if (it < 0) line.indexOf(':') else it }
                    if (eq > 0) {
                        b.addStyle(SpanStyle(color = colors.attribute), i, i + eq)
                        val value = line.substring(eq + 1).trimStart()
                        if (value.startsWith('"') || value.startsWith("'")) {
                            val vStart = i + eq + 1 + (line.length - eq - 1 - value.length)
                            b.addStyle(SpanStyle(color = colors.string), vStart, lineEnd)
                        }
                    }
                }
            }
            i = lineEnd + 1
        }
    }

    // ------------------------------------------------------------------
    // Markdown（标题 / 围栏代码 / 行内代码 / 链接）
    // ------------------------------------------------------------------

    private fun scanMarkdown(text: String, b: AnnotatedString.Builder) {
        val n = text.length
        var i = 0
        var inFence = false
        while (i < n) {
            val lineEnd = text.indexOf('\n', i).let { if (it < 0) n else it }
            val line = text.substring(i, lineEnd)
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("```") -> {
                    inFence = !inFence
                    b.addStyle(SpanStyle(color = colors.comment), i, lineEnd)
                }
                inFence -> b.addStyle(SpanStyle(color = colors.code), i, lineEnd)
                trimmed.startsWith("#") && trimmed.dropWhile { it == '#' }.startsWith(" ") -> {
                    b.addStyle(
                        SpanStyle(color = colors.heading, fontWeight = FontWeight.Bold),
                        i, lineEnd,
                    )
                }
                else -> scanMarkdownInline(text, i, lineEnd, b)
            }
            i = lineEnd + 1
        }
    }

    private fun scanMarkdownInline(text: String, from: Int, to: Int, b: AnnotatedString.Builder) {
        var i = from
        while (i < to) {
            when {
                text[i] == '`' -> {
                    val rel = text.indexOf('`', i + 1)
                    val end = if (rel < 0 || rel > to) to else rel + 1
                    b.addStyle(SpanStyle(color = colors.code), i, end)
                    i = end
                }
                text.startsWith("**", i) -> {
                    val rel = text.indexOf("**", i + 2)
                    val end = if (rel < 0 || rel > to) to else rel + 2
                    b.addStyle(SpanStyle(fontWeight = FontWeight.Bold), i, end)
                    i = end
                }
                text[i] == '[' -> {
                    val rel = text.indexOf(']', i)
                    if (rel in 0..to && rel + 1 < to && text[rel + 1] == '(') {
                        val close = text.indexOf(')', rel)
                        val end = if (close < 0 || close > to) to else close + 1
                        b.addStyle(SpanStyle(color = colors.link), i, end)
                        i = end
                    } else i++
                }
                else -> i++
            }
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 扫描带引号字符串（支持反斜杠转义），返回结束下标（不含换行）。 */
    private fun scanQuoted(text: String, start: Int, quote: Char): Int {
        val n = text.length
        var i = start + 1
        while (i < n) {
            when (text[i]) {
                '\\' -> i += 2
                quote -> return i + 1
                '\n' -> return i
                else -> i++
            }
        }
        return n
    }

    /** Shell 变量：`$name` / `${name}` / `$1`。 */
    private fun scanShellVar(text: String, start: Int): Int {
        val n = text.length
        var i = start + 1
        if (i < n && text[i] == '{') {
            while (i < n && text[i] != '}') i++
            return if (i < n) i + 1 else n
        }
        while (i < n && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '?')) i++
        return if (i == start + 1) start + 1 else i
    }

    companion object {
        /** 超过该长度的文本不做高亮，保证输入流畅。 */
        const val MAX_HIGHLIGHT_CHARS = 200_000

        private val CHAR_LANGUAGES = setOf(
            Language.KOTLIN, Language.JAVA, Language.JAVASCRIPT,
            Language.TYPESCRIPT, Language.DART,
        )
        private val ANNOTATION_LANGUAGES = setOf(
            Language.KOTLIN, Language.JAVA, Language.DART,
        )

        private fun Language.blockComment(): Pair<String, String>? = when (this) {
            Language.XML -> "<!--" to "-->"
            Language.PROPERTIES, Language.SHELL, Language.MARKDOWN, Language.JSON -> null
            else -> "/*" to "*/"
        }

        internal fun lineCommentsOf(language: Language): List<String> = when (language) {
            Language.SHELL, Language.PROPERTIES -> listOf("#")
            Language.PLAIN -> listOf("#", "//")
            Language.JSON, Language.XML, Language.MARKDOWN -> emptyList()
            else -> listOf("//")
        }

        internal fun keywordsOf(language: Language): Set<String> = when (language) {
            Language.KOTLIN, Language.GRADLE -> KOTLIN_KEYWORDS
            Language.JAVA -> JAVA_KEYWORDS
            Language.JAVASCRIPT -> JS_KEYWORDS
            Language.TYPESCRIPT -> JS_KEYWORDS + TS_KEYWORDS
            Language.DART -> DART_KEYWORDS
            Language.SHELL -> SHELL_KEYWORDS
            Language.PLAIN -> emptySet()
            else -> KOTLIN_KEYWORDS
        }

        private val KOTLIN_KEYWORDS = setOf(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if",
            "in", "interface", "is", "null", "object", "package", "return", "super", "this",
            "throw", "true", "try", "typealias", "val", "var", "when", "while", "by", "catch",
            "constructor", "delegate", "dynamic", "field", "file", "finally", "get", "import",
            "init", "param", "property", "receiver", "set", "setparam", "where", "actual",
            "abstract", "annotation", "companion", "const", "crossinline", "data", "enum",
            "expect", "external", "final", "infix", "inline", "inner", "internal", "lateinit",
            "noinline", "operator", "open", "out", "override", "private", "protected", "public",
            "reified", "sealed", "suspend", "tailrec", "vararg", "it", "fun", "String", "Int",
            "Long", "Boolean", "Double", "Float", "List", "Map", "Set", "Unit",
        )

        private val JAVA_KEYWORDS = setOf(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false",
            "null", "var", "record",
        )

        private val JS_KEYWORDS = setOf(
            "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger",
            "default", "delete", "do", "else", "export", "extends", "finally", "for", "function",
            "if", "import", "in", "instanceof", "let", "new", "of", "return", "static", "super",
            "switch", "this", "throw", "try", "typeof", "var", "void", "while", "with", "yield",
            "true", "false", "null", "undefined",
        )

        private val TS_KEYWORDS = setOf(
            "type", "interface", "enum", "namespace", "declare", "readonly", "private",
            "protected", "public", "abstract", "implements", "any", "unknown", "never", "string",
            "number", "boolean", "keyof", "typeof", "as", "satisfies",
        )

        private val DART_KEYWORDS = setOf(
            "abstract", "as", "assert", "async", "await", "break", "case", "catch", "class",
            "const", "continue", "covariant", "default", "deferred", "do", "dynamic", "else",
            "enum", "export", "extends", "external", "factory", "false", "final", "finally", "for",
            "get", "if", "implements", "import", "in", "is", "late", "library", "mixin", "new",
            "null", "on", "operator", "part", "required", "rethrow", "return", "set", "static",
            "super", "switch", "sync", "this", "throw", "true", "try", "typedef", "var", "void",
            "while", "with", "yield",
        )

        private val SHELL_KEYWORDS = setOf(
            "if", "then", "else", "elif", "fi", "case", "esac", "for", "while", "until", "do",
            "done", "in", "function", "select", "time", "return", "exit", "local", "export",
            "source", "echo", "read", "set", "unset", "shift", "trap", "declare", "readonly",
        )
    }
}
