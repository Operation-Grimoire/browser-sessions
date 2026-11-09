package io.grimoire.sessions.android

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.grimoire.sessions.BrowserSession
import io.grimoire.sessions.CookieScopeAccuracy
import io.grimoire.sessions.SameSite
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WebViewCookieStoreTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun browserLoginHttpRotationAndLogoutShareOneNativeStore() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path?.substringBefore('?')) {
                    "/login" -> MockResponse().setBody("""<html><form action="/session"><button id="login">Sign in</button></form></html>""")
                    "/session" -> MockResponse().setResponseCode(302).addHeader("Location", "/private")
                        .addHeader("Set-Cookie", "login=browser; Path=/; HttpOnly; SameSite=Strict; Max-Age=3600")
                    "/private" -> if (request.getHeader("Cookie")?.contains("login=") == true) MockResponse().setBody("Private chapter")
                        else MockResponse().setResponseCode(401).setBody("Login required")
                    "/rotate" -> MockResponse().addHeader("Set-Cookie", "login=http-rotated; Path=/; HttpOnly; SameSite=Lax; Max-Age=3600")
                    "/logout" -> MockResponse().addHeader("Set-Cookie", "login=; Path=/; Max-Age=0")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            ActivityScenario.launch(CookieTestActivity::class.java).use { scenario ->
                lateinit var activity: CookieTestActivity
                scenario.onActivity { activity = it }
                val store = WebViewCookieStore()
                val client = BrowserSession(store).configure(OkHttpClient.Builder()).build()
                navigate(activity, server.url("/login").toString())
                val signedIn = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    activity.browser.webViewClient = client { if (it.endsWith("/private")) signedIn.countDown() }
                    activity.browser.evaluateJavascript("document.getElementById('login').click()", null)
                }
                await(signedIn)
                assertEquals(200, fetch(client, server.url("/private").toString()))
                assertEquals(SameSite.STRICT, store.snapshot(server.url("/private")).single { it.name == "login" }.sameSite)
                assertEquals(200, fetch(client, server.url("/rotate").toString()))
                navigate(activity, server.url("/private").toString())
                val rotated = store.snapshot(server.url("/private")).single { it.name == "login" }
                assertEquals("http-rotated", rotated.value)
                assertTrue(rotated.httpOnly && rotated.hostOnly)
                assertEquals(SameSite.LAX, rotated.sameSite)
                assertEquals(CookieScopeAccuracy.HOST_PINNED, rotated.scopeAccuracy)
                assertNotNull(rotated.expiresAt)
                assertEquals(200, fetch(client, server.url("/logout").toString()))
                assertEquals(401, fetch(client, server.url("/private").toString()))
                assertTrue(store.cookiesForRequest(server.url("/private")).none { it.name == "login" })
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    /** Run each named phase in a fresh instrumentation process to prove restart persistence. */
    @Test fun persistentCookieAndLogout() {
        ActivityScenario.launch(CookieTestActivity::class.java).use {
            val store = WebViewCookieStore()
            val url = "https://sessions.example.test/account".toHttpUrl()
            when (InstrumentationRegistry.getArguments().getString("phase")) {
                "publish" -> store.setCookie(url, "restart_login=persisted; Path=/account; Secure; HttpOnly; SameSite=Lax; Max-Age=3600")
                "restore" -> assertEquals("persisted", store.cookiesForRequest(url).single { cookie -> cookie.name == "restart_login" }.value)
                "logout" -> store.setCookie(url, "restart_login=; Path=/account; Secure; Max-Age=0")
                "verifyLogout" -> assertTrue(store.cookiesForRequest(url).none { cookie -> cookie.name == "restart_login" })
                null -> {
                    store.setCookie(url, "__Host-flush_check=value; Path=/; Secure; HttpOnly; Max-Age=3600")
                    assertTrue(store.snapshot(url).single { cookie -> cookie.name == "__Host-flush_check" }.secure)
                    store.setCookie(url, "__Host-flush_check=; Path=/; Secure; Max-Age=0")
                }
                else -> error("Unknown persistence phase")
            }
            store.flush()
        }
    }

    private fun fetch(client: OkHttpClient, url: String): Int = client.newCall(Request.Builder().url(url).build()).execute().use { it.code }
    private fun await(latch: CountDownLatch) { assertTrue("Browser operation timed out", latch.await(20, TimeUnit.SECONDS)) }
    private fun client(loaded: (String) -> Unit) = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) { loaded(url) }
    }
    private fun navigate(activity: CookieTestActivity, url: String) {
        val loaded = CountDownLatch(1)
        instrumentation.runOnMainSync { activity.browser.webViewClient = client { if (it == url) loaded.countDown() }; activity.browser.loadUrl(url) }
        await(loaded)
    }
}
