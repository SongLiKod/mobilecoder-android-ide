package com.mobilecoder.ide.feature.build

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `opencode tools` 通用安装的纯逻辑：
 * 简写 → npm 包名解析、包名/关键字合法性（防旗标注入）、
 * `files/lib/node_modules` 全局包扫描（含 `@scope` 与版本解析）。
 */
class ToolInstallerPkgTest {

    /* ---------------- resolvePackage / validToken ---------------- */

    @Test
    fun `常用简写解析为 npm 包名且大小写不敏感`() {
        assertEquals("@google/gemini-cli", ToolInstaller.resolvePackage("gemini"))
        assertEquals("@openai/codex", ToolInstaller.resolvePackage("CODEX"))
        assertEquals("@anthropic-ai/claude-code", ToolInstaller.resolvePackage("claude"))
        assertEquals("qwen-code", ToolInstaller.resolvePackage("qwen"))
        assertEquals("opencode-ai", ToolInstaller.resolvePackage("opencode"))
    }

    @Test
    fun `完整包名原样透传`() {
        assertEquals("typescript", ToolInstaller.resolvePackage("typescript"))
        assertEquals("@vue/cli", ToolInstaller.resolvePackage("@vue/cli"))
        assertEquals("pkg@1.2.3", ToolInstaller.resolvePackage("pkg@1.2.3"))
        assertEquals("pkg@latest", ToolInstaller.resolvePackage("pkg@latest"))
    }

    @Test
    fun `非法输入返回 null（旗标注入与危险字符）`() {
        assertNull(ToolInstaller.resolvePackage("--registry=evil"))
        assertNull(ToolInstaller.resolvePackage("-g"))
        assertNull(ToolInstaller.resolvePackage("a b"))
        assertNull(ToolInstaller.resolvePackage("a;rm -rf /"))
        assertNull(ToolInstaller.resolvePackage(""))
        assertNull(ToolInstaller.resolvePackage("\${HOME}"))
    }

    @Test
    fun `validToken 只放行白名单字符且不以横线开头`() {
        assertTrue(ToolInstaller.validToken("@scope/name-x"))
        assertTrue(ToolInstaller.validToken("node.js"))
        assertFalse(ToolInstaller.validToken("x|y"))
        assertFalse(ToolInstaller.validToken("-h"))
        assertFalse(ToolInstaller.validToken(""))
    }

    /* ---------------- installedPackages ---------------- */

    @Test
    fun `扫描全局包目录含 scope 与版本`() {
        val nm = Files.createTempDirectory("nm").toFile()
        try {
            writePkg(nm, "typescript", "5.4.5")
            writePkg(nm, "@anthropic-ai/claude-code", "2.1.0")
            // 无 package.json → 回落目录名；隐藏目录（.bin 等）跳过
            File(nm, "mystery").mkdirs()
            File(nm, ".bin").mkdirs()

            assertEquals(
                listOf("@anthropic-ai/claude-code@2.1.0", "mystery", "typescript@5.4.5"),
                ToolInstaller.installedPackages(nm),
            )
        } finally {
            nm.deleteRecursively()
        }
    }

    @Test
    fun `目录不存在或为空时返回空列表`() {
        val missing = File(
            System.getProperty("java.io.tmpdir"),
            "nm-missing-${System.nanoTime()}",
        )
        assertEquals(emptyList<String>(), ToolInstaller.installedPackages(missing))
        val empty = Files.createTempDirectory("nm-empty").toFile()
        try {
            assertEquals(emptyList<String>(), ToolInstaller.installedPackages(empty))
        } finally {
            empty.deleteRecursively()
        }
    }

    private fun writePkg(root: File, name: String, version: String) {
        val dir = File(root, name)
        dir.mkdirs()
        File(dir, "package.json").writeText("""{"name":"$name","version":"$version"}""")
    }
}
