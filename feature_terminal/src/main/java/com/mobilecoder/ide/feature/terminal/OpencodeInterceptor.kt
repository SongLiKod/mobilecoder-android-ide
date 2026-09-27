package com.mobilecoder.ide.feature.terminal

/**
 * `opencode` 命令拦截器（PRD 2.4：终端手动输入 opencode 命令）。
 *
 * 策略：**前缀缓冲 + 本地回显 + 回滚**，既能在终端里执行 `opencode …`，
 * 又不破坏普通交互程序（vim、密码输入、方向键等）的直通输入。
 *
 * 状态机：
 *  - [Mode.IDLE]      pending 为空，等待下一行的首个字符；
 *  - [Mode.PREFIX]    已缓冲的字符仍是 "opencode" 的前缀（本地回显、不发给 PTY）；
 *  - [Mode.INTERCEPT] 已完整输入 "opencode"，本行其余字符全部暂存（拦截模式）；
 *  - [Mode.PASSTHROUGH] 曾出现前缀失配，整行直通到下一次 Enter。
 *
 * 所有回调都在主线程调用（由 [TerminalSession] 保证），因此本地回显与回滚
 * 与屏幕缓冲严格同步。
 */
class OpencodeInterceptor(
    /** 写给 PTY（shell / 交互程序）的字节。 */
    private val onWritePty: (ByteArray) -> Unit,
    /** 本地回显：把字符直接画进屏幕缓冲。 */
    private val onLocalEcho: (String) -> Unit,
    /** 回滚最近 n 个本地回显字符（含自动换行场景）。 */
    private val onRollback: (Int) -> Unit,
) {

    enum class Mode { IDLE, PREFIX, INTERCEPT, PASSTHROUGH }

    private companion object {
        const val TARGET = "opencode"
        val BYTE_BACKSPACE = byteArrayOf(0x7F)
        val BYTE_CR = byteArrayOf(0x0D)
    }

    var mode: Mode = Mode.IDLE
        private set

    /** 当前缓冲（PREFIX：前缀；INTERCEPT：整行命令）。 */
    private val line = StringBuilder()

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
                    // 规则 a：仅当首字符是 'o' 才进入暂存
                    if (c == 'o') {
                        mode = Mode.PREFIX
                        line.append(c)
                        onLocalEcho("o")
                        i++
                    } else {
                        // 规则 f 的对偶：本行不再拦截，直通直到 Enter
                        mode = Mode.PASSTHROUGH
                        onWritePty(text.substring(i).toByteArray())
                        return
                    }
                }

                Mode.PREFIX -> {
                    val candidate = line.toString() + c
                    if (TARGET.startsWith(candidate)) {
                        // 规则 b：仍是 "opencode" 的前缀 → 继续暂存并本地回显
                        line.append(c)
                        onLocalEcho(c.toString())
                        if (line.length == TARGET.length) mode = Mode.INTERCEPT
                        i++
                    } else {
                        // 规则 c：回滚本地回显后，pending + 新输入一次性交给 PTY
                        onRollback(line.length)
                        val flush = line.toString() + text.substring(i)
                        line.setLength(0)
                        mode = Mode.PASSTHROUGH
                        onWritePty(flush.toByteArray())
                        return
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

    /**
     * 处理回车。
     *
     * @return 拦截到的完整命令行（`opencode …`，由调用方交给 OpencodeCli 执行）；
     *         直通模式下返回 null 且已向 PTY 写入 `"\r"`。
     */
    fun enter(): String? {
        return when (mode) {
            Mode.INTERCEPT -> {
                val intercepted = line.toString()
                line.setLength(0)
                mode = Mode.IDLE
                intercepted
            }

            Mode.PREFIX -> {
                // 输入的是 "open…" 之类的非目标命令：回滚回显后交给 shell
                onRollback(line.length)
                val flush = line.toString()
                line.setLength(0)
                mode = Mode.IDLE
                onWritePty(flush.toByteArray() + BYTE_CR)
                null
            }

            else -> {
                line.setLength(0)
                mode = Mode.IDLE
                onWritePty(BYTE_CR)
                null
            }
        }
    }

    /**
     * Ctrl+C（0x03）：清空 pending、回滚本地回显、恢复正常直通。
     * 调用方随后会把 0x03 写给 PTY。
     */
    fun interrupt() {
        if (line.isNotEmpty()) onRollback(line.length)
        line.setLength(0)
        mode = Mode.IDLE
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
                    if (line.isEmpty()) mode = Mode.IDLE
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
        mode = Mode.IDLE
    }
}
