package io.grimoire.sessions

import okhttp3.Cookie
import okhttp3.HttpUrl

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
) {
    init {
        require(name.matches(Regex("[!#$%&'*+.^_`|~0-9a-zA-Z-]+"))) { "Invalid cookie name" }
        require(value.all { it.code in 0x20..0x7e && it != ';' }) { "Invalid cookie value" }
        require(path.startsWith('/') && path.none { it.code < 0x20 || it.code == 0x7f || it == ';' }) { "Invalid cookie path" }
        require(!name.startsWith("__Secure-") || secure) { "Secure cookie prefix requires Secure" }
        require(!name.startsWith("__Host-") || (secure && hostOnly && path == "/")) { "Host cookie prefix requires host-only Secure scope" }
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

    fun toSetCookieHeader(): String = toCookie().toString() + (sameSite?.let { "; SameSite=${it.headerValue}" } ?: "")

    override fun toString() = "SessionCookie(name=$name, domain=$domain, path=$path, value=<redacted>)"

    companion object {
        fun fromCookie(cookie: Cookie, sameSite: SameSite? = null): SessionCookie = SessionCookie(
            cookie.name, cookie.value, cookie.domain, cookie.path, cookie.hostOnly, cookie.secure,
            cookie.httpOnly, cookie.expiresAt.takeIf { cookie.persistent }, sameSite,
        )

        /** Partitioned state requires a top-level context that an ordinary HTTP request lacks. */
        fun parse(url: HttpUrl, header: String): SessionCookie? {
            if (header.any { it == '\r' || it == '\n' || it == '\u0000' }) return null
            val attributes = header.split(';').drop(1).map { it.trim().split('=', limit = 2) }
            if (attributes.any { it[0].equals("Partitioned", true) }) return null
            val parsed = Cookie.parse(url, header) ?: return null
            if (parsed.secure && !url.isHttps) return null
            val sameSite = attributes.lastOrNull { it[0].equals("SameSite", true) }?.getOrNull(1)
                ?.let { value -> SameSite.entries.firstOrNull { it.headerValue.equals(value, true) } }
            return runCatching { fromCookie(parsed, sameSite) }.getOrNull()
        }
    }
}
