package dev.pocketide.app

import dev.pocketide.app.core.Requirements
import org.junit.Test

class RequirementsTest {
    @Test
    fun requirementsChecks() {
        RequirementsSelfTest.runAll()
    }
}

object RequirementsSelfTest {
    private var fails = 0
    private var passes = 0

    private fun ok(c: Boolean, m: String) {
        if (c) passes++ else fails++
        println((if (c) "PASS  " else "FAIL  ") + m)
    }

    fun runAll() {
        parsing(); messages(); gaps()
        println("\n$passes passed, $fails failed")
        check(fails == 0) { "$fails requirements checks failed" }
    }

    private fun parsing() {
        val p = Requirements.parse("REQUIREMENTS: Space shooter\n1. Ship moves with arrow keys\n2. Enemies fall from the top\n3. Score is shown\nAssumed: no sound")
        ok(p != null && p.title == "Space shooter" && p.steps.size == 3, "parses the list and name")
        ok(p?.steps?.get(1) == "Enemies fall from the top", "keeps each point's words")
        val md = Requirements.parse("**REQUIREMENTS:** Todo app\n- Add tasks\n- Delete tasks")
        ok(md != null && md.steps.size == 2, "accepts bullets and bold heading")
        ok(Requirements.parse("I will build a game.") == null, "prose without a list gives null")
        val many = Requirements.parse("REQUIREMENTS: x\n" + (1..20).joinToString("\n") { "$it. point $it" })
        ok(many != null && many.steps.size <= Requirements.MAX, "long lists are capped")
        ok(Requirements.fromEdited("t", "1. a\n- b\n\nc")?.steps == listOf("a", "b", "c"), "edited text becomes points")
        ok(Requirements.fromEdited("t", "  \n ") == null, "empty edit is rejected")
    }

    private fun messages() {
        val req = Requirements.request("make a game")
        ok(req.startsWith(Requirements.REQUEST_START) && req.contains("Request: make a game") && req.contains("REQUIREMENTS:"), "request asks for the list")
        val w = Requirements.work("make a game", listOf("a", "b"))
        ok(w.startsWith("make a game") && w.contains("1. a") && w.contains("2. b") && w.contains("nothing that is not on it"), "work message carries the list")
        val c = Requirements.check("make a game", listOf("a"))
        ok(c.contains("Original request: make a game") && c.contains("Left out:") && c.contains("Changed:") && c.contains("do not change any file"), "check message compares and forbids edits")
        ok(!Requirements.check("x", emptyList()).contains("Approved requirements"), "check without a list omits that part")
        ok(Requirements.wanted(true, false, false, true), "wanted for a big request")
        ok(!Requirements.wanted(false, false, false, true) && !Requirements.wanted(true, true, false, true) && !Requirements.wanted(true, false, true, true) && !Requirements.wanted(true, false, false, false), "not wanted when off, local, attached or small")
    }

    private fun gaps() {
        ok(!Requirements.hasGaps("Matches: all\nLeft out: nothing\nChanged: nothing"), "clean check has no gaps")
        ok(Requirements.hasGaps("Matches: most\nLeft out: sound effects\nChanged: nothing"), "left out is a gap")
        ok(Requirements.hasGaps("Left out: none\nChanged: uses squares instead of sprites"), "changed is a gap")
        ok(!Requirements.hasGaps("**Left out:** nothing.\n**Changed:** None."), "bold and punctuation are ignored")
    }
}
