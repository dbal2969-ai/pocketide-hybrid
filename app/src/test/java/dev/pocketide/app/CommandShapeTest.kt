package dev.pocketide.app

import dev.pocketide.app.core.AgentActions
import dev.pocketide.app.core.CommandShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandShapeTest {
    private fun pad(s: String) = s + "\n" + "x".repeat(CommandShape.MIN_CHARS)

    @Test fun aFailingJestRunKeepsFailuresAndDropsPasses() {
        val passes = (1..40).joinToString("\n") { "    ✓ renders item $it (3 ms)" }
        val out = "PASS src/a.test.js\n$passes\nFAIL src/b.test.js\n  ● cart › adds an item\n    Expected: 2\n    Received: 1\n" +
            (1..12).joinToString("\n") { "      at Object.<anonymous> (src/b.test.js:$it:5)" } + "\nTests: 1 failed, 40 passed, 41 total"
        val r = CommandShape.shape("cd app && npm test", 1, out)
        assertTrue(r, r.contains("Expected: 2") && r.contains("Received: 1") && r.contains("Tests: 1 failed"))
        assertFalse(r, r.contains("renders item 7"))
        assertTrue(r, r.contains("41 passing test lines left out") && r.contains("… 9 more stack lines"))
        assertEquals(3, Regex("at Object").findAll(r).count())
    }

    @Test fun aPassingRunKeepsItsSummary() {
        val out = (1..60).joinToString("\n") { "tests/test_m.py::test_case_$it PASSED" } + "\n============ 60 passed in 1.2s ============"
        val r = CommandShape.shape("python3 -m pytest -v", 0, out)
        assertTrue(r, r.contains("60 passed in 1.2s") && r.startsWith("[all tests passed"))
        assertTrue(r, r.lines().size <= 9)
    }

    @Test fun goTestDropsRunAndPassLines() {
        val out = (1..30).joinToString("\n") { "=== RUN   TestThing$it\n--- PASS: TestThing$it (0.00s)" } + "\n--- FAIL: TestBroken (0.00s)\n    x_test.go:12: got 1, want 2\nFAIL\nFAIL\texample.com/x\t0.01s"
        val r = CommandShape.shape("go test ./...", 1, out)
        assertTrue(r, r.contains("--- FAIL: TestBroken") && r.contains("got 1, want 2") && !r.contains("TestThing5"))
    }

    @Test fun gitStatusDropsHints() {
        val out = pad("On branch main\nChanges not staged for commit:\n  (use \"git add <file>...\" to update what will be committed)\n  (use \"git restore <file>...\" to discard changes in working directory)\n\tmodified:   game/main.js")
        val r = CommandShape.shape("git status", 0, out)
        assertTrue(r, r.contains("modified:   game/main.js") && !r.contains("git restore") && r.contains("2 hint lines left out"))
        assertEquals(out, CommandShape.shape("git status --short", 0, out))
    }

    @Test fun gitLogBecomesOneLinePerCommit() {
        val out = (1..20).joinToString("\n") { "commit ${"a".repeat(33)}${it.toString().padStart(7, '0')}\nAuthor: Dee <d@x.org>\nDate:   Wed Oct 28 10:0$it 2026\n\n    Fix number $it\n" }
        val r = CommandShape.shape("git log", 0, out)
        assertEquals(20, r.lines().size)
        assertTrue(r, r.lines().first().startsWith("aaaaaaa ") && r.lines().first().endsWith("Dee: Fix number 1"))
    }

    @Test fun npmInstallKeepsTheResult() {
        val out = (1..30).joinToString("\n") { "npm WARN deprecated pkg$it@1.0.0: no longer supported" } + "\n\nadded 312 packages, and audited 313 packages in 9s\nfound 0 vulnerabilities"
        val r = CommandShape.shape("npm install", 0, out)
        assertTrue(r, r.contains("added 312 packages") && r.contains("found 0 vulnerabilities") && r.contains("30 download and deprecation lines left out"))
    }

    @Test fun unknownOrShortOutputIsUnchanged() {
        val long = "line\n".repeat(400)
        assertEquals(long, CommandShape.shape("ls -la", 0, long))
        assertEquals("✓ one", CommandShape.shape("npm test", 0, "✓ one"))
        assertTrue(AgentActions.runResult("npm test", 0, "✓ ok").startsWith("exit code 0"))
    }

    @Test fun aModuleNameRunAsACommandGetsANote() {
        // Llama 3.3 70B ran `node:test`, saw exit 127 and said Node was not installed
        val r = AgentActions.runResult("node:test", 127, "/bin/sh: 1: node:test: not found")
        assertTrue(r, r.contains("node:test is not a command") && r.contains("this does not mean node is missing") && r.contains("`node --test`"))
        assertTrue(AgentActions.runResult("cd app && python3:run x", 127, "").contains("python3:run is not a command"))
        assertFalse(AgentActions.runResult("nodee test.js", 127, "nodee: not found").contains("Note:"))
        assertFalse(AgentActions.runResult("node:test", 1, "").contains("Note:"))
    }

    @Test fun aTestFileUsingTestAsAGlobalGetsANote() {
        // Llama 3.3 70B wrote test('split', …) without importing node:test and then replaced its tests with console.log
        val r = AgentActions.runResult("node --test test/money.test.js", 1, "test('split', () => {\n^\n\nReferenceError: test is not defined\n    at Object.<anonymous>")
        assertTrue(r, r.contains("test is not a global here") && r.contains("require('node:test')"))
        assertTrue(AgentActions.runResult("node t.js", 1, "ReferenceError: expect is not defined").contains("`expect` is Jest's"))
        assertFalse(AgentActions.runResult("node t.js", 1, "ReferenceError: total is not defined").contains("Note:"))
        assertFalse(AgentActions.runResult("node t.js", 0, "ReferenceError: test is not defined").contains("Note:"))
    }
}
