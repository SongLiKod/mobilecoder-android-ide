package com.mobilecoder.ide.feature.editor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * 导出文件 / 目录到用户选定的系统目录（SAF tree URI，文件树长按菜单「导出到…」）。
 *
 * 目录逐级对应创建，导出后的结构与源一致；目标处同名条目先删后建，
 * 使重复导出是覆盖而非被提供方改名成「x (1)」。
 * 递归深度有上限，且调用前应以 [isTargetInside] 拒绝「导出到自身子目录」
 * ——否则每层都会把源复制进自己，无限递归。
 */
internal object FileExporter {

    /** 递归上限：远高于真实工程深度，只用于兜住异常 / 自嵌套时的失控复制。 */
    private const val MAX_DEPTH = 64

    /** 申请 tree URI 的持久读写权限（部分提供方不支持持久化，失败不拦：本次会话内仍可写）。 */
    fun takePersistablePermission(context: Context, treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    /**
     * 目标目录是否位于 [sourceDir] 内部（含相等）。
     *
     * tree URI 拿不到直接的文件系统路径，故按提供方 documentId 猜路径再比对；
     * 猜不出（云盘等非文件系统提供方）时返回 false 放行——这道防线失守时
     * 还有 [export] 的深度上限兜底。
     */
    fun isTargetInside(treeUri: Uri, sourceDir: File): Boolean {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrNull() ?: return false
        if (!documentId.contains(':')) return false
        val volume = documentId.substringBefore(':')
        val relative = documentId.substringAfter(':')
        val base = if (volume.isEmpty() || volume == "primary") {
            "/storage/emulated/0"
        } else {
            "/storage/$volume"
        }
        val target = runCatching { File(base, relative).canonicalPath }.getOrNull() ?: return false
        return runCatching { isPathInside(target, sourceDir.canonicalPath) }.getOrDefault(false)
    }

    /** 导出 [src] 到 [treeUri] 指向的目录；全部写入成功才返回 true。 */
    fun export(context: Context, src: File, treeUri: Uri): Boolean {
        if (!src.exists()) return false
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
        if (!root.isDirectory) return false
        return copy(context, src, root, depth = 0)
    }

    private fun copy(context: Context, src: File, parent: DocumentFile, depth: Int): Boolean {
        if (depth > MAX_DEPTH) return false
        if (src.isDirectory) {
            val dir = ensureDirectory(parent, src.name) ?: return false
            val children = src.listFiles() ?: return false
            return children.all { child -> copy(context, child, dir, depth + 1) }
        }
        return src.isFile && exportFile(context, src, parent)
    }

    private fun exportFile(context: Context, src: File, parent: DocumentFile): Boolean {
        // 同名条目先删：不删则提供方改名成「x (1)」，重复导出不会覆盖
        findChild(parent, src.name)?.takeIf { !it.isDirectory }?.delete()
        val target = parent.createFile(mimeTypeOf(src.name), src.name) ?: return false
        return runCatching {
            val out = context.contentResolver.openOutputStream(target.uri) ?: return@runCatching false
            out.use { stream -> src.inputStream().use { it.copyTo(stream) } }
            true
        }.getOrDefault(false)
    }

    /** 取 [name] 子目录；已存在但被同名文件占位则删掉重建。 */
    private fun ensureDirectory(parent: DocumentFile, name: String): DocumentFile? {
        val existing = findChild(parent, name)
        if (existing != null) {
            if (existing.isDirectory) return existing
            if (!existing.delete()) return null
        }
        return parent.createDirectory(name)
    }

    private fun findChild(parent: DocumentFile, name: String): DocumentFile? =
        parent.listFiles().firstOrNull { it.name == name }

    /** 扩展名 → MIME；未知一律二进制（提供方据此决定落盘文件名，带原扩展名最稳）。 */
    private fun mimeTypeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}

/**
 * [targetPath] 是否等于或位于 [sourceDirPath] 内部（按分隔符级别比对，不看前缀相似）。
 *
 * internal 仅为单测可见（FileExporterTest 锁「/a/bcd 不算 /a/b 的子路径」）。
 */
internal fun isPathInside(targetPath: String, sourceDirPath: String): Boolean {
    val target = File(targetPath).canonicalPath
    val source = File(sourceDirPath).canonicalPath
    // 根路径本身以分隔符结尾（"/"、"C:\"），再补一个分隔符会匹配不上
    val prefix = if (source.endsWith(File.separator)) source else source + File.separator
    return target == source || target.startsWith(prefix)
}
