package dev.pocketide.app.core

/**
 * How the assistant uses a project's history: its finished work is committed under its own name, and its history tool reads
 * status, commits, diffs, blame and file history (so it can answer "when did this break?" from facts). Pure logic.
 */
object VcsAssist {
    /** The commit message for the assistant's work: the request as the title, the start of its reply as the body. */
    fun commitMessage(request: String, reply: String): String {
        val title = request.trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().replace(Regex("\\s+"), " ").let { if (it.length > 64) it.take(61).trimEnd() + "…" else it }
        val body = reply.trim().take(700)
        return "Assistant: " + title.ifEmpty { "changes" } + (if (body.isEmpty()) "" else "\n\n$body")
    }

    const val TOOL_DESC = "Read this project's version history (it is kept like a GitHub repository). action: status (uncommitted changes), " +
        "log (recent commits; path= for one file), show (one commit's message and changes: commit=), diff (a file's uncommitted change: path=), " +
        "blame (which commit last changed each line of path=), branches. Use it to find when and why something changed instead of guessing. " +
        "Your finished work is committed automatically, so you do not need to commit."

    private fun short(c: VcsStore.Commit, assistant: Boolean = c.author.email == Vcs.ASSISTANT.email) =
        "${c.short} ${if (assistant) "(assistant)" else "(${c.author.name})"} ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(c.author.time * 1000))}: ${c.title}"

    private fun ops(rows: List<DiffOp>?, cap: Int = 120): String =
        if (rows == null) "(binary or very large file)\n" else Diff.compact(rows).take(cap).joinToString("") { if (it.tag == '~') "  … ${it.skipped} unchanged lines\n" else "${it.tag} ${it.text}\n" }

    /** Answers one history tool call. */
    fun run(v: Vcs, action: String, path: String, commit: String): String {
        if (!v.enabled()) return "This project does not keep version history yet (the person can start it on the project page, History tab)."
        return when (action.trim().lowercase()) {
            "status" -> {
                val st = v.status()
                "On branch ${v.branch() ?: "(detached)"}." + (if (v.merging()) " A merge is in progress." else "") +
                    if (st.isEmpty()) " No uncommitted changes." else "\nUncommitted changes:\n" + st.take(100).joinToString("\n") { "${it.kind} ${it.path}" }
            }
            "log" -> {
                val list = v.log(null, 30, path.ifBlank { null })
                if (list.isEmpty()) "No commits" + (if (path.isNotBlank()) " touch $path." else ".") else list.joinToString("\n") { short(it) }
            }
            "show" -> {
                val id = v.store.resolve(commit.ifBlank { "HEAD" }.let { if (it == "HEAD") v.store.headId() ?: "" else it }) ?: return "No commit '$commit'."
                val c = v.store.readCommit(id) ?: return "No commit '$commit'."
                val sb = StringBuilder(short(c) + "\n" + c.message.trim() + "\n\n")
                for (f in v.changes(c.id)) {
                    if (sb.length > 12000) { sb.append("… more files\n"); break }
                    sb.append("## ${f.kind} ${f.path} (+${f.added} -${f.removed})\n").append(ops(v.diff(f.before, f.after)))
                }
                sb.toString()
            }
            "diff" -> if (path.isBlank()) "Give path=." else "Uncommitted change to $path:\n" + ops(v.diffWorking(path), 300)
            "blame" -> {
                if (path.isBlank()) return "Give path=."
                val rows = v.blame(path)
                if (rows.isEmpty()) "$path is not in the last commit." else rows.take(400).mapIndexed { i, (l, c) -> "${i + 1} ${c?.short ?: "-------"} ${l.take(160)}" }.joinToString("\n") +
                    "\n\nCommits named above:\n" + rows.mapNotNull { it.second }.distinctBy { it.id }.take(30).joinToString("\n") { short(it) }
            }
            "branches" -> v.branches().keys.joinToString("\n") { (if (it == v.branch()) "* " else "  ") + it }.ifEmpty { "No branches yet." }
            else -> "Unknown action '$action'. Use status, log, show, diff, blame or branches."
        }
    }
}
