package com.mobilecoder.ide.feature.editor

/**
 * 文件内符号（大纲 / 方法导航）。
 *
 * @param name 符号名（Markup/JSON 等会带修饰，如 `div#app`、`<manifest>`）
 * @param line 1 起始行号
 * @param depth 缩进层级（0 = 顶层），用于大纲列表缩进显示
 */
data class CodeSymbol(
    val name: String,
    val line: Int,
    val kind: SymbolKind,
    val depth: Int = 0,
)

/** 符号类型（大纲徽标与文案）。 */
enum class SymbolKind(val label: String, val badge: String) {
    CLASS("类", "C"),
    INTERFACE("接口", "I"),
    OBJECT("对象", "O"),
    ENUM("枚举", "E"),
    MIXIN("混入", "X"),
    FUNCTION("函数", "F"),
    METHOD("方法", "M"),
    PROPERTY("属性", "V"),
    FIELD("字段", "f"),
    HEADING("标题", "H"),
    ELEMENT("节点", "N"),
}

private const val MAX_SYMBOLS = 400
private const val MAX_OUTLINE_LINES = 40_000

/**
 * 从 [text] 中提取可导航符号（大纲 / 方法导航数据源）。
 *
 * 按语言分派解析器：类 C 系（Kotlin/Java/JS/TS/Dart）走「逐行 + 花括号栈」扫描，
 * Markdown 取标题，XML/HTML 取标签，JSON/属性文件取顶层键，Shell 取函数定义。
 * 解析是启发式的，宁缺毋滥：识别不了的行一律跳过。
 */
fun extractSymbols(text: String, language: Language): List<CodeSymbol> {
    if (text.isBlank()) return emptyList()
    return when (language) {
        Language.KOTLIN, Language.GRADLE, Language.JAVA,
        Language.JAVASCRIPT, Language.TYPESCRIPT, Language.DART,
        -> extractBraceLanguage(text, language)

        Language.MARKDOWN -> extractMarkdown(text)
        Language.XML -> extractMarkup(text)
        Language.JSON -> extractJsonKeys(text)
        Language.PROPERTIES -> extractPropertyKeys(text)
        Language.SHELL -> extractShellFunctions(text)
        Language.PLAIN -> emptyList()
    }
}

// ---------------------------------------------------------------------------
// 类 C 系语言：逐行扫描 + 花括号栈（区分类型块 / 普通块）
// ---------------------------------------------------------------------------

/** 被当作方法/函数名的控制流关键字（避免把 `if (...)` 当成声明）。 */
private val CONTROL_KEYWORDS = setOf(
    "if", "else", "for", "while", "switch", "case", "catch", "do", "return", "try",
    "throw", "new", "super", "this", "break", "continue", "synchronized", "instanceof",
    "assert", "yield", "await", "typeof", "function", "in", "is", "when",
    "val", "var", "fun", "class", "interface", "object", "package",
    "import", "then", "do", "done", "fi", "esac", "elif",
)

private fun extractBraceLanguage(text: String, language: Language): List<CodeSymbol> {
    val out = ArrayList<CodeSymbol>()
    val kotlinLike = language == Language.KOTLIN || language == Language.GRADLE
    val jsLike = language == Language.JAVASCRIPT || language == Language.TYPESCRIPT

    // 花括号栈：true = 该块是类型（class/interface/object…）块
    val blockStack = ArrayDeque<Boolean>()
    var pendingType = false // 上一行声明了类型但尚未见到 `{`
    var inBlockComment = false
    var inTriple = false
    var lineNo = 0

    fun add(name: String, kind: SymbolKind, depth: Int) {
        if (name.isEmpty() || name in CONTROL_KEYWORDS) return
        if (out.size >= MAX_SYMBOLS) return
        if (out.any { it.line == lineNo && it.name == name }) return
        out.add(CodeSymbol(name, lineNo, kind, depth))
    }

    for (raw in text.lineSequence()) {
        lineNo++
        if (lineNo > MAX_OUTLINE_LINES || out.size >= MAX_SYMBOLS) break

        var line = raw
        if (inBlockComment) {
            val end = line.indexOf("*/")
            if (end < 0) continue
            line = line.substring(end + 2)
            inBlockComment = false
        }
        if (inTriple) {
            val end = line.indexOf("\"\"\"")
            if (end < 0) continue
            line = line.substring(end + 3)
            inTriple = false
        }

        val stripped = stripLine(line, language, jsLike, kotlinLike)
        if (stripped.blockComment) inBlockComment = true
        if (stripped.triple) inTriple = true
        val code = stripped.code
        if (code.isBlank()) continue

        // 类型声明与 `{` 不在同一行时，仅当紧随其后的行以 `{` 开头才认账
        val trimmed = code.trimStart()
        if (pendingType && !trimmed.startsWith("{")) pendingType = false

        val typeDepth = blockStack.count { it }
        val insideType = blockStack.lastOrNull() == true
        val kindDepth = typeDepth

        when {
            kotlinLike -> {
                val type = KOTLIN_TYPE.find(trimmed)
                if (type != null) {
                    val keyword = type.groupValues[1]
                    val name = type.groupValues[2]
                    val kind = when {
                        keyword == "interface" -> SymbolKind.INTERFACE
                        keyword == "object" -> SymbolKind.OBJECT
                        trimmed.contains("enum class") -> SymbolKind.ENUM
                        else -> SymbolKind.CLASS
                    }
                    add(name, kind, kindDepth)
                    pendingType = true
                }
                val fn = KOTLIN_FUN.find(trimmed)
                if (fn != null) {
                    add(
                        fn.groupValues[1],
                        if (insideType) SymbolKind.METHOD else SymbolKind.FUNCTION,
                        kindDepth,
                    )
                }
                val prop = KOTLIN_PROPERTY.find(trimmed)
                if (prop != null && (blockStack.isEmpty() || insideType)) {
                    add(prop.groupValues[1], SymbolKind.PROPERTY, kindDepth)
                }
            }

            language == Language.JAVA -> {
                val type = JAVA_TYPE.find(trimmed)
                if (type != null) {
                    val keyword = type.groupValues[1]
                    val kind = when (keyword) {
                        "interface" -> SymbolKind.INTERFACE
                        "enum" -> SymbolKind.ENUM
                        else -> SymbolKind.CLASS
                    }
                    add(type.groupValues[2], kind, kindDepth)
                    pendingType = true
                }
                val method = JAVA_METHOD.find(trimmed)
                if (method != null) {
                    add(
                        method.groupValues[1],
                        if (insideType) SymbolKind.METHOD else SymbolKind.FUNCTION,
                        kindDepth,
                    )
                }
                if (insideType) {
                    val field = JAVA_FIELD.find(trimmed)
                    if (field != null) add(field.groupValues[1], SymbolKind.FIELD, kindDepth)
                }
            }

            else -> { // JS / TS / Dart
                val type = when {
                    jsLike -> TS_CLASS.find(trimmed) ?: TS_INTERFACE.find(trimmed)
                    else -> DART_TYPE.find(trimmed)
                }
                if (type != null) {
                    val keyword = type.groupValues[1]
                    val kind = when {
                        keyword == "interface" -> SymbolKind.INTERFACE
                        keyword == "enum" -> SymbolKind.ENUM
                        keyword == "mixin" || keyword == "extension" -> SymbolKind.MIXIN
                        else -> SymbolKind.CLASS
                    }
                    add(type.groupValues[2], kind, kindDepth)
                    pendingType = true
                }
                val fn = when {
                    !jsLike -> null
                    else -> JS_FUNCTION.find(trimmed) ?: JS_ARROW.find(trimmed)
                }
                if (fn != null) {
                    add(
                        fn.groupValues[1],
                        if (insideType) SymbolKind.METHOD else SymbolKind.FUNCTION,
                        kindDepth,
                    )
                }
                val method = when {
                    jsLike -> JS_METHOD.find(trimmed)
                    else -> DART_METHOD.find(trimmed)
                }
                if (method != null) {
                    add(
                        method.groupValues[1],
                        if (insideType) SymbolKind.METHOD else SymbolKind.FUNCTION,
                        kindDepth,
                    )
                }
            }
        }

        // 花括号入/出栈（字符串与注释已剥离，不会误计）
        for (ch in code) {
            when (ch) {
                '{' -> {
                    blockStack.addLast(pendingType)
                    pendingType = false
                }

                '}' -> if (blockStack.isNotEmpty()) blockStack.removeLast()
            }
        }
    }
    return out
}

private class StripResult(val code: String, val blockComment: Boolean, val triple: Boolean)

/** 去掉行内注释与字符串字面量（保留结构性字符），并识别跨行注释 / 三引号字符串。 */
private fun stripLine(
    line: String,
    language: Language,
    jsLike: Boolean,
    kotlinLike: Boolean,
): StripResult {
    val sb = StringBuilder(line.length)
    var i = 0
    val n = line.length
    var block = false
    var triple = false
    val charLiterals = language in setOf(
        Language.KOTLIN, Language.GRADLE, Language.JAVA, Language.DART,
        Language.JAVASCRIPT, Language.TYPESCRIPT,
    )
    val allowBacktick = jsLike || language == Language.DART

    while (i < n) {
        val c = line[i]
        if (c == '/' && i + 1 < n && line[i + 1] == '/') return StripResult(sb.toString(), false, false)
        if (c == '/' && i + 1 < n && line[i + 1] == '*') {
            i += 2
            val end = line.indexOf("*/", i)
            if (end < 0) return StripResult(sb.toString(), true, false)
            i = end + 2
            continue
        }
        if (kotlinLike && c == '"' && line.startsWith("\"\"\"", i)) {
            val end = line.indexOf("\"\"\"", i + 3)
            if (end < 0) return StripResult(sb.toString(), false, true)
            i = end + 3
            continue
        }
        val isQuote = c == '"' || (c == '\'' && charLiterals) || (c == '`' && allowBacktick)
        if (isQuote) {
            val quote = c
            i++
            var closed = false
            while (i < n) {
                if (line[i] == '\\') {
                    i += 2
                    continue
                }
                if (line[i] == quote) {
                    i++
                    closed = true
                    break
                }
                i++
            }
            if (!closed) return StripResult(sb.toString(), false, false)
            sb.append(quote).append(quote)
            continue
        }
        sb.append(c)
        i++
    }
    return StripResult(sb.toString(), false, false)
}

// 类型 / 函数 / 成员声明（在剥离注释与字符串后的行上匹配）
private val KOTLIN_MODS =
    "(?:(?:public|private|internal|protected|open|abstract|sealed|data|annotation|value|" +
        "inner|enum|inline|expect|actual|external)\\s+)*"
private val KOTLIN_ANNOT = "(?:@\\w+(?:\\([^)]*\\))?\\s+)*"
private val KOTLIN_TYPE = Regex(
    "^$KOTLIN_ANNOT$KOTLIN_MODS(class|interface|object)\\s+([A-Za-z_]\\w*)",
)
private val KOTLIN_FUN = Regex(
    "^$KOTLIN_ANNOT(?:(?:public|private|internal|protected|open|abstract|override|inline|" +
        "suspend|operator|infix|tailrec|external|expect|actual)\\s+)*fun\\s+" +
        "(?:<[^>]*>\\s+)?(?:[A-Za-z_]\\w*\\.)?([A-Za-z_]\\w*)",
)
private val KOTLIN_PROPERTY = Regex(
    "^$KOTLIN_ANNOT(?:(?:public|private|internal|protected|override|open|const|lateinit|" +
        "suspend|tailrec|expect|actual)\\s+)*(?:val|var)\\s+([A-Za-z_]\\w*)",
)

private val JAVA_TYPE = Regex(
    "^(?:(?:public|private|protected|static|final|abstract|strictfp|sealed)\\s+)*" +
        "(class|interface|enum|record)\\s+([A-Za-z_]\\w*)",
)
private val JAVA_METHOD = Regex(
    "^(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|" +
        "strictfp)\\s+)*[A-Za-z_]\\w*(?:<[^>]*>)?(?:\\[\\])?\\s+([A-Za-z_]\\w*)\\s*\\([^)]*\\)" +
        "\\s*(?:throws\\s+[\\w,\\.\\s]+)?(?:\\{|;|$)",
)
private val JAVA_FIELD = Regex(
    "^(?:(?:public|private|protected|static|final|volatile|transient)\\s+)+" +
        "[A-Za-z_]\\w*(?:<[^>]*>)?(?:\\[\\])?\\s+([A-Za-z_]\\w*)\\s*(?:=[^=]*|;)?\\s*$",
)

private val TS_CLASS = Regex(
    "^(?:(?:export|default|abstract|declare)\\s+)*(class)\\s+([A-Za-z_$]\\w*)",
)
private val TS_INTERFACE = Regex(
    "^(?:(?:export|default|declare)\\s+)*(interface|enum)\\s+([A-Za-z_$]\\w*)",
)
private val JS_FUNCTION = Regex(
    "^(?:(?:export|default)\\s+)*(?:async\\s+)?function\\s*\\*?\\s*([A-Za-z_$]\\w*)",
)
private val JS_ARROW = Regex(
    "^(?:(?:export|default)\\s+)?(?:const|let|var)\\s+([A-Za-z_$]\\w*)\\s*(?::[^=]+)?=" +
        "\\s*(?:async\\s*)?(?:\\([^)]*\\)|[A-Za-z_$]\\w*)\\s*=>",
)
private val JS_METHOD = Regex(
    "^(?:(?:public|private|protected|static|readonly|async|get|set)\\s+)*" +
        "([A-Za-z_$]\\w*)\\s*\\([^)]*\\)\\s*[^;{]*\\{",
)
private val DART_TYPE = Regex(
    "^(?:(?:abstract|base|final|interface|sealed)\\s+)*(class|mixin|enum|extension)\\s+" +
        "([A-Za-z_]\\w*)",
)
private val DART_METHOD = Regex(
    "^(?:[A-Za-z_]\\w*(?:<[^>]*>)?(?:\\[\\])?\\s+)+" +
        "(?:static\\s+|final\\s+|const\\s+|required\\s+)*([A-Za-z_]\\w*)\\s*\\([^)]*\\)" +
        "\\s*(?:\\w+\\s*)*(?:\\{|=>|;|$)",
)

// ---------------------------------------------------------------------------
// Markdown 标题
// ---------------------------------------------------------------------------

private fun extractMarkdown(text: String): List<CodeSymbol> {
    val out = ArrayList<CodeSymbol>()
    var inFence = false
    var lineNo = 0
    val heading = Regex("^(#{1,6})\\s+(.+?)\\s*#*\\s*$")
    for (line in text.lineSequence()) {
        lineNo++
        if (lineNo > MAX_OUTLINE_LINES || out.size >= MAX_SYMBOLS) break
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            inFence = !inFence
            continue
        }
        if (inFence) continue
        val match = heading.find(trimmed) ?: continue
        val title = match.groupValues[2].trim().take(60)
        if (title.isEmpty()) continue
        out.add(CodeSymbol(title, lineNo, SymbolKind.HEADING, match.groupValues[1].length - 1))
    }
    return out
}

// ---------------------------------------------------------------------------
// XML / HTML 标签
// ---------------------------------------------------------------------------

private val VOID_ELEMENTS = setOf(
    "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta",
    "param", "source", "track", "wbr",
)
private val STRUCTURAL_TAGS = setOf(
    "html", "head", "body", "script", "style", "section", "header", "footer", "nav",
    "main", "article", "aside", "form", "table", "ul", "ol", "template",
)
private val MARKUP_TAG = Regex("</?([A-Za-z][\\w:.-]*)([^>]*)>")
private val MARKUP_ID = Regex("id\\s*=\\s*[\"']([^\"']+)[\"']")

private fun extractMarkup(text: String): List<CodeSymbol> {
    val out = ArrayList<CodeSymbol>()
    val openTags = ArrayDeque<String>()
    var inComment = false
    var lineNo = 0

    for (raw in text.lineSequence()) {
        lineNo++
        if (lineNo > MAX_OUTLINE_LINES || out.size >= MAX_SYMBOLS) break

        // 剥离注释（跨行）
        val clean = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            if (inComment) {
                val end = raw.indexOf("-->", i)
                if (end < 0) {
                    i = raw.length
                } else {
                    inComment = false
                    i = end + 3
                }
                continue
            }
            if (raw.startsWith("<!--", i)) {
                inComment = true
                i += 4
                continue
            }
            clean.append(raw[i])
            i++
        }
        val line = clean.toString()

        for (match in MARKUP_TAG.findAll(line).toList()) {
            val name = match.groupValues[1].lowercase()
            val attrs = match.groupValues[2]
            val isClose = match.value.startsWith("</")
            if (isClose) {
                if (openTags.isNotEmpty() && openTags.last() == name) openTags.removeLast()
                continue
            }
            val selfClosing = attrs.trimEnd().endsWith("/") || name in VOID_ELEMENTS
            val depth = openTags.size

            val headingLevel = if (name.length == 2 && name[0] == 'h' && name[1] in '1'..'6') {
                name[1] - '0'
            } else {
                0
            }
            val id = MARKUP_ID.find(attrs)?.groupValues?.get(1)
            val symbol = when {
                headingLevel > 0 -> {
                    val textContent = line.substring(match.range.last + 1)
                        .substringBefore('<').trim().take(60)
                    val display = if (textContent.isNotEmpty()) textContent else "<$name>"
                    CodeSymbol(display, lineNo, SymbolKind.HEADING, headingLevel - 1)
                }

                id != null -> CodeSymbol("$name#$id", lineNo, SymbolKind.ELEMENT, depth)

                depth <= 1 || name in STRUCTURAL_TAGS ->
                    CodeSymbol("<$name>", lineNo, SymbolKind.ELEMENT, depth)

                else -> null
            }
            if (symbol != null && out.size < MAX_SYMBOLS &&
                out.none { it.line == lineNo && it.name == symbol.name }
            ) {
                out.add(symbol)
            }
            if (!selfClosing) openTags.addLast(name)
        }
    }
    return out
}

// ---------------------------------------------------------------------------
// JSON 顶层键 / 属性文件键 / Shell 函数
// ---------------------------------------------------------------------------

private fun extractJsonKeys(text: String): List<CodeSymbol> {
    val out = ArrayList<CodeSymbol>()
    var depth = 0
    var lineNo = 0
    val key = Regex("^\\s*\"([^\"]+)\"\\s*:")
    for (raw in text.lineSequence()) {
        lineNo++
        if (lineNo > MAX_OUTLINE_LINES || out.size >= MAX_SYMBOLS) break
        if (depth <= 2) {
            val match = key.find(raw)
            if (match != null) {
                out.add(
                    CodeSymbol(
                        match.groupValues[1].take(60),
                        lineNo,
                        SymbolKind.PROPERTY,
                        (depth - 1).coerceAtLeast(0),
                    ),
                )
            }
        }
        // 统计字符串之外的花括号，推进深度
        val stripped = stripLine(raw, Language.JSON, jsLike = false, kotlinLike = false)
        for (ch in stripped.code) {
            when (ch) {
                '{' -> depth++
                '}' -> depth = (depth - 1).coerceAtLeast(0)
            }
        }
    }
    return out
}

private fun extractPropertyKeys(text: String): List<CodeSymbol> {
    val out = ArrayList<CodeSymbol>()
    val key = Regex("^\\s*([A-Za-z_][\\w.\\-]*)\\s*[=:]")
    var lineNo = 0
    for (line in text.lineSequence()) {
        lineNo++
        if (lineNo > MAX_OUTLINE_LINES || out.size >= MAX_SYMBOLS) break
        if (line.trimStart().startsWith("#") || line.trimStart().startsWith("!")) continue
        val match = key.find(line) ?: continue
        out.add(CodeSymbol(match.groupValues[1], lineNo, SymbolKind.PROPERTY, 0))
    }
    return out
}

private fun extractShellFunctions(text: String): List<CodeSymbol> {
    val out = ArrayList<CodeSymbol>()
    val fn = Regex("^\\s*(?:function\\s+)?([A-Za-z_][\\w\\-]*)\\s*\\(\\s*\\)\\s*\\{?")
    var lineNo = 0
    for (line in text.lineSequence()) {
        lineNo++
        if (lineNo > MAX_OUTLINE_LINES || out.size >= MAX_SYMBOLS) break
        val match = fn.find(line) ?: continue
        val name = match.groupValues[1]
        if (name in CONTROL_KEYWORDS) continue
        out.add(CodeSymbol(name, lineNo, SymbolKind.FUNCTION, 0))
    }
    return out
}
