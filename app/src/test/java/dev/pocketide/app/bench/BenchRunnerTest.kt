package dev.pocketide.app.bench

import dev.pocketide.app.bench.ScriptedModel.Companion.call
import dev.pocketide.app.bench.ScriptedModel.Companion.say
import dev.pocketide.app.bench.ScriptedModel.Companion.tools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The benchmark's own checks, with scripted models that pass and fail on purpose (1.5.86). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BenchRunnerTest {
    private fun task(name: String) = BenchTasks.ALL.first { it.name == name }

    // 1.5.161 (Copilot HANDOFF-CALIBRATION S5): every report says which version and settings it ran with
    @Test fun theReportNamesItsVersionAndSettings() {
        val env = mapOf("POCKETIDE_BENCH_SCOPE" to "1", "POCKETIDE_BENCH_APP_TOOLS" to "0", "POCKETIDE_BENCH_HELPER" to "openrouter:x/y")
        val line = BenchRunner.settingsLine({ env[it] }, "1.5.161-gpl")
        org.junit.Assert.assertEquals("Settings: version=1.5.161 ask=0 cause=0 scope=1 variety=0 look=0 verify=0 premise=0 explore=0 helper=openrouter:x/y app_tools=0", line)
    }

    @Test fun aProviderRefusalBeforeAnyToolIsNotAScore() {
        fun r(calls: Int, ending: String) = BenchRunner.Result("t", "m:x", false, 1, calls, 0, 0, 0, 1, ending, "")
        assertTrue(BenchRunner.providerError(r(0, "error: HTTP 402 over budget")))
        assertTrue(BenchRunner.providerError(r(0, "2nd try, error: HTTP 503")))
        assertFalse(BenchRunner.providerError(r(4, "error: HTTP 500")))
        assertFalse(BenchRunner.providerError(r(0, "not finished")))
        assertTrue(BenchRunner.report(listOf(r(0, "error: HTTP 402"))).contains("1 run(s) ended in a provider error"))
    }

    @Test fun aCorrectSolutionPasses() {
        val m = ScriptedModel(listOf(
            tools(call("write_file", "path" to "convert.js", "content" to "function celsiusToFahrenheit(c) { return c * 9 / 5 + 32; }\nmodule.exports = { celsiusToFahrenheit };\n")),
            tools(call("run_command", "command" to "node -e \"console.log(require('./convert.js').celsiusToFahrenheit(100))\"")),
            say("Added convert.js; 100 gives 212.")))
        val r = BenchRunner.run(task("add-feature"), m, "openrouter", "scripted")
        assertTrue(r.checkOutput + " / " + r.ending, r.pass)
    }

    // 1.5.115: Aider's two tries: a failed check gives one more attempt with its output, from the files as left, without the hidden test
    @Test fun aTwoTryTaskGetsTheTestOutputOnce() {
        val check = "set -e\ncat > 'add_test.py' <<'EOF'\nimport unittest\nfrom add import add\nclass T(unittest.TestCase):\n    def test_add(self):\n        self.assertEqual(add(2, 3), 5)\nEOF\npython3 -m unittest add_test 2>&1 | tail -25\ntest \"\${PIPESTATUS[0]}\" = 0 || exit 1"
        val task = BenchTasks.Task("py-add", "Write add(a, b).", mapOf("add.py" to "def add(a, b):\n    pass\n"), check, twoTries = true, hidden = listOf("add_test.py"))
        var second: List<Map<String, Any?>> = emptyList()
        fun text(u: Map<String, Any?>) = (u["content"] as? String) ?: (u["content"] as? List<*>)?.joinToString { (it as? Map<*, *>)?.get("text")?.toString().orEmpty() }.orEmpty()
        // each reply looks at the conversation: write once per attempt (wrong code first, right code once the test output is shown), then finish
        val step: (List<Map<String, Any?>>) -> dev.pocketide.app.core.ToolTurn = { msgs ->
            val retryAt = msgs.indexOfFirst { it["role"] == "user" && text(it).contains("See the testing errors above") }
            val retry = retryAt >= 0
            val ask = if (retry) retryAt else 0
            if (retry) second = msgs
            if (msgs.drop(ask).none { it["role"] == "tool" }) tools(call("write_file", "path" to "add.py", "content" to "def add(a, b):\n    return a ${if (retry) "+" else "-"} b\n"))(msgs)
            else say(if (retry) "Fixed." else "Done.")(msgs)
        }
        val m = ScriptedModel(List(12) { step })
        val r = BenchRunner.run(task, m, "openrouter", "scripted")
        assertTrue(r.checkOutput + " / " + r.ending, r.pass)
        assertTrue(r.firstPass == false)
        val asked = text(second.first { it["role"] == "user" && text(it).contains("See the testing errors above") })
        assertTrue(asked, asked.contains("See the testing errors above.") && asked.contains("you cannot run the tests") && asked.contains("-1 != 5") && asked.contains("Fix the code in add.py"))
        // 1.5.163: as Aider, the second try carries on the same conversation: the request, the first try's work and its answer come first
        assertTrue(text(second.first { it["role"] == "user" }).startsWith("Write add(a, b)."))
        assertTrue(second.any { it["role"] == "tool" } && second.any { it["role"] == "assistant" && it["content"] == "Done." })
        assertTrue(second.indexOfFirst { it["role"] == "assistant" && it["content"] == "Done." } < second.indexOfFirst { it["role"] == "user" && text(it).contains("See the testing errors above") })
        assertTrue(r.transcript.contains("# Second attempt (test output shown)"))
        assertTrue(BenchRunner.report(listOf(r)).contains("two-try tasks: 0 of 1 on the first try, 1 with the second"))
    }

    @Test fun aWrongSolutionFails() {
        val m = ScriptedModel(listOf(
            tools(call("write_file", "path" to "convert.js", "content" to "function celsiusToFahrenheit(c) { return c + 32; }\nmodule.exports = { celsiusToFahrenheit };\n")),
            say("Done.")))
        assertFalse(BenchRunner.run(task("add-feature"), m, "openrouter", "scripted").pass)
    }

    @Test fun aQuestionIsScoredOnTheAnswerAndUnchangedFiles() {
        val good = ScriptedModel(listOf(tools(call("search", "pattern" to "TAX_RATE =")), say("src/tax.js sets it: TAX_RATE = 0.08 (8%).")))
        assertTrue(BenchRunner.run(task("answer-a-question"), good, "openrouter", "scripted").pass)
        val wrong = ScriptedModel(listOf(say("It is in src/cart.js.")))
        assertFalse(BenchRunner.run(task("answer-a-question"), wrong, "openrouter", "scripted").pass)
    }

    @Test fun theBarrierTaskChecksTheRealFix() {
        val fix = ScriptedModel(listOf(
            tools(call("edit_file", "path" to "game/world.js", "search" to "x < 64 && z < 64", "replace" to "x < WIDTH && z < HEIGHT")),
            say("The bounds now use the map size.")))
        assertTrue(BenchRunner.run(task("remove-the-barrier"), fix, "openrouter", "scripted").pass)
        val tooMuch = ScriptedModel(listOf(
            tools(call("edit_file", "path" to "game/world.js", "search" to "return inBounds(x, z) && !blocked.has(x + ',' + z);", "replace" to "return true;")),
            say("Removed all limits.")))
        assertFalse("blocked tiles must stay blocked", BenchRunner.run(task("remove-the-barrier"), tooMuch, "openrouter", "scripted").pass)
    }

    @Test fun reportsAreReadable() {
        val r = BenchRunner.Result("t", "p:m", false, 3, 5, 1, 2, 0, 9, "not finished", "got 4.5", reads = 2)
        val md = BenchRunner.report(listOf(r))
        assertTrue(md, md.contains("| p:m | t | **no** | 3 | 5 | 1 | 2 | 0 | 9 | not finished | 2 |") && md.contains("p:m**: 0 of 1 passed"))
    }

    @Test fun readCallsAreCountedSeparatelyFromOtherTools() {
        val model = ScriptedModel(listOf(
            tools(call("read_file", "path" to "src/tax.js")),
            tools(call("read_file", "path" to "src/tax.js")),
            say("src/tax.js sets TAX_RATE = 0.08 (8%).")))
        val r = BenchRunner.run(task("answer-a-question"), model, "openrouter", "scripted")
        assertTrue(r.pass)
        org.junit.Assert.assertEquals(2, r.reads)
    }
}
