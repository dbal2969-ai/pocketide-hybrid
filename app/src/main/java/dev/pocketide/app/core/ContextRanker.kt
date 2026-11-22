package dev.pocketide.app.core

// Builds the project part of a prompt within a size budget. Kotlin port of the PocketIDE web app code, which is a port of
// the author's MobileCoder project (context.py).

class ProjectFile(val path: String, val text: String?, val binary: Boolean)

object ContextRanker {
    private val WORD = Regex("[A-Za-z_][A-Za-z0-9_]{2,}")
    private val CAMEL = Regex("([a-z0-9])([A-Z])")
    private val SPLIT = Regex("[_\\W]+")
    private val DECL = Regex("""^\s*(?:export\s+)?(?:async\s+)?(?:def|class|function|interface|type|enum|struct|trait|fn|func|fun|object)\s+([A-Za-z_$][\w$]*)""", RegexOption.MULTILINE)
    private val VARDECL = Regex("""^\s*(?:export\s+)?(?:const|let|var|val)\s+([A-Za-z_$][\w$]*)\s*=""", RegexOption.MULTILINE)
    private val GOMETHOD = Regex("""^\s*func\s+\([^)]*\)\s*([A-Za-z_]\w*)""", RegexOption.MULTILINE)
    private val HTMLID = Regex("""\bid\s*=\s*["']([\w-]+)["']""")
    private val CSSCLASS = Regex("""(?:^|[\s,}])\.([A-Za-z_][\w-]*)\s*[{,:.\s]""", RegexOption.MULTILINE)

    fun keywords(s: String): Set<String> = WORD.findAll(s).map { it.value.lowercase() }.toSet()

    fun symTerms(name: String): List<String> =
        name.replace(CAMEL, "$1 $2").split(SPLIT).map { it.lowercase() }.filter { it.length >= 3 }

    fun symbols(path: String, text: String): List<String> {
        val names = HashSet<String>()
        fun grab(re: Regex) { re.findAll(text).forEach { names.add(it.groupValues[1]) } }
        grab(DECL); grab(VARDECL); grab(GOMETHOD)
        if (path.endsWith(".html", true) || path.endsWith(".htm", true)) grab(HTMLID)
        if (path.endsWith(".css", true)) grab(CSSCLASS)
        return names.sorted()
    }

    fun pathHits(path: String, kw: Set<String>): Int =
        WORD.findAll(path).map { it.value.lowercase() }.toSet().count { kw.contains(it) }

    private fun expand(kw: Set<String>): Set<String> {
        val out = HashSet(kw)
        kw.forEach { out.addAll(symTerms(it)) }
        return out
    }

    fun score(path: String, text: String, kw: Set<String>, kwx: Set<String>): Int {
        if (kw.isEmpty()) return 0
        val body = text.lowercase()
        val bodyHits = kw.count { body.contains(it) }
        val symHits = symbols(path, text).count { s -> symTerms(s).any { kwx.contains(it) } }
        return pathHits(path, kw) * 5 + symHits * 8 + bodyHits
    }

    /**
     * The open file comes first, other files are ranked by how well they match the request, and files that do not
     * fit are listed with their main names instead. In current-file mode, files named in the request are still added.
     */
    fun build(files: List<ProjectFile>, open: String?, query: String, limit: Int, everything: Boolean): String {
        var budget = if (limit > 0) limit else 60000
        val names = files.map { it.path }
        val sb = StringBuilder("Project files: ")
        sb.append(names.take(300).joinToString(", ") { n -> if (files.first { it.path == n }.binary) "$n (binary)" else n })
        if (names.size > 300) sb.append(", and ${names.size - 300} more")
        sb.append("\n")
        val kw = keywords(query)
        val kwx = expand(kw)
        val texts = files.filter { !it.binary && it.text != null }
        val byPath = texts.associate { it.path to it.text!! }
        val hasOpen = open != null && byPath.containsKey(open)
        // files the rest of the project depends on come first among equals (open file and files named by the request lead)
        val rank = try { RepoRank.rank(byPath, { p, t -> symbols(p, t) }, (listOfNotNull(open) + texts.map { it.path }.filter { pathHits(it, kw) > 0 }).toSet()) } catch (e: Exception) { emptyMap() }
        val rest = texts.map { it.path }.filter { it != open }
            .map { it to score(it, byPath[it]!!, kw, kwx) }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenByDescending { rank[it.first] ?: 0.0 }.thenBy { it.first })
            .map { it.first }
        val order = (if (hasOpen) listOf(open!!) else emptyList()) +
            (if (everything) rest else rest.filter { score(it, byPath[it]!!, kw, kwx) > 0 })
        val mapBudget = minOf(1500, (budget * 0.15).toInt())
        budget -= mapBudget
        val shown = HashSet<String>()
        val later = ArrayList<String>()
        val out = StringBuilder()
        for (p in order) {
            val c = byPath[p]!!
            if (c.length + 40 <= budget) {
                budget -= c.length + 40
                shown.add(p)
                out.append("\n--- ").append(p).append(" ---\n").append(c).append("\n")
            } else later.add(p)
        }
        if (later.isNotEmpty() && budget > 800) {
            val p = later[0]
            var cut = byPath[p]!!.take(budget - 160)
            val nl = cut.lastIndexOf('\n')
            if (nl > 0) cut = cut.substring(0, nl)
            out.append("\n--- ").append(p).append(" (first ").append(cut.split("\n").size)
                .append(" lines only, truncated; do not edit lines you cannot see) ---\n").append(cut).append("\n")
        }
        val rows = ArrayList<String>()
        texts.map { it.path }.filter { !shown.contains(it) }
            .sortedWith(compareByDescending<String> { score(it, byPath[it]!!, kw, kwx) }.thenByDescending { rank[it] ?: 0.0 }.thenBy { it })
            .forEach { p ->
                val syms = symbols(p, byPath[p]!!)
                    .sortedWith(compareByDescending<String> { s -> symTerms(s).any { kwx.contains(it) } }.thenBy { it.lowercase() })
                    .take(12)
                if (syms.isNotEmpty()) rows.add("$p: ${syms.joinToString(", ")}")
            }
        var map = rows.joinToString("\n")
        if (map.length > mapBudget) map = map.take(mapBudget).replace(Regex("\n[^\n]*$"), "")
        sb.append(out)
        if (map.isNotEmpty()) sb.append("\nOther files and what they define (not shown in full):\n").append(map).append("\n")
        return sb.toString()
    }
}
