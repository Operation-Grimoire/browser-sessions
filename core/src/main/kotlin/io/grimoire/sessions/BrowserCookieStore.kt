package io.grimoire.sessions

import okhttp3.Cookie
import okhttp3.HttpUrl

/**
 * The browser owns matching, persistence, and mutation. Calls are blocking and belong on
 * an HTTP/IO worker, never inside browser callbacks. Implementations must be thread-safe.
 */
interface BrowserCookieStore {
    fun cookiesForRequest(url: HttpUrl): List<Cookie>
    fun setCookie(url: HttpUrl, header: String)
    /** Cookies visible at this URL, including HttpOnly. Unsupported metadata must fail explicitly. */
    fun snapshot(url: HttpUrl): List<SessionCookie>
    fun flush()
}
