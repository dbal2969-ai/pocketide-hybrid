package dev.pocketide.app.core

import java.io.File

/**
 * A project's GitHub-style records, kept on the phone beside the project (not in its files or history, as on GitHub): pull
 * requests with reviews, line comments and suggestions, issues with labels, check results per commit, releases, and the branch
 * rules. Issues and pull requests share one numbering (#1, #2 …). One JSON file; every change is saved at once. Pure logic.
 */
class Collab(val dir: File) {
    class Comment(val by: String, val body: String, val time: Long, val path: String = "", val line: Int = 0, val suggestion: String? = null, var applied: Boolean = false, val last: Int = line)
    class Review(val by: String, val state: String, val body: String, val time: Long)   // state: approved, changes, comment

    class Pull(val number: Int, var title: String, var body: String, val fromProject: String, val fromBranch: String, var into: String,
               var state: String, val created: Long, var updated: Long, val reviews: MutableList<Review> = ArrayList(),
               val comments: MutableList<Comment> = ArrayList(), var autoMerge: Boolean = false, var mergedAs: String? = null,
               var closes: List<Int> = emptyList(), var by: String = "you") {
        val open: Boolean get() = state == OPEN || state == DRAFT
        /** The ref the source branch has in this project's history. */
        fun sourceRef(): String = if (fromProject.isEmpty()) "refs/heads/$fromBranch" else "refs/remotes/${remoteName(fromProject)}/$fromBranch"
        fun approved(): Boolean = reviews.lastOrNull { it.state != "comment" }?.state == "approved"
        fun changesRequested(): Boolean = reviews.lastOrNull { it.state != "comment" }?.state == "changes"
    }

    class Issue(val number: Int, var title: String, var body: String, val labels: MutableList<String>, var state: String, val created: Long, var updated: Long,
                val comments: MutableList<Comment> = ArrayList(), var assistant: Boolean = false, var pull: Int? = null, var column: String = "",
                /** When assigned to the assistant: the branch it works on and the branch it came from. */
                var branch: String = "", var base: String = "")

    class Check(val name: String, val ok: Boolean?, val detail: String, val time: Long)

    class Release(val tag: String, var name: String, var notes: String, val commit: String, val time: Long, val assets: MutableList<String> = ArrayList(), var draft: Boolean = false)

    class Rules(var protect: MutableSet<String> = linkedSetOf(), var requireChecks: Boolean = true, var requireApproval: Boolean = false,
                var assistantBranches: Boolean = false, var runChecks: Boolean = true, var deleteMerged: Boolean = true)

    companion object {
        const val OPEN = "open"; const val DRAFT = "draft"; const val MERGED = "merged"; const val CLOSED = "closed"
        val LABELS = listOf("bug", "feature", "idea", "question", "docs", "code check")
        fun remoteName(project: String) = "fork-" + project.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "project" }
        private val CLOSES = Regex("""\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\s+#(\d+)""", RegexOption.IGNORE_CASE)
        /** "Fixes #3" in a pull request's text: the issues it closes when merged. */
        fun closesIn(text: String): List<Int> = CLOSES.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.distinct().toList()
        private val REF = Regex("""(?<![\w&])#(\d{1,5})\b""")
        /** "#12" references in text (issues and pull requests). */
        fun refs(text: String): List<Int> = REF.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.distinct().toList()
    }

    val pulls = ArrayList<Pull>()
    val issues = ArrayList<Issue>()
    val checks = LinkedHashMap<String, MutableList<Check>>()
    val releases = ArrayList<Release>()
    val rules = Rules()
    private var next = 1

    private val file get() = File(dir, "collab.json")

    init { load() }

    fun nextNumber(): Int = next++

    // ---------------------------------------------------------------- pull requests

    fun openPull(title: String, body: String, fromProject: String, fromBranch: String, into: String, draft: Boolean, by: String = "you", now: Long = System.currentTimeMillis()): Pull {
        pulls.firstOrNull { it.open && it.fromProject == fromProject && it.fromBranch == fromBranch && it.into == into }?.let { return it }
        val p = Pull(nextNumber(), title.trim().ifEmpty { "Merge $fromBranch into $into" }, body, fromProject, fromBranch, into, if (draft) DRAFT else OPEN, now, now, by = by)
        p.closes = closesIn(title + "\n" + body)
        pulls.add(p); p.closes.forEach { n -> issue(n)?.let { it.pull = p.number } }
        save(); return p
    }

    fun pull(n: Int): Pull? = pulls.firstOrNull { it.number == n }

    /** What still stops a pull request from merging (empty: it can merge). [headChecks] are the checks of its last commit. */
    fun blockers(p: Pull, headChecks: List<Check>?, clashes: List<String>): List<String> {
        val out = ArrayList<String>()
        if (p.state == DRAFT) out.add("It is a draft: mark it ready first.")
        if (!p.open) out.add("It is ${p.state}.")
        if (clashes.isNotEmpty()) out.add("Files changed on both sides: ${clashes.take(5).joinToString()}. Resolve them on ${p.fromBranch} (merge ${p.into} into it).")
        val protected = p.into in rules.protect
        if (protected && rules.requireChecks) {
            when {
                headChecks == null || headChecks.isEmpty() -> out.add("Checks have not run on its last commit.")
                headChecks.any { it.ok == false } -> out.add("Checks failed: " + headChecks.filter { it.ok == false }.joinToString { it.name } + ".")
            }
        }
        if (protected && rules.requireApproval && !p.approved()) out.add("${p.into} needs an approving review.")
        if (p.changesRequested()) out.add("A review asked for changes.")
        return out
    }

    fun merged(p: Pull, commit: String?, now: Long = System.currentTimeMillis()): List<Int> {
        p.state = MERGED; p.mergedAs = commit; p.updated = now; p.autoMerge = false
        val closed = (p.closes + closesIn(p.title + "\n" + p.body)).distinct().filter { n -> issue(n)?.let { it.state = CLOSED; it.updated = now; it.column = "Done"; true } == true }
        save(); return closed
    }

    // ---------------------------------------------------------------- issues

    fun openIssue(title: String, body: String, labels: List<String>, now: Long = System.currentTimeMillis()): Issue {
        val i = Issue(nextNumber(), title.trim().ifEmpty { "Untitled" }, body, labels.toMutableList(), OPEN, now, now)
        issues.add(i); save(); return i
    }

    fun issue(n: Int): Issue? = issues.firstOrNull { it.number == n }

    /** The board column of an issue: chosen, else from its state and its pull request. */
    fun column(i: Issue): String = when {
        i.state == CLOSED -> "Done"
        i.column.isNotEmpty() -> i.column
        i.pull?.let { pull(it) }?.open == true || i.assistant -> "In progress"
        else -> "To do"
    }

    /** The open issue the assistant works on in [branch], if any. */
    fun issueOnBranch(branch: String?): Issue? = issues.firstOrNull { it.state == OPEN && it.assistant && it.branch.isNotEmpty() && it.branch == branch }

    /** A branch name for working on an issue: issue-12-map-is-cut-off. */
    fun issueBranch(i: Issue): String = "issue-${i.number}-" + i.title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(30).trimEnd('-').ifEmpty { "work" }

    // ---------------------------------------------------------------- checks and releases

    fun setChecks(commit: String, list: List<Check>) { checks[commit] = list.toMutableList(); while (checks.size > 300) checks.remove(checks.keys.first()); save() }

    fun releaseFor(tag: String): Release? = releases.firstOrNull { it.tag == tag }

    fun assetDir(tag: String): File = File(dir, "releases/" + tag.replace(Regex("[^A-Za-z0-9._-]"), "_")).also { it.mkdirs() }

    // ---------------------------------------------------------------- storage

    private fun comments(list: List<Comment>) = list.map { linkedMapOf("by" to it.by, "body" to it.body, "time" to it.time, "path" to it.path, "line" to it.line, "suggestion" to it.suggestion, "applied" to it.applied, "last" to it.last) }

    fun save() {
        dir.mkdirs()
        val m = linkedMapOf<String, Any?>(
            "next" to next,
            "rules" to linkedMapOf("protect" to rules.protect.toList(), "requireChecks" to rules.requireChecks, "requireApproval" to rules.requireApproval,
                "assistantBranches" to rules.assistantBranches, "runChecks" to rules.runChecks, "deleteMerged" to rules.deleteMerged),
            "pulls" to pulls.map { p -> linkedMapOf("number" to p.number, "title" to p.title, "body" to p.body, "fromProject" to p.fromProject, "fromBranch" to p.fromBranch,
                "into" to p.into, "state" to p.state, "created" to p.created, "updated" to p.updated, "autoMerge" to p.autoMerge, "mergedAs" to p.mergedAs, "closes" to p.closes, "by" to p.by,
                "reviews" to p.reviews.map { linkedMapOf("by" to it.by, "state" to it.state, "body" to it.body, "time" to it.time) }, "comments" to comments(p.comments)) },
            "issues" to issues.map { i -> linkedMapOf("number" to i.number, "title" to i.title, "body" to i.body, "labels" to i.labels, "state" to i.state, "created" to i.created,
                "updated" to i.updated, "assistant" to i.assistant, "pull" to i.pull, "column" to i.column, "branch" to i.branch, "base" to i.base, "comments" to comments(i.comments)) },
            "checks" to checks.mapValues { (_, l) -> l.map { linkedMapOf("name" to it.name, "ok" to it.ok, "detail" to it.detail, "time" to it.time) } },
            "releases" to releases.map { r -> linkedMapOf("tag" to r.tag, "name" to r.name, "notes" to r.notes, "commit" to r.commit, "time" to r.time, "assets" to r.assets, "draft" to r.draft) })
        val tmp = File(dir, "collab.json.tmp"); tmp.writeText(MiniJson.write(m)); if (!tmp.renameTo(file)) { tmp.copyTo(file, true); tmp.delete() }
    }

    private fun load() {
        val o = try { if (file.isFile) MiniJson.obj(MiniJson.parse(file.readText())) else null } catch (e: Exception) { null } ?: return
        fun str(m: Map<String, Any?>, k: String) = m[k] as? String ?: ""
        fun num(m: Map<String, Any?>, k: String) = (m[k] as? Number)?.toLong() ?: 0L
        fun bool(m: Map<String, Any?>, k: String, d: Boolean = false) = m[k] as? Boolean ?: d
        fun cms(x: Any?) = MiniJson.list(x).orEmpty().mapNotNull { MiniJson.obj(it) }.map { c ->
            Comment(str(c, "by"), str(c, "body"), num(c, "time"), str(c, "path"), num(c, "line").toInt(), c["suggestion"] as? String, bool(c, "applied"), (c["last"] as? Number)?.toInt() ?: num(c, "line").toInt()) }.toMutableList()
        next = num(o, "next").toInt().coerceAtLeast(1)
        MiniJson.obj(o["rules"])?.let { r ->
            rules.protect = MiniJson.list(r["protect"]).orEmpty().mapNotNull { it as? String }.toMutableSet()
            rules.requireChecks = bool(r, "requireChecks", true); rules.requireApproval = bool(r, "requireApproval")
            rules.assistantBranches = bool(r, "assistantBranches"); rules.runChecks = bool(r, "runChecks", true); rules.deleteMerged = bool(r, "deleteMerged", true)
        }
        for (x in MiniJson.list(o["pulls"]).orEmpty()) { val p = MiniJson.obj(x) ?: continue
            pulls.add(Pull(num(p, "number").toInt(), str(p, "title"), str(p, "body"), str(p, "fromProject"), str(p, "fromBranch"), str(p, "into"), str(p, "state"), num(p, "created"), num(p, "updated"),
                MiniJson.list(p["reviews"]).orEmpty().mapNotNull { MiniJson.obj(it) }.map { Review(str(it, "by"), str(it, "state"), str(it, "body"), num(it, "time")) }.toMutableList(),
                cms(p["comments"]), bool(p, "autoMerge"), p["mergedAs"] as? String, MiniJson.list(p["closes"]).orEmpty().mapNotNull { (it as? Number)?.toInt() }, str(p, "by").ifEmpty { "you" })) }
        for (x in MiniJson.list(o["issues"]).orEmpty()) { val i = MiniJson.obj(x) ?: continue
            issues.add(Issue(num(i, "number").toInt(), str(i, "title"), str(i, "body"), MiniJson.list(i["labels"]).orEmpty().mapNotNull { it as? String }.toMutableList(), str(i, "state"),
                num(i, "created"), num(i, "updated"), cms(i["comments"]), bool(i, "assistant"), (i["pull"] as? Number)?.toInt(), str(i, "column"), str(i, "branch"), str(i, "base"))) }
        MiniJson.obj(o["checks"])?.forEach { (k, v) -> checks[k] = MiniJson.list(v).orEmpty().mapNotNull { MiniJson.obj(it) }.map { c -> Check(str(c, "name"), c["ok"] as? Boolean, str(c, "detail"), num(c, "time")) }.toMutableList() }
        for (x in MiniJson.list(o["releases"]).orEmpty()) { val r = MiniJson.obj(x) ?: continue
            releases.add(Release(str(r, "tag"), str(r, "name"), str(r, "notes"), str(r, "commit"), num(r, "time"), MiniJson.list(r["assets"]).orEmpty().mapNotNull { it as? String }.toMutableList(), bool(r, "draft"))) }
    }
}

/** Suggestions in a review ("SUGGEST path:12-14" then a code block), as GitHub's suggested changes. Pure logic. */
object Suggestions {
    private val BLOCK = Regex("""SUGGEST\s+([^\s:]+):(\d+)(?:-(\d+))?\s*\n```[^\n]*\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)

    class Suggestion(val path: String, val first: Int, val last: Int, val code: String)

    fun parse(text: String): List<Suggestion> = BLOCK.findAll(text).map { m ->
        val a = m.groupValues[2].toInt(); Suggestion(m.groupValues[1], a, m.groupValues[3].toIntOrNull() ?: a, m.groupValues[4])
    }.toList()

    /** The file with lines [first]..[last] replaced by [code]; null when the lines are not there. */
    fun apply(file: String, s: Suggestion): String? {
        val lines = file.split("\n").toMutableList()
        if (s.first < 1 || s.last < s.first || s.last > lines.size) return null
        for (k in s.last downTo s.first) lines.removeAt(k - 1)
        lines.addAll(s.first - 1, s.code.split("\n"))
        return lines.joinToString("\n")
    }

    const val REVIEW_SYSTEM = "You review a pull request like a careful senior engineer. Look for bugs, missing pieces, and anything that does not match its description. " +
        "Start with one line: APPROVE if it is ready to merge, or CHANGES if something must be fixed. Then a short list of findings, most important first, each naming the file and line. " +
        "For a concrete fix, add a suggestion exactly like this (the lines are the new version's line numbers, replaced by the code):\nSUGGEST path/to/file.js:12-14\n```\nthe new code for those lines\n```\n" +
        "Do not suggest style-only changes. Be brief."
}
