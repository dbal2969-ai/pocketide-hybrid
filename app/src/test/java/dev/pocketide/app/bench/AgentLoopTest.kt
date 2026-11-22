package dev.pocketide.app.bench

import dev.pocketide.app.bench.ScriptedModel.Companion.call
import dev.pocketide.app.bench.ScriptedModel.Companion.say
import dev.pocketide.app.bench.ScriptedModel.Companion.tools
import dev.pocketide.app.core.Scope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The real agent loop (NativeAgent), driven by scripted model replies (1.5.85). Each test is a behaviour a real run showed
 * or a rule a release added; they run in the JVM suite, so a change that breaks the loop fails here instead of on a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentLoopTest {
    /** The model's words: the reply without the app's verdict line above them (Outcome, 1.5.170). */
    private fun words(o: AgentHarness.Outcome): String = o.result!!.text.let { t -> if (Regex("^[✓⚠✗] ").containsMatchIn(t)) t.substringAfter("\n\n") else t }

    private val app = mapOf(
        "app.js" to "function add(a, b) {\n  return a - b;\n}\nmodule.exports = { add };\n",
        "test.js" to "const { add } = require('./app.js');\nif (add(2, 3) !== 5) { console.error('add(2, 3) gave ' + add(2, 3)); process.exit(1); }\nconsole.log('ok');\n",
        "tests/play.txt" to "# reload keeps entries\ntap \"Add\"\nrun location.reload()\nexpect document.querySelectorAll('li').length === 1\n",
    )

    private fun run(request: String, vararg steps: (List<Map<String, Any?>>) -> dev.pocketide.app.core.ToolTurn, askOnly: Boolean = false) =
        AgentHarness(app, request, ScriptedModel(steps.toList()), askOnly = askOnly, askCheck = true).run()

    @Test fun aSimpleFixIsMadeCheckedAndFinished() {
        val o = run("Fix add in app.js so the test passes",
            tools(call("read_file", "path" to "app.js")),
            tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;")),
            tools(call("run_command", "command" to "node test.js")),
            say("Fixed add: it subtracted. node test.js prints ok."))
        assertFalse("timed out", o.timedOut)
        assertTrue(o.file("app.js")!!.contains("a + b"))
        assertTrue(o.resultsOf("run_command").single().contains("ok"))
        assertTrue(o.finishedOk)
        assertEquals(listOf("read_file", "edit_file", "run_command"), o.names)
    }

    @Test fun aNameNothingDefinesIsReportedAndAskedAboutBeforeFinishing() {
        // 1.5.103: a real run wrote `FOOD[sid]` with FOOD defined nowhere; every check passed and every inventory tap threw
        org.junit.Assume.assumeTrue("needs Node", NodeNames.available)
        val o = run("Add a cook(sid) function to app.js that gives the cooked item id",
            tools(call("edit_file", "path" to "app.js", "search" to "module.exports = { add };", "replace" to "function cook(sid) { return FOOD[sid]; }\nmodule.exports = { add, cook };")),
            say("Added cook(sid)."),
            tools(call("edit_file", "path" to "app.js", "search" to "function cook(sid)", "replace" to "const FOOD = { 317: 315 };\nfunction cook(sid)")),
            say("Defined FOOD and cook(sid)."))
        assertFalse("timed out", o.timedOut)
        val edits = o.resultsOf("edit_file")
        assertEquals(2, edits.size)   // the first finish was sent back
        assertTrue(edits[0], edits[0].contains("Warning: app.js reads names") && edits[0].contains("`FOOD` (app.js line 4)"))
        assertFalse(edits[1], edits[1].contains("Warning"))
        assertTrue(o.file("app.js")!!.contains("const FOOD"))
    }

    @Test fun anEmptyCatchIsReportedAndAskedAboutWhenErrorsWereSilent() {
        // 1.5.104: told "a bad row is silently ignored", gpt-oss-120b wrapped each insert in an empty catch in every hard-suite run
        val o = run("Saving a bad row is silently ignored. Fix add in app.js so it fails on bad input.",
            tools(call("edit_file", "path" to "app.js", "search" to "module.exports = { add };",
                "replace" to "function safeAdd(a, b) { try { return add(a, b); } catch (e) { /* ignore */ } }\nmodule.exports = { add, safeAdd };")),
            say("Done."),
            tools(call("edit_file", "path" to "app.js", "search" to "catch (e) { /* ignore */ }", "replace" to "catch (e) { throw e; }")),
            say("The error now reaches the caller."))
        assertFalse("timed out", o.timedOut)
        val edits = o.resultsOf("edit_file")
        assertEquals(2, edits.size)   // the first finish was sent back
        assertTrue(edits[0], edits[0].contains("Warning: app.js now throws an error away") && edits[0].contains("complained"))
        assertFalse(edits[1], edits[1].contains("throws an error away"))
    }

    @Test fun aFileCanBeDeletedButNotATestUnasked() {
        // 1.5.105: with no delete tool, a model "removed" a feature by emptying its files
        val o = run("Remove the old helper file old.js, nothing uses it",
            tools(call("write_file", "path" to "old.js", "content" to "module.exports = {};\n")),
            tools(call("delete_file", "path" to "old.js")),
            tools(call("delete_file", "path" to "test.js")),
            say("Removed old.js."))
        assertNull(o.file("old.js"))
        assertTrue(o.resultsOf("delete_file")[0], o.resultsOf("delete_file")[0].startsWith("Deleted old.js."))
        assertTrue(o.resultsOf("delete_file")[1], o.resultsOf("delete_file")[1].contains("is a test"))
        assertTrue(o.file("test.js") != null)
    }

    @Test fun searchIsARegexLikeGrep() {
        val o = run("Where is add defined? Do not change anything.",
            tools(call("search", "pattern" to """function add\(""")),
            say("app.js line 1."))
        assertTrue(o.resultsOf("search").single(), o.resultsOf("search").single().contains("app.js:1"))
    }

    @Test fun aReportOnlyRequestChangesNoFile() {
        val o = run("Run node test.js and report the results",
            tools(call("run_command", "command" to "node test.js")),
            tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;")),
            say("The test fails: add(2, 3) gives -1."))
        assertTrue(o.file("app.js")!!.contains("a - b"))
        assertTrue(o.resultsOf("edit_file").single().startsWith(Scope.REPORT_REFUSAL.take(30)))
    }

    @Test fun aSavedPlayTestIsNotWeakened() {
        val o = run("Make the list keep its entries",
            tools(call("edit_file", "path" to "tests/play.txt", "search" to "run location.reload()", "replace" to "run window.__app.render()")),
            say("Done."))
        assertTrue(o.file("tests/play.txt")!!.contains("location.reload()"))
        assertTrue(o.resultsOf("edit_file").single().startsWith("Not written"))
    }

    @Test fun askModeRefusesWrites() {
        val o = run("What does add do?",
            tools(call("write_file", "path" to "notes.txt", "content" to "x")),
            say("It subtracts."), askOnly = true)
        assertTrue(o.file("notes.txt") == null)
        assertFalse(o.resultsOf("write_file").single().startsWith("Wrote"))
    }

    @Test fun anUnprovenClaimIsLabelledUntested() {
        // the model edits, never checks, and keeps saying it works: it is asked for proof, then the reply says it is untested
        val steps = mutableListOf<(List<Map<String, Any?>>) -> dev.pocketide.app.core.ToolTurn>(
            tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;")))
        repeat(10) { steps.add(say("Done, it works and all tests pass.")) }
        val o = AgentHarness(app, "Fix add in app.js", ScriptedModel(steps)).run()
        assertFalse("timed out", o.timedOut)
        assertTrue(o.result!!.text, o.result!!.text.contains("untested"))
        assertTrue("re-prompts are bounded: ${o.requests}", o.requests <= 2 + dev.pocketide.app.core.Rounds.FINISH_BUDGET)
    }

    @Test fun aRepeatedFailingCallIsStopped() {
        val steps = (1..20).map { tools(call("run_command", "command" to "node missing.js")) }
        val o = AgentHarness(app, "Fix add in app.js", ScriptedModel(steps), rounds = 24).run()
        assertFalse("timed out", o.timedOut)
        assertTrue("ran ${o.names.size} commands", o.names.size < 12)
        // the stop names the real reason, not the round limit, and is saved for Continue
        assertTrue(o.result!!.text, o.result!!.text.contains("going in circles") && !o.result!!.text.contains("Tool rounds per request"))
        assertTrue(o.result!!.progress, o.result!!.progress.contains("Why it stopped"))
    }

    /**
     * Phase 1a of ROADMAP-HANDOFF.md, the field run of 2026-11-10 (BENCHMARKS.md, "Field run"): Kimi read an unchanged file 15
     * times in different ranges and printed it with `cat -n | sed` between reads, with no edit, until the person pressed Stop.
     * The loop guards never fired. The run must be stopped as going in circles well before its round limit.
     */
    @Test fun readingAnUnchangedFileInCirclesIsStopped() {
        // every read asks for a different range and every slice printed is a different one, as in the field run
        val big = app + ("recipes.js" to (1..120).joinToString("\n") { "const line$it = $it;" } + "\n")
        val steps = (0 until 15).flatMap { i ->
            listOf(
                tools(call("read_file", "path" to "recipes.js", "start" to 1 + i * 7, "end" to 20 + i * 7)),
                tools(call("run_command", "command" to "cat -n recipes.js | sed -n ${1 + i * 6},${12 + i * 6}p")),
            )
        }
        val o = AgentHarness(big, "Fix add in app.js", ScriptedModel(steps), rounds = 48).run()
        assertFalse("timed out", o.timedOut)
        assertTrue("made ${o.names.size} calls before stopping", o.names.size < 16)
        assertTrue(o.result!!.text, o.result!!.text.contains("going in circles"))
        // 1.5.125: nothing was changed, so the model is asked for the question it needs answered (StopNote.wrapAsk)
        assertTrue(o.messages.any { m -> m.toString().contains("No file was changed in this run") })
    }

    @Test fun aReportWithNothingRunIsSentBackToRun() {
        // gpt-oss-120b at 1.5.88: "I'm unable to run that command because Node is not installed", without trying
        val o = run("Run node test.js and report the results",
            say("I can't run that: Node is not installed here."),
            tools(call("run_command", "command" to "node test.js")),
            say("It fails: add(2, 3) gave -1."))
        assertFalse("timed out", o.timedOut)
        assertEquals(listOf("run_command"), o.names)
        assertTrue(o.result!!.text, o.result!!.text.contains("-1"))
    }

    @Test fun anAnswerThatOnlyNamesTheNextLookIsSentBackOnce() {
        // Llama 3.3 70B, 3 of 3 runs at 1.5.88: found the file, then answered "I should look at this file" and stopped
        val o = run("What does add in app.js return?",
            tools(call("repo_map")),
            say("add is in app.js. To find what it returns, I should look at this file."),
            tools(call("read_file", "path" to "app.js")),
            say("add returns a - b, so it subtracts."))
        assertFalse("timed out", o.timedOut)
        assertEquals(listOf("repo_map", "read_file"), o.names)
        assertTrue(o.result!!.text, o.result!!.text.contains("a - b"))
        assertTrue(o.messages.any { it["role"] == "user" && it["content"].toString().contains(dev.pocketide.app.core.Deferral.ASK) })
    }

    @Test fun aFixThatStopsAtIWillChangeIsSentBackOnce() {
        // Llama 3.3 70B on the hard suite (1.5.89): ran the tests, then "To fix this, I will change the line …" and stopped
        val o = run("npm test fails. Fix it.",
            tools(call("run_command", "command" to "node test.js")),
            say("The test fails because add subtracts. To fix this, I will change the return line in app.js."),
            tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;")),
            tools(call("run_command", "command" to "node test.js")),
            say("Fixed add: node test.js prints ok."))
        assertFalse("timed out", o.timedOut)
        assertTrue(o.file("app.js")!!.contains("a + b"))
        assertTrue(o.messages.any { it["role"] == "user" && it["content"].toString().contains(dev.pocketide.app.core.Deferral.CHANGE_ASK) })
    }

    @Test fun aSecondStopAtReadingANamedFileGetsTheFileRead() {
        // Llama 3.3 70B, hard suite (1.5.90 candidate): after the one-time ask it ended with "… in src/checkout.js. Let's read that."
        val o = run("node test.js fails. Fix it.",
            say("The test fails. Let me look at the code first."),
            say("The cause is in app.js. Let's read that to find the problem."),
            tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;")),
            tools(call("run_command", "command" to "node test.js")),
            say("Fixed add: it subtracted. node test.js prints ok."))
        assertFalse("timed out", o.timedOut)
        assertTrue(o.file("app.js")!!.contains("a + b"))
        assertTrue(o.messages.any { it["role"] == "user" && it["content"].toString().contains(dev.pocketide.app.core.Deferral.ASK) })
        val read = o.messages.map { it["content"].toString() }.single { it.contains("You said you would look at app.js, so the app read it for you:") }
        assertTrue(read, read.contains("1: function add(a, b) {"))
        assertFalse(o.names.contains("read_file"))
    }

    @Test fun aFileNamedWithoutItsFolderIsReadForTheModel() {
        // Llama 3.3 70B, hard suite (1.5.90 candidate): "I will first read the paginate.test.js file" for test/paginate.test.js
        val o = run("Fix add in app.js",
            say("Let me look at the play test first."),
            say("The steps are in a play test. To fix it, I will first read the play.txt file."),
            tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;")),
            tools(call("run_command", "command" to "node test.js")),
            say("Fixed add: node test.js prints ok."))
        assertFalse("timed out", o.timedOut)
        assertTrue(o.file("app.js")!!.contains("a + b"))
        val read = o.messages.map { it["content"].toString() }.single { it.contains("You said you would look at tests/play.txt, so the app read it for you:") }
        assertTrue(read, read.contains("tap \"Add\""))
    }

    @Test fun aSecondStopWithNothingChangedIsAskedForTheChangeOnce() {
        // Llama 3.3 70B, hard suite (1.5.90 candidate): after the one-time ask and a read, "To fix it, I will first read …" ended the run
        val steps = mutableListOf<(List<Map<String, Any?>>) -> dev.pocketide.app.core.ToolTurn>(
            say("The test fails. Let me look at the code first."),
            tools(call("read_file", "path" to "app.js")))
        repeat(6) { steps.add(say("add subtracts. To understand the tests, I should look at the test cases first.")) }
        val o = AgentHarness(app, "npm test fails. Fix it.", ScriptedModel(steps)).run()
        assertFalse("timed out", o.timedOut)
        val asks = o.messages.map { it["content"].toString() }.filter { it.contains("No file has changed yet, and your reply ends at a step you have not taken.") }
        assertEquals(1, asks.size)
        assertEquals(4, o.requests)
    }

    @Test fun aPlainPageWithAMissingScriptIsSentBackWithoutGameTalk() {
        // 1.5.90: the files-and-links check of the page the preview opens was reported as "the app's game check" on any web project
        val page = mapOf("index.html" to "<html><body><script src=\"main.js\"></script></body></html>\n")
        val steps = mutableListOf<(List<Map<String, Any?>>) -> dev.pocketide.app.core.ToolTurn>(
            tools(call("edit_file", "path" to "index.html", "search" to "<body>", "replace" to "<body><h1>Hi</h1>")),
            say("Added the heading."),
            tools(call("write_file", "path" to "main.js", "content" to "console.log('hi');\n")))
        repeat(6) { steps.add(say("Added the heading and main.js.")) }
        val o = AgentHarness(page, "Add a heading to index.html", ScriptedModel(steps)).run()
        assertFalse("timed out", o.timedOut)
        val all = o.messages.filter { it["role"] == "user" }.map { it["content"].toString() }
        assertTrue(all.joinToString("\n---\n").takeLast(3000), all.any { it.contains("Not done: the app's link check of index.html found problems") && it.contains("main.js") })
        assertFalse(all.any { it.contains("game_cache") })
        assertNotNull(o.file("main.js"))
    }

    @Test fun aReplyThatAnnouncesTheFixAndStopsIsAskedForIt() {
        // Llama 3.3 70B, hard suite (1.5.90 candidate): "This line should be … Here is the change:" ended the run with the fix unmade
        val o = run("node test.js fails. Fix it.",
            tools(call("run_command", "command" to "node test.js")),
            say("add subtracts: it should return a + b.\nHere is the change:"),
            tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;")),
            tools(call("run_command", "command" to "node test.js")),
            say("Fixed add; node test.js prints ok."))
        assertFalse("timed out", o.timedOut)
        assertEquals(1, o.messages.count { it["role"] == "user" && it["content"].toString().contains("Your reply ends with \"Here is the change:\"") })
        assertTrue(o.file("app.js")!!.contains("return a + b;"))
        assertTrue(o.result!!.text, o.result!!.text.contains("prints ok"))
        // with no tool run since the ask, the same ending is not asked about again
        val steps = mutableListOf<(List<Map<String, Any?>>) -> dev.pocketide.app.core.ToolTurn>()
        repeat(8) { steps.add(say("add subtracts: it should return a + b.\nHere is the change:")) }
        val again = AgentHarness(app, "node test.js fails. Fix it.", ScriptedModel(steps)).run()
        assertFalse("timed out", again.timedOut)
        assertEquals(1, again.messages.count { it["role"] == "user" && it["content"].toString().contains("Your reply ends with") })
    }

    @Test fun theWorkLogKeepsThinkingWordsAndStepsInOrder() {
        // 1.5.95: the thinking was counted and thrown away, and the words between steps were not saved with the chat
        val o = run("Fix add in app.js so the test passes",
            { _ -> dev.pocketide.app.core.ToolTurn("Reading app.js first.", listOf(call("read_file", "path" to "app.js")), "tool_calls", "Where is add defined? Probably app.js.", 4) },
            { _ -> dev.pocketide.app.core.ToolTurn("", listOf(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;")), "tool_calls", "It subtracts. Change - to +.", 2) },
            say("Fixed add: it subtracted."))
        assertFalse("timed out", o.timedOut)
        val log = o.result!!.log
        val kinds = log.map { it.kind.key }
        assertEquals(kinds.toString(), listOf("think", "say", "step", "think", "step"), kinds.take(5))
        assertEquals("Where is add defined? Probably app.js.", log[0].text)
        assertEquals(4, log[0].secs)
        assertTrue(log[2].text, log[2].text.startsWith("Read app.js"))
        assertFalse("thinking is not sent back to the model", o.messages.any { it["content"].toString().contains("Probably app.js") })
    }

    @Test fun aTestChangeTheRequestDidNotAskForIsPutToThePerson() {
        // 1.5.98: keeping the player's position broke a test that expected the start position; the change was only refused
        val o = run("Keep the list when the page reloads",
            tools(call("edit_file", "path" to "tests/play.txt", "search" to "expect document.querySelectorAll('li').length === 1", "replace" to "expect document.querySelectorAll('li').length >= 0")),
            say("Done: the test now accepts a kept list."))
        assertFalse("timed out", o.timedOut)
        val reply = o.resultsOf("edit_file").single()
        assertTrue(reply, reply.contains("put to the person"))
        assertEquals("the file is not written", app["tests/play.txt"], o.file("tests/play.txt"))
        val p = o.result!!.testProposals.single()
        assertEquals("tests/play.txt", p.first)
        assertTrue(p.second, p.second.contains("length >= 0"))
        assertEquals(listOf("expect document.querySelectorAll('li').length === 1"), p.third)
    }

    @Test fun aPieceOfASmallFileGetsTheWholeFileOnceThenAReminder() {
        // 1.5.99: the model read a 900-line main.js 40 to 45 times a request in 150-line pieces
        val big = (1..400).joinToString("\n") { "// line $it" } + "\nfunction add(a, b) {\n  return a - b;\n}\nmodule.exports = { add };\n"
        val files = app + ("app.js" to big)
        val o = AgentHarness(files, "Fix add in app.js so the test passes", ScriptedModel(listOf(
            tools(call("read_file", "path" to "app.js", "start" to 1, "end" to 150)),
            tools(call("read_file", "path" to "app.js", "start" to 150, "end" to 300)),
            tools(call("run_command", "command" to "node test.js")),
            tools(call("read_file", "path" to "app.js", "start" to 390, "end" to 410)),
            tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;")),
            tools(call("read_file", "path" to "app.js", "start" to 400, "end" to 405)),
            say("Fixed add.")))).run()
        val reads = o.resultsOf("read_file")
        // the first read was the whole file; after the edit the app blanks out the old text in the conversation
        assertTrue(reads[0], reads[0].startsWith("[Earlier contents of app.js left out: the file was changed afterwards."))
        assertTrue(reads[1], reads[1].startsWith("app.js has not changed since the whole file was shown"))
        assertTrue("a command does not make it unseen", reads[2].startsWith("app.js has not changed"))
        assertTrue("after its own edit the new text is sent", reads[3].startsWith("The whole of app.js (405 lines) is below (you asked for lines 400-405)") && reads[3].contains("402:   return a + b;"))
        assertEquals(4, reads.size)
    }

    @Test fun withWholeReadsOffPiecesAreServedAsAsked() {
        // 1.5.100: the benchmark turns ReadPlan off to measure it on the same code
        val big = (1..400).joinToString("\n") { "// line $it" } + "\n"
        val o = AgentHarness(app + ("app.js" to big), "What is on line 20 of app.js?", ScriptedModel(listOf(
            tools(call("read_file", "path" to "app.js", "start" to 1, "end" to 30)), say("// line 20"))), readWhole = false).run()
        val r = o.resultsOf("read_file").single()
        assertFalse(r.take(80), r.startsWith("The whole of"))
        assertTrue(r.contains("20: // line 20")); assertFalse(r.contains("200: // line 200"))
    }

    @Test fun onlyAndroidsOwnClosesAreCarriedOnByThemselves() {
        val c = dev.pocketide.app.BackgroundRun.ANDROID_CLOSED
        assertTrue(android.app.ApplicationExitInfo.REASON_LOW_MEMORY in c)
        assertTrue(android.app.ApplicationExitInfo.REASON_OTHER in c)
        assertFalse("the person swiped it away", android.app.ApplicationExitInfo.REASON_USER_REQUESTED in c)
        assertFalse("force-stopped", android.app.ApplicationExitInfo.REASON_USER_STOPPED in c)
        assertFalse("a crash is not carried on", android.app.ApplicationExitInfo.REASON_CRASH in c)
        assertFalse("unknown start", dev.pocketide.app.BackgroundRun.closedByAndroid(org.robolectric.RuntimeEnvironment.getApplication(), 0L))
    }

    @Test fun aBackgroundCloseIsExplainedInPlainWords() {
        assertTrue(dev.pocketide.app.BackgroundRun.reason(android.app.ApplicationExitInfo.REASON_LOW_MEMORY).contains("low on memory"))
        assertTrue(dev.pocketide.app.BackgroundRun.reason(android.app.ApplicationExitInfo.REASON_OTHER, "freeze by system").endsWith("(freeze by system)"))
        assertTrue(dev.pocketide.app.BackgroundRun.reason(android.app.ApplicationExitInfo.REASON_USER_REQUESTED).contains("recent apps"))
        assertEquals(null, dev.pocketide.app.BackgroundRun.lastExit(org.robolectric.RuntimeEnvironment.getApplication(), 0L))
    }

    @Test fun theSavedWorkLogOpensAndFolds() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val log = listOf(dev.pocketide.app.core.WorkLog.Item(dev.pocketide.app.core.WorkLog.Kind.THINK, "Doors have no wall bits.\nSo openDoor removes nothing.", 12),
            dev.pocketide.app.core.WorkLog.Item(dev.pocketide.app.core.WorkLog.Kind.SAY, "Found it."),
            dev.pocketide.app.core.WorkLog.Item(dev.pocketide.app.core.WorkLog.Kind.STEP, "Ran python3 -c \"\nimport json\n\""))
        val v = dev.pocketide.app.WorkLogView.saved(ctx, log) as android.widget.LinearLayout
        val head = v.getChildAt(0) as android.widget.TextView
        val inner = v.getChildAt(1) as android.widget.LinearLayout
        assertEquals("▸ How it worked: Thought once (12s) · 1 note · 1 step", head.text.toString())
        assertEquals(android.view.View.GONE, inner.visibility)
        head.performClick()
        assertEquals(android.view.View.VISIBLE, inner.visibility)
        assertEquals(3, inner.childCount)
        val thought = inner.getChildAt(0) as android.widget.TextView
        assertEquals("▸ Thought for 12s: Doors have no wall bits.", thought.text.toString())
        thought.performClick()
        assertTrue(thought.text.toString().contains("So openDoor removes nothing."))
        assertEquals("· Ran python3 -c: import json", (inner.getChildAt(2) as android.widget.TextView).text.toString())
        head.performClick()
        assertEquals(android.view.View.GONE, inner.visibility)
    }

    @Test fun allFinishAsksShareTheThreeRepromptBudget() {
        val o = run("Fix add in app.js so the test passes",
            say("Here is the change:"),
            say("Here is the change:"),
            say("I will change app.js now."),
            say("I should read app.js now."))
        assertFalse("timed out", o.timedOut)
        val asks = o.messages.filter {
            it["role"] == "user" && it["content"].toString().startsWith("Finish check ")
        }
        assertEquals(3, asks.size)
        assertTrue(asks.toString(), asks.any { it["content"].toString().contains("Your reply ends with") })
        assertTrue(asks.toString(), asks.any { it["content"].toString().contains(dev.pocketide.app.core.Deferral.CHANGE_ASK) })
        assertEquals(4, o.requests)
        assertTrue(o.result!!.text, o.result!!.text.contains("Not finished: the reply stopped before a needed step."))
        assertFalse(o.messages.any {
            it["role"] == "user" && it["content"].toString().contains(dev.pocketide.app.core.Deferral.ASK)
        })
    }

    @Test fun aLookWithNoPageIsNotRecordedAsACheck() {
        // 1.5.90 benchmark: look_at_preview answered "There is no page to look at" and the footer still said "looked at the page"
        val page = mapOf("index.html" to "<html><body><h1>Hello</h1></body></html>\n")
        val steps = mutableListOf<(List<Map<String, Any?>>) -> dev.pocketide.app.core.ToolTurn>(
            tools(call("edit_file", "path" to "index.html", "search" to "<h1>Hello</h1>", "replace" to "<h1>Hello</h1><p>Hi</p>")),
            tools(call("look_at_preview", "page" to "index.html")))
        repeat(6) { steps.add(say("Added the paragraph.")) }
        val o = AgentHarness(page, "Add a paragraph to index.html", ScriptedModel(steps)).run()
        assertFalse("timed out", o.timedOut)
        assertTrue(o.resultsOf("look_at_preview").single().startsWith("There is no page to look at"))
        assertFalse(o.result!!.text, o.result!!.text.contains("looked at the page"))
    }

    @Test fun aClaimThatNodeIsMissingIsCheckedByTheApp() {
        // Llama 3.3 70B, hard suite (1.5.90 candidate): ran `node:test`, got exit 127, ended with "Node.js is not installed"
        val o = run("Fix add in app.js so the test passes",
            tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;")),
            tools(call("run_command", "command" to "node:test test.js")),
            say("Fixed add. Node.js is not installed in this environment, so I could not run the test."),
            tools(call("run_command", "command" to "node test.js")),
            say("Fixed add: node test.js prints ok."))
        assertFalse("timed out", o.timedOut)
        assertTrue(o.messages.any { it["role"] == "user" && it["content"].toString().contains("node is installed here (`command -v node` gives /") })
        assertTrue(o.result!!.text, o.result!!.text.contains("prints ok"))
    }

    @Test fun aRepairTurnIsNotToldToRunWhatItCannotRun() {
        // Copilot PR #29 (py-connect, Kimi): in a repair turn the model said the interpreter was missing; ToolClaim answered "it is
        // installed, use it" and Proof asked for a run, both contradicting "you cannot run the tests", and it spent the budget on refused calls
        val o = AgentHarness(app, "Fix add in app.js so the test passes", ScriptedModel(listOf(
            tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;")),
            say("Fixed add: it subtracted. Node.js is not installed here, so I could not run anything, but it works now."))), repair = true).run()
        assertFalse("timed out", o.timedOut)
        val sent = o.messages.filter { it["role"] == "user" }.joinToString("\n") { it["content"].toString() }
        assertFalse(sent, sent.contains("is installed here"))
        assertFalse(sent, sent.contains("claims more than any check"))
        assertTrue(o.file("app.js")!!.contains("a + b"))
    }

    @Test fun aRenameThatLeavesTheOldNameIsSentBackWithThePlaces() {
        // Llama 3.3 70B, hard suite (1.5.90 candidate): renamed getUser in eleven places, missed one call, said "renamed everywhere"
        val o = run("Rename add to sum everywhere.",
            tools(call("edit_file", "path" to "app.js", "search" to "function add(a, b)", "replace" to "function sum(a, b)")),
            tools(call("edit_file", "path" to "app.js", "search" to "module.exports = { add };", "replace" to "module.exports = { sum };")),
            say("Renamed add to sum everywhere."),
            tools(call("write_file", "path" to "test.js", "content" to "const { sum } = require('./app.js');\nif (sum(2, 3) !== 5) process.exit(1);\nconsole.log('ok');\n")),
            tools(call("run_command", "command" to "node test.js")),
            say("Renamed add to sum in app.js and test.js; node test.js prints ok."))
        assertFalse("timed out", o.timedOut)
        assertTrue(o.file("test.js")!!.contains("sum(2, 3)"))
        // 1.5.170: sum still subtracts, so node test.js fails after the last change; this run used to end "finished" anyway
        assertEquals(o.result!!.text, dev.pocketide.app.core.Outcome.Kind.NOT_DONE, o.result!!.outcome)
        assertFalse(o.finishedOk)
        val ask = o.messages.map { it["content"].toString() }.single { it.contains("The rename is not finished: add still appears in 2 place(s):") }
        assertTrue(ask, ask.contains("test.js:1: const { add } = require('./app.js');"))
        assertFalse(ask, ask.contains("app.js:1:"))
    }

    @Test fun aClassThatNothingStylesIsSentBackOnce() {
        // gpt-oss-120b and Kimi K2.6, standard suite (1.5.90 candidate): a dark mode button toggling 'dark', no .dark rule, "the page goes dark"
        val page = mapOf(
            "index.html" to "<html><head><link rel=\"stylesheet\" href=\"style.css\"></head><body><button id=\"hi\">Say hi</button><script src=\"app.js\"></script></body></html>\n",
            "app.js" to "document.getElementById('hi').addEventListener('click', () => {});\n",
            "style.css" to "body { font-family: sans-serif; }\n")
        val addCss = call("edit_file", "path" to "style.css", "search" to "body { font-family: sans-serif; }", "replace" to "body { font-family: sans-serif; }\nbody.dark { background: #111; color: #eee; }")
        val steps = mutableListOf<(List<Map<String, Any?>>) -> dev.pocketide.app.core.ToolTurn>(
            tools(call("edit_file", "path" to "index.html", "search" to "<button id=\"hi\">Say hi</button>", "replace" to "<button id=\"hi\">Say hi</button><button id=\"dark\">Dark</button>")),
            tools(call("edit_file", "path" to "app.js", "search" to "{});\n", "replace" to "{});\ndocument.getElementById('dark').addEventListener('click', () => {\n  document.body.classList.toggle('dark');\n});\n")))
        repeat(8) { steps.add { m -> if (m.last()["content"].toString().contains("nothing styles .dark")) tools(addCss)(m) else say("Added the dark mode button.")(m) } }
        val o = AgentHarness(page, "Add a dark mode button that toggles a 'dark' class on the body.", ScriptedModel(steps)).run()
        assertFalse("timed out", o.timedOut)
        val asks = o.messages.map { it["content"].toString() }.filter { it.contains("nothing styles .dark") }
        assertEquals(1, asks.size)
        assertTrue(asks.single(), Regex("""- app\.js:\d+ toggles 'dark'""").containsMatchIn(asks.single()))
        assertTrue(o.file("style.css")!!.contains("body.dark"))
    }

    @Test fun aChangeLeftForLaterIsAskedForAfterALookWasAskedFor() {
        // Llama 3.3 70B, hard suite (1.5.90 candidate): "I will read the test file" was sent back, the model read it, then
        // stopped at "To fix it, I will edit the paginate function" and the run ended there
        val o = run("npm test fails. Fix it.",
            say("The test fails. To fix it, I will read the test file to see the exact assertion."),
            tools(call("read_file", "path" to "test.js")),
            say("The test expects 5. To fix it, I will edit the add function in app.js."),
            tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;")),
            tools(call("run_command", "command" to "node test.js")),
            say("Fixed add: node test.js prints ok."))
        assertFalse("timed out", o.timedOut)
        assertTrue(o.file("app.js")!!.contains("a + b"))
        assertTrue(o.messages.any { it["role"] == "user" && it["content"].toString().contains(dev.pocketide.app.core.Deferral.ASK) })
        assertTrue(o.messages.any { it["role"] == "user" && it["content"].toString().contains(dev.pocketide.app.core.Deferral.CHANGE_ASK) })
    }

    @Test fun aFailedLastCheckIsSentBackOnce() {
        // Llama 3.3 70B, 3 of 13 hard tasks at 1.5.89: broke the file, saw the error, and ended with "I could not check it"
        val o = run("Fix add in app.js so the test passes",
            tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a * b;")),
            tools(call("run_command", "command" to "node test.js")),
            say("I could not make the test pass: add(2, 3) gives 6."),
            tools(call("edit_file", "path" to "app.js", "search" to "return a * b;", "replace" to "return a + b;")),
            tools(call("run_command", "command" to "node test.js")),
            say("Fixed add: node test.js prints ok."))
        assertFalse("timed out", o.timedOut)
        assertEquals(listOf("edit_file", "run_command", "edit_file", "run_command"), o.names)
        assertTrue(o.file("app.js")!!.contains("a + b"))
        assertTrue(o.result!!.text, o.result!!.text.contains("prints ok"))
    }

    @Test fun aFailedLastCheckIsAskedAboutOnlyOnce() {
        val steps = mutableListOf<(List<Map<String, Any?>>) -> dev.pocketide.app.core.ToolTurn>(
            tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a * b;")),
            tools(call("run_command", "command" to "node test.js")))
        repeat(10) { steps.add(say("The test still fails; I cannot find why.")) }
        val o = AgentHarness(app, "Fix add in app.js", ScriptedModel(steps)).run()
        assertFalse("timed out", o.timedOut)
        assertEquals(4, o.requests)
        assertTrue(o.result!!.text, o.result!!.text.contains("still fails"))
    }

    @Test fun editsThatAllFailAreSentBackOnce() {
        // Llama 3.3 70B at 1.5.89: three edits to guessed files, none applied, then "renamed everywhere"
        val o = run("Fix add in app.js so the test passes",
            tools(call("edit_file", "path" to "src/app.js", "search" to "return a - b;", "replace" to "return a + b;")),
            say("Fixed add everywhere."),
            tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;")),
            tools(call("run_command", "command" to "node test.js")),
            say("Fixed add in app.js: node test.js prints ok."))
        assertFalse("timed out", o.timedOut)
        assertEquals(listOf("edit_file", "edit_file", "run_command"), o.names)
        assertTrue(o.file("app.js")!!.contains("a + b"))
    }

    @Test fun aToolCallWrittenAsTextIsRun() {
        // Llama 3.3 70B on the hard suite (1.5.89): calls written in Python or JSON form instead of called, then guessed results
        val o = run("Fix add in app.js so the test passes",
            say("First, let's read the file.\nread_file(path=\"app.js\")\n\nThen we can fix it:\nedit_file(path=\"src/guess.js\", search=\"x\", replace=\"y\")"),
            say("{\"type\": \"function\", \"name\": \"edit_file\", \"parameters\": {\"path\": \"app.js\", \"search\": \"return a - b;\", \"replace\": \"return a + b;\"}}"),
            tools(call("run_command", "command" to "node test.js")),
            say("Fixed add: node test.js prints ok."))
        assertFalse("timed out", o.timedOut)
        assertEquals(listOf("read_file", "edit_file", "run_command"), o.names)
        assertTrue(o.resultsOf("read_file").single().contains("return a - b"))
        assertTrue(o.file("app.js")!!.contains("a + b"))
        assertTrue(o.finishedOk)
        // the guessed second call was not kept in the conversation, and the model was told only the first call ran
        val said = o.messages.filter { it["role"] == "assistant" }.joinToString(" ") { (it["content"] as? String).orEmpty() }
        assertFalse(said, said.contains("guess.js"))
        assertTrue(o.messages.any { it["role"] == "user" && (it["content"] as? String).orEmpty().contains("ran the first one (read_file)") })
    }

    @Test fun theModelIsToldOnceWhenRoundsRunLow() {
        val steps = ArrayList<(List<Map<String, Any?>>) -> dev.pocketide.app.core.ToolTurn>()
        repeat(10) { i -> steps.add(tools(call("run_command", "command" to "echo $i"))) }
        repeat(6) { steps.add(say("Checked; add needs a fix I could not make.")) }
        val o = AgentHarness(app, "Fix add in app.js", ScriptedModel(steps), rounds = 12).run()
        assertFalse("timed out", o.timedOut)
        val low = o.messages.filter { it["role"] == "user" && (it["content"] as? String).orEmpty().contains("tool rounds are left") }
        assertEquals(1, low.size)
        assertTrue(low.single()["content"].toString(), (low.single()["content"] as String).startsWith("3 tool rounds are left"))
    }

    @Test fun lineBreaksEscapedTwiceAreWrittenAsLineBreaks() {
        // Llama 3.3 70B on the hard suite (1.5.89) wrote "\\n" in edit_file and broke the file with a syntax error
        val o = run("Fix add in app.js so the test passes",
            tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "const sum = a + b;\\n  return sum;")),
            tools(call("edit_file", "path" to "app.js", "search" to "const sum = a + b;\\n  return sum;", "replace" to "const total = a + b;\\n  return total;\\n  // added")),
            tools(call("run_command", "command" to "node test.js")),
            say("Fixed add: node test.js prints ok."))
        assertFalse("timed out", o.timedOut)
        val app = o.file("app.js")!!
        assertTrue(app, app.contains("const total = a + b;\n  return total;\n  // added") && !app.contains("\\n"))
        assertTrue(o.resultsOf("edit_file").last().contains("escaped twice"))
    }

    @Test fun aCallInAnExplanationIsNotRun() {
        val o = run("How do I read a file in Python? Do not change anything.",
            say("Use open:\n```python\nread_file(\"notes.txt\")\nwith open(\"notes.txt\") as f:\n    print(f.read())\n```"))
        assertFalse("timed out", o.timedOut)
        assertTrue(o.names.toString(), o.names.isEmpty())
    }

    @Test fun progressIsReportedBeforeEachRequest() {
        // the benchmark saves a run from this, so a restart mid-run keeps the steps already taken
        val seen = ArrayList<Pair<List<String>, Int>>()
        val o = AgentHarness(app, "Fix add in app.js so the test passes", ScriptedModel(listOf(
            tools(call("read_file", "path" to "app.js")),
            tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;")),
            say("Fixed add."))), onProgress = { calls, n, _ -> seen.add(calls.map { it.first.name } to n) }).run()
        assertFalse("timed out", o.timedOut)
        assertEquals(o.requests, seen.size)
        assertEquals(emptyList<String>() to 1, seen.first())
        assertTrue(seen.toString(), seen.any { it.first == listOf("read_file", "edit_file") })
    }

    // 1.5.138, the ask checklist (AskLedger): a request with two asks is checked ask by ask; an ask with no proof goes back by name
    @Test fun aRequestWithTwoAsksIsCheckedAskByAsk() {
        val o = run("Fix add in app.js so the test passes. Also add a subtract function to app.js.",
            tools(call("read_file", "path" to "app.js")),
            tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;")),
            tools(call("run_command", "command" to "node test.js")),
            say("Fixed add."),
            say("ASKS\n1. fix add | app.js:2 returns a + b, `node test.js` printed ok\n2. add a subtract function | not done yet"),
            tools(call("edit_file", "path" to "app.js", "search" to "module.exports = { add };", "replace" to "function subtract(a, b) {\n  return a - b;\n}\nmodule.exports = { add, subtract };")),
            tools(call("run_command", "command" to "node -e \"require('assert').strictEqual(require('./app.js').subtract(5, 3), 2)\"")),   // 1.5.175: a check that can fail (a print alone is asked to assert)
            say("Fixed add and added subtract (prints 2)."))
        assertFalse("timed out", o.timedOut)
        assertTrue(o.finishedOk)
        assertTrue(o.file("app.js")!!.contains("function subtract"))
        val sent = o.messages.filter { it["role"] == "user" }.joinToString("\n") { m -> (m["content"] as? String) ?: (m["content"] as? List<*>)?.joinToString { (it as? Map<*, *>)?.get("text").toString() }.orEmpty() }
        assertTrue(sent, sent.contains("the request has several asks"))
        assertTrue(sent, sent.contains("These asks have no proof yet:\n- add a subtract function"))
        // 1.5.139: after a send-back the person sees the model's reply, never a bare list
        assertEquals("Fixed add and added subtract (prints 2).", words(o).substringBefore("\n\nChecks run").trim())
    }

    // 1.5.139 (Copilot, ANALYSIS-1.5.138-ASKS F1): when the list passes, the answer is the summary written before it, not "ASKS ..."
    @Test fun aPassingChecklistLeavesTheSummaryAsTheAnswer() {
        val o = run("Fix add in app.js so the test passes. Also add a subtract function to app.js.",
            tools(call("read_file", "path" to "app.js")),
            tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;")),
            tools(call("edit_file", "path" to "app.js", "search" to "module.exports = { add };", "replace" to "function subtract(a, b) {\n  return a - b;\n}\nmodule.exports = { add, subtract };")),
            tools(call("run_command", "command" to "node test.js")),
            say("Fixed add and added subtract; node test.js prints ok."),
            say("ASKS\n1. fix add | app.js:2 returns a + b\n2. add a subtract function | app.js:4, `node test.js` printed ok"))
        assertFalse("timed out", o.timedOut)
        assertTrue(o.finishedOk)
        assertEquals(6, o.requests)
        assertEquals("Fixed add and added subtract; node test.js prints ok.", words(o).substringBefore("\n\nChecks run").trim())
        assertFalse(o.result!!.text, o.result!!.text.contains("ASKS"))
    }

    // 1.5.140 (Copilot, ANALYSIS-1.5.139-ASKS G3): a send-back that changed nothing keeps the summary as the answer; the reply to it
    // ("That ask is already done, here's the proof") answers the app, not the person (ask-cli-1 in run 43470290747)
    @Test fun aSendBackThatChangesNothingKeepsTheSummary() {
        val o = run("Fix add in app.js so the test passes. Also add a subtract function to app.js.",
            tools(call("read_file", "path" to "app.js")),
            tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;")),
            tools(call("edit_file", "path" to "app.js", "search" to "module.exports = { add };", "replace" to "function subtract(a, b) {\n  return a - b;\n}\nmodule.exports = { add, subtract };")),
            tools(call("run_command", "command" to "node test.js")),
            say("Fixed add and added subtract; node test.js prints ok."),
            say("ASKS\n1. fix add | app.js:2\n2. add a subtract function | it is there"),
            say("That ask is already done. Here's the proof: app.js:4 defines subtract.\n\nASKS\n1. fix add | app.js:2\n2. add a subtract function | app.js:4"))
        assertFalse("timed out", o.timedOut)
        assertTrue(o.finishedOk)
        assertEquals(7, o.requests)
        assertEquals("Fixed add and added subtract; node test.js prints ok.", words(o).substringBefore("\n\nChecks run").trim())
    }

    // 1.5.140 (G2): a proof that quotes the ask's subject ("Unknown option") is not sent back
    @Test fun aProofQuotingUnknownIsNotSentBack() {
        val o = run("Fix add in app.js so the test passes. Also make an unknown option print \"Unknown option\".",
            tools(call("read_file", "path" to "app.js")),
            tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;")),
            tools(call("run_command", "command" to "node test.js")),
            say("Fixed add; node test.js prints ok."),
            say("ASKS\n1. fix add | app.js:2\n2. an unknown option prints \"Unknown option\" | app.js:6, `node app.js --x` printed Unknown option: --x"))
        assertFalse("timed out", o.timedOut)
        assertEquals(5, o.requests)
        assertEquals("Fixed add; node test.js prints ok.", words(o).substringBefore("\n\nChecks run").trim())
    }

    // 1.5.139 (F5): the checklist has its own slots, so the verify ask, the list and a send-back leave the shared finish budget at one spent
    @Test fun theChecklistDoesNotSpendTheFinishBudget() {
        val o = run("Fix add in app.js so the test passes. Also add a subtract function to app.js.",
            tools(call("read_file", "path" to "app.js")),
            tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;")),
            say("Fixed add."),
            tools(call("run_command", "command" to "node test.js")),
            say("Fixed add; node test.js prints ok."),
            say("ASKS\n1. fix add | app.js:2\n2. add a subtract function | not done yet"),
            tools(call("edit_file", "path" to "app.js", "search" to "module.exports = { add };", "replace" to "function subtract(a, b) {\n  return a - b;\n}\nmodule.exports = { add, subtract };")),
            tools(call("run_command", "command" to "node -e \"require('assert').strictEqual(require('./app.js').subtract(5, 3), 2)\"")),   // 1.5.175: a check that can fail (a print alone is asked to assert)
            say("ASKS\n1. fix add | app.js:2\n2. add a subtract function | app.js:4, prints 2"))
        assertFalse("timed out", o.timedOut)
        assertTrue(o.finishedOk)
        val user = o.messages.filter { it["role"] == "user" }.map { it["content"].toString() }
        assertEquals(user.toString(), 1, user.count { it.startsWith("Finish check ") })
        assertEquals(user.toString(), 2, user.count { it.startsWith("Ask check: ") })
        // the reply after the send-back is only a list, so the earlier summary is the answer
        assertEquals("Fixed add; node test.js prints ok.", words(o).substringBefore("\n\nChecks run").trim())
    }

    // 1.5.177 (SmallLocal): a small local model replies with one JSON action; tools are described in the prompt, results come back as text
    @Test fun aSmallLocalModelWorksByJsonActions() {
        val o = AgentHarness(app, "Fix add in app.js so the test passes", ScriptedModel(listOf(
            say("{\"thought\": \"Read the code.\", \"tool\": \"read_file\", \"args\": {\"path\": \"app.js\"}}"),
            say("{\"thought\": \"It subtracts.\", \"tool\": \"edit_file\", \"args\": {\"path\": \"app.js\", \"search\": \"return a - b;\", \"replace\": \"return a + b;\"}}"),
            say("{\"thought\": \"Check it.\", \"tool\": \"run_command\", \"args\": {\"command\": \"node test.js\"}}"),
            say("{\"thought\": \"Done.\", \"tool\": \"answer\", \"args\": {\"text\": \"Fixed add: it subtracted. node test.js prints ok.\"}}"))),
            askCheck = true, provider = "ollama", modelName = "qwen2.5-coder:1.5b").run()
        assertFalse("timed out", o.timedOut)
        assertTrue(o.file("app.js")!!.contains("a + b"))
        assertTrue(o.finishedOk)
        val sent = o.messages.filter { it["role"] == "user" }.joinToString("\n") { it["content"].toString() }
        // the calls ran in order (text mode: results come back as user text, not tool messages)
        assertTrue(sent, sent.indexOf("EXECUTION RESULT of [read_file]") < sent.indexOf("EXECUTION RESULT of [edit_file]") &&
            sent.indexOf("EXECUTION RESULT of [edit_file]") < sent.indexOf("EXECUTION RESULT of [run_command]"))
        assertTrue(sent, sent.contains("Next: change app.js: write it whole with tool \"write_file\""))   // the app names the next step, with the file
        assertTrue(sent, sent.contains("Next: check your change"))
        assertEquals("Fixed add: it subtracted. node test.js prints ok.", words(o).substringBefore("\n\nChecks run").trim())
    }

    // 1.5.175 (Proof.printOnly): a check that only prints cannot fail on a wrong result; the model is asked once to assert
    @Test fun aCheckThatOnlyPrintsIsAskedToAssert() {
        val o = run("Add a subtract function to app.js.",
            tools(call("read_file", "path" to "app.js")),
            tools(call("edit_file", "path" to "app.js", "search" to "module.exports = { add };", "replace" to "function subtract(a, b) {\n  return a - b;\n}\nmodule.exports = { add, subtract };")),
            tools(call("run_command", "command" to "node -e \"console.log(require('./app.js').subtract(5, 3))\"")),
            say("Added subtract."),
            tools(call("run_command", "command" to "node -e \"require('assert').strictEqual(require('./app.js').subtract(5, 3), 2)\"")),
            say("Added subtract; an assertion checks 5 - 3."))
        assertFalse("timed out", o.timedOut)
        assertTrue(o.finishedOk)
        val sent = o.messages.filter { it["role"] == "user" }.joinToString("\n") { it["content"].toString() }
        assertTrue(sent, sent.contains("only printed results"))
        assertEquals("Added subtract; an assertion checks 5 - 3.", words(o).substringBefore("\n\nChecks run").trim())
    }

    // 1.5.139 (Copilot's choice, rec. 4): with a helper model set, the helper counts the asks once and its number decides
    @Test fun theHelperCountsTheAsksWhenOneIsSet() {
        // the word counter says 1; the helper says 2, so the checklist runs
        val o = AgentHarness(app, "Fix add in app.js so the test passes, and make sure subtract still works",
            ScriptedModel(listOf(
                tools(call("read_file", "path" to "app.js")),
                tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;")),
                tools(call("run_command", "command" to "node test.js")),
                say("Fixed add; node test.js prints ok."),
                say("2"),
                say("ASKS\n1. fix add | app.js:2\n2. subtract still works | `node test.js` printed ok"))),
            helper = "groq:llama-3.1-8b-instant", askCheck = true).run()
        assertFalse("timed out", o.timedOut)
        // the main model: 4 steps and the list; the helper: the count (and the reviewer, which runs on the helper too)
        val roles = o.log.map { it.role }
        assertEquals(roles.toString(), 1, roles.count { it == "askcount" })
        assertEquals(roles.toString(), 5, roles.count { it == "main" })
        assertTrue(o.result!!.text, words(o).startsWith("Fixed add; node test.js prints ok."))
        // 1.5.146: the reviewer on the helper is its own job, and the run's own tool calls stay the run's
        assertTrue(roles.toString(), "review" in roles && "route" !in roles)
        assertEquals(listOf("read_file", "edit_file", "run_command"), o.names)
    }

    // 1.5.142: the helper can add the checklist but never remove it; the word counter says 2 here, the helper 1, and the list is asked
    @Test fun aHelperThatCountsOneAskDoesNotSkipTheChecklist() {
        val o = AgentHarness(app, "Fix add in app.js. It should add, not subtract.",
            ScriptedModel(listOf(
                tools(call("read_file", "path" to "app.js")),
                tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;")),
                tools(call("run_command", "command" to "node test.js")),
                say("Fixed add; node test.js prints ok."),
                say("1"),
                say("ASKS\n1. fix add | app.js:2\n2. it should add | `node test.js` printed ok"))),
            helper = "groq:llama-3.1-8b-instant", askCheck = true).run()
        assertFalse("timed out", o.timedOut)
        assertEquals(2, dev.pocketide.app.core.AskLedger.count("Fix add in app.js. It should add, not subtract."))
        val roles = o.log.map { it.role }
        assertEquals(roles.toString(), 1, roles.count { it == "askcount" })
        assertEquals(roles.toString(), 5, roles.count { it == "main" })
        assertTrue(o.result!!.text, words(o).startsWith("Fixed add; node test.js prints ok."))
    }

    @Test fun findCodeUsesSearchByMeaning() {
        var asked = ""
        val meaning: (String, Map<String, String>) -> List<dev.pocketide.app.core.RankedSearch.Hit> = { q, files ->
            asked = q; listOf(dev.pocketide.app.core.RankedSearch.Hit("test.js", 1, 3, 0.9, listOf(1 to files.getValue("test.js").lines()[0])))
        }
        val o = AgentHarness(app, "Where is the check that the sum is right? Only tell me.",
            ScriptedModel(listOf(tools(call("find_code", "query" to "zzqx verifies the total")), say("In test.js."))), meaning = meaning).run()
        assertFalse("timed out", o.timedOut)
        assertEquals("zzqx verifies the total", asked)
        val shown = o.messages.joinToString { it["content"].toString() }
        assertTrue(shown, shown.contains("- test.js:1-3"))

        val failing: (String, Map<String, String>) -> List<dev.pocketide.app.core.RankedSearch.Hit> = { _, _ -> throw IllegalStateException("HTTP 401") }
        val o2 = AgentHarness(app, "Where is add defined? Only tell me.",
            ScriptedModel(listOf(tools(call("find_code", "query" to "add function")), say("In app.js."))), meaning = failing).run()
        val shown2 = o2.messages.joinToString { it["content"].toString() }
        assertTrue(shown2, shown2.contains("- app.js:"))
    }

    // 1.5.141: a search that finds nothing also names the closest places, so the next step can read instead of guessing again
    @Test fun anEmptySearchNamesTheClosestPlaces() {
        val o = AgentHarness(app, "Where are numbers added? Only tell me.",
            ScriptedModel(listOf(tools(call("search", "pattern" to "addNumbers\\(")), say("In app.js."))), meaning = null).run()
        assertFalse("timed out", o.timedOut)
        val shown = o.messages.joinToString { it["content"].toString() }
        assertTrue(shown, shown.contains("No matches for 'addNumbers\\('"))
        assertTrue(shown, shown.contains("The places closest to it") && shown.contains("- app.js:1-"))
        assertEquals("addNumbers", dev.pocketide.app.core.RankedSearch.queryOf("\\baddNumbers\\("))
        assertEquals("", dev.pocketide.app.core.RankedSearch.queryOf("^\\s*$"))
    }

    // 1.5.145 (Copilot's rule B): the checklist is off unless the person turns it on
    @Test fun theChecklistIsOffByDefault() {
        val o = AgentHarness(app, "Fix add in app.js so the test passes. Also add a subtract function to app.js.",
            ScriptedModel(listOf(
                tools(call("read_file", "path" to "app.js")),
                tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;")),
                tools(call("run_command", "command" to "node test.js")),
                say("Fixed add; node test.js prints ok."))), askCheck = false).run()
        assertFalse("timed out", o.timedOut)
        assertEquals(4, o.requests)
        assertFalse(o.messages.joinToString { it["content"].toString() }.contains("Ask check:"))
    }

    // Copilot's HANDOFF-RAISE-CEILING WP3 (1.5.145): a run with an outside cut-off (the benchmark's) wraps up at 60% of it and answers
    @Test fun aRunWithAHardLimitWrapsUpBeforeIt() {
        val o = AgentHarness(app, "Why does add in app.js subtract? Find the cause.",
            ScriptedModel(listOf(
                { _ -> Thread.sleep(3_300); dev.pocketide.app.core.ToolTurn("", listOf(call("read_file", "path" to "app.js")), "tool_calls") },
                tools(call("search", "pattern" to "subtract")),
                say("add returns a - b at app.js:2; that is the cause. I have not changed anything."))),
            timeoutSeconds = 5).run()
        assertFalse("timed out", o.timedOut)
        assertTrue(o.result!!.text, words(o).startsWith("add returns a - b at app.js:2"))
        assertTrue(o.result!!.cutShort, o.result!!.cutShort.contains("-second limit"))
        // the wrap-up ask went out after the first round; the model may finish its last call (here the search), then it answers
        assertEquals(3, o.requests)
        assertTrue(o.messages.joinToString { it["content"].toString() }.contains("-second limit"))
    }

    // 1.5.147 (CauseGate): in a problem report the first change waits once for a named cause; then it goes through
    private val report = "node test.js fails: add gives the wrong result. Fix it."

    @Test fun theFirstChangeWaitsForANamedCause() {
        assertTrue(dev.pocketide.app.core.Habits.reportsProblem(report))
        val o = AgentHarness(app, report, ScriptedModel(listOf(
            tools(call("read_file", "path" to "app.js")),
            tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;")),
            tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;"),
                text = "The cause is app.js:2: add returns a - b instead of a + b."),
            tools(call("run_command", "command" to "node test.js")),
            say("The cause was app.js:2 (a - b). Fixed; node test.js prints ok."))), causeFirst = true).run()
        assertFalse("timed out", o.timedOut)
        assertTrue(o.finishedOk)
        val edits = o.calls.filter { it.first.name == "edit_file" }.map { it.second }
        assertEquals(2, edits.size)
        assertTrue(edits[0], edits[0].startsWith("Not changed yet. This is a problem report"))
        assertFalse(edits[1], edits[1].startsWith("Not changed yet"))
        assertTrue(File(o.root, "app.js").readText().contains("a + b"))
    }

    @Test fun aNamedCauseIsNotHeldBack() {
        val o = AgentHarness(app, report, ScriptedModel(listOf(
            tools(call("read_file", "path" to "app.js")),
            tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;"),
                text = "The bug is in app.js: add returns a - b."),
            tools(call("run_command", "command" to "node test.js")),
            say("Fixed app.js:2."))), causeFirst = true).run()
        assertFalse(o.calls.any { it.second.startsWith("Not changed yet") })
        assertEquals(4, o.requests)
    }

    @Test fun aChangedDataFileTheReportDoesNotNameIsQuestioned() {
        val files = app + ("data/limits.json" to "{\"max\": 10}\n")
        val o = AgentHarness(files, report, ScriptedModel(listOf(
            tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;"),
                text = "The cause is app.js:2: a - b."),
            tools(call("write_file", "path" to "data/limits.json", "content" to "{\"max\": 20}\n")),
            tools(call("run_command", "command" to "node test.js")),
            say("Fixed app.js and raised the limit."),
            tools(call("write_file", "path" to "data/limits.json", "content" to "{\"max\": 10}\n")),
            say("Put data/limits.json back; the fix is in app.js:2."))), causeFirst = true).run()
        assertFalse("timed out", o.timedOut)
        val asked = o.messages.joinToString { it["content"].toString() }
        assertTrue(asked, asked.contains("You changed data/limits.json, which the report does not mention"))
        assertEquals("{\"max\": 10}\n", File(o.root, "data/limits.json").readText())
    }

    // 1.5.156/157 (ScopeGate, Copilot R4): an edit beside the cause is questioned once, and put back when it was not needed
    @Test fun anEditBesideTheCauseIsQuestionedWhenScopeCheckIsOn() {
        val files = app + ("ui.js" to "function label(n) { return 'crew ' + n; }\nmodule.exports = { label };\n")
        val o = AgentHarness(files, report, ScriptedModel(listOf(
            tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;"),
                text = "The cause is app.js:2: add returns a - b."),
            tools(call("edit_file", "path" to "ui.js", "search" to "'crew ' + n", "replace" to "'crew ' + Math.max(0, n)")),
            tools(call("run_command", "command" to "node test.js")),
            say("Fixed app.js:2 and clamped the label in ui.js."),
            tools(call("edit_file", "path" to "ui.js", "search" to "'crew ' + Math.max(0, n)", "replace" to "'crew ' + n")),
            say("Put ui.js back; the fix is in app.js:2."))), scope = true).run()
        assertFalse("timed out", o.timedOut)
        val asked = o.messages.joinToString { it["content"].toString() }
        assertTrue(asked, asked.contains("You changed ui.js, which the request does not name and you have not named as the cause"))
        assertFalse(asked, asked.contains("You changed app.js"))
        assertTrue(File(o.root, "ui.js").readText().contains("'crew ' + n;"))
    }

    // 1.5.160 (Copilot ANALYSIS-1.5.159): after the scope question, the unfinished-plan nudge tells the model to leave the questioned
    // file as it is, so "continue" is not read as leave to put the change back in
    @Test fun laterNudgesLeaveTheQuestionedFilesAlone() {
        val files = app + ("ui.js" to "function label(n) { return 'crew ' + n; }\nmodule.exports = { label };\n")
        fun plan(vararg st: Pair<String, String>) = call("update_plan", "steps" to st.map { mapOf("text" to it.first, "status" to it.second) })
        val o = AgentHarness(files, report, ScriptedModel(listOf(
            tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;"),
                text = "The cause is app.js:2: add returns a - b."),
            tools(call("edit_file", "path" to "ui.js", "search" to "'crew ' + n", "replace" to "'crew ' + Math.max(0, n)")),
            tools(call("run_command", "command" to "node test.js")),
            say("Fixed app.js:2 and clamped the label in ui.js."),
            tools(call("edit_file", "path" to "ui.js", "search" to "'crew ' + Math.max(0, n)", "replace" to "'crew ' + n"),
                plan("Fix add in app.js" to "done", "Clamp the label" to "todo")),
            tools(call("run_command", "command" to "node test.js")),
            say("Put ui.js back; the fix is in app.js:2."),
            tools(plan("Fix add in app.js" to "done", "Clamp the label" to "failed")),
            say("Done: app.js:2 fixed, ui.js unchanged."))), scope = true).run()
        assertFalse("timed out", o.timedOut)
        val sent = o.messages.joinToString { it["content"].toString() }
        assertTrue(sent, sent.contains("You changed ui.js, which the request does not name"))
        assertTrue(sent, sent.contains("unfinished") && sent.contains("You were already asked about ui.js and answered. Leave them as they are now"))
        assertTrue(File(o.root, "ui.js").readText().contains("'crew ' + n;"))
    }

    /** Answers the fresh reviewer's call itself (with [reply]; null = the call fails; "" = it never answers) and passes the rest on. */
    private class Reviewer(private val inner: ScriptedModel, private val reply: String?) : dev.pocketide.app.ModelGateway {
        val asked = ArrayList<Triple<String, Boolean, String>>()
        override fun startNative(settings: dev.pocketide.app.AppSettings, system: String, messages: List<Map<String, Any?>>, tools: Boolean,
                                 listener: dev.pocketide.app.NativeListener, docs: Boolean, loaded: Set<String>?): dev.pocketide.app.ModelCall {
            if (!system.startsWith(dev.pocketide.app.core.FreshVerify.PROMPT)) return inner.startNative(settings, system, messages, tools, listener, docs, loaded)
            asked.add(Triple(system, tools, messages.joinToString("\n") { it["content"].toString() }))
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            when {
                reply == null -> main.post { listener.onError("HTTP 503", false, false) }
                reply.isEmpty() -> org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(31))
                else -> main.post { listener.onTurn(dev.pocketide.app.core.ToolTurn(reply, emptyList(), "stop")) }
            }
            return dev.pocketide.app.ModelCall()
        }
    }

    private fun freshRun(reply: String?, on: Boolean = true): Pair<AgentHarness.Outcome, Reviewer> {
        val r = Reviewer(ScriptedModel(listOf(
            tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;"), text = "The cause is app.js:2."),
            tools(call("run_command", "command" to "node test.js")),
            say("Fixed app.js:2 and added tests for it."),
            say("Corrected: I did not add tests; app.js:2 is the fix."))), reply)
        return AgentHarness(app, report, r, verifyFresh = on).run() to r
    }

    // 1.5.161 (Copilot HANDOFF-CALIBRATION S4): one call with no tools and no history; what it finds goes back once
    @Test fun aFreshReviewSeesOnlyTheRequestTheDiffAndTheMessage() {
        val (o, r) = freshRun("Missing: none\nNot needed: none\nNot shown: the message says tests were added; the diff adds none")
        assertFalse("timed out", o.timedOut)
        assertEquals(1, r.asked.size)
        val (system, tools, sent) = r.asked.single()
        assertEquals(dev.pocketide.app.core.FreshVerify.PROMPT, system)
        assertFalse("no tool list", tools)
        assertTrue(sent, sent.startsWith("Request:\n" + report))
        assertTrue(sent, sent.contains("--- a/app.js\n+++ b/app.js\n") && sent.contains("-  return a - b;\n+  return a + b;"))
        assertTrue(sent, sent.contains("Final message:\nFixed app.js:2 and added tests for it.") && sent.contains("$ node test.js (exit 0)"))
        assertFalse("no history", sent.contains("The cause is app.js:2"))
        val back = o.messages.joinToString { it["content"].toString() }
        assertTrue(back, back.contains("A reviewer who saw only your request, the diff and your final message found:\nNot shown: the message says tests were added"))
        assertTrue(o.result!!.text, words(o).startsWith("Corrected: I did not add tests; app.js:2 is the fix."))
    }

    @Test fun aCleanReviewFinishesAsBefore() {
        val (o, r) = freshRun("Missing: none\nNot needed: none\nNot shown: none")
        assertEquals(1, r.asked.size)
        assertFalse(o.messages.joinToString { it["content"].toString() }.contains("A reviewer who saw only"))
        assertTrue(o.result!!.text, words(o).startsWith("Fixed app.js:2 and added tests for it."))
    }

    @Test fun aFailedOrSlowReviewNeverBlocks() {
        for (reply in listOf(null, "")) {
            val (o, r) = freshRun(reply)
            assertFalse("timed out ($reply)", o.timedOut)
            assertEquals(1, r.asked.size)
            assertTrue(o.result!!.text, words(o).startsWith("Fixed app.js:2 and added tests for it."))
        }
    }

    @Test fun theFreshReviewIsOffByDefault() {
        val (o, r) = freshRun("Missing: everything", on = false)
        assertEquals(0, r.asked.size)
        assertTrue(o.result!!.text, words(o).startsWith("Fixed app.js:2 and added tests for it."))
    }

    // 1.5.164, the repair turn (research handoff item 1): only reading and editing, and an ending with no edit goes back once
    @Test fun aRepairTurnOnlyEditsAndMustEdit() {
        var offered: Set<String>? = null
        val o = AgentHarness(app, report, ScriptedModel(listOf(
            { m -> offered = dev.pocketide.app.core.Tools.only; tools(call("run_command", "command" to "node test.js"))(m) },
            say("I cannot run the tests, so I have not changed anything."),
            tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;")),
            say("Fixed app.js:2."))), repair = true).run()
        assertFalse("timed out", o.timedOut)
        assertEquals(dev.pocketide.app.core.RepairTurn.TOOLS, offered)
        assertTrue(o.calls.first().second.startsWith("Not available in this turn"))
        val sent = o.messages.joinToString { it["content"].toString() }
        assertTrue(sent, sent.contains(dev.pocketide.app.core.RepairTurn.MUST_EDIT))
        assertFalse("no test nudge in a repair turn", sent.contains("nothing was checked after the last change"))
        assertTrue(File(o.root, "app.js").readText().contains("a + b"))
        assertNull("restored after the run", dev.pocketide.app.core.Tools.only)
    }

    // 1.5.164 (2009scape field run): the plan check refused the same reworded plan four times; now it refuses once, then keeps the
    // left-out steps at the end and takes the new plan
    @Test fun aRewordedPlanIsRefusedOnceThenKept() {
        fun plan(vararg st: Pair<String, String>) = call("update_plan", "steps" to st.map { mapOf("text" to it.first, "status" to it.second) })
        val o = AgentHarness(app, report, ScriptedModel(listOf(
            tools(plan("Read app.js" to "doing", "Fix add" to "todo")),
            tools(plan("Read the app" to "done", "Fix add" to "doing")),
            tools(plan("Read the app" to "done", "Fix add" to "doing")),
            tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;")),
            tools(call("run_command", "command" to "node test.js")),
            say("Fixed app.js:2."))) ).run()
        assertFalse("timed out", o.timedOut)
        val replies = o.resultsOf("update_plan")
        assertEquals(1, replies.count { it.startsWith("Plan not changed") })
        assertTrue(replies.last(), replies.last().startsWith("Plan shown"))
    }

    // Copilot's PR #27, T1.3: the plan check ends whatever the model does next
    @Test fun thePlanCheckNeverLoops() {
        fun plan(vararg st: Pair<String, String>) = call("update_plan", "steps" to st.map { mapOf("text" to it.first, "status" to it.second) })
        fun run(vararg later: (List<Map<String, Any?>>) -> dev.pocketide.app.core.ToolTurn) = AgentHarness(app, report, ScriptedModel(
            listOf(tools(plan("Read app.js" to "doing", "Fix add" to "todo"))) + later.toList() + listOf(
                tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;")),
                tools(call("run_command", "command" to "node test.js")), say("Fixed app.js:2.")))).run().resultsOf("update_plan")
        // reworded, then the original texts again: one refusal, then taken
        val complies = run(tools(plan("Read it" to "done", "Fix add" to "doing")), tools(plan("Read app.js" to "done", "Fix add" to "doing")))
        assertEquals(1, complies.count { it.startsWith("Plan not changed") }); assertTrue(complies.last().startsWith("Plan shown"))
        // reworded three times: still one refusal
        val never = run(tools(plan("A" to "done")), tools(plan("B" to "done")), tools(plan("C" to "done")))
        assertEquals(1, never.count { it.startsWith("Plan not changed") }); assertTrue(never.drop(2).all { it.startsWith("Plan shown") })
    }

    // 1.5.164: a folder with its own .git (a downloaded repository) is left out of the project's syntax checks
    @Test fun aClonedRepositoryIsNotSyntaxChecked() {
        val files = listOf("build.sh", "server/Tools/diff.py", "server/Server/main.py", "tools/mine.py")
        val p = dev.pocketide.app.core.Verify.plan(files, { null }) { d -> d == "server" }!!
        assertTrue(p.command, p.command.contains("tools/mine.py") && p.command.contains("build.sh"))
        assertFalse(p.command, p.command.contains("server/"))
    }

    // 1.5.164 (PremiseCheck): report_finding is offered only with the setting, and its finding is the run's answer
    @Test fun aFindingEndsTheRunAsItsAnswer() {
        var offered = false
        val o = AgentHarness(app, "The 'Export CSV' button has disappeared, bring it back.", ScriptedModel(listOf(
            { m -> offered = dev.pocketide.app.core.Tools.reportEnabled; tools(call("search", "pattern" to "Export CSV"))(m) },
            tools(call("report_finding", "kind" to "not_found", "evidence" to "search for \"Export CSV\" found nothing; the project has no export code.")),
            say("Reported."))), premise = true).run()
        assertFalse("timed out", o.timedOut)
        assertTrue(offered)
        assertTrue(o.result!!.text, words(o).startsWith("I could not find this in the project.\n\nsearch for \"Export CSV\" found nothing"))
        val first = o.messages.first { it["role"] == "user" }["content"].toString()
        assertTrue(first, first.contains("\"Export CSV\": found in no file"))
        assertFalse(dev.pocketide.app.core.Tools.reportEnabled)
    }

    @Test fun scopeCheckIsOffByDefault() {
        val files = app + ("ui.js" to "function label(n) { return 'crew ' + n; }\nmodule.exports = { label };\n")
        val o = AgentHarness(files, report, ScriptedModel(listOf(
            tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;"),
                text = "The cause is app.js:2."),
            tools(call("edit_file", "path" to "ui.js", "search" to "'crew ' + n", "replace" to "'crew ' + Math.max(0, n)")),
            tools(call("run_command", "command" to "node test.js")),
            say("Fixed."))), scope = false).run()
        assertFalse(o.messages.joinToString { it["content"].toString() }.contains("which the request does not name"))
        assertFalse(dev.pocketide.app.core.AgentPrompt.core(report, true, "", false, false, false, false).contains("do not add it to make the request true"))
        assertTrue(dev.pocketide.app.core.AgentPrompt.core(report, true, "", false, false, false, false, scope = true).contains("do not add it to make the request true"))
    }

    @Test fun causeFirstIsOffByDefault() {
        val o = AgentHarness(app, report, ScriptedModel(listOf(
            tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;")),
            tools(call("run_command", "command" to "node test.js")),
            say("Fixed."))), causeFirst = false).run()
        assertFalse(o.calls.any { it.second.startsWith("Not changed yet") })
    }

    @Test fun aRequestWithOneAskIsNotAskedForTheList() {
        val o = run("Fix add in app.js so the test passes",
            tools(call("read_file", "path" to "app.js")),
            tools(call("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;")),
            tools(call("run_command", "command" to "node test.js")),
            say("Fixed add: it subtracted. node test.js prints ok."))
        assertEquals(4, o.requests)
    }
}
