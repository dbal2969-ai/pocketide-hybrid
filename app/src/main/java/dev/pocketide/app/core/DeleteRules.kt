package dev.pocketide.app.core

/**
 * Rules for the assistant's delete_file tool (1.5.105): a test is not deleted unless the person asked to change tests, and after a
 * delete the assistant is told which files still load or link the deleted one, so a removed feature leaves no broken import behind.
 */
object DeleteRules {
    private val TEST = Regex("""(?i)(^|/)(tests?|__tests__|spec|specs)/|[._-](test|spec)\.[a-z]+$|(^|/)(test|spec)s?\.[a-z]+$|(^|/)test_[^/]+\.py$|_test\.(py|go)$|Test\.(kt|java)$""")
    private val CODE = Regex("""(?i)\.(?:js|mjs|cjs|jsx|ts|tsx|html?|css|py|json|kt|java|go|rb|php|vue|svelte)$""")
    private const val MAX_FILES = 400
    private const val MAX_CHARS = 400_000

    fun isTest(path: String): Boolean = TEST.containsMatchIn(path)

    /** Files that import, require, load or link [path] by its name (with or without its extension). */
    fun usedBy(path: String, others: Collection<String>, read: (String) -> String?): List<String> {
        val file = path.substringAfterLast('/')
        val stem = file.substringBeforeLast('.', file)
        if (stem.length < 2) return emptyList()
        // './coupons', '../src/coupons.js', "coupons.css", from coupons import x
        val ref = Regex("""(?<![\w$.-])""" + Regex.escape(stem) + """(?:\.[A-Za-z0-9]+)?["'`]|[/'"]""" + Regex.escape(file) + """\b|\bfrom\s+\.?""" + Regex.escape(stem) + """\s+import\b|\bimport\s+""" + Regex.escape(stem) + """\b""")
        return others.filter { CODE.containsMatchIn(it) && !it.contains("node_modules/") }.sorted().take(MAX_FILES).filter { p ->
            read(p)?.takeIf { it.length <= MAX_CHARS }?.let { t -> ref.containsMatchIn(t) } == true
        }
    }

    private val EXPORTS = listOf(
        Regex("""module\.exports\s*=\s*\{([^}]*)\}"""), Regex("""\bexport\s*\{([^}]*)\}"""))
    private val ONE_EXPORT = listOf(
        Regex("""\bexports\.([A-Za-z_$][\w$]*)\s*="""), Regex("""\bmodule\.exports\.([A-Za-z_$][\w$]*)\s*="""),
        Regex("""\bexport\s+(?:default\s+)?(?:async\s+)?(?:function\*?|class|const|let|var)\s+([A-Za-z_$][\w$]*)"""),
        Regex("""(?m)^(?:async\s+)?def\s+([A-Za-z_]\w*)|^class\s+([A-Za-z_]\w*)"""))

    /** The names [text] (a deleted file) gave other files: what it exports, or a Python module's top-level functions and classes. */
    fun exported(path: String, text: String): List<String> {
        val out = LinkedHashSet<String>()
        val py = path.endsWith(".py", true)
        if (!py) for (r in EXPORTS) r.findAll(text).forEach { m ->
            m.groupValues[1].split(',').map { it.trim().substringAfterLast(" as ").substringBefore(':').trim() }
                .filter { Regex("""[A-Za-z_$][\w$]*""").matches(it) && it != "default" }.forEach { out.add(it) }
        }
        for (r in ONE_EXPORT) {
            if (py != r.pattern.contains("def\\s")) continue
            r.findAll(text).forEach { m -> m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.let { out.add(it) } }
        }
        return out.filter { it.length >= 3 }.take(12)
    }

    /** Files that still use one of [names] (word match), with the names each uses. */
    fun stillUse(names: List<String>, others: Collection<String>, read: (String) -> String?): Map<String, List<String>> {
        if (names.isEmpty()) return emptyMap()
        val res = LinkedHashMap<String, List<String>>()
        for (p in others.filter { CODE.containsMatchIn(it) && !it.contains("node_modules/") }.sorted().take(MAX_FILES)) {
            val t = read(p)?.takeIf { it.length <= MAX_CHARS } ?: continue
            val hit = names.filter { n -> Regex("""(?<![\w$])""" + Regex.escape(n) + """(?![\w$])""").containsMatchIn(t) }
            if (hit.isNotEmpty()) res[p] = hit
        }
        return res
    }

    /** Added to "Deleted p.": what still loads the file or uses what it defined. */
    fun leftovers(loaders: List<String>, uses: Map<String, List<String>>): String {
        val all = LinkedHashMap<String, List<String>>()
        for (p in loaders) all[p] = emptyList()
        for ((p, n) in uses) all[p] = n
        if (all.isEmpty()) return ""
        return " These files still load it or use what it defined; update them so nothing is left of it: " +
            all.entries.take(8).joinToString(", ") { (p, n) -> if (n.isEmpty()) p else p + " (" + n.take(4).joinToString(", ") + ")" } + "."
    }
}
