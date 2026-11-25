package dev.pocketide.app.bench

import dev.pocketide.app.HybridAgent
import dev.pocketide.app.HybridAi
import dev.pocketide.app.HybridCloud
import dev.pocketide.app.ModelGateway
import dev.pocketide.app.core.MiniJson
import dev.pocketide.app.core.ToolCall
import dev.pocketide.app.core.ToolTurn
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Hybrid AI in the real agent loop (#BuildHybridAI), headless: NativeAgent and ModelClient's hybrid path run as on the phone,
 * with scripted stand-ins for Gemini Nano (small local model mode replies) and the cloud model (tool calls). They check that
 * Nano's actions become real tool calls, and that what Nano cannot do is passed on with a visible handoff line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HybridAgentLoopTest {
    private val app = mapOf(
        "app.js" to "function add(a, b) {\n  return a - b;\n}\nmodule.exports = { add };\n",
        "test.js" to "const { add } = require('./app.js');\nif (add(2, 3) !== 5) { console.error('add(2, 3) gave ' + add(2, 3)); process.exit(1); }\nconsole.log('ok');\n",
    )
    private var nanoPrompts = 0
    private var cloudCalls = 0

    @Before fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        HybridAi.appContext = ctx
        HybridCloud.off = true
        ctx.getSharedPreferences("hybrid_ai", 0).edit().clear().putString("mode", HybridAi.Mode.PREFER_ON_DEVICE.name).commit()
    }

    @After fun tearDown() { HybridAgent.testPhone = null; HybridAgent.testCloud = null }

    private fun nano(vararg replies: String) {
        val q = ArrayDeque(replies.toList())
        HybridAgent.testPhone = { nanoPrompts++; q.removeFirstOrNull() ?: """{"reason":"finished","tool":"answer","tool_args":{"text":"Fixed add; node test.js prints ok."}}""" }
    }

    private fun cloud(vararg turns: ToolTurn) {
        val q = ArrayDeque(turns.toList())
        HybridAgent.testCloud = { _, _, _ -> cloudCalls++; q.removeFirstOrNull() ?: ToolTurn("Fixed add: it subtracted. node test.js prints ok.", emptyList(), "stop") }
    }

    private fun action(tool: String, vararg args: Pair<String, Any?>) =
        """{"reason":"next step","tool":"$tool","tool_args":${MiniJson.write(mapOf(*args))}}"""

    private fun tools(name: String, vararg args: Pair<String, Any?>) =
        ToolTurn("", listOf(ToolCall("c${System.nanoTime()}", name, MiniJson.write(mapOf(*args)))), "tool_calls")

    private fun run(request: String) = AgentHarness(app, request, ModelGateway.NETWORK, provider = "hybrid", modelName = "", timeoutSeconds = 60).run()

    /** Everything the person sees from the model: its words between calls and the final reply. */
    private fun shown(o: AgentHarness.Outcome): String =
        o.messages.filter { it["role"] == "assistant" }.joinToString("\n") { it["content"]?.toString().orEmpty() } + "\n" + o.result?.text.orEmpty()

    @Test fun nanoDoesTheWholeFixOnThePhone() {
        nano(action("read_file", "path" to "app.js"),
            action("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;"),
            action("run_command", "command" to "node test.js"))
        cloud()
        val o = run("Fix add in app.js so the test passes")
        assertFalse("timed out", o.timedOut)
        assertTrue(o.file("app.js")!!.contains("a + b"))
        assertEquals(listOf("read_file", "edit_file", "run_command"), o.names.take(3))
        assertTrue(o.resultsOf("run_command").first().contains("ok"))
        assertEquals("the cloud was never used", 0, cloudCalls)
        assertTrue(shown(o), shown(o).contains("Ran on this phone (Gemini Nano)"))
    }

    @Test fun nanoProseCodeIsPassedToTheCloudWhichFinishes() {
        nano("Sure! Here is the fixed code:\n```js\nfunction add(a, b) { return a + b; }\n```")
        cloud(tools("read_file", "path" to "app.js"),
            tools("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;"),
            tools("run_command", "command" to "node test.js"))
        val o = run("Fix add in app.js so the test passes")
        assertFalse("timed out", o.timedOut)
        assertTrue(o.file("app.js")!!.contains("a + b"))
        assertEquals("after the handoff the rest of the request stays in the cloud", 1, nanoPrompts)
        val s = shown(o)
        assertTrue(s, s.contains("I'm passing this to the cloud model") && s.contains("did not reply with one JSON action"))
        assertTrue(s, s.contains("Ran in the cloud"))
    }

    @Test fun aWebRequestSkipsNanoAndSaysWhy() {
        nano()
        cloud(tools("write_file", "path" to "RECIPES.md", "content" to "# Meal-planning projects\n"))
        val o = run("Research meal planning with grocery lists from recipes on github and write RECIPES.md")
        assertFalse("timed out", o.timedOut)
        assertEquals("Nano was not asked", 0, nanoPrompts)
        assertTrue(o.file("RECIPES.md").orEmpty().contains("Meal-planning"))
        assertTrue(shown(o), shown(o).contains("this needs the web"))
    }

    @Test fun aNanoRefusalIsPassedOn() {
        nano("""{"reason":"x","tool":"answer","tool_args":{"text":"I cannot edit files in this project."}}""")
        cloud(tools("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;"),
            tools("run_command", "command" to "node test.js"))
        val o = run("Fix add in app.js so the test passes")
        assertFalse("timed out", o.timedOut)
        assertTrue(o.file("app.js")!!.contains("a + b"))
        assertTrue(shown(o), shown(o).contains("said it could not do it"))
    }

    @Test fun aClaimWithNoEditIsPassedOn() {
        nano("""{"reason":"x","tool":"answer","tool_args":{"text":"I have fixed add in app.js."}}""")
        cloud(tools("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;"),
            tools("run_command", "command" to "node test.js"))
        val o = run("Fix add in app.js so the test passes")
        assertFalse("timed out", o.timedOut)
        assertTrue("the edit Nano only claimed was really made by the cloud", o.file("app.js")!!.contains("a + b"))
        assertTrue(shown(o), shown(o).contains("no file was written"))
    }

    @Test fun cloudFirstModeNeverAsksNanoAndSaysNothingAboutAHandoff() {
        RuntimeEnvironment.getApplication().getSharedPreferences("hybrid_ai", 0).edit().putString("mode", HybridAi.Mode.PREFER_IN_CLOUD.name).commit()
        nano()
        cloud(tools("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;"))
        val o = run("Fix add in app.js so the test passes")
        assertFalse("timed out", o.timedOut)
        assertEquals(0, nanoPrompts)
        assertFalse(shown(o), shown(o).contains("I'm passing this to"))
    }

    @Test fun twoFailedEditsInARowHandTheRestToTheCloud() {
        nano(action("edit_file", "path" to "app.js", "search" to "return a + c;", "replace" to "return a + b;"),
            action("edit_file", "path" to "app.js", "search" to "return a * b;", "replace" to "return a + b;"),
            action("edit_file", "path" to "app.js", "search" to "nope", "replace" to "still wrong"))
        cloud(tools("edit_file", "path" to "app.js", "search" to "return a - b;", "replace" to "return a + b;"),
            tools("run_command", "command" to "node test.js"))
        val o = run("Fix add in app.js so the test passes")
        assertFalse("timed out", o.timedOut)
        assertEquals("Nano got two tries, then the cloud took over", 2, nanoPrompts)
        assertTrue(o.file("app.js")!!.contains("a + b"))
        assertTrue(shown(o), shown(o).contains("my last 2 tool calls failed"))
    }

    @Test fun anEditThatDeletesADefinitionStillInUseIsPutBack() {
        org.junit.Assume.assumeTrue("needs Node", NodeNames.available)
        val start = mapOf("app.js" to "let selected = [];\nfunction pick(id) { selected.push(id); return selected.length; }\nmodule.exports = { pick };\n",
            "test.js" to "const { pick } = require('./app.js');\nif (pick(1) !== 1) process.exit(1);\nconsole.log('ok');\n")
        nano(action("edit_file", "path" to "app.js", "search" to "let selected = [];", "replace" to "let days = Array(30).fill(null);"),
            action("run_command", "command" to "node test.js"))
        cloud()
        val o = AgentHarness(start, "Add a days array to app.js", ModelGateway.NETWORK, provider = "hybrid", modelName = "", timeoutSeconds = 60).run()
        assertFalse("timed out", o.timedOut)
        val edit = o.resultsOf("edit_file").first()
        assertTrue(edit, edit.startsWith("NOT SAVED: this change deleted the definition of `selected`"))
        assertTrue("the file was put back", o.file("app.js")!!.startsWith("let selected = [];"))
    }

    private val renameProject = mapOf(
        "total.js" to "function calcTotal(items) {\n  return items.reduce((s, it) => s + it.price * it.qty, 0);\n}\nmodule.exports = { calcTotal };\n",
        "test.js" to "const t = require('./total.js');\nif (!t.orderTotal || t.orderTotal([{ price: 2, qty: 3 }]) !== 6) { console.error('orderTotal is missing'); process.exit(1); }\nconsole.log('ok');\n")

    @Test fun nanoRenamesWithAllOnThePhone() {
        nano(action("edit_file", "path" to "total.js", "search" to "calcTotal", "replace" to "orderTotal", "all" to true),
            action("run_command", "command" to "node test.js"))
        cloud()
        val o = AgentHarness(renameProject, "Rename calcTotal to orderTotal everywhere and keep node test.js passing", ModelGateway.NETWORK,
            provider = "hybrid", modelName = "", timeoutSeconds = 60).run()
        assertFalse("timed out", o.timedOut)
        assertTrue(o.file("total.js")!!, !o.file("total.js")!!.contains("calcTotal"))
        assertTrue(o.resultsOf("run_command").first().contains("ok"))
        assertEquals(0, cloudCalls)
    }

    @Test fun repeatingAFailedStepHandsTheRestToTheCloud() {
        val same = action("edit_file", "path" to "total.js", "search" to "calcTotal", "replace" to "orderTotal")
        nano(same, action("read_file", "path" to "total.js"), same)
        cloud(tools("edit_file", "path" to "total.js", "search" to "calcTotal", "replace" to "orderTotal", "all" to true),
            tools("run_command", "command" to "node test.js"))
        val o = AgentHarness(renameProject, "Rename calcTotal to orderTotal everywhere and keep node test.js passing", ModelGateway.NETWORK,
            provider = "hybrid", modelName = "", timeoutSeconds = 60).run()
        assertFalse("timed out", o.timedOut)
        assertEquals("the repeated edit was not run; the cloud took over", 3, nanoPrompts)
        assertTrue(shown(o), shown(o).contains("repeated a step that already failed"))
        assertTrue(o.resultsOf("run_command").first().contains("ok"))
    }
}
