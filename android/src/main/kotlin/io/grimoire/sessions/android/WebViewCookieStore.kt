package io.grimoire.sessions.android

import android.webkit.CookieManager
import androidx.webkit.CookieManagerCompat
import androidx.webkit.WebViewFeature
import io.grimoire.sessions.BrowserCookieStore
import io.grimoire.sessions.CookieScopeAccuracy
import io.grimoire.sessions.SessionCookie
import okhttp3.Cookie
import okhttp3.HttpUrl

/** Uses the application's existing WebView profile; construction must follow WebView setup. */
class WebViewCookieStore @JvmOverloads constructor(
    private val manager: CookieManager = CookieManager.getInstance(),
) : BrowserCookieStore {
    override fun cookiesForRequest(url: HttpUrl): List<Cookie> {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO)) {
            return CookieManagerCompat.getCookieInfo(manager, url.toString())
                .mapNotNull { SessionCookie.parse(url, it)?.toCookie() }
        }
        // Legacy WebViews still select the actual request cookies. Full metadata export
        // remains unavailable, and browser-native rules own matching on this path.
        return manager.getCookie(url.toString())?.split(';')
            ?.mapNotNull { Cookie.parse(url, it.trim()) }.orEmpty()
    }

    override fun setCookie(url: HttpUrl, header: String) { manager.setCookie(url.toString(), header) }

    override fun snapshot(url: HttpUrl): List<SessionCookie> {
        check(WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO)) {
            "This WebView cannot expose full cookie metadata"
        }
        return CookieManagerCompat.getCookieInfo(manager, url.toString()).map { header ->
            val cookie = requireNotNull(SessionCookie.parse(url, header)) { "WebView returned unsupported cookie metadata" }
            // Some WebViews materialize Domain even for an originally host-only cookie.
            // Narrow exports to this exact host instead of inventing subdomain permission.
            cookie.copy(domain = url.host, hostOnly = true, scopeAccuracy = CookieScopeAccuracy.HOST_PINNED)
        }
    }

    override fun flush() = manager.flush()
}
