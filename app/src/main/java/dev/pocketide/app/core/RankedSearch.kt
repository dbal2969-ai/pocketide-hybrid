package dev.pocketide.app.core

import kotlin.math.ln

/**
 * Finds code by what it does, from plain words ("where is the player's health lowered"), when the exact text is unknown.
 * The project is cut into overlapping chunks of lines, words are taken from identifiers (loadUser and load_user both give load,
 * user), and chunks are ranked with BM25, with a boost for chunks that define a matching name and a small penalty for test
 * files. The idea comes from Semble (keyword ranking plus a code model); this keeps the keyword half, so it needs no model and
 * runs anywhere. Pure logic.
 */
object RankedSearch {
    const val CHUNK_LINES = 40
    const val STEP = 20
    private const val K1 = 1.2
    private const val B = 0.75
    private const val MAX_FILE_CHARS = 200_000

    class Hit(val path: String, val start: Int, val end: Int, val score: Double, val lines: List<Pair<Int, String>>)

    private class Chunk(val path: String, val start: Int, val end: Int, val terms: Map<String, Int>, val length: Int, val defines: Set<String>, val test: Boolean)

    private val WORD = Regex("""[A-Za-z][A-Za-z0-9]*""")
    private val CAMEL = Regex("""(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])""")
    private val DEFINE = Regex("""\b(?:function|class|def|fun|func|interface|struct|enum|type|const|let|var|val)\s+([A-Za-z_$][\w$]*)|([A-Za-z_$][\w$]*)\s*[:=]\s*(?:async\s*)?(?:function\b|\([^)]*\)\s*=>)""")
    private val STOP = setOf("the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "is", "are", "it", "this", "that", "with", "where", "what",
        "how", "which", "when", "does", "do", "be", "by", "as", "at", "from", "if", "else", "return", "new", "var", "let", "const", "function", "true", "false",
        "null", "undefined", "import", "export", "default", "class", "public", "private", "static", "void", "int", "string", "get", "set", "code", "file")

    /** Lower-case words from text, identifiers split at camelCase and underscores, with a light plural/tense trim. */
    fun words(text: String): List<String> {
        val out = ArrayList<String>()
        for (m in WORD.findAll(text)) for (part in m.value.split(CAMEL)) {
            val w = stem(part.lowercase())
            if (w.length >= 2 && w !in STOP) out.add(w)
        }
        return out
    }

    private fun stem(w: String): String = when {
        w.length > 5 && w.endsWith("ing") -> w.dropLast(3)
        w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
        w.length > 4 && w.endsWith("ed") -> w.dropLast(2)
        w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
        else -> w
    }

    private fun isTest(path: String): Boolean {
        val p = path.lowercase()
        return p.contains("/test/") || p.contains("/tests/") || p.contains("__tests__") || p.startsWith("test/") || p.startsWith("tests/") ||
            Regex("""(^|/)(test_[^/]+|[^/]+[._-](test|spec)\.[a-z]+)$""").containsMatchIn(p)
    }

    /** The best [limit] chunks for [query] across [files] (path to text). Empty when nothing matches. */
    fun search(query: String, files: Map<String, String>, limit: Int = 8): List<Hit> {
        val q = words(query).distinct()
        if (q.isEmpty()) return emptyList()
        val chunks = ArrayList<Chunk>()
        val texts = HashMap<String, List<String>>()
        for ((path, raw) in files) {
            if (raw.length > MAX_FILE_CHARS) continue
            val lines = raw.split('\n')
            texts[path] = lines
            var s = 0
            while (true) {
                val e = minOf(lines.size, s + CHUNK_LINES)
                val body = lines.subList(s, e).joinToString("\n")
                val ws = words(body) + words(path.substringAfterLast('/'))
                if (ws.isNotEmpty()) {
                    val tf = HashMap<String, Int>(); for (w in ws) tf[w] = (tf[w] ?: 0) + 1
                    val defs = HashSet<String>()
                    for (m in DEFINE.findAll(body)) words(m.groupValues[1].ifEmpty { m.groupValues[2] }).forEach { defs.add(it) }
                    chunks.add(Chunk(path, s + 1, e, tf, ws.size, defs, isTest(path)))
                }
                if (e >= lines.size) break
                s += STEP
            }
        }
        if (chunks.isEmpty()) return emptyList()
        val n = chunks.size.toDouble()
        val avg = chunks.sumOf { it.length }.toDouble() / n
        val df = HashMap<String, Int>()
        for (c in chunks) for (w in q) if (c.terms.containsKey(w)) df[w] = (df[w] ?: 0) + 1
        val wantsTests = q.any { it == "test" || it == "spec" }
        val scored = chunks.mapNotNull { c ->
            var score = 0.0
            var matched = 0
            for (w in q) {
                val f = c.terms[w] ?: continue
                matched++
                val idf = ln(1.0 + (n - (df[w] ?: 0) + 0.5) / ((df[w] ?: 0) + 0.5))
                score += idf * (f * (K1 + 1)) / (f + K1 * (1 - B + B * c.length / avg))
            }
            if (matched == 0) return@mapNotNull null
            score *= 1.0 + 0.25 * (matched - 1)                 // more of the query's words in one place
            if (q.any { it in c.defines }) score *= 1.5         // the chunk defines something the query names
            if (c.test && !wantsTests) score *= 0.7
            c to score
        }.sortedByDescending { it.second }
        // one hit per overlapping stretch of a file: keep the better of two chunks that overlap
        val taken = ArrayList<Pair<Chunk, Double>>()
        for (pair in scored) {
            val c = pair.first
            if (taken.any { it.first.path == c.path && c.start <= it.first.end && it.first.start <= c.end }) continue
            taken.add(pair)
            if (taken.size >= limit) break
        }
        return taken.map { (c, score) ->
            val lines = texts[c.path]!!
            val best = (c.start..c.end).map { i -> i to lines[i - 1] }
                .map { (i, l) -> Triple(i, l, words(l).count { it in q }) }
                .filter { it.third > 0 }.sortedByDescending { it.third }.take(3).sortedBy { it.first }
                .map { it.first to it.second.trim().take(160) }
            Hit(c.path, c.start, c.end, score, best)
        }
    }

    /** The words of a search pattern (regex signs and quotes dropped), for ranking when the exact text is not found; "" when none. */
    fun queryOf(pattern: String): String =
        pattern.replace(Regex("""\\[bBwWsSdD]|[^A-Za-z0-9_]+"""), " ").trim().replace(Regex("\\s+"), " ").takeIf { words(it).isNotEmpty() }.orEmpty()

    /** The places closest to a search that found nothing, at most three, or "" when there are none. */
    fun near(hits: List<Hit>): String {
        val top = hits.take(3)
        if (top.isEmpty()) return ""
        return "\n\nThe places closest to it (read them before searching for another name):\n" + top.joinToString("\n") { h ->
            "- ${h.path}:${h.start}-${h.end}" + (h.lines.firstOrNull()?.let { (i, l) -> "  ($i: $l)" } ?: "")
        }
    }

    /** The hits as the assistant reads them. */
    fun format(query: String, hits: List<Hit>): String =
        if (hits.isEmpty()) "Nothing in the project matches \"$query\". Try other words, or search for exact text with search."
        else "Best matches for \"$query\" (read the lines around them with read_file start=… end=…):\n" + hits.joinToString("\n") { h ->
            "- ${h.path}:${h.start}-${h.end}\n" + h.lines.joinToString("\n") { (i, l) -> "    ${i}: $l" }
        }
}
