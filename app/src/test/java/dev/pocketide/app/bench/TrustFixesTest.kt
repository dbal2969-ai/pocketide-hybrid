package dev.pocketide.app.bench

import dev.pocketide.app.bench.ScriptedModel.Companion.call
import dev.pocketide.app.bench.ScriptedModel.Companion.say
import dev.pocketide.app.bench.ScriptedModel.Companion.tools
import dev.pocketide.app.core.CantDo
import dev.pocketide.app.core.ChatHome
import dev.pocketide.app.core.Outcome
import dev.pocketide.app.core.Proof
import dev.pocketide.app.core.RequestReminder
import dev.pocketide.app.core.SmallTools
import dev.pocketide.app.core.Steer
import dev.pocketide.app.core.ToolTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** HANDOFF-FABLE-TRUST-RESPONSE's changes (1.5.170): each one shown on a scripted run or on its rule. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrustFixesTest {
    private val app = mapOf("app.js" to "function add(a, b) { return a - b; }\nmodule.exports = { add };\n",
        "test.js" to "const { add } = require('./app.js');\nif (add(2, 3) !== 5) { console.error('add is wrong'); process.exit(1) }\nconsole.log('ok');\n")
    private val fix = call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;")
    private fun steps(vararg s: (List<Map<String, Any?>>) -> ToolTurn) = ScriptedModel(s.toList())
    private fun said(o: AgentHarness.Outcome, what: String) = o.messages.any { it["role"] == "user" && it["content"].toString().contains(what) }

    // change 2: only real checks count
    @Test fun readingAndVersionCommandsAreNotChecks() {
        val exists = { p: String -> p in setOf("game/main.js", "src/a.js", "test.js") }
        for (c in listOf("ls tests", "echo test", "cat test.js", "node --version", "python3 --version", "mkdir tests", "grep -n test src/a.js",
            "node -e \"console.log('ok')\"", "which node", "cd src && ls"))
            assertFalse(c, Proof.isCheckCommand(c, exists))
        for (c in listOf("npm test", "node game/main.js", "node test.js", "python3 -m pytest", "node -e \"console.log(require('./src/a.js').x)\"",
            "cd src && node ../test.js", "npx jest", "curl -s localhost:3000/api"))
            assertTrue(c, Proof.isCheckCommand(c, exists))
        assertFalse("a file that is not in the project", Proof.isCheckCommand("node nothing.js", exists))
    }

    // change 1: the app's verdict
    @Test fun theVerdictComesFromWhatTheAppSaw() {
        assertEquals(Outcome.Kind.NOT_DONE, Outcome.of(0, "", "", "", emptyList()).kind)
        assertEquals(Outcome.Kind.CHECKED, Outcome.of(1, "`npm test`", "", "", emptyList()).kind)
        assertEquals(Outcome.Kind.UNCHECKED, Outcome.of(2, "", "", "", emptyList()).kind)
        assertEquals(Outcome.Kind.NOT_DONE, Outcome.of(1, "", "`npm test` (exit 1)", "", emptyList()).kind)
        assertEquals(Outcome.Kind.NOT_DONE, Outcome.of(1, "`npm test`", "", "", listOf("2 plan steps still open")).kind)
        assertEquals(Outcome.Kind.NOT_DONE, Outcome.of(1, "", "", "24 rounds used", emptyList()).kind)
        assertTrue(Outcome.of(0, "", "", "", emptyList()).header.startsWith("✗ Nothing was changed"))
    }

    @Test fun aRunThatChangedNothingIsNotDoneWhateverItSays() {
        val bad = call("edit_file", "path" to "app.js", "search" to "return a * b;", "replace" to "return a + b;")
        val o = AgentHarness(app, "Fix add in app.js so test.js passes", steps(
            tools(call("read_file", "path" to "app.js")), tools(bad), say("Done: add is fixed."),
            tools(bad), say("Done: add is fixed."), say("Done: add is fixed."), say("Done: add is fixed."))).run()
        assertFalse(o.timedOut)
        assertFalse("ok", o.result!!.ok)
        assertEquals(Outcome.Kind.NOT_DONE, o.result!!.outcome)
        assertTrue(o.result!!.text, o.result!!.text.startsWith("✗ Nothing was changed"))
        assertEquals("app.js unchanged", app["app.js"], o.file("app.js"))
    }

    @Test fun aChangeCheckedByRunningItIsDoneAndChecked() {
        val o = AgentHarness(app, "Fix add in app.js so test.js passes", steps(
            tools(call("read_file", "path" to "app.js")), tools(fix), tools(call("run_command", "command" to "node test.js")), say("Fixed: add subtracted."))).run()
        assertEquals(Outcome.Kind.CHECKED, o.result!!.outcome)
        assertTrue(o.result!!.text, o.result!!.text.startsWith("✓ Done and checked: `node test.js`"))
        assertTrue(o.finishedOk)
    }

    @Test fun lsIsNotProofThatTheChangeWorks() {
        val o = AgentHarness(app, "Fix add in app.js", steps(
            tools(call("read_file", "path" to "app.js")), tools(fix), tools(call("run_command", "command" to "ls")),
            say("Fixed add."), say("Fixed add."), say("Fixed add."), say("Fixed add."))).run()
        assertEquals(Outcome.Kind.UNCHECKED, o.result!!.outcome)
        assertTrue(o.result!!.text, o.result!!.text.startsWith("⚠ Changed, not checked"))
        assertTrue("the nothing-was-checked ask still fired", said(o, "Not checked") || said(o, "check"))
    }

    // change 3: the job follows the person
    @Test fun yesDoItAfterAQuestionUnlocksTheChange() {
        val o = AgentHarness(app, "What does add in app.js do?", steps(
            tools(call("read_file", "path" to "app.js")), tools(call("read_file", "path" to "app.js")), tools(fix), say("Fixed it: add now adds.")),
            steer = 3 to "Yes, fix it so it adds.").run()
        assertTrue(o.file("app.js")!!, o.file("app.js")!!.contains("a + b"))
    }

    @Test fun theReminderAndTheRenameCheckFollowTheNewestMessage() {
        val r = RequestReminder.text("Rename calc to computeTotal everywhere.", listOf("Keep calc in legacy.js."))
        assertTrue(r.indexOf("Keep calc in legacy.js.") < r.indexOf("Rename calc"))
        assertFalse(r.contains("nothing it did not ask for"))
        assertTrue(Steer.job("A", listOf("B", "C")).startsWith("Newest message: \"C\""))
        val files = mapOf("calc.js" to "function calc(x) { return x; }\nmodule.exports = { calc };\n", "legacy.js" to "const { calc } = require('./calc.js');\nmodule.exports = { calc };\n")
        val o = AgentHarness(files, "Rename calc to computeTotal everywhere.", steps(
            tools(call("read_file", "path" to "calc.js")), tools(call("read_file", "path" to "legacy.js")),
            tools(call("write_file", "path" to "calc.js", "content" to "function computeTotal(x) { return x; }\nmodule.exports = { computeTotal, calc: computeTotal };\n")),
            say("Renamed it in calc.js and kept calc in legacy.js as you asked."), say("Done."), say("Done."), say("Done.")),
            steer = 3 to "Keep the name calc in legacy.js, other teams import it.").run()
        assertFalse("the rename check pulled the model back to the old job", said(o, "still appears"))
        assertEquals(files["legacy.js"], o.file("legacy.js"))
    }

    @Test fun steersThatAskForAChangeUnlock() {
        for (s in listOf("Yes, do it", "ok go ahead", "Please fix it", "Make the change")) assertTrue(s, Steer.unlocks(s))
        for (s in listOf("Why is it like that?", "Thanks")) assertFalse(s, Steer.unlocks(s))
    }

    // change 5: never "not possible" without a look
    @Test fun notPossibleIsSentBackOnceWithTheTools() {
        assertNotNull(CantDo.claim("Sorry, that isn't possible: I can't access other projects."))
        assertNull(CantDo.claim("data/prices.csv does not exist in this project."))
        val o = AgentHarness(app, "Fix add in app.js", steps(say("That is not possible, I cannot modify files."), tools(call("read_file", "path" to "app.js")), tools(fix), say("Fixed."))).run()
        assertTrue(said(o, "Before saying something cannot be done"))
        assertTrue(o.file("app.js")!!.contains("a + b"))
    }

    @Test fun aPartOfAProjectNameFindsIt() {
        val ps = listOf("2009scape-project", "Empty-project-10", "Web page project")
        assertTrue(ChatHome.namesProject("take the sprites from my scape project", ps))
        assertTrue(ChatHome.namesProject("copy it into empty project 10", ps))
        assertFalse(ChatHome.namesProject("add a dark mode to this project", ps))
    }

    // 1.5.171: a held test change is asked about while the run waits, and the answer reaches the model at once
    private val playPrior = "# reload keeps entries\ntap \"Add\"\nrun location.reload()\nexpect document.querySelectorAll('li').length === 1\n"
    private val playWeaker = "# reload keeps entries\ntap \"Add\"\nrun window.__app.render()\nexpect document.querySelectorAll('li').length === 1\n"
    private fun testRun(answer: Boolean?) = AgentHarness(app + ("tests/play.txt" to playPrior), "Fix add in app.js", steps(
        tools(call("read_file", "path" to "tests/play.txt")), tools(call("write_file", "path" to "tests/play.txt", "content" to playWeaker)),
        tools(fix), tools(call("run_command", "command" to "node test.js")), say("Fixed add."), say("Fixed add.")), testAnswer = answer).run()

    @Test fun allowingAHeldTestChangeWritesItWhileTheRunGoesOn() {
        val o = testRun(true)
        assertEquals(playWeaker, o.file("tests/play.txt"))
        assertTrue("the model heard the answer as the tool's result", o.messages.any { it["role"] == "tool" && it["content"].toString().contains("The person allowed this test change") })
        assertTrue(o.file("app.js")!!.contains("a + b"))
    }

    @Test fun keepingTheTestsLeavesThemAndTellsTheModel() {
        val o = testRun(false)
        assertEquals(playPrior, o.file("tests/play.txt"))
        assertTrue(o.messages.any { it["role"] == "tool" && it["content"].toString().contains("kept the saved tests") })
    }

    @Test fun withNoOneToAskTheChangeIsStillProposedAtTheEnd() {
        val o = testRun(null)
        assertEquals(playPrior, o.file("tests/play.txt"))
        assertEquals(listOf("tests/play.txt"), o.result!!.testProposals.map { it.first })
    }

    // change 4: the small tool set
    @Test fun twoFailedEditsNarrowTheToolSet() {
        assertTrue(SmallTools.slipped("Edit not applied to app.js: the search text was not found."))
        val bad = call("edit_file", "path" to "app.js", "search" to "return a * b;", "replace" to "return a + b;")
        val o = AgentHarness(app, "Fix add in app.js", steps(tools(bad), tools(bad), tools(call("read_file", "path" to "app.js")), tools(fix), say("Fixed."))).run()
        assertTrue(o.result!!.narrowed)
        assertTrue(said(o, "From now on you have a few plain tools"))
        assertNull("the run gives the full set back", dev.pocketide.app.core.Tools.only)
        assertTrue(o.file("app.js")!!.contains("a + b"))
    }
}
