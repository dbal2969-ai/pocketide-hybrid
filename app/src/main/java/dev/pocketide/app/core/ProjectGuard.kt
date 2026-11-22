package dev.pocketide.app.core

/** Keeps the assistant from filling the project with things it does not need, and from reading minified libraries. */
object ProjectGuard {
    /** Folders that hold installed packages: not listed, not read into the chat's context. */
    val HEAVY = setOf("node_modules", "__pycache__", "venv", "site-packages")

    fun isHeavy(name: String): Boolean = name in HEAVY

    /** What the assistant is told so it does not mistake a shortened file list for the whole project. */
    fun listNote(skipped: Map<String, Int>, truncated: Boolean, limit: Int, omitted: Map<String, Int> = emptyMap()): String {
        val sb = StringBuilder()
        if (skipped.isNotEmpty()) sb.append("Not in the file list (installed packages, ignore them): ").append(skipped.entries.joinToString(", ") { "${it.key}/ (${it.value} files)" }).append(". ")
        if (omitted.isNotEmpty()) sb.append("The project is too big to list in full, so these files were left out of the list (they exist): ")
            .append(rollUp(omitted, 12).entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key.ifEmpty { "(top folder)" }}/ (${it.value} files)" })
            .append(". list_files or search on any of these folders shows its files. ")
        else if (truncated) sb.append("The list shows only the first $limit files. ")
        if (sb.isNotEmpty()) sb.append("A file that is not in a list may still exist: read it by name before you decide it is missing, and never recreate a file just because you did not see it.")
        return sb.toString()
    }

    /**
     * Folder counts merged into their parent folders, deepest first, until at most [max] remain (1.5.167). Before, the note named the
     * 12 biggest folders and dropped the rest, so a source tree spread over hundreds of small folders was never mentioned, and a
     * model concluded an imported server's source "is not in the project". Every left-out file is still counted under some folder.
     */
    fun rollUp(counts: Map<String, Int>, max: Int): Map<String, Int> {
        var cur = LinkedHashMap(counts)
        while (cur.size > max) {
            val depth = cur.keys.maxOf { if (it.isEmpty()) 0 else it.count { c -> c == '/' } + 1 }
            if (depth == 0) break
            val next = LinkedHashMap<String, Int>()
            for ((k, n) in cur) {
                val d = if (k.isEmpty()) 0 else k.count { c -> c == '/' } + 1
                val key = if (d == depth) k.substringBeforeLast('/', "") else k
                next[key] = (next[key] ?: 0) + n
            }
            cur = next
        }
        return cur
    }

    /**
     * Which files to list when there are more than [limit]: text and code first, then binary files, each in path order. The
     * rest are counted by folder (folder to number left out) for the note. With [limit] or fewer files, all of them.
     */
    fun pickListed(all: List<String>, limit: Int, binary: (String) -> Boolean): Pair<List<String>, Map<String, Int>> {
        if (all.size <= limit) return all.sorted() to emptyMap()
        val bin = HashSet<String>(); for (p in all) if (binary(p)) bin.add(p)   // asked once per file, not once per comparison
        // shallow files first: the top of the project always shows (a zip just added there must not vanish), and source code, which
        // is usually near the top, is kept before bulk data deep in export folders; then text before binary, then by name
        val ordered = all.sortedWith(compareBy<String> { it.count { c -> c == '/' } }.thenBy { if (it in bin) 1 else 0 }.thenBy { it })
        val keep = ordered.take(limit)
        val left = LinkedHashMap<String, Int>()
        for (p in ordered.drop(limit)) { val d = p.substringBeforeLast('/', ""); left[d] = (left[d] ?: 0) + 1 }
        return keep.sorted() to left
    }

    private val BROWSER = Regex("""(?i)\b(puppeteer|puppeteer-core|playwright|selenium|chromedriver|chromium|chromium-browser|google-chrome|firefox|jsdom|happy-dom|linkedom)\b""")
    private val INSTALL = Regex("""(?i)\b(npm|npx|yarn|pnpm|pip3?|apk|apt|apt-get)\s+(install|i|add|exec|dlx)?\b|\bnpx\b""")

    /** A message when the command would install or run a browser, or null. The app already checks pages itself. */
    fun installRefusal(command: String): String? =
        if (BROWSER.containsMatchIn(command) && INSTALL.containsMatchIn(command))
            "Refused: do not install or run a browser or browser-testing library (puppeteer, playwright, chromium, jsdom). It adds hundreds of files and is not needed: " +
                "PocketIDE checks pages itself (the Preview tab and the automatic page check after your changes). Check your work with node -c, by re-reading files, or with small node scripts that do not need a browser."
        else null

    private val OUTSIDE = listOf(
        Regex("""\bcd\s+(/[^\s;&|]*)"""),
        Regex(""">>?\s*(/[^\s;&|]*)"""),
        Regex("""\btee\s+(?:-a\s+)?(/[^\s;&|]*)"""),
        Regex("""\b(?:mkdir|touch|rm|mv|cp)\b[^;&|\n]*?\s(/[^\s;&|]*)"""))

    /**
     * A message when the command works at an absolute path outside the project (a made-up /work/game, say): commands run in the
     * project folder, so such a path writes nowhere useful. [root] is the project folder's path. /tmp and /dev are allowed.
     */
    fun outsideRefusal(command: String, root: String): String? {
        val base = root.trimEnd('/')
        for (re in OUTSIDE) for (m in re.findAll(command)) {
            val p = m.groupValues[1]
            if (p == "/" || p.startsWith("/dev/") || p == "/tmp" || p.startsWith("/tmp/") || p == base || p.startsWith("$base/")) continue
            return "Refused: $p is outside the project. Commands run inside the project folder, so use paths relative to it (for example game/main.js), and do not cd to another folder."
        }
        return null
    }

    private val EXTRACT = Regex("""(?i)\b(unzip|gunzip|bunzip2|unxz|7z\s+x|tar\s+-?\w*x\w*)\b""")
    private val ARCHIVE = Regex("""[^\s'"]+\.(zip|tar|tgz|gz|bz2|xz|7z)\b""", RegexOption.IGNORE_CASE)
    const val BIG_ARCHIVE = 50L * 1024 * 1024

    /**
     * A message when the command would unpack a big archive (over 50 MB) that the request did not name: such caches and
     * downloads are the person's data, not something to open on a whim. [size] gives a project file's size in bytes.
     */
    fun archiveRefusal(command: String, request: String, size: (String) -> Long): String? {
        if (!EXTRACT.containsMatchIn(command)) return null
        for (m in ARCHIVE.findAll(command)) {
            val path = m.value.trimStart('.', '/')
            val name = path.substringAfterLast('/')
            if (size(path) > BIG_ARCHIVE && !request.contains(name, ignoreCase = true))
                return "Refused: $name is a big archive (over 50 MB) and the request does not ask to unpack it. Work with the files already in the project; if you really need it, ask the person."
        }
        return null
    }

    private val READERS = Regex("""(?i)\b(cat|grep|egrep|fgrep|rg|head|tail|sed|awk|less|more|strings|wc|xxd|od|cut)\b""")

    /** True when the command reads inside the bundled game engine folder, which is a huge minified file. */
    fun readsEngine(command: String): Boolean = command.contains(GameTemplates.ENGINE_DIR) && READERS.containsMatchIn(command)

    const val ENGINE_REFUSAL = "Refused: engine/ is a minified game engine (one huge line). Do not read or search it. Use the engine's documented functions from your own files."
}
