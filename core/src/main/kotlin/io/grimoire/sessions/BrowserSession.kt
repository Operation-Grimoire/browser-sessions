package io.grimoire.sessions

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/** CookieJar-compatible fallback. Use BrowserSession to retain raw response attributes. */
class BrowserCookieJar(private val store: BrowserCookieStore) : CookieJar {
    override fun loadForRequest(url: HttpUrl): List<Cookie> = store.cookiesForRequest(url)
        .filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() }
        .sortedByDescending { it.path.length }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { saveCookie(store, url, it.toString()) }
    }
}

/** Install both members together, or call configure. This object stores no cookie copies. */
class BrowserSession(val store: BrowserCookieStore) {
    private val reader = BrowserCookieJar(store)
    val cookieJar: CookieJar = object : CookieJar {
        override fun loadForRequest(url: HttpUrl) = reader.loadForRequest(url)
        // The network interceptor already saved the original headers before redirect handling.
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
    }
    val networkInterceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        try {
            response.headers("Set-Cookie").forEach { saveCookie(store, response.request.url, it) }
            response
        } catch (failure: Exception) {
            response.close()
            throw failure
        }
    }

    fun configure(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder
        .cookieJar(cookieJar).addNetworkInterceptor(networkInterceptor)
}

private fun saveCookie(store: BrowserCookieStore, url: HttpUrl, header: String) {
    val cookie = SessionCookie.parse(url, header) ?: return
    if (!url.isHttps) {
        val secureUrl = url.newBuilder().scheme("https").encodedPath(cookie.path).build()
        val wouldOverwriteSecure = store.cookiesForRequest(secureUrl).any { existing ->
            existing.secure && existing.name == cookie.name &&
                (existing.domain == cookie.domain || existing.domain.endsWith(".${cookie.domain}") || cookie.domain.endsWith(".${existing.domain}")) &&
                (cookie.path == existing.path || cookie.path.startsWith(existing.path.trimEnd('/') + "/"))
        }
        if (wouldOverwriteSecure) return
    }
    store.setCookie(url, header)
}
