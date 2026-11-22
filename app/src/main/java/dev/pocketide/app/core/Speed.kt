package dev.pocketide.app.core

/**
 * Rules for the speed options: phase routing (a cheap model for reading rounds), the slow-reply offer and the long-chat offer.
 * Kept free of Android classes so the rules can be tested.
 */
object Speed {
    /** Cheaper models that failed to answer in this session (provider/model): they are not tried again until the app restarts. */
    private val failedCheap = java.util.Collections.synchronizedSet(HashSet<String>())
    fun markCheapFailed(provider: String, model: String) { failedCheap.add("$provider/$model") }
    fun cheapFailed(provider: String, model: String): Boolean = "$provider/$model" in failedCheap

    /** Seconds without any sign of an answer before the person is offered a retry or another provider. */
    const val SLOW_SECS = 60

    /** A chat this long resends a lot with every request; a fresh chat with a summary is offered. */
    const val LONG_CHAT_CHARS = 60_000
    const val LONG_CHAT_MESSAGES = 40

    /** Tools that only look: a round made of these only is exploring, which a cheaper model can do. */
    val READ_ONLY = setOf("read_file", "list_files", "search", "find_code", "find_references", "repo_map", "find_symbol", "file_outline", "doc_outline", "web_search", "web_fetch", "project_audit")

    /** True when the model's tool calls this round only read. A round with no calls (a final answer) is not one. */
    fun readOnlyRound(names: List<String>): Boolean = names.isNotEmpty() && names.all { it in READ_ONLY }

    /**
     * A cheaper model may take a reading round only when it speaks the same message format as the main model (the history
     * is already written in that format), supports tools and has not refused them.
     */
    fun canRoute(slotAnthropic: Boolean, mainAnthropic: Boolean, supportsTools: Boolean, refusedTools: Boolean): Boolean =
        supportsTools && !refusedTools && slotAnthropic == mainAnthropic

    /** The first round has the person's request, and the answer after reading is the one that counts: both go to the main model. */
    fun routeThisRound(enabled: Boolean, round: Int, lastRoundReadOnly: Boolean, wrapping: Boolean): Boolean =
        enabled && round >= 1 && lastRoundReadOnly && !wrapping

    /** What the cheap model answered is kept only when it was a plain reading round; anything else is done again by the main model. */
    fun keepCheapTurn(names: List<String>): Boolean = readOnlyRound(names)

    /** True when a reply has shown no sign of life for long enough to offer a retry. A model on the phone is slow by nature and is left alone. */
    fun slow(secs: Long, sawAnything: Boolean, local: Boolean): Boolean = !sawAnything && !local && secs >= SLOW_SECS

    fun longChat(chars: Int, messages: Int): Boolean = chars >= LONG_CHAT_CHARS || messages >= LONG_CHAT_MESSAGES
}
