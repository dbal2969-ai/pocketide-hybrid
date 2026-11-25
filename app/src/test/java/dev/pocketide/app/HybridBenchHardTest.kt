package dev.pocketide.app

import dev.pocketide.app.core.HybridBenchTasks
import dev.pocketide.app.core.HybridBenchTasks.Facts
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The hard hybrid benchmark (bench/hybrid/hard): its tasks are complete, fair and judged for the right reasons. */
class HybridBenchHardTest {
    private val hard = HybridBenchTasks.HARD
    private fun task(id: String) = hard.single { it.id == id }
    private fun facts(id: String, changed: Map<String, String> = emptyMap(), exit: Int? = 0, out: String = "ok\n", transcript: String = "") =
        task(id).files.let { orig -> Facts(orig + changed, orig, exit, out, transcript, "done") }
    private val bench = listOf(File("../bench/hybrid/hard"), File("bench/hybrid/hard")).firstOrNull { it.isDirectory }

    @Test fun everyTaskHasItsFilesAndIdsAreUnique() {
        check(hard.size == 10)
        check((hard + HybridBenchTasks.ALL).map { it.id }.toSet().size == hard.size + HybridBenchTasks.ALL.size)
        for (t in hard) check(t.files.isNotEmpty()) { "${t.id} has no files" }
        check(task("h07-long-file-two-bugs").files["inventory.js"]!!.length > 28_000) { "the long file must be well over the on-phone limit" }
        check(task("h08-private-password").privateMode)
        check(task("h06-ui-filters").files.containsKey("tests/play.txt"))
    }

    @Test fun theAppHasTheSameFilesAsTheRepository() {
        assumeTrue("needs bench/hybrid/hard", bench != null)
        for (t in hard) {
            val dir = File(bench, "tasks/${t.id}")
            val disk = dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).invariantSeparatorsPath to it.readText() }
            check(disk == t.files) { "${t.id}: HybridBenchHardFiles is out of date; run bench/hybrid/to_kotlin.py bench/hybrid/hard ... HybridBenchHardFiles" }
        }
    }

    @Test fun eachCommandTaskFailsAsGivenAndPassesWithItsReferenceFix() {
        assumeTrue("needs bench/hybrid/hard", bench != null)
        val node = try { ProcessBuilder("node", "--version").start().waitFor() == 0 } catch (e: Exception) { false }
        assumeTrue("needs Node", node)
        fun run(dir: File): Pair<Int, String> { val p = ProcessBuilder("node", "test.js").directory(dir).redirectErrorStream(true).start(); val o = p.inputStream.bufferedReader().readText(); return p.waitFor() to o }
        for (t in hard.filter { it.command != null }) {
            val dir = Files.createTempDirectory("hard").toFile()
            for ((p, s) in t.files) File(dir, p).apply { parentFile?.mkdirs(); writeText(s) }
            val (before, out) = run(dir)
            check(before != 0) { "${t.id} already passes: $out" }
            val sol = File(bench, "solutions/${t.id}")
            sol.walkTopDown().filter { it.isFile }.forEach { f -> File(dir, f.relativeTo(sol).path).apply { parentFile?.mkdirs(); writeText(f.readText()) } }
            val (after, out2) = run(dir)
            check(after == 0 && out2.trim().endsWith("ok")) { "${t.id} fails with its reference fix: $out2" }
            dir.deleteRecursively()
        }
    }

    @Test fun judgesPassAndFailForTheRightReasons() {
        check(task("h01-two-bugs-two-files").judge(facts("h01-two-bugs-two-files")) == null)
        check(task("h01-two-bugs-two-files").judge(facts("h01-two-bugs-two-files", exit = 1, out = "bulk at exactly ten: got 20, want 15\n"))!!.contains("bulk at exactly ten"))
        check(task("h05-find-the-cause").judge(facts("h05-find-the-cause", mapOf("src/report.js" to "patched"))) == "the callers were patched instead of the shared date helper")
        val web = "I'm passing this to the cloud model (x). Why: this needs the web, which Gemini Nano on the phone cannot reach."
        check(task("h09-research-to-json").judge(facts("h09-research-to-json", transcript = web)) == null)
        check(task("h09-research-to-json").judge(facts("h09-research-to-json"))!!.contains("no handoff"))
        check(task("h08-private-password").judge(facts("h08-private-password", transcript = "☁ Ran in the cloud (x) · 1 s"))!!.contains("cloud"))
        check(task("h06-ui-filters").judge(facts("h06-ui-filters", exit = null))!!.contains("buttons"))
        val ui = mapOf("index.html" to task("h06-ui-filters").files["index.html"]!!.replace("<ul", "<button>All</button><button>Active</button><button>Done</button><ul"))
        check(task("h06-ui-filters").judge(facts("h06-ui-filters", ui, exit = null, transcript = "outcome: ✓ Done and checked")) == null)
        val report = HybridBenchTasks.report(emptyList(), "about", null, hard.size, "Hybrid AI benchmark (hard)")
        check(report.startsWith("# Hybrid AI benchmark (hard)") && report.contains("Passed 0 of 10")) { report }
    }

    @Test fun theRoutingRuleSendsTheResearchTaskToTheCloud() =
        check(dev.pocketide.app.core.HybridRoute.needsCloud(task("h09-research-to-json").prompt) != null)
}
