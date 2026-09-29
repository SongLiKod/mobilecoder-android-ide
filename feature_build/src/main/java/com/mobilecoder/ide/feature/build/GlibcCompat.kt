package com.mobilecoder.ide.feature.build

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** ELF 动态链接器（`PT_INTERP`）类型。 */
enum class InterpKind {
    /** 不是 ELF（shell shim、文本、不存在、损坏） */
    NOT_ELF,

    /** 是 ELF 但没有 `PT_INTERP`：静态链接的可执行文件，或共享库本身 */
    NONE,

    /** Android bionic：`/system/bin/linker64` */
    BIONIC,

    /** glibc：`/lib/ld-linux-aarch64.so.1`、`/lib64/ld-linux-x86-64.so.2` */
    GLIBC,

    /** musl：`/lib/ld-musl-aarch64.so.1`（本项目不提供运行时，只能报清楚原因） */
    MUSL,

    /** 其它解释器（未知目标平台） */
    OTHER,
}

/** [GlibcCompat.rewrite] 的结果。 */
enum class InterpRewrite {
    /** 解释器已经指向本机 loader，无需改写（幂等命中） */
    ALREADY,

    /** 已改写：新 loader 路径追加到文件末尾，`PT_INTERP` 指向它 */
    PATCHED,

    /** 跳过：不是 glibc ELF（bionic / 静态 / musl / 非 ELF） */
    SKIPPED,

    /** 改写失败（权限、磁盘满、文件被并发写入 …），调用方应展示原因 */
    FAILED,
}

/**
 * glibc 兼容层：识别 ELF 的动态链接器，并把「官方 Linux 发行包」的解释器改写为
 * 本机 `files/sdk/glibc/lib/ld-linux-aarch64.so.1`。
 *
 * ### 为什么需要
 * 内核 `execve` 只做一件事：读 ELF 头里的 `PT_INTERP`，把它当绝对路径打开。
 * nodejs.org / Adoptium / Gradle 官方包链的是 glibc 的 `/lib/ld-linux-aarch64.so.1`，
 * Android 上没有这个文件 → **内核在 exec 阶段就 ENOENT(2)**，
 * 症状是 `mobilecoder: 命令执行失败 (execvp): No such file or directory` + 退出码 127，
 * 与「文件丢了」「缺执行位」「架构不匹配」表现完全一样，只能靠读 `PT_INTERP` 区分。
 *
 * ### 怎么改（对齐 termux-glibc / glibc-runner 的思路，但不依赖它们）
 * 1. `EnvDownloader` 下载一份 aarch64 glibc 运行时到 `files/sdk/glibc`（含 loader）；
 * 2. 把 glibc ELF 的 `PT_INTERP` **原地改写**：新 loader 路径**追加到文件末尾**，
 *    再把 `PT_INTERP` 记录的 `p_offset` / `p_filesz` 指过去。
 *    内核 `load_elf_binary()` 读解释器只按 `p_offset + p_filesz` 从文件里取字节，
 *    **不要求它落在 `PT_LOAD` 内、也不使用 `p_vaddr`**，所以追加是安全的，
 *    且不用移动任何已有段（比 patchelf 重建段表简单得多）。
 * 3. 改写完成后，**子进程不需要任何钩子**：npm 拉起来的 esbuild/ripgrep、终端里直接敲的
 *    命令，都按已改写的解释器正常 exec。[rewrite] 幂等，重复跑只返回 [InterpRewrite.ALREADY]。
 *
 * 尚未改写过的新文件（`npm i -g` 解包瞬间执行的 postinstall 脚本）由 LD_PRELOAD 钩子
 * （`core_native/.../mc_exec_hook.c`）兜底：exec 前发现是 glibc ELF 就改走 loader。
 */
object GlibcCompat {

    private const val PT_INTERP = 3

    /** 解释器路径最大长度（内核限制 `PATH_MAX`，这里只做读取保护）。 */
    private const val MAX_INTERP = 4096

    /** 读取 ELF 的 `PT_INTERP` 字符串；非 ELF / 没有解释器返回 null。 */
    fun readInterp(file: File): String? = parse(file)?.interp

    /** 判断 [file] 的动态链接器类型（[file] 为 null 或不存在 → [InterpKind.NOT_ELF]）。 */
    fun kind(file: File?): InterpKind {
        if (file == null || !file.isFile) return InterpKind.NOT_ELF
        val interp = parse(file)?.interp ?: return when {
            !BuildEnvironment.isElfBinary(file) -> InterpKind.NOT_ELF
            else -> InterpKind.NONE
        }
        return kindOf(interp)
    }

    /** 解释器字符串 → 类型。 */
    fun kindOf(interp: String): InterpKind = when {
        interp.contains("ld-linux") -> InterpKind.GLIBC
        interp.contains("/linker") || interp.endsWith("linker64") -> InterpKind.BIONIC
        interp.contains("ld-musl") -> InterpKind.MUSL
        interp.contains("ld.so") -> InterpKind.GLIBC
        else -> InterpKind.OTHER
    }

    /**
     * 把 [file] 的解释器改写为 [loaderPath]（仅当它是 glibc ELF）。
     *
     * 算法：把 `loaderPath` + `\0` **追加到文件末尾**，再把 `PT_INTERP` 记录的
     * `p_offset` / `p_filesz` 指向新位置；其它字段一律读改写前的原值，不重排任何段。
     *
     * @return [InterpRewrite.PATCHED] 改写成功；[InterpRewrite.ALREADY] 已经是目标 loader
     */
    fun rewrite(file: File, loaderPath: String): InterpRewrite {
        val elf = parse(file) ?: return InterpRewrite.SKIPPED
        val current = elf.interp ?: return InterpRewrite.SKIPPED
        if (kindOf(current) != InterpKind.GLIBC) return InterpRewrite.SKIPPED
        if (current == loaderPath) return InterpRewrite.ALREADY
        return runCatching {
            RandomAccessFile(file, "rw").use { rw ->
                val appended = loaderPath.toByteArray(Charsets.US_ASCII) + 0.toByte()
                val at = rw.length()
                if (at <= 0L || at + appended.size > Int.MAX_VALUE) return@runCatching InterpRewrite.FAILED
                rw.seek(at)
                rw.write(appended)

                // 原地更新 PT_INTERP：先读回完整记录，只改 offset / filesz 两个字段
                val base = elf.phOff + elf.interpIndex.toLong() * elf.phEntSize
                if (base < 0 || base + elf.phEntSize > rw.length()) return@runCatching InterpRewrite.FAILED
                val record = ByteArray(elf.phEntSize)
                rw.seek(base)
                rw.readFully(record)
                val buf = ByteBuffer.wrap(record).order(elf.order)
                if (elf.is64) {
                    buf.putLong(8, at)
                    buf.putLong(32, appended.size.toLong())
                } else {
                    buf.putInt(4, at.toInt())
                    buf.putInt(16, appended.size)
                }
                rw.seek(base)
                rw.write(record)
            }
            // 改完立刻回读校验：失败必须让调用方知道，否则会退回到「exec 才发现」的老路
            if (parse(file)?.interp == loaderPath) InterpRewrite.PATCHED else InterpRewrite.FAILED
        }.getOrElse { InterpRewrite.FAILED }
    }

    /**
     * 递归改写 [root] 下所有 glibc ELF 的解释器（跳过符号链接目录，防止 npm 里的环）。
     *
     * 只读文件头几十字节定位 `PT_INTERP`，不读文件内容，`bin` 目录里几十个文件是毫秒级；
     * 深度与文件数设上限，避免误入巨型目录树。
     *
     * @return 成功改写的文件数（ALREADY 不计）
     */
    fun rewriteTree(root: File, loaderPath: String, maxDepth: Int = 8, maxFiles: Int = 20_000): Int {
        if (!root.exists()) return 0
        var patched = 0
        var visited = 0
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty()) {
            val (dir, depth) = queue.removeFirst()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (visited >= maxFiles) return patched
                if (child.isDirectory) {
                    if (depth < maxDepth) queue.add(child to depth + 1)
                    continue
                }
                if (!child.isFile) continue
                visited++
                // 只碰 ELF：4 字节魔数，绝大多数普通文件在这一步就被过滤掉
                if (!BuildEnvironment.isElfBinary(child)) continue
                if (rewrite(child, loaderPath) == InterpRewrite.PATCHED) patched++
            }
        }
        return patched
    }

    // ------------------------------------------------------------------
    // ELF 解析（32/64 位、大小端；Android 目标只会用到小端）
    // ------------------------------------------------------------------

    private data class Elf(
        val is64: Boolean,
        val order: ByteOrder,
        val phOff: Long,
        val phEntSize: Int,
        val phNum: Int,
        val interp: String?,
        val interpIndex: Int,
    )

    private fun parse(file: File): Elf? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val size = raf.length()
            if (size < 52L) return@use null
            val ident = ByteArray(16)
            raf.readFully(ident)
            if (ident[0] != 0x7F.toByte() || ident[1] != 'E'.code.toByte() ||
                ident[2] != 'L'.code.toByte() || ident[3] != 'F'.code.toByte()
            ) {
                return@use null
            }
            val is64 = ident[4].toInt() == 2
            val order = if (ident[5].toInt() == 2) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
            if (is64 && size < 64L) return@use null

            val phOff = field(raf, if (is64) 32L else 28L, if (is64) 8 else 4, order) ?: return@use null
            val phEntSize = field(raf, if (is64) 54L else 42L, 2, order)?.toInt() ?: return@use null
            val phNum = field(raf, if (is64) 56L else 44L, 2, order)?.toInt() ?: return@use null
            val phEntNeed = if (is64) 56 else 32
            if (phEntSize < phEntNeed || phNum <= 0 || phNum > 65535) return@use null
            if (phOff < 0 || phOff + phNum.toLong() * phEntSize > size) return@use null

            var interp: String? = null
            var interpIndex = -1
            for (i in 0 until phNum) {
                val base = phOff + i.toLong() * phEntSize
                val buf = readAt(raf, base, phEntSize, order) ?: break
                if (buf.getInt(0) != PT_INTERP) continue
                val offset: Long
                val filesz: Long
                if (is64) {
                    offset = buf.getLong(8)
                    filesz = buf.getLong(32)
                } else {
                    offset = buf.getInt(4).toLong() and 0xFFFFFFFFL
                    filesz = buf.getInt(16).toLong() and 0xFFFFFFFFL
                }
                if (filesz <= 1L || filesz > MAX_INTERP || offset < 0 || offset + filesz > size) break
                val raw = readAt(raf, offset, filesz.toInt(), order) ?: break
                val bytes = raw.array()
                val end = bytes.indexOf(0.toByte()).let { if (it < 0) bytes.size else it }
                interp = String(bytes, 0, end, Charsets.US_ASCII)
                interpIndex = i
                break
            }
            Elf(is64, order, phOff, phEntSize, phNum, interp, interpIndex)
        }
    }.getOrNull()

    /** 从 [offset] 起读 [len] 字节并按 [order] 包装；越界返回 null。 */
    private fun readAt(raf: RandomAccessFile, offset: Long, len: Int, order: ByteOrder): ByteBuffer? {
        if (offset < 0 || len <= 0 || offset + len > raf.length()) return null
        raf.seek(offset)
        val bytes = ByteArray(len)
        raf.readFully(bytes)
        return ByteBuffer.wrap(bytes).order(order)
    }

    /** 读取 ELF 头中的定长整数字段（大端文件同样正确）。 */
    private fun field(raf: RandomAccessFile, offset: Long, len: Int, order: ByteOrder): Long? {
        val buf = readAt(raf, offset, len, order) ?: return null
        return when (len) {
            2 -> buf.getShort(0).toLong() and 0xFFFFL
            4 -> buf.getInt(0).toLong() and 0xFFFFFFFFL
            8 -> buf.getLong(0)
            else -> null
        }
    }
}