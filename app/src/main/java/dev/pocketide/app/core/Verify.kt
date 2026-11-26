package dev.pocketide.app.core

/** Finds the project's test or check command, and turns a failing run into a message the model can act on. */
object Verify {
    enum class Outcome { PASS, FAIL, MISSING_TOOL }

    class Plan(val command: String, val label: String)

    /**
     * Python tests the check leaves out (pytest --deselect ids). Set by the benchmark to the tests that fail on this phone even with
     * the reference fix, so the in-app check matches the scoring (1.5.200); empty otherwise.
     */
    @Volatile var deselect: List<String> = emptyList()

    /** False for the fallback "syntax checks", which only show the files parse (1.5.173); tests and builds run the code. */
    fun runsCode(label: String): Boolean = label != "syntax checks"

    private val NPM_DEFAULT = Regex("""no test specified""", RegexOption.IGNORE_CASE)
    private val CODE_EXT = setOf("py", "js", "mjs", "cjs", "ts", "tsx", "jsx", "go", "rs", "c", "cc", "cpp", "h", "java", "kt", "sh", "rb", "php", "cs", "swift", "json", "toml", "mod")

    /** True when at least one changed file is code that tests or checks could catch problems in. */
    fun relevant(changed: Collection<String>): Boolean = changed.any { it.substringAfterLast('.', "").lowercase() in CODE_EXT }

    /** The best command to verify the project: tests when there are any, otherwise the syntax checks. [read] gives a file's text. */
    fun plan(files: List<String>, read: (String) -> String?, installed: Set<String>? = null, isRepo: (String) -> Boolean = { false }): Plan? {
        // 1.5.164: code inside a downloaded repository (a folder with its own .git) is not the project's to check: in the 2009scape field
        // run the syntax check failed on server/Tools/diff_json_simple.py, a tool of the cloned server, and the run ended on it
        val fs = RunTargets.ownFiles(files.filter { !RunTargets.ignored(it) }, isRepo)
        fun has(t: String) = installed == null || t in installed
        if ("package.json" in fs && has("node")) {
            val pkg = read("package.json").orEmpty()
            val m = Regex(""""test"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(pkg)
            if (m != null && !NPM_DEFAULT.containsMatchIn(m.groupValues[1])) return Plan("npm test --silent 2>&1", "npm test")
        }
        val pyTests = fs.any { it.endsWith(".py") && (it.substringAfterLast('/').startsWith("test_") || it.endsWith("_test.py") || it.startsWith("tests/")) }
        if (pyTests && has("python"))
            return Plan("if python3 -c 'import pytest' 2>/dev/null; then python3 -m pytest -q -x" +
                deselect.filter { it.matches(Regex("""[\w./\-]+::[\w\[\]\-.]+""")) }.joinToString("") { " --deselect $it" } +
                " 2>&1; else python3 -m unittest discover -q 2>&1; fi", "Python tests")
        if ("go.mod" in fs && fs.any { it.endsWith("_test.go") } && has("go")) return Plan("go test ./... 2>&1", "go test")
        if ("Cargo.toml" in fs && has("cargo")) return Plan("cargo test -q 2>&1", "cargo test")
        val mk = fs.firstOrNull { it == "Makefile" }
        if (mk != null && Regex("""(?m)^test\s*:""").containsMatchIn(read(mk).orEmpty())) return Plan("make test 2>&1", "make test")
        val check = RunTargets.checkCommand(fs, installed) ?: return null
        return Plan(check, "syntax checks")
    }

    private val BAD = Regex("""(error|fail|assert|traceback|exception|panic|cannot find|not defined|undefined|unexpected|syntaxerror|\bE\s)""", RegexOption.IGNORE_CASE)

    fun outcome(code: Int, out: String): Outcome = when {
        code == 0 && !Regex("""(?m)^\S+: (SyntaxError|error:)""").containsMatchIn(out) -> Outcome.PASS
        code == 127 || Regex("""(command not found|: not found|No such file or directory: 'python3?'|not recognized)""", RegexOption.IGNORE_CASE).containsMatchIn(out.take(400)) && code != 1 -> Outcome.MISSING_TOOL
        else -> Outcome.FAIL
    }

    /** The useful part of long output: lines that look like errors with one line of context, or else the last lines. */
    fun trim(out: String, maxChars: Int = 3000): String {
        val lines = out.replace("\r", "").lines()
        if (lines.size <= 40 && out.length <= maxChars) return out.trim()
        val keep = sortedSetOf<Int>()
        lines.forEachIndexed { i, l -> if (BAD.containsMatchIn(l)) { keep.add(i); if (i > 0) keep.add(i - 1); if (i + 1 < lines.size) keep.add(i + 1) } }
        val picked = if (keep.isEmpty()) lines.takeLast(40) else keep.toList().takeLast(60).map { lines[it] }
        val s = picked.joinToString("\n").trim()
        return if (s.length <= maxChars) s else s.takeLast(maxChars)
    }

    fun fixRequest(plan: Plan, code: Int, out: String, attempt: Int, max: Int): String =
        "Verification failed: `${plan.command.take(120)}` exited with code $code (attempt $attempt of $max).\n\n" + trim(out) +
            "\n\nRead the failing code, fix the cause with your tools (do not just change the test to pass unless the test itself is wrong), and I will run the check again."
}
