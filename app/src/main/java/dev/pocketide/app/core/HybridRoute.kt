package dev.pocketide.app.core

/**
 * Hybrid AI routing beyond request length: requests Gemini Nano on the phone cannot do (anything that needs the web),
 * and Nano replies that only say it cannot do the task. Both go to the cloud in the on-phone-first mode.
 */
object HybridRoute {
    private val WEB = Regex("""(?i)\b(research|look\s+up|search\s+(the\s+)?(web|internet|online)|google\s+it|browse|on\s+github|from\s+github|github\.com|latest|news|current\s+(versions?|prices?)|up[- ]to[- ]date)\b|https?://""")
    private val REFUSAL = Regex("""(?i)\b(I\s+(cannot|can't|can\s+not|am\s+(unable|not\s+able)\s+to|do\s+not\s+have\s+(the\s+ability|access)|don't\s+have\s+(the\s+ability|access))|I'm\s+(unable|not\s+able)\s+to|as\s+an\s+AI\b|I'm\s+(just\s+)?a\s+language\s+model)""")

    /** Why [request] needs the cloud, or null. */
    fun needsCloud(request: String): String? =
        if (WEB.containsMatchIn(request)) "this needs the web, which Gemini Nano on the phone cannot reach" else null

    /** True when the reply opens by saying it cannot do the task (only its first sentence is read). */
    fun refused(reply: String): Boolean =
        REFUSAL.containsMatchIn(reply.trim().split(Regex("""(?<=[.!?])\s+|\n""")).firstOrNull().orEmpty().take(240))
}
