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
     * 项目树口径的目录直系子项：在 [listChildren] 之上再过滤构建产物 / VCS 内部目录。
     * `tree()` 与文件树「展开时按需补载」共用同一口径，保证两处列出的内容一致。
     */
    fun listTreeChildren(dir: File, showHidden: Boolean = false): List<FileNode> {
        val children = listChildren(dir, showHidden)
        return if (showHidden) children else children.filterNot { isIgnored(it.name) }
    }

    /** 递归构建项目树（到 [maxDepth] 层为止，自动跳过构建产物）。 */
    fun tree(root: File, maxDepth: Int = 6, showHidden: Boolean = false): List<FileNode> {
        val out = ArrayList<FileNode>()
        fun walk(dir: File, depth: Int) {
            if (depth > maxDepth) return
            for (child in listTreeChildren(dir, showHidden)) {
                out.add(child.copy(depth = depth))
                if (child.isDirectory) walk(child.file, depth + 1)
            }
        }
        walk(root, 1)
        return out
    }

    /** 是否应被项目树隐藏（构建产物 / VCS 内部）。 */
    fun isIgnored(name: String): Boolean = name in IGNORED_DIRS || name.startsWith(".")

    private val IGNORED_DIRS = setOf(
        "build", ".gradle", ".idea", ".git", "node_modules", ".cxx", ".kotlin", "captures",
    )

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
                if (isIgnored(child.name)) continue
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
