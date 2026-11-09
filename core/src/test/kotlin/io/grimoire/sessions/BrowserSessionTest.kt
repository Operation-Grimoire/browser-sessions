package io.grimoire.sessions

import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import kotlin.test.*

class BrowserSessionTest {
    private class Store : BrowserCookieStore {
        val records = mutableListOf<Cookie>()
        val received = mutableListOf<String>()
        override fun cookiesForRequest(url: HttpUrl) = records.filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() }
        override fun setCookie(url: HttpUrl, header: String) {
            received += header
            Cookie.parse(url, header)?.let { cookie ->
                records.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                if (cookie.expiresAt > System.currentTimeMillis()) records += cookie
            }
        }
        override fun snapshot(url: HttpUrl) = cookiesForRequest(url).map { SessionCookie.fromCookie(it) }
        override fun flush() = Unit
    }

    @Test fun `redirect responses preserve raw attributes and use the browser login immediately`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/reader")
                .addHeader("Set-Cookie", "login=first; Path=/; HttpOnly; SameSite=Strict"))
            server.enqueue(MockResponse().setBody("private chapter"))
            val store = Store()
            val client = BrowserSession(store).configure(OkHttpClient.Builder()).build()
            client.newCall(Request.Builder().url(server.url("/login")).build()).execute().use { assertEquals(200, it.code) }
            assertNull(server.takeRequest().getHeader("Cookie"))
            assertEquals("login=first", server.takeRequest().getHeader("Cookie"))
            assertEquals(listOf("login=first; Path=/; HttpOnly; SameSite=Strict"), store.received)
        }
    }

    @Test fun `browser rotations and server logout are visible without a second jar`() {
        val origin = "https://example.com/reader".toHttpUrl()
        val store = Store()
        val session = BrowserSession(store)
        store.setCookie(origin, "login=browser; Path=/; HttpOnly; Secure")
        assertEquals("browser", session.cookieJar.loadForRequest(origin).single().value)
        store.setCookie(origin, "login=rotated; Path=/; HttpOnly; Secure")
        assertEquals("rotated", session.cookieJar.loadForRequest(origin).single().value)
        store.setCookie(origin, "login=; Path=/; Max-Age=0; Secure")
        assertTrue(session.cookieJar.loadForRequest(origin).isEmpty())
    }

    @Test fun `insecure responses cannot set or overwrite a secure cookie including deletion`() {
        val store = Store()
        val https = "https://example.com/reader".toHttpUrl()
        val http = "http://example.com/reader".toHttpUrl()
        val writer = BrowserCookieJar(store)
        store.setCookie(https, "login=secret; Path=/; Secure")
        listOf("login=attacker; Path=/", "login=; Path=/; Max-Age=0", "other=value; Secure")
            .forEach { Cookie.parse(http, it)?.let { parsed -> writer.saveFromResponse(http, listOf(parsed)) } }
        assertEquals("secret", writer.loadForRequest(https).single().value)
        assertEquals(1, store.received.size)
        assertTrue(writer.loadForRequest(http).isEmpty())
    }

    @Test fun `codec keeps cookie metadata without exposing the value in diagnostics`() {
        val url = "https://example.com/account/login".toHttpUrl()
        val cookie = requireNotNull(SessionCookie.parse(url, "login=do-not-log; Path=/account; Max-Age=3600; Secure; HttpOnly; SameSite=Lax"))
        assertTrue(cookie.hostOnly && cookie.secure && cookie.httpOnly)
        assertEquals(SameSite.LAX, cookie.sameSite)
        assertEquals("/account", cookie.path)
        assertNotNull(cookie.expiresAt)
        assertFalse(cookie.toString().contains("do-not-log"))
        assertTrue(cookie.toSetCookieHeader().contains("SameSite=Lax"))
        assertTrue(cookie.toCookie().matches(url))
        assertFalse(cookie.toCookie().matches("https://child.example.com/account".toHttpUrl()))
        assertFalse(cookie.toCookie().matches("https://example.com/accounting".toHttpUrl()))
    }

    @Test fun `invalid prefixes and unsupported partitioned state are rejected`() {
        val url = "https://example.com/".toHttpUrl()
        for (header in listOf("__Secure-id=x", "__Host-id=x; Secure; Domain=example.com; Path=/", "__Host-id=x; Secure; Path=/other",
            "id=x; SameSite=None", "id=x; Secure; Partitioned", "id=x\r\nInjected: value")) {
            assertNull(SessionCookie.parse(url, header), header.substringBefore('='))
        }
    }

    @Test fun `compatibility cookie jar still writes server cookies when used alone`() {
        val store = Store()
        val url = "https://example.com/".toHttpUrl()
        val jar = BrowserCookieJar(store)
        jar.saveFromResponse(url, listOf(requireNotNull(Cookie.parse(url, "login=value; HttpOnly; Secure"))))
        assertEquals("value", jar.loadForRequest(url).single().value)
    }

    @Test fun `secure overlay checks use the cookie path even when the response has a different path`() {
        val store = Store()
        val secure = "https://example.com/private".toHttpUrl()
        store.setCookie(secure, "login=secret; Path=/private; Secure")
        val responseUrl = "http://example.com/public".toHttpUrl()
        BrowserCookieJar(store).saveFromResponse(responseUrl, listOf(requireNotNull(Cookie.parse(responseUrl, "login=attacker; Path=/private"))))
        assertEquals("secret", store.cookiesForRequest(secure).single().value)
    }

    @Test fun `quoted cookie values already accepted by OkHttp remain supported`() {
        val url = "https://example.com/".toHttpUrl()
        val original = requireNotNull(Cookie.parse(url, "preference=\"dark mode\"; Path=/"))
        assertEquals(original.value, SessionCookie.parse(url, original.toString())?.value)
    }
}
