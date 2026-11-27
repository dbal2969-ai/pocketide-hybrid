package dev.pocketide.app.bench

import dev.pocketide.app.HybridAgent
import dev.pocketide.app.HybridAi
import dev.pocketide.app.HybridCloud
import dev.pocketide.app.ModelGateway
import dev.pocketide.app.core.MiniJson
import dev.pocketide.app.core.SmallLocal
import dev.pocketide.app.core.ToolTurn
import dev.pocketide.app.core.Tuning
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

/** 1.5.208, from reliability run 1126-2203: an edit that changes nothing, and the same edit applied again, are refused. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditRepeatTest {
    private val app = mapOf("helpers.js" to "function helper4(x) {\n  return x + 4;\n}\nfunction helper40(x) {\n  return x - 40;\n}\nmodule.exports = { helper4, helper40 };\n")

    @Before fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        HybridAi.appContext = ctx
        HybridCloud.off = true
        ctx.getSharedPreferences("hybrid_ai", 0).edit().clear().putString("mode", HybridAi.Mode.ONLY_ON_DEVICE.name).commit()
        Tuning.reset()
    }

    @After fun tearDown() { HybridAgent.testPhone = null; HybridAgent.testCloud = null; Tuning.reset() }

    private fun edit(search: String, replace: String) =
        """{"reason":"edit","tool":"edit_file","tool_args":${MiniJson.write(mapOf("path" to "helpers.js", "search" to search, "replace" to replace))}}"""

    private fun run(vararg replies: String): AgentHarness.Outcome {
        val q = ArrayDeque(replies.toList())
        HybridAgent.testPhone = { _ -> q.removeFirstOrNull() ?: """{"reason":"done","tool":"answer","tool_args":{"text":"Done."}}""" }
        HybridAgent.testCloud = { _, _, _ -> ToolTurn("cloud", emptyList(), "stop") }
        return AgentHarness(app, "helpers.js is long. helper40 should return x plus 40. Fix only that.", ModelGateway.NETWORK, provider = "hybrid", modelName = "", timeoutSeconds = 60).run()
    }

    @Test fun anEditThatChangesNothingIsRefused() {
        val o = run(edit("return x - 40;", "return x - 40;"))
        val r = o.resultsOf("edit_file")
        assertTrue(r.toString(), r.first().startsWith("Edit not applied to helpers.js: the replacement is the same as the text it replaces"))
        assertEquals(app["helpers.js"], o.files["helpers.js"])
    }

    @Test fun theSameEditIsNotAppliedTwice() {
        // r23: the replacement begins with the search, so the search still matches and each repeat added another copy
        val e = edit("function helper4(x) {", "function helper4(x) {\n  // plus 40\n  return x + 40;\n}\nfunction helper4b(x) {")
        val o = run(e, e, e)
        val r = o.resultsOf("edit_file")
        assertTrue(r.toString(), r[0].startsWith("Edited helpers.js"))
        assertTrue(r.toString(), r.drop(1).all { it.startsWith("Edit not applied to helpers.js: this exact edit was already applied") })
        assertEquals(1, Regex("// plus 40").findAll(o.files["helpers.js"]!!).count())
    }

    @Test fun theJavaScriptNoteGoesOnlyToJavaScriptRequests() {
        assertTrue(SmallLocal.isJs("main.js needs greet.js. Create greet.js exporting greet(name)"))
        assertTrue(SmallLocal.isJs("node test.js should print ok"))
        assertFalse(SmallLocal.isJs("Fix this issue in rich/_inspect.py. Then run `pytest`"))
        assertTrue(SmallLocal.remoteLine(true).contains("module.exports = { greet }"))
        assertEquals("", SmallLocal.remoteLine(false))
    }
}
