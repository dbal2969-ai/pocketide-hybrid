package dev.pocketide.app.core

/** Reads "rate limit reached, try again in 14.8s" answers so the app can wait and retry by itself. */
object RateLimit {
    /** Longest wait that is worth doing automatically; per-minute limits reset within this. */
    const val MAX_WAIT = 60
    /** Tries after the first one. */
    const val RETRIES = 3
    private val CAPACITY_WAITS = intArrayOf(8, 15, 25)
    private val PER_MIN_WAITS = intArrayOf(20, 40, 60)
    private val PER_MIN = Regex("""(?i)per[- ]?min(ute)?\b""")
    private val UPSTREAM = Regex("""(?i)provider returned error""")
    private val BUSY_CODES = setOf(502, 503, 504, 529)
    private val INTERNAL = Regex("""(?i)\b(?:unknown )?internal (?:server )?error\b""")
    private val BUSY = Regex("""(?i)temporar\w*|unavailable|overload\w*|busy|bad gateway|gateway time-?out|try again later""")

    private val MS = Regex("""(?:try again|retry) (?:in|after)\s+(\d+(?:\.\d+)?)\s*ms""", RegexOption.IGNORE_CASE)
    private val CLOCK = Regex("""(?:try again|retry) (?:in|after)\s+(?:(\d+)h)?\s*(?:(\d+)m(?!s))?\s*(?:(\d+(?:\.\d+)?)s)?""", RegexOption.IGNORE_CASE)
    private val PLAIN = Regex("""(?:try again|retry) (?:in|after)\s+(\d+(?:\.\d+)?)\s*seconds?""", RegexOption.IGNORE_CASE)

    /** Seconds to wait before trying again, or null when this is not a short rate limit (a daily limit or a billing problem is shown as an error). */
    fun waitSeconds(code: Int, message: String, retryAfterHeader: String?, attempt: Int = 0): Int? {
        // a provider out of capacity (Cloudflare "Capacity temporarily exceeded", busy servers) clears on its own: wait a little longer each time
        if ((code == 429 || code == 503) && message.contains("capacity", true)) return CAPACITY_WAITS[attempt.coerceIn(0, CAPACITY_WAITS.size - 1)]
        // a gateway whose upstream is briefly down (LLM7 "Upstream provider is temporarily unavailable" 503, Anthropic's 529
        // "overloaded", a bad-gateway page): ended a benchmark run on its first request, and clears on its own like capacity
        // 502, 504 and 529 never come from the request itself; a gateway's bare "error code: 502" (haimaker, 1.5.111) has none of
        // the words above and ended a benchmark run, so these are retried whatever the text says
        if (code in BUSY_CODES && (code != 503 || BUSY.containsMatchIn(message))) return CAPACITY_WAITS[attempt.coerceIn(0, CAPACITY_WAITS.size - 1)]
        // Cloudflare Workers AI answers "AiError: Internal server error (<id>)" (500) or "Unknown internal error" (503) now and then:
        // half of glm-4.7-flash's hard-suite runs ended on one at their first request (1.5.109), and a retry gets through
        if ((code == 500 || code == 503) && INTERNAL.containsMatchIn(message)) return CAPACITY_WAITS[attempt.coerceIn(0, CAPACITY_WAITS.size - 1)]
        if (code != 429) return null
        // 1.5.170: OpenRouter's free tier, "Rate limit exceeded: free-models-per-min" with no time in it, ended benchmark runs mid-task;
        // a per-minute limit clears within a minute. An upstream provider's 429 behind a gateway ("Provider returned error") is busy
        // capacity. A daily limit ("free-models-per-day") is not waited for. These only apply when the answer gives no time of its
        // own: Groq's "tokens per minute ... try again in 14.8s" waits the time it states.
        val secs: Double = retryAfterHeader?.trim()?.toDoubleOrNull()
            ?: MS.find(message)?.groupValues?.get(1)?.toDoubleOrNull()?.div(1000.0)
            ?: PLAIN.find(message)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: clock(message)
            ?: return when {
                PER_MIN.containsMatchIn(message) -> PER_MIN_WAITS[attempt.coerceIn(0, PER_MIN_WAITS.size - 1)]
                UPSTREAM.containsMatchIn(message) -> CAPACITY_WAITS[attempt.coerceIn(0, CAPACITY_WAITS.size - 1)]
                else -> null
            }
        val whole = Math.ceil(secs).toInt() + 1  // one second of margin
        return if (whole in 1..MAX_WAIT) whole else null
    }

    private fun clock(message: String): Double? {
        val m = CLOCK.find(message) ?: return null
        val h = m.groupValues[1].toDoubleOrNull()
        val min = m.groupValues[2].toDoubleOrNull()
        val s = m.groupValues[3].toDoubleOrNull()
        if (h == null && min == null && s == null) return null
        return (h ?: 0.0) * 3600 + (min ?: 0.0) * 60 + (s ?: 0.0)
    }

    fun status(seconds: Int, attempt: Int): String = "Rate limit: retrying in ${seconds}s (try $attempt of ${RETRIES})…"
}
