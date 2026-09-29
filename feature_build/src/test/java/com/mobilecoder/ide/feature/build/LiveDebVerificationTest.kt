package com.mobilecoder.ide.feature.build

import java.io.File
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 一次性实测（不进常规回归）：从**真实 Debian 镜像**下载索引与三个 .deb，
 * 走完 [GlibcDebRuntime.parseIndex] → [ArchiveExtractor.extractDeb] → [GlibcDebRuntime.flatten]
 * 全链路，验证内置源「真实可达且有安装包、解包能组装出 loader」。
 */
class LiveDebVerificationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun realDebianMirror_fullPipeline() {
        val base = "https://deb.debian.org/debian"

        // 1) 真实 Packages.gz 索引 → 解析三个包路径（无网络时按「假设不成立」跳过，不误报失败）
        val indexFile = File(tmp.newFolder("idx"), "Packages.gz")
        try {
            URL("$base/dists/trixie/main/binary-arm64/Packages.gz").openStream().use { input ->
                indexFile.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (t: Throwable) {
            Assume.assumeTrue("真实 Debian 镜像不可达，跳过实测（${t.message}）", false)
            return
        }
        println("索引下载：${indexFile.length()} 字节")
        val paths = indexFile.inputStream().use { stream ->
            GlibcDebRuntime.parseIndex(stream, GlibcDebRuntime.REQUIRED_PACKAGES.toSet())
        }
        assertEquals("索引应解析出 3 个包", 3, paths.size)
        paths.forEach { (pkg, rel) -> println("  $pkg -> $rel") }

        // 2) 三个真实 .deb 全部下载并解包到同一根
        val root = tmp.newFolder("deb-root")
        paths.forEach { (pkg, rel) ->
            val deb = File(tmp.newFolder("pkg-$pkg"), rel.substringAfterLast('/'))
            URL("$base/$rel").openStream().use { input ->
                deb.outputStream().use { output -> input.copyTo(output) }
            }
            println("  下载 $pkg：${deb.length()} 字节")
            val result = ArchiveExtractor.extractDeb(deb.inputStream(), root, deb.length())
            println("  解包 $pkg：entries=${result.entries} bytes=${result.readBytes}")
            assertTrue("$pkg 解包必须有条目", result.entries > 0)
        }

        // 3) 扁平化组装 lib/ 并校验 loader
        val lib = File(tmp.newFolder("out"), "lib")
        val flat = GlibcDebRuntime.flatten(root, lib)
        println("flatten: copied=${flat.copied} skipped=${flat.skipped} loader=${flat.loaderName}")
        assertTrue("必须组装出 ld-linux* loader", flat.loaderName != null)
        assertTrue("应复制出多个运行库", flat.copied > 5)

        // 4) loader 是真实 ELF（7F 'E' 'L' 'F'）
        val loader = File(lib, flat.loaderName!!)
        val head = loader.readBytes().copyOf(4)
        assertTrue(
            "loader 应为 ELF，实际 ${head.joinToString(",") { (it.toInt() and 0xff).toString(16) }}",
            (head[0].toInt() and 0xff) == 0x7f && head[1] == 'E'.code.toByte() &&
                head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte(),
        )
        // 5) libc / libstdc++ 体量抽查
        assertTrue("libc.so.6 应 > 1MB", File(lib, "libc.so.6").length() > 1_000_000)
        // Debian 的 libstdc++6 包内文件名带 `+`（libstdc++.so.6，可能为软链，flatten 会解成真实文件）
        assertTrue("libstdc++.so.6 应存在且 > 1MB", File(lib, "libstdc++.so.6").length() > 1_000_000)
        println("loader=${loader.name} ${loader.length()} 字节；lib/ 共 ${lib.listFiles()?.size} 个文件")
    }
}
