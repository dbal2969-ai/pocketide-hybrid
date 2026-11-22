package dev.pocketide.app.core

/**
 * Evidence that a step built a stand-in instead of the real thing (1.5.164; research handoff §9). Two kinds:
 *  - stand-in markers in a changed file's comments or error messages ("placeholder", "stub", "not implemented", "skeleton" ...);
 *  - files written by hand into a folder that one of the project's scripts downloads with `git clone`, while no clone is there. In the
 *    2009scape field run, setup.sh cloned the server into server/, and a step wrote a 15-file stand-in into server/ instead.
 */
object Placeholders {
    private val COMMENT = Regex("""^\s*(//|#|/\*|\*|<!--|--)""")
    private val MARK = Regex("""\b(placeholder|stub(bed)?|not (yet )?implemented|implement (this|me|later)|skeleton|dummy implementation|fake implementation|minimal (stand-in|version))\b""", RegexOption.IGNORE_CASE)
    private val THROWS = Regex("""(NotImplementedError|NotImplementedException|UnsupportedOperationException\("[^"]*not implemented|throw new Error\(['"][^'"]*not (yet )?(implemented|written))""", RegexOption.IGNORE_CASE)

    /** "path:line: text" for each stand-in marker in [text], at most [max]. */
    private val NOT_CODE = Regex("""(^|/)(tests?|__tests__|__mocks__|mocks?|spec|docs?)/|\.(spec|test)\.[a-z]+$|\.(md|txt|rst|adoc)$""", RegexOption.IGNORE_CASE)

    /** Tests, mocks and docs talk about stubs on purpose (Copilot N3, PR #27). */
    fun exempt(path: String): Boolean = NOT_CODE.containsMatchIn(path)

    fun markers(path: String, text: String, max: Int = 3): List<String> = if (exempt(path)) emptyList() else text.lines().withIndex()
        .filter { (_, l) -> (COMMENT.containsMatchIn(l) && MARK.containsMatchIn(l)) || THROWS.containsMatchIn(l) }
        .take(max).map { (i, l) -> "$path:${i + 1}: ${l.trim().take(100)}" }

    private val CLONE = Regex("""git\s+clone\s+(?:(?:--?[\w-]+(?:[ =]\d+)?)\s+)*["']?([^\s"']+?)(?:\.git)?["']?(?:\s+["']?([\w./-]+?)["']?)?\s*(?:$|[;&|)\n])""", RegexOption.MULTILINE)

    /** Folders the project's shell scripts clone into: (folder, script). */
    fun cloneTargets(files: List<String>, read: (String) -> String?): List<Pair<String, String>> =
        // top-level and scripts/ shell files, and Makefiles (Copilot N4, PR #27)
        files.filter { (it.endsWith(".sh") && (!it.contains('/') || it.startsWith("scripts/") || it.startsWith("tools/") || it.startsWith("bin/"))) ||
            it == "Makefile" || it == "makefile" }.flatMap { s ->
            CLONE.findAll(read(s).orEmpty()).map { m ->
                val dest = m.groupValues[2].ifEmpty { m.groupValues[1].substringAfterLast('/') }.trim('/').removePrefix("./")
                dest to s
            }.filter { it.first.isNotEmpty() && !it.first.startsWith("$") }.toList()
        }.distinct()

    /** Problems for a finished step: stand-in markers in [changed], and hand-written files where a script should have cloned. */
    fun problems(changed: List<String>, files: List<String>, read: (String) -> String?, isRepo: (String) -> Boolean): List<String> {
        val out = ArrayList<String>()
        for ((dir, script) in cloneTargets(files, read)) {
            val written = changed.filter { it.startsWith("$dir/") }
            if (written.isNotEmpty() && !isRepo(dir))
                out.add("$script downloads $dir/ with git clone, but $dir/ is not a clone: this step wrote ${written.size} file(s) there by hand " +
                    "(${written.take(3).joinToString(", ")}). Run the script to get the real thing instead of writing a stand-in.")
        }
        for (p in changed) out.addAll(markers(p, read(p) ?: continue).map { "Stand-in left in the code: $it" })
        return out.take(6)
    }
}
