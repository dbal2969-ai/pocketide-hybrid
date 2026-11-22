package dev.pocketide.app.core

/**
 * Ranks files by how much the rest of the project depends on them, like the repo map in Aider: an edge goes from a file
 * to each file that defines a name it uses, and a personalized PageRank spreads weight along the edges, starting from the
 * open file and the files the request is about. The best-ranked files are the ones worth showing first.
 */
object RepoRank {
    private val TOKEN = Regex("""[A-Za-z_][A-Za-z0-9_]{3,}""")
    const val MAX_FILES = 400
    const val MAX_FILE_CHARS = 200_000

    /** Rank per path (sums to 1). [defs] gives the names a file defines; [seeds] are the files to favour. */
    fun rank(texts: Map<String, String>, defs: (String, String) -> Collection<String>, seeds: Set<String> = emptySet()): Map<String, Double> {
        val files = texts.keys.sorted().take(MAX_FILES)
        if (files.isEmpty()) return emptyMap()
        val definers = HashMap<String, MutableSet<String>>()
        for (p in files) for (n in defs(p, texts.getValue(p))) if (n.length >= 4) definers.getOrPut(n) { HashSet() }.add(p)
        // a name defined in many files says little about any one of them
        definers.entries.removeAll { it.value.size > 3 }
        val out = HashMap<String, HashMap<String, Double>>()
        for (p in files) {
            val t = texts.getValue(p)
            if (t.length > MAX_FILE_CHARS) continue
            val counts = HashMap<String, Int>()
            for (m in TOKEN.findAll(t)) { val w = m.value; if (definers.containsKey(w)) counts[w] = (counts[w] ?: 0) + 1 }
            for ((w, c) in counts) for (d in definers.getValue(w)) if (d != p) {
                val row = out.getOrPut(p) { HashMap() }
                row[d] = (row[d] ?: 0.0) + Math.sqrt(c.toDouble())
            }
        }
        val n = files.size
        val seedSet = seeds.filter { it in texts }.toSet()
        val teleport = files.associateWith { if (seedSet.isEmpty()) 1.0 / n else if (it in seedSet) 1.0 / seedSet.size else 0.0 }
        var r = files.associateWith { 1.0 / n }
        repeat(25) {
            val next = HashMap<String, Double>()
            var dangling = 0.0
            for (p in files) {
                val row = out[p]
                val total = row?.values?.sum() ?: 0.0
                if (row == null || total == 0.0) { dangling += r.getValue(p); continue }
                for ((d, w) in row) next[d] = (next[d] ?: 0.0) + 0.85 * r.getValue(p) * w / total
            }
            r = files.associateWith { next[it].orEmpty() + 0.85 * dangling * teleport.getValue(it) + 0.15 * teleport.getValue(it) }
        }
        val sum = r.values.sum().takeIf { it > 0 } ?: 1.0
        return r.mapValues { it.value / sum }
    }

    private fun Double?.orEmpty(): Double = this ?: 0.0
}
