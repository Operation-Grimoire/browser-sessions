package io.grimoire.sessions.android

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView

class CookieTestActivity : Activity() {
    lateinit var browser: WebView
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        browser = WebView(this)
        browser.settings.javaScriptEnabled = true
        setContentView(browser)
    }
    override fun onDestroy() { browser.destroy(); super.onDestroy() }
}
