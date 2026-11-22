package dev.pocketide.app

import android.app.AlertDialog
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.pocketide.app.core.Collab
import dev.pocketide.app.core.Suggestions
import dev.pocketide.app.core.Vcs
import dev.pocketide.app.core.VcsFlow
import java.io.File

/**
 * GitHub's collaboration screens for a project on the phone: forks (with sync and compare), pull requests (files, commits,
 * checks, reviews, line suggestions, merge or squash, auto-merge, drafts), checks that run after each commit, branch rules,
 * and releases with their files. Records live in [Collab]; history work goes through [Vcs] and [VcsFlow].
 */
object CollabUi {
    private fun toast(h: VcsUi.Host, s: String) = Toast.makeText(h.ctx, s, Toast.LENGTH_LONG).show()
    private fun pad(v: View, l: Int, t: Int, r: Int, b: Int) = v.setPadding(Ui.dp(v.context, l), Ui.dp(v.context, t), Ui.dp(v.context, r), Ui.dp(v.context, b))
    private fun scrollOf(v: View) = ScrollView(v.context).also { it.addView(v) }
    private fun gap(h: VcsUi.Host) = Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(h.ctx, 6) }

    fun collab(h: VcsUi.Host): Collab = Collab(File(Projects.metaDir(h.ctx, h.store.root.name), "collab"))

    private fun projectDir(h: VcsUi.Host, name: String) = File(Projects.root(h.ctx), name)

    /** Projects forked from this one (their folders). */
    fun forksOf(h: VcsUi.Host): List<File> = Projects.list(h.ctx).map { projectDir(h, it) }.filter { it != h.store.root && Vcs(it).upstream()?.absolutePath == h.store.root.absolutePath }

    // ---------------------------------------------------------------- after a commit: checks, then auto-merge

    fun afterCommit(h: VcsUi.Host, commit: String) {
        val c = collab(h)
        if (!c.rules.runChecks) { tryAutoMerge(h); return }
        runChecks(h, commit) { tryAutoMerge(h) }
    }

    /** Runs the checks on [commit] (files are valid, no secrets, no conflict markers, and the project's tests when they can run). */
    fun runChecks(h: VcsUi.Host, commit: String, done: (List<Collab.Check>) -> Unit = {}) {
        VcsUi.bg(h.ctx, {
            val v = h.vcs; val now = System.currentTimeMillis()
            val texts = v.changes(commit).filter { it.after != null }.mapNotNull { f ->
                v.content(f.after!!)?.takeIf { it.size < 2_000_000 && !v.isBinary(it) }?.let { f.path to String(it, Charsets.UTF_8) } }
            val badJson = texts.filter { it.first.endsWith(".json", true) }.filter { (_, t) -> try { dev.pocketide.app.core.MiniJson.parse(t); false } catch (e: Exception) { true } }.map { it.first }
            val secrets = dev.pocketide.app.core.SecurityScan.scan(texts).filter { it.level == dev.pocketide.app.core.SecurityScan.Level.HIGH }
            val markers = texts.filter { dev.pocketide.app.core.VcsText.hasConflictMarkers(it.second) }.map { it.first }
            val list = listOf(
                Collab.Check("Valid JSON", badJson.isEmpty(), if (badJson.isEmpty()) "" else "Not valid JSON: " + badJson.joinToString(), now),
                Collab.Check("No secrets", secrets.isEmpty(), if (secrets.isEmpty()) "" else dev.pocketide.app.core.SecurityScan.summary(secrets), now),
                Collab.Check("No conflict markers", markers.isEmpty(), if (markers.isEmpty()) "" else "Still marked: " + markers.joinToString(), now))
            val plan = if (h.runCommand != null && v.store.headId() == commit && v.status().isEmpty())
                dev.pocketide.app.core.Verify.plan(h.store.list(), { p -> h.store.read(p) }, null) else null
            list to plan
        }) { (list, plan) ->
            fun finish(all: List<Collab.Check>) { val c = collab(h); c.setChecks(commit, all); done(all) }
            if (plan == null) { finish(list); return@bg }
            h.runCommand!!.invoke(plan.command) { code, out ->
                McpClient.onMain { finish(list + Collab.Check(plan.label, code == 0, if (code == 0) "" else out.takeLast(1500), System.currentTimeMillis())) }
            }
        }
    }

    fun checksLine(list: List<Collab.Check>?): String = when {
        list == null || list.isEmpty() -> "checks not run"
        list.all { it.ok == true } -> "✓ ${list.size} checks passed"
        else -> "✗ " + list.filter { it.ok == false }.joinToString { it.name } + " failed"
    }

    /** Merges every open pull request that asked for auto-merge and now can. */
    fun tryAutoMerge(h: VcsUi.Host) {
        val c = collab(h)
        val ready = c.pulls.filter { it.open && it.autoMerge }
        if (ready.isEmpty()) return
        VcsUi.bg(h.ctx, {
            val v = h.vcs; val out = ArrayList<String>()
            for (p in ready) {
                if (p.fromProject.isNotEmpty()) projectDir(h, p.fromProject).takeIf { it.isDirectory }?.let { v.fetchFrom(it, Collab.remoteName(p.fromProject)) }
                val head = v.store.resolve(p.sourceRef()) ?: continue
                if (c.blockers(p, c.checks[head], v.clashes(p.into, p.sourceRef())).isNotEmpty() || v.status().isNotEmpty()) continue
                val r = VcsFlow.mergePull(v, c, p, false, p.fromProject.takeIf { it.isNotEmpty() }?.let { projectDir(h, it) })
                if (r.conflicts.isEmpty()) out.add("#${p.number} merged automatically.")
            }
            out
        }) { msgs -> if (msgs.isNotEmpty()) { toast(h, msgs.joinToString(" ")); h.changed(emptyList()) } }
    }

    // ---------------------------------------------------------------- forks

    fun fork(h: VcsUi.Host, then: () -> Unit) {
        AlertDialog.Builder(h.ctx).setTitle("Fork ${h.store.root.name}?")
            .setMessage("A fork is your own copy of this project, history included, that remembers where it came from: you can bring in the original's new commits (Sync fork) and send your work back as a pull request.")
            .setPositiveButton("Fork") { _, _ ->
                VcsUi.bg(h.ctx, {
                    if (!h.vcs.enabled()) h.vcs.start()
                    val name = Projects.duplicate(h.ctx, h.store.root.name) ?: throw IllegalStateException("Could not copy the project.")
                    Vcs(projectDir(h, name)).setUpstream(h.store.root)
                    name
                }) { name ->
                    then()
                    AlertDialog.Builder(h.ctx).setTitle("Forked as $name").setMessage("Open the fork now?")
                        .setPositiveButton("Open") { _, _ -> Projects.switchTo(h.ctx, name) }.setNegativeButton("Stay here", null).show()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    fun syncFork(h: VcsUi.Host, then: () -> Unit) {
        val up = h.vcs.upstream() ?: run { toast(h, "This project is not a fork."); return }
        VcsUi.bg(h.ctx, {
            val v = h.vcs; val heads = v.fetchFrom(up, "upstream"); val b = v.branch() ?: "main"
            val target = if (b in heads) b else heads.keys.firstOrNull { it == "main" } ?: heads.keys.first()
            val behind = v.ahead("HEAD", "refs/remotes/upstream/$target").size
            Triple(target, behind, v.ahead("refs/remotes/upstream/$target", "HEAD").size)
        }) { (target, behind, ahead) ->
            if (behind == 0) { toast(h, "Up to date with ${up.name}/$target." + if (ahead > 0) " You are $ahead commit(s) ahead: open a pull request to send them." else ""); return@bg }
            AlertDialog.Builder(h.ctx).setTitle("Sync fork").setMessage("${up.name}/$target has $behind new commit(s). Merge them into ${h.vcs.branch()}?")
                .setPositiveButton("Merge") { _, _ -> VcsUi.bg(h.ctx, { h.vcs.merge("refs/remotes/upstream/$target", Vcs.YOU, "Sync with ${up.name}/$target") }) { r ->
                    h.changed(emptyList())
                    if (r.conflicts.isEmpty()) { toast(h, r.message); then() } else VcsUi.conflictsDialog(h, then)
                } }.setNegativeButton("Cancel", null).show()
        }
    }

    // ---------------------------------------------------------------- the Pull requests tab

    fun pullsTab(h: VcsUi.Host, body: LinearLayout, line: (LinearLayout, Boolean, String, Int, String, String, () -> Unit) -> Unit, card: () -> LinearLayout, refresh: () -> Unit) {
        val v = h.vcs
        val top = Ui.horizontal(h.ctx); pad(top, 0, 10, 0, 0)
        top.addView(Ui.button(h.ctx, "New pull request", true) { newPull(h, refresh) })
        top.addView(Ui.chip(h.ctx, "Fork") { fork(h, refresh) }, gap(h))
        if (v.upstream() != null) top.addView(Ui.chip(h.ctx, "Sync fork") { syncFork(h, refresh) }, gap(h))
        top.addView(Ui.chip(h.ctx, "Branch rules") { rulesDialog(h) }, gap(h))
        body.addView(android.widget.HorizontalScrollView(h.ctx).also { it.addView(top) })
        val c = collab(h)
        v.upstream()?.let { body.addView(Ui.text(h.ctx, "Forked from ${it.name}", 12f, Ui.MUTED).also { t -> pad(t, 2, 8, 2, 0) }) }
        for ((label, list) in listOf("Open" to c.pulls.filter { it.open }, "Closed" to c.pulls.filter { !it.open }.takeLast(30))) {
            body.addView(Ui.text(h.ctx, "${label.uppercase()} · ${list.size}", 12f, Ui.MUTED).also { it.typeface = Typeface.DEFAULT_BOLD; pad(it, 2, 16, 2, 0) })
            val box = card()
            if (list.isEmpty()) box.addView(Ui.text(h.ctx, if (label == "Open") "No open pull requests. Make a branch, commit, then open one to review and merge it." else "None.", 13f, Ui.MUTED).also { pad(it, 14, 14, 14, 14) })
            for ((i, p) in list.sortedByDescending { it.updated }.withIndex()) {
                val (mark, color) = when (p.state) { Collab.DRAFT -> "◌" to Ui.MUTED; Collab.MERGED -> "⑃" to 0xFFA371F7.toInt(); Collab.CLOSED -> "✕" to Ui.BAD; else -> "⇄" to Ui.GOOD }
                line(box, i == 0, mark, color, "#${p.number} ${p.title}", (if (p.fromProject.isEmpty()) p.fromBranch else "${p.fromProject}:${p.fromBranch}") + " → ${p.into}  ·  ${p.state}" +
                    (if (p.by == "assistant") "  ·  by the assistant" else "") + (if (p.autoMerge) "  ·  auto-merge" else "") + "  ·  " + VcsUi.ago(p.updated / 1000)) { pullDetail(h, p.number, refresh) }
            }
        }
    }

    fun newPull(h: VcsUi.Host, then: () -> Unit) {
        VcsUi.bg(h.ctx, {
            val v = h.vcs
            val local = v.branches().keys.toList()
            val forks = forksOf(h).flatMap { f -> Vcs(f).branches().keys.map { f.name to it } }
            Triple(local, forks, v.branch())
        }) { (local, forks, cur) ->
            if (local.size < 2 && forks.isEmpty()) { toast(h, "Make a branch with some commits first (⑂ → New branch), or fork the project."); return@bg }
            val sources = local.map { "" to it } + forks
            val labels = sources.map { (proj, b) -> if (proj.isEmpty()) b else "$proj (fork): $b" }
            AlertDialog.Builder(h.ctx).setTitle("Pull request from…").setItems(labels.toTypedArray()) { _, i ->
                val (proj, from) = sources[i]
                val targets = local.filter { proj.isNotEmpty() || it != from }
                val dflt = targets.indexOfFirst { it == "main" }.takeIf { it >= 0 } ?: targets.indexOf(cur).coerceAtLeast(0)
                AlertDialog.Builder(h.ctx).setTitle("…into").setSingleChoiceItems(targets.toTypedArray(), dflt, null).setPositiveButton("Next") { d, _ ->
                    val into = targets[(d as AlertDialog).listView.checkedItemPosition.coerceAtLeast(0)]
                    pullForm(h, proj, from, into, then)
                }.setNegativeButton("Cancel", null).show()
            }.show()
        }
    }

    private fun pullForm(h: VcsUi.Host, proj: String, from: String, into: String, then: () -> Unit) {
        VcsUi.bg(h.ctx, {
            val v = h.vcs
            val ref = if (proj.isEmpty()) "refs/heads/$from" else { v.fetchFrom(projectDir(h, proj), Collab.remoteName(proj)); "refs/remotes/${Collab.remoteName(proj)}/$from" }
            Triple(v.ahead(into, ref), v.compareBranches(into, ref), ref)
        }) { (commits, files, ref) ->
            if (commits.isEmpty()) { toast(h, "$from has nothing that $into does not already have."); return@bg }
            val box = Ui.vertical(h.ctx); pad(box, 18, 6, 18, 6)
            box.addView(Ui.text(h.ctx, "${commits.size} commit(s), ${files.size} file(s) changed  +${files.sumOf { it.added }} −${files.sumOf { it.removed }}", 12f, Ui.MUTED))
            val title = EditText(h.ctx); title.hint = "Title"; title.setText(commits.last().title.takeIf { commits.size == 1 } ?: from.replace('-', ' ').replace('/', ' ')); box.addView(title)
            val body = EditText(h.ctx); body.hint = "What it does and why (\"Fixes #3\" closes issue 3 when merged)"; body.minLines = 3
            body.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; box.addView(body)
            val draft = CheckBox(h.ctx); draft.text = "Draft (not ready to merge yet)"; draft.setTextColor(Ui.TEXT); box.addView(draft)
            box.addView(Ui.chip(h.ctx, "✨ Summarize the changes") {
                body.hint = "Summarizing…"
                VcsUi.bg(h.ctx, { commits.joinToString("\n") { "- " + it.title } + "\n\n" + diffText(h.vcs, into, ref) }) { text ->
                    VcsUi.quickAsk(h.ctx, h.settings, "You write pull request descriptions: a short summary paragraph, then a bullet list of the main changes. Plain words. Reply with the description only.", text) { t -> body.hint = ""; if (t != null) body.setText(t) }
                }
            }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(h.ctx, 6) })
            AlertDialog.Builder(h.ctx).setTitle("$from → $into").setView(scrollOf(box)).setPositiveButton("Open pull request") { _, _ ->
                val p = collab(h).openPull(title.text.toString(), body.text.toString(), proj, from, into, draft.isChecked)
                toast(h, "Opened #${p.number}."); then(); pullDetail(h, p.number, then)
            }.setNegativeButton("Cancel", null).show()
        }
    }

    private fun diffText(v: Vcs, base: String, head: String, cap: Int = 12000): String {
        val sb = StringBuilder()
        for (f in v.compareBranches(base, head)) {
            if (sb.length > cap) { sb.append("… more files\n"); break }
            sb.append("## ${f.kind} ${f.path}\n")
            val ops = v.diff(f.before, f.after)
            if (ops == null) sb.append("(binary)\n") else {
                // new-version line numbers, so suggestions can point at them
                var n = 0
                for (o in ops) { if (o.tag != '-') n++; if (o.tag != ' ') sb.append(if (o.tag == '+') "+$n " else "-   ").append(o.line).append('\n') }
            }
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- one pull request

    fun pullDetail(h: VcsUi.Host, n: Int, then: () -> Unit) {
        val c = collab(h); val p = c.pull(n) ?: return
        VcsUi.bg(h.ctx, {
            val v = h.vcs
            if (p.fromProject.isNotEmpty()) projectDir(h, p.fromProject).takeIf { it.isDirectory }?.let { v.fetchFrom(it, Collab.remoteName(p.fromProject)) }
            val ref = p.sourceRef(); val head = v.store.resolve(ref)
            val commits = if (head == null) emptyList() else v.ahead(p.into, ref)
            val files = if (head == null) emptyList() else v.compareBranches(p.into, ref)
            val clashes = if (head == null || !p.open) emptyList() else v.clashes(p.into, ref)
            Pair(Triple(head, commits, files), clashes)
        }) { (info, clashes) ->
            val (head, commits, files) = info
            val box = Ui.vertical(h.ctx); pad(box, 18, 6, 18, 10)
            box.addView(Ui.text(h.ctx, "${p.state.uppercase()} · ${if (p.fromProject.isEmpty()) p.fromBranch else "${p.fromProject}:${p.fromBranch}"} → ${p.into}" + (if (p.by == "assistant") " · by the assistant" else ""), 12f, Ui.MUTED))
            if (p.body.isNotBlank()) box.addView(Ui.text(h.ctx, p.body.trim(), 13f, Ui.TEXT).also { it.setTextIsSelectable(true); pad(it, 0, 8, 0, 0) })
            if (head == null && p.open) box.addView(Ui.text(h.ctx, "The branch ${p.fromBranch} is gone.", 13f, Ui.BAD))
            val checks = head?.let { c.checks[it] }
            fun section(t: String) = box.addView(Ui.text(h.ctx, t, 12f, Ui.MUTED).also { it.typeface = Typeface.DEFAULT_BOLD; pad(it, 0, 14, 0, 4) })
            if (p.open) {
                val why = c.blockers(p, checks, clashes)
                section("MERGE")
                box.addView(Ui.text(h.ctx, if (why.isEmpty()) "Ready to merge." else why.joinToString("\n") { "• $it" }, 13f, if (why.isEmpty()) Ui.GOOD else Ui.BAD))
            }
            section("CHECKS · " + checksLine(checks))
            checks?.filter { it.ok == false }?.forEach { k -> box.addView(Ui.text(h.ctx, "✗ ${k.name}: ${k.detail.take(400)}", 12f, Ui.BAD, true)) }
            section("COMMITS · ${commits.size}")
            for (k in commits.take(40)) box.addView(Ui.text(h.ctx, "${k.short}  ${k.title.take(70)}", 12f, Ui.TEXT, true).also { t -> t.setOnClickListener { VcsUi.commitDetail(h, k.id) } })
            section("FILES CHANGED · ${files.size}  +${files.sumOf { it.added }} −${files.sumOf { it.removed }}")
            for (f in files.take(200)) {
                val t = Ui.text(h.ctx, "${f.kind}  ${f.path}   +${f.added} −${f.removed}", 12f, when (f.kind) { 'A' -> Ui.GOOD; 'D' -> Ui.BAD; else -> Ui.ACCENT }, true)
                pad(t, 0, 4, 0, 4)
                t.setOnClickListener { VcsUi.bg(h.ctx, { h.vcs.diff(f.before, f.after) }) { VcsUi.showDiff(h.ctx, f.path, it) } }
                box.addView(t)
            }
            if (p.reviews.isNotEmpty() || p.comments.isNotEmpty()) section("CONVERSATION")
            for (r in p.reviews) box.addView(Ui.text(h.ctx, "${r.by} ${when (r.state) { "approved" -> "approved"; "changes" -> "asked for changes"; else -> "commented" }} · ${VcsUi.ago(r.time / 1000)}" + (if (r.body.isNotBlank()) "\n" + r.body.trim() else ""),
                12f, when (r.state) { "approved" -> Ui.GOOD; "changes" -> Ui.BAD; else -> Ui.TEXT }).also { pad(it, 0, 6, 0, 6); it.setTextIsSelectable(true) })
            for ((k, cm) in p.comments.withIndex()) {
                box.addView(Ui.text(h.ctx, "${cm.by}" + (if (cm.path.isNotEmpty()) " on ${cm.path}:${cm.line}" else "") + "\n" + cm.body.trim(), 12f, Ui.TEXT).also { pad(it, 0, 6, 0, 2) })
                if (cm.suggestion != null) {
                    box.addView(Ui.text(h.ctx, cm.suggestion, 11f, Ui.GOOD, true))
                    if (!cm.applied && p.open && p.fromProject.isEmpty()) box.addView(Ui.chip(h.ctx, "Apply suggestion") { applySuggestion(h, p, k, then) })
                    else if (cm.applied) box.addView(Ui.text(h.ctx, "Applied.", 11f, Ui.MUTED))
                }
            }
            val actions = ArrayList<Pair<String, () -> Unit>>()
            if (p.open && head != null) {
                if (p.state == Collab.DRAFT) actions.add("Mark ready for review" to { p.state = Collab.OPEN; p.updated = System.currentTimeMillis(); c.save(); then(); pullDetail(h, n, then) })
                actions.add("Merge (keep its commits)" to { merge(h, p, false, then) })
                actions.add("Squash and merge (one commit)" to { merge(h, p, true, then) })
                actions.add((if (p.autoMerge) "Turn off auto-merge" else "Merge automatically when ready") to {
                    p.autoMerge = !p.autoMerge; c.save(); toast(h, if (p.autoMerge) "It merges by itself once nothing blocks it." else "Auto-merge off."); tryAutoMerge(h); then()
                })
                actions.add("Approve" to { review(h, p, "approved", then) })
                actions.add("Request changes" to { review(h, p, "changes", then) })
                actions.add("Comment" to { review(h, p, "comment", then) })
                actions.add("Ask the assistant to review" to { aiReview(h, p, then) })
                actions.add("Run checks on its last commit" to { runChecks(h, head) { toast(h, checksLine(it)); pullDetail(h, n, then) } })
                if (p.state != Collab.DRAFT) actions.add("Convert to draft" to { p.state = Collab.DRAFT; c.save(); then() })
                if (p.fromProject.isEmpty() && h.vcs.branch() != p.fromBranch) actions.add("Switch to ${p.fromBranch} to work on it" to { VcsUi.switchTo(h, p.fromBranch, then) })
                actions.add("Close without merging" to { p.state = Collab.CLOSED; p.autoMerge = false; p.updated = System.currentTimeMillis(); c.save(); then() })
            } else if (p.state == Collab.CLOSED) actions.add("Reopen" to { p.state = Collab.OPEN; c.save(); then() })
            AlertDialog.Builder(h.ctx).setTitle("#${p.number} ${p.title}".take(80)).setView(scrollOf(box)).setPositiveButton("Close", null)
                .apply { if (actions.isNotEmpty()) setNeutralButton("Actions…") { _, _ -> AlertDialog.Builder(h.ctx).setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }.show() } }
                .show()
        }
    }

    private fun merge(h: VcsUi.Host, p: Collab.Pull, squash: Boolean, then: () -> Unit) {
        val c = collab(h)
        VcsUi.bg(h.ctx, {
            val v = h.vcs
            val head = v.store.resolve(p.sourceRef())
            val why = c.blockers(p, head?.let { c.checks[it] }, v.clashes(p.into, p.sourceRef()))
            why
        }) { why ->
            fun go() = VcsUi.bg(h.ctx, { VcsFlow.mergePull(h.vcs, collab(h), collab(h).pull(p.number)!!, squash, p.fromProject.takeIf { it.isNotEmpty() }?.let { projectDir(h, it) }) }) { r ->
                h.changed(emptyList())
                if (r.conflicts.isNotEmpty()) { toast(h, r.message); VcsUi.conflictsDialog(h, then); return@bg }
                toast(h, "Merged #${p.number}. " + r.message)
                collab(h).pull(p.number)?.closes?.takeIf { it.isNotEmpty() }?.let { toast(h, "Closed issue(s) " + it.joinToString { n -> "#$n" } + ".") }
                h.vcs.store.headId()?.let { afterCommit(h, it) }
                then()
            }
            if (why.isEmpty()) go()
            else AlertDialog.Builder(h.ctx).setTitle("Not ready to merge").setMessage(why.joinToString("\n") { "• $it" })
                .setPositiveButton("Merge anyway") { _, _ -> go() }.setNegativeButton("Cancel", null).show()
        }
    }

    private fun review(h: VcsUi.Host, p: Collab.Pull, state: String, then: () -> Unit) {
        val e = EditText(h.ctx); e.hint = if (state == "approved") "Optional note" else "What should change?"; e.minLines = 2
        val box = Ui.vertical(h.ctx); pad(box, 20, 8, 20, 0); box.addView(e)
        AlertDialog.Builder(h.ctx).setTitle(when (state) { "approved" -> "Approve"; "changes" -> "Request changes"; else -> "Comment" }).setView(box).setPositiveButton("Send") { _, _ ->
            val c = collab(h); val q = c.pull(p.number)!!
            q.reviews.add(Collab.Review("you", state, e.text.toString(), System.currentTimeMillis())); q.updated = System.currentTimeMillis(); c.save()
            tryAutoMerge(h); then(); pullDetail(h, p.number, then)
        }.setNegativeButton("Cancel", null).show()
    }

    /** The assistant reviews the pull request: a verdict, findings, and line suggestions that can be applied with one tap. */
    private fun aiReview(h: VcsUi.Host, p: Collab.Pull, then: () -> Unit) {
        toast(h, "The assistant is reviewing #${p.number}…")
        VcsUi.bg(h.ctx, { "Pull request #${p.number}: ${p.title}\n${p.body}\n\nChanges (new-version line numbers after +):\n" + diffText(h.vcs, p.into, p.sourceRef(), 16000) }) { text ->
            VcsUi.quickAsk(h.ctx, h.settings, Suggestions.REVIEW_SYSTEM, text) { reply ->
                if (reply == null) return@quickAsk
                val c = collab(h); val q = c.pull(p.number)!!; val now = System.currentTimeMillis()
                val state = if (reply.trimStart().startsWith("APPROVE")) "approved" else if (reply.trimStart().startsWith("CHANGES")) "changes" else "comment"
                val plain = reply.replace(Regex("""SUGGEST\s+\S+\s*\n```[^\n]*\n.*?\n```""", RegexOption.DOT_MATCHES_ALL), "").trim()
                q.reviews.add(Collab.Review("assistant", state, plain.removePrefix("APPROVE").removePrefix("CHANGES").trim(), now))
                for (s in Suggestions.parse(reply)) q.comments.add(Collab.Comment("assistant", "Suggested change to lines ${s.first}–${s.last}", now, s.path, s.first, s.code, false, s.last))
                q.updated = now; c.save()
                then(); pullDetail(h, p.number, then)
            }
        }
    }

    private fun applySuggestion(h: VcsUi.Host, p: Collab.Pull, index: Int, then: () -> Unit) {
        val c = collab(h); val q = c.pull(p.number)!!; val cm = q.comments[index]
        VcsUi.bg(h.ctx, {
            val v = h.vcs
            require(v.status().isEmpty()) { "Commit or stash your changes first." }
            if (v.branch() != q.fromBranch) v.switchTo(q.fromBranch)
            val text = h.store.read(cm.path) ?: throw IllegalStateException("${cm.path} is not on ${q.fromBranch}.")
            val s = Suggestions.Suggestion(cm.path, cm.line, maxOf(cm.line, cm.last), cm.suggestion!!)
            val out = Suggestions.apply(text, s) ?: throw IllegalStateException("Those lines are no longer in ${cm.path}.")
            h.store.write(cm.path, out)
            v.commit("Apply suggestion to ${cm.path}", listOf(cm.path))
        }) { id ->
            cm.applied = true; c.save(); h.changed(listOf(cm.path))
            toast(h, "Applied and committed on ${q.fromBranch}."); id?.let { afterCommit(h, it) }; then()
        }
    }

    // ---------------------------------------------------------------- branch rules

    fun rulesDialog(h: VcsUi.Host) {
        val c = collab(h)
        VcsUi.bg(h.ctx, { h.vcs.branches().keys.toList() }) { branches ->
            val box = Ui.vertical(h.ctx); pad(box, 20, 8, 20, 0)
            box.addView(Ui.text(h.ctx, "Protected branches only change through pull requests:", 13f, Ui.MUTED))
            val boxes = branches.filter { !it.startsWith("assistant/") }.map { b -> CheckBox(h.ctx).also { it.text = b; it.setTextColor(Ui.TEXT); it.isChecked = b in c.rules.protect; box.addView(it) } }
            fun opt(t: String, v: Boolean) = CheckBox(h.ctx).also { it.text = t; it.setTextColor(Ui.TEXT); it.isChecked = v; box.addView(it) }
            val checks = opt("…and their checks must pass", c.rules.requireChecks)
            val approval = opt("…and need an approving review", c.rules.requireApproval)
            val own = opt("The assistant always works on its own branch with a pull request", c.rules.assistantBranches)
            val run = opt("Run checks after every commit", c.rules.runChecks)
            val del = opt("Delete a branch once its pull request is merged", c.rules.deleteMerged)
            AlertDialog.Builder(h.ctx).setTitle("Branch rules").setView(scrollOf(box)).setPositiveButton("Save") { _, _ ->
                c.rules.protect = boxes.filter { it.isChecked }.map { it.text.toString() }.toMutableSet()
                c.rules.requireChecks = checks.isChecked; c.rules.requireApproval = approval.isChecked; c.rules.assistantBranches = own.isChecked
                c.rules.runChecks = run.isChecked; c.rules.deleteMerged = del.isChecked; c.save(); toast(h, "Saved.")
            }.setNegativeButton("Cancel", null).show()
        }
    }

    // ---------------------------------------------------------------- releases

    fun releasesTab(h: VcsUi.Host, body: LinearLayout, line: (LinearLayout, Boolean, String, Int, String, String, () -> Unit) -> Unit, card: () -> LinearLayout, refresh: () -> Unit) {
        val top = Ui.horizontal(h.ctx); pad(top, 0, 10, 0, 0)
        top.addView(Ui.button(h.ctx, "New release", true) { newRelease(h, refresh) })
        body.addView(top)
        val c = collab(h)
        val box = card()
        if (c.releases.isEmpty()) box.addView(Ui.text(h.ctx, "No releases yet. A release names a version (a tag), with notes and files people can download: the source as a zip, an APK, anything you attach.", 13f, Ui.MUTED).also { pad(it, 14, 14, 14, 14) })
        for ((i, r) in c.releases.sortedByDescending { it.time }.withIndex())
            line(box, i == 0, "🏷", if (r.draft) Ui.MUTED else Ui.GOOD, r.name.ifBlank { r.tag } + if (r.draft) " (draft)" else "", "${r.tag} · ${r.commit.take(7)} · ${r.assets.size} file(s) · ${VcsUi.ago(r.time / 1000)}") { releaseDetail(h, r.tag, refresh) }
    }

    fun newRelease(h: VcsUi.Host, then: () -> Unit) {
        VcsUi.bg(h.ctx, { val v = h.vcs; Triple(v.tags(), v.store.headId(), v.branch()) }) { (tags, head, branch) ->
            if (head == null) { toast(h, "Make a first commit first."); return@bg }
            val box = Ui.vertical(h.ctx); pad(box, 18, 6, 18, 6)
            val tag = EditText(h.ctx); tag.hint = "Tag, e.g. v1.0"; tag.setSingleLine(); box.addView(tag)
            box.addView(Ui.text(h.ctx, "A new tag marks the last commit on ${branch ?: "HEAD"}; an existing tag keeps its commit." + if (tags.isNotEmpty()) " Tags: " + tags.keys.joinToString() else "", 11f, Ui.MUTED))
            val name = EditText(h.ctx); name.hint = "Title (optional)"; box.addView(name)
            val notes = EditText(h.ctx); notes.hint = "Notes"; notes.minLines = 4; notes.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; box.addView(notes)
            box.addView(Ui.chip(h.ctx, "Write notes from the commits") {
                val target = tags[tag.text.toString().trim()]?.let { h.vcs.store.readCommit(it)?.id } ?: head
                VcsUi.bg(h.ctx, { VcsFlow.releaseNotes(h.vcs, collab(h), target) }) { notes.setText(it) }
            })
            val draft = CheckBox(h.ctx); draft.text = "Draft"; draft.setTextColor(Ui.TEXT); box.addView(draft)
            AlertDialog.Builder(h.ctx).setTitle("New release").setView(scrollOf(box)).setPositiveButton("Publish") { _, _ ->
                val t = tag.text.toString().trim(); if (t.isEmpty()) { toast(h, "Give it a tag."); return@setPositiveButton }
                VcsUi.bg(h.ctx, {
                    val v = h.vcs; val c = collab(h)
                    require(c.releaseFor(t) == null) { "There is already a release for $t." }
                    val commit = if (t in v.tags()) v.store.readCommit(v.tags()[t]!!)!!.id else v.tag(t, null, name.text.toString().ifBlank { "Release $t" })
                    val r = Collab.Release(t, name.text.toString().trim(), notes.text.toString(), commit, System.currentTimeMillis(), draft = draft.isChecked)
                    val zip = File(c.assetDir(t), "${h.store.root.name.replace(' ', '-')}-$t-source.zip")
                    zip.outputStream().use { VcsFlow.zipCommit(v, commit, it, "${h.store.root.name.replace(' ', '-')}-$t") }
                    r.assets.add(zip.name); c.releases.add(r); c.save(); t
                }) { tg -> toast(h, "Released $tg."); then(); releaseDetail(h, tg, then) }
            }.setNegativeButton("Cancel", null).show()
        }
    }

    fun releaseDetail(h: VcsUi.Host, tag: String, then: () -> Unit) {
        val c = collab(h); val r = c.releaseFor(tag) ?: return
        val box = Ui.vertical(h.ctx); pad(box, 18, 6, 18, 6)
        box.addView(Ui.text(h.ctx, "$tag · ${r.commit.take(7)} · ${VcsUi.ago(r.time / 1000)}" + if (r.draft) " · draft" else "", 12f, Ui.MUTED))
        if (r.notes.isNotBlank()) box.addView(Ui.text(h.ctx, r.notes.trim(), 13f, Ui.TEXT).also { pad(it, 0, 8, 0, 8); it.setTextIsSelectable(true) })
        box.addView(Ui.text(h.ctx, "FILES", 12f, Ui.MUTED).also { it.typeface = Typeface.DEFAULT_BOLD; pad(it, 0, 10, 0, 4) })
        for (a in r.assets) {
            val row = Ui.horizontal(h.ctx); row.gravity = Gravity.CENTER_VERTICAL
            val f = File(c.assetDir(tag), a)
            row.addView(Ui.text(h.ctx, "📦 $a  (${f.length() / 1024} KB)", 12f, Ui.TEXT, true), Ui.lp(0, Ui.WRAP, 1f))
            row.addView(Ui.chip(h.ctx, "Save") { h.saveFile?.invoke(f) ?: toast(h, "Saving is not available here.") })
            box.addView(row)
        }
        val actions = listOf<Pair<String, () -> Unit>>(
            "Attach a project file (an APK, a build…)" to {
                VcsUi.bg(h.ctx, { h.store.list().filter { !it.startsWith(".") } }) { files ->
                    val pick = files.sortedWith(compareBy({ !it.endsWith(".apk") && !it.endsWith(".zip") }, { it })).take(300)
                    AlertDialog.Builder(h.ctx).setTitle("Attach").setItems(pick.toTypedArray()) { _, i ->
                        VcsUi.bg(h.ctx, { val src = h.store.resolve(pick[i])!!; src.copyTo(File(c.assetDir(tag), src.name), true); src.name }) { n ->
                            val cc = collab(h); cc.releaseFor(tag)?.assets?.let { if (n !in it) it.add(n) }; cc.save(); then(); releaseDetail(h, tag, then)
                        }
                    }.show()
                }
            },
            (if (r.draft) "Publish (not a draft)" else "Mark as draft") to { val cc = collab(h); cc.releaseFor(tag)?.let { it.draft = !it.draft }; cc.save(); then() },
            "Open its commit" to { VcsUi.commitDetail(h, r.commit, then) },
            "Delete the release (the tag stays)" to { val cc = collab(h); cc.releases.removeAll { it.tag == tag }; cc.save(); c.assetDir(tag).deleteRecursively(); then() })
        AlertDialog.Builder(h.ctx).setTitle(r.name.ifBlank { tag }).setView(scrollOf(box)).setPositiveButton("Close", null)
            .setNeutralButton("Actions…") { _, _ -> AlertDialog.Builder(h.ctx).setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }.show() }.show()
    }
}
