package io.grimoire.sessions.example

import io.grimoire.sessions.SameSite
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

fun main(args: Array<String>) {
    try {
        runBrowserPhase(args)
    } catch (failure: Throwable) {
        failure.printStackTrace()
        System.err.println("CEF state at failure: ${org.cef.CefApp.getState()}")
        Thread.getAllStackTraces().forEach { (thread, stack) ->
            System.err.println("Thread ${thread.name}: ${thread.state}")
            stack.forEach { System.err.println("  at $it") }
        }
        // A failed native shutdown can leave Swing alive after main throws.
        // This is an isolated test process: report failure without hiding the timeout.
        kotlin.system.exitProcess(1)
    }
}

private fun runBrowserPhase(args: Array<String>) {
    val phase = args.single()
    val loads = LinkedBlockingQueue<Pair<String, Int>>()
    val certificate = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
    val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    val trustedTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
    val spki = java.util.Base64.getEncoder().encodeToString(
        java.security.MessageDigest.getInstance("SHA-256").digest(certificate.certificate.publicKey.encoded))
    val host = ExampleBrowser(
        File("build/browser-test/profile"), File("build/jcef-152.0.6"), "about:blank",
        configureEngine = { it.addJcefArgs("--ignore-certificate-errors-spki-list=$spki") },
        configureHttp = { it.sslSocketFactory(trustedTls.sslSocketFactory(), trustedTls.trustManager) },
    )
    var failure: Throwable? = null
    try {
        host.created.get(30, TimeUnit.SECONDS)
        host.onLoad = { url, status -> loads.put(url to status) }
        fun navigate(url: String): Int {
            loads.clear()
            javax.swing.SwingUtilities.invokeLater { host.browser.loadURL(url) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (System.nanoTime() < deadline) {
                val loaded = loads.poll(1, TimeUnit.SECONDS) ?: continue
                if (loaded.first == url) return loaded.second
            }
            error("Browser navigation timed out")
        }
        fun fetch(url: String): Int = host.http.newCall(Request.Builder().url(url).build()).execute().use { it.code }
        val persistent = "https://sessions.example.test/account".toHttpUrl()
        when (phase) {
            "roundtrip" -> MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when (request.path?.substringBefore('?')) {
                        "/login" -> MockResponse().setBody("""<html><form action="/session"><button id="login">Sign in</button></form></html>""")
                        "/session" -> MockResponse().setResponseCode(302).addHeader("Location", "/private")
                            .addHeader("Set-Cookie", "login=browser; Path=/; HttpOnly; SameSite=Strict; Max-Age=3600")
                        "/private" -> if (request.getHeader("Cookie")?.contains("login=") == true) MockResponse().setBody("Private chapter")
                            else MockResponse().setResponseCode(401)
                        "/rotate" -> MockResponse().addHeader("Set-Cookie", "login=http-rotated; Path=/; HttpOnly; SameSite=Lax; Max-Age=3600")
                        "/logout" -> MockResponse().addHeader("Set-Cookie", "login=; Path=/; Max-Age=0")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
                server.useHttps(serverTls.sslSocketFactory(), false)
                server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
                fun fixture(path: String) = server.url(path).newBuilder().host("127.0.0.1").build()
                check(navigate(fixture("/login").toString()) == 200)
                host.browser.executeJavaScript("document.getElementById('login').click()", host.browser.url, 0)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                while (!host.browser.url.endsWith("/private") && System.nanoTime() < deadline) Thread.sleep(50)
                check(host.browser.url.endsWith("/private"))
                check(fetch(fixture("/private").toString()) == 200)
                val before = host.store.snapshot(fixture("/private")).single { it.name == "login" }
                check(before.sameSite == SameSite.STRICT && !before.secure)
                val httpUrl = fixture("/private").newBuilder().scheme("http").build()
                val beforeHttpEligible = host.store.cookiesForRequest(httpUrl).any { it.name == "login" }
                check(fetch(fixture("/rotate").toString()) == 200)
                val cookie = host.store.snapshot(fixture("/private")).single { it.name == "login" }
                check(cookie.value == "http-rotated" && cookie.httpOnly && cookie.hostOnly && !cookie.secure && cookie.sameSite == SameSite.LAX)
                check(host.store.cookiesForRequest(httpUrl).any { it.name == "login" } == beforeHttpEligible)
                check(navigate(fixture("/private").toString()) == 200)
                check(fetch(fixture("/logout").toString()) == 200)
                check(fetch(fixture("/private").toString()) == 401)
                check(navigate(fixture("/private").toString()) == 401)
            }
            "publish" -> host.store.setCookie(persistent, "restart_login=persisted; Path=/account; Secure; HttpOnly; SameSite=Lax; Max-Age=3600")
            "restore" -> check(host.store.cookiesForRequest(persistent).single { it.name == "restart_login" }.value == "persisted")
            "logout" -> host.store.setCookie(persistent, "restart_login=; Path=/account; Secure; Max-Age=0")
            "verifyLogout" -> check(host.store.cookiesForRequest(persistent).none { it.name == "restart_login" })
            else -> error("Unknown browser-test phase")
        }
        host.store.flush()
    } catch (problem: Throwable) {
        failure = problem
        problem.printStackTrace()
    } finally {
        try {
            javax.swing.SwingUtilities.invokeAndWait { host.close() }
            // Closing from the EDT queues flush, browser close and engine shutdown.
            host.terminated.get(60, TimeUnit.SECONDS)
        } catch (cleanup: Throwable) {
            failure?.addSuppressed(cleanup) ?: run { failure = cleanup }
        }
    }
    failure?.let { throw it }
    File("build/browser-test/$phase.txt").apply { parentFile.mkdirs(); writeText("PASS: $phase\n") }
    println("PASS: embedded JCEF $phase")
}
