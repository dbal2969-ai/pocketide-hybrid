package dev.pocketide.app.core

import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

/**
 * The total benchmark (1.5.192): the chosen suites one after another in one run, each in its own folder with its own report, and a
 * summary of them all. Its progress (which suite it is on) is saved in total.properties, so Stop, Resume and a PocketIDE that was
 * closed mid-run work across the whole run; the suite that was running keeps its own progress (BenchResume). Pure logic.
 */
object BenchTotal {
    const val ID = "hybrid-total"
    const val FILE = "total.properties"

    data class State(
        val stamp: String,
        /** The suites' ids (HybridBench.Suite.id), in the order they run. */
        val suites: List<String>,
        /** The suite running now, or the next to run. */
        val index: Int = 0,
        /** Runs per task in the reliability suite (the other suites run each task once). */
        val runs: Int = 1,
        val python: Boolean = true,
        val active: Boolean = true,
        val finished: Boolean = false,
        /** 1.5.203: the Python helper for Gemini Nano is on in every suite (a total run had it off before). */
        val pyHelper: Boolean = false,
    ) {
        val resumable get() = !active && !finished && index < suites.size
    }

    fun encode(s: State): String = StringWriter().also { w ->
        Properties().apply {
            this["stamp"] = s.stamp; this["suites"] = s.suites.joinToString(","); this["index"] = s.index.toString(); this["runs"] = s.runs.toString()
            this["python"] = s.python.toString(); this["active"] = s.active.toString(); this["finished"] = s.finished.toString()
            this["pyHelper"] = s.pyHelper.toString()
        }.store(w, "PocketIDE total benchmark progress")
    }.toString()

    fun decode(text: String): State? = try {
        val p = Properties().apply { load(StringReader(text)) }
        State(p.getProperty("stamp") ?: return null, p.getProperty("suites").orEmpty().split(',').filter { it.isNotBlank() },
            p.getProperty("index")?.toIntOrNull() ?: 0, p.getProperty("runs")?.toIntOrNull() ?: 1, p.getProperty("python") != "false",
            p.getProperty("active") == "true", p.getProperty("finished") == "true", p.getProperty("pyHelper") == "true")
    } catch (e: Exception) { null }

    /** One suite's line in the summary. [ran]: null when the suite has not run yet. */
    class Row(val title: String, val folder: String, val passed: Int, val total: Int, val phone: Int, val cloud: Int, val note: String = "", val ran: Boolean = true)

    /** The summary at the top of the total report: a line per suite, and all of them together. */
    fun table(rows: List<Row>): String = buildString {
        append("| Suite | Passed | Pass rate | Steps on the phone / in the cloud | Folder |\n| --- | --- | --- | --- | --- |\n")
        for (r in rows) {
            if (!r.ran) { append("| ${r.title} | not run yet | – | – | ${r.folder}/ |\n"); continue }
            append("| ${r.title} | ${r.passed} of ${r.total} | ${pct(r.passed, r.total)} | ${r.phone} / ${r.cloud} | ${r.folder}/ |\n")
        }
        val done = rows.filter { it.ran }
        val p = done.sumOf { it.passed }; val t = done.sumOf { it.total }
        append("| **All suites** | **$p of $t** | **${pct(p, t)}** | ${done.sumOf { it.phone }} / ${done.sumOf { it.cloud }} | |\n")
        val notes = rows.filter { it.note.isNotBlank() }
        if (notes.isNotEmpty()) { append('\n'); for (r in notes) append("- ${r.title}: ${r.note}\n") }
    }

    private fun pct(p: Int, t: Int) = if (t == 0) "–" else "${p * 100 / t}%"
}
