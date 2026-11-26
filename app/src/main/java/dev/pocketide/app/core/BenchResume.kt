package dev.pocketide.app.core

import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

/**
 * Interrupted benchmark runs (1.5.188). A run saves its progress after every task (run.properties in its results folder, and
 * result.properties in each task's folder). If it is stopped, or PocketIDE is closed or killed, the tasks that finished are not
 * lost: they are zipped as a partial result, and the run can be resumed later. On resume the task that was running when it was
 * interrupted goes to the back of the queue (it may be what stopped the run), and a task interrupted [LIMIT] times is recorded as
 * failed so a run can never get stuck on it. Pure logic; HybridBench reads and writes the files.
 */
object BenchResume {
    const val FILE = "run.properties"
    const val RESULT = "result.properties"
    /** Interruptions after which a task is recorded as failed instead of being tried again. */
    const val LIMIT = 2

    data class State(
        val suite: String,
        val stamp: String,
        val helping: Boolean = false,
        val runs: Int = 1,
        val python: Boolean = true,
        /** Every task id in the run, in the order first planned. */
        val order: List<String>,
        /** The ids that have a recorded result, in the order they finished. */
        val done: List<String> = emptyList(),
        /** The task that was running when this was saved. */
        val current: String? = null,
        /** How many times each task was running when the run was interrupted. */
        val interrupted: Map<String, Int> = emptyMap(),
        /** A run is going on now (false once it was interrupted and saved, or it finished). */
        val active: Boolean = true,
        /** All tasks have results, or the run was given up: nothing to resume. */
        val finished: Boolean = false,
        /** The person's settings before the run, put back when it ends or is interrupted. */
        val before: Map<String, String> = emptyMap(),
        val unsound: Map<String, String> = emptyMap(),
        val deselect: Map<String, List<String>> = emptyMap(),
        val pythonNote: String = "",
    ) {
        val resumable get() = !active && !finished && done.size < order.size
    }

    private const val SEP = "\t"
    private fun join(l: List<String>) = l.joinToString(SEP)
    private fun split(s: String?) = s.orEmpty().split(SEP).filter { it.isNotEmpty() }

    fun encode(s: State): String {
        val p = Properties()
        p["suite"] = s.suite; p["stamp"] = s.stamp; p["helping"] = s.helping.toString(); p["runs"] = s.runs.toString()
        p["python"] = s.python.toString(); p["order"] = join(s.order); p["done"] = join(s.done); p["current"] = s.current.orEmpty()
        p["active"] = s.active.toString(); p["finished"] = s.finished.toString(); p["pythonNote"] = s.pythonNote
        for ((k, v) in s.interrupted) p["interrupted.$k"] = v.toString()
        for ((k, v) in s.before) p["before.$k"] = v
        for ((k, v) in s.unsound) p["unsound.$k"] = v
        for ((k, v) in s.deselect) p["deselect.$k"] = join(v)
        return StringWriter().also { p.store(it, "PocketIDE benchmark progress") }.toString()
    }

    fun decode(text: String): State? = try {
        val p = Properties().apply { load(StringReader(text)) }
        fun group(prefix: String) = p.stringPropertyNames().filter { it.startsWith(prefix) }.sorted().associate { it.removePrefix(prefix) to p.getProperty(it) }
        State(
            suite = p.getProperty("suite") ?: return null,
            stamp = p.getProperty("stamp") ?: return null,
            helping = p.getProperty("helping") == "true",
            runs = p.getProperty("runs")?.toIntOrNull() ?: 1,
            python = p.getProperty("python") != "false",
            order = split(p.getProperty("order")),
            done = split(p.getProperty("done")),
            current = p.getProperty("current")?.ifEmpty { null },
            interrupted = group("interrupted.").mapValues { it.value.toIntOrNull() ?: 0 },
            active = p.getProperty("active") == "true",
            finished = p.getProperty("finished") == "true",
            before = group("before."),
            unsound = group("unsound."),
            deselect = group("deselect.").mapValues { split(it.value) },
            pythonNote = p.getProperty("pythonNote").orEmpty(),
        )
    } catch (e: Exception) { null }

    /** The state once the run was interrupted: the running task gets one more interruption, and the run is no longer active. */
    fun interrupt(s: State): State {
        val c = s.current?.takeIf { it !in s.done }
        return s.copy(current = null, active = false,
            interrupted = if (c == null) s.interrupted else s.interrupted + (c to (s.interrupted[c] ?: 0) + 1))
    }

    /** Tasks interrupted [LIMIT] times that still have no result: recorded as failed on resume. */
    fun givenUp(s: State): List<String> = s.order.filter { it !in s.done && (s.interrupted[it] ?: 0) >= LIMIT }

    /** The tasks still to run, in order, with the ones that were interrupted moved to the back (rotated), the least-interrupted first. */
    fun remaining(s: State): List<String> {
        val left = s.order.filter { it !in s.done && (s.interrupted[it] ?: 0) < LIMIT }
        return left.filter { (s.interrupted[it] ?: 0) == 0 } + left.filter { (s.interrupted[it] ?: 0) > 0 }.sortedBy { s.interrupted[it] }
    }

    fun givenUpWhy(id: String) = "the run was interrupted during this task $LIMIT times (PocketIDE was stopped, closed or killed), so it was recorded as failed"

    /** A task's saved result: whether it passed, why not, its seconds and how it ended. */
    class Saved(val passed: Boolean, val why: String, val seconds: Long, val ended: String)

    fun resultText(r: Saved): String = StringWriter().also { w ->
        Properties().apply { this["passed"] = r.passed.toString(); this["why"] = r.why; this["seconds"] = r.seconds.toString(); this["ended"] = r.ended }.store(w, null)
    }.toString()

    fun parseResult(text: String): Saved? = try {
        val p = Properties().apply { load(StringReader(text)) }
        Saved(p.getProperty("passed") == "true", p.getProperty("why").orEmpty(), p.getProperty("seconds")?.toLongOrNull() ?: 0, p.getProperty("ended").orEmpty())
    } catch (e: Exception) { null }

    /** For results saved before 1.5.188 (no result.properties): the verdict in check.txt's last "Result:" line. */
    fun fromCheck(check: String): Saved? {
        val line = check.lines().lastOrNull { it.startsWith("Result: ") } ?: return null
        val v = line.removePrefix("Result: ").trim()
        return Saved(v == "passed", if (v == "passed") "" else v, 0, "")
    }

    /** The partial zip's name: the suite, the run's stamp and how far it got. */
    fun partialName(suite: String, helping: Boolean, stamp: String, done: Int, total: Int) =
        "pocketide-$suite${if (helping) "-helping" else ""}-benchmark-$stamp-partial-${done}of$total.zip"

    /** What the report says above the results of an interrupted run. */
    fun interruptedNote(s: State, total: Int, how: String): String {
        val back = s.order.filter { it !in s.done && (s.interrupted[it] ?: 0) > 0 }
        return "Interrupted ($how) after ${s.done.size} of $total tasks. These are the results so far; resume the run from the same " +
            "benchmark button to finish it." + if (back.isEmpty()) "" else " Moved to the back of the queue because the run was interrupted during them: " +
            back.joinToString(", ") { "$it (${s.interrupted[it]}×)" } + "."
    }
}
