package com.mobilecoder.ide.feature.build

import android.content.Context
import android.net.Uri
import com.mobilecoder.ide.core.nativebridge.NativeRuntime
import com.mobilecoder.ide.core.storage.AppStorage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    NODE("Node.js"),
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
data class EnvStatus(
    /** 当前项目**需要**的组件（按项目类型动态推断，未识别的工程按安卓处理）。 */
    val items: List<EnvItem>,
    val required: List<EnvKind> = items.map { it.kind },
) {

    /** 所需组件全部就绪才算构建环境可用。 */
    val ready: Boolean get() = items.isNotEmpty() && items.all { it.ready }

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

    /** 执行一次体检并缓存结果（[projectDir] 用于按项目类型动态过滤所需组件）。 */
    suspend fun refresh(context: Context, projectDir: File? = null): EnvStatus {
        val all = detect(context)
        val required = requirementsFor(projectDir)
        val status = EnvStatus(items = all.filter { it.kind in required }, required = required)
        _status.value = status
        // JAVA_HOME 同步注入进程环境，供终端 / CLI 子进程继承（与项目类型无关）
        runCatching {
            NativeRuntime.setJavaHome(context, all.firstOrNull { it.kind == EnvKind.JDK }?.path)
        }
        return status
    }

    /**
     * 按当前项目类型推断**需要**哪些环境（「构建环境」页据此动态展示与检查）。
     *
     * - 安卓 Gradle 工程 → JDK + Gradle + Android SDK
     * - 纯 JVM/Kotlin Gradle 工程 → JDK + Gradle
     * - Node/Vue 工程（package.json）→ Node.js
     * - 未识别 → 按安卓工程处理
     */
    fun requirementsFor(projectDir: File?): List<EnvKind> {
        if (projectDir == null) return listOf(EnvKind.JDK, EnvKind.GRADLE, EnvKind.SDK)
        val hasGradle = File(projectDir, "gradlew").exists() || listOf(
            "settings.gradle",
            "settings.gradle.kts",
            "build.gradle",
            "build.gradle.kts",
        ).any { File(projectDir, it).exists() }
        return when {
            hasGradle && isAndroidProject(projectDir) -> listOf(EnvKind.JDK, EnvKind.GRADLE, EnvKind.SDK)
            hasGradle -> listOf(EnvKind.JDK, EnvKind.GRADLE)
            File(projectDir, "package.json").exists() -> listOf(EnvKind.NODE)
            else -> listOf(EnvKind.JDK, EnvKind.GRADLE, EnvKind.SDK)
        }
    }

    /** 是否安卓工程：存在 AndroidManifest，或构建脚本引用了 com.android 插件。 */
    fun isAndroidProject(root: File): Boolean {
        if (File(root, "app/src/main/AndroidManifest.xml").exists()) return true
        if (File(root, "src/main/AndroidManifest.xml").exists()) return true
        return listOf("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts")
            .mapNotNull { name -> File(root, name).takeIf { it.exists() } }
            .any { file -> runCatching { file.readText() }.getOrDefault("").contains("com.android") }
    }

    /** 体检：按 [projectDir] 所需组件返回路径与可用性。 */
    suspend fun status(context: Context, projectDir: File? = null): EnvStatus {
        val all = detect(context)
        val required = requirementsFor(projectDir)
        return EnvStatus(items = all.filter { it.kind in required }, required = required)
    }

    /** 全量检测（不做项目过滤）。 */
    private suspend fun detect(context: Context): List<EnvItem> {
        val jdk = resolveJdk(context)
        val gradle = resolveGradle(context)
        val node = resolveNode(context)
        val sdk = sdkDir(context)
        val sdkReady = sdkLooksReady(sdk)
        return listOf(
            EnvItem(
                kind = EnvKind.JDK,
                path = jdk,
                ready = jdk != null,
                hint = "未就绪：点「在线下载」安装 Temurin JDK 17（约 190MB），或导入本地 JDK 压缩包（zip / tar.gz）",
            ),
            EnvItem(
                kind = EnvKind.GRADLE,
                path = gradle,
                ready = gradle != null,
                hint = "未就绪：点「在线下载」安装 Gradle 8.9（约 130MB），或导入 gradle-8.9-bin.zip",
            ),
            EnvItem(
                kind = EnvKind.SDK,
                path = if (sdk.isDirectory) sdk.absolutePath else null,
                ready = sdkReady,
                hint = "未就绪：点「在线安装 SDK」将下载 cmdline-tools 并自动装好 platform-tools / android-35 / build-tools-35，也可导入完整 SDK zip",
            ),
            EnvItem(
                kind = EnvKind.NODE,
                path = node,
                ready = node != null,
                hint = "未就绪：点「在线下载」安装 Node.js 20（含 npm，约 50MB），或导入 node-v*-linux-*.tar.gz",
            ),
        )
    }

    // ------------------------------------------------------------------
    // 导入与落位（本地 SAF 文件 / 在线下载共用）
    // ------------------------------------------------------------------

    /**
     * 解压用户选择的本地压缩包（zip / tar.gz，按流首部自动嗅探）到 `files/sdk/`。
     *
     * - **路径穿越防护**：条目 canonical 化后必须位于目标目录内；
     * - **结构识别**：自动定位含 `bin/java`（或 `bin/gradle`、`bin/node`、SDK 结构）的根目录；
     * - **幂等**：重复导入直接覆盖旧目录；
     * - **权限修复**：压缩包不保留 Unix 权限位，导入后对 `bin` 目录下的文件执行 setExecutable。
     *
     * @param onProgress 解压进度 0..1（按字节数）
     * @return 最终落地目录绝对路径
     */
    suspend fun importArchive(
        context: Context,
        uri: Uri,
        kind: EnvKind,
        onProgress: (Float) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val sdk = sdkDir(context)
        if (!sdk.exists() && !sdk.mkdirs()) {
            throw IllegalStateException("无法创建目录：${sdk.absolutePath}")
        }
        val tmp = File(sdk, ".import_${kind.name.lowercase(Locale.ROOT)}_${System.nanoTime()}")
        if (!tmp.mkdirs() && !tmp.isDirectory) {
            throw IllegalStateException("无法创建临时目录：${tmp.absolutePath}")
        }
        try {
            val totalBytes = totalSize(context, uri).coerceAtLeast(1L)
            val raw = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("无法读取所选文件，请重新选择")
            raw.use { input -> ArchiveExtractor.extract(input, tmp, totalBytes, onProgress) }
            onProgress(1f)
            placeExtracted(context, kind, tmp)
        } finally {
            runCatching { tmp.deleteRecursively() }
        }
    }

    /**
     * 解压产物落位：结构识别 → 移入 files/sdk → 修复可执行权限 → 写偏好 → 刷新体检。
     *
     * @param requireReady SDK 合并后是否强制要求结构完整（在线下载 cmdline-tools 时传 false，
     *                     待 sdkmanager 装完平台组件后自然满足）
     * @return 最终落地目录绝对路径
     */
    internal suspend fun placeExtracted(
        context: Context,
        kind: EnvKind,
        tmp: File,
        requireReady: Boolean = true,
    ): String {
        val sdk = sdkDir(context)
        val target = when (kind) {
            EnvKind.JDK -> relocate(tmp, "bin/java", File(sdk, "jdk"))
            EnvKind.GRADLE -> relocate(tmp, "bin/gradle", File(sdk, "gradle"))
            EnvKind.NODE -> relocate(tmp, "bin/node", File(sdk, "node"))
            EnvKind.SDK -> mergeIntoSdk(tmp, sdk, requireReady)
        }
        // 修复可执行权限（gradlew / java / gradle / node 都是脚本或 ELF）
        fixExecutable(File(target, "bin"))
        when (kind) {
            EnvKind.JDK -> {
                runCatching { AppStorage.preferences.setJdkPath(target) }
                runCatching { NativeRuntime.setJavaHome(context, target) }
            }
            EnvKind.GRADLE -> runCatching { AppStorage.preferences.setGradlePath(target) }
            EnvKind.NODE -> writeNodeShims(context, File(target))
            EnvKind.SDK -> Unit
        }
        refresh(context)
        return target
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
    private fun mergeIntoSdk(tmp: File, sdk: File, requireReady: Boolean = true): String {
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
        if (requireReady && !sdkLooksReady(sdk)) {
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

    /** Node 安装目录（`files/sdk/node`），以 `bin/node` 存在为准。 */
    private fun resolveNode(context: Context): String? {
        val root = File(sdkDir(context), "node")
        return if (File(root, "bin/node").exists()) root.absolutePath else null
    }

    /**
     * 在 `files/bin` 生成 node / npm / npx 命令入口（PATH 已包含 files/bin）。
     * npm 通过 node 直接执行 CLI 脚本，`npm i -g` 的全局包落在 node 目录内的 lib/node_modules。
     */
    private fun writeNodeShims(context: Context, nodeRoot: File) {
        val bin = binDir(context)
        if (!bin.isDirectory && !bin.mkdirs()) return
        val node = File(nodeRoot, "bin/node").absolutePath
        val npmCli = File(nodeRoot, "lib/node_modules/npm/bin/npm-cli.js").absolutePath
        val npxCli = File(nodeRoot, "lib/node_modules/npm/bin/npx-cli.js").absolutePath
        writeShim(File(bin, "node"), "#!/system/bin/sh\nexec \"$node\" \"\$@\"\n")
        writeShim(File(bin, "npm"), "#!/system/bin/sh\nexec \"$node\" \"$npmCli\" \"\$@\"\n")
        writeShim(File(bin, "npx"), "#!/system/bin/sh\nexec \"$node\" \"$npxCli\" \"\$@\"\n")
    }

    private fun writeShim(file: File, body: String) {
        runCatching {
            file.writeText(body)
            file.setExecutable(true, false)
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
        val node = status?.item(EnvKind.NODE)
        val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        return buildList {
            add("—— 环境体检 $time ——")
            add("  files         ${context.filesDir.absolutePath}")
            status?.required?.let {
                add("  本项目所需   ${it.joinToString(" / ") { kind -> kind.title }}")
            }
            add("  JDK           ${mark(jdk)} ${jdk?.path ?: "未安装"}")
            add("                bin/java = ${if (hasBin(dirOf(jdk), "java")) "存在" else "缺失"}")
            add("  Gradle        ${mark(gradle)} ${gradle?.path ?: "未安装"}")
            add("                bin/gradle = ${if (hasBin(dirOf(gradle), "gradle")) "存在" else "缺失"}")
            add("  Android SDK   ${mark(sdkItem)} ${sdk.absolutePath}")
            add("                结构检查 = ${if (sdkLooksReady(sdk)) "通过（platforms/build-tools）" else "缺失 platforms/build-tools"}")
            add("  Node.js       ${mark(node)} ${node?.path ?: "未安装"}")
            add("  ANDROID_HOME  ${sdk.absolutePath}")
            add("  GRADLE_USER_HOME ${gradleUserHome(context).absolutePath}")
            add("  TMPDIR        ${tmpDir(context).absolutePath}")
            if (status?.ready == true) {
                add("结论：构建环境已就绪，可以开始构建")
            } else {
                add("结论：构建环境未就绪，请在「构建环境」页在线下载或导入缺失组件")
            }
        }
    }

    private fun mark(item: EnvItem?): String = if (item?.ready == true) "[就绪]" else "[缺失]"

    private fun dirOf(item: EnvItem?): File? = item?.path?.let { File(it) }
}
