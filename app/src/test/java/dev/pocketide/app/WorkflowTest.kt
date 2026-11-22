package dev.pocketide.app

import dev.pocketide.app.core.Workflow
import dev.pocketide.app.core.Workflow.Slot
import org.junit.Test

class WorkflowTest {
    @Test fun workflow() {
        val f = ArrayList<String>()
        fun check(ok: Boolean, m: String) { if (!ok) f.add(m) }
        check(Workflow.parse("cascade") == "cascade" && Workflow.parse("x") == "single" && Workflow.parse("") == "single", "parse")
        check(Workflow.decode(Slot("groq", "m").encode()) == Slot("groq", "m") && Workflow.decode("junk") == null && Workflow.decode("\tm") == null, "slot roundtrip")
        check(Workflow.defaultDraftProvider(listOf("anthropic", "groq", "cerebras"), "anthropic") == "cerebras", "cheapest first")
        check(Workflow.defaultDraftProvider(listOf("anthropic"), "anthropic") == null, "no second provider")
        check(Workflow.defaultDraftProvider(listOf("anthropic", "custom"), "anthropic") == "custom", "any other")
        check(Workflow.distinct(Slot("a", "x"), Slot("a", "y")) && !Workflow.distinct(Slot("a", "x"), Slot("a", "x")) && !Workflow.distinct(null, Slot("a", "x")), "distinct")
        check(Workflow.describe("single", null, null) == "Single model" && Workflow.describe("cascade", Slot("groq", "m"), null).contains("groq/m"), "describe")
        val reqs = listOf("a map", "a bank")
        val esc = Workflow.escalatePrompt("Build a game", reqs, "game", "Left out: the bank", listOf("index.html loads game.js but that file does not exist."))
        check(esc.contains("game.js") && esc.contains("Left out: the bank") && esc.contains("1. a map") && esc.contains("folder \"game\""), "escalate")
        check(Workflow.critiquePrompt("t", reqs, "", emptyList()).contains("Do not change any file") && Workflow.critiquePrompt("t", reqs, "", emptyList()).contains("No defects"), "critique")
        check(Workflow.noDefects("No defects.") && Workflow.noDefects("  ") && !Workflow.noDefects("1. game.js is missing"), "noDefects")
        check(Workflow.revisePrompt("t", "1. fix x", "").contains("Revise once"), "revise")
        check(Workflow.shouldEscalate("Matches: all\nLeft out: nothing\nChanged: nothing", emptyList()) == false, "clean review")
        check(Workflow.shouldEscalate("Matches: a\nLeft out: the bank\nChanged: nothing", emptyList()), "gap escalates")
        check(Workflow.shouldEscalate("Left out: nothing\nChanged: nothing", listOf("x")), "found escalates")
        if (f.isNotEmpty()) throw AssertionError(f.joinToString("; "))
    }
}
