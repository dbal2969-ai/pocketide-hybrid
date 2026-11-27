package dev.pocketide.app

import dev.pocketide.app.core.PlayTests
import dev.pocketide.app.core.Proof
import org.junit.Assert.*
import org.junit.Test

/** 1.5.172: checks written over several lines are recognised, and a play-test step that keeps failing the same way is pointed out. */
class Fixes172Test {
    private val exists = { p: String -> p in setOf("src/a.js", "test.js", "app.py", "data.json") }

    @Test fun aCheckWrittenOverSeveralLinesCounts() {
        val multi = "node -e \"\nconst a = require('./src/a.js');\nif (a.add(1, 2) !== 3) process.exit(1);\nconsole.log('ok')\n\""
        assertTrue(Proof.isCheckCommand(multi, exists))
        assertTrue(Proof.isCheckCommand("cd src && node -e '\nconst a = require(\"./a.js\")\nconsole.log(a)\n'", exists))
        assertTrue(Proof.isCheckCommand("python3 - <<'EOF'\nimport app\nprint(app.total([1, 2]))\nEOF", exists))
        assertTrue(Proof.isCheckCommand("node <<EOF\nconst a = require('./src/a.js')\nEOF\necho done", exists))
        assertTrue(Proof.isCheckCommand("python3 -c \"\nimport json\nprint(json.load(open('data.json')))\n\"", exists))
    }

    @Test fun codeThatLoadsNothingStillDoesNotCount() {
        assertFalse(Proof.isCheckCommand("node -e \"\nconsole.log('ok');\nconsole.log(1 + 1)\n\"", exists))
        assertFalse(Proof.isCheckCommand("python3 - <<EOF\nprint('ok')\nEOF", exists))
        // text in quotes is not a command: this only prints
        assertFalse(Proof.isCheckCommand("echo \"run it with: node test.js; npm test\"", exists))
        // writing a file through a here-document is not a check, whatever the file says
        assertFalse(Proof.isCheckCommand("cat > notes.txt <<EOF\nnpm test\nnode test.js\nEOF", exists))
    }

    @Test fun commandsAreStillCutOutsideQuotes() {
        assertEquals(listOf("cd src", "node ../test.js", "echo a;b", "ls"), Proof.segments("cd src && node ../test.js; echo 'a;b' | ls").map { it.replace("'", "") })
        assertTrue(Proof.isCheckCommand("ls\nnode test.js", exists))
        assertTrue(Proof.isCheckCommand("ls || npm test", exists))
        // a quote that never closes falls back to the plain cut
        assertTrue(Proof.isCheckCommand("echo it's here\nnode test.js", exists))
        assertTrue(Proof.isCheckCommand("node test.js <<< 'input'", exists))
    }

    private fun fail(step: String, value: String) = "VERDICT: FAIL\nChecks (0 of 1 passed):\n- FAIL $step -> $value\n"
    private val pass = "VERDICT: PASS\nChecks (1 of 1 passed):\n- PASS expect x -> true\n"

    @Test fun theSameFailureThreeTimesGetsOneNote() {
        val r = PlayTests.Repeats()
        val f = listOf("start" to fail("expect window.score === 5", "false"), "walk" to pass)
        assertEquals("", r.notes(f))
        assertEquals("", r.notes(f))                       // 2 failures: no note
        val third = r.notes(f)
        assertTrue(third, third.contains("\"start\": " + PlayTests.STEP_NOTE) && !third.contains("walk"))
        assertEquals("said once per test per request", "", r.notes(f))
    }

    @Test fun aPassOrADifferentFailureStartsTheCountAgain() {
        val r = PlayTests.Repeats()
        val a = listOf("start" to fail("expect a", "false")); val b = listOf("start" to fail("expect b", "false"))
        r.notes(a); r.notes(a)
        assertEquals("", r.notes(b)); assertEquals("", r.notes(b))
        assertEquals("", r.notes(listOf("start" to pass)))
        assertEquals("", r.notes(b)); assertEquals("", r.notes(b))
        assertTrue(r.notes(b).contains(PlayTests.STEP_NOTE))
        assertNull(PlayTests.failure(pass))
        assertEquals("VERDICT: FAIL", PlayTests.failure("VERDICT: FAIL\nSteps not played: 2"))
    }

    @Test fun chatSearchLeavesOutWhatTheAppAdded() {
        val saved = "a snake game in one file\n\nThe person approved this list of requirements. Build all of them."
        assertEquals("a snake game in one file", dev.pocketide.app.core.ChatHome.typed(saved))
        assertEquals("fix it\n\nplease", dev.pocketide.app.core.ChatHome.typed("fix it\n\nplease"))
        assertEquals("fix the hud", dev.pocketide.app.core.ChatHome.typed("fix the hud\n\nWork only inside the folder \"game\"."))
    }

    /** 1.5.210: a request to make something in the chat with no project is offered as a project; questions are not. */
    @org.junit.Test fun buildRequestsAreRecognised() {
        val c = dev.pocketide.app.core.ChatHome
        org.junit.Assert.assertTrue(c.isBuildRequest("Make a snake game in one HTML file with arrow-key and swipe controls, a score, and a restart button."))
        org.junit.Assert.assertTrue(c.isBuildRequest("can you build me a tip calculator app"))
        org.junit.Assert.assertTrue(c.isBuildRequest("Create a website for my bakery"))
        org.junit.Assert.assertFalse(c.isBuildRequest("Explain how HTTP caching works"))
        org.junit.Assert.assertFalse(c.isBuildRequest("Compare SQLite and IndexedDB for a small web app"))
        org.junit.Assert.assertFalse(c.isBuildRequest("Write a regex that matches email addresses"))
    }
}
