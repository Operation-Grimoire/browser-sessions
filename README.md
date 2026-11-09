# Browser sessions

Share a browser's cookie store with OkHttp on Android and desktop JVM. Android uses
WebView; desktop uses embedded JCEF. The library keeps cookies in the browser's
profile, so login, rotation, expiry and logout have one owner.

The modules contain no Grimoire source parsers, account service or cloud-sync code.
Android/JVM are the initial targets. Kotlin/Native is not implemented.

## Use

Artifacts are published together on a version tag. GitHub Packages requires
`GITHUB_ACTOR` and a package-read `GITHUB_TOKEN`, including for public packages.
Add `https://maven.pkg.github.com/Operation-Grimoire/browser-sessions` as a Maven
repository with those credentials. Android applications use
`io.grimoire:browser-sessions-android:0.1.0`; desktop hosts use
`io.grimoire:browser-sessions-jcef:0.1.0`.

```kotlin
// Android: use the same WebView profile as the application's login screen.
val session = BrowserSession(WebViewCookieStore())
val client = session.configure(OkHttpClient.Builder()).build()
```

```kotlin
// Desktop: create an embedded CefBrowser with the global CEF request context first.
val store = JcefCookieStore(browser)
val client = BrowserSession(store).configure(OkHttpClient.Builder()).build()
// Before disposing the browser, on an IO worker:
store.flush()
store.close()
```

Call `configure` once. It installs a paired CookieJar and network interceptor.
The interceptor saves the original `Set-Cookie` on every redirect response,
retaining attributes such as SameSite. Installing only `session.cookieJar` omits
response writes. `BrowserCookieJar(store)` is a standalone compatibility adapter;
OkHttp's Cookie model cannot retain SameSite on that path.

Run blocking cookie/HTTP operations on IO workers, outside browser callbacks.
The JCEF adapter rejects Swing event-thread calls, imposes a deadline, and cancels
pending operations when closed. The host owns browser creation, popups, navigation,
profile selection, user-agent consistency and disposal. See the desktop example
for a complete embedding. An ordinary source HTTP request has no browser top-level
site context: stored SameSite metadata does not add browser navigation semantics
to OkHttp.

## Metadata and scope

`snapshot(url)` returns cookies visible at that URL, including HttpOnly. It is
not an enumeration of every path or a guarantee that a source login transfers
between devices. Cookie values are sensitive; `SessionCookie.toString()` redacts
them. The library does not write separate plaintext cookie files.

Android metadata export requires WebView's `GET_COOKIE_INFO` capability. Some
WebViews materialize Domain on host-only cookies, so exports are conservatively
pinned to the requested host and marked `HOST_PINNED`. Older providers can still
supply their native request cookies, but full metadata export fails explicitly.
Partitioned cookies require additional top-level context: they are excluded from
JCEF HTTP requests and rejected by metadata export rather than widened into
ordinary cookies. The Android metadata path excludes them as well.

The bridge rejects malformed prefixes, insecure SameSite=None and HTTP attempts
to set or overwrite Secure cookies when scope metadata is available. Native
browser matching remains authoritative. Closing an adapter does not sign out;
source logout must expire/revoke the actual source session.

JCEF uses its global request context. Configure a dedicated persistent CEF profile;
custom request contexts are not supported by this adapter. Do not run two CEF
processes against the same profile. Persistent cookies can survive a clean restart;
ordinary session cookies follow the host's CEF/WebView persistence settings.

## Build and verify

Use JDK 17+ and Android SDK 36. The wrapper pins Gradle 9.4.1 with a SHA-256.

```sh
./gradlew :core:test :jcef:test :android:assembleRelease :android:lintRelease
./gradlew :android:connectedDebugAndroidTest
./gradlew :examples:desktop:run
./gradlew :examples:desktop:browserTest
```

The desktop example opens a dedicated browser profile and lets the user sign in
and check HTTP access to the current URL. It supplies the browser user-agent to
HTTP requests. JCEF downloads its pinned native bundle on first launch; engine
updates and platform packaging remain the host's release responsibility.

The maintained browser tests use a local HTTP fixture, never a real password.
For process-restart checks, run the desktop `browserTest` task four times with
`-PbrowserPhase=publish`, `restore`, `logout`, then `verifyLogout`. Android's
`WebViewCookieStoreTest#persistentCookieAndLogout` accepts the same `phase`
instrumentation argument; invoke each phase in a fresh instrumentation process.
Use the same test profile/application data for the sequence.

CI runs unit tests, Android builds/lint, an Android emulator round trip, and
embedded JCEF tests on Windows including separate-process restore/logout.
A consenting real-source login and additional OS acceptance remain separate gates;
compilation alone does not establish them. See
[validation](docs/validation.md) for recorded results and missing coverage.

## References

- [Android CookieManager](https://developer.android.com/reference/android/webkit/CookieManager)
- [Android cookie metadata API](https://developer.android.com/reference/kotlin/androidx/webkit/CookieManagerCompat)
- [JCEF Maven distribution and host requirements](https://github.com/jcefmaven/jcefmaven)
- [Chromium cookie protocol](https://chromedevtools.github.io/devtools-protocol/tot/Network/)

Licensed under Apache-2.0. JCEF/Chromium and transitive dependencies retain their
own licenses and distribution notices.

The JCEF adapter pins its native binding to version 152.0.6. A small class in
`org.cef.browser` registers an owned observer because the upstream convenience
client can lose fast replies before their command IDs arrive. It neither copies
nor replaces vendor classes. Use the ordinary classpath; a JPMS split-package
module path is not supported. Native Windows tests gate publication and any
engine upgrade must pass them again.
