package com.mobilecoder.ide.feature.ai

import com.mobilecoder.ide.core.common.cli.CliCommand
import com.mobilecoder.ide.core.common.cli.CliEngine
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `run_command` / `git_status` / `git_diff` / `git_log` 四个新工具（Issue 6）。
 *
 * 只验证**不触碰 Android 运行时**的部分：schema、确认/快照分类、危险命令拦截、
 * 命令组装，以及「进程内 git 走 CliEngine」这条链路（注册替身命令模拟 feature_git）。
 */
class AiToolsCommandTest {

    private val root: File = Files.createTempDirectory("ai-tools").toFile().apply { mkdirs() }

    private fun schemas(): List<String> {
        val array = AiTools.schemas()
        return (0 until array.length()).map { array.getJSONObject(it).getJSONObject("function").getString("name") }
    }

    private fun args(json: String) = JSONObject(json)

    // ---------------- schema ----------------

    @Test
    fun `新增工具都在 schema 里且参数齐全`() {
        val names = schemas()
        for (tool in listOf("run_command", "git_status", "git_diff", "git_log")) {
            assertTrue("$tool 应出现在 schema：$names", tool in names)
        }
        assertTrue(
            "原 5 个文件工具仍在",
            listOf("list_files", "read_file", "write_file", "search_replace", "delete_path")
                .all { it in names },
        )

        val runCommand = AiTools.schemas().let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).getJSONObject("function") }
                .first { it.getString("name") == "run_command" }
        }
        val params = runCommand.getJSONObject("parameters")
        val required = params.getJSONArray("required")
        assertEquals(listOf("command"), (0 until required.length()).map(required::getString))
        assertTrue(params.getJSONObject("properties").has("cwd"))
        assertTrue(params.getJSONObject("properties").has("timeout"))
    }

    // ---------------- 分类（确认 / 快照 / 可点开） ----------------

    @Test
    fun `run_command 恒需确认且不做文件快照——git 工具只读`() {
        assertTrue(AiTools.needsConfirm("run_command"))
        assertTrue(AiTools.needsConfirm("delete_path"))
        assertFalse("命令不可快照，不能算写类工具", AiTools.isMutating("run_command"))
        assertFalse(AiTools.needsConfirm("git_status"))
        assertFalse(AiTools.isMutating("git_diff"))
        assertFalse(AiTools.isMutating("git_log"))
        assertFalse("命令/结果不是文件路径，不该点开编辑器", AiTools.opensFile("run_command"))
        assertFalse(AiTools.opensFile("git_log"))
        assertTrue(AiTools.opensFile("read_file"))
        assertEquals("执行", AiTools.displayName("run_command"))
        assertEquals("查看", AiTools.displayName("git_status"))
    }

    @Test
    fun `确认弹窗与事件行的目标文本`() {
        assertEquals("./gradlew test", AiTools.targetOf("run_command", """{"command":"./gradlew test"}"""))
        assertEquals("git status", AiTools.targetOf("git_status", "{}"))
        assertEquals("git status --short", AiTools.targetOf("git_status", """{"short":true}"""))
        assertEquals("git diff --staged", AiTools.targetOf("git_diff", """{"staged":true}"""))
        assertEquals("git log -n 5", AiTools.targetOf("git_log", """{"limit":5}"""))
        assertEquals("app/build.gradle.kts", AiTools.targetOf("write_file", """{"path":"app/build.gradle.kts"}"""))
        // 非法 JSON 不抛异常
        assertEquals("", AiTools.targetOf("run_command", "not json"))
    }

    @Test
    fun `git 命令行按参数组装`() {
        assertEquals("git status", AiTools.gitLine("git_status", args("{}")))
        assertEquals("git status --short", AiTools.gitLine("git_status", args("""{"short":true}""")))
        assertEquals("git diff", AiTools.gitLine("git_diff", args("{}")))
        assertEquals("git diff --staged --stat", AiTools.gitLine("git_diff", args("""{"staged":true,"stat":true}""")))
        assertEquals("git log -n 20", AiTools.gitLine("git_log", args("{}")))
        assertEquals("git log -n 200", AiTools.gitLine("git_log", args("""{"limit":9999}""")))
    }

    // ---------------- 危险命令拦截 ----------------

    @Test
    fun `危险命令被拦截且不进入执行通道`() = runBlocking {
        for (command in listOf("rm -rf /", "shutdown now", "git reset --hard", "echo x > /etc/passwd")) {
            val result = AiTools.execute("run_command", """{"command":"$command"}""", root)
            assertFalse("应拦截：$command", result.ok)
            assertTrue(result.summary.contains("拦截"))
            assertTrue(result.content.contains("拦截"))
        }
    }

    @Test
    fun `cwd 超出项目根被拒绝`() = runBlocking {
        val result = AiTools.execute(
            "run_command",
            """{"command":"ls","cwd":"../.."}""",
            root,
        )
        assertFalse(result.ok)
        assertTrue(result.summary.contains("超出项目根"))
    }

    @Test
    fun `缺少 command 参数被拒绝`() = runBlocking {
        val result = AiTools.execute("run_command", "{}", root)
        assertFalse(result.ok)
        assertTrue(result.summary.contains("缺少 command"))
    }

    @Test
    fun `安全命令在执行环境未初始化时给出可读失败`() = runBlocking {
        // JVM 单测没有 Android Context：不能真的起子进程，但失败信息必须明确可读
        val result = AiTools.execute("run_command", """{"command":"echo hi"}""", root)
        assertFalse(result.ok)
        assertTrue("失败信息应说明执行环境问题，实际：${result.content}", result.content.contains("命令执行环境未初始化"))
    }

    // ---------------- 只读 git 工具走进程内命令引擎 ----------------

    @Test
    fun `git 工具经命令引擎执行——与终端共用锁`() = runBlocking {
        CliEngine.register(
            CliCommand(name = "git", terminalIntercept = true) { commandArgs, _, emit ->
                emit("On branch main")
                emit("args=" + commandArgs.joinToString(" "))
                0
            },
        )
        val status = AiTools.execute("git_status", "{}", root)
        assertTrue(status.ok)
        assertEquals("git status", status.target)
        assertTrue(status.content.contains("On branch main"))
        assertTrue(status.content.contains("args=status"))

        val diff = AiTools.execute("git_diff", """{"staged":true}""", root)
        assertTrue(diff.ok)
        assertTrue(diff.content.contains("args=diff --staged"))

        val log = AiTools.execute("git_log", """{"limit":3}""", root)
        assertTrue(log.ok)
        assertTrue(log.content.contains("args=log -n 3"))
    }

    @Test
    fun `git 命令失败时回传退出码与错误输出`() = runBlocking {
        CliEngine.register(
            CliCommand(name = "git", terminalIntercept = true) { _, _, emit ->
                emit("fatal: not a git repository")
                128
            },
        )
        val result = AiTools.execute("git_status", "{}", root)
        assertFalse(result.ok)
        assertTrue(result.summary.contains("128"))
        assertTrue(result.content.contains("退出码 128"))
        assertTrue(result.content.contains("not a git repository"))
    }
}
