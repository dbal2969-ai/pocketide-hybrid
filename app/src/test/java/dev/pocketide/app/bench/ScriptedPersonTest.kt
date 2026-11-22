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

/** The trust suite's scripted person (1.5.170): its message reaches the model before request 3, once, as the chat's steer box does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScriptedPersonTest {
    @Test fun theSteerIsReadBeforeTheThirdRequestAndOnlyOnce() {
        val seen = ArrayList<String>()
        fun lastUser(m: List<Map<String, Any?>>) = m.lastOrNull { it["role"] == "user" }?.get("content").toString()
        val steps = listOf<(List<Map<String, Any?>>) -> dev.pocketide.app.core.ToolTurn>(
            { m -> seen.add(lastUser(m)); tools(call("read_file", "path" to "a.js"))(m) },
            { m -> seen.add(lastUser(m)); tools(call("read_file", "path" to "a.js"))(m) },
            { m -> seen.add(lastUser(m)); tools(call("write_file", "path" to "a.js", "content" to "module.exports = 2;\n"))(m) },
            { m -> seen.add(lastUser(m)); say("Set it to 2 as you asked.")(m) },
        )
        val o = AgentHarness(mapOf("a.js" to "module.exports = 1;\n"), "Set a.js to export 3.", ScriptedModel(steps), steer = 3 to "No, make it 2.").run()
        assertFalse(o.timedOut)
        assertEquals(4, seen.size)
        assertFalse(seen[1].contains("make it 2"))
        assertTrue(seen[2], seen[2].contains("No, make it 2."))
        assertEquals(1, o.messages.count { it["role"] == "user" && it["content"].toString().contains("No, make it 2.") })
        assertTrue(o.file("a.js")!!.contains("2"))
    }
}
