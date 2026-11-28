package dev.pocketide.app.core

/**
 * 1.5.220: changes to try in a benchmark before they become the app's behaviour. Each is off unless ticked under
 * Models → Hybrid AI → Benchmarks → Experiments; a benchmark report's first line says which were on. They come from the
 * 2026-11-28 research pass; measured in a workspace first, each looked small (HYBRID-CHANGELOG, 1257dd0), and the owner asked
 * for switches to measure them on the phone.
 */
object Experiments {
    /** Python fix path: run the test under a tracer, and try first the functions the failing test runs (PyFix.withCoverage). */
    @Volatile var coverage = false
    /** Gemini Nano's step is asked for as a typed object (Firebase AI Logic structured output), not as JSON text. */
    @Volatile var structured = false
    /** Every tool result shown to Gemini Nano starts with OK or ERROR (ResultMark). */
    @Volatile var okError = false

    const val COVERAGE = "coverage"
    const val STRUCTURED = "structured"
    const val OK_ERROR = "okerror"
    val ALL = listOf(COVERAGE, STRUCTURED, OK_ERROR)

    fun title(id: String): String = when (id) {
        COVERAGE -> "Python fixes: try first the functions the failing test runs (coverage)"
        STRUCTURED -> "Ask Gemini Nano for each step as a typed object (structured output)"
        OK_ERROR -> "Start every tool result with OK or ERROR"
        else -> id
    }

    fun isOn(id: String): Boolean = when (id) { COVERAGE -> coverage; STRUCTURED -> structured; OK_ERROR -> okError; else -> false }
    fun set(id: String, on: Boolean) { when (id) { COVERAGE -> coverage = on; STRUCTURED -> structured = on; OK_ERROR -> okError = on } }

    /** For the report: "none", or the short names of the ones that are on. */
    fun label(): String = listOfNotNull("coverage".takeIf { coverage }, "structured output".takeIf { structured }, "OK/ERROR results".takeIf { okError })
        .joinToString(", ").ifEmpty { "none" }
}

/** 1.5.220 (experiment): marks a tool result as a success or a failure in its first word, for a small model. */
object ResultMark {
    private val FAILED = Regex("""(?i)^\s*(NOT SAVED|Not saved|Not written|Edit not applied|Not applied|Not inserted|Not available|Error\b|Could not|Failed|No such file|Unknown tool|Give something|The user declined|You already read this file)""")
    private val EXIT = Regex("""^\s*exit code (-?\d+)""")

    /** True when [result] reports that the step did not do what was asked. */
    fun failed(result: String): Boolean = EXIT.find(result)?.let { it.groupValues[1] != "0" } ?: FAILED.containsMatchIn(result)

    fun mark(result: String): String = (if (failed(result)) "ERROR. " else "OK. ") + result.trimStart()
}
