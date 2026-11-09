package io.grimoire.sessions.jcef

import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DevToolsCommandQueueTest {
    @Test fun `a synchronous result is retained before the command ID arrives`() {
        lateinit var queue: DevToolsCommandQueue
        queue = DevToolsCommandQueue { _, _ ->
            queue.received(1, true, "reply")
            CompletableFuture.completedFuture(1)
        }
        repeat(100) { assertEquals("reply", queue.execute("method", null).get(1, TimeUnit.SECONDS)) }
        queue.close()
    }

    @Test fun `a result can also arrive after its ID and unrelated results do not consume it`() {
        val queue = DevToolsCommandQueue { _, _ -> CompletableFuture.completedFuture(7) }
        val result = queue.execute("method", null)
        queue.received(99, true, "other")
        assertFalse(result.isDone)
        queue.received(7, true, "expected")
        assertEquals("expected", result.get(1, TimeUnit.SECONDS))
        queue.close()
    }

    @Test fun `cancellation before ID completion and close release waiting requests`() {
        val id = CompletableFuture<Int>()
        val queue = DevToolsCommandQueue { _, _ -> id }
        val result = queue.execute("method", null)
        result.cancel(true)
        assertTrue(id.isCancelled)
        queue.close()
        assertTrue(queue.execute("method", null).isCompletedExceptionally)
        val open = DevToolsCommandQueue { _, _ -> CompletableFuture.completedFuture(2) }
        val waiting = open.execute("method", null)
        open.close()
        assertTrue(waiting.isCancelled)
    }
}
