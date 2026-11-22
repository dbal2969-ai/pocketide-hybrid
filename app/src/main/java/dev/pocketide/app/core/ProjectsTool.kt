package dev.pocketide.app.core

import java.io.File

/**
 * The person's other projects, for the assistant (1.5.168). The chat with no project could not touch any project: asked to "take
 * files from one project and make a new branch on my Empty-project-10 with them", it said that was not possible. In the Claude or
 * Copilot app the same request just works. This tool lists the projects, reads and searches any of them, copies files and folders
 * from one project to another, and makes a branch or a commit in a project's own history. Writes are allowed only when [canWrite]
 * says so (the chat with no project, or Agent mode in a project); every file a write replaces is kept first ([keep]), so Undo works.
 * Pure file code: the app runs it off the main thread.
 */
class ProjectsTool(
    private val root: File,
    /** The open project's folder name ("" or a dot name for the chat with no project). */
    private val current: String,
    private val canWrite: () -> Boolean,
    /** Called with (project, path, old bytes or null) before a file is written or replaced in that project. */
    private val keep: (String, String, ByteArray?) -> Unit = { _, _, _ -> },
) {
    companion object {
        const val NAME = "projects"
        const val DESC = "Work with the person's other projects in PocketIDE (and this one). action: list (every project, with its size " +
            "and branch); files (project, path: a folder); read (project, path, start, end); search (project, pattern, path); copy " +
            "(from: project, to: project, paths: files or folders in 'from', comma-separated, empty for all of it, dest: folder in 'to', " +
            "empty for the same paths); branch (project, name: makes the branch from the current one and switches to it); commit " +
            "(project, message: saves every change in that project's history). Use it whenever the person names another project; " +
            "projects are found by name, so you do not need a path from them."
        const val MAX_COPY = 5000
        const val MAX_BYTES = 300L * 1024 * 1024
        private val SKIP = setOf(".git", ".pocketide", "node_modules", "__pycache__", ".gradle", "build", ".venv", "venv")
    }

    private fun names(): List<String> = root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }
        .map { it.name }.sortedWith(String.CASE_INSENSITIVE_ORDER)

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    /** The project named [name], matched loosely ("empty project 10" finds Empty-project-10); "this"/"" is the open one. */
    fun find(name: String): String? {
        val n = name.trim()
        if ((n.isEmpty() || n.equals("this", true) || n.equals("current", true)) && current.isNotEmpty() && !current.startsWith(".")) return current
        if (n.isEmpty()) return null
        val all = names()
        all.firstOrNull { it == n }?.let { return it }
        all.firstOrNull { it.equals(n, true) }?.let { return it }
        val k = norm(n)
        if (k.isEmpty()) return null
        all.firstOrNull { norm(it) == k }?.let { return it }
        val part = all.filter { norm(it).contains(k) || k.contains(norm(it)) }
        return part.singleOrNull()
    }

    /** Projects whose names loosely contain [name] (or are contained in it), for the ambiguity message. */
    fun matches(name: String): List<String> { val k = norm(name); return if (k.isEmpty()) emptyList() else names().filter { norm(it).contains(k) || k.contains(norm(it)) } }

    // more than one loose match: never pick one, ask which (Copilot PR #29)
    private fun missing(name: String): String {
        val m = matches(name)
        if (m.size > 1) return "Several projects match '$name': " + m.joinToString(", ") + ". Ask the person which one they mean, then use its exact name."
        return "No project called '$name'. Projects: " + names().joinToString(", ").ifEmpty { "(none)" } + "."
    }

    private fun safe(dir: File, path: String): File? {
        val p = path.trim().removePrefix("./").trim('/')
        if (p.split('/').any { it == ".." } || p.contains('\u0000')) return null
        val f = if (p.isEmpty()) dir.canonicalFile else File(dir, p).canonicalFile
        val base = dir.canonicalPath
        return if (f.path == base || f.path.startsWith(base + File.separator)) f else null
    }

    private fun walk(base: File, prefix: String, out: MutableList<String>, limit: Int) {
        for (k in base.listFiles().orEmpty().sortedBy { it.name }) {
            if (out.size >= limit) return
            if (k.isDirectory) { if (k.name !in SKIP && !k.name.startsWith(".")) walk(k, prefix + k.name + "/", out, limit) }
            else out.add(prefix + k.name)
        }
    }

    private fun files(dir: File, under: String, limit: Int = 50_000): List<String> {
        val start = safe(dir, under) ?: return emptyList()
        if (start.isFile) return listOf(under.trim('/'))
        val out = ArrayList<String>()
        val pre = under.trim().removePrefix("./").trim('/').let { if (it.isEmpty()) "" else "$it/" }
        walk(start, pre, out, limit)
        return out
    }

    private fun isText(b: ByteArray) = b.take(8000).none { it.toInt() == 0 }

    fun run(a: Map<String, Any?>): String {
        fun g(k: String) = (a[k] as? String)?.trim().orEmpty()
        return try {
            when (g("action").lowercase()) {
                "list", "" -> list()
                "files" -> { val p = find(g("project")) ?: return missing(g("project")); val dir = File(root, p)
                    val fs = files(dir, g("path"))
                    if (fs.isEmpty()) "Nothing under '${g("path").ifEmpty { "/" }}' in $p." else "Project $p:\n" + AgentActions.list(fs, { File(dir, it).length() }, g("path")) }
                "read" -> read(g("project"), g("path"), (a["start"] as? Number)?.toInt() ?: g("start").toIntOrNull(), (a["end"] as? Number)?.toInt() ?: g("end").toIntOrNull())
                "search" -> { val p = find(g("project")) ?: return missing(g("project")); val dir = File(root, p)
                    val fs = files(dir, g("path"), 20_000)
                    val r = AgentActions.grep(fs, { f -> File(dir, f).takeIf { it.length() < 2_000_000 }?.readBytes()?.takeIf { isText(it) }?.toString(Charsets.UTF_8) }, g("pattern"), g("path"))
                    "Project $p: " + r.text }
                "copy" -> copy(g("from"), g("to"), g("paths"), g("dest"))
                "branch" -> branch(g("project"), g("name"))
                "commit" -> commit(g("project"), g("message"))
                else -> "Unknown action '${g("action")}'. Use list, files, read, search, copy, branch or commit."
            }
        } catch (e: Exception) { "projects failed: " + (e.message ?: e.javaClass.simpleName) }
    }

    private fun list(): String {
        val all = names()
        if (all.isEmpty()) return "There are no projects yet."
        return "Projects (" + all.size + "):\n" + all.joinToString("\n") { n ->
            val d = File(root, n)
            val count = ArrayList<String>().also { walk(d, "", it, 5000) }.size
            val v = Vcs(d)
            val br = if (v.enabled()) " · branch " + (v.branch() ?: "?") else " · no history yet"
            n + (if (n == current) " (open)" else "") + " · " + (if (count >= 5000) "5000+" else count.toString()) + " files" + br
        }
    }

    private fun read(project: String, path: String, start: Int?, end: Int?): String {
        val p = find(project) ?: return missing(project)
        val f = safe(File(root, p), path) ?: return "Refused: '$path' is not a path inside $p."
        if (!f.isFile) return "No file $path in $p." + if (f.isDirectory) " It is a folder: use action files." else ""
        val b = f.readBytes()
        if (!isText(b)) return "$p/$path is a binary file (${b.size} bytes); copy it with action copy instead of reading it."
        val lines = b.toString(Charsets.UTF_8).split("\n")
        val s = (start ?: 1).coerceAtLeast(1); val e = (end ?: lines.size).coerceAtMost(lines.size)
        val body = (s..e).joinToString("\n") { "$it: " + lines[it - 1] }
        val cut = body.take(40_000)
        return "$p/$path (${lines.size} lines):\n" + cut + if (cut.length < body.length) "\n… cut; read a smaller range with start and end." else ""
    }

    private fun copy(from: String, to: String, paths: String, dest: String): String {
        if (!canWrite()) return "Not allowed here: copying into a project needs the chat with no project, or Agent mode."
        val src = find(from) ?: return missing(from)
        val dst = find(to) ?: return missing(to)
        val sDir = File(root, src); val dDir = File(root, dst)
        val picked = paths.split(',').map { it.trim().removePrefix("./").trim('/') }.filter { it.isNotEmpty() }.ifEmpty { listOf("") }
        val all = LinkedHashSet<String>()
        for (p in picked) {
            if (safe(sDir, p)?.exists() != true) return "No file or folder '$p' in $src."
            all.addAll(files(sDir, p, MAX_COPY + 1))
        }
        if (all.size > MAX_COPY) return "That is more than $MAX_COPY files; copy smaller folders one at a time."
        val total = all.sumOf { File(sDir, it).length() }
        if (total > MAX_BYTES) return "That is ${total / (1024 * 1024)} MB; copy smaller folders one at a time (up to ${MAX_BYTES / (1024 * 1024)} MB)."
        val d = dest.trim().removePrefix("./").trim('/')
        // with a dest folder, each picked folder lands inside it under its own name; without one, paths stay as they were
        fun target(p: String): String {
            if (d.isEmpty()) return p
            val top = picked.filter { it.isNotEmpty() && (p == it || p.startsWith("$it/")) }.maxByOrNull { it.length }
            val rel = if (top == null) p else top.substringAfterLast('/') + p.removePrefix(top)
            return "$d/$rel"
        }
        var made = 0; var replaced = 0; var same = 0
        for (p in all) {
            val t = target(p)
            val out = safe(dDir, t) ?: return "Refused: '$t' is not a path inside $dst."
            if (src == dst && out.canonicalPath == File(sDir, p).canonicalPath) { same++; continue }
            val bytes = File(sDir, p).readBytes()
            val old = if (out.isFile) out.readBytes() else null
            if (old != null && old.contentEquals(bytes)) { same++; continue }
            keep(dst, t, old)
            out.parentFile?.mkdirs(); out.writeBytes(bytes)
            if (old == null) made++ else replaced++
        }
        return "Copied from $src to $dst: $made new file${if (made == 1) "" else "s"}, $replaced replaced, $same already the same" +
            (if (d.isEmpty()) "" else ", into $d/") + "." + (if (Vcs(dDir).enabled()) " Use action commit to save them on ${Vcs(dDir).branch() ?: "the current branch"}." else "")
    }

    private fun branch(project: String, name: String): String {
        if (!canWrite()) return "Not allowed here: making a branch needs the chat with no project, or Agent mode."
        val p = find(project) ?: return missing(project)
        val n = name.trim().replace(Regex("\\s+"), "-")
        val v = Vcs(File(root, p))
        val started = if (!v.enabled()) { v.start(Vcs.ASSISTANT); true } else false
        if (n in v.branches().keys) { v.switchTo(n); return "Switched $p to its existing branch $n." }
        v.createBranch(n)
        val msg = v.switchTo(n)
        return (if (started) "Started version history in $p (first commit of every file). " else "") + "Made branch $n in $p and switched to it." + msg.substringAfter(".", "").let { if (it.isBlank()) "" else " " + it.trim() }
    }

    private fun commit(project: String, message: String): String {
        if (!canWrite()) return "Not allowed here: committing needs the chat with no project, or Agent mode."
        val p = find(project) ?: return missing(project)
        val v = Vcs(File(root, p))
        if (!v.enabled()) v.start(Vcs.ASSISTANT)
        val id = v.commit(message.ifBlank { "Changes made by the assistant" }, null, Vcs.ASSISTANT) ?: return "Nothing to commit in $p: no changes since the last commit."
        return "Committed ${id.take(7)} on ${v.branch() ?: "?"} in $p."
    }
}
