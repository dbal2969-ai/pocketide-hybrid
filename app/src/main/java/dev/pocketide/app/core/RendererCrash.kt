package dev.pocketide.app.core

/**
 * When a page crashes the browser's renderer (1.5.133, analysis C2). Android's WebView kills the whole app in that case unless the
 * app says it handled it, and canvas games on phones run out of memory often enough for that to matter: the app and any
 * assistant run went with the page. Now the visible preview gets a new browser and loads again; the hidden checks report it.
 */
object RendererCrash {
    const val RESTARTED = "The preview crashed (the page used too much memory or hit a browser bug) and was restarted."
    const val STOPPED = "The preview crashed again within a minute, so it was not loaded again. Fix the page, then tap Reload."
    const val IN_CHECK = "The page crashed the browser while it was being checked (it used too much memory or hit a browser bug)."

    fun lookReport(errors: List<String>): String =
        "Page check: the page crashed the browser before it could be looked at, so there is no report or screenshot. " +
            "This usually means it used too much memory (a very large canvas, an endless loop that allocates, huge images)." +
            (if (errors.isEmpty()) "" else "\nErrors before the crash:\n" + errors.take(8).joinToString("\n"))

    /** Reload after a crash, but not when the page crashed twice within [windowMs]: it would only crash again. */
    class Restarts(private val windowMs: Long = 60_000L) {
        private var last = Long.MIN_VALUE
        fun reload(now: Long): Boolean {
            val again = last != Long.MIN_VALUE && now - last < windowMs
            last = now
            return !again
        }
    }
}
