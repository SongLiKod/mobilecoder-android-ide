package com.mobilecoder.ide.feature.editor

/**
 * 文件内查找 / 替换的纯文本运算（无 Android 依赖，便于单测）。
 *
 * 口径与编辑器保持一致：**大小写不敏感**、命中互不重叠（找到一处后从
 * `start + query.length` 继续扫描）、上限 [MAX_MATCHES]。
 */

/** 命中下标上限（对应 README「文件内查找（5000 命中上限）」）。 */
const val MAX_MATCHES = 5000

/** 一次替换的结果：新文本 + 实际命中数。 */
data class ReplaceOutcome(val text: String, val count: Int)

/**
 * 查找全部命中下标（1 起始行 / 0 起始偏移皆为下标语义，与光标偏移同源）。
 *
 * @param limit 命中上限，超出即停止扫描（防超大文件 / 极短词卡 UI）
 */
fun findAll(text: String, query: String, limit: Int = MAX_MATCHES): List<Int> {
    if (query.isEmpty() || text.isEmpty()) return emptyList()
    val out = ArrayList<Int>()
    var index = text.indexOf(query, 0, ignoreCase = true)
    while (index >= 0 && out.size < limit) {
        out.add(index)
        index = text.indexOf(query, index + query.length, true)
    }
    return out
}

/**
 * 替换 [text] 中的全部命中（「全部替换」与跨文件替换共用的纯实现）。
 *
 * 命中按升序互不重叠，因此正向拼接即可；替换文本是**字面量**（不做正则 / 变量展开），
 * 大小写按原文命中处保留、替换内容原样写入。
 */
fun replaceAllIn(text: String, query: String, replacement: String): ReplaceOutcome {
    if (query.isEmpty()) return ReplaceOutcome(text, 0)
    val matches = findAll(text, query)
    if (matches.isEmpty()) return ReplaceOutcome(text, 0)
    val builder = StringBuilder(text.length + matches.size * (replacement.length - query.length))
    var cursor = 0
    for (start in matches) {
        builder.append(text, cursor, start)
        builder.append(replacement)
        cursor = start + query.length
    }
    builder.append(text, cursor, text.length)
    return ReplaceOutcome(builder.toString(), matches.size)
}

/** 替换 `[start, start + queryLength)` 一段（「替换当前命中」用），越界自动裁剪。 */
fun replaceRange(text: String, start: Int, queryLength: Int, replacement: String): String {
    val from = start.coerceIn(0, text.length)
    val to = (start + queryLength).coerceIn(from, text.length)
    return text.substring(0, from) + replacement + text.substring(to)
}

/**
 * 全部替换后的光标重映射：[offset] 之前的每处替换带来
 * `replacement.length - queryLength` 的位移，逐处累加即可。
 *
 * @param matches 与本次替换同源的命中列表（[replaceAllIn] 的入参）
 */
fun remapOffset(
    matches: List<Int>,
    queryLength: Int,
    replacement: String,
    offset: Int,
): Int {
    val delta = replacement.length - queryLength
    var shift = 0
    for (start in matches) {
        if (start >= offset) break
        shift += delta
    }
    return (offset + shift).coerceAtLeast(0)
}
