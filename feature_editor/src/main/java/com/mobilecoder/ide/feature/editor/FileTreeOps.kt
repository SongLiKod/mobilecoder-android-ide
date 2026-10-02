package com.mobilecoder.ide.feature.editor

import com.mobilecoder.ide.core.storage.FileNode
import java.io.File

/**
 * 文件树纯逻辑（JVM 可测，见 FileTreeOpsTest）：
 * 定位展开祖先链、懒补载子级的 DFS 插入、全量目录路径收集。
 *
 * 树是 [FileRepository.tree] 产出的**扁平 DFS 列表**（按 depth 表达层级），
 * 补载插入必须维持「目录节点后紧跟其子树」的不变式，否则 [buildTreeRows]
 * 的祖先栈解析会把子树挂错父级。
 */

/**
 * [path] 在树内的祖先目录绝对路径链（自浅入深，**不含** [root] 自身）。
 *
 * 打开文件时用它把折叠中的上级目录全部展开（root 本身不是树节点、不存在折叠态）。
 * 路径不在 root 之下（或 root 为空）时返回空链 —— 树外文件不做定位。
 * 分隔符跟随 root 自身的风格（Android 恒 `/`；JVM 单测在 Windows 下 `File.path` 为 `\`），
 * 保证产出的链与 [java.io.File.path] 同格式、可直接与折叠集合做差集。
 */
fun ancestorDirPaths(root: String?, path: String): List<String> {
    if (root.isNullOrBlank()) return emptyList()
    val base = root.trimEnd('/', '\\')
    // 必须卡目录边界：否则 root=/a/b 会把 /a/bc/d 误判为树内路径
    if (path != base && !path.startsWith("$base/") && !path.startsWith("$base\\")) return emptyList()
    val rel = path.removePrefix(base).trimStart('/', '\\')
    if (rel.isEmpty()) return emptyList()
    val segments = rel.split('/', '\\').filter { it.isNotEmpty() }
    if (segments.size < 2) return emptyList() // root 直下文件：无祖先目录
    val sep = when {
        base.contains('/') -> '/'
        base.contains('\\') -> '\\'
        else -> File.separatorChar
    }
    val out = ArrayList<String>(segments.size - 1)
    var prefix = base
    for (i in 0 until segments.size - 1) {
        prefix = "$prefix$sep${segments[i]}"
        out.add(prefix)
    }
    return out
}

/**
 * [tree] 中 [idx] 处目录节点的子级是否已物化。
 *
 * 扁平 DFS 列表里，目录的首个子节点必然紧随其后且 depth 更深；
 * 紧随其后的节点 depth 不更深（或已是末尾）即子级未读盘。
 */
fun hasChildrenLoaded(tree: List<FileNode>, idx: Int): Boolean {
    if (idx < 0 || idx >= tree.size) return false
    val dir = tree[idx]
    if (!dir.isDirectory) return false
    return idx + 1 < tree.size && tree[idx + 1].depth > dir.depth
}

/**
 * 把 [children]（depth 已按 `目录.depth + 1` 就位）插入到 [dirPath] 节点之后，
 * 形成完整子树段；子级已物化或目录不在树内时原样返回（幂等，可重复调用）。
 */
fun withChildrenInserted(
    tree: List<FileNode>,
    dirPath: String,
    children: List<FileNode>,
): List<FileNode> {
    val idx = tree.indexOfFirst { it.file.path == dirPath }
    if (idx < 0 || hasChildrenLoaded(tree, idx)) return tree
    if (children.isEmpty()) return tree // 空目录不落盘占位，避免每次展开都判「未物化」
    val out = ArrayList<FileNode>(tree.size + children.size)
    out.addAll(tree.subList(0, idx + 1))
    out.addAll(children)
    out.addAll(tree.subList(idx + 1, tree.size))
    return out
}

/** [tree] 中全部目录的绝对路径（「全部折叠」的目标集合）。 */
fun directoryPathsOf(tree: List<FileNode>): Set<String> =
    tree.filter { it.isDirectory }.mapTo(LinkedHashSet()) { it.file.path }
