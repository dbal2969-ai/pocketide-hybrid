package dev.pocketide.app.core

/**
 * Which cloud Gemini model hybrid AI uses, and when it moves up to a stronger one (1.5.184).
 *
 * Gemini Nano on the phone stays first. Behind it are three cloud tiers from Firebase AI Logic, all usable on the free Spark plan
 * with the Gemini Developer API: Fast (Flash-Lite), Balanced (Flash) and Best (Gemini 4 Hydrogen, the strongest model that needs
 * no billing). In Auto, the cloud starts on Balanced and passes the request to Best when the work proves hard, the way Gemini Nano
 * passes it to the cloud: it says so in the chat, with the reason, and the rest of the request stays there. A model the project
 * cannot use (not available, or needs billing) is skipped for the session and the next one down answers.
 */
object CloudTier {
    enum class Tier(val id: String, val label: String, val model: String, val thinking: String) {
        FAST("fast", "Fast", "gemini-3.5-flash-lite", "low"),
        BALANCED("balanced", "Balanced", "gemini-3.8-flash", "medium"),
        BEST("best", "Best", "gemini-4-hydrogen", "high"),
    }

    enum class Choice(val id: String, val label: String, val about: String) {
        AUTO("auto", "Auto", "Balanced (Gemini 3.8 Flash), and Best (Gemini 4 Hydrogen) when the work gets hard"),
        FAST("fast", "Fast", "Gemini 3.5 Flash-Lite: quickest, for small edits and questions"),
        BALANCED("balanced", "Balanced", "Gemini 3.8 Flash for every cloud step"),
        BEST("best", "Best", "Gemini 4 Hydrogen with high thinking for every cloud step: strongest, slower");

        companion object { fun of(id: String?): Choice = values().firstOrNull { it.id == id } ?: AUTO }
    }

    /** Starts the line the cloud writes when it passes the rest of a request to the strongest model. */
    const val ESCALATE_MARK = "☁ Passing this to the strongest cloud model"

    /** The tier for this step, and why it moved up now (null = no move this step). */
    data class Pick(val tier: Tier, val why: String?)

    /** How many failed tool calls in a row, or failed checks in a request, move Auto up to Best. */
    const val FAIL_STREAK = 2
    const val FAILED_CHECKS = 2

    private val FAILED = Regex("""(?i)^\s*(NOT SAVED|Not saved|Not written|Edit not applied|Error\b|Could not|Failed|No such file|Unknown tool)""")
    private val CHECK_FAILED = Regex("""(?i)^\s*exit code [1-9]""")
    private val SENT_BACK = Regex("""(?i)\b(not finished|the last check failed|still fail|fails the same way|asking for a fix)\b""")

    /**
     * The tier for the next cloud step. [said]: the assistant's words so far in this request (to see an earlier move up);
     * [results]: the tool results in order; [notes]: the app's own messages to the model (finish checks sending it back);
     * [skip]: tiers this project cannot use; [pinned]: a model Remote Config pins (then tiers do not apply, see [model]).
     */
    fun pick(choice: Choice, said: List<String>, results: List<String>, notes: List<String>, skip: Set<Tier>): Pick {
        val fixed = when (choice) { Choice.FAST -> Tier.FAST; Choice.BALANCED -> Tier.BALANCED; Choice.BEST -> Tier.BEST; Choice.AUTO -> null }
        if (fixed != null) return Pick(usable(fixed, skip), null)
        if (said.any { it.contains(ESCALATE_MARK) }) return Pick(usable(Tier.BEST, skip), null)
        val why = hard(results, notes)
        if (why != null && Tier.BEST !in skip) return Pick(Tier.BEST, why)
        return Pick(usable(Tier.BALANCED, skip), null)
    }

    /** Why the request has proved hard for Balanced, or null. */
    fun hard(results: List<String>, notes: List<String>): String? {
        var streak = 0
        for (r in results.asReversed()) if (FAILED.containsMatchIn(r)) streak++ else break
        if (streak >= FAIL_STREAK) return "the last $streak tool calls failed"
        val checks = results.count { CHECK_FAILED.containsMatchIn(it) }
        if (checks >= FAILED_CHECKS) return "the check failed $checks times"
        if (notes.count { SENT_BACK.containsMatchIn(it) } >= 2) return "the app's checks sent the work back twice"
        return null
    }

    /** [t], or the next tier down that this project can use. */
    fun usable(t: Tier, skip: Set<Tier>): Tier {
        var x: Tier? = t
        while (x != null && x in skip) x = lower(x)
        return x ?: Tier.FAST
    }

    fun lower(t: Tier): Tier? = when (t) { Tier.BEST -> Tier.BALANCED; Tier.BALANCED -> Tier.FAST; Tier.FAST -> null }

    // 1.5.184 (hard run 3): "Role 'function' is not supported" matched a loose "is not supported" and Balanced was skipped for the
    // session; only errors about the model itself count now
    private val UNAVAILABLE = Regex("""(?i)(models?/\S+\s+(is\s+)?not\s+(found|supported|available)|model\s+\S*\s*(is\s+)?not\s+(found|available)|not\s+found\s+for\s+api|\bNOT_FOUND\b|\b404\b|Blaze|billing|pay-as-you-go|no\s+access\s+to\s+(the\s+)?model)""")
    private val THINKING = Regex("""(?i)thinking""")

    /** True when an error says this project cannot use the model (so the next tier down should answer). */
    fun unavailable(error: String): Boolean = UNAVAILABLE.containsMatchIn(error)

    /** True when an error is about the thinking setting (the request is sent again without it). */
    fun thinkingRefused(error: String): Boolean = THINKING.containsMatchIn(error) && Regex("""(?i)invalid|not supported|unsupported|unknown""").containsMatchIn(error)

    private val BUSY = Regex("""(?i)high\s+demand|overloaded|try\s+again\s+later|\b503\b|\b429\b|RESOURCE_EXHAUSTED|rate\s+limit|quota|temporarily|UNAVAILABLE""")

    private val QUOTA = Regex("""(?i)exceeded\s+your\s+current\s+quota|quota\s+exceeded|RESOURCE_EXHAUSTED|\b429\b|rate[-\s]limit""")

    /** How long a tier whose quota ran out is left alone before it is tried again. */
    const val QUOTA_COOLDOWN_MS = 10 * 60_000L

    /**
     * True when the free tier's request quota for this model ran out (hard run 5: "You exceeded your current quota, please check your
     * plan and billing details"). It refills, so the tier cools down for [QUOTA_COOLDOWN_MS] instead of being dropped for the session.
     */
    fun quota(error: String): Boolean = QUOTA.containsMatchIn(error)

    /** True when the model is busy or rate-limited for now (it is not marked unusable; a lower tier answers this step). */
    fun busy(error: String): Boolean = BUSY.containsMatchIn(error) && !Regex("""(?i)not\s+found|billing|Blaze""").containsMatchIn(error)

    /** True when the reply was cut off at its token limit (it is asked again with a larger one). */
    fun cutOff(error: String): Boolean = Regex("""(?i)MAX_TOKENS""").containsMatchIn(error)

    /** The line shown when the cloud passes the rest of a request to Best. */
    fun escalation(model: String, why: String): String = "$ESCALATE_MARK ($model). Why: $why."
}
