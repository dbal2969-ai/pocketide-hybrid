package dev.pocketide.app.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * With a polyglot checkout (POCKETIDE_POLYGLOT_DIR, set by the benchmark workflow): every task loads, its check fails on the stub and
 * passes with the exercise's own example solution. The exercises' code runs in the benchmark sandbox only.
 */
class PolyglotTasksTest {
    @Test fun tasksFailAsGivenAndPassWithTheExample() {
        val dir = PolyglotTasks.dir()
        assumeTrue("set POCKETIDE_POLYGLOT_DIR", dir != null && System.getenv("POCKETIDE_BENCH_MODELS").orEmpty().isNotBlank())
        val all = PolyglotTasks.ALL
        assertEquals(34, all.size)
        val wrong = ArrayList<String>()
        for (t in all) {
            assertTrue(t.name, t.request.contains("Use the above instructions to modify the supplied files") && t.files.keys.none { it.endsWith("_test.py") })
            val ex = File(dir, t.name.removePrefix(PolyglotTasks.PREFIX))
            fun run(withExample: Boolean): Int {
                val d = Files.createTempDirectory("poly").toFile()
                try {
                    for ((p, text) in t.files) File(d, p).writeText(text)
                    if (withExample) File(d, t.files.keys.first()).writeText(File(ex, ".meta/example.py").readText())
                    return AgentHarness.shell(d, t.check).first
                } finally { d.deleteRecursively() }
            }
            if (run(false) == 0) wrong.add("${t.name} passes with the stub")
            if (run(true) != 0) wrong.add("${t.name} fails with its example solution")
        }
        println("POLYGLOT CHECKS: ${all.size} tasks, problems: " + wrong.ifEmpty { listOf("none") })
        assertEquals(emptyList<String>(), wrong)
    }

    // 1.5.125: the JavaScript exercises are set up as Aider's npm-test.sh does; this runs without a checkout or Node
    @Test fun aJavaScriptTaskHidesTheSpecAndRunsItsSkippedTests() {
        val root = Files.createTempDirectory("polyjs").toFile()
        try {
            val ex = File(root, "two-fer").apply { mkdirs() }
            File(ex, ".meta").mkdirs(); File(ex, ".docs").mkdirs(); File(ex, "data").mkdirs()
            File(ex, ".meta/config.json").writeText("""{"files": {"solution": ["two-fer.js"], "test": ["two-fer.spec.js"], "example": [".meta/proof.ci.js"]}}""")
            File(ex, ".docs/instructions.md").writeText("Say two-fer.")
            File(ex, "two-fer.js").writeText("export const twoFer = () => { throw new Error('Remove this statement'); };\n")
            File(ex, "two-fer.spec.js").writeText("import { twoFer } from './two-fer';\ntest('one', () => {});\nxtest('two', () => {});\n")
            File(ex, "package.json").writeText("""{"scripts": {"test": "jest ./*"}}""")
            File(ex, "babel.config.js").writeText("module.exports = { presets: ['@exercism/babel-preset-javascript'] };\n")
            File(ex, "data/words.txt").writeText("some words\n")
            val jest = File(root, "jest").apply { File(this, "node_modules/.bin").mkdirs(); File(this, "node_modules/.bin/jest").writeText("") }
            assertNull("no jest, no task", PolyglotTasks.jsTask(ex, null))
            val t = PolyglotTasks.jsTask(ex, jest)!!
            assertEquals("js-two-fer", t.name)
            assertEquals(setOf("two-fer.js"), t.files.keys)
            assertTrue(t.request, t.request.startsWith("Say two-fer.") && t.request.contains("Use the above instructions to modify the supplied files: two-fer.js"))
            assertFalse("the model never sees the tests", t.request.contains("xtest") || t.files.values.any { it.contains("test(") })
            assertTrue(t.twoTries)
            assertEquals(listOf("two-fer.spec.js", "package.json", "babel.config.js", "data/words.txt"), t.hidden)
            assertTrue(t.check, t.check.contains("test('two'") && !t.check.contains("xtest("))
            assertTrue(t.check, t.check.contains("NODE_PATH='${File(jest, "node_modules").path}'") && t.check.contains("npm test"))
        } finally { root.deleteRecursively() }
    }

    @Test fun theJavaScriptSuitesSayWhatIsMissing() {
        assumeTrue("only without a checkout and jest", PolyglotTasks.jsDir() == null || PolyglotTasks.jestDir() == null)
        for (suite in listOf("polyglot-js", "polyglot-all"))
            assertTrue(suite, assertThrows(IllegalArgumentException::class.java) { BenchTasks.select(suite, emptySet()) }.message!!.contains("POCKETIDE_POLYGLOT_JEST"))
    }

    /**
     * 1.5.125: with a checkout (POCKETIDE_POLYGLOT_DIR), jest installed (POCKETIDE_POLYGLOT_JEST) and Node: every JavaScript task
     * fails on its stub (except the refactoring exercise ledger, which passes as given) and passes with the exercise's own
     * reference solution (.meta/proof.ci.js). In the benchmark workflow (POCKETIDE_BENCH_MODELS set) the checks run in the task sandbox.
     */
    private val REFACTORING = setOf("js-ledger")

    @Test fun javaScriptTasksFailAsGivenAndPassWithTheExample() {
        val dir = PolyglotTasks.jsDir()
        assumeTrue("set POCKETIDE_POLYGLOT_DIR and POCKETIDE_POLYGLOT_JEST", dir != null && PolyglotTasks.jestDir() != null)
        val node = try { ProcessBuilder("node", "--version").redirectErrorStream(true).start().let { it.inputStream.readBytes(); it.waitFor() == 0 } } catch (e: Exception) { false }
        assumeTrue("Node is not installed", node)
        val all = PolyglotTasks.JS
        assertEquals(49, all.size)
        val wrong = ArrayList<String>()
        for (t in all) {
            assertTrue(t.name, t.files.keys.none { it.endsWith(".spec.js") } && t.hidden.any { it.endsWith(".spec.js") })
            val ex = File(dir, t.name.removePrefix(PolyglotTasks.JS_PREFIX))
            fun run(withExample: Boolean): Int {
                val d = Files.createTempDirectory("polyjs").toFile()
                try {
                    for ((p, text) in t.files) File(d, p).writeText(text)
                    if (withExample) File(d, t.files.keys.first()).writeText(File(ex, PolyglotTasks.files(ex, "example").first()).readText())
                    return AgentHarness.shell(d, t.check).first
                } finally { d.deleteRecursively() }
            }
            // ledger is a refactoring exercise: its stub is working code that passes by design (a free pass in Aider's score too)
            val stubPasses = run(false) == 0
            if (stubPasses != (t.name in REFACTORING)) wrong.add("${t.name} " + (if (stubPasses) "passes" else "fails") + " with the stub")
            if (run(true) != 0) wrong.add("${t.name} fails with its example solution")
        }
        println("POLYGLOT JS CHECKS: ${all.size} tasks, problems: " + wrong.ifEmpty { listOf("none") })
        assertEquals(emptyList<String>(), wrong)
    }
}
