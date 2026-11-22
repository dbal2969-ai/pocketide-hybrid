package dev.pocketide.app.core

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The assistant's own record for one chat: every tool call it made (name, arguments, what came back, round, time taken) and
 * the start and end of each request. It goes into the chat's transcript file, between the commands, so the whole run reads in
 * order. Keys are masked before anything is written.
 */
object ToolTrace {
    const val MAX_ARGS = 4_000
    const val MAX_RESULT = 8_000

    /** The chat's transcript file while a request runs; null between requests. */
    @Volatile var target: File? = null

    fun write(text: String) {
        try { target?.appendText(SecretMask.mask(text)) } catch (e: Exception) { }
    }

    fun cap(s: String, max: Int): String = if (s.length <= max) s else s.take(max) + "… (${s.length - max} characters left out)"

    private fun stamp(t: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(t))

    fun call(started: Long, round: Int, name: String, args: String, result: String, ms: Long): String =
        ChatExport.redact("----- ${stamp(started)} · round $round · tool $name · ${ms}ms\n" +
            cap(args.trim(), MAX_ARGS) + "\n→ " + cap(result.trim(), MAX_RESULT) + "\n\n")

    fun event(message: String, time: Long = System.currentTimeMillis()): String =
        ChatExport.redact("===== ${stamp(time)} · $message\n\n")

    /** The assistant's own words in a round, kept in full up to the call limit. */
    fun block(kind: String, text: String, round: Int, time: Long = System.currentTimeMillis()): String =
        ChatExport.redact("----- ${stamp(time)} · round $round · $kind\n" + cap(text, MAX_RESULT) + "\n\n")
}
