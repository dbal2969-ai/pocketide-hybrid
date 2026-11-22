package dev.pocketide.app.core

/**
 * Makes the agreement between parts built at the same time something the app can check, the way Liza (liza-mas/liza, Apache-2.0;
 * ideas only, no code) puts an architecture step, a reviewer and done criteria between the plan and the merge:
 * - before the parts start, [review] checks the agreement itself (every file it names belongs to a part or already exists);
 * - after they finish, [check] compares the files with it (pages load what they should, modules export the agreed names, the
 *   page has the agreed element ids, data files exist);
 * - the final review's [verdict] decides whether the job is done, requirement by requirement, instead of the builder saying so.
 * Pure logic.
 */
object PartsContract {
    /** The lines the agreement must include, asked for in [Parts.requestParallel]. */
    const val FORMAT = "Then, so the app can check the parts against it, add these lines to the agreement (one per file; paths from the project root):\n" +
        "LOADS <page or file>: <the files it loads or imports, in order>\n" +
        "EXPORTS <file>: <the names other parts use from it>\n" +
        "PAGE <html file>: <the element ids the code looks up>\n" +
        "DATA <file>: <the data files it reads>\n" +
        "The page the game or app opens must belong to one part, and that part writes every id listed under PAGE."

    class Contract(val loads: Map<String, List<String>>, val exports: Map<String, List<String>>, val pages: Map<String, List<String>>, val data: Map<String, List<String>>) {
        val isEmpty: Boolean get() = loads.isEmpty() && exports.isEmpty() && pages.isEmpty() && data.isEmpty()
    }

    private val LINE = Regex("""^\s*[-*]?\s*\**(LOADS|EXPORTS|PAGE|DATA)\**\s+`?([^:`]+?)`?\s*:\s*(.*)$""", RegexOption.IGNORE_CASE)

    fun parse(agreement: String): Contract {
        val loads = LinkedHashMap<String, MutableList<String>>(); val exports = LinkedHashMap<String, MutableList<String>>()
        val pages = LinkedHashMap<String, MutableList<String>>(); val data = LinkedHashMap<String, MutableList<String>>()
        for (line in agreement.lines()) {
            val m = LINE.find(line) ?: continue
            val key = Parts.norm(m.groupValues[2])
            val values = m.groupValues[3].split(',', ';').map { it.trim().trim('`', '\'', '"', '.', ' ').removePrefix("#") }.filter { it.isNotEmpty() && it.lowercase() != "none" }
            val into = when (m.groupValues[1].uppercase()) { "LOADS" -> loads; "EXPORTS" -> exports; "PAGE" -> pages; else -> data }
            into.getOrPut(key) { ArrayList() }.addAll(values.map { if (into === exports || into === pages) it else Parts.norm(it) })
        }
        return Contract(loads, exports, pages, data)
    }

    /** A path the agreement names, as a project path: as written, or relative to the folder of the file that names it. */
    private fun candidates(from: String, ref: String): List<String> {
        val dir = from.substringBeforeLast('/', "")
        return listOfNotNull(Parts.norm(ref), GameCheck.resolve(dir, ref)).distinct()
    }

    /**
     * Problems in the agreement before any part starts: files it names that no part writes and that do not exist yet, and a
     * missing agreement. [owned] tells whether a part writes a path; [exists] whether it is already in the project.
     */
    fun review(c: Contract, owned: (String) -> Boolean, exists: (String) -> Boolean): List<String> {
        if (c.isEmpty) return listOf("The agreement has no LOADS, EXPORTS or PAGE lines, so nothing can check that the parts fit together. Add them.")
        val out = ArrayList<String>()
        fun ok(p: String) = owned(p) || exists(p)
        fun need(from: String?, ref: String, what: String) {
            val cs = if (from == null) listOf(Parts.norm(ref)) else candidates(from, ref)
            if (cs.none { ok(it) }) out.add("$ref ($what) is not written by any part and is not in the project yet. Give it to a part or fix the name.")
        }
        for ((f, targets) in c.loads) { need(null, f, "listed under LOADS"); for (t in targets) need(f, t, "loaded by $f") }
        for (f in c.exports.keys) need(null, f, "listed under EXPORTS")
        for (f in c.pages.keys) need(null, f, "the page under PAGE")
        for ((f, files) in c.data) { need(null, f, "listed under DATA"); for (d in files) if (candidates(f, d).none { ok(it) }) out.add("$d (data read by $f) is not written by any part and is not in the project yet.") }
        return out.distinct()
    }

    /** After the parts are built: where the files differ from the agreement. */
    fun check(c: Contract, exists: (String) -> Boolean, read: (String) -> String?): List<String> {
        val out = ArrayList<String>()
        for ((f, targets) in c.loads) {
            val text = read(f)
            if (text == null) { out.add("$f is in the agreement but was not written."); continue }
            for (t in targets) {
                val hit = candidates(f, t).firstOrNull { exists(it) }
                if (hit == null) out.add("$f should load $t, which is not in the project.")
                else if (!text.contains(t.substringAfterLast('/'))) out.add("$f should load $t (the agreement says so), but it does not mention it.")
            }
        }
        for ((f, names) in c.exports) {
            val text = read(f) ?: run { out.add("$f is in the agreement but was not written."); null } ?: continue
            // 1.5.122: names shared through window / CommonJS count too (a field run was sent chasing a false "does not export")
            val have = GameCheck.exportsOf(text) + GameCheck.sharedOf(text)
            val missing = names.filter { it !in have }
            if (missing.isNotEmpty()) out.add("$f should export ${missing.joinToString(", ")} (the agreement says so), but it does not" + (if (have.isNotEmpty()) " (it exports ${have.sorted().take(8).joinToString(", ")})" else "") + ".")
        }
        for ((f, ids) in c.pages) {
            val text = read(f) ?: run { out.add("$f (the page) is in the agreement but was not written."); null } ?: continue
            val missing = ids.filter { id -> !Regex("""\bid\s*=\s*["']${Regex.escape(id)}["']""").containsMatchIn(text) }
            if (missing.isNotEmpty()) out.add("$f should have the element id(s) ${missing.joinToString(", ") { "#$it" }} (the agreement says so), but it does not.")
        }
        for ((f, files) in c.data) for (d in files) if (candidates(f, d).none { exists(it) }) out.add("$d (data read by $f) is not in the project.")
        return out.distinct()
    }

    /** What the final review decided. */
    class Verdict(val unmet: List<Int>, val unanswered: List<Int>, val leftOut: String, val doesntFit: String) {
        val passed: Boolean get() = unmet.isEmpty() && unanswered.isEmpty() && none(leftOut) && none(doesntFit)
        /** The findings to fix, in short lines. */
        fun summary(reqs: List<String>): String {
            val b = StringBuilder()
            for (n in unmet) b.append("- Requirement $n is not met: ").append(reqs.getOrNull(n - 1).orEmpty()).append('\n')
            for (n in unanswered) b.append("- Requirement $n was not checked: ").append(reqs.getOrNull(n - 1).orEmpty()).append('\n')
            if (!none(leftOut)) b.append("- Left out: ").append(leftOut).append('\n')
            if (!none(doesntFit)) b.append("- Does not fit: ").append(doesntFit).append('\n')
            return b.toString().trim()
        }
    }

    private fun none(s: String) = s.isBlank() || Regex("""^\W*(nothing|none|n/a|no problems?)\b""", RegexOption.IGNORE_CASE).containsMatchIn(s.trim())

    private val VERDICT = Regex("""(?im)^\W*R(?:equirement)?\s*#?(\d{1,2})\s*[:.)\-–—]\s*\**\s*(not met|met|partly met|partially met|partly|partial|no|yes|done|missing)\b""")

    /** The final review's reply as a verdict; [count] is the number of approved requirements. */
    fun verdict(reply: String, count: Int): Verdict {
        val seen = HashMap<Int, Boolean>()
        for (m in VERDICT.findAll(reply)) {
            val n = m.groupValues[1].toInt()
            if (n < 1 || n > count) continue
            val v = m.groupValues[2].lowercase()
            seen[n] = v == "met" || v == "yes" || v == "done"
        }
        val unmet = seen.filterValues { !it }.keys.sorted()
        val unanswered = if (seen.isEmpty()) emptyList() else (1..count).filter { it !in seen }
        return Verdict(unmet, unanswered, field(reply, "Left out"), field(reply, "Doesn't fit").ifEmpty { field(reply, "Does not fit") })
    }

    private fun field(reply: String, name: String): String {
        val lines = reply.lines()
        val at = lines.indexOfFirst { Regex("""^\W*${Regex.escape(name)}\W*:""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        if (at < 0) return ""
        val first = lines[at].substringAfter(':').trim()
        val rest = lines.drop(at + 1).takeWhile { !Regex("""^\W*(Matches|Left out|Changed|Doesn't fit|Does not fit|Verdict|R\d{1,2})\W*:""", RegexOption.IGNORE_CASE).containsMatchIn(it) && it.isNotBlank() }
        return (listOf(first) + rest).joinToString(" ").trim().take(800)
    }

    /** The verdict lines the review is asked for (Liza's done criteria: each requirement is judged on its own). */
    const val VERDICT_FORMAT = "Verdict: then one line per requirement, exactly like \"R1: met (the file that shows it)\" or \"R1: not met (what is missing)\"."

    /** The state of a parts run, kept in the project so it can be read later (like Liza's blackboard). */
    fun stateJson(task: String, reqs: List<String>, parts: List<Parts.Part>, statuses: List<String>, agreement: String,
                  agreementProblems: List<String>, buildProblems: List<String>?, review: Verdict?, stage: String, now: Long = System.currentTimeMillis()): String =
        MiniJson.write(linkedMapOf(
            "updated" to now, "stage" to stage, "request" to task,
            "requirements" to reqs.mapIndexed { i, r -> linkedMapOf("n" to i + 1, "text" to r) },
            "parts" to parts.mapIndexed { i, p -> linkedMapOf("name" to p.name, "requirements" to p.numbers.sorted(), "files" to p.files, "status" to (statuses.getOrNull(i) ?: "todo")) },
            "agreement" to agreement, "agreementProblems" to agreementProblems,
            "buildProblems" to buildProblems,
            "review" to review?.let { linkedMapOf("passed" to it.passed, "unmet" to it.unmet, "unchecked" to it.unanswered, "leftOut" to it.leftOut, "doesNotFit" to it.doesntFit) }))

    /** Where the state is kept in the project. */
    const val STATE_FILE = ".pocketide/parts-state.json"
}
