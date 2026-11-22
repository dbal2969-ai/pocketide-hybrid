package dev.pocketide.app

import dev.pocketide.app.core.Parts
import dev.pocketide.app.core.Requirements
import org.junit.Test

class PartsTest {
    @Test
    fun partsChecks() {
        PartsSelfTest.runAll()
    }
}

object PartsSelfTest {
    private var fails = 0
    private var passes = 0

    private fun ok(c: Boolean, m: String) {
        if (c) passes++ else fails++
        println((if (c) "PASS  " else "FAIL  ") + m)
    }

    fun runAll() {
        splitting(); parsing(); prompts(); TestFirstSelfTest.runAll()
        println("\n$passes passed, $fails failed")
        check(fails == 0) { "$fails parts checks failed" }
    }

    private fun splitting() {
        ok(Parts.shouldSplit(6, true) && Parts.shouldSplit(12, true), "long lists are split")
        ok(!Parts.shouldSplit(5, true) && !Parts.shouldSplit(10, false), "short lists and the off switch are not")
    }

    private fun parsing() {
        val reply = "PARTS: Space shooter\n1. Game rules and data: 1, 2, 3\n2. Screen and controls: 4, 5\n3. Polish: 6\n"
        val p = Parts.parse(reply, 6)
        ok(p != null && p.size == 3 && p[0].name == "Game rules and data" && p[0].numbers == listOf(1, 2, 3), "reads parts and their numbers")
        val dup = Parts.parse("1. A: 1, 2\n2. B: 2, 3, 4", 4)
        ok(dup != null && dup[1].numbers == listOf(3, 4), "a requirement is only in one part")
        val missing = Parts.parse("1. A: 1\n2. B: 2", 4)
        ok(missing != null && missing.last().numbers == listOf(2, 3, 4), "forgotten requirements join the last part")
        val bad = Parts.parse("1. A: 1, 99\n2. B: 0, 100", 4)
        ok(bad == null, "numbers outside the list do not count, leaving fewer than two parts")
        ok(Parts.parse("I will build it all at once.", 6) == null && Parts.parse("1. Only one: 1, 2, 3", 3) == null, "prose or a single part gives null")
        val long = Parts.parse((1..6).joinToString("\n") { "$it. Part $it: $it" }, 6)
        ok(long != null && long.size == Parts.MAX_PARTS && long.last().numbers.containsAll(listOf(4, 5, 6)), "no more than four parts, the rest are merged into the last")
        val fb = Parts.fallback(9)
        ok(fb.size in 2..Parts.MAX_PARTS && fb.flatMap { it.numbers }.sorted() == (1..9).toList(), "the fallback covers every requirement once")
        ok(Parts.fallback(6).size >= 2, "the fallback always makes at least two parts")
    }

    private fun prompts() {
        val reqs = listOf("a", "b", "c", "d", "e", "f")
        ok(Parts.request("make a game", reqs).contains("PARTS:") && Parts.request("make a game", reqs).contains("6. f"), "the grouping request lists the requirements")
        val plan = Parts.plan(listOf(Parts.Part("Rules", listOf(1, 2)), Parts.Part("Screen", listOf(3))), "Game")
        ok(plan.steps[0] == "Rules (requirements 1, 2)" && plan.title == "Game", "the plan card shows each part with its numbers")
        val s = Parts.stepPrompt("make a game", reqs, plan, 1, "src")
        ok(s.contains("build part 2 of 2 only: Screen") && s.contains("Earlier parts are already in the project") && s.contains("inside the folder \"src\"") && s.contains("Part done:"), "the part prompt is focused and keeps the folder rule")
        ok(Requirements.check("t", reqs, "", true).contains("Doesn't fit:") && !Requirements.check("t", reqs, "", false).contains("Doesn't fit"), "the check asks about fit only after a split job")
        ok(Requirements.hasGaps("Left out: nothing\nChanged: nothing\nDoesn't fit: part 2 calls draw() but part 1 named it render()"), "a fit problem counts as a gap")
    }
}

object TestFirstSelfTest {
    fun runAll() {
        var f = 0
        fun ok(c: Boolean, m: String) { println((if (c) "PASS  " else "FAIL  ") + m); if (!c) f++ }
        val withTests = dev.pocketide.app.core.Verify.Plan("pytest", "Python tests")
        val syntaxOnly = dev.pocketide.app.core.Verify.Plan("node --check", "Syntax check")
        ok(dev.pocketide.app.core.TestFirst.wanted("the login button is broken, fix it", withTests), "a bug fix with tests gets test first")
        ok(!dev.pocketide.app.core.TestFirst.wanted("the login button is broken, fix it", syntaxOnly), "syntax checks alone are not tests")
        ok(!dev.pocketide.app.core.TestFirst.wanted("the login button is broken, fix it", null), "no check command, no test first")
        ok(!dev.pocketide.app.core.TestFirst.wanted("build me a snake game from scratch", withTests), "new work is not a bug fix")
        ok(!dev.pocketide.app.core.TestFirst.wanted("add a dark theme", withTests), "a feature request is not a bug fix")
        ok(dev.pocketide.app.core.TestFirst.NOTE.contains("fails because of the bug"), "the note asks for a failing test")
        check(f == 0) { "$f test-first checks failed" }
    }
}
