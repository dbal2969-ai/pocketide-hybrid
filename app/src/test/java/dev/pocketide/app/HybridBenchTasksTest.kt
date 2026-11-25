package dev.pocketide.app

import dev.pocketide.app.core.HybridBenchTasks
import dev.pocketide.app.core.HybridBenchTasks.Facts
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HybridBenchTasksTest {
    private fun task(id: String) = HybridBenchTasks.ALL.single { it.id == id }
    private fun facts(id: String, changed: Map<String, String> = emptyMap(), exit: Int? = 0, out: String = "ok\n", transcript: String = "", ended: String = "done") =
        task(id).files.let { orig -> Facts(orig + changed, orig, exit, out, transcript, ended) }

    @Test fun everyTaskHasItsStartingFiles() {
        check(HybridBenchTasks.ALL.size == 8)
        for (t in HybridBenchTasks.ALL) check(t.files.isNotEmpty()) { "${t.id} has no files" }
        check(task("07-long-file").files["inventory.js"]!!.length > 20_000) { "the long file must be over the on-phone limit" }
        check(task("08-private-validate").privateMode)
    }

    @Test fun countsReadWhereStepsRanAndHandoffs() {
        val log = "step\n⚡ Ran on this phone (Gemini Nano) · 2.0 s\n⚡ Gemini Nano (on this phone): I'm passing this to the cloud model (gemini-3.1-flash-lite). Why: this needs the web, which Gemini Nano on the phone cannot reach.\n☁ Ran in the cloud (gemini-3.1-flash-lite) · 3.0 s\n"
        val c = HybridBenchTasks.counts(log)
        check(c.phone == 1 && c.cloud == 1) { "${c.phone} ${c.cloud}" }
        check(c.handoffs.single().startsWith("the cloud model (gemini-3.1-flash-lite): this needs the web")) { c.handoffs.toString() }
    }

    @Test fun judgesPassAndFailForTheRightReasons() {
        check(task("01-fix-add").judge(facts("01-fix-add")) == null)
        check(task("01-fix-add").judge(facts("01-fix-add", exit = 1, out = "add(2, 3) gave -1\n"))!!.contains("add(2, 3) gave -1"))
        check(task("02-explain").judge(facts("02-explain", exit = null, out = "")) == null)
        check(task("02-explain").judge(facts("02-explain", mapOf("csv.js" to "changed"), exit = null)) == "a file changed")
        check(task("05-keep-definition").judge(facts("05-keep-definition", mapOf("app.js" to "let days = [];"))) == "the definition of selected is gone")
        check(task("04-rename").judge(facts("04-rename")) == "calcTotal is still used")
        val dates = mapOf("DATES.md" to "| date-fns | MIT |\n| Day.js | MIT |\n| Luxon | MIT |")
        val web = "I'm passing this to the cloud model (x). Why: this needs the web, which Gemini Nano on the phone cannot reach."
        check(task("06-research-handoff").judge(facts("06-research-handoff", dates, exit = null, transcript = web)) == null)
        check(task("06-research-handoff").judge(facts("06-research-handoff", dates, exit = null))!!.contains("no handoff"))
        check(task("08-private-validate").judge(facts("08-private-validate", transcript = "☁ Ran in the cloud (x) · 1 s"))!!.contains("cloud"))
        val report = HybridBenchTasks.report(listOf(HybridBenchTasks.Result(task("01-fix-add"), true, "", HybridBenchTasks.counts(""), 75, "done")), "about", null)
        check(report.contains("Passed 1 of 8") && report.contains("✓ passed") && report.contains("1 min 15 s")) { report }
    }

    @Test fun eachCommandTaskFailsBeforeTheWork() {
        val node = try { ProcessBuilder("node", "--version").start().waitFor() == 0 } catch (e: Exception) { false }
        org.junit.Assume.assumeTrue("needs Node", node)
        for (t in HybridBenchTasks.ALL.filter { it.command != null }) {
            val dir = Files.createTempDirectory("bench").toFile()
            for ((p, s) in t.files) File(dir, p).apply { parentFile?.mkdirs(); writeText(s) }
            val p = ProcessBuilder("node", "test.js").directory(dir).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText(); val code = p.waitFor()
            check(code != 0) { "${t.id}: node test.js already passes before the work: $out" }
            dir.deleteRecursively()
        }
    }
}
