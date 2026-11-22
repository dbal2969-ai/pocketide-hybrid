package dev.pocketide.app.core

/**
 * A map of the project for the assistant: the files whose definitions the rest of the project uses most, each with its main
 * names, sized to a token budget. It works by pattern matching on common languages and ranks files with PageRank over how often
 * one file uses another file's names, so it needs no native code. A real parser can replace the patterns later for accuracy.
 */
object RepoMap {
    const val MAX_FILE_CHARS = 200_000
    const val PER_FILE_NAMES = 12
    const val DEFAULT_TOKENS = 1024
    private const val DAMPING = 0.85
    private const val ITERATIONS = 30
    private val SKIP = setOf("node_modules", "build", "dist", "cache", "vendor", ".git", "_ref", "cache-export")
    private val WORD = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
    private val STOP = setOf("if", "for", "while", "return", "new", "this", "self", "const", "let", "var", "class", "function", "def", "fun", "val", "the", "and", "not")

    private val JS = listOf(
        Regex("""(?m)^\s*(?:export\s+(?:default\s+)?)?(?:async\s+)?function\s*\*?\s*([A-Za-z_$][\w$]*)"""),
        Regex("""(?m)^\s*(?:export\s+(?:default\s+)?)?(?:abstract\s+)?class\s+([A-Za-z_$][\w$]*)"""),
        Regex("""(?m)^\s*(?:export\s+)?(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*(?:async\s*)?(?:\([^)]*\)|[A-Za-z_$][\w$]*)\s*=>"""),
    )
    private val PY = listOf(Regex("""(?m)^\s*def\s+([A-Za-z_]\w*)\s*\("""), Regex("""(?m)^\s*class\s+([A-Za-z_]\w*)"""))
    private val JVM = listOf(
        Regex("""(?m)^\s*(?:(?:public|private|internal|protected|open|data|abstract|override|suspend|inline|static|final|sealed|enum)\s+)*(?:fun|object|interface|class)\s+(?:<[^>]*>\s*)?([A-Za-z_]\w*)"""),
    )

    /** The definitions in one file, by its extension. */
    fun names(path: String, text: String): List<String> {
        TreeSitter.definitions(path, text)?.let { return it }
        val patterns = when (path.substringAfterLast('.', "").lowercase()) {
            "js", "jsx", "ts", "tsx", "mjs", "cjs" -> JS
            "py" -> PY
            "kt", "kts", "java" -> JVM
            else -> return emptyList()
        }
        val out = LinkedHashSet<String>()
        for (p in patterns) for (m in p.findAll(text)) m.groupValues[1].takeIf { it.length >= 2 && it !in STOP }?.let { out.add(it) }
        return out.toList()
    }

    /** The map text. [files] maps project paths to their text. Empty when no file has any definitions. */
    /** Most characters the map reads at once. The phone's heap is small, and a project can be much bigger than that. */
    const val MAX_TOTAL_CHARS = 4_000_000

    /**
     * Reads the files the map needs, bounded: each file cut to [MAX_FILE_CHARS], and at most [MAX_TOTAL_CHARS] in all.
     * Files past the budget are left out of the map (the first files in [paths] win).
     */
    fun gather(paths: Collection<String>, read: (String) -> String?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var total = 0
        // code first, then the rest; before 1.5.115 the first paths won, so in a game with 1,759 map files under game/data the
        // 4 MB went on map data and main.js and every file after "data" were left out of the map and the ranked search
        val (code, other) = paths.filter { p -> p.split('/').none { it in SKIP } }.partition { it.substringAfterLast('.', "").lowercase() in CODE }
        for (p in code + other) {
            val t = read(p) ?: continue
            if (p !in code && t.length > DATA_MAX && p.substringAfterLast('.', "").lowercase() in DATA) continue   // big data files say nothing about the code
            val cut = t.take(MAX_FILE_CHARS)
            if (total + cut.length > MAX_TOTAL_CHARS) { if (p in code) continue else break }
            out[p] = cut
            total += cut.length
        }
        return out
    }

    private val CODE = setOf("js", "jsx", "ts", "tsx", "mjs", "cjs", "py", "kt", "kts", "java", "go", "rs", "rb", "php", "c", "cc", "cpp", "h", "hpp", "cs", "swift", "lua", "vue", "svelte", "html", "htm", "css", "sh", "dart")
    private val DATA = setOf("json", "csv", "tsv", "xml", "txt", "log", "ndjson", "jsonl", "geojson", "svg")
    /** Data files bigger than this are not read for the map or the ranked search. */
    const val DATA_MAX = 64_000

    /**
     * Files changed in at least two of the recorded turns ([changeSets], newest first), most often first; ties go to the most
     * recent. They are where work is happening, so they are worth reading first.
     */
    fun hot(changeSets: List<List<String>>, limit: Int = 8): List<String> {
        val count = LinkedHashMap<String, Int>()
        for (set in changeSets) for (p in set.toSet()) count[p] = (count[p] ?: 0) + 1
        return count.entries.filter { it.value >= 2 }.sortedByDescending { it.value }.take(limit).map { it.key }
    }

    /** Lines added under the map: the files changed most often lately and the command that tests the project. */
    fun notes(hot: List<String>, testCommand: String?): String {
        val b = StringBuilder()
        if (hot.isNotEmpty()) b.append("\nChanged most often lately: ").append(hot.joinToString(", "))
        if (!testCommand.isNullOrBlank()) b.append("\nTest command: ").append(testCommand)
        return b.toString()
    }

    fun build(files: Map<String, String>, tokenBudget: Int = DEFAULT_TOKENS): String {
        val keep = files.filterKeys { p -> p.split('/').none { it in SKIP } }.mapValues { it.value.take(MAX_FILE_CHARS) }
        val defs = keep.mapValues { (p, t) -> names(p, t).take(PER_FILE_NAMES * 4) }.filterValues { it.isNotEmpty() }
        if (defs.isEmpty()) return ""
        // which file defines each name (a name defined twice counts for both files)
        val owners = HashMap<String, MutableList<String>>()
        for ((p, ns) in defs) for (n in ns) owners.getOrPut(n) { ArrayList() }.add(p)
        // how often each file uses each word
        val uses = keep.mapValues { (_, t) -> HashMap<String, Int>().also { m -> for (w in WORD.findAll(t)) m.merge(w.value, 1, Int::plus) } }
        // edges: a file that uses a name defined in another file points at that file
        val edges = HashMap<String, HashMap<String, Double>>()
        for ((from, counts) in uses) for ((word, n) in counts) {
            val targets = owners[word] ?: continue
            for (to in targets) if (to != from) edges.getOrPut(from) { HashMap() }.merge(to, n.toDouble() / targets.size, Double::plus)
        }
        val nodes = keep.keys.toList()
        val n = nodes.size.toDouble()
        var rank = nodes.associateWith { 1.0 / n }
        val out = nodes.associateWith { edges[it]?.values?.sum() ?: 0.0 }
        repeat(ITERATIONS) {
            val dangling = nodes.filter { out[it] == 0.0 }.sumOf { rank[it]!! }
            val next = HashMap<String, Double>()
            for (node in nodes) next[node] = (1 - DAMPING) / n + DAMPING * dangling / n
            for ((from, targets) in edges) {
                val total = out[from] ?: continue
                if (total == 0.0) continue
                for ((to, w) in targets) next[to] = next[to]!! + DAMPING * rank[from]!! * w / total
            }
            rank = next
        }
        val budgetChars = tokenBudget * 4
        val sb = StringBuilder("Project map (files other files use most first; names are the main definitions):\n")
        for (p in defs.keys.sortedByDescending { rank[it] ?: 0.0 }) {
            val line = "- $p: " + defs[p]!!.take(PER_FILE_NAMES).joinToString(", ") + "\n"
            if (sb.length + line.length > budgetChars) break
            sb.append(line)
        }
        return sb.toString()
    }
}
