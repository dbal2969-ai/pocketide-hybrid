package dev.pocketide.app.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Proves the cause tasks: each fails on the start, passes the real fix, and fails when the symptom is hidden in the wrong place. */
class CauseBenchTasksTest {
    companion object {
        @BeforeClass @JvmStatic fun tools() {
            assumeTrue("node is needed for the benchmark checks", try { ProcessBuilder("bash", "-c", "node --version").start().waitFor() == 0 } catch (e: Exception) { false })
        }
    }

    private fun run(name: String, change: (File) -> Unit): Pair<Int, String> {
        val t = CauseBenchTasks.ALL.single { it.name == name }
        val dir = Files.createTempDirectory("cause").toFile()
        try {
            for ((p, text) in t.files) File(dir, p).also { it.parentFile.mkdirs() }.writeText(text)
            change(dir)
            File(dir, ".bench").mkdirs(); File(dir, ".bench/answer.txt").writeText("")
            val pr = ProcessBuilder("bash", "-c", t.check).directory(dir).redirectErrorStream(true).start()
            val out = pr.inputStream.bufferedReader().readText()
            if (!pr.waitFor(90, TimeUnit.SECONDS)) { pr.destroyForcibly(); return 124 to "timed out" }
            return pr.exitValue() to out
        } finally { dir.deleteRecursively() }
    }
    private fun passes(name: String, change: (File) -> Unit) { val (c, o) = run(name, change); assertEquals("$name should pass: $o", 0, c) }
    private fun fails(name: String, why: String, change: (File) -> Unit = {}) { val (c, o) = run(name, change); println("$name, $why: ${o.trim()}"); assertNotEquals("$name should fail when $why", 0, c) }
    private fun File.edit(p: String, f: (String) -> String) = File(this, p).let { val before = it.readText(); val after = f(before); require(after != before) { "edit of $p matched nothing" }; it.writeText(after) }

    @Test fun theSuiteIsSelectableAndUnique() {
        val names = CauseBenchTasks.ALL.map { it.name }
        assertEquals(4, names.toSet().size)
        assertEquals(names, BenchTasks.select("cause", emptySet()).map { it.name })
        for (t in CauseBenchTasks.ALL) {
            assertTrue("${t.name}: the check must be ASCII", t.check.all { it.code in 1..126 })
            assertTrue("${t.name} is a problem report", dev.pocketide.app.core.Habits.reportsProblem(t.request))
        }
    }

    @Test fun causeShipping() {
        fails("cause-shipping", "nothing was done")
        passes("cause-shipping") { it.edit("src/shipping.js") { s -> s.replace("rates.freeFrom * 1000", "rates.freeFrom * 100") } }
        fails("cause-shipping", "the rates data was changed to hide it") { it.edit("data/rates.json") { s -> s.replace("\"freeFrom\": 50", "\"freeFrom\": 5") } }
    }

    @Test fun causeMenu() {
        fails("cause-menu", "nothing was done")
        passes("cause-menu") { it.edit("src/menu.js") { s -> s.replace("note.status", "note.state") } }
        fails("cause-menu", "the condition was removed from the data") { it.edit("data/actions.json") { s -> s.replace(", \"when\": \"unpinned\"", "") } }
    }

    @Test fun causeBadge() {
        fails("cause-badge", "nothing was done")
        passes("cause-badge") { it.edit("src/badge.js") { s -> s.replace("plural(count, 'items')", "plural(count, 'item')") } }
        fails("cause-badge", "the shared helper was changed to hide it") { it.edit("src/util/plural.js") { s -> s.replace("  if (n === 1) return n + ' ' + word;", "  if (n === 1) return n + ' ' + word.replace(/s$/, '');") } }
    }

    @Test fun causeScores() {
        fails("cause-scores", "nothing was done")
        passes("cause-scores") { it.edit("src/leaderboard.js") { s -> s.replace("return { name, score };", "return { name, score: Number(score) };") } }
        fails("cause-scores", "the CSV was reordered to hide it") { it.edit("data/scores.csv") { s -> "name,score\nCy,100\nDi,25\nBo,10\nAda,9\n" } }
    }
}
