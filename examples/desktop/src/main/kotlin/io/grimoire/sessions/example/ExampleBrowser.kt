package io.grimoire.sessions.example

import com.google.gson.JsonParser
import io.grimoire.sessions.BrowserSession
import io.grimoire.sessions.jcef.JcefCookieStore
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter
import okhttp3.OkHttpClient
import okhttp3.Request
import org.cef.CefApp
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.SessionDevToolsBridge
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.*

/** A minimal host showing the ownership required by an embedded browser application. */
class ExampleBrowser(
    profile: File,
    natives: File,
    initialUrl: String,
    configureEngine: (CefAppBuilder) -> Unit = {},
    configureHttp: (OkHttpClient.Builder) -> Unit = {},
) : AutoCloseable {
    val terminated = CompletableFuture<Unit>()
    val created = CompletableFuture<CefBrowser>()
    private val browserClosed = CompletableFuture<Unit>()
    private val worker = Executors.newSingleThreadExecutor()
    private val app: CefApp
    private val client: org.cef.CefClient
    val browser: CefBrowser
    private lateinit var window: JFrame
    val store: JcefCookieStore by lazy { JcefCookieStore(created.get(30, TimeUnit.SECONDS)) }
    val commands: SessionDevToolsBridge by lazy { SessionDevToolsBridge(created.get(30, TimeUnit.SECONDS)) }
    private val httpDelegate = lazy {
        val userAgent = JsonParser.parseString(commands.execute("Browser.getVersion", null)
            .get(10, TimeUnit.SECONDS)).asJsonObject.get("userAgent").asString
        BrowserSession(store).configure(OkHttpClient.Builder().apply(configureHttp)).addInterceptor { chain ->
            val request = chain.request()
            chain.proceed(if (request.header("User-Agent") == null) request.newBuilder().header("User-Agent", userAgent).build() else request)
        }.build()
    }
    val http: OkHttpClient get() = httpDelegate.value
    @Volatile var onLoad: (String, Int) -> Unit = { _, _ -> }
    private val closing = java.util.concurrent.atomic.AtomicBoolean()

    init {
        val builder = CefAppBuilder()
        builder.setInstallDir(natives)
        builder.cefSettings.windowless_rendering_enabled = false
        builder.cefSettings.root_cache_path = profile.absolutePath
        builder.cefSettings.cache_path = File(profile, "default").absolutePath
        builder.cefSettings.log_file = File(profile, "cef.log").absolutePath
        builder.setAppHandler(object : MavenCefAppHandlerAdapter() {
            override fun stateHasChanged(state: CefApp.CefAppState) {
                if (state == CefApp.CefAppState.TERMINATED) terminated.complete(Unit)
            }
        })
        configureEngine(builder)
        app = builder.build()
        client = app.createClient()
        client.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
            override fun onAfterCreated(browser: CefBrowser) { created.complete(browser) }
            override fun onBeforeClose(browser: CefBrowser) { browserClosed.complete(Unit) }
        })
        client.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, status: Int) {
                if (frame.isMain) onLoad(browser.url, status)
            }
        })
        browser = client.createBrowser(initialUrl, false, false)
        SwingUtilities.invokeAndWait {
            window = JFrame("Browser sessions")
            val address = JTextField(initialUrl)
            val status = JLabel("Sign in here, then check HTTP access to the current URL.")
            val fetch = JButton("Check HTTP access")
            address.addActionListener { browser.loadURL(address.text) }
            fetch.addActionListener {
                val url = browser.url
                fetch.isEnabled = false
                worker.execute {
                    val message = runCatching {
                        http.newCall(Request.Builder().url(url).build()).execute().use { "HTTP ${it.code} for $url" }
                    }.getOrElse { "HTTP check failed: ${it.javaClass.simpleName}" }
                    SwingUtilities.invokeLater { status.text = message; fetch.isEnabled = true }
                }
            }
            window.layout = BorderLayout()
            window.add(JPanel(BorderLayout()).apply { add(address, BorderLayout.CENTER); add(fetch, BorderLayout.EAST) }, BorderLayout.NORTH)
            window.add(browser.uiComponent, BorderLayout.CENTER)
            window.add(status, BorderLayout.SOUTH)
            window.setSize(1100, 800)
            window.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
            window.addWindowListener(object : WindowAdapter() {
                override fun windowClosing(event: WindowEvent) { worker.execute { close() } }
            })
            window.isVisible = true
        }
    }

    override fun close() {
        if (SwingUtilities.isEventDispatchThread()) {
            if (!closing.get()) {
                try { worker.execute { close() } } catch (stopped: java.util.concurrent.RejectedExecutionException) {
                    if (!closing.get()) throw stopped
                }
            }
            return
        }
        if (!closing.compareAndSet(false, true)) return
        try { if (created.isDone) store.flush() } finally {
            if (created.isDone) { store.close(); commands.close() }
            if (httpDelegate.isInitialized()) {
                http.connectionPool.evictAll()
                http.dispatcher.executorService.shutdown()
            }
            try {
                browser.close(true)
                browserClosed.get(20, TimeUnit.SECONDS)
            } finally {
                client.dispose()
                app.dispose()
                try { SwingUtilities.invokeAndWait { window.dispose() } } finally { worker.shutdown() }
            }
        }
    }
}
