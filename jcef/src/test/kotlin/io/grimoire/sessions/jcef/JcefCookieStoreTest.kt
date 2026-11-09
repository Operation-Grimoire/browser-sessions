package io.grimoire.sessions.jcef

import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class JcefCookieStoreTest {
    private val url = "https://example.com/account".toHttpUrl()

    @Test fun `empty cookies complete immediately and queries stay bound to the supplied browser URL`() {
        val store = JcefCookieStore({ method, json ->
            assertEquals("Network.getCookies", method)
            assertEquals(url.toString(), JsonParser.parseString(json).asJsonObject.getAsJsonArray("urls").single().asString)
            CompletableFuture.completedFuture("""{"cookies": []}""")
        }, { CompletableFuture.completedFuture(Unit) }, 100)
        assertTrue(store.cookiesForRequest(url).isEmpty())
    }

    @Test fun `snapshot preserves native metadata and never exports partitioned state`() {
        val record = """{"name":"login","value":"secret","domain":"example.com","path":"/","secure":true,"httpOnly":true,"sameSite":"Lax","expires":2000000000}"""
        val store = JcefCookieStore({ _, _ -> CompletableFuture.completedFuture("""{"cookies":[$record]}""") },
            { CompletableFuture.completedFuture(Unit) }, 100)
        val cookie = store.snapshot(url).single()
        assertTrue(cookie.secure && cookie.httpOnly && cookie.hostOnly)
        assertEquals(2000000000000L, cookie.expiresAt)
        assertEquals("Lax", cookie.sameSite?.headerValue)
        assertFalse(cookie.toString().contains("secret"))
        val partitioned = JcefCookieStore({ _, _ -> CompletableFuture.completedFuture("""{"cookies":[${record.dropLast(1)},"partitionKey":{}}]}""") },
            { CompletableFuture.completedFuture(Unit) }, 100)
        assertTrue(partitioned.cookiesForRequest(url).isEmpty())
        assertFailsWith<IllegalStateException> { partitioned.snapshot(url) }
    }

    @Test fun `writes retain SameSite and host-only semantics`() {
        val store = JcefCookieStore({ method, json ->
            assertEquals("Network.setCookie", method)
            val params = JsonParser.parseString(json).asJsonObject
            assertEquals("Strict", params.get("sameSite").asString)
            assertTrue(params.get("httpOnly").asBoolean)
            assertFalse(params.has("domain"))
            CompletableFuture.completedFuture("""{"success":true}""")
        }, { CompletableFuture.completedFuture(Unit) }, 100)
        store.setCookie(url, "login=value; Secure; HttpOnly; SameSite=Strict; Path=/")
    }

    @Test fun `timeouts cancel pending work and close interrupts outstanding calls`() {
        val timed = CompletableFuture<String>()
        val timeoutStore = JcefCookieStore({ _, _ -> timed }, { CompletableFuture.completedFuture(Unit) }, 30)
        assertFailsWith<IOException> { timeoutStore.cookiesForRequest(url) }
        assertTrue(timed.isCancelled)
        val pending = CompletableFuture<String>()
        val started = CountDownLatch(1)
        val store = JcefCookieStore({ _, _ -> started.countDown(); pending }, { CompletableFuture.completedFuture(Unit) }, 5000)
        val worker = CompletableFuture.supplyAsync { runCatching { store.cookiesForRequest(url) }.exceptionOrNull() }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        store.close()
        assertIs<IOException>(worker.get(2, TimeUnit.SECONDS))
        assertTrue(pending.isCancelled)
        assertFailsWith<IllegalStateException> { store.cookiesForRequest(url) }
    }
}
