package dev.pocketide.app.core

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Saves chats on one background thread, in order (1.5.136, Copilot's review of 1.5.135): masking a long chat
 * (SecretMask.maskJson) and writing it took place on the main thread on every save. The JSON is taken on the caller's thread,
 * so the saved chat is the chat as it was at that moment; masking and writing happen here. A newer save of the same file
 * replaces one still waiting, so a burst of saves writes once.
 */
object ChatWriter {
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "chat-writer").also { it.isDaemon = true } }
    private val waiting = HashMap<String, String>()

    fun save(file: File, json: String, after: () -> Unit = {}) {
        val key = file.absolutePath
        val queued = synchronized(waiting) { val had = waiting.containsKey(key); waiting[key] = json; had }
        if (queued) return
        pool.execute {
            val latest = synchronized(waiting) { waiting.remove(key) } ?: return@execute
            try { SafeWrite.write(file, SecretMask.maskJson(latest)) } catch (e: Exception) { }
            try { after() } catch (e: Exception) { }
        }
    }

    /** Waits until every save so far is written (tests, and before the app reads the files back). */
    fun flush(timeoutMs: Long = 10000) {
        val done = java.util.concurrent.CountDownLatch(1)
        pool.execute { done.countDown() }
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
    }
}
