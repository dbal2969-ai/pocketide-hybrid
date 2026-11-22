package dev.pocketide.app.core

/**
 * What is known about how models actually do in PocketIDE, next to what providers say about them (ModelFacts) (1.5.128).
 *
 * Model suggestions were scored only from the providers' own descriptions, so a model OpenRouter describes as free, agentic and
 * tool-using (thinkingmachines/inkling:free) was suggested over Kimi K2.6, the model PocketIDE has measured working, again after
 * every restart, and after it had failed for the owner. Two kinds of evidence now count:
 *  - MEASURED: models that did well in PocketIDE's own benchmarks (BENCHMARKS.md), matched by name on any provider;
 *  - this phone's own record: how each model's requests ended here (finished, or unfinished or failed).
 * A model with neither is "unproven"; it is never suggested over a model that is proven. A model that failed twice here and never
 * finished is not suggested at all, nor one the person dismissed. A suggestion is shown at most once a week per model.
 */
object ModelTrack {
    /** Name fragments of models measured in BENCHMARKS.md (hard, held-out or real-project suites), with what was measured. */
    val MEASURED: List<Pair<String, String>> = listOf(
        "kimi-k2.6" to "PocketIDE's benchmarks: 11/12 held-out tasks, most real-project tasks",
        "claude-haiku-5-5" to "PocketIDE's benchmarks: 31/32 hard tasks, 12/12 held-out",
        "glm-5.3-flash" to "PocketIDE's benchmarks: 32/32 hard tasks, 12/12 held-out",
        "deepseek-v4.1-flash" to "PocketIDE's benchmarks: 15/16 hard tasks, 12/12 held-out",
        "gpt-5-mini" to "PocketIDE's benchmarks: 14/16 hard tasks, 12/12 held-out",
        // 1.5.175, the free-model survey (no key or a free key): the best of the free models measured
        "flint-v1.0" to "PocketIDE's free-model survey: 27/29 tasks (standard, hard, held-out)",
    )

    const val WEEK_MS = 7L * 24 * 3600 * 1000

    data class Record(val ok: Int = 0, val failed: Int = 0, val suggestedAt: Long = 0, val dismissed: Boolean = false)

    private val records = LinkedHashMap<String, Record>()
    @Volatile var onChange: ((String) -> Unit)? = null

    private fun key(provider: String, id: String) = "$provider/$id"

    fun measured(id: String): String? = id.lowercase().let { low -> MEASURED.firstOrNull { low.contains(it.first) }?.second }

    @Synchronized fun get(provider: String, id: String): Record = records[key(provider, id)] ?: Record()

    /** How a request with this model ended here: finished, or unfinished / failed. */
    fun outcome(provider: String, id: String, finished: Boolean) {
        if (provider.isBlank() || id.isBlank()) return
        update(provider, id) { if (finished) it.copy(ok = it.ok + 1) else it.copy(failed = it.failed + 1) }
    }

    fun suggested(provider: String, id: String, now: Long) = update(provider, id) { it.copy(suggestedAt = now) }
    fun dismiss(provider: String, id: String) = update(provider, id) { it.copy(dismissed = true) }

    private fun update(provider: String, id: String, f: (Record) -> Record) {
        synchronized(this) { records[key(provider, id)] = f(get(provider, id)) }
        onChange?.invoke(encode())
    }

    /** Proven: measured in the benchmarks, or finished at least twice here and more often than it failed. */
    fun proven(provider: String, id: String): Boolean {
        if (measured(id) != null) return true
        val r = get(provider, id)
        return r.ok >= 2 && r.ok > r.failed
    }

    /** Never suggested: dismissed, or failed twice here without ever finishing. */
    fun excluded(provider: String, id: String): Boolean {
        val r = get(provider, id)
        return r.dismissed || (r.failed >= 2 && r.ok == 0)
    }

    /** Points added to a model's score: measured +4, each finish here +2 (up to 6), each failure here -3 (up to -9). */
    fun adjust(provider: String, id: String): Int {
        val r = get(provider, id)
        return (if (measured(id) != null) 4 else 0) + minOf(r.ok * 2, 6) - minOf(r.failed * 3, 9)
    }

    fun recentlySuggested(provider: String, id: String, now: Long): Boolean = now - get(provider, id).suggestedAt < WEEK_MS

    /** One line on what is known, for the suggestion note. */
    fun evidence(provider: String, id: String): String {
        val r = get(provider, id)
        val here = if (r.ok + r.failed == 0) "" else "on this phone: ${r.ok} finished, ${r.failed} unfinished or failed"
        return listOfNotNull(measured(id), here.ifEmpty { null }).joinToString("; ")
    }

    // ---- kept between runs ----

    @Synchronized fun encode(): String = MiniJson.write(records.map { (k, r) ->
        linkedMapOf("k" to k, "o" to r.ok, "f" to r.failed, "s" to r.suggestedAt, "d" to r.dismissed)
    })

    @Synchronized fun load(json: String?) {
        records.clear()
        val l = try { MiniJson.parse(json.orEmpty()) as? List<*> } catch (e: Exception) { null } ?: return
        for (x in l) {
            val o = MiniJson.obj(x) ?: continue
            val k = o["k"] as? String ?: continue
            records[k] = Record((o["o"] as? Number)?.toInt() ?: 0, (o["f"] as? Number)?.toInt() ?: 0, (o["s"] as? Number)?.toLong() ?: 0,
                o["d"] as? Boolean ?: false)
        }
    }

    @Synchronized fun clearForTest() { records.clear(); onChange = null }
}
