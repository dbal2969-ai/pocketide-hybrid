package dev.pocketide.app.core

/**
 * Fallback for models (usually small local ones) that ignore PocketIDE's edit format and write files
 * in a looser layout instead. Recognised shapes:
 *
 *   runserver.go            **app.js**  /  `app.js`  /  ### app.js  /  File: app.js  /  app.js:
 *   =======                 ```js
 *   ...whole file...        ...whole file...
 *   =======                 ```
 *
 * plus a fence whose first line is only a comment naming the file (`// main.go`, `# app.py`, `<!-- index.html -->`).
 * A divider of ===== or ----- may also run to the end of the reply when the closing one is missing.
 *
 * These are whole-file writes, so an existing file is only replaced when the new text looks complete
 * (at least 60% of the old length); shorter snippets are left in the reply text instead of clobbering work.
 */
object LooseFiles {
    data class Result(val text: String, val files: LinkedHashMap<String, String>, val skipped: List<String>)

    private val EXTENSIONS = setOf(
        "go", "mod", "sum", "py", "js", "mjs", "cjs", "ts", "tsx", "jsx", "html", "htm", "css", "scss", "sass", "less",
        "json", "md", "txt", "kt", "kts", "java", "c", "h", "cpp", "cc", "hpp", "hh", "cs", "rs", "rb", "php", "sh", "bash",
        "zsh", "yml", "yaml", "toml", "ini", "cfg", "conf", "xml", "svg", "sql", "lua", "swift", "dart", "vue", "svelte",
        "gradle", "env", "csv", "tsv", "r", "pl", "ex", "exs", "erl", "hs", "ml", "scala", "clj", "zig", "nim", "v", "proto",
        "graphql", "gql", "bat", "ps1", "dockerfile", "make", "mk", "cmake", "lock", "properties", "webmanifest"
    )
    private val BARE_NAMES = setOf("Makefile", "Dockerfile", "README", "LICENSE", "Procfile", "Gemfile", "Rakefile", ".gitignore", ".env")
    private val NAME = Regex("""^[\w\-.]+(/[\w\-.]+)*$""")
    private val DIVIDER = Regex("""^\s*(={3,}|-{3,})\s*$""")
    private val FENCE = Regex("""^\s*```""")
    private val COMMENT_NAME = Regex("""^\s*(?://|#|--|;|/\*|<!--)\s*(?:file(?:name)?\s*:\s*)?([\w\-./]+)\s*(?:\*/|-->)?\s*$""", RegexOption.IGNORE_CASE)

    /** A line that is nothing but a file name, maybe decorated. Returns the cleaned relative path, or null. */
    fun pathLine(line: String, known: Set<String> = emptySet()): String? {
        var s = line.trim()
        if (s.isEmpty() || s.length > 160) return null
        s = s.replace(Regex("""^#{1,6}\s+"""), "")
        s = s.replace(Regex("""^(?:[-*+]|\d+[.)])\s+"""), "")
        s = s.replace(Regex("""^(?:file(?:name)?|path)\s*:\s*""", RegexOption.IGNORE_CASE), "")
        s = s.trim().trimEnd(':').trim()
        // strip wrapping **, __, `, *, quotes (possibly nested, e.g. **`app.js`**)
        repeat(3) {
            for (w in listOf("**", "__", "`", "*", "\"", "'")) {
                if (s.length > 2 * w.length && s.startsWith(w) && s.endsWith(w)) s = s.substring(w.length, s.length - w.length).trim()
            }
            s = s.trimEnd(':').trim()
        }
        if (s.startsWith("./")) s = s.substring(2) else if (s.startsWith("/")) s = s.substring(1)
        if (s.isEmpty() || s.contains("..") || !NAME.matches(s)) return null
        if (known.contains(s)) return s
        val base = s.substringAfterLast('/')
        if (BARE_NAMES.contains(base)) return s
        val dot = base.lastIndexOf('.')
        if (dot <= 0 || dot == base.length - 1) return null
        return if (EXTENSIONS.contains(base.substring(dot + 1).lowercase())) s else null
    }

    private val LANG_EXT = mapOf(
        "python" to "py", "py" to "py", "javascript" to "js", "js" to "js", "node" to "js", "typescript" to "ts", "ts" to "ts",
        "html" to "html", "css" to "css", "json" to "json", "go" to "go", "golang" to "go", "java" to "java", "kotlin" to "kt", "kt" to "kt",
        "rust" to "rs", "rs" to "rs", "c" to "c", "cpp" to "cpp", "c++" to "cpp", "ruby" to "rb", "rb" to "rb", "php" to "php",
        "sh" to "sh", "bash" to "sh", "shell" to "sh", "zsh" to "sh", "yaml" to "yml", "yml" to "yml", "xml" to "xml", "sql" to "sql",
        "lua" to "lua", "swift" to "swift", "markdown" to "md", "md" to "md", "toml" to "toml"
    )
    private val EXT_ALIAS = mapOf("htm" to "html", "yaml" to "yml", "mjs" to "js", "cjs" to "js", "jsx" to "js", "tsx" to "ts", "kts" to "kt", "bash" to "sh", "cc" to "cpp", "hpp" to "cpp")

    private fun fenceLang(fenceLine: String): String? {
        val tag = fenceLine.trim().trimStart('`').trim().lowercase().takeWhile { it.isLetterOrDigit() || it == '+' }
        return LANG_EXT[tag]
    }

    /** The one existing file named in [prose] whose extension matches the fence language, or null. */
    private fun mentionedFile(prose: String, known: Set<String>, lang: String?): String? {
        if (lang == null) return null
        val hits = known.filter { k ->
            val ext = k.substringAfterLast('.', "").lowercase()
            val norm = EXT_ALIAS[ext] ?: ext
            norm == lang && Regex("""(?<![\w./-])${Regex.escape(k)}(?![\w-])""").containsMatchIn(prose)
        }
        return if (hits.size == 1) hits[0] else null
    }

    fun extract(text: String, existing: Map<String, String>): Result {
        val lines = text.replace("\r", "").split("\n")
        val known = existing.keys
        val files = LinkedHashMap<String, String>()
        val skipped = ArrayList<String>()
        val keep = ArrayList<String>()

        fun accept(path: String, body: List<String>): Boolean {
            val content = body.joinToString("\n").trimEnd('\n') + "\n"
            if (content.isBlank()) return false
            val old = existing[path]
            if (old != null && old.isNotBlank() && content.length < old.length * 0.6) { if (!skipped.contains(path)) skipped.add(path); return false }
            files[path] = content
            return true
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val path = pathLine(line, known)
            if (path != null) {
                // the body starts on the next non-blank line (one blank line allowed)
                var j = i + 1
                if (j < lines.size && lines[j].isBlank()) j++
                if (j < lines.size && FENCE.containsMatchIn(lines[j])) {
                    val close = (j + 1 until lines.size).firstOrNull { FENCE.containsMatchIn(lines[it]) && lines[it].trim().trimStart('`').isEmpty() }
                    if (close != null && accept(path, lines.subList(j + 1, close))) { i = close + 1; continue }
                } else if (j < lines.size && DIVIDER.matches(lines[j])) {
                    val mark = lines[j].trim()[0]
                    val close = (j + 1 until lines.size).firstOrNull { DIVIDER.matches(lines[it]) && lines[it].trim()[0] == mark } ?: lines.size
                    if (accept(path, lines.subList(j + 1, close))) { i = minOf(close + 1, lines.size); continue }
                }
            } else if (FENCE.containsMatchIn(line) && i + 1 < lines.size) {
                // ```go  /  // main.go  /  ...
                val m = COMMENT_NAME.matchEntire(lines[i + 1])
                val named = m?.let { pathLine(it.groupValues[1], known) }
                if (named != null) {
                    val close = (i + 2 until lines.size).firstOrNull { FENCE.containsMatchIn(lines[it]) && lines[it].trim().trimStart('`').isEmpty() }
                    if (close != null && accept(named, lines.subList(i + 2, close))) { i = close + 1; continue }
                } else {
                    // "Here's the updated `main.py` file:" followed by a fence in the same language as that existing file
                    val prev = (i - 1 downTo maxOf(0, i - 3)).map { lines[it] }.firstOrNull { it.isNotBlank() }
                    val target = prev?.let { mentionedFile(it, known, fenceLang(line)) }
                    if (target != null) {
                        val close = (i + 1 until lines.size).firstOrNull { FENCE.containsMatchIn(lines[it]) && lines[it].trim().trimStart('`').isEmpty() }
                        if (close != null && accept(target, lines.subList(i + 1, close))) { i = close + 1; continue }
                    }
                }
            }
            keep.add(line)
            i++
        }
        val rest = keep.joinToString("\n").replace(Regex("""\n{3,}"""), "\n\n").trim()
        return Result(rest, files, skipped)
    }
}
