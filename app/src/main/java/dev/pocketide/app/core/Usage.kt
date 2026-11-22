package dev.pocketide.app.core

import java.util.Locale

/** Tokens used by one or more model requests. [input] is the part billed at the normal input rate; cached and cacheWrite are counted apart. */
data class Usage(val input: Long = 0, val output: Long = 0, val cached: Long = 0, val cacheWrite: Long = 0, val estimated: Boolean = false) {
    operator fun plus(o: Usage) = Usage(input + o.input, output + o.output, cached + o.cached, cacheWrite + o.cacheWrite, estimated || o.estimated)
    val prompt: Long get() = input + cached + cacheWrite
    val total: Long get() = prompt + output
    val isEmpty: Boolean get() = total == 0L
}

/** Reads token counts out of a streamed response, OpenAI-style or Anthropic-style. */
class UsageCollector(private val anthropic: Boolean) {
    private var input = 0L; private var output = 0L; private var cached = 0L; private var cacheWrite = 0L
    private var seen = false

    private fun num(x: Any?): Long = when (x) { is Number -> x.toLong(); is String -> x.toLongOrNull() ?: 0L; else -> 0L }

    /** Feeds one parsed event. */
    fun feed(ev: Map<String, Any?>) {
        if (anthropic) {
            val u = MiniJson.obj(MiniJson.obj(ev["message"])?.get("usage")) ?: MiniJson.obj(ev["usage"]) ?: return
            seen = true
            if (u.containsKey("input_tokens")) input = maxOf(input, num(u["input_tokens"]))
            if (u.containsKey("cache_read_input_tokens")) cached = maxOf(cached, num(u["cache_read_input_tokens"]))
            if (u.containsKey("cache_creation_input_tokens")) cacheWrite = maxOf(cacheWrite, num(u["cache_creation_input_tokens"]))
            if (u.containsKey("output_tokens")) output = maxOf(output, num(u["output_tokens"]))
        } else {
            val u = MiniJson.obj(ev["usage"]) ?: return
            val prompt = num(u["prompt_tokens"]); val out = num(u["completion_tokens"])
            if (prompt == 0L && out == 0L) return
            seen = true
            val c = num(MiniJson.obj(u["prompt_tokens_details"])?.get("cached_tokens"))
            cached = c; input = (prompt - c).coerceAtLeast(0); output = out
        }
    }

    val any: Boolean get() = seen
    fun result() = Usage(input, output, cached, cacheWrite)

    companion object {
        /** A rough count (about 4 characters a token) for servers that report none. */
        fun estimate(sentChars: Int, receivedChars: Int) = Usage((sentChars / 4).toLong(), (receivedChars / 4).toLong(), estimated = true)
    }
}

/** What a million tokens cost, set by the user. */
data class Price(val inPerM: Double, val outPerM: Double, val cachedPerM: Double = -1.0)

object Prices {
    fun cost(u: Usage, p: Price): Double {
        val cachedRate = if (p.cachedPerM >= 0) p.cachedPerM else p.inPerM * 0.1
        return (u.input * p.inPerM + u.cached * cachedRate + u.cacheWrite * p.inPerM * 1.25 + u.output * p.outPerM) / 1_000_000.0
    }

    fun parse(json: String): Map<String, Price> {
        if (json.isBlank()) return emptyMap()
        val o = try { MiniJson.obj(MiniJson.parse(json)) } catch (e: MiniJson.JsonError) { null } ?: return emptyMap()
        val out = LinkedHashMap<String, Price>()
        for ((k, v) in o) {
            val m = MiniJson.obj(v) ?: continue
            val i = (m["in"] as? Number)?.toDouble() ?: continue
            val ou = (m["out"] as? Number)?.toDouble() ?: continue
            out[k] = Price(i, ou, (m["cached"] as? Number)?.toDouble() ?: -1.0)
        }
        return out
    }

    fun write(m: Map<String, Price>): String = MiniJson.write(m.mapValues { (_, p) ->
        if (p.cachedPerM >= 0) mapOf("in" to p.inPerM, "out" to p.outPerM, "cached" to p.cachedPerM) else mapOf("in" to p.inPerM, "out" to p.outPerM)
    })

    fun key(provider: String, model: String) = "$provider/$model"
}

/** Totals per day and per provider/model, kept as JSON. */
object UsageLog {
    class Row(val day: String, val key: String, val usage: Usage, val requests: Int)

    fun parse(json: String): List<Row> {
        if (json.isBlank()) return emptyList()
        val l = try { MiniJson.list(MiniJson.parse(json)) } catch (e: MiniJson.JsonError) { null } ?: return emptyList()
        return l.mapNotNull { x ->
            val o = MiniJson.obj(x) ?: return@mapNotNull null
            fun n(k: String) = (o[k] as? Number)?.toLong() ?: 0L
            Row(o["day"] as? String ?: return@mapNotNull null, o["key"] as? String ?: return@mapNotNull null,
                Usage(n("in"), n("out"), n("cached"), n("write"), o["est"] == true), n("n").toInt())
        }
    }

    fun write(rows: List<Row>): String = MiniJson.write(rows.map {
        mapOf("day" to it.day, "key" to it.key, "in" to it.usage.input, "out" to it.usage.output, "cached" to it.usage.cached,
            "write" to it.usage.cacheWrite, "est" to it.usage.estimated, "n" to it.requests)
    })

    fun add(rows: List<Row>, day: String, key: String, u: Usage, keepDays: Int = 35): List<Row> {
        val old = rows.firstOrNull { it.day == day && it.key == key }
        val merged = Row(day, key, (old?.usage ?: Usage()) + u, (old?.requests ?: 0) + 1)
        val all = rows.filter { !(it.day == day && it.key == key) } + merged
        val days = all.map { it.day }.distinct().sortedDescending().take(keepDays).toSet()
        return all.filter { it.day in days }
    }

    fun forDays(rows: List<Row>, days: Collection<String>): List<Row> = rows.filter { it.day in days }
    fun total(rows: List<Row>): Usage = rows.fold(Usage()) { a, r -> a + r.usage }
}

/** Short readable numbers and amounts. */
object UsageText {
    fun tokens(n: Long): String = when {
        n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
        n >= 10_000 -> "${n / 1000}k"
        n >= 1_000 -> String.format(Locale.US, "%.1fk", n / 1000.0)
        else -> n.toString()
    }

    /** Share of the request's input that was read back from the cache (billed at about a tenth of the normal price). */
    fun cacheRate(u: Usage): Int = if (u.prompt <= 0) 0 else ((u.cached * 100) / u.prompt).toInt()

    /** True when a lot was sent but little came from the cache: caching is off, or the start of requests keeps changing. */
    fun lowCache(u: Usage): Boolean = u.prompt >= 200_000 && cacheRate(u) < 30

    fun money(d: Double): String = if (d < 0.01) String.format(Locale.US, "$%.4f", d) else String.format(Locale.US, "$%.2f", d)

    fun line(u: Usage, cost: Double?): String {
        val sb = StringBuilder("↑ ${tokens(u.prompt)} in")
        if (u.cached > 0) sb.append(" (${tokens(u.cached)} cached, ${cacheRate(u)}%)")
        if (u.cacheWrite > 0) sb.append(" · ${tokens(u.cacheWrite)} saved to cache")
        sb.append(" · ↓ ${tokens(u.output)} out")
        if (cost != null) sb.append(" · ≈ ${money(cost)}")
        if (u.estimated) sb.append(" · estimated")
        return sb.toString()
    }
}

/** Collects what the current exchange used, and hands finished requests to the app for saving. */
object UsageMeter {
    @Volatile var sink: ((String, String, Usage) -> Unit)? = null
    private var exchange = Usage()

    @Synchronized fun report(provider: String, model: String, u: Usage) {
        if (u.isEmpty) return
        exchange += u
        sink?.invoke(provider, model, u)
    }

    /** What this exchange has used so far; starts a fresh count. */
    @Synchronized fun take(): Usage { val u = exchange; exchange = Usage(); return u }
}
