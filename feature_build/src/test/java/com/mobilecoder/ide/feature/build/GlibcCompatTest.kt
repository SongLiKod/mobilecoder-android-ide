package com.mobilecoder.ide.feature.build

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ELF 解析与 `PT_INTERP` 原地改写的单元测试。
 *
 * 手工构造最小 ELF64 / ELF32（ELF 头 + 一个 `PT_INTERP` 段 + 解释器字符串），
 * 覆盖三件事：
 *  1. **分类**：glibc / bionic / musl / 静态 / 非 ELF 必须分得开——退出码 127 的根因
 *     只能靠读 `PT_INTERP` 区分，症状（ENOENT）完全一样；
 *  2. **改写**：追加新 loader 路径到文件末尾 + 只改 `p_offset` / `p_filesz`，
 *     不移动任何已有段，且第二次调用幂等；
 *  3. **树扫描**：只碰 ELF，且只改 glibc 的那些。
 */
class GlibcCompatTest {

    private val loader = "/data/user/0/com.mobilecoder.ide/files/sdk/glibc/lib/ld-linux-aarch64.so.1"

    // ------------------------------------------------------------------
    // 分类
    // ------------------------------------------------------------------

    @Test
    fun `kind 区分 glibc bionic musl 静态与非 ELF`() {
        val tmp = Files.createTempDirectory("interp-kind").toFile()
        try {
            assertEquals(InterpKind.GLIBC, GlibcCompat.kind(elf64(tmp, "glibc", GLIBC_INTERP)))
            assertEquals(InterpKind.GLIBC, GlibcCompat.kind(elf32(tmp, "glibc32", GLIBC32_INTERP)))
            assertEquals(InterpKind.BIONIC, GlibcCompat.kind(elf64(tmp, "bionic", "/system/bin/linker64")))
            assertEquals(InterpKind.MUSL, GlibcCompat.kind(elf64(tmp, "musl", "/lib/ld-musl-aarch64.so.1")))
            assertEquals(InterpKind.NONE, GlibcCompat.kind(elf64(tmp, "static", null)))
            assertEquals(InterpKind.NOT_ELF, GlibcCompat.kind(File(tmp, "not-elf").apply { writeText("#!/bin/sh") }))
            assertEquals(InterpKind.NOT_ELF, GlibcCompat.kind(File(tmp, "missing")))
            assertEquals(InterpKind.NOT_ELF, GlibcCompat.kind(null))
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `kindOf 覆盖常见解释器命名`() {
        assertEquals(InterpKind.GLIBC, GlibcCompat.kindOf("/lib64/ld-linux-x86-64.so.2"))
        assertEquals(InterpKind.GLIBC, GlibcCompat.kindOf("/lib/ld-linux-aarch64.so.1"))
        assertEquals(InterpKind.BIONIC, GlibcCompat.kindOf("/system/bin/linker64"))
        assertEquals(InterpKind.BIONIC, GlibcCompat.kindOf("/system/bin/linker"))
        assertEquals(InterpKind.MUSL, GlibcCompat.kindOf("/lib/ld-musl-x86_64.so.1"))
        assertEquals(InterpKind.OTHER, GlibcCompat.kindOf("/vendor/bin/other-loader"))
    }

    @Test
    fun `readInterp 原样返回解释器字符串`() {
        val tmp = Files.createTempDirectory("read-interp").toFile()
        try {
            val file = elf64(tmp, "node", GLIBC_INTERP)
            assertEquals(GLIBC_INTERP, GlibcCompat.readInterp(file))
            assertNull(GlibcCompat.readInterp(File(tmp, "absent")))
        } finally {
            tmp.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 改写
    // ------------------------------------------------------------------

    @Test
    fun `rewrite 改写后指向本机 loader 且文件只在末尾增长`() {
        val tmp = Files.createTempDirectory("rewrite64").toFile()
        try {
            val file = elf64(tmp, "node", GLIBC_INTERP)
            val before = file.readBytes()

            assertEquals(InterpRewrite.PATCHED, GlibcCompat.rewrite(file, loader))

            val after = file.readBytes()
            // ELF 头与解释器字符串**原位不动**：改写只碰 PT_INTERP 的 offset/filesz 两个字段
            assertArrayEquals(before.copyOfRange(0, 64), after.copyOfRange(0, 64))
            assertArrayEquals(
                before.copyOfRange(120, before.size),
                after.copyOfRange(120, before.size),
            )
            // 增长量 = 新 loader 路径 + 结尾 NUL，且正好落在文件末尾
            assertEquals(before.size + loader.length + 1, after.size)
            assertArrayEquals(
                (loader + "\u0000").toByteArray(Charsets.US_ASCII),
                after.copyOfRange(after.size - loader.length - 1, after.size),
            )
            assertEquals(loader, GlibcCompat.readInterp(file))
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `rewrite 幂等第二次返回 ALREADY`() {
        val tmp = Files.createTempDirectory("rewrite-idem").toFile()
        try {
            val file = elf64(tmp, "node", GLIBC_INTERP)
            assertEquals(InterpRewrite.PATCHED, GlibcCompat.rewrite(file, loader))
            val size = file.length()
            assertEquals(InterpRewrite.ALREADY, GlibcCompat.rewrite(file, loader))
            assertEquals(size, file.length())
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `rewrite 跳过 bionic 静态 musl 与非 ELF`() {
        val tmp = Files.createTempDirectory("rewrite-skip").toFile()
        try {
            assertEquals(InterpRewrite.SKIPPED, GlibcCompat.rewrite(elf64(tmp, "bionic", "/system/bin/linker64"), loader))
            assertEquals(InterpRewrite.SKIPPED, GlibcCompat.rewrite(elf64(tmp, "static", null), loader))
            assertEquals(InterpRewrite.SKIPPED, GlibcCompat.rewrite(elf64(tmp, "musl", "/lib/ld-musl-aarch64.so.1"), loader))
            val text = File(tmp, "shim").apply { writeText("#!/system/bin/sh\n") }
            assertEquals(InterpRewrite.SKIPPED, GlibcCompat.rewrite(text, loader))
            assertEquals(InterpRewrite.SKIPPED, GlibcCompat.rewrite(File(tmp, "missing"), loader))
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `rewrite 支持 32 位 ELF 的程序头布局`() {
        val tmp = Files.createTempDirectory("rewrite32").toFile()
        try {
            val file = elf32(tmp, "node", GLIBC32_INTERP)
            assertEquals(InterpRewrite.PATCHED, GlibcCompat.rewrite(file, loader))
            assertEquals(loader, GlibcCompat.readInterp(file))
        } finally {
            tmp.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 树扫描
    // ------------------------------------------------------------------

    @Test
    fun `rewriteTree 只改 glibc ELF 并返回改写数`() {
        val tmp = Files.createTempDirectory("rewrite-tree").toFile()
        try {
            File(tmp, "bin").mkdirs()
            elf64(File(tmp, "bin"), "glibc-a", GLIBC_INTERP)
            elf64(File(tmp, "bin"), "glibc-b", GLIBC_INTERP)
            elf64(File(tmp, "bin"), "bionic", "/system/bin/linker64")
            File(tmp, "bin/README.md").writeText("not an elf")
            File(tmp, "nested/deep").mkdirs()
            elf64(File(tmp, "nested/deep"), "glibc-c", GLIBC_INTERP)

            assertEquals(3, GlibcCompat.rewriteTree(tmp, loader))

            // 幂等：第二次扫一遍不再改写
            assertEquals(0, GlibcCompat.rewriteTree(tmp, loader))
            assertEquals(loader, GlibcCompat.readInterp(File(tmp, "nested/deep/glibc-c")))
            assertEquals("/system/bin/linker64", GlibcCompat.readInterp(File(tmp, "bin/bionic")))
            assertEquals(0, GlibcCompat.rewriteTree(File(tmp, "does-not-exist"), loader))
        } finally {
            tmp.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 构造用的最小 ELF
    // ------------------------------------------------------------------

    /** ELF64 头 64B + 一个 56B 的 PT_INTERP 段头 + 解释器字符串（[interp] 为 null = 无该段）。 */
    private fun elf64(dir: File, name: String, interp: String?): File {
        val body = interp?.let { (it.toByteArray(Charsets.US_ASCII) + 0) }
        val size = 64 + 56 + (body?.size ?: 0)
        val buf = ByteBuffer.wrap(ByteArray(size)).order(ByteOrder.LITTLE_ENDIAN)
        eIdent(buf)
        buf.putShort(16, 2)            // e_type = ET_EXEC
        buf.putShort(18, 183)          // e_machine = EM_AARCH64
        buf.putInt(20, 1)              // e_version
        buf.putLong(24, 0x400000)      // e_entry
        buf.putLong(32, 64)            // e_phoff
        buf.putLong(40, 0)             // e_shoff
        buf.putInt(48, 0)              // e_flags
        buf.putShort(52, 64)           // e_ehsize
        buf.putShort(54, 56)           // e_phentsize
        buf.putShort(56, if (body == null) 0 else 1)  // e_phnum
        buf.putShort(58, 0)
        buf.putShort(60, 0)
        buf.putShort(62, 0)
        if (body != null) {
            buf.putInt(64 + 0, 3)      // p_type = PT_INTERP
            buf.putInt(64 + 4, 4)      // p_flags = PF_R
            buf.putLong(64 + 8, 120)   // p_offset
            buf.putLong(64 + 16, 0x400000)
            buf.putLong(64 + 24, 0x400000)
            buf.putLong(64 + 32, body.size.toLong())
            buf.putLong(64 + 40, body.size.toLong())
            buf.putLong(64 + 48, 1)
            System.arraycopy(body, 0, buf.array(), 120, body.size)
        }
        return File(dir, name).apply { writeBytes(buf.array()) }
    }

    /** ELF32：头 52B + 一个 32B 的 PT_INTERP 段头 + 解释器字符串。 */
    private fun elf32(dir: File, name: String, interp: String): File {
        val body = interp.toByteArray(Charsets.US_ASCII) + 0
        val size = 52 + 32 + body.size
        val buf = ByteBuffer.wrap(ByteArray(size)).order(ByteOrder.LITTLE_ENDIAN)
        eIdent(buf, elf32 = true)
        buf.putShort(16, 2)            // e_type
        buf.putShort(18, 40)           // e_machine = EM_ARM
        buf.putInt(20, 1)
        buf.putInt(24, 0x8000)         // e_entry
        buf.putInt(28, 52)             // e_phoff
        buf.putInt(32, 0)              // e_shoff
        buf.putInt(36, 0)              // e_flags
        buf.putShort(40, 52)           // e_ehsize
        buf.putShort(42, 32)           // e_phentsize
        buf.putShort(44, 1)            // e_phnum
        buf.putShort(46, 0)
        buf.putShort(48, 0)
        buf.putShort(50, 0)
        buf.putInt(52 + 0, 3)          // p_type
        buf.putInt(52 + 4, 84)         // p_offset
        buf.putInt(52 + 8, 0x8000)     // p_vaddr
        buf.putInt(52 + 12, 0x8000)    // p_paddr
        buf.putInt(52 + 16, body.size) // p_filesz
        buf.putInt(52 + 20, body.size) // p_memsz
        buf.putInt(52 + 24, 5)         // p_flags
        buf.putInt(52 + 28, 0x1000)    // p_align
        System.arraycopy(body, 0, buf.array(), 84, body.size)
        return File(dir, name).apply { writeBytes(buf.array()) }
    }

    private fun eIdent(buf: ByteBuffer, elf32: Boolean = false) {
        buf.put(0, 0x7F.toByte())
        buf.put(1, 'E'.code.toByte())
        buf.put(2, 'L'.code.toByte())
        buf.put(3, 'F'.code.toByte())
        buf.put(4, (if (elf32) 1 else 2).toByte()) // EI_CLASS
        buf.put(5, 1.toByte())                     // EI_DATA = 小端
        buf.put(6, 1.toByte())                     // EI_VERSION
        buf.put(7, 0.toByte())                     // EI_OSABI
        repeat(8) { buf.put(8 + it, 0.toByte()) }
    }

    private companion object {
        const val GLIBC_INTERP = "/lib/ld-linux-aarch64.so.1"
        const val GLIBC32_INTERP = "/lib/ld-linux-armhf.so.3"
    }
}
