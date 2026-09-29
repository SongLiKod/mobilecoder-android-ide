package com.mobilecoder.ide.feature.build

import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.file.Files
import java.util.zip.GZIPInputStream

/**
 * glibc 运行时的 **Debian 仓库在线组装**：与 `tools/glibc-runtime/build.sh` 同款流程 ——
 *
 * 1. 取 `<镜像>/dists/<suite>/main/binary-<debArch>/Packages.gz` 软件包索引，动态解析
 *    `libc6` / `libgcc-s1` / `libstdc++6` 的 `Filename`（**不写死版本号**：Debian 点版本
 *    滚动后文件名自动跟随，避免硬编码过期 404）；
 * 2. 逐个下载 `.deb` 并用 `ArchiveExtractor.extractDeb` 解包到同一根目录；
 * 3. [flatten] 把根目录里的 `*.so*` 按 build.sh 的规则扁平化进 `lib/`：软链按包内布局解析
 *    （绝对路径挂到解包根）、跳过 gconv/locale/audit/lint、同名后写覆盖，且必须解出
 *    `ld-linux*` loader（否则运行时不可用，交由调用方报错换源）。
 *
 * 为什么内置源用 Debian 仓库：这是**唯一真实、公开、持续可达**的 glibc aarch64/x86_64 分发
 * （Debian 官方与清华 TUNA / 阿里云 / 腾讯云等国内镜像全量同步 pool），不依赖任何自建
 * 占位域名；四个内置源均实测「索引 + 三个 .deb 均 HTTP 200」（2026-09）。
 *
 * 注意：在线组装只含运行库（node/JDK/Gradle 可运行），**不含 DNS/exec 钩子**
 * （钩子须在 Linux 上交叉编译，见 build.sh）；要完整能力用 APK 内置包
 * （`EnvDownloader.bundledGlibcAsset`）或自托管 tar.gz（自定义源）。
 */
object GlibcDebRuntime {

    /** Debian suite（与 `build.sh` 的 `SUITE` 一致：trixie 提供 glibc 2.41）。 */
    const val DEFAULT_SUITE = "trixie"

    /** 运行时必须的三个 .deb（与 `build.sh` 的 `pkgs` 一致：node/JDK 的动态依赖）。 */
    val REQUIRED_PACKAGES = listOf("libc6", "libgcc-s1", "libstdc++6")

    /** App 架构 → Debian 架构 token（索引路径与 .deb 文件名后缀都按它拼）。 */
    fun debArch(appArch: String): String = when (appArch) {
        "aarch64" -> "arm64"
        "x64" -> "amd64"
        else -> throw IllegalArgumentException("不支持的架构：$appArch（仅 aarch64 / x64）")
    }

    /**
     * 从 Packages 索引**行流**解析 [wanted] 各包的 `Filename`（懒序列，可提前停止）。
     *
     * 按 stanza（空行分隔）处理、`Package` 精确匹配（`libc6` 不会命中 `libc6-dev`）；
     * 找齐即 break，配合 gzip 流式读取时不必解压整个索引（约 30MB+）。
     *
     * @param lines 索引文本行
     * @return 包名 → `pool/.../<file>.deb`；未找到的包不在结果里（由调用方报错）
     */
    fun parseIndex(lines: Sequence<String>, wanted: Set<String>): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var pkg: String? = null
        var filename: String? = null
        fun flush() {
            val p = pkg
            if (p != null && p in wanted && filename != null) out[p] = filename!!
            pkg = null
            filename = null
        }
        for (line in lines) {
            if (line.isEmpty()) {
                flush()
                if (out.size == wanted.size) break
                continue
            }
            when {
                line.startsWith("Package: ") -> pkg = line.substringAfter("Package: ").trim()
                line.startsWith("Filename: ") -> filename = line.substringAfter("Filename: ").trim()
            }
        }
        flush()
        return out
    }

    /** 便捷重载：按 gzip 流解析索引（`Packages.gz`）。 */
    fun parseIndex(input: InputStream, wanted: Set<String>): Map<String, String> =
        GZIPInputStream(input).use { gz ->
            BufferedReader(InputStreamReader(gz)).use { reader ->
                parseIndex(reader.lineSequence(), wanted)
            }
        }

    /** 扁平化统计 + 解出的 loader 文件名（null = 没有 `ld-linux*`，调用方须视为失败）。 */
    data class FlattenResult(val copied: Int, val skipped: Int, val loaderName: String?)

    /** 与 build.sh 一致：这些目录对运行时无用（gconv/locale/audit/lint）。 */
    private val SKIP_PATH_MARKERS = listOf("/gconv/", "/locale/", "/audit/", "/lint/")

    /**
     * 把解包根 [root] 下的 `*.so*`（普通文件与软链）扁平化复制到 [destLib]，文件名取原名。
     *
     * - **不深入软链目录**（防环，同 `find` 默认行为）；跳过 gconv/locale/audit/lint；
     * - 软链目标按包内布局解析：绝对路径挂到 [root]（`/usr/...` → `root/usr/...`），相对路径
     *   按所在目录；跟随上限 8 跳，断链/成环计入 [FlattenResult.skipped]；
     * - 同名后写覆盖、复制后补可执行位（与 build.sh 的 `cp` + `chmod 0755` 一致）。
     */
    fun flatten(root: File, destLib: File): FlattenResult {
        destLib.mkdirs()
        val rootDir = root.canonicalFile
        var copied = 0
        var skipped = 0
        var loader: String? = null
        for (f in collectSoFiles(rootDir).sortedBy { it.path }) {
            if (SKIP_PATH_MARKERS.none { f.invariantSeparatorsPath.contains(it) }) {
                val real = resolveTarget(f, rootDir)
                if (real != null) {
                    val dest = File(destLib, f.name)
                    real.copyTo(dest, overwrite = true)
                    runCatching { dest.setExecutable(true, false) }
                    copied++
                    if (loader == null && f.name.startsWith("ld-linux")) loader = f.name
                    continue
                }
            }
            skipped++
        }
        return FlattenResult(copied, skipped, loader)
    }

    /** 收集 [root] 下名字含 `.so` 的普通文件与软链（不跟随软链目录，带 canonical 去重防环）。 */
    private fun collectSoFiles(root: File): List<File> {
        val out = mutableListOf<File>()
        val stack = ArrayDeque<File>()
        val seen = mutableSetOf<String>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val key = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
            if (!seen.add(key)) continue
            val children = dir.listFiles() ?: continue
            for (c in children) {
                when {
                    Files.isSymbolicLink(c.toPath()) -> if (c.name.contains(".so")) out += c
                    c.isDirectory -> stack.add(c)
                    c.isFile && c.name.contains(".so") -> out += c
                }
            }
        }
        return out
    }

    /** 跟随软链到真实文件（绝对目标挂到解包根；8 跳上限；断链/成环返回 null）。 */
    private fun resolveTarget(start: File, root: File): File? {
        var cur = start
        var hops = 0
        while (hops < 8 && Files.isSymbolicLink(cur.toPath())) {
            val target = runCatching { Files.readSymbolicLink(cur.toPath()) }.getOrNull() ?: return null
            // Windows 上软链目标会带反斜杠，先归一成 / 再判断绝对路径（Android/Linux 不受影响）
            val text = target.toString().replace('\\', '/')
            cur = if (text.startsWith("/")) File(root, text.trimStart('/')) else File(cur.parentFile, text)
            hops++
        }
        return if (cur.isFile) cur else null
    }
}
