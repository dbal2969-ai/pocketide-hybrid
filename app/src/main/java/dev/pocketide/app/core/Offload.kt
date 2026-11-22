package dev.pocketide.app.core

/** Slow work off the screen's thread, then the next step back on it (1.5.133). The next step runs even if the work fails. */
object Offload {
    fun run(work: () -> Unit, post: (Runnable) -> Unit, start: (Runnable) -> Unit = { Thread(it, "pocketide-offload").start() }, then: () -> Unit) {
        start(Runnable {
            try { work() } catch (t: Throwable) { AppLog.add("offload", t.javaClass.simpleName + ": " + (t.message ?: "")) }
            post(Runnable { then() })
        })
    }
}
