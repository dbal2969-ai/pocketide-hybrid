package dev.pocketide.app.core

/**
 * Cause before change, for problem reports (1.5.147, Copilot's HANDOFF-RAISE-CEILING WP5; experimental, off by default until measured).
 * The largest group of real-project failures in the 1.5.136 baseline (6 of 15) was a fix in the wrong place: the model changed a data
 * file or a shared module so the symptom went away, while the cause was elsewhere. Habits.REPRODUCE_NOTE already asks for the cause
 * in the prompt; these are checks in the loop instead:
 *  - [ask]: the first file change in a problem report is held back once, unless the model has already named a cause with a file, and
 *    the model is asked to say where the wrong behaviour starts and to change it there;
 *  - [dataAsk]: before finishing, a changed data file the report does not name is questioned once (is the data really wrong?).
 * Tuned only on the `cause` benchmark suite; the realproject tasks stay the held-out score.
 */
object CauseGate {
    internal val CAUSE_WORDS = Regex("""\b(?:root cause|the cause|caused by|because|the bug is|the problem is|the issue is|is wrong|should be|instead of|never (?:set|called|reached)|returns? the wrong|off by one)\b""", RegexOption.IGNORE_CASE)
    internal val FILE = Regex("""\b[\w./\-]+\.[A-Za-z]{1,5}\b(?::\d+)?""")

    /**
     * Whether [text] (what the model has said so far) names a cause and a file it is in. [exists] says whether a path is a project file
     * (1.5.150, Copilot's ANALYSIS-1.5.148 M5: any "x.y" counted before, so a made-up name satisfied it); a bare file name counts when a
     * project file has that name.
     */
    fun stated(text: String, exists: (String) -> Boolean = { true }): Boolean = text.lines().any { line ->
        CAUSE_WORDS.containsMatchIn(line) && FILE.findAll(line).any { m -> exists(m.value.substringBefore(':').trimStart('.', '/')) }
    }

    /** Sent back in place of the first change when no cause was named. */
    const val ASK = "Not changed yet. This is a problem report: before the first change, say in one line where the wrong behaviour starts: " +
        "the file and line, and what value or step is wrong there. Then make the change at that place. If the change you were about to make " +
        "only hides the symptom (in a data file, a list of options or a menu, or a shared helper the report does not mention), fix the cause instead."

    // not txt (M5): a new notes.txt is not data the code reads
    private val DATA = setOf("json", "csv", "tsv", "yaml", "yml", "xml", "ini", "toml", "dat")

    /** A data file: read by code, not code itself. */
    fun isData(path: String): Boolean = path.substringAfterLast('/').substringAfterLast('.', "").lowercase() in DATA &&
        !path.substringAfterLast('/').lowercase().let { it == "package.json" || it == "tsconfig.json" || it.startsWith("readme") }

    /** Whether [request] names [path] (its whole path or its file name). */
    fun named(path: String, request: String): Boolean = request.contains(path, true) || request.contains(path.substringAfterLast('/'), true)

    /** The data files among [changed] that the request does not name. */
    fun unnamedData(changed: Collection<String>, request: String): List<String> = changed.filter { isData(it) && !named(it, request) }

    /** Sent once before finishing when [files] were changed. */
    fun dataAsk(files: List<String>): String =
        "You changed ${files.joinToString(", ")}, which the report does not mention. When a result looks wrong, the data is usually right and " +
            "the code that reads it is wrong. If the data was right, put it back exactly as it was and fix the code that reads it. " +
            "If an entry really was wrong, or you created the file as new test data, say which entry and why in one line, and keep it."
}
