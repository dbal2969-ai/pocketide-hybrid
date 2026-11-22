package dev.pocketide.app.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything the assistant ran in one chat: each command, when, how long, its exit code, and what it printed. Written as it
 * happens, so a failing run can be traced command by command. Keys are masked before anything is written.
 */
object Transcript {
    /** Longest output kept for one command. A longer one keeps its start and its end, with a note in between. */
    const val MAX_OUTPUT = 200_000

    fun cap(out: String, max: Int = MAX_OUTPUT): String {
        if (out.length <= max) return out
        val head = max * 3 / 5
        return out.take(head) + "\n… (${out.length - max} characters left out in the middle) …\n" + out.takeLast(max - head)
    }

    fun entry(started: Long, command: String, code: Int, output: String, seconds: Long): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(started))
        val body = cap(output.trimEnd())
        return ChatExport.redact("----- $stamp · exit $code · ${seconds}s\n\$ $command\n" + (if (body.isEmpty()) "(no output)" else body) + "\n\n")
    }
}
