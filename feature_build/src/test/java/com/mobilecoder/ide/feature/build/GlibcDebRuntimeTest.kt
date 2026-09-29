package com.mobilecoder.ide.feature.build

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [GlibcDebRuntime]：Debian 索引的包路径解析、架构映射、`.deb` 解包后的扁平化组装。
 *
 * 对应 `tools/glibc-runtime/build.sh` 的同名流程（Packages 解析 / `*.so*` 落 `lib/`）。
 */
class GlibcDebRuntimeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- 架构映射（索引路径与 .deb 文件名后缀都靠它拼）----

    @Test
    fun debArch_abiToDebianToken() {
        assertEquals("arm64", GlibcDebRuntime.debArch("aarch64"))
        assertEquals("amd64", GlibcDebRuntime.debArch("x64"))
    }

    // ---- Packages 索引解析 ----

    @Test
    fun parseIndex_resolvesExactPackages_andIgnoresSiblings() {
        // 与真实 trixie 索引同构：Package 精确匹配（libc6 不命中 libc6-dev）、Filename 靠后
        val text = """
            Package: libc6-dev
            Filename: pool/main/g/glibc/libc6-dev_2.41-12+deb13u4_arm64.deb

            Package: libc6
            Architecture: arm64
            Filename: pool/main/g/glibc/libc6_2.41-12+deb13u4_arm64.deb

            Package: libgcc-s1
            Filename: pool/main/g/gcc-14/libgcc-s1_14.2.0-19_arm64.deb

            Package: libstdc++6
            Filename: pool/main/g/gcc-14/libstdc++6_14.2.0-19_arm64.deb
        """.trimIndent().lineSequence()

        val wanted = GlibcDebRuntime.REQUIRED_PACKAGES.toSet()
        val out = GlibcDebRuntime.parseIndex(text, wanted)

        assertEquals(3, out.size)
        assertEquals("pool/main/g/glibc/libc6_2.41-12+deb13u4_arm64.deb", out["libc6"])
        assertEquals("pool/main/g/gcc-14/libgcc-s1_14.2.0-19_arm64.deb", out["libgcc-s1"])
        assertEquals("pool/main/g/gcc-14/libstdc++6_14.2.0-19_arm64.deb", out["libstdc++6"])
    }

    @Test
    fun parseIndex_missingPackageAbsentNotEmptyValue() {
        val out = GlibcDebRuntime.parseIndex(
            sequenceOf(
                "Package: libc6",
                "Filename: pool/main/g/glibc/libc6_2.41-12_arm64.deb",
                "",
                "Package: libgcc-s1",
                "Filename: pool/main/g/gcc-14/libgcc-s1_14.2.0-19_arm64.deb",
            ),
            setOf("libc6", "libgcc-s1", "libstdc++6"),
        )
        assertEquals(2, out.size)
        assertTrue("libstdc++6" !in out)
        assertNull(out["libstdc++6"])
    }

    @Test
    fun parseIndex_stopsAfterAllFound_ignoresLaterNoise() {
        var consumed = 0
        val lines = sequence {
            yield("Package: libc6")
            yield("Filename: p/a.deb")
            yield("")
            yield("Package: libgcc-s1")
            yield("Filename: p/b.deb")
            yield("")
            yield("Package: libstdc++6")
            yield("Filename: p/c.deb")
            yield("")
            repeat(10_000) {
                yield("Package: noise$it")
                consumed++
            }
        }
        val out = GlibcDebRuntime.parseIndex(lines, setOf("libc6", "libgcc-s1", "libstdc++6"))
        assertEquals(3, out.size)
        // 找齐即停：后续索引（解压量可达数十 MB）不再消费
        assertEquals(0, consumed)
    }

    // ---- 扁平化组装 ----

    @Test
    fun flatten_copiesSoFilesIntoFlatLib() {
        val root = tmp.newFolder("deb-root")
        File(root, "usr/lib").mkdirs()
        File(root, "usr/lib/ld-linux-aarch64.so.1").writeText("loader")
        File(root, "usr/lib/x86_64").mkdirs()
        File(root, "usr/lib/x86_64/libc.so.6").writeText("libc")
        // build.sh 同款：gconv/locale/audit/lint 跳过；非 .so 文件不搬
        File(root, "usr/lib/gconv").mkdirs()
        File(root, "usr/lib/gconv/UTF-16.so").writeText("skip-me")
        File(root, "usr/share/doc").mkdirs()
        File(root, "usr/share/doc/copyright").writeText("not-a-lib")

        val destLib = File(tmp.newFolder("out"), "lib")
        val result = GlibcDebRuntime.flatten(root, destLib)

        assertEquals("ld-linux-aarch64.so.1", result.loaderName)
        assertEquals(2, result.copied)
        assertTrue(result.skipped >= 1)
        assertEquals("loader", File(destLib, "ld-linux-aarch64.so.1").readText())
        assertEquals("libc", File(destLib, "libc.so.6").readText())
        assertTrue(!File(destLib, "UTF-16.so").exists())
        assertTrue(!File(destLib, "copyright").exists())
    }

    @Test
    fun flatten_missingLoader_reportsNull() {
        val root = tmp.newFolder("deb-root-no-loader")
        File(root, "usr/lib").mkdirs()
        File(root, "usr/lib/libc.so.6").writeText("libc")

        val destLib = File(tmp.newFolder("out2"), "lib")
        val result = GlibcDebRuntime.flatten(root, destLib)

        assertNull(result.loaderName)
        assertEquals(1, result.copied)
    }

    @Test
    fun flatten_resolvesSymlinkToRealContent_whenCreatable() {
        val root = tmp.newFolder("deb-root-link")
        File(root, "usr/lib").mkdirs()
        val real = File(root, "usr/lib/ld-2.41.so")
        real.writeText("real-loader")
        // 软链指向包内绝对路径（Debian 布局：/usr/lib/ld-2.41.so → 解包根）
        val link = File(root, "usr/lib/ld-linux-aarch64.so.1")
        val linkCreated = runCatching {
            Files.createSymbolicLink(link.toPath(), File("/usr/lib/ld-2.41.so").toPath())
            true
        }.getOrDefault(false)

        val destLib = File(tmp.newFolder("out3"), "lib")
        val result = GlibcDebRuntime.flatten(root, destLib)

        if (linkCreated) {
            assertEquals("ld-linux-aarch64.so.1", result.loaderName)
            assertEquals(2, result.copied)
            assertEquals("real-loader", File(destLib, "ld-linux-aarch64.so.1").readText())
        } else {
            // Windows 未开开发者模式时建不了软链：只要不炸、loader 至少由真实文件提供
            assertEquals(1, result.copied)
            assertNull(result.loaderName)
        }
    }
}
