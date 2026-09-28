package com.mobilecoder.ide.feature.git

/**
 * Git Main Porcelain 日常命令参考。
 *
 * 供 Git 页提示、`git help porcelain` 与终端未知子命令提示共用。
 * [implemented] 表示本机终端（进程内 libgit2）可直接执行。
 */
object GitPorcelain {

    data class Command(
        val name: String,
        val summary: String,
        val implemented: Boolean,
        val usage: String = "git $name",
    )

    data class Group(
        val title: String,
        val commands: List<Command>,
    )

    val groups: List<Group> = listOf(
        Group(
            title = "仓库与同步",
            commands = listOf(
                Command("init", "初始化当前目录为仓库", true, "git init"),
                Command("clone", "克隆远程仓库", true, "git clone <url> [目录]"),
                Command("fetch", "抓取远程对象和引用", true, "git fetch"),
                Command("pull", "抓取并合并当前分支", true, "git pull"),
                Command("push", "推送当前分支", true, "git push"),
            ),
        ),
        Group(
            title = "工作区与提交",
            commands = listOf(
                Command("status", "工作区与暂存区状态", true, "git status [-s]"),
                Command("add", "加入暂存区", true, "git add <路径| . |-A>"),
                Command("restore", "还原工作区或取消暂存", true, "git restore [--staged] <路径>"),
                Command("reset", "重置 HEAD / 取消暂存", true, "git reset"),
                Command("commit", "提交暂存区", true, "git commit -m \"信息\""),
                Command("rm", "从工作区和索引删除文件", false, "git rm <路径>"),
                Command("mv", "移动或重命名文件", false, "git mv <源> <目标>"),
                Command("clean", "清理未跟踪文件", false, "git clean"),
                Command("stash", "暂存未提交改动", false, "git stash"),
            ),
        ),
        Group(
            title = "查看历史与差异",
            commands = listOf(
                Command("diff", "查看差异", true, "git diff [--staged] [--stat]"),
                Command("log", "提交历史", true, "git log [-n] [--oneline]"),
                Command("show", "查看对象内容", false, "git show [<提交>]"),
                Command("shortlog", "按作者汇总提交", false, "git shortlog"),
                Command("describe", "用最近标签给提交起可读名", false, "git describe"),
                Command("range-diff", "比较两段提交区间", false, "git range-diff"),
                Command("grep", "在跟踪文件中搜索", false, "git grep <模式>"),
            ),
        ),
        Group(
            title = "分支、合并与标签",
            commands = listOf(
                Command("branch", "列出 / 新建 / 删除分支", true, "git branch [-d <名>]"),
                Command("switch", "切换分支", true, "git switch <分支>"),
                Command("checkout", "切换分支或还原文件", true, "git checkout [-b] <分支>"),
                Command("merge", "合并分支", true, "git merge [--abort] <分支>"),
                Command("rebase", "变基到另一分支", false, "git rebase <分支>"),
                Command("cherry-pick", "拣选已有提交", false, "git cherry-pick <提交>"),
                Command("revert", "用新提交撤销旧提交", false, "git revert <提交>"),
                Command("tag", "列出 / 新建 / 删除标签", true, "git tag [-d <名>]"),
            ),
        ),
        Group(
            title = "补丁、子模块与工作树",
            commands = listOf(
                Command("am", "从邮箱应用补丁", false, "git am"),
                Command("format-patch", "生成补丁邮件", false, "git format-patch"),
                Command("archive", "打包某次提交的快照", false, "git archive"),
                Command("bundle", "用归档搬对象和引用", false, "git bundle"),
                Command("notes", "给提交加备注", false, "git notes"),
                Command("submodule", "子模块管理", false, "git submodule"),
                Command("worktree", "多工作树", false, "git worktree"),
                Command("sparse-checkout", "稀疏检出", false, "git sparse-checkout"),
                Command("bisect", "二分查找引入问题的提交", false, "git bisect"),
            ),
        ),
        Group(
            title = "维护",
            commands = listOf(
                Command("gc", "垃圾回收与优化", false, "git gc"),
                Command("maintenance", "仓库维护任务", false, "git maintenance"),
            ),
        ),
    )

    val all: List<Command> = groups.flatMap { it.commands }

    val implementedNames: Set<String> = all.filter { it.implemented }.map { it.name }.toSet()

    val referenceNames: Set<String> = all.filter { !it.implemented }.map { it.name }.toSet()

    fun lines(): List<String> {
        val out = ArrayList<String>(all.size + groups.size * 2 + 6)
        out += "Main Porcelain 日常命令参考"
        out += "终端可执行的命令标为 [可用]；其余为标准 git 对照。"
        out += "可视化操作见 Git 页；完整列表也可在 Git → 设置 查阅。"
        out += ""
        groups.forEach { group ->
            out += group.title
            group.commands.forEach { cmd ->
                val mark = if (cmd.implemented) "可用" else "参考"
                out += "  %-16s %-4s %s".format(cmd.name, mark, cmd.summary)
            }
            out += ""
        }
        return out
    }
}
