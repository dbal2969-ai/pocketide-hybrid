package dev.pocketide.app.core

/** Helpers for choosing a model: hiding models that cannot chat, searching long lists, and fixing a mistyped name. */
object ModelPick {
    private val NOT_CHAT = Regex("""(whisper|tts|orpheus|guard|embed|moderation|rerank|transcrib|dall-e|speech|audio-|-audio|image-gen|imagen|veo|lyria)""", RegexOption.IGNORE_CASE)

    /** Models that can answer a chat message (speech, safety and embedding models are left out). */
    fun chatModels(all: List<String>): List<String> = all.filter { !NOT_CHAT.containsMatchIn(it) }

    /** Entries containing every word of the query, ignoring case. */
    fun filter(all: List<String>, query: String): List<String> {
        val terms = query.trim().lowercase().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return all
        return all.filter { m -> val l = m.lowercase(); terms.all { l.contains(it) } }
    }

    /** True when an error reply means the model name is wrong or retired. */
    fun looksMissing(message: String): Boolean {
        val m = message.lowercase()
        if (!(m.contains("http 404") || m.contains("http 400") || m.contains("http 422") || m.contains("not_found") || m.contains("model_not_found"))) return false
        return Regex("""(model|models/).{0,80}(does not exist|do not have access|not found|is not found|decommission|no longer supported|not supported|unknown|invalid)""").containsMatchIn(m) ||
            m.contains("no such model") || m.contains("model_not_found") || m.contains("unknown model")
    }

    private fun lev(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) { val t = d[j]; d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1); prev = t }
        }
        return d[b.length]
    }

    /** The best matches for a mistyped name, closest first: same name ignoring case, names that start with it, names that contain it, then near spellings. */
    fun closest(typed: String, list: List<String>, max: Int = 3): List<String> {
        val t = typed.trim().lowercase().removePrefix("models/")
        if (t.isEmpty()) return emptyList()
        val scored = list.mapNotNull { m ->
            val l = m.lowercase()
            val score = when {
                l == t -> 0
                l.startsWith(t) -> 1
                t.startsWith(l) -> 2
                l.contains(t) -> 3
                else -> { val d = lev(t, l.take(t.length + 4)); if (d <= 4) 4 + d else null }
            }
            score?.let { m to it }
        }
        return scored.sortedWith(compareBy({ it.second }, { it.first.length })).take(max).map { it.first }
    }

    /** Models that are known to be a good fit on a provider when nothing better is known; verified against the provider's own list when one is available. */
    fun suggestions(providerId: String): List<String> = when (providerId) {
        "groq" -> listOf("openai/gpt-oss-20b", "llama-3.3-70b-versatile", "openai/gpt-oss-120b")
        else -> emptyList()
    }
}

/** What a request costs a provider with a small per-minute limit, and how much to send. */
object Budget {
    class Limits(val contextChars: Int, val historyChars: Int, val historyMessages: Int, val attachChars: Int, val tight: Boolean)

    /** Normal limits for cloud models. */
    val NORMAL = Limits(20000, 12000, 12, 30000, false)

    /** For providers whose free tier allows only a few thousand tokens a minute (Groq's free tier is 8,000). */
    val TIGHT = Limits(6000, 2500, 4, 4000, true)

    private val TPM = Regex("""tokens per minute[^:]*:\s*Limit\s+(\d+)""", RegexOption.IGNORE_CASE)

    /** The per-minute token limit named in a rate-limit error, or null. */
    fun tpmLimit(message: String): Int? = TPM.find(message)?.groupValues?.get(1)?.toIntOrNull()

    private val OTPM = Regex("""output tokens per minute[^:]*:\s*Limit\s+(\d+)""", RegexOption.IGNORE_CASE)

    /** The per-minute OUTPUT token limit named in a rate-limit error (Groq's OTPM), or null. */
    fun otpmLimit(message: String): Int? = OTPM.find(message)?.groupValues?.get(1)?.toIntOrNull()

    /** How many tokens one reply may ask for under that limit: half of it, so two requests fit in a minute. Never below 256. */
    fun outputCapFor(limit: Int): Int = (limit / 2).coerceAtLeast(256)

    /** True when an error names a per-minute limit small enough that large requests will keep failing. */
    fun learnsTight(message: String): Boolean = (tpmLimit(message) ?: Int.MAX_VALUE) <= 20000

    fun limits(tight: Boolean, local: Boolean, localContextChars: Int = 6000): Limits =
        if (local) Limits(localContextChars, 6000, 8, 5000, false) else if (tight) TIGHT else NORMAL
}

/** A saved choice of provider and model, such as "Cheap fast" or "Smart slow". */
data class ModelProfile(val name: String, val provider: String, val model: String, val base: String = "")

object Profiles {
    fun parse(json: String): List<ModelProfile> {
        if (json.isBlank()) return emptyList()
        val l = try { MiniJson.list(MiniJson.parse(json)) } catch (e: MiniJson.JsonError) { null } ?: return emptyList()
        return l.mapNotNull { x ->
            val o = MiniJson.obj(x) ?: return@mapNotNull null
            val n = (o["name"] as? String)?.trim().orEmpty(); val p = (o["provider"] as? String).orEmpty()
            if (n.isEmpty() || p.isEmpty()) null else ModelProfile(n, p, (o["model"] as? String).orEmpty(), (o["base"] as? String).orEmpty())
        }
    }

    fun write(list: List<ModelProfile>): String =
        MiniJson.write(list.map { mapOf("name" to it.name, "provider" to it.provider, "model" to it.model, "base" to it.base) })

    /** Adds or replaces (by name, ignoring case); at most 12 are kept. */
    fun upsert(list: List<ModelProfile>, p: ModelProfile): List<ModelProfile> =
        (list.filter { !it.name.equals(p.name, true) } + p).takeLast(12)

    fun remove(list: List<ModelProfile>, name: String): List<ModelProfile> = list.filter { !it.name.equals(name, true) }

    /** A short chip label for the current model: the part after the last slash, at most 18 characters. */
    fun shortName(model: String, fallback: String): String {
        val m = model.ifBlank { fallback }.substringAfterLast('/')
        return if (m.length <= 18) m else m.take(17) + "…"
    }
}
