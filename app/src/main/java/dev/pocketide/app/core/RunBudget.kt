package dev.pocketide.app.core

/**
 * A spending limit for one request: dollars (when the model's price is known) and tokens (always counted, so free
 * and unpriced models are covered too). The monthly limits in [SpendBudget] protect the account; this one stops a single
 * request from running for a hundred rounds. It is checked after each model reply, so it can go over by one reply.
 * A limit of 0 means off.
 */
class RunBudget(maxUsd: Double, maxTokens: Long) {
    var maxUsd: Double = maxUsd; private set
    var maxTokens: Long = maxTokens; private set
    var usd = 0.0; private set
    var tokens = 0L; private set
    /** True when some usage had no known price, so the dollar figure is a lower bound. */
    var unpriced = false; private set
    private val stepUsd = maxUsd
    private val stepTokens = maxTokens

    fun add(u: Usage, price: Price?) {
        if (u.isEmpty) return
        tokens += u.total
        if (price != null) usd += Prices.cost(u, price) else unpriced = true
    }

    /** Why the request must pause now, or null while it is within its limit. */
    fun over(): String? = when {
        maxUsd > 0 && usd >= maxUsd -> "it has spent ${money(usd)} (limit ${money(maxUsd)})"
        maxTokens > 0 && tokens >= maxTokens -> "it has used ${count(tokens)} tokens (limit ${count(maxTokens)})"
        else -> null
    }

    /** The person chose to continue: allow one more step of the same size. */
    fun extend() {
        if (stepUsd > 0) maxUsd = maxOf(maxUsd, usd) + stepUsd
        if (stepTokens > 0) maxTokens = maxOf(maxTokens, tokens) + stepTokens
    }

    val isOff: Boolean get() = maxUsd <= 0 && maxTokens <= 0

    /** Short meter for the live status line: "$0.42 of $1.00" or "310k of 2.0M tokens". */
    fun meter(): String = when {
        maxUsd > 0 && !(unpriced && usd == 0.0) -> money(usd) + (if (unpriced) "+" else "") + " of " + money(maxUsd)
        maxTokens > 0 -> count(tokens) + " of " + count(maxTokens) + " tokens"
        else -> ""
    }

    companion object {
        const val DEFAULT_USD = 1.0
        const val DEFAULT_TOKENS = 3_000_000L

        fun money(d: Double): String = if (d < 10) String.format(java.util.Locale.US, "$%.2f", d) else String.format(java.util.Locale.US, "$%.0f", d)

        fun count(n: Long): String = when {
            n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0)
            n >= 1_000 -> "${n / 1000}k"
            else -> n.toString()
        }

        /** Reads what the person typed for the dollar limit: "1", "$2.50", "0" or "off". Null when unreadable. */
        fun parseUsd(s: String): Double? {
            val t = s.trim().removePrefix("$").trim().lowercase()
            if (t == "off" || t.isEmpty()) return 0.0
            return t.toDoubleOrNull()?.takeIf { it >= 0 && it < 10_000 }
        }

        /** Reads a token limit: "3000000", "3M", "500k", "0" or "off". Null when unreadable. */
        fun parseTokens(s: String): Long? {
            val t = s.trim().lowercase().replace(",", "").replace("_", "")
            if (t == "off" || t.isEmpty()) return 0L
            val mul = when { t.endsWith("m") -> 1_000_000.0; t.endsWith("k") -> 1_000.0; else -> 1.0 }
            val n = t.trimEnd('m', 'k').trim().toDoubleOrNull() ?: return null
            if (n < 0) return null
            return (n * mul).toLong()
        }
    }
}

/**
 * List prices for Anthropic models (platform.claude.com pricing page, checked 2026-10-22), used only when the person has not
 * entered a price, so spend limits and cost lines still work. Matched on the model id; the newest version names come first.
 */
object KnownPrices {
    private val ANTHROPIC = listOf(
        Regex("fable-5-1|mythos-5-1") to Price(10.0, 50.0, 0.25),
        Regex("fable|mythos") to Price(10.0, 50.0, 1.0),
        Regex("opus-5-5") to Price(4.0, 20.0, 0.20),
        Regex("opus-4-[5-9]|opus-5") to Price(5.0, 25.0, 0.50),
        Regex("opus") to Price(15.0, 75.0, 1.50),
        Regex("sonnet-5-5") to Price(2.0, 10.0, 0.10),
        Regex("sonnet-5") to Price(2.0, 10.0, 0.20),
        Regex("sonnet") to Price(3.0, 15.0, 0.30),
        Regex("haiku-5") to Price(0.10, 0.50, 0.01),
        Regex("haiku-4") to Price(1.0, 5.0, 0.10),
        Regex("haiku") to Price(0.80, 4.0, 0.08)
    )

    fun find(provider: String, model: String): Price? {
        val m = model.lowercase()
        if (provider == "anthropic" || m.startsWith("claude") || m.contains("/claude"))
            ANTHROPIC.firstOrNull { it.first.containsMatchIn(m) }?.second?.let { return it }
        // otherwise the price the provider publishes in its model list (OpenRouter lists one for every model)
        val f = ModelFacts.get(provider, model) ?: return null
        if (f.free == true) return Price(0.0, 0.0)
        val i = f.inPerM ?: return null
        val o = f.outPerM ?: return null
        return Price(i, o)
    }
}
