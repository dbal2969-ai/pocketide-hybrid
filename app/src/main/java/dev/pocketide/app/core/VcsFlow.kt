package dev.pocketide.app.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The GitHub flow on the phone, where history and records meet: the assistant's work lands on its own branch with a pull
 * request when the branch is protected (or when asked), pull requests merge with their branch rules, and releases get notes.
 * Pure logic over [Vcs] and [Collab].
 */
object VcsFlow {
    class Committed(val commit: String?, val branch: String?, val pull: Int?, val note: String)

    /** True when the assistant should not commit straight onto [branch]. */
    fun ownBranch(c: Collab, branch: String?): Boolean = branch != null && !branch.startsWith("assistant/") && c.issueOnBranch(branch) == null &&
        (branch in c.rules.protect || c.rules.assistantBranches)

    /**
     * Commits the assistant's finished work. On a protected branch (or with "assistant works on its own branches") it first
     * moves to a new branch assistant/<time> and opens a pull request back into the branch it left; later work in that
     * branch updates the same pull request.
     */
    fun assistantCommit(v: Vcs, c: Collab, request: String, reply: String, paths: List<String>, now: Long = System.currentTimeMillis()): Committed {
        if (!v.enabled() || v.merging()) return Committed(null, v.branch(), null, "")
        val from = v.branch()
        var pull: Int? = null; var note = ""
        if (ownBranch(c, from)) {
            val name = "assistant/" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
            v.createBranch(name)
            v.switchTo(name, Vcs.Carry.CARRY)
            val title = request.trim().lineSequence().firstOrNull().orEmpty().take(70).ifEmpty { "Assistant changes" }
            val id = v.commit(VcsAssist.commitMessage(request, reply), paths, Vcs.ASSISTANT)
            if (id == null) return Committed(null, name, null, "")
            val p = c.openPull(title, "Changes the assistant made for:\n\n> " + request.trim().take(600).replace("\n", "\n> ") + "\n\n" + reply.trim().take(1500), "", name, from!!, false, "assistant", now)
            pull = p.number
            note = "${from} is protected, so the assistant's work is on $name with pull request #${p.number}."
            return Committed(id, name, pull, note)
        }
        val id = v.commit(VcsAssist.commitMessage(request, reply), paths, Vcs.ASSISTANT)
        // work on an assistant branch keeps its pull request up to date
        c.pulls.firstOrNull { it.open && it.fromProject.isEmpty() && it.fromBranch == from }?.let { it.updated = now; c.save(); pull = it.number }
        // an issue assigned to the assistant: its first commit opens the pull request that closes it
        val issue = c.issueOnBranch(from)
        if (id != null && pull == null && issue != null && from != null) {
            val p = c.openPull("Fixes #${issue.number}: ${issue.title}", "Fixes #${issue.number}\n\n" + reply.trim().take(1500), "", from, issue.base.ifEmpty { "main" }, false, "assistant", now)
            issue.pull = p.number; issue.updated = now; c.save()
            pull = p.number; note = "Opened pull request #${p.number} for issue #${issue.number}."
        }
        return Committed(id, from, pull, note)
    }

    /** A name for a branch made because a commit was aimed at a protected one. */
    fun workBranchName(now: Long = System.currentTimeMillis()): String = "work-" + SimpleDateFormat("MMdd-HHmm", Locale.US).format(Date(now))

    /**
     * Merges pull request [p] in [v] (the project it targets): switches to its target, brings in a fork's history, then merges
     * or squashes. Returns the result; on success the pull request is marked merged (and its issues closed).
     */
    fun mergePull(v: Vcs, c: Collab, p: Collab.Pull, squash: Boolean, forkRoot: java.io.File?, author: VcsStore.Person = Vcs.YOU): Vcs.MergeResult {
        require(v.status().isEmpty()) { "Commit or stash your changes first." }
        if (p.fromProject.isNotEmpty()) { require(forkRoot != null) { "The fork ${p.fromProject} is gone." }; v.fetchFrom(forkRoot, Collab.remoteName(p.fromProject)) }
        if (v.branch() != p.into) v.switchTo(p.into, Vcs.Carry.CARRY)
        val ref = p.sourceRef()
        val msg = "${p.title} (#${p.number})" + (if (p.body.isBlank()) "" else "\n\n" + p.body.trim().take(2000))
        val r = if (squash) v.squashMerge(ref, msg, author) else v.merge(ref, author, "Merge pull request #${p.number} from ${p.fromBranch}\n\n${p.title}")
        if (r.conflicts.isEmpty() && (r.commit != null || r.fastForward || r.upToDate)) {
            c.merged(p, r.commit ?: v.store.headId())
            if (c.rules.deleteMerged && p.fromProject.isEmpty() && p.fromBranch != p.into && p.fromBranch in v.branches() && p.fromBranch != v.branch()) v.deleteBranch(p.fromBranch)
        }
        return r
    }

    /** Release notes from the commits since the previous release's tag. */
    fun releaseNotes(v: Vcs, c: Collab, commit: String): String {
        val prev = c.releases.filter { !it.draft }.maxByOrNull { it.time }?.commit
        val list = if (prev != null && prev != commit) v.ahead(prev, commit, 100) else v.log(commit, 100)
        val merged = list.filter { it.title.startsWith("Merge pull request #") || Regex("""\(#\d+\)$""").containsMatchIn(it.title) }
        val sb = StringBuilder("## What's changed\n")
        for (k in list.filter { it.parents.size < 2 }.take(60)) sb.append("- ").append(k.title).append(if (k.author.email == Vcs.ASSISTANT.email) " (assistant)" else "").append(" ").append(k.short).append('\n')
        if (merged.isNotEmpty()) sb.append("\n").append(merged.size).append(" pull request(s) merged.\n")
        return sb.toString()
    }

    /** The files of [commit] as a zip (the "Source code" of a release). */
    fun zipCommit(v: Vcs, commit: String, out: java.io.OutputStream, folder: String) {
        java.util.zip.ZipOutputStream(out).use { z ->
            for ((p, e) in v.filesAt(commit)) {
                val data = v.content(e.second) ?: continue
                z.putNextEntry(java.util.zip.ZipEntry("$folder/$p")); z.write(data); z.closeEntry()
            }
        }
    }
}
