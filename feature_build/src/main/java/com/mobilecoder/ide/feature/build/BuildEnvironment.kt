package com.mobilecoder.ide.feature.build

import android.content.Context
import android.net.Uri
import com.mobilecoder.ide.core.nativebridge.NativeRuntime
import com.mobilecoder.ide.core.storage.AppStorage
import java.io.BufferedInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** 构建环境组件类别。 */
enum class EnvKind(val title: String) {
    JDK("JDK"),
    GRADLE("Gradle"),
    SDK("Android SDK"),
}

/** 单项环境检测结果。 */
data class EnvItem(
    val kind: EnvKind,
    /** 已配置/检测到的根目录，null 表示尚未导入。 */
    val path: String?,
    val ready: Boolean,
    /** 未就绪时的中文引导。 */
    val hint: String,
)

/** 整体环境体检结果。 */
data class EnvStatus(val items: List<EnvItem>) {

    /** JDK + Gradle + SDK 全部就绪才算构建环境可用。 */
    val ready: Boolean get() = items.all { it.ready }

    fun item(kind: EnvKind): EnvItem? = items.firstOrNull { it.kind == kind }
}

/**
 * 构建环境（PRD 2.7「移动端轻量化 Gradle 编译环境」/ TECH 4.6「内置轻量化 Android SDK + Gradle 精简环境」）。
 *
 * 设备端**不联网**：JDK / Gradle / Android SDK 全部由用户在「构建环境」页
 * 通过 SAF 选择本地 zip 导入到 `files/sdk/`（zip slip 防护 + 权限修复 + 幂等）。
 */
object BuildEnvironment {

    private const val TAG = "BuildEnvironment"

    /** 最近一次体检结果（UI 订阅）。 */
    private val _status = MutableStateFlow<EnvStatus?>(null)
    val status: StateFlow<EnvStatus?> = _status.asStateFlow()

    // ------------------------------------------------------------------
    // 目录约定（TECH.md 5 存储结构）
    // ------------------------------------------------------------------

    /** `files/sdk` —— ANDROID_HOME / ANDROID_SDK_ROOT。 */
    fun sdkDir(context: Context): File = File(context.filesDir, "sdk")

    /** `files/.gradle` —— GRADLE_USER_HOME。 */
    fun gradleUserHome(context: Context): File = File(context.filesDir, ".gradle")

    /** `cache/tmp` —— TMPDIR。 */
    fun tmpDir(context: Context): File = File(context.cacheDir, "tmp")

    /** `files/bin` —— PATH 首位。 */
    fun binDir(context: Context): File = File(context.filesDir, "bin")

    private fun hasBin(dir: File?, executable: String): Boolean =
        dir != null && File(dir, "bin/$executable").exists()

    // ------------------------------------------------------------------
    // 检测
    // ------------------------------------------------------------------

    /** 解析 JDK 根目录：用户配置 → `files/sdk/jdk` → sdk 下任意含 `bin/java` 的目录。 */
    suspend fun resolveJdk(context: Context): String? {
        val configured = runCatching { AppStorage.preferences.jdkPath() }.getOrDefault("")
        if (configured.isNotBlank() && hasBin(File(configured), "java")) return configured
        return scanFor(context, "java")
    }

    /** 解析 Gradle 发行版根目录：用户配置 → `files/sdk/gradle` → sdk 下任意含 `bin/gradle` 的目录。 */
    suspend fun resolveGradle(context: Context): String? {
        val configured = runCatching { AppStorage.preferences.gradlePath() }.getOrDefault("")
        if (configured.isNotBlank() && hasBin(File(configured), "gradle")) return configured
        return scanFor(context, "gradle")
    }

    private fun scanFor(context: Context, executable: String): String? {
        val sdk = sdkDir(context)
        val direct = File(sdk, if (executable == "java") "jdk" else "gradle")
        if (hasBin(direct, executable)) return direct.absolutePath
        val found = sdk.listFiles()?.firstNotNullOfOrNull { dir ->
            when {
                dir.isDirectory && hasBin(dir, executable) -> dir
                dir.isDirectory -> dir.listFiles()?.firstOrNull { inner ->
                    inner.isDirectory && hasBin(inner, executable)
                }
                else -> null
            }
        } ?: return null
        return found.absolutePath
    }

    /** SDK 是否具备构建所需结构（platforms / build-tools / platform-tools 至少其一）。 */
    fun sdkLooksReady(sdk: File): Boolean =
        listOf("platforms", "build-tools", "platform-tools").any { name ->
            val dir = File(sdk, name)
            dir.isDirectory && (dir.listFiles()?.isNotEmpty() == true)
        }

    /** 执行一次体检并缓存结果。 */
    suspend fun refresh(context: Context): EnvStatus {
        val status = status(context)
        _status.value = status
        // JAVA_HOME 同步注入进程环境，供终端 / CLI 子进程继承
        runCatching { NativeRuntime.setJavaHome(context, status.item(EnvKind.JDK)?.path) }
        return status
    }

    /** 体检：JDK / Gradle / SDK 三项的路径与可用性。 */
    suspend fun status(context: Context): EnvStatus {
        val jdk = resolveJdk(context)
        val gradle = resolveGradle(context)
        val sdk = sdkDir(context)
        val sdkReady = sdkLooksReady(sdk)
        return EnvStatus(
            items = listOf(
                EnvItem(
                    kind = EnvKind.JDK,
                    path = jdk,
                    ready = jdk != null,
                    hint = "未就绪：点击「导入 JDK zip」，选择适用于 Android（arm64/aarch64）的 JDK 压缩包",
                ),
                EnvItem(
                    kind = EnvKind.GRADLE,
                    path = gradle,
                    ready = gradle != null,
                    hint = "未就绪：点击「导入 Gradle zip」，选择 gradle-x.x-bin.zip 发行版",
                ),
                EnvItem(
                    kind = EnvKind.SDK,
                    path = if (sdk.isDirectory) sdk.absolutePath else null,
                    ready = sdkReady,
                    hint = "未就绪：SDK 结构需包含 platforms/android-xx、build-tools、platform-tools，可直接导入 SDK zip",
                ),
            ),
        )
    }

    // ------------------------------------------------------------------
    // zip 导入（离线，SAF 选中的本地文件）
    // ------------------------------------------------------------------

    /**
     * 解压用户选择的 zip 到 `files/sdk/`。
     *
     * - **zip slip 防护**：每个条目 canonical 化后必须位于目标目录内；
     * - **结构识别**：自动定位含 `bin/java`（或 `bin/gradle`、SDK 结构）的根目录；
     * - **幂等**：重复导入直接覆盖旧目录；
     * - **权限修复**：zip 不保留 Unix 权限位，导入后对 `bin` 目录下的文件执行 setExecutable。
     *
     * @param onProgress 解压进度 0..1（按字节数）
     * @return 最终落地目录绝对路径
     */
    suspend fun importZip(
        context: Context,
        uri: Uri,
        kind: EnvKind,
        onProgress: (Float) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val sdk = sdkDir(context)
        if (!sdk.exists() && !sdk.mkdirs()) {
            throw IllegalStateException("无法创建目录：${sdk.absolutePath}")
        }
        val totalBytes = totalSize(context, uri).coerceAtLeast(1L)
        val tmp = File(sdk, ".import_${kind.name.lowercase(Locale.ROOT)}_${System.nanoTime()}")
        if (!tmp.mkdirs() && !tmp.isDirectory) {
            throw IllegalStateException("无法创建临时目录：${tmp.absolutePath}")
        }
        try {
            var readBytes = 0L
            var entries = 0
            val raw = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("无法读取所选文件，请重新选择")
            raw.use { input ->
                ZipInputStream(BufferedInputStream(input)).use { zis ->
                    val root = tmp.canonicalFile
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val target = File(root, entry.name).canonicalFile
                        if (!target.path.startsWith(root.path + File.separator)) {
                            throw SecurityException("压缩包内含非法路径（zip slip），已中止导入：${entry.name}")
                        }
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { out -> readBytes += zis.copyTo(out) }
                            entries++
                            if (entries % 8 == 0) {
                                onProgress((readBytes.toFloat() / totalBytes).coerceIn(0f, 0.99f))
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            if (entries == 0) throw IllegalStateException("压缩包为空或不是有效的 zip 文件")
            onProgress(1f)

            val target = when (kind) {
                EnvKind.JDK -> relocate(tmp, "bin/java", File(sdk, "jdk"))
                EnvKind.GRADLE -> relocate(tmp, "bin/gradle", File(sdk, "gradle"))
                EnvKind.SDK -> mergeIntoSdk(tmp, sdk)
            }

            // 修复可执行权限（gradlew / java / gradle 都是脚本或 ELF）
            fixExecutable(File(target, "bin"))

            when (kind) {
                EnvKind.JDK -> {
                    runCatching { AppStorage.preferences.setJdkPath(target) }
                    runCatching { NativeRuntime.setJavaHome(context, target) }
                }
                EnvKind.GRADLE -> runCatching { AppStorage.preferences.setGradlePath(target) }
                EnvKind.SDK -> Unit
            }
            refresh(context)
            target
        } finally {
            runCatching { tmp.deleteRecursively() }
        }
    }

    /** 找到含 [marker]（相对路径）的目录并整体移动到 [dest]（旧目录先删除，保证幂等）。 */
    private fun relocate(tmp: File, marker: String, dest: File): String {
        val root = findContaining(tmp, marker, 4)
            ?: throw IllegalStateException("压缩包结构不匹配：未找到 $marker，请确认选择的是正确的 zip")
        if (root.canonicalPath == dest.canonicalPath) return dest.absolutePath
        if (dest.exists()) dest.deleteRecursively()
        dest.parentFile?.mkdirs()
        if (!root.renameTo(dest)) {
            root.copyRecursively(dest, overwrite = true)
        }
        return dest.absolutePath
    }

    /** SDK zip：把顶层内容合并进 `files/sdk`（兼容单层 `android-sdk/` 包裹目录）。 */
    private fun mergeIntoSdk(tmp: File, sdk: File): String {
        var source = tmp
        val children = tmp.listFiles()?.filter { !it.name.startsWith(".") }.orEmpty()
        val sdkMarkers = listOf("platforms", "build-tools", "platform-tools")
        if (children.size == 1 && children[0].isDirectory) {
            val inner = children[0]
            if (sdkMarkers.any { File(inner, it).isDirectory } || hasBin(inner, "java")) {
                source = inner
            }
        }
        source.listFiles()?.forEach { child ->
            val dest = File(sdk, child.name)
            if (child.isDirectory) {
                if (!child.renameTo(dest)) {
                    child.copyRecursively(dest, overwrite = true)
                }
            } else if (!dest.exists()) {
                child.copyTo(dest, overwrite = true)
            }
        }
        if (!sdkLooksReady(sdk)) {
            throw IllegalStateException("SDK 结构不完整：缺少 platforms / build-tools，请导入正确的 Android SDK 压缩包")
        }
        return sdk.absolutePath
    }

    /** 深度优先查找包含 [marker] 的目录（depth ≤ [maxDepth]）。 */
    private fun findContaining(dir: File, marker: String, maxDepth: Int): File? {
        if (maxDepth < 0) return null
        if (File(dir, marker).exists()) return dir
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                findContaining(child, marker, maxDepth - 1)?.let { return it }
            }
        }
        return null
    }

    /** 对目录下所有普通文件授予可执行权限（zip 不保留权限位）。 */
    private fun fixExecutable(bin: File) {
        if (!bin.isDirectory) return
        bin.walkTopDown().forEach { file ->
            if (file.isFile) runCatching { file.setExecutable(true, false) }
        }
    }

    private fun totalSize(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
    }.getOrDefault(0L)

    // ------------------------------------------------------------------
    // 环境体检输出（「环境体检」按钮 / `opencode doctor` 风格）
    // ------------------------------------------------------------------

    /** 打印路径与可用性明细（逐行返回，由调用方写入日志流）。 */
    fun healthLines(context: Context, status: EnvStatus?): List<String> {
        val sdk = sdkDir(context)
        val jdk = status?.item(EnvKind.JDK)
        val gradle = status?.item(EnvKind.GRADLE)
        val sdkItem = status?.item(EnvKind.SDK)
        val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        return buildList {
            add("—— 环境体检 $time ——")
            add("  files         ${context.filesDir.absolutePath}")
            add("  JDK           ${mark(jdk)} ${jdk?.path ?: "未导入"}")
            add("                bin/java = ${if (hasBin(dirOf(jdk), "java")) "存在" else "缺失"}")
            add("  Gradle        ${mark(gradle)} ${gradle?.path ?: "未导入"}")
            add("                bin/gradle = ${if (hasBin(dirOf(gradle), "gradle")) "存在" else "缺失"}")
            add("  Android SDK   ${mark(sdkItem)} ${sdk.absolutePath}")
            add("                结构检查 = ${if (sdkLooksReady(sdk)) "通过（platforms/build-tools）" else "缺失 platforms/build-tools"}")
            add("  ANDROID_HOME  ${sdk.absolutePath}")
            add("  GRADLE_USER_HOME ${gradleUserHome(context).absolutePath}")
            add("  TMPDIR        ${tmpDir(context).absolutePath}")
            if (status?.ready == true) {
                add("结论：构建环境已就绪，可以开始构建")
            } else {
                add("结论：构建环境未就绪，请在本页导入缺失组件（全部本地 zip，不联网）")
            }
        }
    }

    private fun mark(item: EnvItem?): String = if (item?.ready == true) "[就绪]" else "[缺失]"

    private fun dirOf(item: EnvItem?): File? = item?.path?.let { File(it) }
}
