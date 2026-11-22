package dev.pocketide.app.core

/**
 * Checks a generated game folder before it is run: every local script or page that is referenced exists, every data file
 * parses, and every object that a place file names has its picture. Reports what is wrong and how to fix it, in plain words.
 * Reads only; it never changes a file.
 */
object GameCheck {
    /** Game folders (a pathfinder.js next to a main.js) that hold at least one of the [changed] files; "" is the project root. */
    fun dirsFor(paths: List<String>, changed: Collection<String>, exists: (String) -> Boolean): List<String> =
        paths.filter { it.endsWith("pathfinder.js") && exists(it.removeSuffix("pathfinder.js") + "main.js") }
            .map { it.removeSuffix("pathfinder.js").trimEnd('/') }
            .filter { d -> changed.any { c -> d.isEmpty() || c.startsWith("$d/") } }

    private val SRC = Regex("""(?:src|href)\s*=\s*["']([^"'#?]+)["']""")
    private val IMPORT = Regex("""(?:import|from)\s*\(?\s*['"](\.{0,2}/?[^'"]+\.(?:js|mjs))['"]""")
    private val DATA = Regex("""['"`](data/[^'"`]+\.(?:json|png|wav|mp3|ogg))['"`]""")
    private val PLACE_ROW = Regex("""\[(\d+),\d+,\d+,\d+,\d+,\d+,\d+(?:,\d+)?\]""")

    /** Joins a folder and a relative path, resolving . and .. (returns null when it climbs above the folder). */
    fun resolve(folder: String, rel: String): String? {
        val parts = ArrayList<String>()
        for (p in (if (folder.isEmpty()) "" else "$folder/").split('/') + rel.split('/')) {
            when (p) { "", "." -> {}; ".." -> { if (parts.isEmpty()) return null; parts.removeAt(parts.size - 1) }; else -> parts.add(p) }
        }
        return parts.joinToString("/")
    }

    fun check(files: List<String>, read: (String) -> String?, size: (String) -> Long): String {
        val all = files.toSet()
        val problems = ArrayList<String>()
        var pages = 0
        for (f in files.sorted()) {
            if (!(f.endsWith(".html") || f.endsWith(".js") || f.endsWith(".mjs"))) continue
            if (f.contains("node_modules/")) continue
            val text = read(f) ?: continue
            val dir = f.substringBeforeLast('/', "")
            if (f.endsWith(".html")) pages++
            if (f.endsWith(".html")) for (m in SRC.findAll(text)) local(m.groupValues[1], dir, f, all)?.let { problems.add(it) }   // scripts set .src with a built-up name: only pages are read for src=
            for (m in IMPORT.findAll(text)) local(m.groupValues[1], dir, f, all)?.let { problems.add(it) }
            for (line in text.lineSequence()) {
                val calls = FETCHED.findAll(line).map { it.groupValues[1] }.filter { !it.startsWith("data/") && !it.contains("\${") && !it.startsWith("http") && !it.startsWith("//") }.toList()
                if (calls.isEmpty()) continue
                val targets = calls.map { it to resolve(dir, it) }
                if (targets.any { (_, t) -> t == null || t in all }) continue   // one of the alternatives on this line is there (or is outside the folder)
                problems.add("$f loads ${targets.first().first}, which is not in the project. Paths are read from the page's folder: fix the path or add the file.")
            }
            for (m in DATA.findAll(text)) {
                val p = m.groupValues[1]
                if (p.contains("\${")) { if (!templateMatches(p, dir, all)) problems.add("$f loads $p, but no file in the project matches that pattern. Export the data with game_cache or fix the name."); continue }
                if (resolve(dir, p) !in all) problems.add("$f loads $p, which is not in the project. Export it with game_cache (see the file name) or remove the line.")
            }
        }
        for (f in files) {
            if (f.endsWith(".json") && size(f) > 6_000_000) continue   // too big to parse here; the page reads it
            if (f.endsWith(".json")) {
                val t = read(f)?.trim() ?: continue
                val ok = try { MiniJson.parse(t); true } catch (e: Exception) { false }
                if (!ok) problems.add("$f is not valid JSON. Export it again with game_cache.")
            }
            if (f.contains("/places/") && f.endsWith(".json")) {
                val text = read(f) ?: continue
                val base = f.substringBeforeLast("/places/")
                val ids = PLACE_ROW.findAll(text).map { it.groupValues[1] }.toSet()
                val missing = ids.filter { "$base/objects/$it.png" !in all }
                if (missing.isNotEmpty()) problems.add("$f places ${missing.size} object(s) without a picture (for example ${missing.take(3).joinToString(", ")}). Run game_cache place_objects again for those squares.")
            }
        }
        // a script that nothing loads is dead weight: list it so it is deleted or imported
        val loaded = HashSet<String>()
        for (f in files) {
            val text = if (f.endsWith(".html") || f.endsWith(".js") || f.endsWith(".mjs")) read(f) ?: continue else continue
            val dir = f.substringBeforeLast('/', "")
            for (m in SRC.findAll(text)) resolve(dir, m.groupValues[1])?.let { loaded.add(it) }
            for (m in IMPORT.findAll(text)) resolve(dir, m.groupValues[1])?.let { loaded.add(it) }
            for (m in MODULE_STR.findAll(text)) resolve(dir, m.groupValues[1])?.let { loaded.add(it) }
        }
        problems.addAll(wiring(files, read))
        for (f in files) if ((f.endsWith(".js") || f.endsWith(".mjs")) && f !in loaded && !f.startsWith("data/") && !f.contains("node_modules/") && pages > 0)
            problems.add("$f is never loaded by a page or script. Import it or delete it (project_audit lists unused files).")
        if (pages == 0 && files.none { it.endsWith(".js") }) return "Nothing to check: no game page or script in this folder."
        val head = "Checked ${files.size} files" + (if (pages > 0) " and $pages page(s)" else "") + "."
        return if (problems.isEmpty()) "$head Every script, page and data file it uses is there and reads correctly."
            else "$head ${problems.size} problem(s):\n" + problems.take(20).joinToString("\n") { "- $it" } + (if (problems.size > 20) "\n… and ${problems.size - 20} more." else "")
    }

    private val NAMED = Regex("""import\s*(?:([A-Za-z_$][\w$]*)\s*,\s*)?\{([^}]*)\}\s*from\s*['"](\.{1,2}/[^'"]+)['"]""")
    private val DEFAULT_ONLY = Regex("""import\s+([A-Za-z_$][\w$]*)\s+from\s*['"](\.{1,2}/[^'"]+)['"]""")
    private val EXPORT_DECL = Regex("""export\s+(?:async\s+)?(?:function\*?|class)\s+([A-Za-z_$][\w$]*)""")
    private val EXPORT_VARS = Regex("""(?m)^\s*export\s+(?:const|let|var)\s+(.+)$""")
    private val EXPORT_LIST = Regex("""export\s*\{([^}]*)\}""")
    private val VAR_NAME = Regex("""(?:^|,)\s*([A-Za-z_$][\w$]*)\s*=""")
    /** A data file fetched with a literal path, such as json('game/data/enemies.json') or fetch("levels/1.json"). */
    private val FETCHED = Regex("""\b(?:fetch|json|getJSON|loadJSON|loadJson|fetchJSON|fetchJson)\s*\(\s*['"]([^'"`]+\.(?:json|png|jpg|wav|mp3|ogg|bin|gz))['"]""")
    /** A module path written as a string, for example in getCtor('World', './world.js') or a list of modules to import. */
    private val MODULE_STR = Regex("""['"`](\.{1,2}/[^'"`\s]+\.m?js)['"`]""")
    /** A class looked up by name in a module: getCtor('World', './world.js'), load("Render", "./render.js"). */
    private val NAMED_LOOKUP = Regex("""\(\s*['"]([A-Z][\w$]*)['"]\s*,\s*['"](\.{1,2}/[^'"]+\.m?js)['"]\s*\)""")
    /** A helper that wraps getElementById: function byId(id) { return document.getElementById(id) } or const $ = id => document.getElementById(id). */
    private val ID_HELPER_FN = Regex("""function\s+([A-Za-z_$][\w$]*)\s*\(\s*([A-Za-z_$][\w$]*)\s*\)\s*\{[^}]{0,200}?getElementById\(\s*\2\s*\)""")
    private val ID_HELPER_ARROW = Regex("""(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*\(?\s*([A-Za-z_$][\w$]*)\s*\)?\s*=>\s*[^;\n]{0,160}?getElementById\(\s*\2\s*\)""")
    private val BY_ID = Regex("""getElementById\(\s*['"]([\w-]+)['"]\s*\)|querySelector\(\s*['"]#([\w-]+)['"]\s*\)""")
    private val HTML_ID = Regex("""\bid\s*=\s*["']([\w-]+)["']""")
    private val JS_ID = Regex("""\.id\s*=\s*['"`]([\w-]+)['"`]|\bid\s*=\s*\\?["']([\w-]+)\\?["']""")

    private val GLOBAL_SET = Regex("""(?<![\w$.])(?:window|globalThis|self)\.([A-Za-z_$][\w$]*)\s*=(?!=)""")
    private val GLOBAL_ASSIGN = Regex("""Object\.assign\(\s*(?:window|globalThis|self)\s*,\s*\{([^}]*)\}""")
    private val CJS_OBJECT = Regex("""module\.exports\s*=\s*\{([^}]*)\}""")
    private val CJS_NAME = Regex("""(?<![\w$.])(?:module\.)?exports\.([A-Za-z_$][\w$]*)\s*=(?!=)""")

    /**
     * The names a plain script shares with the page's other scripts (1.5.122): `window.x = …`, `globalThis.x`, `self.x`,
     * `Object.assign(window, { x, y })`, and CommonJS `module.exports = { x }` / `exports.x =`. A web page that loads its files
     * with ordinary script tags shares functions this way, not with `export`.
     */
    fun sharedOf(text: String): Set<String> {
        val out = HashSet<String>()
        GLOBAL_SET.findAll(text).forEach { out.add(it.groupValues[1]) }
        CJS_NAME.findAll(text).forEach { out.add(it.groupValues[1]) }
        for (r in listOf(GLOBAL_ASSIGN, CJS_OBJECT)) r.findAll(text).forEach { m ->
            m.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { e ->
                Regex("""^(?:async\s+)?\*?\s*([A-Za-z_$][\w$]*)""").find(e)?.let { out.add(it.groupValues[1]) }
            }
        }
        return out
    }

    /** The names a module exports ("default" for export default). */
    fun exportsOf(text: String): Set<String> {
        val out = HashSet<String>()
        EXPORT_DECL.findAll(text).forEach { out.add(it.groupValues[1]) }
        EXPORT_VARS.findAll(text).forEach { m -> VAR_NAME.findAll(topLevel(m.groupValues[1])).forEach { out.add(it.groupValues[1]) } }
        EXPORT_LIST.findAll(text).forEach { m -> m.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { out.add(it.split(Regex("\\s+as\\s+")).last().trim()) } }
        if (Regex("""export\s+default\b""").containsMatchIn(text)) out.add("default")
        return out
    }

    // keeps only the text outside brackets, so "a = f(1, b = 2), c = 3" yields a and c
    private fun topLevel(s: String): String {
        val b = StringBuilder(); var depth = 0
        for (c in s) { when (c) { '(', '[', '{' -> depth++; ')', ']', '}' -> depth--; else -> if (depth == 0) b.append(c) } ; if (depth < 0) break }
        return b.toString()
    }

    /** Null when brackets close, else where the text looks cut off. Strings, template text and comments are skipped. */
    fun unclosed(text: String): String? {
        val stack = ArrayList<Int>()
        var i = 0; val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c == '/' && i + 1 < n && text[i + 1] == '/' -> { i = text.indexOf('\n', i).let { if (it < 0) n else it }; continue }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> { val e = text.indexOf("*/", i + 2); if (e < 0) return "a comment is never closed"; i = e + 2; continue }
                c == '"' || c == '\'' || c == '`' -> {
                    var j = i + 1
                    while (j < n && text[j] != c) { if (text[j] == '\\') j++; else if (c != '`' && text[j] == '\n') break; j++ }
                    if (j >= n) return "a string or template starting on line ${line(text, i)} is never closed"
                    i = j + 1; continue
                }
                c == '{' || c == '(' || c == '[' -> stack.add(i)
                c == '}' || c == ')' || c == ']' -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            }
            i++
        }
        return if (stack.isEmpty()) null else "the '${text[stack.last()]}' on line ${line(text, stack.last())} is never closed (the file stops early)"
    }

    // data/maps/m${mx}_${mz}.json matches when at least one file fits the pattern with something in place of each ${...}
    private fun templateMatches(p: String, dir: String, all: Set<String>): Boolean {
        val full = resolve(dir, p) ?: return false
        val re = Regex(full.split(Regex("""\$\{[^}]*\}""")).joinToString("[^/]+") { Regex.escape(it) })
        return all.any { re.matches(it) }
    }

    private fun line(t: String, at: Int) = t.substring(0, at.coerceAtMost(t.length)).count { it == '\n' } + 1

    /** Imports of names a file does not export, scripts that stop early, and page elements scripts look up that no page has. */
    fun wiring(files: List<String>, read: (String) -> String?): List<String> {
        val all = files.toSet()
        val out = ArrayList<String>()
        val scripts = files.filter { (it.endsWith(".js") || it.endsWith(".mjs")) && !it.contains("node_modules/") && !it.contains("/lib/") && !it.startsWith("lib/") && !it.contains(".min.") }
        val texts = HashMap<String, String>()
        fun text(f: String) = texts.getOrPut(f) { read(f) ?: "" }
        for (f in scripts) {
            val t = text(f)
            if (t.length > 400_000) continue
            unclosed(t)?.let { out.add("$f looks cut off: $it. Write the missing end, or put the kit file back with game_cache action=restore.") }
            val dir = f.substringBeforeLast('/', "")
            fun need(names: List<String>, ref: String) {
                val target = resolve(dir, ref) ?: return
                if (target !in all) return   // reported as a missing file already
                val have = exportsOf(text(target))
                val missing = names.filter { it !in have }
                if (missing.isNotEmpty()) out.add("$f imports ${missing.joinToString(", ") { "'$it'" }} from $ref, but $target does not export ${if (missing.size == 1) "it" else "them"}" +
                    (if (have.isNotEmpty()) " (it exports: ${have.sorted().take(8).joinToString(", ")})" else "") + ". Fix the name on one side.")
            }
            for (m in NAMED.findAll(t)) {
                val names = m.groupValues[2].split(',').map { it.trim().split(Regex("\\s+as\\s+")).first().trim() }.filter { it.isNotEmpty() }.toMutableList()
                if (m.groupValues[1].isNotEmpty()) names.add("default")
                need(names, m.groupValues[3])
            }
            for (m in DEFAULT_ONLY.findAll(t)) need(listOf("default"), m.groupValues[2])
            for (m in NAMED_LOOKUP.findAll(t)) {
                val name = m.groupValues[1]; val ref = m.groupValues[2]
                val target = resolve(dir, ref) ?: continue
                if (target !in all) { out.add("$f looks up '$name' in $ref, which is not in the folder. Fix the file name."); continue }
                val have = exportsOf(text(target))
                if (name !in have) out.add("$f looks up '$name' in $ref, but $target does not export it" +
                    (if (have.isNotEmpty()) " (it exports: ${have.sorted().take(8).joinToString(", ")})" else "") +
                    ". Point it at the file that exports $name, or export it there.")
            }
        }
        val pages = files.filter { it.endsWith(".html") }
        if (pages.isNotEmpty()) {
            val ids = HashSet<String>()
            for (p in pages) HTML_ID.findAll(read(p) ?: "").forEach { ids.add(it.groupValues[1]) }
            for (f in scripts) JS_ID.findAll(text(f)).forEach { m -> ids.add(m.groupValues[1].ifEmpty { m.groupValues[2] }) }
            val seen = HashSet<String>()
            for (f in scripts) for (m in BY_ID.findAll(text(f))) {
                val id = m.groupValues[1].ifEmpty { m.groupValues[2] }
                if (id !in ids && seen.add(id)) out.add("$f looks for the page element #$id, but no page has it. Add it to the page or remove the lookup.")
            }
            // lookups through a helper such as byId('p-bar'): the helper hides the miss, so a missing element breaks the page later
            val helpers = HashSet<String>()
            for (f in scripts) { val t = text(f); ID_HELPER_FN.findAll(t).forEach { helpers.add(it.groupValues[1]) }; ID_HELPER_ARROW.findAll(t).forEach { helpers.add(it.groupValues[1]) } }
            if (helpers.isNotEmpty()) {
                val call = Regex("""(?<![\w$.])(""" + helpers.joinToString("|") { Regex.escape(it) } + """)\(\s*['"]([\w-]+)['"]\s*\)""")
                for (f in scripts) for (m in call.findAll(text(f))) {
                    val id = m.groupValues[2]
                    if (id !in ids && seen.add(id)) out.add("$f looks for the page element #$id (with ${m.groupValues[1]}), but no page has it. Add it to the page or remove the lookup.")
                }
            }
        }
        return out.distinct()
    }

    /**
     * The problems in a check report that break the game: every listed problem except files nothing loads (dead weight, but the
     * game still runs). Used to decide whether a request may end as done.
     */
    fun blocking(report: String): List<String> =
        report.lines().filter { it.startsWith("- ") && !it.contains("is never loaded by a page or script") }.distinct()

    /** The page the preview opens: index.html at the top of the project, else the first page (the same rule as the preview). */
    fun entryPage(paths: List<String>): String? = paths.firstOrNull { it == "index.html" } ?: paths.firstOrNull { it.endsWith(".html") }

    /**
     * Problems in one page that is outside the checked game folders, usually the page the preview opens: scripts and modules it
     * loads that are not in the project. [files] are project paths.
     */
    fun pageProblems(page: String, files: Collection<String>, read: (String) -> String?): List<String> {
        val text = read(page) ?: return emptyList()
        val all = files.toSet(); val dir = page.substringBeforeLast('/', "")
        val out = ArrayList<String>()
        for (m in SRC.findAll(text)) local(m.groupValues[1], dir, page, all)?.let { out.add(it) }
        for (m in IMPORT.findAll(text)) local(m.groupValues[1], dir, page, all)?.let { out.add(it) }
        return out.distinct()
    }

    private fun local(ref: String, dir: String, from: String, all: Set<String>): String? {
        if (ref.startsWith("http") || ref.startsWith("//") || ref.startsWith("data:") || ref.startsWith("blob:")) return null
        val p = resolve(dir, ref) ?: return "$from points outside the project with $ref. Use a path inside the folder."
        return if (p in all) null else "$from uses $ref, which is not in the folder. Create it, or fix the name to match a file that exists."
    }
}
