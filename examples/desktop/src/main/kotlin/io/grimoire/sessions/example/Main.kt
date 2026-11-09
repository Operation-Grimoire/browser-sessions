package io.grimoire.sessions.example

import java.io.File

fun main(args: Array<String>) {
    val base = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "browser-sessions-example")
    val initialUrl = args.firstOrNull() ?: "https://www.royalroad.com/account/login"
    val browser = ExampleBrowser(File(base, "profile"), File(base, "jcef-152.0.6"), initialUrl)
    browser.terminated.get()
}
