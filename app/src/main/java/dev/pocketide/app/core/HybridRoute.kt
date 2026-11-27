package dev.pocketide.app.core

/**
 * Hybrid AI routing beyond request length: requests Gemini Nano on the phone cannot do (anything that needs the web),
 * and Nano replies that only say it cannot do the task. Both go to the cloud in the on-phone-first mode.
 */
object HybridRoute {
    private val WEB = Regex("""(?i)\b(research|look\s+up|search\s+(the\s+)?(web|internet|online)|google\s+it|browse|on\s+github|from\s+github|latest|news|current\s+(versions?|prices?)|up[- ]to[- ]date)\b""")
    private val URL = Regex("""(?i)https?://|\bgithub\.com\b""")
    /** Asking for a page itself: open, read, fetch or summarise a link. */
    private val USE_URL = Regex("""(?i)\b(open|read|fetch|visit|go\s+to|look\s+at|summari[sz]e|check\s+out|download|clone|what('s|\s+is)\s+(on|at))\b[^\n]{0,80}(https?://|github\.com)""")

    /**
     * The person's own words in [request]: its first paragraph, at most 400 characters. 1.5.204 (Python run 1126-2032): every task
     * went to the cloud as "needs the web" for links in the pasted issue text (github.com/sponsors/…), after Nano had already fixed it.
     */
    fun ownWords(request: String): String = request.trim().split(Regex("""\n\s*\n""")).first().take(400)
    private val REFUSAL = Regex("""(?i)\b(I\s+(cannot|can't|can\s+not|am\s+(unable|not\s+able)\s+to|do\s+not\s+have\s+(the\s+ability|access)|don't\s+have\s+(the\s+ability|access))|I'm\s+(unable|not\s+able)\s+to|as\s+an\s+AI\b|I'm\s+(just\s+)?a\s+language\s+model)""")

    /** Why [request] needs the cloud, or null. */
    fun needsCloud(request: String): String? {
        val own = ownWords(request)
        val web = WEB.containsMatchIn(own) || USE_URL.containsMatchIn(request) || (request.length < 200 && URL.containsMatchIn(request))
        return if (web) "this needs the web, which Gemini Nano on the phone cannot reach" else null
    }

    /** True when the reply opens by saying it cannot do the task (only its first sentence is read). */
    fun refused(reply: String): Boolean =
        REFUSAL.containsMatchIn(reply.trim().split(Regex("""(?<=[.!?])\s+|\n""")).firstOrNull().orEmpty().take(240))
}
