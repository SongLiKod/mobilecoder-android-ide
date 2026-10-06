package com.mobilecoder.ide.core.storage

import java.io.File

/** 项目树节点（懒加载：只在展开目录时读取子级）。 */
data class FileNode(
    val name: String,
    val file: File,
    val isDirectory: Boolean,
    val size: Long,
    val depth: Int,
)

/** 文本文件判定与读写（编辑器 / 检索 / Diff 共用）。 */
object FileRepository {

    private const val MAX_TEXT_BYTES = 4L * 1024 * 1024
    private val TEXT_EXT = setOf(
        "kt", "kts", "java", "xml", "gradle", "properties", "json", "md", "txt", "pro",
        "js", "ts", "jsx", "tsx", "dart", "html", "css", "scss", "yml", "yaml", "toml",
        "sh", "c", "h", "cpp", "hpp", "py", "gitignore", "editorconfig", "cfg", "ini",
        "svg", "cfg", "lock", "manifest",
    )
    private val BINARY_EXT = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "ico", "so", "a", "o", "jar", "apk", "aab",
        "zip", "gz", "tar", "7z", "rar", "woff", "woff2", "ttf", "otf", "eot", "mp3", "mp4",
        "pdf", "keystore", "jks", "bin", "dex", "class", "apk_", "properties_",
    )

    /** 目录直系子项（按：目录优先、名称不区分大小写排序）。 */
    fun listChildren(dir: File, showHidden: Boolean = false): List<FileNode> {
        val children = dir.listFiles() ?: return emptyList()
        return children
            .asSequence()
            .filter { showHidden || !it.name.startsWith(".") }
            .map { FileNode(it.name, it, it.isDirectory, if (it.isFile) it.length() else 0L, 0) }
            .sortedWith(compareByDescending<FileNode> { it.isDirectory }.thenBy { it.name.lowercase() })
            .toList()
    }

    /**
     * 项目树口径的目录直系子项：在 [listChildren] 之上再过滤构建产物 / 依赖目录。
     * `tree()` 与文件树「展开时按需补载」共用同一口径，保证两处列出的内容一致。
     *
     * [showHidden] = true 时保留以 `.` 开头的文件与目录（.gitignore / .github / .env …），
     * 但「不显示」名单内的条目（[isArtifact]，默认 [DEFAULT_HIDDEN_NAMES]、可自定义）
     * **任何口径都过滤**——它们是噪音（build/node_modules 体量极大，eager 建树扫进去会拖垮刷新）。
     *
     * [childDepth] = 子项相对项目根的深度（root 直下为 1），供 [isArtifact] 区分
     * 「浅层 build 产物目录」与「深层 build 源码包名」。
     */
    fun listTreeChildren(dir: File, showHidden: Boolean = false, childDepth: Int = 1): List<FileNode> =
        listChildren(dir, showHidden).filterNot { isArtifact(it.name, childDepth) }

    /** 递归构建项目树（到 [maxDepth] 层为止，自动跳过构建产物）。 */
    fun tree(root: File, maxDepth: Int = 6, showHidden: Boolean = false): List<FileNode> {
        val out = ArrayList<FileNode>()
        fun walk(dir: File, depth: Int) {
            if (depth > maxDepth) return
            for (child in listTreeChildren(dir, showHidden, childDepth = depth)) {
                out.add(child.copy(depth = depth))
                if (child.isDirectory) walk(child.file, depth + 1)
            }
        }
        walk(root, 1)
        return out
    }

    /**
     * 快速打开（编辑器 ⋮ 菜单「打开文件」）用：项目内全部可编辑文本文件。
     *
     * 与 [tree] 同口径跳过构建产物 / 隐藏目录，深度上限 24 层；
     * 按路径字典序返回，最多 [limit] 个。
     *
     * **上限必须远大于真实项目规模**：DFS 按「目录优先 + 字典序」遍历，
     * 截断会静默吃掉排序靠后的整个模块（本仓库 2000 条会在
     * `core_native/.../third_party`（mbedtls，2400+ 文件）内截断，
     * 导致 `feature_*` 全部搜不到 —— 真机回归锁见
     * [listOpenableFiles 默认上限不截断后序模块]）。
     */
    fun listOpenableFiles(root: File, limit: Int = 50_000): List<File> {
        val out = ArrayList<File>()
        fun walk(dir: File, depth: Int) {
            if (out.size >= limit || depth > 24) return
            for (child in listTreeChildren(dir, showHidden = false, childDepth = depth)) {
                if (out.size >= limit) return
                if (child.isDirectory) {
                    walk(child.file, depth + 1)
                } else if (isTextFile(child.file)) {
                    out.add(child.file)
                }
            }
        }
        walk(root, 1)
        return out.sortedBy { it.path.lowercase() }
    }

    /**
     * 是否应被树（默认口径）/ 检索 / 快速打开 / AI 工具隐藏：
     * 以 `.` 开头的隐藏项 + 「不显示」名单（[isArtifact]）。
     *
     * 文件树的「显示隐藏文件」开关打开时走 [isArtifact] 而非本口径，
     * 以便 .gitignore / .github / .env 等可见（见 [listTreeChildren]）。
     */
    fun isIgnored(name: String, childDepth: Int = 1): Boolean =
        name.startsWith(".") || isArtifact(name, childDepth)

    /**
     * 「不显示」的文件 / 目录名：与「隐藏项」无关，**任何口径（含 showHidden=true）都过滤**。
     *
     * 名单 = [customHiddenNames]（用户自定义，由 EditorController 从设置注入；
     * **null = 未注入用默认，空集 = 用户清空即全部显示**），未注入时取 [DEFAULT_HIDDEN_NAMES]。
     *
     * **`build` 只在浅层（root/ 与模块根/，即深度 ≤ 2）算构建产物**：
     * 深层同名目录是合法源码包名（如 `feature/build/`），按名字一刀切会让整个
     * feature_build 模块的源码在树 / 检索 / 快速打开里全部消失（真机回归锁见
     * FileRepositoryTreeTest「同名 build 源码包不被误杀」）。
     */
    fun isArtifact(name: String, childDepth: Int = 1): Boolean {
        val names = customHiddenNames ?: DEFAULT_HIDDEN_NAMES
        if (name !in names) return false
        return name != "build" || childDepth <= 2
    }

    /** 内置默认的「不显示」名称（构建产物 / 依赖目录），用户可在设置中自定义。 */
    val DEFAULT_HIDDEN_NAMES = setOf("build", "node_modules", "captures")

    /**
     * 用户自定义的「不显示」名称（文件 / 目录名，按名匹配任意层级）。
     * 由 [com.mobilecoder.ide.feature.editor.EditorController] 从偏好注入（进程级）；
     * 其余调用方（检索 / 快速打开 / AI 工具）经 [isIgnored]、[isArtifact] 自动同源生效。
     */
    @Volatile
    var customHiddenNames: Set<String>? = null

    fun isTextFile(file: File): Boolean {
        val ext = file.extension.lowercase()
        if (ext in BINARY_EXT) return false
        if (ext in TEXT_EXT) return true
        if (!file.isFile || file.length() > MAX_TEXT_BYTES) return false
        return sniffText(file)
    }

    /**
     * 读取文本（PRD：任意格式、任意大小都可编辑）。
     *
     * 不做大小与扩展名白名单拦截：文本按 UTF-8 读入（非法字节用替换符）；
     * 仅在真正读不下（OOM / IO 失败）时返回 null，由编辑器提示。
     */
    fun readText(file: File): String? {
        if (!file.isFile) return null
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
    }

    fun writeText(file: File, content: String): Boolean = runCatching {
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }
        file.writeText(content, Charsets.UTF_8)
        true
    }.getOrDefault(false)

    fun createFile(dir: File, name: String): File? = runCatching {
        val target = File(dir, name)
        if (target.exists() || name.isBlank() || name.contains('/')) return null
        target.parentFile?.let { if (!it.exists()) it.mkdirs() }
        if (target.createNewFile()) target else null
    }.getOrNull()

    fun createDirectory(dir: File, name: String): File? = runCatching {
        val target = File(dir, name)
        if (target.exists() || name.isBlank() || name.contains('/')) return null
        if (target.mkdirs()) target else null
    }.getOrNull()

    fun delete(file: File): Boolean = runCatching {
        if (file.isDirectory) file.deleteRecursively() else file.delete()
    }.getOrDefault(false)

    fun rename(file: File, newName: String): File? {
        if (newName.isBlank() || newName.contains('/')) return null
        val target = File(file.parentFile, newName)
        if (target.exists()) return null
        return runCatching {
            if (file.renameTo(target)) target else null
        }.getOrNull()
    }

    fun move(file: File, targetDir: File): File? {
        if (!targetDir.isDirectory || file.parentFile?.canonicalPath == targetDir.canonicalPath) return null
        val target = File(targetDir, file.name)
        if (target.exists()) return null
        return runCatching {
            if (file.renameTo(target)) target else null
        }.getOrNull()
    }

    /** 全文检索（文件名 + 内容），返回「文件路径 : 行号 : 行内容」。 */
    fun search(root: File, query: String, limit: Int = 200): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        val needle = query.lowercase()
        val hits = ArrayList<SearchHit>()
        fun walk(dir: File, depth: Int) {
            if (hits.size >= limit || depth > 10) return
            for (child in listChildren(dir, showHidden = false)) {
                if (hits.size >= limit) return
                if (isIgnored(child.name, childDepth = depth)) continue
                if (child.isDirectory) {
                    walk(child.file, depth + 1)
                    continue
                }
                if (child.name.lowercase().contains(needle)) {
                    hits.add(SearchHit(child.file, 0, child.name))
                }
                if (!isTextFile(child.file)) continue
                val lines = runCatching { child.file.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
                for ((index, line) in lines.withIndex()) {
                    if (line.lowercase().contains(needle)) {
                        hits.add(SearchHit(child.file, index + 1, line.trim().take(200)))
                        if (hits.size >= limit) break
                    }
                }
            }
        }
        walk(root, 1)
        return hits
    }

    data class SearchHit(val file: File, val line: Int, val preview: String)

    private fun sniffText(file: File): Boolean = runCatching {
        val bytes = file.inputStream().use { input ->
            val buffer = ByteArray(2048)
            val read = input.read(buffer)
            if (read <= 0) return true
            buffer.copyOf(read)
        }
        !bytes.any { it == 0.toByte() }
    }.getOrDefault(false)
}
