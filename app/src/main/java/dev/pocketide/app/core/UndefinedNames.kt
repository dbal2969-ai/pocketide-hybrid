package dev.pocketide.app.core

/**
 * Names a script reads but nothing defines (1.5.103). `node --check` and the syntax check only parse, so in a real project a line
 * `const cooked = FOOD[sid]?.cook` (FOOD defined nowhere) passed every check and made every inventory tap throw "FOOD is not
 * defined", and `await ensureNpcPic(id)` (defined nowhere) kept fishing spots from ever being drawn. The scope walk itself is
 * assets/check/names.js (run on Acorn's tree in the hidden browser, or under Node in the benchmark); this file reads its result
 * and drops what other files may define: classic scripts share globals, and window.x = … defines x everywhere.
 */
object UndefinedNames {
    data class Use(val name: String, val line: Int)

    private val JS = Regex("""(?i)\.(?:js|mjs|cjs)$""")
    private val CODE = Regex("""(?i)\.(?:js|mjs|cjs|html?)$""")
    private val SKIP = Regex("""(?i)(^|/)(node_modules|dist|build|vendor|\.git)/|\.min\.""")
    private val PAGE = Regex("""(?i)\.html?$""")
    private val SCRIPT_TAG = Regex("""(?i)<script\b([^>]*)>""")
    private val SRC = Regex("""(?i)\bsrc\s*=\s*["']?([^"'\s>]+)""")
    private val USE_LINE = Regex("""^([A-Za-z_$][\w$]*) (\d+)$""")
    private const val MAX_FILES = 400
    private const val MAX_CHARS = 400_000
    const val SHOWN = 5

    /** Scripts this check reads: JavaScript files that are not bundled libraries or build output. */
    fun checks(path: String): Boolean = JS.containsMatchIn(path) && !SKIP.containsMatchIn(path)

    /** Reads names.js's answer ("OK", "SKIP", or "name line" lines; possibly as a JSON string from evaluateJavascript). */
    fun parse(raw: String?): List<Use> {
        var s = raw?.trim() ?: return emptyList()
        if (s.startsWith("\"")) s = (try { MiniJson.parse(s) as? String } catch (e: Exception) { null }) ?: return emptyList()
        return s.lines().mapNotNull { l -> USE_LINE.matchEntire(l.trim())?.let { Use(it.groupValues[1], it.groupValues[2].toInt()) } }
    }

    private fun word(name: String) = Regex("""(?<![\w$])""" + Regex.escape(name) + """(?![\w$])""")
    private fun onGlobal(name: String) =
        Regex("""\b(?:window|globalThis|self)\s*(?:\.\s*""" + Regex.escape(name) + """(?![\w$])|\[\s*['"]""" + Regex.escape(name) + """['"]\s*\])""")
    private fun declares(name: String) =
        Regex("""(?<![\w$.])(?:const|let|var|function\s*\*?|class)\s+""" + Regex.escape(name) + """(?![\w$])|(?<![\w$.])""" + Regex.escape(name) + """\s*=\s*(?:async\s+)?(?:function\b|\()""")

    /** A page loads a classic script this check does not read (from the web, a library file, a bundle): its globals are unknown. */
    fun unseenGlobals(paths: Collection<String>, text: (String) -> String?): Boolean =
        paths.filter { PAGE.containsMatchIn(it) && !SKIP.containsMatchIn(it) }.take(MAX_FILES).any { p ->
            val t = text(p)?.takeIf { it.length <= MAX_CHARS } ?: return@any false
            SCRIPT_TAG.findAll(t).any { m ->
                val attrs = m.groupValues[1]
                val src = SRC.find(attrs)?.groupValues?.get(1) ?: return@any false
                !Regex("""(?i)\btype\s*=\s*["']?module""").containsMatchIn(attrs) &&
                    (src.contains("//") || SKIP.containsMatchIn(src) || Regex("""(?i)(^|/)(lib|libs|engine|third[_-]?party)/""").containsMatchIn(src))
            }
        }

    /**
     * The names in [now] worth reporting for [path] ([text] is its new content): not already undefined [before] the change, not set
     * on window anywhere, and for a classic script not mentioned by any other script or page (it may define it as a global). When
     * pages load scripts this check cannot read, classic scripts are not reported and in modules only lower-case names are (a
     * library global such as THREE or PIXI starts with a capital).
     */
    fun fresh(path: String, text: String, now: List<Use>, before: List<Use>?, paths: Collection<String>, read: (String) -> String?): List<Use> {
        if (now.isEmpty()) return emptyList()
        val old = before.orEmpty().map { it.name }.toSet()
        val module = SyntaxCheck.kind(path, text) == SyntaxCheck.Kind.MODULE
        val others = paths.filter { it != path && CODE.containsMatchIn(it) && !SKIP.containsMatchIn(it) }.sorted().take(MAX_FILES)
            .mapNotNull { read(it)?.takeIf { t -> t.length <= MAX_CHARS } }
        val unseen = unseenGlobals(paths, read)
        if (unseen && !module) return emptyList()
        return now.filter { u ->
            u.name !in old && (others + text).none { onGlobal(u.name).containsMatchIn(it) } &&
                (module || others.none { word(u.name).containsMatchIn(it) }) &&
                (!unseen || u.name.first().isLowerCase())
        }
    }

    /** The names in [fresh] that [before] (the file before the change) declared: the change deleted a definition still in use. */
    fun removedDefinitions(fresh: List<Use>, before: String): List<Use> =
        fresh.filter { declares(it.name).containsMatchIn(before) }.distinctBy { it.name }

    /** The refusal for a change that deleted definitions still in use (the file was put back). */
    fun removedRefusal(path: String, gone: List<Use>): String =
        "NOT SAVED: this change deleted the definition of " + gone.take(3).joinToString { "`${it.name}`" } + " from $path, but $path still uses " +
            (if (gone.size == 1) "it" else "them") + " (" + gone.take(3).joinToString { "`${it.name}` at line ${it.line}" } + "). " +
            "The file was put back exactly as it was. Keep the definition in your replace text (or change every use too), then make the change again."

    /** Another file that declares [name] (so it may only need importing), or null. */
    fun definedIn(name: String, path: String, paths: Collection<String>, read: (String) -> String?): String? =
        paths.filter { it != path && checks(it) }.sorted().take(MAX_FILES).firstOrNull { p ->
            read(p)?.takeIf { it.length <= MAX_CHARS }?.let { declares(name).containsMatchIn(it) } == true
        }

    private fun list(path: String, uses: List<Use>, where: (String) -> String?): String =
        uses.take(SHOWN).joinToString("\n") { u ->
            "- `${u.name}` ($path line ${u.line})" + (where(u.name)?.let { " — declared in $it: import it, or use what that file exports" } ?: " — defined nowhere in the project")
        } + (if (uses.size > SHOWN) "\n- and ${uses.size - SHOWN} more" else "")

    /** Added to the write's reply. */
    fun note(path: String, uses: List<Use>, where: (String) -> String?): String =
        "Warning: $path reads names that nothing defines or imports. When that line runs it throws \"<name> is not defined\" " +
            "(the syntax check and node --check do not catch this):\n" + list(path, uses, where) +
            "\nDefine or import each one, or use the name that does exist."

    /** Sent back once before the work is called done, when the warnings were not fixed. */
    fun ask(left: Map<String, List<Use>>, where: (String, String) -> String?): String =
        "Before finishing: these names are still read but nothing defines or imports them, so those lines throw when they run:\n" +
            left.entries.joinToString("\n") { (p, uses) -> list(p, uses) { where(p, it) } } +
            "\nFix each one (define it, import it, or use the existing name), or say why it is defined at run time."
}
