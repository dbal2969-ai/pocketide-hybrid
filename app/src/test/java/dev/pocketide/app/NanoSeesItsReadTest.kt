package dev.pocketide.app

import dev.pocketide.app.core.EditBlock
import dev.pocketide.app.core.EditBlocks
import dev.pocketide.app.core.StaleReads
import dev.pocketide.app.core.Verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.5.200, from Python run 1126-1710 (1 of 13): Gemini Nano read rich/highlighter.py, its edit was refused, and the read was then
 * blanked as "the file was changed afterwards" (StaleReads counted the refused edit) or cut to a seventh of the prompt, so it never saw
 * the file again and its rereads were refused as already shown.
 */
class NanoSeesItsReadTest {
    private val file = (1..240).joinToString("\n") { "    line_$it = 'value number $it of the highlighter module'" }

    private fun call(id: String, name: String, args: String) = mapOf<String, Any?>("role" to "assistant", "content" to "",
        "tool_calls" to listOf(mapOf("id" to id, "type" to "function", "function" to mapOf("name" to name, "arguments" to args))))
    private fun result(id: String, text: String) = mapOf<String, Any?>("role" to "tool", "tool_call_id" to id, "content" to text)

    private val refused = "Edit not applied to rich/highlighter.py: the SEARCH text was not found in the current file. Read the file again and copy the text exactly."
    private fun run() = mutableListOf(
        mapOf<String, Any?>("role" to "system", "content" to "system"),
        mapOf<String, Any?>("role" to "user", "content" to "My request: fix the URL regex in rich/highlighter.py"),
        call("r1", "read_file", "{\"path\":\"rich/highlighter.py\"}"), result("r1", file),
        call("e1", "edit_file", "{\"path\":\"rich/highlighter.py\",\"search\":\"r'(?P<url>https?://)\",\"replace\":\"x\"}"), result("e1", refused))

    @Test fun aRefusedEditLeavesTheReadInPlace() {
        val m = run()
        assertEquals(0, StaleReads.clear(m))
        assertEquals(file, m[3]["content"])
        // an edit that was applied still makes the old read stale
        m.add(call("e2", "edit_file", "{\"path\":\"rich/highlighter.py\",\"search\":\"line_1 \",\"replace\":\"line_one \"}")); m.add(result("e2", "Edited rich/highlighter.py."))
        assertEquals(1, StaleReads.clear(m))
        assertTrue(m[3]["content"].toString().startsWith("[Earlier contents"))
    }

    @Test fun nanoKeepsTheWholeReadWhileItsEditsAreRefused() {
        val m = run()
        assertEquals(3, HybridAgent.currentRead(m))
        val p = HybridAgent.phonePrompt(false, m, 22000)!!
        assertTrue("the whole file is in the prompt", p.contains("line_240 = "))
        assertTrue(p.length <= 22000)
        // after an edit that changed the file, the old read is no longer current
        m.add(call("e2", "edit_file", "{}")); m.add(result("e2", "Edited rich/highlighter.py."))
        assertEquals(-1, HybridAgent.currentRead(m))
    }

    @Test fun linesThatDifferOnlyInQuotesMatch() {
        val cur = "class A:\n    def f(self, obj, name):\n        qualname = name or getattr(obj, \"__qualname__\", name)\n        return qualname\n"
        val r = EditBlocks.apply(cur, EditBlock("a.py", "qualname = name or getattr(obj, '__qualname__', name)",
            "qualname = name or getattr(obj, '__qualname__', None) or str(obj)"))
        assertEquals("class A:\n    def f(self, obj, name):\n        qualname = name or getattr(obj, '__qualname__', None) or str(obj)\n        return qualname\n", r.text)
        // backticks still differ (a JavaScript template literal is not the same string)
        assertEquals(null, EditBlocks.apply("const a = `x`;\n", EditBlock("a.js", "const a = 'x';", "y")).text)
    }

    @Test fun theCheckLeavesOutTestsThatFailOnThisPhone() {
        val files = listOf("rich/console.py", "tests/test_console.py")
        try {
            Verify.deselect = listOf("tests/test_console.py::test_brokenpipeerror", "bad; rm -rf /")
            val c = Verify.plan(files, { "" })!!.command
            assertTrue(c, c.contains("pytest -q -x --deselect tests/test_console.py::test_brokenpipeerror 2>&1"))
            assertFalse(c, c.contains("rm -rf"))
        } finally { Verify.deselect = emptyList() }
        assertFalse(Verify.plan(files, { "" })!!.command.contains("--deselect"))
    }
}
