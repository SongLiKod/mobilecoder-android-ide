package com.mobilecoder.ide.feature.build

import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 打包被用户取消（[BuildRunner.cancel] 置位后中止）。 */
internal class PackagingCancelledException : Exception("已取消打包")

/**
 * 源码 / dist 打包器：把目录压成单个 zip（**进程内**完成，无需子进程与工具链）。
 *
 * 供「构建与运行」页对无需编译（或暂不能编译）的项目提供可 下载 / 分享 的产物，
 * 也用于把 npm 构建出的 `dist` 目录固化成单文件产物。
 *
 * 产物固定写到 `<project>/build/outputs/package/`（与 APK / JAR 同级，
 * 编辑器文件树默认隐藏 `build`），同名覆盖 = 每次构建只保留最新一份。
 *
 * 排除规则（[shouldSkip]）：
 *  - 任意层级以 `.` 开头的文件 / 目录（`.git`、`.gradle`、`.gitignore` …）；
 *  - `node_modules` / `captures` / `dist` / `out`（依赖与构建产物，任意层级）；
 *  - `build` **深度 ≤2** 的子树（根 `build/`、`模块/build/` 是产物目录）——
 *    与 [com.mobilecoder.ide.core.storage.FileRepository.isArtifact] 同口径；
 *    深层同名目录是合法源码包（如 `src/main/kotlin/.../build/`），保留。
 */
internal object ProjectPackager {

    /** 产物目录：`<project>/build/outputs/package`。 */
    fun outputDir(projectDir: File): File = File(projectDir, "build/outputs/package")

    /** 任意层级都排除的目录名。 */
    private val SKIP_ANY_DEPTH = setOf("node_modules", "captures", "dist", "out")

    /** 判断相对路径是否应排除（[rel] 用 `/` 分隔，目录不含结尾斜杠）。 */
    internal fun shouldSkip(rel: String, isDir: Boolean): Boolean {
        if (rel.isEmpty()) return false
        val segments = rel.split('/')
        if (segments.any { it.startsWith(".") }) return true
        if (isDir && segments.last() in SKIP_ANY_DEPTH) return true
        // 根 build（1 段）与模块 build（2 段）是产物目录；更深的是源码包
        val buildIndex = segments.indexOf("build")
        if (buildIndex >= 0 && buildIndex < 2) return true
        return false
    }

    /**
     * 打包**项目源码**（zip 内条目相对项目根）。
     *
     * @param outName 输出文件名（默认 `<项目名>.zip`）
     * @return 生成的 zip 文件
     * @throws PackagingCancelledException [shouldCancel] 返回 true 时中止（已删除半成品）
     */
    fun packageProject(
        projectDir: File,
        outName: String = "${projectDir.name}.zip",
        shouldCancel: () -> Boolean = { false },
        onProgress: (Int) -> Unit = {},
    ): File {
        val out = File(outputDir(projectDir), outName)
        return zipTree(
            sourceRoot = projectDir,
            out = out,
            relFilter = { rel, isDir -> !shouldSkip(rel, isDir) },
            shouldCancel = shouldCancel,
            onProgress = onProgress,
        )
    }

    /**
     * 打包 npm 构建产物目录（`dist` → `out` → `build` 里第一个非空的）。
     *
     * @return 生成的 `<项目名>-dist.zip`；**没有任何产物目录时返回 null**
     */
    fun packageNodeDist(
        projectDir: File,
        shouldCancel: () -> Boolean = { false },
        onProgress: (Int) -> Unit = {},
    ): File? {
        val source = listOf("dist", "out", "build")
            .map { File(projectDir, it) }
            .firstOrNull { it.isDirectory && it.listFiles()?.isNotEmpty() == true }
            ?: return null
        // 选中根 build 时跳过我们自己的产物目录，避免 zip 把自己包进去
        val selfPrefix = if (source.name == "build") "outputs/package" else null
        return zipTree(
            sourceRoot = source,
            out = File(outputDir(projectDir), "${projectDir.name}-dist.zip"),
            relFilter = { rel, _ -> selfPrefix == null || !rel.startsWith(selfPrefix) },
            shouldCancel = shouldCancel,
            onProgress = onProgress,
        )
    }

    /** 实际压缩：条目名 = 相对 [sourceRoot] 的路径（`/` 分隔）。 */
    private fun zipTree(
        sourceRoot: File,
        out: File,
        relFilter: (rel: String, isDir: Boolean) -> Boolean,
        shouldCancel: () -> Boolean,
        onProgress: (Int) -> Unit,
    ): File {
        out.parentFile?.mkdirs()
        if (out.exists()) out.delete()
        var count = 0
        try {
            ZipOutputStream(out.outputStream().buffered()).use { zip ->
                sourceRoot.walkTopDown()
                    .onEnter { dir ->
                        dir == sourceRoot || runCatching {
                            val rel = dir.relativeTo(sourceRoot).invariantSeparatorsPath
                            relFilter(rel, true)
                        }.getOrDefault(false)
                    }
                    .filter { it.isFile }
                    .forEach { file ->
                        if (count % 128 == 0) {
                            if (shouldCancel()) throw PackagingCancelledException()
                            if (count > 0) onProgress(count)
                        }
                        val rel = runCatching { file.relativeTo(sourceRoot).invariantSeparatorsPath }
                            .getOrDefault(file.name)
                        if (!relFilter(rel, false)) return@forEach
                        zip.putNextEntry(ZipEntry(rel))
                        FileInputStream(file).use { it.copyTo(zip) }
                        zip.closeEntry()
                        count++
                    }
                if (shouldCancel()) throw PackagingCancelledException()
            }
            onProgress(count)
        } catch (t: Throwable) {
            runCatching { out.delete() }
            throw t
        }
        return out
    }
}
