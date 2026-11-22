package dev.pocketide.app.core

/**
 * Spending limits the person sets for each provider. A provider can have a dollar limit for the last 30 days,
 * a token limit for today, or both. Free providers usually run out of tokens, paid ones of money, so they differ.
 */
object SpendBudget {
    const val NEAR = 0.8

    data class Limit(val dollars: Double = 0.0, val tokensPerDay: Long = 0) {
        val isOff: Boolean get() = dollars <= 0.0 && tokensPerDay <= 0L
    }

    private fun providerOf(key: String) = key.substringBefore('/')

    fun spent(rows: List<UsageLog.Row>, prices: Map<String, Price>, provider: String): Double =
        rows.filter { providerOf(it.key) == provider }.sumOf { r -> prices[r.key]?.let { Prices.cost(r.usage, it) } ?: 0.0 }

    fun tokens(rows: List<UsageLog.Row>, provider: String): Long =
        rows.filter { providerOf(it.key) == provider }.sumOf { it.usage.total }

    /** 0 = fine or no limit, 1 = close, 2 = reached. */
    fun level(used: Double, limit: Double): Int = when {
        limit <= 0.0 -> 0
        used >= limit -> 2
        used >= limit * NEAR -> 1
        else -> 0
    }

    /** Warning lines for one provider. [last30] are the rows of the last 30 days, [today] those of today. */
    fun warnings(providerName: String, provider: String, limit: Limit, last30: List<UsageLog.Row>, today: List<UsageLog.Row>, prices: Map<String, Price>): List<String> {
        val out = ArrayList<String>()
        if (limit.dollars > 0.0) {
            val s = spent(last30, prices, provider)
            when (level(s, limit.dollars)) {
                2 -> out.add("$providerName budget reached: ${UsageText.money(s)} of ${UsageText.money(limit.dollars)} in the last 30 days.")
                1 -> out.add("$providerName budget: ${UsageText.money(s)} of ${UsageText.money(limit.dollars)} used in the last 30 days.")
            }
        }
        if (limit.tokensPerDay > 0L) {
            val t = tokens(today, provider)
            when (level(t.toDouble(), limit.tokensPerDay.toDouble())) {
                2 -> out.add("$providerName token limit reached for today: ${UsageText.tokens(t)} of ${UsageText.tokens(limit.tokensPerDay)}. Switch to a smaller model or provider, or raise the limit.")
                1 -> out.add("$providerName tokens today: ${UsageText.tokens(t)} of ${UsageText.tokens(limit.tokensPerDay)}.")
            }
        }
        return out
    }

    /** "25 200k" = $25 for 30 days and 200k tokens a day. "25" = dollars only, "- 200k" = tokens only, "0" or blank = off. Null if not understood. */
    fun parse(text: String): Limit? {
        val parts = text.trim().replace("$", "").split(Regex("[ ,/]+")).filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts == listOf("0")) return Limit()
        if (parts.size > 2) return null
        val d = if (parts[0] == "-") 0.0 else parts[0].toDoubleOrNull() ?: return null
        val t = if (parts.size < 2) 0L else tokenCount(parts[1]) ?: return null
        return if (d < 0) null else Limit(d, t)
    }

    fun tokenCount(s: String): Long? {
        val t = s.lowercase().trim()
        if (t == "-" || t == "0") return 0L
        val mult = when { t.endsWith("m") -> 1_000_000.0; t.endsWith("k") -> 1000.0; else -> 1.0 }
        val n = t.trimEnd('m', 'k').toDoubleOrNull() ?: return null
        return if (n < 0) null else (n * mult).toLong()
    }

    fun describe(l: Limit): String = when {
        l.isOff -> "off"
        else -> listOfNotNull(if (l.dollars > 0) UsageText.money(l.dollars) + " / 30 days" else null, if (l.tokensPerDay > 0) UsageText.tokens(l.tokensPerDay) + " tokens / day" else null).joinToString(" and ")
    }

    fun encode(m: Map<String, Limit>): String = MiniJson.write(m.filterValues { !it.isOff }.mapValues { mapOf("dollars" to it.value.dollars, "tokens" to it.value.tokensPerDay) })

    fun decode(json: String): Map<String, Limit> {
        if (json.isBlank()) return emptyMap()
        return try {
            (MiniJson.obj(MiniJson.parse(json)) ?: emptyMap()).mapNotNull { (k, v) ->
                val o = MiniJson.obj(v) ?: return@mapNotNull null
                k to Limit((o["dollars"] as? Number)?.toDouble() ?: 0.0, (o["tokens"] as? Number)?.toLong() ?: 0L)
            }.toMap()
        } catch (e: MiniJson.JsonError) { emptyMap() }
    }
}
