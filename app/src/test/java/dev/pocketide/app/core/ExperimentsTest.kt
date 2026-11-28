package dev.pocketide.app.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.220: the benchmark experiments and the OK/ERROR mark. */
class ExperimentsTest {
    @After fun off() { Experiments.ALL.forEach { Experiments.set(it, false) } }

    @Test fun experimentsAreOffUntilTicked() {
        assertEquals("none", Experiments.label())
        Experiments.set(Experiments.COVERAGE, true); Experiments.set(Experiments.OK_ERROR, true)
        assertTrue(Experiments.isOn(Experiments.COVERAGE)); assertFalse(Experiments.isOn(Experiments.STRUCTURED))
        assertEquals("coverage, OK/ERROR results", Experiments.label())
    }

    /** The results as they were worded in run 1127-2155. */
    @Test fun aResultStartsWithOkOrError() {
        assertEquals("OK. Edited price.js.", ResultMark.mark("Edited price.js."))
        assertEquals("OK. exit code 0\nok", ResultMark.mark("exit code 0\nok"))
        assertEquals("ERROR. exit code 1\nhelper40(2) gave -38", ResultMark.mark("exit code 1\nhelper40(2) gave -38"))
        assertTrue(ResultMark.mark("NOT SAVED: hello.js has a syntax error at line 5, column 1:").startsWith("ERROR. NOT SAVED"))
        assertTrue(ResultMark.failed("Edit not applied to a.js: the SEARCH text was not found"))
        assertTrue(ResultMark.failed("Not applied: start 3 and end 5 replace lines 3-5"))
        assertTrue(ResultMark.failed("Not inserted: controls.js already has this exact line at line 2"))
        assertTrue(ResultMark.failed("The user declined to run this command."))
        assertFalse(ResultMark.failed("Inserted 1 line into controls.js after line 1: it is now line 2. It is saved, so do not insert it again."))
        assertFalse(ResultMark.failed("1: function greet(name) {\n2:   return 'Error: ' + name;"))
        assertFalse(ResultMark.failed("Wrote index.html (40 lines)."))
        assertFalse(ResultMark.failed("Edited a.py.\nLanguage server errors in a.py (fix these):"))
    }

    /** The typed step is written back as the JSON the agent already reads; arguments a tool does not use are left out. */
    @Test fun aTypedStepBecomesTheAgentsJson() {
        val edit = dev.pocketide.app.NanoAction(reason = "fix the typo", tool = "edit_file", path = "greet.js", search = "Helo", replace = "Hello")
        assertEquals("""{"reason":"fix the typo","tool":"edit_file","tool_args":{"path":"greet.js","search":"Helo","replace":"Hello"}}""", edit.json())
        val cut = dev.pocketide.app.NanoAction(tool = "edit_file", path = "a.js", search = "debugger;", replace = "")
        assertTrue(cut.json(), cut.json().contains("\"replace\":\"\""))
        val lines = dev.pocketide.app.NanoAction(tool = "edit_lines", path = "a.js", start = 2, end = 1, text = "x();")
        assertEquals("""{"reason":"","tool":"edit_lines","tool_args":{"path":"a.js","text":"x();","start":2,"end":1}}""", lines.json())
        assertEquals("""{"reason":"","tool":"answer","tool_args":{"text":"Done."}}""", dev.pocketide.app.NanoAction(tool = "answer", text = "Done.").json())
    }
}
