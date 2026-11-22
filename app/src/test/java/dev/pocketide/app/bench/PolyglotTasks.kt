package dev.pocketide.app.bench

import dev.pocketide.app.bench.BenchTasks.Task
import java.io.File

/**
 * The Aider polyglot benchmark's Python exercises (1.5.113, https://github.com/Aider-AI/polyglot-benchmark, exercises from Exercism's
 * Python track, MIT): 34 harder Exercism problems that many coding tools publish scores on. Loaded from a checkout at
 * POCKETIDE_POLYGLOT_DIR (the workflow clones it at a pinned commit); nothing is copied into this repository.
 *
 * Like Aider's harness, the model gets the instructions and the stub file, not the tests; when the hidden tests fail, it gets one
 * more attempt with their output (1.5.115), so the report gives both Aider's first-try rate ("pass_rate_1") and the two-try rate
 * its leaderboard shows ("pass_rate_2"). The model may also run its own checks, which Aider's does not.
 *
 * 1.5.125: the 49 JavaScript exercises too (prefix js-, [JS]), because most of the owner's projects are JavaScript. Their hidden
 * tests run with jest, as each exercise's package.json says, from one install per job outside the project (POCKETIDE_POLYGLOT_JEST,
 * a folder holding node_modules), as Aider's harness uses one /npm-install for every exercise.
 */
object PolyglotTasks {
    const val PREFIX = "py-"
    const val JS_PREFIX = "js-"

    fun dir(): File? = practice("python")

    /** 1.5.125: javascript/exercises/practice in the same checkout. */
    fun jsDir(): File? = practice("javascript")

    private fun practice(lang: String): File? = System.getenv("POCKETIDE_POLYGLOT_DIR")?.takeIf { it.isNotBlank() }
        ?.let { File(it, "$lang/exercises/practice") }?.takeIf { it.isDirectory }

    /**
     * 1.5.125: the folder jest was installed in (the workflow runs npm install there once per job). The benchmark sandbox binds it
     * read-only (AgentHarness.shell), so a test run finds jest and the babel preset without the network.
     */
    fun jestDir(): File? = System.getenv("POCKETIDE_POLYGLOT_JEST")?.takeIf { it.isNotBlank() }?.let { File(it).canonicalFile }
        ?.takeIf { File(it, "node_modules/.bin/jest").exists() }

    /** The exercise's instructions as Aider builds them: introduction, instructions, then the track's additions. */
    fun instructions(ex: File): String = listOf("introduction.md", "instructions.md", "instructions.append.md")
        .mapNotNull { File(ex, ".docs/$it").takeIf { f -> f.isFile }?.readText()?.trim() }.joinToString("\n\n")

    /** The exercise's .meta/config.json file list for [key] (solution, test or example). */
    fun files(ex: File, key: String): List<String> {
        val cfg = File(ex, ".meta/config.json").takeIf { it.isFile }?.readText() ?: return emptyList()
        return Regex("\"$key\"\\s*:\\s*\\[([^\\]]*)]").find(cfg)?.groupValues?.get(1)
            ?.let { Regex("\"([^\"]+)\"").findAll(it).map { m -> m.groupValues[1] }.toList() }.orEmpty()
    }

    // Aider's words after the instructions (benchmark/prompts.py), the same for every language
    private fun request(ex: File, solutions: List<String>) = instructions(ex) + "\n\n####\n\nUse the above instructions to modify the supplied files: " + solutions.joinToString(" ") +
        "\nDon't change the names of existing functions or classes, as they may be referenced from other code like unit tests, etc.\nOnly use standard libraries, don't suggest installing any packages."

    private fun write(path: String, text: String) = "cat > '$path' <<'POLYGLOT_EOF'\n" + text.trimEnd() + "\nPOLYGLOT_EOF"

    fun task(ex: File): Task? {
        val solutions = files(ex, "solution"); val tests = files(ex, "test")
        if (solutions.isEmpty() || tests.isEmpty()) return null
        val files = solutions.associateWith { File(ex, it).readText() }
        // the tests are written into the project only when it is checked, so the model never sees them
        val check = (listOf("set -e") + tests.map { t -> write(t, File(ex, t).readText()) } +
            listOf("python3 -m unittest " + tests.joinToString(" ") { it.removeSuffix(".py").replace('/', '.') } + " 2>&1 | tail -" + BenchRunner.OUTPUT_LINES)).joinToString("\n")
        return Task(PREFIX + ex.name, request(ex, solutions), files, check + "\ntest \"\${PIPESTATUS[0]}\" = 0 || exit 1", rounds = 24, twoTries = true, hidden = tests)
    }

    /**
     * 1.5.125: a JavaScript exercise, set up as Aider's harness does (benchmark/npm-test.sh): the model gets the instructions and the
     * stub; at check time the spec, the exercise's package.json and babel config (and grep's data files) are written, xtest( becomes
     * test( so skipped tests run, and `npm test` runs the package's own test script (jest on every file). Aider links node_modules into the exercise; here
     * PATH and NODE_PATH point at [jest] instead, so nothing outside the model's files is left in the project, and nothing that
     * deletes the project can reach the shared install through a link. Null when jest is not installed.
     */
    fun jsTask(ex: File, jest: File? = jestDir()): Task? {
        val solutions = files(ex, "solution"); val tests = files(ex, "test")
        if (jest == null || solutions.isEmpty() || tests.isEmpty()) return null
        val files = solutions.associateWith { File(ex, it).readText() }
        val support = listOf("package.json", "babel.config.js").filter { File(ex, it).isFile } +
            (File(ex, "data").listFiles()?.filter { it.isFile }?.map { "data/" + it.name }?.sorted().orEmpty())
        val hidden = tests + support
        val modules = File(jest, "node_modules").path
        val check = (listOf("set -e") + (if (support.any { it.startsWith("data/") }) listOf("mkdir -p data") else emptyList()) +
            support.map { write(it, File(ex, it).readText()) } +
            tests.map { t -> write(t, File(ex, t).readText().replace(Regex("\\bxtest\\("), "test(")) } +
            // TZ=UTC as in Exercism's and Aider's test runs: ledger's dates are parsed as UTC days, so its reference solution fails
            // in New York time
            listOf("PATH='$modules/.bin':\"\$PATH\" NODE_PATH='$modules' TZ=UTC NPM_CONFIG_UPDATE_NOTIFIER=false npm test 2>&1 | tail -" + BenchRunner.OUTPUT_LINES)).joinToString("\n")
        return Task(JS_PREFIX + ex.name, request(ex, solutions), files, check + "\ntest \"\${PIPESTATUS[0]}\" = 0 || exit 1", rounds = 24, twoTries = true, hidden = hidden)
    }

    val ALL: List<Task> by lazy { dir()?.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.mapNotNull { task(it) }.orEmpty() }

    /** 1.5.125: the JavaScript exercises; empty without the checkout or without jest. */
    val JS: List<Task> by lazy { jsDir()?.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.mapNotNull { jsTask(it) }.orEmpty() }
}
