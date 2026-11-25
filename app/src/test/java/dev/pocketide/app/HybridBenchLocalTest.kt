package dev.pocketide.app

import dev.pocketide.app.core.HybridBenchTasks
import dev.pocketide.app.core.HybridBenchTasks.Facts
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** The local-model suite (bench/hybrid/local): complete, private, the app's copy matches the repository, judged for the right reasons. */
class HybridBenchLocalTest {
    private val local = HybridBenchTasks.LOCAL
    private fun task(id: String) = local.single { it.id == id }
    private val bench = listOf(File("../bench/hybrid/local"), File("bench/hybrid/local")).firstOrNull { it.isDirectory }
    private fun log(answer: String, extra: String = "") =
        "===== request started: x\n$extra----- t · round 3 · assistant said\n$answer\n\n⚡ Ran on this phone (Gemini Nano) · 2.0 s\n\n===== outcome: x\n"

    @Test fun twentyPrivateTasksWithSkills() {
        check(local.size == 20)
        check(local.all { it.privateMode && it.skill.isNotEmpty() && it.files.isNotEmpty() })
        val all = HybridBenchTasks.ALL + HybridBenchTasks.HARD + HybridBenchTasks.GAMES + local
        check(all.map { it.id }.toSet().size == all.size)
    }

    @Test fun theAppHasTheSameFilesAsTheRepository() {
        assumeTrue(bench != null)
        for (t in local) {
            val dir = File(bench, "tasks/${t.id}")
            val disk = dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).invariantSeparatorsPath to it.readText() }
            check(disk == t.files) { "${t.id}: HybridBenchLocalFiles is out of date" }
        }
    }

    @Test fun questionsAreJudgedOnTheAnswerAndNoChange() {
        val t = task("l01-find-file"); val o = t.files
        check(t.judge(Facts(o, o, null, "", log("It is defined in rules.js."), "done")) == null)
        check(t.judge(Facts(o, o, null, "", log("It is in game.js."), "done"))!!.contains("does not say rules.js"))
        check(t.judge(Facts(o + ("rules.js" to "x"), o, null, "", log("rules.js"), "done"))!!.contains("files were changed"))
        check(t.judge(Facts(o, o, null, "", log("rules.js") + "☁ Ran in the cloud (m)", "done"))!!.contains("cloud"))
        val r = task("l04-report-test-result"); val ro = r.files
        val ran = "----- t · round 1 · tool run_command · 9ms\n{\"command\":\"node test.js\"}\n→ exit code 0\nok\n"
        check(r.judge(Facts(ro, ro, 0, "ok\n", log("Yes, it passes.", ran), "done")) == null)
        check(r.judge(Facts(ro, ro, 0, "ok\n", log("Yes, it passes."), "done")) == "node test.js was not run")
    }

    @Test fun fixAndVerifyNeedsTheTestAfterTheLastEdit() {
        val t = task("l09-fix-and-verify"); val o = t.files
        val edit = "----- t · round 1 · tool edit_file · 9ms\n{}\n→ Edited price.js.\n"
        val run = "----- t · round 2 · tool run_command · 9ms\n{\"command\":\"node test.js\"}\n→ exit code 0\nok\n"
        check(t.judge(Facts(o, o, 0, "ok\n", edit + run, "done")) == null)
        check(t.judge(Facts(o, o, 0, "ok\n", run + edit, "done")) == "the test was not run after the last change")
    }

    @Test fun theReportMeasuresNanoAndScoresEachSkill() {
        val log = "→ Edit not applied to a.js: x\n→ NOT SAVED: y\n→ You already read this file\nGemini Nano did not reply with one JSON action; z\n" +
            "NOTE: that exact action already failed (x)\n⚡ Ran on this phone (Gemini Nano) · 1 s\n"
        val n = HybridBenchTasks.nano(log)
        check(n.refused == 2 && n.repeats == 1 && n.unusable == 1 && n.repairs == 1) { "${n.refused} ${n.repeats} ${n.unusable} ${n.repairs}" }
        val res = listOf(HybridBenchTasks.Result(task("l05-fix-operator"), true, "", HybridBenchTasks.counts(log), 30, "done"),
            HybridBenchTasks.Result(task("l10-add-function"), false, "x", HybridBenchTasks.counts(log), 30, "done"))
        val rep = HybridBenchTasks.report(res, "a", null, 20, "Hybrid AI benchmark (local model)")
        check(rep.contains("4 refused by the app") && rep.contains("| Fix one line | 1 of 1 |") && rep.contains("| Add new code | 0 of 1 |")) { rep }
    }
}
