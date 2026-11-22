package dev.pocketide.app

import dev.pocketide.app.core.TextTools
import dev.pocketide.app.core.Tools
import dev.pocketide.app.core.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextToolsTest {
    private val none: (String) -> Map<String, String>? = { null }

    @Test fun parsesOneCallAndKeepsTheRestOfTheReply() {
        val p = TextTools.parse("Let me read it.\n<function=read_file>\n<parameter=path>\nsrc/app.js\n</parameter>\n</function>", none)
        assertEquals(1, p.calls.size)
        assertEquals("read_file", p.calls[0].name)
        assertEquals("src/app.js", p.calls[0].str("path"))
        assertEquals("Let me read it.", p.text)
    }

    @Test fun parsesSeveralCallsInOrder() {
        val reply = "<function=read_file>\n<parameter=path>\na.txt\n</parameter>\n</function>\n" +
            "<function=search>\n<parameter=pattern>\nneedle\n</parameter>\n</function>"
        val calls = TextTools.parse(reply, none).calls
        assertEquals(listOf("read_file", "search"), calls.map { it.name })
        assertEquals("needle", calls[1].str("pattern"))
    }

    @Test fun keepsLinesInsideAValue() {
        val reply = "<function=write_file>\n<parameter=path>\nindex.html\n</parameter>\n<parameter=content>\n<p>Hi</p>\n  indented\nend\n</parameter>\n</function>"
        val c = TextTools.parse(reply, none).calls.single()
        assertEquals("<p>Hi</p>\n  indented\nend", c.str("content"))
        assertEquals("index.html", c.str("path"))
    }

    @Test fun coercesValuesByTheirSchemaType() {
        val types = mapOf("start" to "integer", "force" to "boolean", "ratio" to "number", "edits" to "array")
        val reply = "<function=t>\n<parameter=start>\n12\n</parameter>\n<parameter=force>\ntrue\n</parameter>\n" +
            "<parameter=ratio>\n0.5\n</parameter>\n<parameter=edits>\n[{\"path\":\"a.txt\"}]\n</parameter>\n</function>"
        val a = TextTools.parse(reply) { types }.calls.single().args()
        assertEquals(12, (a["start"] as Number).toInt())
        assertEquals(true, a["force"])
        assertEquals(0.5, (a["ratio"] as Number).toDouble(), 0.0001)
        assertTrue(a["edits"] is List<*>)
    }

    @Test fun aValueThatDoesNotFitItsTypeStaysText() {
        assertEquals("abc", TextTools.coerce("abc", "integer"))
        assertEquals("maybe", TextTools.coerce("maybe", "boolean"))
        assertEquals("{not json", TextTools.coerce("{not json", "object"))
    }

    @Test fun blocksInsideCodeFencesAreNotCalls() {
        val reply = "Here is the page:\n```html\n<function=read_file>\n<parameter=path>\nx\n</parameter>\n</function>\n```\nDone."
        val p = TextTools.parse(reply, none)
        assertTrue(p.calls.isEmpty())
        assertTrue(p.text.contains("<function=read_file>"))
    }

    @Test fun aBlockCutOffAtTheEndStillParses() {
        val c = TextTools.parse("<function=list_files>\n<parameter=dir>\nsrc\n</parameter>", none).calls
        assertEquals(1, c.size)
        assertEquals("src", c[0].str("dir"))
    }

    @Test fun unknownToolNamesStillBecomeCallsSoTheAgentCanRefuseThem() {
        val c = TextTools.parse("<function=made_up>\n<parameter=x>\n1\n</parameter>\n</function>", none).calls.single()
        assertEquals("made_up", c.name)
        assertEquals("1", c.str("x"))
    }

    @Test fun noMoreThanTheCallLimitIsRun() {
        val one = "<function=list_files>\n<parameter=dir>\nd\n</parameter>\n</function>\n"
        val p = TextTools.parse(one.repeat(TextTools.MAX_CALLS + 3), none)
        assertEquals(TextTools.MAX_CALLS, p.calls.size)
        assertFalse(p.text.contains("<function="))
    }

    @Test fun plainTextHasNoCalls() {
        val p = TextTools.parse("Just an answer, no tools needed.", none)
        assertTrue(p.calls.isEmpty())
        assertEquals("Just an answer, no tools needed.", p.text)
    }

    @Test fun resultsComeBackAsNamedText() {
        val text = TextTools.results(listOf(ToolCall("1", "read_file", "{}") to "hello", ToolCall("2", "search", "{}") to "no match"))
        assertEquals("EXECUTION RESULT of [read_file]:\nhello\n\nEXECUTION RESULT of [search]:\nno match", text)
    }

    @Test fun catalogueListsEveryToolWithItsParameters() {
        val cat = Tools.textCatalogue(false)
        assertTrue(cat.contains("- read_file: "))
        assertTrue(cat.contains("    - path (string, required)"))
        assertTrue(cat.contains("<function=read_file>"))
        assertTrue(cat.contains(TextTools.RESULT_HEAD))
    }

    @Test fun knownToolsReportTheirParameterTypes() {
        val types = Tools.paramTypes("read_file")
        assertNotNull(types)
        assertEquals("string", types!!["path"])
        assertNull(Tools.paramTypes("no_such_tool"))
    }

    @Test fun askIsTheMessageForABlockThatDidNotParse() {
        assertTrue(TextTools.ASK.contains("<function=name>"))
    }
}
