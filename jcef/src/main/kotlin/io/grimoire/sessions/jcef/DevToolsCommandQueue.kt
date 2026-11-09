package io.grimoire.sessions.jcef

import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.function.BiFunction

/** Correlates native replies even when they arrive before the generated command ID. */
internal class DevToolsCommandQueue(
    private val send: BiFunction<String, String?, CompletableFuture<Int>>,
) : AutoCloseable {
    private data class Reply(val success: Boolean, val json: String)
    private val lock = Any()
    private val pending = mutableMapOf<Int, CompletableFuture<String>>()
    private val early = mutableMapOf<Int, Reply>()
    private val active = mutableSetOf<CompletableFuture<String>>()
    private var awaitingIds = 0
    private var closed = false

    fun execute(method: String, parameters: String?): CompletableFuture<String> {
        val result = CompletableFuture<String>()
        synchronized(lock) {
            if (closed) return result.apply { completeExceptionally(IOException("Browser command queue is closed")) }
            awaitingIds++
            active += result
        }
        val id = try { send.apply(method, parameters) } catch (failure: Exception) {
            CompletableFuture.failedFuture<Int>(failure)
        }
        id.whenComplete { number, failure ->
            val received = synchronized(lock) {
                awaitingIds--
                val reply = if (failure == null && !closed && !result.isDone) {
                    early.remove(number).also { if (it == null) pending[number] = result }
                } else null
                if (awaitingIds == 0) early.clear()
                reply
            }
            if (failure != null) result.completeExceptionally(IOException("Browser command failed"))
            else received?.let { deliver(result, it) }
        }
        result.whenComplete { _, _ ->
            synchronized(lock) {
                active -= result
                pending.entries.removeAll { it.value === result }
            }
            if (result.isCancelled) id.cancel(true)
        }
        return result
    }

    fun received(id: Int, success: Boolean, json: String) {
        val reply = Reply(success, json)
        val result = synchronized(lock) {
            if (closed) return
            pending.remove(id).also { waiting ->
                if (waiting == null && awaitingIds > 0) early[id] = reply
            }
        }
        result?.let { deliver(it, reply) }
    }

    private fun deliver(future: CompletableFuture<String>, reply: Reply) {
        if (reply.success) future.complete(reply.json)
        else future.completeExceptionally(IOException("Browser rejected a command"))
    }

    override fun close() {
        val outstanding = synchronized(lock) {
            if (closed) return
            closed = true
            pending.clear()
            early.clear()
            active.toList()
        }
        outstanding.forEach { it.cancel(true) }
    }
}
