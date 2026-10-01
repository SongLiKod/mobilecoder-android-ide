package com.mobilecoder.ide.feature.git

import com.mobilecoder.ide.core.common.cli.CliEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端 `git` 接管链路的注册侧验证：
 * GitCli.register()（由 GitController.init 调用）→ 拦截目标包含 `git` → 命令可被引擎找到。
 * 同时锁定 apt CLI 的移除：`apt …` 不再被拦截，一律交回 shell。
 */
class GitCliRegistrationTest {

    @Test
    fun `git 注册为终端拦截命令`() {
        GitCli.register() // 幂等（同名覆盖）

        assertTrue("interceptTargets 应包含 git", CliEngine.interceptTargets().contains("git"))
        assertTrue("git status 应走进程内执行", CliEngine.isInProcessLine("git status"))
        assertFalse("apt 行不再拦截（apt CLI 已移除）", CliEngine.isInProcessLine("apt help"))
        assertFalse("普通 shell 命令不应被拦截", CliEngine.isInProcessLine("ls -la"))
        assertEquals("git", CliEngine.find("git")?.name)
    }
}
