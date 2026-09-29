package com.mobilecoder.ide.feature.build

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android 上必须用 bionic ELF 的 node：官方 linux-arm64 依赖 glibc 动态链接器，
 * execve 会报 `No such file or directory`。探测 / shim 必须以 `files/bin/node` ELF 为准。
 */
class BuildEnvironmentNodeExecTest {

    @Test
    fun `ELF 魔数识别真二进制并拒绝 shell shim`() {
        val tmp = Files.createTempDirectory("node-elf").toFile()
        try {
            val elf = File(tmp, "node")
            elf.writeBytes(elfMagic())
            assertTrue(BuildEnvironment.isElfBinary(elf))

            val shim = File(tmp, "npm")
            shim.writeText("#!/system/bin/sh\nexec /sdk/node/bin/node \"\$@\"\n")
            assertFalse(BuildEnvironment.isElfBinary(shim))

            assertFalse(BuildEnvironment.isElfBinary(File(tmp, "missing")))
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `符号链接即使指向 ELF 也不当作 files bin 真二进制`() {
        val tmp = Files.createTempDirectory("node-link").toFile()
        try {
            val real = File(tmp, "real-node")
            real.writeBytes(elfMagic())
            val link = File(tmp, "node")
            Files.createSymbolicLink(link.toPath(), real.toPath())
            assertFalse(BuildEnvironment.isElfBinary(link))
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `resolveNodeExec 优先 files bin ELF 而不是 sdk glibc node`() {
        val files = Files.createTempDirectory("files").toFile()
        try {
            File(files, "bin").mkdirs()
            File(files, "sdk/node/bin").mkdirs()
            val binNode = File(files, "bin/node")
            binNode.writeBytes(elfMagic())
            File(files, "sdk/node/bin/node").writeText("glibc-placeholder")
            assertEquals(
                binNode.canonicalPath,
                BuildEnvironment.resolveNodeExec(files)!!.canonicalPath,
            )
        } finally {
            files.deleteRecursively()
        }
    }

    @Test
    fun `resolveNodeExec 在 bin 是 shim 时回落到 sdk node`() {
        val files = Files.createTempDirectory("files").toFile()
        try {
            File(files, "bin").mkdirs()
            File(files, "sdk/node/bin").mkdirs()
            File(files, "bin/node").writeText("#!/system/bin/sh\nexec sdk/node/bin/node \"\$@\"\n")
            val sdkNode = File(files, "sdk/node/bin/node")
            sdkNode.writeText("placeholder")
            assertEquals(
                sdkNode.canonicalPath,
                BuildEnvironment.resolveNodeExec(files)!!.canonicalPath,
            )
        } finally {
            files.deleteRecursively()
        }
    }

    @Test
    fun `resolveNodeExec 两者都没有时返回 null`() {
        val files = Files.createTempDirectory("empty-files").toFile()
        try {
            assertNull(BuildEnvironment.resolveNodeExec(files))
        } finally {
            files.deleteRecursively()
        }
    }

    @Test
    fun `findNpmCli 优先 sdk 再 files lib`() {
        val files = Files.createTempDirectory("npm-cli").toFile()
        try {
            assertNull(BuildEnvironment.findNpmCli(files))
            val libCli = File(files, "lib/node_modules/npm/bin/npm-cli.js")
            libCli.parentFile.mkdirs()
            libCli.writeText("module.exports = {}")
            assertEquals(libCli.canonicalPath, BuildEnvironment.findNpmCli(files)!!.canonicalPath)

            val sdkCli = File(files, "sdk/node/lib/node_modules/npm/bin/npm-cli.js")
            sdkCli.parentFile.mkdirs()
            sdkCli.writeText("module.exports = {}")
            assertEquals(sdkCli.canonicalPath, BuildEnvironment.findNpmCli(files)!!.canonicalPath)
        } finally {
            files.deleteRecursively()
        }
    }

    @Test
    fun `relinkBrokenSdkNode 把 sdk 的 glibc node 换成指向 files bin ELF 的链接`() {
        val files = Files.createTempDirectory("relink").toFile()
        try {
            File(files, "bin").mkdirs()
            File(files, "sdk/node/bin").mkdirs()
            val working = File(files, "bin/node")
            working.writeBytes(elfMagic())
            val sdkNode = File(files, "sdk/node/bin/node")
            sdkNode.writeText("glibc-placeholder")

            BuildEnvironment.relinkBrokenSdkNode(files)

            assertTrue(Files.isSymbolicLink(sdkNode.toPath()))
            // 不用 sdkNode.canonicalPath 断言：部分 Windows / JDK 组合上
            // File.getCanonicalPath() 并不解析符号链接，会把链接自身路径返回，
            // 导致在 Android 上完全正确的实现被误判。isSameFile 走的是
            // 解析后的真实文件身份，两平台语义一致。
            assertEquals(working.toPath(), Files.readSymbolicLink(sdkNode.toPath()))
            assertTrue(Files.isSameFile(sdkNode.toPath(), working.toPath()))
        } finally {
            files.deleteRecursively()
        }
    }

    private fun elfMagic(): ByteArray =
        byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 1, 2, 3, 4)
}
