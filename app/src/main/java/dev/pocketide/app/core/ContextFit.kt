package dev.pocketide.app.core

/**
 * Fits a request into a model's context window. A small window (32K, as on a self-hosted server) was overflowed by the
 * fixed history budget plus the system prompt and tool definitions ("Input length (33400) exceeds model's maximum
 * context length (32768)"). When the window is known, the history budget and the reply length are sized to it.
 */
object ContextFit {
    /** Characters per token, kept low on purpose: code and JSON pack fewer characters into a token than prose. */
    const val CHARS_PER_TOKEN = 3
    /** The history is never squeezed below this; a window too small even for that gets the length error instead. */
    const val MIN_BUDGET = 8_000

    /** Tokens kept free for the reply: a quarter of the window, at most [cap]. */
    fun replyTokens(contextTokens: Int, cap: Int): Int = minOf(cap, contextTokens / 4).coerceAtLeast(512)

    /** The smallest reply limit [replyFor] sends: room for a tool call or a short answer. */
    const val MIN_REPLY = 1024

    /**
     * The reply limit for one request: [cap] (0 sends none), lowered so the prompt and the reply fit a known window. Cloudflare
     * counts the requested reply against the window: "maximum context length is 24000 tokens. However, you requested 6000 output
     * tokens and your prompt contains at least 18001 input tokens" ended a Llama 3.3 run in 1.5.89. Never below [MIN_REPLY].
     */
    fun replyFor(cap: Int, contextTokens: Int?, promptChars: Int): Int {
        if (cap <= 0 || contextTokens == null || contextTokens <= 0) return cap
        val room = contextTokens - promptChars / CHARS_PER_TOKEN - 256
        return minOf(cap, room).coerceAtLeast(MIN_REPLY)
    }

    /**
     * The most characters the history may use when the window is known: half the window, at most [MAX_BUDGET]. Before 1.5.76 the
     * history was capped at HistoryTrim.BUDGET (90,000 characters, about 30K tokens) whatever the window, so a 262K model worked
     * from 30K tokens of history and lost what it had read a few rounds earlier. Prompt caching makes the longer history cheap.
     */
    const val MAX_BUDGET = 360_000

    /** Characters the conversation may use, after the system prompt, the tool definitions and the reply. */
    fun messageBudget(contextTokens: Int?, systemChars: Int, toolChars: Int, replyCap: Int): Int {
        if (contextTokens == null || contextTokens <= 0) return HistoryTrim.BUDGET
        val room = (contextTokens - replyTokens(contextTokens, replyCap)) * CHARS_PER_TOKEN - systemChars - toolChars
        val half = (contextTokens.toLong() * CHARS_PER_TOKEN / 2).toInt()
        return room.coerceIn(floor(contextTokens), minOf(MAX_BUDGET, maxOf(HistoryTrim.BUDGET, half)))
    }

    /**
     * The least history kept: [MIN_BUDGET], or a quarter of a small window (1.5.179). An 8K local server cannot hold 8,000
     * characters of history next to the prompt and the reply, and a shortened retry stopped at that floor and overflowed again.
     */
    fun floor(contextTokens: Int?): Int =
        if (contextTokens == null || contextTokens <= 0) MIN_BUDGET else minOf(MIN_BUDGET, contextTokens * CHARS_PER_TOKEN / 4)

    /** A tool result's cap when the window is unknown: three times the old fixed cap. */
    const val DEFAULT_RESULT_CAP = 24_000
    /** The most one tool result may use whatever the window: a whole 50 KB file fits with room to spare. */
    const val MAX_RESULT_CAP = 150_000

    /**
     * Characters one tool result (a file read, a search) may use: 15% of the window, never below the old 8,000 and never above
     * [MAX_RESULT_CAP]. A 262K model reads about 118,000 characters in one call; a 32K model about 14,000.
     */
    fun resultCap(contextTokens: Int?): Int =
        if (contextTokens == null || contextTokens <= 0) DEFAULT_RESULT_CAP
        else (contextTokens.toLong() * CHARS_PER_TOKEN * 15 / 100).toInt().coerceIn(Tools.MAX_RESULT, MAX_RESULT_CAP)

    /** Recent messages kept whole while shortening: fewer when the budget is small, or they alone overflow it. */
    fun keepLast(budget: Int): Int = if (budget < 40_000) 4 else if (budget < 70_000) 6 else HistoryTrim.KEEP_LAST

    private val TOO_LONG = Regex(
        """exceeds (?:the )?model'?s? maximum context length|maximum context length is|context[_ ]length[_ ]exceeded|""" +
            """prompt is too long|input is too long|too many tokens|input length \(?\d+\)? exceeds|reduce the length of the messages|""" +
            // Ollama and llama.cpp: "request (11831 tokens) exceeds the available context size (8192 tokens), try increasing it"
            """exceeds the available context size""",
        RegexOption.IGNORE_CASE)

    /** True when an error says the request did not fit the model's context. Such an error is never a refusal of tools. */
    fun isTooLong(message: String): Boolean = TOO_LONG.containsMatchIn(message)

    /** The window size an error names: vLLM "maximum context length (32768)", OpenAI-style "maximum context length is 8192 tokens". */
    fun limitFrom(message: String): Int? =
        Regex("""(?:maximum context length (?:is )?|available context size )\(?(\d{3,7})""", RegexOption.IGNORE_CASE).find(message)?.groupValues?.get(1)?.toIntOrNull()

    /** " · context 42% of 262K" for the working line (Cline and OpenCode show this too), or "" when the window is unknown. */
    fun usage(usedChars: Int, contextTokens: Int?): String {
        if (contextTokens == null || contextTokens <= 0) return ""
        val pct = (usedChars.toLong() / CHARS_PER_TOKEN * 100 / contextTokens).toInt().coerceIn(0, 999)
        val size = if (contextTokens >= 1000) "${contextTokens / 1000}K" else contextTokens.toString()
        return " · context $pct% of $size"
    }
}
