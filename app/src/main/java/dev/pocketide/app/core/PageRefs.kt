package dev.pocketide.app.core

/** Finds a web page's broken promises: files it loads that do not exist, and functions its buttons call that nothing defines. */
object PageRefs {
    private val REF = Regex("""<(?:script|img|source|audio|video|iframe)\b[^>]*?\bsrc\s*=\s*["']([^"']+)["']|<link\b[^>]*?\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val HANDLER = Regex("""\bon(?:click|change|input|submit|keydown|keyup|load)\s*=\s*["']\s*(?:return\s+)?([A-Za-z_$][\w$]*)\s*\(""", RegexOption.IGNORE_CASE)
    private val BUILTIN = setOf("alert", "confirm", "prompt", "if", "event", "this", "history", "location", "document", "window", "console")

    private fun resolve(htmlPath: String, ref: String): String? {
        val r = ref.substringBefore('#').substringBefore('?').trim()
        if (r.isEmpty() || r.startsWith("http:") || r.startsWith("https:") || r.startsWith("//") || r.startsWith("data:") || r.startsWith("blob:") ||
            r.startsWith("mailto:") || r.startsWith("javascript:") || r.startsWith("tel:")) return null
        val base = if (r.startsWith("/")) "" else htmlPath.substringBeforeLast('/', "")
        val parts = ArrayList<String>()
        for (x in (if (base.isEmpty()) r.trimStart('/') else base + "/" + r).split('/')) when (x) { "", "." -> {} ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1) else -> parts.add(x) }
        return parts.joinToString("/")
    }

    private fun defined(name: String, js: String): Boolean {
        val n = Regex.escape(name)
        return Regex("""\bfunction\s*\*?\s+$n\b|(?<![\w$.])$n\s*[:=]\s*(?:async\s*)?(?:function\b|\(|[A-Za-z_$][\w$]*\s*=>)|\b(?:const|let|var)\s+$n\b|\bwindow\.$n\s*=|\bglobalThis\.$n\s*=""").containsMatchIn(js)
    }

    /**
     * Only the page files that do not exist or are empty (reads just the .html files, so it is cheap after every command).
     */
    fun missing(paths: List<String>, read: (String) -> String?, size: (String) -> Long): List<String> {
        val have = paths.toSet(); val out = ArrayList<String>()
        for (h in paths.filter { it.endsWith(".html", true) && !it.startsWith(".") && !it.split('/').any { s -> s == "node_modules" } }) {
            val html = read(h) ?: continue
            for (m in REF.findAll(html)) {
                val target = resolve(h, m.groupValues[1].ifEmpty { m.groupValues[2] }) ?: continue
                val ext = target.substringAfterLast('.', "").lowercase()
                if (ext !in setOf("js", "mjs", "css", "json")) continue
                if (target !in have) out.add("$h loads $target but that file does not exist.")
                else if ((ext == "js" || ext == "css") && size(target) == 0L) out.add("$target is empty (0 bytes) but $h loads it.")
            }
        }
        return out.distinct().take(5)
    }


    private val IMPORT_FROM = Regex("""(?m)^\s*import\s+([^;'"]*?)\s+from\s+["'](\.{1,2}/[^"']+\.m?js)["']""")
    private val GET_ID = Regex("""getElementById\(\s*["']([\w-]+)["']\s*\)""")

    /** True when the module text exports [name] (a function, class, const/let/var, or an export list); a default import asks for "default". */
    private fun exports(js: String, name: String): Boolean {
        if (Regex("""(?m)^\s*export\s*\*\s*from""").containsMatchIn(js) || js.contains("module.exports")) return true   // cannot tell: assume yes
        if (name == "default") return Regex("""\bexport\s+default\b|\bexport\s*\{[^}]*\bas\s+default\b""").containsMatchIn(js)
        val n = Regex.escape(name)
        return Regex("""\bexport\s+(?:async\s+)?(?:function\s*\*?|class)\s+$n\b|\bexport\s+(?:const|let|var)\s+[^;\n]*?\b$n\b\s*=|\bexport\s*\{[^}]*\b(?:$n|as\s+$n)\b[^}]*\}""").containsMatchIn(js)
    }

    /** Names a module page asks for that the file it imports from does not export, and element ids a script looks up that no page has. */
    private fun moduleProblems(paths: List<String>, htmls: List<String>, read: (String) -> String?): List<String> {
        val out = ArrayList<String>()
        val have = paths.toSet()
        val jsFiles = paths.filter { (it.endsWith(".js", true) || it.endsWith(".mjs", true)) && !it.split('/').any { s -> s == "node_modules" } }
        val text = HashMap<String, String>()
        for (f in jsFiles) read(f)?.let { text[f] = it }
        for ((f, src) in text) {
            for (m in IMPORT_FROM.findAll(src)) {
                val target = resolve(f, m.groupValues[2]) ?: continue
                if (target !in have) continue   // a missing file is reported elsewhere
                val body = text[target] ?: continue
                val clause = m.groupValues[1].trim()
                val missing = ArrayList<String>()
                val named = Regex("""\{([^}]*)\}""").find(clause)
                val def = clause.substringBefore('{').trim().trimEnd(',').trim().takeIf { it.isNotEmpty() && !it.startsWith("*") }
                if (def != null && !exports(body, "default")) missing.add("a default export")
                named?.groupValues?.get(1)?.split(',')?.map { it.trim().substringBefore(" as ").trim() }?.filter { it.isNotEmpty() }?.forEach { if (!exports(body, it)) missing.add(it) }
                if (missing.isNotEmpty()) out.add("$f imports ${missing.take(4).joinToString(", ")} from $target, but $target does not export ${if (missing.size == 1) "it" else "them"}, so the page stops before it starts. Export it, or remove the import.")
            }
        }
        if (htmls.isNotEmpty()) {
            val pageText = htmls.mapNotNull { read(it) }
            val pageIds = pageText.flatMap { h -> Regex("""\bid\s*=\s*(?:["']([\w-]+)["']|([\w-]+))""").findAll(h).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList() }.toSet()
            val allJs = text.values.joinToString("\n")
            for ((f, src) in text) for (id in GET_ID.findAll(src).map { it.groupValues[1] }.distinct()) {
                if (id in pageIds) continue
                val made = Regex("""\.id\s*=\s*["']$id["']|setAttribute\(\s*["']id["']\s*,\s*["']$id["']|\bid\s*[:=]\s*\\?["']$id\\?["']""").containsMatchIn(allJs)   // an element the script makes itself
                if (made) continue
                val close = pageIds.firstOrNull { it.contains(id, true) || id.contains(it, true) }
                out.add("$f looks up the element \"$id\" but no page has an element with that id" + (if (close != null) " (a page has \"$close\": use that name)" else "") + ", so it fails when the page loads.")
            }
        }
        return out
    }

    /**
     * Problems in the project's pages, as plain sentences. [paths] are all project files, [read] gives a file's text (null when
     * unreadable), [size] its length in bytes. Only .html files are looked at.
     */
    fun problems(paths: List<String>, read: (String) -> String?, size: (String) -> Long, max: Int = 8): List<String> {
        val out = ArrayList<String>()
        val have = paths.toSet()
        val htmls = paths.filter { it.endsWith(".html", true) && !it.startsWith(".") && !it.split('/').any { s -> s == "node_modules" } }
        val js = StringBuilder()
        for (p in paths) if (p.endsWith(".js", true) || p.endsWith(".mjs", true)) read(p)?.let { js.append(it).append('\n') }
        for (h in htmls) {
            val html = read(h) ?: continue
            js.append(html).append('\n')   // inline scripts define functions too
        }
        val allJs = js.toString()
        for (h in htmls) {
            val html = read(h) ?: continue
            for (m in REF.findAll(html)) {
                val ref = m.groupValues[1].ifEmpty { m.groupValues[2] }
                val target = resolve(h, ref) ?: continue
                val ext = target.substringAfterLast('.', "").lowercase()
                if (target !in have) {
                    if (ext in setOf("js", "mjs", "css", "json", "html")) out.add("$h loads $target but that file does not exist. Create it.")
                } else if ((ext == "js" || ext == "css") && size(target) == 0L) out.add("$target is empty (0 bytes) but $h loads it.")
            }
            val missing = HANDLER.findAll(html).map { it.groupValues[1] }.filter { it !in BUILTIN }.distinct().filter { !defined(it, allJs) }.toList()
            if (missing.isNotEmpty()) out.add("$h calls ${missing.take(6).joinToString(", ") { "$it()" }}${if (missing.size > 6) " and ${missing.size - 6} more" else ""} but no file defines ${if (missing.size == 1) "it" else "them"}.")
        }
        out.addAll(moduleProblems(paths, htmls, read))
        out.addAll(wiring(paths, read))
        return out.distinct().take(max)
    }

    private val SCRIPT_OPEN = Regex("""<script\b""", RegexOption.IGNORE_CASE)
    private val INLINE = Regex("""<script\b(?![^>]*\bsrc\s*=)[^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
    private val LOOKUP = Regex("""getElementById\(\s*["'`]([\w-]+)["'`]\s*\)|querySelector(?:All)?\(\s*["'`]#([\w-]+)["'`]\s*\)""")
    /** An id given anywhere: id="x" in a page or in a string in a script, el.id = "x", setAttribute("id", "x"). */
    private val ID = Regex("""\bid\s*=\s*\\?["'`]([\w-]+)|\.id\s*=\s*["'`]([\w-]+)|setAttribute\(\s*["']id["']\s*,\s*["'`]([\w-]+)""", RegexOption.IGNORE_CASE)
    private fun skipped(p: String) = p.startsWith(".") || p.split('/').any { it == "node_modules" || it == "tests" || it == "test" || it == "__tests__" } ||
        Regex("""(?i)(\.test|\.spec|\.config|^sw|service-worker|playtests)\.m?js$|(^|/)test\.js$""").containsMatchIn(p.substringAfterLast('/'))

    /**
     * A page that is wired up wrong (1.5.190, from-scratch run 1126-0628, where the app's checks passed both): index.html loading no
     * script while script.js sits beside it (s05: the to-do list did nothing), and a script looking up an id that no page or script
     * gives (s09: getElementById('tipResult') on a page whose element is id="result", so the total never showed).
     */
    fun wiring(paths: List<String>, read: (String) -> String?): List<String> {
        val out = ArrayList<String>()
        val htmls = paths.filter { it.endsWith(".html", true) && !skipped(it) }
        if (htmls.isEmpty()) return out
        val pages = htmls.associateWith { read(it).orEmpty() }
        val loaded = LinkedHashSet<String>()
        for ((h, html) in pages) for (m in REF.findAll(html)) { val r = m.groupValues[1]; if (r.isNotEmpty()) resolve(h, r)?.let { loaded.add(it) } }
        val js = paths.filter { (it.endsWith(".js", true) || it.endsWith(".mjs", true)) && !skipped(it) }
        // a page with no script at all, and a script beside it that nothing loads
        for ((h, html) in pages) {
            if (SCRIPT_OPEN.containsMatchIn(html)) continue
            val dir = h.substringBeforeLast('/', "")
            val beside = js.filter { it.substringBeforeLast('/', "") == dir && it !in loaded }
            if (beside.isNotEmpty()) out.add("$h loads no script, so ${beside.first().substringAfterLast('/')} never runs and the page does nothing. " +
                "Add <script src=\"${beside.first().substringAfterLast('/')}\"></script> before </body>.")
        }
        // ids looked up by the scripts the pages run that nothing gives
        val sources = loaded.filter { it in js } .associateWith { read(it).orEmpty() } + pages.mapValues { (_, html) -> INLINE.findAll(html).joinToString("\n") { it.groupValues[1] } }
        val ids = HashSet<String>()
        for (text in pages.values + js.map { read(it).orEmpty() }) for (m in ID.findAll(text)) ids.add(m.groupValues.drop(1).first { it.isNotEmpty() })
        for ((src, text) in sources) {
            val missing = LOOKUP.findAll(text).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.filter { it !in ids }.distinct().toList()
            if (missing.isNotEmpty()) out.add("${src.substringAfterLast('/')} looks up ${missing.take(6).joinToString(", ") { "#$it" }}${if (missing.size > 6) " and ${missing.size - 6} more" else ""} " +
                "but no page has an element with ${if (missing.size == 1) "that id" else "those ids"}, so ${if (missing.size == 1) "it is" else "they are"} null. Use the ids the page has, or add the elements.")
        }
        return out
    }
}
