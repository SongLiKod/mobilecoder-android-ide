package com.mobilecoder.ide.feature.git

import com.mobilecoder.ide.core.common.cli.AptCli
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端 `git` 接管链路的注册侧验证：
 * GitCli.register()（由 GitController.init 调用）→ 拦截目标包含 `git` → 命令可被引擎找到。
 */
class GitCliRegistrationTest {

    @Test
    fun `git 注册为终端拦截命令`() {
        GitCli.register() // 幂等（同名覆盖）

        assertTrue("interceptTargets 应包含 git", AptCli.interceptTargets().contains("git"))
        assertTrue("git status 应被判定为 CLI 命令行", AptCli.isCliLine("git status"))
        assertTrue("apt 行仍应被判定为 CLI 命令行", AptCli.isCliLine("apt help"))
        assertTrue("普通 shell 命令不应被拦截", !AptCli.isCliLine("ls -la"))
        assertEquals("git", AptCli.find("git")?.name)
    }
}
