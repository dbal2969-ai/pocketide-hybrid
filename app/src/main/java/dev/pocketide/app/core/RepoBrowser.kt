package dev.pocketide.app.core

/** One row of the repo page: a file or a folder. [modified] of a folder is the newest change inside it. */
data class RepoEntry(val name: String, val path: String, val isDir: Boolean, val size: Long, val modified: Long)

/** One step of the path shown above the file list. [path] is "" for the project root. */
data class Crumb(val label: String, val path: String)

/**
 * The logic behind the GitHub-style repo page: what is in a folder, how to show the path, which README to show, and how long ago things changed.
 * Kept free of Android classes so it can be tested.
 */
object RepoBrowser {
    private val README = Regex("""^readme(\.(md|markdown|txt|rst))?$""", RegexOption.IGNORE_CASE)
    private val README_ORDER = listOf("md", "markdown", "txt", "rst", "")

    private fun prefix(dir: String): String = if (dir.isEmpty()) "" else dir.trim('/') + "/"

    /** Folders that are almost never wanted in the list: caches, dependencies and scratch space. Shown on request. */
    val NOISE = setOf("__pycache__", "node_modules", "tmp", "dist", "build", "venv", "target")

    /** True for a folder entry that is hidden by default. */
    fun isNoise(e: RepoEntry): Boolean = e.isDir && e.name.lowercase() in NOISE

    /** Folders first, then files, each A to Z. Hidden names (starting with a dot) are left out. [info] gives a file's size and modified time. */
    fun entries(files: List<String>, dirs: List<String>, dir: String, info: (String) -> Pair<Long, Long>): List<RepoEntry> {
        val pre = prefix(dir)
        val folderTime = LinkedHashMap<String, Long>()
        val out = ArrayList<RepoEntry>()
        for (p in files) {
            if (!p.startsWith(pre)) continue
            val rest = p.substring(pre.length)
            if (rest.isEmpty()) continue
            val slash = rest.indexOf('/')
            if (slash < 0) {
                if (rest.startsWith(".")) continue
                val (size, time) = info(p)
                out.add(RepoEntry(rest, p, false, size, time))
            } else {
                val name = rest.substring(0, slash)
                if (name.startsWith(".")) continue
                val time = info(p).second
                folderTime[name] = maxOf(folderTime[name] ?: 0L, time)
            }
        }
        for (d in dirs) {
            val clean = d.trim('/')
            if (!(clean + "/").startsWith(pre) || clean.length + 1 <= pre.length) continue
            val name = clean.substring(pre.length).substringBefore('/')
            if (name.isEmpty() || name.startsWith(".")) continue
            if (name !in folderTime) folderTime[name] = 0L
        }
        for ((name, time) in folderTime) out.add(RepoEntry(name, pre + name, true, 0L, time))
        return out.sortedWith(compareBy<RepoEntry>({ !it.isDir }, { it.name.lowercase() }))
    }

    /** The project name, then one step per folder: ["app", "app/src"] for "app/src". */
    fun crumbs(project: String, dir: String): List<Crumb> {
        val out = arrayListOf(Crumb(project, ""))
        var path = ""
        for (seg in dir.trim('/').split('/').filter { it.isNotEmpty() }) {
            path = if (path.isEmpty()) seg else "$path/$seg"
            out.add(Crumb(seg, path))
        }
        return out
    }

    fun parent(dir: String): String = dir.trim('/').substringBeforeLast('/', "")

    /** The README in [dir] (README.md first, then .markdown, .txt, .rst, no extension), or null. */
    fun findReadme(files: List<String>, dir: String): String? {
        val pre = prefix(dir)
        val here = files.filter { it.startsWith(pre) && !it.substring(pre.length).contains('/') && README.matches(it.substring(pre.length)) }
        fun rank(p: String): Int {
            val name = p.substring(pre.length)
            val ext = if (name.contains('.')) name.substringAfterLast('.').lowercase() else ""
            val i = README_ORDER.indexOf(ext)
            return if (i < 0) 99 else i
        }
        return here.minByOrNull { rank(it) }
    }

    /** "3 hours ago" style text. Empty when the time is unknown. */
    fun relativeTime(now: Long, then: Long): String {
        if (then <= 0L) return ""
        val s = (now - then) / 1000
        fun unit(n: Long, word: String) = "$n $word${if (n == 1L) "" else "s"} ago"
        return when {
            s < 60 -> "just now"
            s < 3600 -> unit(s / 60, "minute")
            s < 86400 -> unit(s / 3600, "hour")
            s < 2 * 86400 -> "yesterday"
            s < 7 * 86400 -> unit(s / 86400, "day")
            s < 30 * 86400 -> unit(s / (7 * 86400), "week")
            s < 365 * 86400 -> unit(s / (30 * 86400), "month")
            else -> unit(s / (365 * 86400), "year")
        }
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}

enum class MdKind { H1, H2, H3, PARA, BULLET, NUMBERED, QUOTE, CODE, RULE, TASK, DIAGRAM }

/** One block; a TASK knows its line in the file (to tick it there) and whether it is done; a CODE or DIAGRAM block its language. */
data class MdBlock(val kind: MdKind, val text: String, val line: Int = -1, val checked: Boolean = false, val lang: String = "")

/** A small Markdown reader for the README card: headings, paragraphs, lists, quotes, code blocks and rules. Links, bold and code marks are removed, not styled. */
object Markdown {
    private val IMAGE = Regex("""!\[([^\]]*)]\([^)]*\)""")
    private val LINK = Regex("""\[([^\]]*)]\([^)]*\)""")
    private val TAG = Regex("""<[^>]+>""")
    private val RULE = Regex("""^\s*(-{3,}|\*{3,}|_{3,})\s*$""")
    private val BULLET = Regex("""^\s*[-*+]\s+(.*)$""")
    private val NUMBER = Regex("""^\s*(\d+)[.)]\s+(.*)$""")
    private val HEAD = Regex("""^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val TASK = Regex("""^\s*[-*+]\s+\[([ xX])]\s+(.*)$""")

    /** The file with the task list item on [line] ticked or unticked. */
    fun toggleTask(text: String, line: Int, done: Boolean): String {
        val lines = text.split("\n").toMutableList()
        if (line !in lines.indices) return text
        val m = TASK.matchEntire(lines[line]) ?: return text
        lines[line] = lines[line].replaceFirst("[${m.groupValues[1]}]", if (done) "[x]" else "[ ]")
        return lines.joinToString("\n")
    }

    fun inline(s: String): String =
        s.replace(IMAGE, "$1").replace(LINK, "$1").replace(TAG, "")
            .replace("**", "").replace("__", "").replace("`", "").trim()

    fun blocks(text: String, max: Int = 200): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        val para = StringBuilder()
        fun flush() { if (para.isNotEmpty()) { out.add(MdBlock(MdKind.PARA, inline(para.toString()))); para.setLength(0) } }
        val lines = text.replace("\r\n", "\n").split('\n')
        var i = 0
        while (i < lines.size && out.size < max) {
            val line = lines[i]
            val t = line.trim()
            if (t.startsWith("```") || t.startsWith("~~~")) {
                flush()
                val fence = t.take(3)
                val lang = t.drop(3).trim().lowercase()
                val code = ArrayList<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith(fence)) { code.add(lines[i]); i++ }
                out.add(MdBlock(if (lang == "mermaid") MdKind.DIAGRAM else MdKind.CODE, code.joinToString("\n"), lang = lang))
                i++
                continue
            }
            val head = HEAD.matchEntire(line)
            when {
                t.isEmpty() -> flush()
                RULE.matches(line) -> { flush(); out.add(MdBlock(MdKind.RULE, "")) }
                head != null -> {
                    flush()
                    val kind = when (head.groupValues[1].length) { 1 -> MdKind.H1; 2 -> MdKind.H2; else -> MdKind.H3 }
                    out.add(MdBlock(kind, inline(head.groupValues[2])))
                }
                TASK.matches(line) -> { flush(); val m = TASK.matchEntire(line)!!; out.add(MdBlock(MdKind.TASK, inline(m.groupValues[2]), i, m.groupValues[1] != " ")) }
                BULLET.matches(line) -> { flush(); out.add(MdBlock(MdKind.BULLET, inline(BULLET.matchEntire(line)!!.groupValues[1]))) }
                NUMBER.matches(line) -> { flush(); val m = NUMBER.matchEntire(line)!!; out.add(MdBlock(MdKind.NUMBERED, m.groupValues[1] + ". " + inline(m.groupValues[2]))) }
                t.startsWith(">") -> { flush(); out.add(MdBlock(MdKind.QUOTE, inline(t.removePrefix(">").trim()))) }
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(t) }
            }
            i++
        }
        flush()
        return out.filter { it.kind == MdKind.RULE || it.kind == MdKind.CODE || it.kind == MdKind.DIAGRAM || it.text.isNotEmpty() }
    }

    /** The first sentence-like paragraph of a README, for the repositories list. Headings, badges and images are skipped. */
    fun describe(readme: String, limit: Int = 90): String {
        val plain = readme.lines().filter { l ->
            val t = l.trim()
            !(t.startsWith("[![") || t.startsWith("![") || t.startsWith("<img") || (t.startsWith("<a") && t.contains("<img")))
        }.joinToString("\n")
        val first = blocks(plain, 40).firstOrNull { b -> b.kind == MdKind.PARA && b.text.length > 3 }?.text ?: return ""
        return if (first.length <= limit) first else first.take(limit - 1).trimEnd() + "…"
    }
}

/** One row of the "Your repositories" list. */
data class RepoCard(val name: String, val files: Int, val modified: Long, val description: String, val github: String, val open: Boolean)

object RepoList {
    /** Most recently changed first; ties A to Z. */
    fun order(cards: List<RepoCard>): List<RepoCard> =
        cards.sortedWith(compareByDescending<RepoCard> { it.modified }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}
