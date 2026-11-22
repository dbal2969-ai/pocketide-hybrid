package dev.pocketide.app

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import dev.pocketide.app.core.WorkingStatus

/** Keeps a small live line ("Reading app.js...  12s") updating under a reply while the assistant works. */
class WorkingTicker(private val view: TextView, private val announce: Boolean = true) {
    private val handler = Handler(Looper.getMainLooper())
    private val started = System.currentTimeMillis()
    private var phase = "Thinking"
    private var ticks = 0
    private var running = true

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            show()
            ticks++
            handler.postDelayed(this, 1000)
        }
    }

    init {
        view.visibility = View.VISIBLE
        // 1.5.133 (analysis U2): the timer changes every second, and each change was an accessibility event, so TalkBack (and
        // Android's UI tests) never saw the screen settle. The line is hidden from screen readers; phase changes are announced.
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        show()
        handler.postDelayed(tick, 1000)
    }

    private var chars = 0
    private var lastKind = ""
    private var lastSaid = 0L

    private fun show() {
        val t = WorkingStatus.line(phase, (System.currentTimeMillis() - started) / 1000, ticks, chars)
        if (view.text.toString() != t) view.text = t
    }

    /** How much of the reply has arrived (shown next to the timer). Updates on the next tick. */
    fun progress(received: Int) { chars = received }

    /** What the assistant is doing now. Safe to call from the main thread only. */
    fun phase(text: String) {
        if (!running) return
        val kind = WorkingStatus.kind(text)
        phase = text
        show()
        // 1.5.134: only a change of kind ("Reading" to "Running"), at most every 5 s, and through the window (a view marked not
        // important for accessibility may have its announcements dropped)
        val now = System.currentTimeMillis()
        // 1.5.135: Settings > Editor and layout can turn them off (Copilot, ANALYSIS-1.5.134)
        if (announce && kind != lastKind && now - lastSaid >= 5000) {
            lastKind = kind; lastSaid = now
            @Suppress("DEPRECATION")
            (view.rootView ?: view).announceForAccessibility(text)
        }
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        view.visibility = View.GONE
    }
}
