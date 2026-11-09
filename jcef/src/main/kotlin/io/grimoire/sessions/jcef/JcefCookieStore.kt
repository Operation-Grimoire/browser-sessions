package io.grimoire.sessions.jcef

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.grimoire.sessions.BrowserCookieStore
import io.grimoire.sessions.SameSite
import io.grimoire.sessions.SessionCookie
import okhttp3.Cookie
import okhttp3.HttpUrl
import org.cef.browser.CefBrowser
import org.cef.browser.SessionDevToolsBridge
import org.cef.network.CefCookieManager
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.swing.SwingUtilities

/**
 * Cookie access through the embedded browser's DevTools protocol, including HttpOnly and
 * SameSite. Use a global request context and a host-owned persistent CEF profile. Calls
 * block an IO worker with a deadline; never call from Swing/CEF callbacks. Closing this
 * adapter cancels its pending calls but does not dispose the host's browser or DevTools.
 */
class JcefCookieStore internal constructor(
    private val command: (String, String) -> CompletableFuture<String>,
    private val flushCommand: () -> CompletableFuture<Unit>,
    private val timeoutMillis: Long,
    private val closeCommand: () -> Unit = {},
) : BrowserCookieStore, AutoCloseable {
    private val lock = Any()
    private val pending = mutableSetOf<CompletableFuture<*>>()
    private var closed = false

    private constructor(bridge: SessionDevToolsBridge, timeoutMillis: Long) : this(
        { method, params -> bridge.execute(method, params) },
        {
            val result = CompletableFuture<Unit>()
            if (!CefCookieManager.getGlobalManager().flushStore { result.complete(Unit) }) {
                result.completeExceptionally(IOException("CEF cookie store is unavailable"))
            }
            result
        },
        timeoutMillis,
        bridge::close,
    )

    @JvmOverloads constructor(browser: CefBrowser, timeoutMillis: Long = 10_000) :
        this(createBridge(browser, timeoutMillis), timeoutMillis)

    private companion object {
        fun createBridge(browser: CefBrowser, timeoutMillis: Long): SessionDevToolsBridge {
            require(timeoutMillis > 0)
            require(browser.requestContext?.isGlobal != false) { "Only the global CEF request context is supported" }
            return SessionDevToolsBridge(browser)
        }
    }

    init { require(timeoutMillis > 0) }

    override fun cookiesForRequest(url: HttpUrl): List<Cookie> = readCookies(url, snapshot = false)
        .map(SessionCookie::toCookie).filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() }
        .sortedByDescending { it.path.length }

    override fun snapshot(url: HttpUrl): List<SessionCookie> = readCookies(url, snapshot = true)
        .filter { it.toCookie().matches(url) && (it.expiresAt?.let { expiry -> expiry > System.currentTimeMillis() } != false) }

    private fun readCookies(url: HttpUrl, snapshot: Boolean): List<SessionCookie> {
        val parameters = JsonObject().apply { add("urls", JsonArray().apply { add(url.toString()) }) }
        val result = call("Network.getCookies", parameters)
        return result.getAsJsonArray("cookies").mapNotNull { entry ->
            val cookie = entry.asJsonObject
            if (cookie.has("partitionKey") || cookie.get("partitionKeyOpaque")?.asBoolean == true) {
                check(!snapshot) { "Partitioned cookies cannot be exported without their top-level context" }
                return@mapNotNull null
            }
            val domain = cookie.get("domain").asString
            SessionCookie(
                name = cookie.get("name").asString,
                value = cookie.get("value").asString,
                domain = domain.removePrefix("."),
                path = cookie.get("path").asString,
                hostOnly = !domain.startsWith('.'),
                secure = cookie.get("secure").asBoolean,
                httpOnly = cookie.get("httpOnly").asBoolean,
                expiresAt = cookie.get("expires")?.asDouble?.takeIf { it > 0 }?.let { (it * 1000).toLong() },
                sameSite = cookie.get("sameSite")?.asString?.let { value ->
                    requireNotNull(SameSite.entries.firstOrNull { it.headerValue == value }) { "Unsupported browser SameSite metadata" }
                },
            )
        }
    }

    override fun setCookie(url: HttpUrl, header: String) {
        val cookie = SessionCookie.parse(url, header) ?: return
        val parameters = JsonObject().apply {
            addProperty("name", cookie.name)
            addProperty("value", cookie.value)
            addProperty("url", url.toString())
            if (!cookie.hostOnly) addProperty("domain", ".${cookie.domain}")
            addProperty("path", cookie.path)
            addProperty("secure", cookie.secure)
            addProperty("httpOnly", cookie.httpOnly)
            cookie.sameSite?.let { addProperty("sameSite", it.headerValue) }
            cookie.expiresAt?.let { addProperty("expires", it.coerceAtLeast(0) / 1000.0) }
        }
        val result = call("Network.setCookie", parameters)
        if (result.get("success")?.asBoolean == false) throw IOException("Browser rejected a cookie")
    }

    override fun flush() { await { flushCommand() } }

    private fun call(method: String, parameters: JsonObject): JsonObject =
        JsonParser.parseString(await { command(method, parameters.toString()) }).asJsonObject

    private fun <T> await(start: () -> CompletableFuture<T>): T {
        check(!SwingUtilities.isEventDispatchThread()) { "Cookie operations must run on an IO worker" }
        val future = synchronized(lock) {
            check(!closed) { "Cookie store is closed" }
            start().also { pending += it }
        }
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            future.cancel(true)
            throw IOException("Browser cookie operation timed out")
        } catch (interrupted: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw IOException("Browser cookie operation interrupted")
        } catch (failure: java.util.concurrent.ExecutionException) {
            throw IOException("Browser cookie operation failed")
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw IOException("Browser cookie operation was cancelled")
        } finally {
            synchronized(lock) { pending -= future }
        }
    }

    override fun close() {
        val outstanding = synchronized(lock) {
            if (closed) return
            closed = true
            pending.toList().also { pending.clear() }
        }
        outstanding.forEach { it.cancel(true) }
        closeCommand()
    }
}
