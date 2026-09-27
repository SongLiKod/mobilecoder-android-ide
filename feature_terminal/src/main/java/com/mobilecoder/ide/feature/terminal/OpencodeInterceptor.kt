package com.mobilecoder.ide.feature.terminal

/**
 * 终端命令拦截器（PRD 2.4：终端手动输入 `opencode` / `git` 等进程内命令）。
 *
 * Android 设备上没有 `git` 可执行文件（也没有 `opencode`），这些命令由进程内实现接管；
 * 其余命令一律直通给 mksh。策略：**前缀缓冲 + 本地回显 + 回滚**，既能在终端里执行
 * 目标命令，又不破坏普通交互程序（vim、密码输入、方向键等）的直通输入。
 *
 * 目标集合由 [targets] 动态提供（来自 OpencodeCli 的注册表），当前为
 * `opencode` 与 `git`。
 *
 * 状态机：
 *  - [Mode.IDLE]      pending 为空，等待下一行的首个字符；
 *  - [Mode.PREFIX]    已缓冲的字符仍是某个目标命令的前缀（本地回显、不发给 PTY）；
 *  - [Mode.INTERCEPT] 已完整匹配某个目标命令，本行其余字符全部暂存（拦截模式）；
 *  - [Mode.PASSTHROUGH] 曾出现前缀失配，整行直通到下一次 Enter。
 *
 * 所有回调都在主线程调用（由 [TerminalSession] 保证），因此本地回显与回滚
 * 与屏幕缓冲严格同步。
 */
class OpencodeInterceptor(
    /** 当前可拦截的命令前缀（如 `opencode`、`git`），每次判断时动态求值。 */
    private val targets: () -> List<String> = { listOf("opencode") },
    /** 写给 PTY（shell / 交互程序）的字节。 */
    private val onWritePty: (ByteArray) -> Unit,
    /** 本地回显：把字符直接画进屏幕缓冲。 */
    private val onLocalEcho: (String) -> Unit,
    /** 回滚最近 n 个本地回显字符（含自动换行场景）。 */
    private val onRollback: (Int) -> Unit,
) {

    enum class Mode { IDLE, PREFIX, INTERCEPT, PASSTHROUGH }

    private companion object {
        val BYTE_BACKSPACE = byteArrayOf(0x7F)
        val BYTE_CR = byteArrayOf(0x0D)
    }

    var mode: Mode = Mode.IDLE
        private set

    /** 当前缓冲（PREFIX：前缀；INTERCEPT：整行命令）。 */
    private val line = StringBuilder()

    /** 仍可能匹配的目标命令（PREFIX 态）。 */
    private var candidates: List<String> = emptyList()

    /** INTERCEPT 态下已完整匹配的目标命令（Enter 时校验词边界用）。 */
    private var matched: String? = null

    /** 是否处于拦截（本地回显）状态。 */
    val isIntercepting: Boolean
        get() = mode == Mode.PREFIX || mode == Mode.INTERCEPT

    /**
     * 处理一段用户输入（不含回车）。
     *
     * 规则见类文档；失配时先回滚本地回显，再把 pending + 新输入一次性写给 PTY。
     */
    fun feed(text: String) {
        if (text.isEmpty()) return
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when (mode) {
                Mode.IDLE -> {
                    // 规则 a：首字符必须是某个目标命令的首字符，否则整行直通
                    val seed = targets().filter { it.startsWith(c) }
                    if (seed.isEmpty()) {
                        mode = Mode.PASSTHROUGH
                        onWritePty(text.substring(i).toByteArray())
                        return
                    }
                    candidates = seed
                    line.append(c)
                    onLocalEcho(c.toString())
                    markMatchedIfComplete()
                    i++
                }

                Mode.PREFIX -> {
                    line.append(c)
                    onLocalEcho(c.toString())
                    val buffered = line.toString()
                    candidates = candidates.filter { it.startsWith(buffered) }
                    when {
                        // 规则 b：完整命中某个目标命令 → 进入拦截模式
                        targets().any { it == buffered } -> {
                            matched = buffered
                            mode = Mode.INTERCEPT
                            candidates = emptyList()
                            i++
                        }

                        // 规则 c：已不可能命中 → 回滚本地回显，pending + 新输入一次性交给 PTY
                        candidates.isEmpty() -> {
                            onRollback(line.length)
                            val flush = line.toString() + text.substring(i + 1)
                            line.setLength(0)
                            candidates = emptyList()
                            mode = Mode.PASSTHROUGH
                            onWritePty(flush.toByteArray())
                            return
                        }

                        // 仍是某个目标的前缀 → 继续暂存并本地回显
                        else -> i++
                    }
                }

                Mode.INTERCEPT -> {
                    // 规则 d：整行剩余字符全部暂存
                    line.append(c)
                    onLocalEcho(c.toString())
                    i++
                }

                Mode.PASSTHROUGH -> {
                    onWritePty(text.substring(i).toByteArray())
                    return
                }
            }
        }
    }

    /** 缓冲内容若已完整等于某个目标命令 → 进入拦截态（IDLE 首字符即完整命中时用）。 */
    private fun markMatchedIfComplete() {
        val buffered = line.toString()
        candidates = candidates.filter { it.startsWith(buffered) }
        if (targets().any { it == buffered }) {
            matched = buffered
            mode = Mode.INTERCEPT
            candidates = emptyList()
        }
    }

    /**
     * 处理回车。
     *
     * @return 拦截到的完整命令行（如 `git status`，由调用方交给 CLI 引擎执行）；
     *         直通模式下返回 null 且已向 PTY 写入 `"\r"`。
     */
    fun enter(): String? {
        when (mode) {
            Mode.INTERCEPT -> {
                val text = line.toString()
                val target = matched.orEmpty()
                // 词边界校验：`gitx foo` 这类同前缀命令不属于拦截目标；
                // 拦截态退格把缓冲删到目标之前时（如 "gi"），同样交回 shell
                val hit = when {
                    text == target -> true
                    text.length > target.length && text.startsWith(target) ->
                        text[target.length].isWhitespace()

                    else -> false
                }
                val echoed = text.length
                reset()
                if (hit) return text
                onRollback(echoed)
                onWritePty(text.toByteArray() + BYTE_CR)
                return null
            }

            Mode.PREFIX -> {
                // 输入的是 "ope…" 之类的非目标命令：回滚回显后交给 shell
                val flush = line.toString()
                val echoed = line.length
                reset()
                onRollback(echoed)
                onWritePty(flush.toByteArray() + BYTE_CR)
                return null
            }

            else -> {
                reset()
                onWritePty(BYTE_CR)
                return null
            }
        }
    }

    /**
     * Ctrl+C（0x03）：清空 pending、回滚本地回显、恢复正常直通。
     * 调用方随后会把 0x03 写给 PTY。
     */
    fun interrupt() {
        if (line.isNotEmpty()) onRollback(line.length)
        reset()
    }

    /** 退格：拦截态本地删除；直通态向 PTY 发送 0x7f。 */
    fun backspace() {
        when (mode) {
            Mode.INTERCEPT -> {
                if (line.isNotEmpty()) {
                    line.deleteCharAt(line.length - 1)
                    onRollback(1)
                }
            }

            Mode.PREFIX -> {
                if (line.isNotEmpty()) {
                    line.deleteCharAt(line.length - 1)
                    onRollback(1)
                    if (line.isEmpty()) {
                        candidates = emptyList()
                        mode = Mode.IDLE
                    }
                } else {
                    onWritePty(BYTE_BACKSPACE)
                }
            }

            else -> onWritePty(BYTE_BACKSPACE)
        }
    }

    /** 强制复位（会话重启 / 清屏时调用）。 */
    fun reset() {
        line.setLength(0)
        candidates = emptyList()
        matched = null
        mode = Mode.IDLE
    }
}
