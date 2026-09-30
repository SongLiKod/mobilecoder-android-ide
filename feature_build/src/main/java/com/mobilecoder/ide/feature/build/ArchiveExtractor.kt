package com.mobilecoder.ide.feature.build

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import org.tukaani.xz.XZInputStream

/**
 * 通用归档解压（zip / tar.gz / .deb）：「本地导入」与「在线下载」共用。
 *
 * - **路径穿越防护**：每个条目 canonical 化后必须位于目标目录内（zip slip / tar slip）；
 * - **tar.gz 支持**：GNU 长文件名（'L'/'K'）、pax 扩展头（'x'）、目录/普通文件/软链接/硬链接、权限位；
 * - **格式嗅探**：按流首部识别（gzip = 0x1f8b，zip = 'PK'），与文件名无关；
 * - **.deb 支持**（[extractDeb]）：ar 归档 + `data.tar[.xz|.gz]` 成员，安装 proot 三件套
 *   （Termux 官方仓库 .deb）时用。
 */
internal object ArchiveExtractor {

    /** 解压结果：条目数 + 已解出的字节数。 */
    data class Result(val entries: Int, val readBytes: Long)

    /**
     * 解压 [input] 到 [dest]（目录不存在时自动创建）。
     *
     * @param totalBytes 进度换算用的总字节数（压缩包大小，未知传 0）
     * @param onProgress 解压进度 0..1（按已解出的字节数，封顶 0.99；完成值由调用方置 1）
     */
    fun extract(
        input: InputStream,
        dest: File,
        totalBytes: Long = 0L,
        onProgress: (Float) -> Unit = {},
    ): Result {
        if (!dest.isDirectory && !dest.mkdirs()) {
            throw IOException("无法创建目录：${dest.absolutePath}")
        }
        val buffered = if (input.markSupported()) input else BufferedInputStream(input)
        buffered.mark(8)
        val head = ByteArray(4)
        var got = 0
        while (got < head.size) {
            val n = buffered.read(head, got, head.size - got)
            if (n < 0) break
            got += n
        }
        buffered.reset()
        val isGzip = got >= 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()
        val isZip = got >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        val total = totalBytes.coerceAtLeast(1L)
        return when {
            isZip -> extractZip(buffered, dest, total, onProgress)
            isGzip -> extractTar(GZIPInputStream(buffered), dest, total, onProgress)
            else -> throw IOException("无法识别的压缩格式（仅支持 zip / tar.gz）")
        }
    }

    /**
     * 解压 Debian 软件包（`.deb` = `ar` 归档）的 `data.tar[.xz|.gz]` 成员到 [dest]。
     *
     * 用于 Linux 环境在线安装：proot / libtalloc / libandroid-shmem 三个 Termux `.deb`
     * 下载后用本方法解包，再由 `RootfsManager.placeProotFiles` 按归一化路径落位到 rootfs。
     *
     * @param totalBytes 进度换算用（.deb 大小，未知传 0）
     * @return `data.tar*` 的解压结果
     */
    fun extractDeb(
        input: InputStream,
        dest: File,
        totalBytes: Long = 0L,
        onProgress: (Float) -> Unit = {},
    ): Result {
        if (!dest.isDirectory && !dest.mkdirs()) {
            throw IOException("无法创建目录：${dest.absolutePath}")
        }
        val buffered = if (input.markSupported()) input else BufferedInputStream(input)
        // 读掉 8 字节 ar 魔数（'!<arch>\n'），随后的成员头紧跟其后 —— 不可 reset 回 0
        val magic = ByteArray(8)
        var got = 0
        while (got < magic.size) {
            val n = buffered.read(magic, got, magic.size - got)
            if (n < 0) break
            got += n
        }
        if (got < 8 || !magic.contentEquals("!<arch>\n".toByteArray(Charsets.US_ASCII))) {
            throw IOException("不是有效的 .deb（缺少 ar 魔数 '!<arch>'）")
        }
        val total = totalBytes.coerceAtLeast(1L)
        // 逐个读 60 字节 ar 成员头，定位 data.tar*；其它成员（debian-binary / control.tar.*）跳过
        val header = ByteArray(60)
        var pos = 8L // 已过 ar 魔数
        while (true) {
            var read = 0
            while (read < header.size) {
                val n = buffered.read(header, read, header.size - read)
                if (n < 0) break
                read += n
            }
            if (read < header.size) throw IOException(".deb 里没有 data.tar* 成员（ar 头不完整：偏移 $pos 读到 $read 字节）")
            pos += 60
            val name = arField(header, 0, 16).trimEnd('/', ' ')
            val size = arField(header, 48, 10).toLongOrNull()
                ?: throw IOException("ar 成员头损坏（偏移 ${pos - 60}）：${String(header, Charsets.US_ASCII)}")
            if (name.startsWith("data.tar")) {
                val data = LimitedInputStream(buffered, size)
                val stream = when {
                    name.endsWith(".gz") -> GZIPInputStream(data)
                    name.endsWith(".xz") -> XZInputStream(data)
                    name.endsWith(".zst") || name.endsWith(".zstd") ->
                        throw IOException("data.tar.zst 暂不支持（Debian 13 的 .deb 为 xz）：$name")
                    else -> data
                }
                return extractTar(stream, dest, total, onProgress)
            }
            var skip = size
            while (skip > 0) {
                val n = buffered.skip(skip)
                if (n <= 0) {
                    if (buffered.read() < 0) {
                        throw IOException(
                            ".deb 里没有 data.tar* 成员（跳过成员 '$name' 数据时提前 EOF：" +
                                "偏移 $pos 应再跳 $skip 字节）",
                        )
                    }
                    skip--
                } else {
                    skip -= n
                }
            }
            pos += size
            if (size % 2 == 1L) {
                if (buffered.read() < 0) {
                    throw IOException(".deb 里没有 data.tar* 成员（成员 '$name' 奇数字节缺对齐补位：偏移 $pos）")
                }
                pos++
            }
        }
    }

    /** 取 ar 头中 [off] 起 [len] 字节的 ASCII 字段（NUL/空格填充后裁剪），转十进制。 */
    private fun arField(header: ByteArray, off: Int, len: Int): String {
        var end = off
        val limit = off + len
        while (end < limit && header[end].toInt() != 0 && header[end] != ' '.code.toByte()) end++
        return String(header, off, end - off, Charsets.US_ASCII)
    }

    /** 只透传前 [remaining] 字节的 ar 成员视图；不关闭底层流（成员后可能还有数据）。 */
    private class LimitedInputStream(private val src: InputStream, remaining: Long) : InputStream() {
        private var remaining = remaining

        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = src.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = src.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }
    }

    // ------------------------------------------------------------------
    // zip
    // ------------------------------------------------------------------

    private fun extractZip(
        input: InputStream,
        dest: File,
        total: Long,
        onProgress: (Float) -> Unit,
    ): Result {
        val root = dest.canonicalFile
        var readBytes = 0L
        var entries = 0
        ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val target = safeResolve(root, entry.name)
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    val buf = ByteArray(64 * 1024)
                    target.outputStream().use { out ->
                        while (true) {
                            val n = zis.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            readBytes += n
                        }
                    }
                    entries++
                    if (entries % 8 == 0) {
                        onProgress((readBytes.toFloat() / total).coerceIn(0f, 0.99f))
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        if (entries == 0) throw IOException("压缩包为空或不是有效的 zip 文件")
        return Result(entries, readBytes)
    }

    // ------------------------------------------------------------------
    // tar / tar.gz
    // ------------------------------------------------------------------

    private fun extractTar(
        gzip: InputStream,
        dest: File,
        total: Long,
        onProgress: (Float) -> Unit,
    ): Result {
        val root = dest.canonicalFile
        var readBytes = 0L
        var entries = 0
        var longName: String? = null
        var longLink: String? = null
        var paxPath: String? = null
        var paxLink: String? = null
        val header = ByteArray(512)
        while (readFully(gzip, header)) {
            if (isZeroBlock(header)) break
            val size = parseOctal(header, 124, 12)
            val typeCode = header[156].toInt() and 0xff
            val type = if (typeCode == 0) '0' else typeCode.toChar()
            val name = cstr(header, 0, 100)
            val prefix = cstr(header, 345, 155)
            val linkName = cstr(header, 157, 100)

            // ---- 元数据条目：作用于下一个实体条目，随后继续 ----
            when (type) {
                'L' -> {
                    longName = String(readExactly(gzip, size), Charsets.UTF_8).trimEnd { it.code == 0 || it == ' ' }
                    skipPadding(gzip, size)
                    continue
                }
                'K' -> {
                    longLink = String(readExactly(gzip, size), Charsets.UTF_8).trimEnd { it.code == 0 || it == ' ' }
                    skipPadding(gzip, size)
                    continue
                }
                'x', 'X' -> {
                    val pax = parsePax(readExactly(gzip, size))
                    skipPadding(gzip, size)
                    paxPath = pax["path"] ?: paxPath
                    paxLink = pax["linkpath"] ?: paxLink
                    continue
                }
                'g' -> {
                    readExactly(gzip, size)
                    skipPadding(gzip, size)
                    continue
                }
            }
            val rawNameOrig = longName ?: paxPath ?: if (prefix.isNotEmpty()) "$prefix/$name" else name
            val rawLink = longLink ?: paxLink ?: linkName
            longName = null
            longLink = null
            paxPath = null
            paxLink = null
            // Debian data.tar 的条目带 "./" 前缀（首条目常是根目录 "./"）：归一成相对路径，
            // 根条目变空串 → 走下面的空名跳过分支（不进 safeResolve，否则会误报穿越）
            val rawName = if (rawNameOrig == ".") "" else rawNameOrig.removePrefix("./")
            if (rawName.isEmpty()) {
                if (size > 0) skipFully(gzip, size)
                skipPadding(gzip, size)
                continue
            }

            val target = safeResolve(root, rawName)
            val mode = parseOctal(header, 100, 8)
            when (type) {
                '5' -> {
                    target.mkdirs()
                    applyMode(target, mode)
                }
                '0' -> {
                    target.parentFile?.mkdirs()
                    if (target.exists()) target.delete()
                    val buf = ByteArray(64 * 1024)
                    target.outputStream().use { out ->
                        var remain = size
                        while (remain > 0) {
                            val n = gzip.read(buf, 0, minOf(buf.size.toLong(), remain).toInt())
                            if (n < 0) throw IOException("tar 数据被截断：$rawName")
                            out.write(buf, 0, n)
                            remain -= n
                            readBytes += n
                        }
                    }
                    skipPadding(gzip, size)
                    applyMode(target, mode)
                    entries++
                }
                '1' -> {
                    target.parentFile?.mkdirs()
                    val src = runCatching { safeResolve(root, rawLink) }.getOrNull()
                    if (src != null && src.isFile) {
                        if (target.exists()) target.delete()
                        src.copyTo(target, overwrite = true)
                        readBytes += target.length()
                        applyMode(target, mode)
                    }
                    if (size > 0) skipFully(gzip, size)
                    skipPadding(gzip, size)
                    entries++
                }
                '2' -> {
                    createSymlink(rawLink, target)
                    if (size > 0) skipFully(gzip, size)
                    skipPadding(gzip, size)
                    entries++
                }
                else -> {
                    if (size > 0) skipFully(gzip, size)
                    skipPadding(gzip, size)
                }
            }
            if (entries % 8 == 0) {
                onProgress((readBytes.toFloat() / total).coerceIn(0f, 0.99f))
            }
        }
        if (entries == 0) throw IOException("tar 归档为空或格式不正确")
        return Result(entries, readBytes)
    }

    private fun parsePax(data: ByteArray): Map<String, String> {
        if (data.isEmpty()) return emptyMap()
        val text = String(data, Charsets.UTF_8)
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < text.length) {
            val sp = text.indexOf(' ', i)
            if (sp < 0) break
            val len = text.substring(i, sp).toIntOrNull() ?: break
            if (len <= 0 || i + len > text.length) break
            val record = text.substring(sp + 1, i + len).trimEnd('\n')
            val eq = record.indexOf('=')
            if (eq > 0) out[record.substring(0, eq)] = record.substring(eq + 1)
            i += len
        }
        return out
    }

    private fun createSymlink(link: String, target: File) {
        target.parentFile?.mkdirs()
        val created = runCatching {
            if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                Files.delete(target.toPath())
            }
            Files.createSymbolicLink(target.toPath(), File(link).toPath())
        }.isSuccess
        if (created) return
        runCatching {
            val src = File(target.parentFile, link)
            if (src.isFile && !target.exists()) src.copyTo(target, overwrite = true)
        }
    }

    private fun safeResolve(root: File, name: String): File {
        val target = File(root, name).canonicalFile
        if (!target.path.startsWith(root.path + File.separator)) {
            throw SecurityException("压缩包内含非法路径（路径穿越），已中止：$name")
        }
        return target
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun readExactly(input: InputStream, size: Long): ByteArray {
        if (size <= 0) return ByteArray(0)
        if (size > Int.MAX_VALUE - 8) throw IOException("归档条目过大：$size 字节")
        val out = ByteArray(size.toInt())
        var off = 0
        while (off < out.size) {
            val n = input.read(out, off, out.size - off)
            if (n < 0) throw IOException("tar 数据被截断")
            off += n
        }
        return out
    }

    private fun skipFully(input: InputStream, size: Long) {
        var remain = size
        while (remain > 0) {
            val skipped = input.skip(remain)
            if (skipped > 0) {
                remain -= skipped
                continue
            }
            if (input.read() < 0) throw IOException("tar 数据被截断")
            remain--
        }
    }

    /** 条目数据按 512 字节对齐：跳过 [size] 之后的补齐填充。 */
    private fun skipPadding(input: InputStream, size: Long) {
        val rem = size % 512
        if (rem != 0L) skipFully(input, 512 - rem)
    }

    private fun isZeroBlock(buf: ByteArray): Boolean {
        for (b in buf) if (b.toInt() != 0) return false
        return true
    }

    private fun cstr(buf: ByteArray, off: Int, len: Int): String {
        var end = off
        val limit = off + len
        while (end < limit && buf[end].toInt() != 0) end++
        return String(buf, off, end - off, Charsets.UTF_8).trim()
    }

    private fun parseOctal(buf: ByteArray, off: Int, len: Int): Long {
        val first = buf[off].toInt()
        if (first and 0x80 != 0) {
            var v = if (first == 0x80) 0L else (first and 0x7f).toLong()
            for (i in 1 until len) v = (v shl 8) or (buf[off + i].toLong() and 0xff)
            return v
        }
        var v = 0L
        for (i in 0 until len) {
            val c = (buf[off + i].toInt() and 0xff).toChar()
            if (c in '0'..'7') v = (v shl 3) + (c.code - '0'.code) else break
        }
        return v
    }

    private fun applyMode(file: File, mode: Long) {
        if (mode == 0L) return
        runCatching {
            file.setReadable(true, false)
            if (mode and 0x111L != 0L) file.setExecutable(true, false)
        }
    }
}
