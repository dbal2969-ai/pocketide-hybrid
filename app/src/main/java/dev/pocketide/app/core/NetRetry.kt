package dev.pocketide.app.core

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Calm handling of dropped connections: try again a couple of times with a short wait before showing an error. */
object NetRetry {
    /** Tries after the first one. */
    const val RETRIES = 2
    private val WAITS = intArrayOf(2, 6)

    /** True for a failure before any answer arrived (no connection, lost signal, a stalled start), which is safe to send again. */
    fun transient(e: Exception): Boolean =
        e is UnknownHostException || e is ConnectException || e is SocketTimeoutException ||
            (e is IOException && (e.message ?: "").let { it.contains("Unable to resolve", true) || it.contains("Connection reset", true) || it.contains("Software caused connection abort", true) || it.contains("unexpected end of stream", true) })

    /**
     * The server accepted the request, then sent nothing until the read timed out (Cloudflare's gpt-oss-120b, about 1 in 7 runs at
     * 1.5.88). When none of the answer has arrived yet, one more send is as safe as the retry before the answer starts.
     */
    const val STALL_RETRIES = 1

    fun stalledBeforeAnswer(e: Exception, nothingYet: Boolean, tries: Int): Boolean =
        e is SocketTimeoutException && nothingYet && tries < STALL_RETRIES

    const val STALL_STATUS = "No answer yet: asking again…"

    /**
     * The connection broke while the answer was arriving (haimaker's GLM 5.3 Flash: "Premature EOF" ended 3 of 34 benchmark runs,
     * 1.5.114). The partial answer has not been used yet, so the request is sent once more and the answer starts over.
     */
    const val BROKEN_RETRIES = 1
    fun brokenStream(e: Exception, tries: Int): Boolean = e !is SocketTimeoutException && e is IOException && tries < BROKEN_RETRIES &&
        (e.message ?: "").let { m -> listOf("Premature EOF", "unexpected end of stream", "Connection reset", "connection abort", "stream was reset", "closed").any { m.contains(it, true) } }

    const val BROKEN_STATUS = "The answer was cut off: asking again…"

    /** Seconds to wait before try number [attempt] (1 for the first retry). */
    fun waitSeconds(attempt: Int): Int = WAITS[(attempt - 1).coerceIn(0, WAITS.size - 1)]

    fun status(seconds: Int, attempt: Int): String = "Connection problem: trying again in ${seconds}s (try $attempt of $RETRIES)…"

    /** What the person reads when the failure is a timeout, as opposed to no connection. */
    fun timeoutMessage(provider: String): String =
        "$provider did not answer in time. It may be busy, or the request may be too large for this model. Tap Retry, or try another model or provider."

    fun offlineMessage(provider: String): String = "Could not reach $provider. Check your internet connection."
}
