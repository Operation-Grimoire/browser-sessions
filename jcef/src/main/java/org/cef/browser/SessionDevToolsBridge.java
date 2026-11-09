package org.cef.browser;

import io.grimoire.sessions.jcef.DevToolsCommandQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Narrow binding to the pinned JCEF native API. The upstream convenience client can
 * discard a reply before its command ID is delivered. No upstream classes are copied
 * or replaced: this adapter owns one observer and its pending futures.
 * Requires the ordinary JVM classpath, not a split-package module path.
 */
public final class SessionDevToolsBridge implements AutoCloseable {
    private final DevToolsCommandQueue queue;
    private final CefRegistration registration;
    private final AtomicBoolean closed = new AtomicBoolean();

    public SessionDevToolsBridge(CefBrowser browser) {
        if (!(browser instanceof CefBrowser_N)) {
            throw new IllegalArgumentException("A native JCEF browser is required");
        }
        CefBrowser_N nativeBrowser = (CefBrowser_N) browser;
        queue = new DevToolsCommandQueue(nativeBrowser::executeDevToolsMethod);
        registration = nativeBrowser.addDevToolsMessageObserver(new CefDevToolsMessageObserver() {
            @Override
            public void onDevToolsMethodResult(CefBrowser target, int id, boolean success, String json) {
                queue.received(id, success, json);
            }
            @Override
            public void onDevToolsEvent(CefBrowser target, String method, String parameters) {}
        });
        if (registration == null) {
            queue.close();
            throw new IllegalStateException("Browser DevTools observer is unavailable");
        }
    }

    public CompletableFuture<String> execute(String method, String parameters) {
        return queue.execute(method, parameters);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            queue.close();
            registration.dispose();
        }
    }
}
