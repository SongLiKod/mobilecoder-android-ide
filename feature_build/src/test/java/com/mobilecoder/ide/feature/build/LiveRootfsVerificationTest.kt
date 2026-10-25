package com.mobilecoder.ide.feature.build

import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 真源验证：内置 rootfs 镜像与 proot 三件套地址**实测可达**且内容形态正确
 * （rootfs = gzip tar，deb = ar 归档），并把最小的 `.deb` 真解包断言目录布局。
 *
 * proot 三件套的版本**由 [RootfsManager.resolveProotDebUrls] 实时解析**，因此
 * 上游升版不再需要改代码/改测试 —— 本组测的是「解析出来的地址仍然活着」，
 * 外加一条对账：解析结果必须等于上游 `Packages` 索引里的 `Filename`
 * （防止索引挂了却悄悄退回写死的兜底地址）。
 *
 * 分级策略：
 *  - **连接不上**（超时 / DNS / TLS，网络层异常）→ [Assume] 整体跳过，不红；
 *  - **服务器有响应**但状态码异常 / 魔数不对 / 解析与索引对不上 → 判定失败 ——
 *    能抓住「域名还在但包没了 / 换路径 / 索引结构变了 / 挂错内容」几类回归。
 */
class LiveRootfsVerificationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 连接 [url] 读前 3 字节；网络层不可达返回 null。 */
    private fun fetchHead3(url: String): Pair<Int, ByteArray>? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("Range", "bytes=0-2")
        try {
            val code = conn.responseCode
            val head = ByteArray(3)
            var got = 0
            runCatching {
                val input = conn.inputStream
                while (got < 3) {
                    val n = input.read(head, got, 3 - got)
                    if (n < 0) break
                    got += n
                }
                input.close()
            }
            code to head.copyOf(got)
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** 可达性检查：连不上 → 跳过整组；连上了 → 返回状态码与头部字节。 */
    private fun reachableOrSkip(url: String): Pair<Int, ByteArray> {
        val result = fetchHead3(url)
        Assume.assumeTrue("外网不可达（无法连接 $url），跳过真源验证", result != null)
        return result!!
    }

    /** @return 响应头 3 字节（状态码必须 2xx/3xx）。 */
    private fun fetchVerifiedHead(url: String): ByteArray {
        val (code, head) = reachableOrSkip(url)
        assertTrue("$url 实测状态码 $code（期望 2xx/3xx；404/403 = 包没了或路径变了）", code in 200..399)
        return head
    }

    private fun assertGzipMagic(url: String) {
        val head = fetchVerifiedHead(url)
        assertTrue("$url 不是 gzip（缺 1f 8b 魔数）", head.size >= 2)
        assertEquals("$url[0]", 0x1f, head[0].toInt() and 0xff)
        assertEquals("$url[1]", 0x8b, head[1].toInt() and 0xff)
    }

    @Test
    fun rootfsPackages_allFourMirrorsAreReachableGzipTars() {
        RootfsManager.ROOTFS_MIRRORS.forEach { mirror ->
            listOf("aarch64", "x64").forEach { arch ->
                assertGzipMagic("${mirror.base}/releases/24.04/release/${RootfsManager.rootfsFileName(arch)}")
            }
        }
    }

    /** 实时解析的三件套地址（索引拉不到时内部退回兜底地址，后续断言照样跑）。 */
    private fun resolvedProotDebs(arch: String): List<String> =
        runBlocking { RootfsManager.resolveProotDebUrls(arch) }

    @Test
    fun prootDebs_allSixAreReachableArArchives() {
        listOf("aarch64", "x64").forEach { arch ->
            resolvedProotDebs(arch).forEach { url ->
                val head = fetchVerifiedHead(url)
                assertTrue("$url 缺 ar 魔数（!<arch>）", head.size >= 3)
                assertEquals("$url[0]", 0x21, head[0].toInt() and 0xff) // '!'
                assertEquals("$url[1]", 0x3c, head[1].toInt() and 0xff) // '<'
                assertEquals("$url[2]", 0x61, head[2].toInt() and 0xff) // 'a'
            }
        }
    }

    /**
     * 解析结果必须与上游 `Packages` 索引里的 `Filename` 逐条对得上 ——
     * 防止索引拉取/解析悄悄失败后**一直退回写死的兜底地址**（那种情况下
     * 上面的可达性断言今天还能过，等到兜底地址也 404 才会炸）。
     */
    @Test
    fun prootDebs_resolutionMatchesLiveIndex_notStaleFallback() {
        listOf("aarch64", "x64").forEach { arch ->
            val index = EnvDownloader.fetchText(RootfsManager.termuxPackagesIndex(arch))
            Assume.assumeTrue("包索引不可达（$arch），跳过对账", index != null)
            val expected = RootfsManager.parseDebFilenames(
                index!!,
                listOf("proot", "libtalloc", "libandroid-shmem"),
            )
            assertEquals("索引里没解析出三件套（结构变了？）：${expected.keys}", 3, expected.size)

            val resolved = resolvedProotDebs(arch)
            expected.forEach { (pkg, path) ->
                assertTrue(
                    "$pkg 的解析结果不含索引路径 $path（退回兜底地址了？）：$resolved",
                    resolved.any { it.endsWith(path) },
                )
            }
        }
    }

    @Test
    fun smallestDeb_fullDownloadAndExtract_matchesExpectedLayout() {
        // libandroid-shmem 是三件套里最小的（十几 KB）：真下载 + 真解包。
        // Termux deb 的 data.tar 带 `data/data/com.termux/files/usr/…` 前缀，
        // 归位规则（placeProotFiles 按路径后缀匹配）必须照常消费。
        val url = resolvedProotDebs("aarch64").first { it.contains("libandroid-shmem") }
        fetchVerifiedHead(url) // 可达 + 魔数

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 20000
        val bytes = try {
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
        assertTrue("$url 下载为空", bytes.isNotEmpty())
        assertEquals("!", bytes[0].toInt().toChar().toString())

        val staging = tmp.newFolder("deb-staging")
        ArchiveExtractor.extractDeb(ByteArrayInputStream(bytes), staging, bytes.size.toLong())

        val entries = staging.walkTopDown().map { it.absolutePath.replace('\\', '/') }.toList()
        assertTrue(
            "解包后未找到 …/usr/lib/libandroid-shmem.so（deb 布局变了？）：" + entries.joinToString(),
            entries.any { it.endsWith("/usr/lib/libandroid-shmem.so") },
        )

        // 真实 deb → 归位规则：库落到 files/linux/lib/（前缀不影响后缀匹配）
        val linux = File(tmp.root, "linux-probe")
        val copied = RootfsManager.placeProotFiles(staging, linux)
        assertTrue("placeProotFiles 未消费真实 deb（copied=$copied）", copied >= 1)
        assertTrue(File(linux, "lib/libandroid-shmem.so").isFile)
    }
}
