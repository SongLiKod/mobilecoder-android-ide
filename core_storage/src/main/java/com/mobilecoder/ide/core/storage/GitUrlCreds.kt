package com.mobilecoder.ide.core.storage

/**
 * 远程地址里的凭据解析（纯函数、无副作用，JVM 单测可直接覆盖）。
 *
 * 支持 `协议://用户名:密码@主机[:端口]/路径` 形式；语义与 libgit2 保持一致
 * （third_party/libgit2/src/util/net.c / url_parse_finalize）：
 *  - 用户名与密码都做 `%XX` 百分号解码（`+` 不当作空格）；
 *  - `协议://用户名@主机/…`（只写用户名、没写密码）同样算写了凭据；
 *  - 取**最后一个** `@` 作为分隔（密码里可直接含 `@`，也可写成 `%40`）。
 *
 * 用法：克隆前用 [parse] 把凭据摘出来存进凭据库，把 [Parsed.cleanUrl]
 * 交给 libgit2，避免账号密码明文写进 `.git/config`。
 */
object GitUrlCreds {

    /** 解析结果：剥掉凭据后的地址 + 凭据 + origin。 */
    data class Parsed(
        val cleanUrl: String,
        val username: String,
        val password: String,
        val origin: String,
    )

    /** 解析 [url]；地址里没有凭据 / 不是 `协议://` 形式时返回 null。 */
    fun parse(url: String): Parsed? {
        val trimmed = url.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        val rest = trimmed.substring(schemeEnd + 3)
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (authorityEnd >= 0) rest.substring(0, authorityEnd) else rest
        val tail = if (authorityEnd >= 0) rest.substring(authorityEnd) else ""

        val at = authority.lastIndexOf('@')
        if (at <= 0) return null
        val userinfo = authority.substring(0, at)
        val hostPort = authority.substring(at + 1)
        if (hostPort.isEmpty()) return null

        val colon = userinfo.indexOf(':')
        val rawUser = if (colon >= 0) userinfo.substring(0, colon) else userinfo
        val user = percentDecode(rawUser)
        if (user.isEmpty()) return null
        val password = if (colon >= 0) percentDecode(userinfo.substring(colon + 1)) else ""

        return Parsed(
            cleanUrl = "$scheme://$hostPort$tail",
            username = user,
            password = password,
            origin = originOf(scheme, hostPort),
        )
    }

    /**
     * [url] 所属 origin：`协议://主机[:端口]`（整体小写）。
     * 同一主机的多个仓库共用一条凭据（与 git 凭据缓存的主机粒度一致）。
     * 非 `协议://` 形式（本地路径 / scp 写法）返回空串。
     */
    fun originOf(url: String): String {
        val trimmed = url.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return ""
        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        val rest = trimmed.substring(schemeEnd + 3)
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (authorityEnd >= 0) rest.substring(0, authorityEnd) else rest
        if (authority.isEmpty()) return ""
        val hostPort = authority.substringAfter('@', missingDelimiterValue = authority)
        if (hostPort.isEmpty()) return ""
        return originOf(scheme, hostPort)
    }

    /** 把地址里的凭据摘出去，返回可直接交给 libgit2 的地址（没有凭据时原样返回）。 */
    fun stripCredentials(url: String): String = parse(url)?.cleanUrl ?: url.trim()

    private fun originOf(scheme: String, hostPort: String): String =
        "$scheme://${hostPort.lowercase()}"

    /** `%XX` 十六进制解码（按字节收集后按 UTF-8 还原，与 libgit2 一致）；非法序列原样保留，`+` 保持不变。 */
    private fun percentDecode(raw: String): String {
        if (raw.isEmpty() || !raw.contains('%')) return raw
        val bytes = ArrayList<Byte>(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '%' && i + 2 < raw.length) {
                val hi = Character.digit(raw[i + 1], 16)
                val lo = Character.digit(raw[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    bytes.add(((hi shl 4) or lo).toByte())
                    i += 3
                    continue
                }
            }
            // 未编码的字符按 UTF-8 逐字节写入，保证与 %XX 字节序列能正确拼接
            ch.toString().toByteArray(Charsets.UTF_8).forEach { bytes.add(it) }
            i++
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }
}
