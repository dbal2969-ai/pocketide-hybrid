package dev.pocketide.app.core

/**
 * Dependency updates, like GitHub's Dependabot: the packages a project names (package.json, requirements.txt), their newest
 * versions (npm registry, PyPI) and known vulnerabilities (the OSV database, osv.dev). The web calls go through [get]/[post]
 * so the logic is tested without the network. Pure logic.
 */
object Deps {
    class Dep(val ecosystem: String, val name: String, val wanted: String, val file: String) {
        /** The version the project pins or asks for at least (^1.2.3 → 1.2.3), or "" when it names none. */
        val version: String get() = Regex("""\d+(\.\d+){0,3}""").find(wanted)?.value.orEmpty()
    }

    class Report(val dep: Dep, val latest: String?, val vulns: List<String>) {
        val outdated: Boolean get() = latest != null && dep.version.isNotEmpty() && newer(latest, dep.version)
    }

    fun find(files: List<String>, read: (String) -> String?): List<Dep> {
        val out = ArrayList<Dep>()
        if ("package.json" in files) {
            val o = try { MiniJson.obj(MiniJson.parse(read("package.json") ?: "{}")) } catch (e: Exception) { null }
            for (k in listOf("dependencies", "devDependencies")) MiniJson.obj(o?.get(k))?.forEach { (n, v) -> (v as? String)?.let { out.add(Dep("npm", n, it, "package.json")) } }
        }
        if ("requirements.txt" in files) for (l in read("requirements.txt").orEmpty().lines()) {
            val t = l.substringBefore('#').trim(); if (t.isEmpty() || t.startsWith("-")) continue
            val m = Regex("""^([A-Za-z0-9_.\-\[\]]+)\s*(.*)$""").find(t) ?: continue
            out.add(Dep("PyPI", m.groupValues[1].substringBefore('['), m.groupValues[2].trim(), "requirements.txt"))
        }
        return out
    }

    /** True when version [a] is newer than [b] (numbers compared part by part). */
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }; val y = b.split('.').map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }; if (d != 0) return d > 0 }
        return false
    }

    fun latestUrl(d: Dep): String = if (d.ecosystem == "npm") "https://registry.npmjs.org/${d.name.replace("/", "%2F")}/latest" else "https://pypi.org/pypi/${d.name}/json"

    fun parseLatest(d: Dep, json: String): String? = try {
        val o = MiniJson.obj(MiniJson.parse(json))!!
        if (d.ecosystem == "npm") o["version"] as? String else MiniJson.obj(o["info"])?.get("version") as? String
    } catch (e: Exception) { null }

    const val OSV = "https://api.osv.dev/v1/querybatch"

    fun osvQuery(deps: List<Dep>): String = MiniJson.write(linkedMapOf("queries" to deps.filter { it.version.isNotEmpty() }.map {
        linkedMapOf("package" to linkedMapOf("name" to it.name, "ecosystem" to it.ecosystem), "version" to it.version) }))

    /** OSV's answer: for each queried dependency (in order), the vulnerability ids. */
    fun parseOsv(json: String): List<List<String>> = try {
        MiniJson.list(MiniJson.obj(MiniJson.parse(json))!!["results"]).orEmpty().map { r -> MiniJson.list(MiniJson.obj(r)?.get("vulns")).orEmpty().mapNotNull { MiniJson.obj(it)?.get("id") as? String } }
    } catch (e: Exception) { emptyList() }

    fun check(deps: List<Dep>, get: (String) -> String?, post: (String, String) -> String?): List<Report> {
        val latest = deps.map { d -> get(latestUrl(d))?.let { parseLatest(d, it) } }
        val withVersion = deps.filter { it.version.isNotEmpty() }
        val vulns = post(OSV, osvQuery(deps))?.let { parseOsv(it) } ?: emptyList()
        val byDep = HashMap<Dep, List<String>>(); withVersion.forEachIndexed { i, d -> byDep[d] = vulns.getOrElse(i) { emptyList() } }
        return deps.mapIndexed { i, d -> Report(d, latest[i], byDep[d].orEmpty()) }
    }

    /** The request for the assistant to update them on a branch. */
    fun request(reports: List<Report>): String =
        "Update these dependencies, then run the project's tests and fix anything the update breaks:\n" +
            reports.joinToString("\n") { r -> "- ${r.dep.name} (${r.dep.file}): ${r.dep.wanted.ifEmpty { "any" }} → ${r.latest ?: "a version without the vulnerabilities"}" +
                (if (r.vulns.isNotEmpty()) " — known vulnerabilities: ${r.vulns.take(5).joinToString()}" else "") }
}

/** Activity and insights from a project's history: contributions per day, code frequency, hot files, and the branch graph. */
object Insights {
    fun day(epochSec: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(epochSec * 1000))

    fun perDay(commits: List<VcsStore.Commit>): Map<String, Int> = commits.groupingBy { day(it.author.time) }.eachCount()

    /** The last [weeks] weeks as rows of 7 counts (Sunday first), oldest week first, ending with the week of [now]. */
    fun grid(perDay: Map<String, Int>, now: Long = System.currentTimeMillis(), weeks: Int = 16): List<IntArray> {
        val c = java.util.Calendar.getInstance(); c.timeInMillis = now
        c.add(java.util.Calendar.DAY_OF_YEAR, -(c.get(java.util.Calendar.DAY_OF_WEEK) - 1) - 7 * (weeks - 1))
        val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        return List(weeks) { val row = IntArray(7); for (d in 0 until 7) { row[d] = perDay[f.format(c.time)] ?: 0; c.add(java.util.Calendar.DAY_OF_YEAR, 1) }; row }
    }

    class Week(val start: String, val added: Int, val removed: Int, val commits: Int)

    /** Lines added and removed per week, newest last (first parents only, merges skipped). */
    fun codeFrequency(v: Vcs, commits: List<VcsStore.Commit>): List<Week> {
        val byWeek = java.util.TreeMap<String, IntArray>()
        for (c in commits) {
            if (c.parents.size > 1) continue
            val cal = java.util.Calendar.getInstance(); cal.timeInMillis = c.author.time * 1000
            cal.add(java.util.Calendar.DAY_OF_YEAR, -(cal.get(java.util.Calendar.DAY_OF_WEEK) - 1))
            val key = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time)
            val ch = v.changes(c.id); val a = byWeek.getOrPut(key) { IntArray(3) }
            a[0] += ch.sumOf { it.added }; a[1] += ch.sumOf { it.removed }; a[2]++
        }
        return byWeek.map { (k, a) -> Week(k, a[0], a[1], a[2]) }
    }

    /** Files changed most often, with how many of those changes the assistant made. */
    fun hotFiles(v: Vcs, commits: List<VcsStore.Commit>, top: Int = 15): List<Triple<String, Int, Int>> {
        val n = HashMap<String, Int>(); val byAi = HashMap<String, Int>()
        for (c in commits) { if (c.parents.size > 1) continue; val ai = c.author.email == Vcs.ASSISTANT.email
            for (f in v.changes(c.id)) { n[f.path] = (n[f.path] ?: 0) + 1; if (ai) byAi[f.path] = (byAi[f.path] ?: 0) + 1 } }
        return n.entries.sortedByDescending { it.value }.take(top).map { Triple(it.key, it.value, byAi[it.key] ?: 0) }
    }

    /**
     * A text picture of the branches, newest first, one line per commit: lanes drawn with │, the commit as ● (○ for a merge),
     * and ─╮ where a merge's second parent opens a lane.
     */
    fun graph(commits: List<VcsStore.Commit>, labels: Map<String, List<String>> = emptyMap()): List<String> {
        val lanes = ArrayList<String?>()   // the commit each lane waits for
        val out = ArrayList<String>()
        for (c in commits) {
            var me = lanes.indexOf(c.id)
            if (me < 0) { me = lanes.indexOf(null).takeIf { it >= 0 } ?: lanes.size; if (me == lanes.size) lanes.add(c.id) else lanes[me] = c.id }
            // other lanes that also waited for this commit end here
            for (k in lanes.indices) if (k != me && lanes[k] == c.id) lanes[k] = null
            val sb = StringBuilder()
            for (k in lanes.indices) sb.append(when { k == me -> if (c.parents.size > 1) "○" else "●"; lanes[k] != null -> "│"; else -> " " }).append(' ')
            lanes[me] = c.parents.firstOrNull()
            if (c.parents.size > 1) {
                val second = c.parents[1]
                if (second !in lanes) { val free = lanes.indexOf(null).takeIf { it >= 0 && it > me } ?: lanes.size; if (free == lanes.size) lanes.add(second) else lanes[free] = second; sb.append("╮ ") }
            }
            while (lanes.isNotEmpty() && lanes.last() == null) lanes.removeAt(lanes.size - 1)
            out.add(sb.toString().trimEnd() + "  " + c.short + " " + c.title.take(50) + (labels[c.id]?.let { "  (" + it.joinToString() + ")" } ?: ""))
        }
        return out
    }
}
