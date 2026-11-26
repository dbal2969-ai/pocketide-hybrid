package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 1.5.188: Gemini Nano's actions in broken JSON or YAML are read (reliability run 1125-2053, 16 replies thrown away). */
class LenientJsonTest {
    private val replies = File("src/test/resources/nano/unusable-1125-2053.jsonl").readLines().filter { it.isNotBlank() }
        .map { MiniJson.obj(MiniJson.parse(it))!!.let { o -> o["task"] as String to o["reply"] as String } }

    private fun call(reply: String) = SmallLocal.parse(reply) { Tools.paramTypes(it) }

    @Test fun theRunsRejectedRepliesAreRead() {
        val read = replies.filter { SmallLocal.isAction(it.second) }
        for ((task, r) in replies) println("$task: " + (if (SmallLocal.isAction(r)) call(r).calls.firstOrNull()?.let { it.name + " " + it.arguments.take(80) } ?: "answer" else "NOT READ") )
        assertTrue("read ${read.size} of ${replies.size}", read.size == replies.size)
    }

    @Test fun unescapedQuotesInTheReason() {
        val r = """{"reason": "The test expects repr(Foo("hello", bar=3)) to match.", "tool": "read_file", "tool_args": {"path": "rich/repr.py"}}"""
        val p = call(r)
        assertEquals("read_file", p.calls.single().name)
        assertEquals("The test expects repr(Foo(\"hello\", bar=3)) to match.", p.text)
    }

    @Test fun rawStringsBadEscapesAndLineBreaks() {
        val r = "{\"reason\": \"widen the regex\", \"tool\": \"edit_file\", \"tool_args\": {\"path\": \"h.py\", \"search\": \"a\", " +
            "\"replace\": r\"r'(?P<url>\\w+~)'\",\n \"all\": true}}"
        val a = MiniJson.obj(MiniJson.parse(call(r).calls.single().arguments))!!
        assertEquals("r'(?P<url>\\w+~)'", a["replace"])
        val nl = "{\"reason\": \"x\", \"tool\": \"answer\", \"tool_args\": {\"text\": \"line one\nline two\"}}"
        assertEquals("line one\nline two", call(nl).text)
    }

    @Test fun yamlActions() {
        val y = "```yaml\nreason: I need to read the prompt module first.\ntool: read_file\ntool_args:\n  path: rich/prompt.py\n```"
        val c = call(y).calls.single()
        assertEquals("read_file", c.name)
        assertEquals("rich/prompt.py", MiniJson.obj(MiniJson.parse(c.arguments))!!["path"])
        val block = "reason: fix it\ntool: write_file\ntool_args:\n  path: a.py\n  content: |\n    def f():\n        return 1\n"
        assertEquals("def f():\n    return 1\n", MiniJson.obj(MiniJson.parse(call(block).calls.single().arguments))!!["content"])
        assertNull(LenientJson.yaml("Sure, here is the fix: change line 3."))
    }

    @Test fun validJsonIsReadExactlyAsBefore() {
        val r = """{"reason": "ok", "tool": "run_command", "tool_args": {"command": "node test.js"}}"""
        assertEquals("run_command", call(r).calls.single().name)
        assertFalse(SmallLocal.isAction("I could not find the file."))
    }
}
