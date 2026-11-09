package io.grimoire.sessions

import okhttp3.Cookie
import okhttp3.HttpUrl

enum class CookiePriority(val headerValue: String) { LOW("Low"), MEDIUM("Medium"), HIGH("High") }

enum class SameSite(val headerValue: String) { STRICT("Strict"), LAX("Lax"), NONE("None") }
enum class CookieScopeAccuracy { EXACT, HOST_PINNED }

/** Metadata for a cookie, not a claim that a source login is portable between devices. */
data class SessionCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val hostOnly: Boolean,
    val secure: Boolean,
    val httpOnly: Boolean,
    val expiresAt: Long? = null,
    val sameSite: SameSite? = null,
    val scopeAccuracy: CookieScopeAccuracy = CookieScopeAccuracy.EXACT,
    val priority: CookiePriority? = null,
) {
    init {
        require(name.matches(Regex("[!#$%&'*+.^_`|~0-9a-zA-Z-]+"))) { "Invalid cookie name" }
        require(value.all { it.code in 0x20..0x7e && it != ';' }) { "Invalid cookie value" }
        require(path.startsWith('/') && path.none { it.code < 0x20 || it.code == 0x7f || it == ';' }) { "Invalid cookie path" }
        val hostPrefix = name.startsWith("__Host-", ignoreCase = true)
        val httpPrefix = name.startsWith("__Http-", ignoreCase = true) || name.startsWith("__Host-Http-", ignoreCase = true)
        require(!(hostPrefix || httpPrefix || name.startsWith("__Secure-", ignoreCase = true)) || secure) { "Cookie prefix requires Secure" }
        require(!hostPrefix || (hostOnly && path == "/")) { "Host cookie prefix requires host-only root scope" }
        require(!httpPrefix || httpOnly) { "HTTP cookie prefix requires HttpOnly" }
        require(sameSite != SameSite.NONE || secure) { "SameSite=None requires Secure" }
        require(scopeAccuracy != CookieScopeAccuracy.HOST_PINNED || hostOnly) { "Pinned cookies must be host-only" }
        // Let OkHttp validate and canonicalize the domain too; keep failures free of values.
        require(runCatching { toCookie().domain == domain }.getOrDefault(false)) { "Invalid or noncanonical cookie domain" }
    }

    fun toCookie(): Cookie = Cookie.Builder().name(name).value(value).path(path).apply {
        if (hostOnly) hostOnlyDomain(domain) else domain(domain)
        if (secure) secure()
        if (httpOnly) httpOnly()
        expiresAt?.let(::expiresAt)
    }.build()

    fun toSetCookieHeader(): String = toCookie().toString() +
        (sameSite?.let { "; SameSite=${it.headerValue}" } ?: "") +
        (priority?.let { "; Priority=${it.headerValue}" } ?: "")

    override fun toString() = "SessionCookie(name=$name, domain=$domain, path=$path, value=<redacted>)"

    companion object {
        fun fromCookie(cookie: Cookie, sameSite: SameSite? = null, priority: CookiePriority? = null): SessionCookie = SessionCookie(
            cookie.name, cookie.value, cookie.domain, cookie.path, cookie.hostOnly, cookie.secure,
            cookie.httpOnly, cookie.expiresAt.takeIf { cookie.persistent }, sameSite, priority = priority,
        )

        /** Partitioned state requires a top-level context that an ordinary HTTP request lacks. */
        fun parse(url: HttpUrl, header: String): SessionCookie? = decode(url, header, hostPinned = false)

        /** Trusted browser metadata may materialize Domain on an originally host-only cookie. */
        fun fromHostPinnedBrowserMetadata(url: HttpUrl, header: String): SessionCookie? =
            decode(url, header, hostPinned = true)

        private fun decode(url: HttpUrl, header: String, hostPinned: Boolean): SessionCookie? {
            if (header.any { it == '\r' || it == '\n' || it == '\u0000' }) return null
            val attributes = header.split(';').drop(1).map { it.trim().split('=', limit = 2).map { part -> part.trim() } }
            if (attributes.any { it[0].equals("Partitioned", true) }) return null
            val parsed = Cookie.parse(url, header) ?: return null
            if (parsed.secure && !url.isHttps) return null
            var normalizeIpHost = false
            if (!hostPinned && parsed.name.startsWith("__Host-", ignoreCase = true)) {
                if (attributes.lastOrNull { it[0].equals("Path", true) }?.getOrNull(1) != "/") return null
                val declaredDomain = attributes.lastOrNull { it[0].equals("Domain", true) }?.getOrNull(1).orEmpty()
                if (declaredDomain.isNotEmpty()) {
                    val ipHost = url.host.contains(':') || url.host.split('.').let { parts ->
                        parts.size == 4 && parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
                    }
                    if (!ipHost || declaredDomain != url.host) return null
                    normalizeIpHost = true
                }
            }
            val sameSite = attributes.lastOrNull { it[0].equals("SameSite", true) }?.getOrNull(1)
                ?.let { value -> SameSite.entries.firstOrNull { it.headerValue.equals(value, true) } }
            val priority = attributes.lastOrNull { it[0].equals("Priority", true) }?.getOrNull(1)
                ?.let { value -> CookiePriority.entries.firstOrNull { it.headerValue.equals(value, true) } }
            if (hostPinned && !parsed.matches(url)) return null
            return runCatching {
                if (!hostPinned && !normalizeIpHost) fromCookie(parsed, sameSite, priority)
                else SessionCookie(
                    parsed.name, parsed.value, url.host, parsed.path, true, parsed.secure,
                    parsed.httpOnly, parsed.expiresAt.takeIf { parsed.persistent }, sameSite,
                    if (hostPinned) CookieScopeAccuracy.HOST_PINNED else CookieScopeAccuracy.EXACT, priority,
                )
            }.getOrNull()
        }
    }
}
