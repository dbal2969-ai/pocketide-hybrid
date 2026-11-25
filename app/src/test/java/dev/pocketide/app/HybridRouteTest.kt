package dev.pocketide.app

import dev.pocketide.app.core.HybridRoute
import org.junit.Test

class HybridRouteTest {
    @Test fun webRequestsGoToTheCloud() {
        check(HybridRoute.needsCloud("Research mealplanning with grocery lists generated from the recipes on github") != null)
        check(HybridRoute.needsCloud("look up the latest Phaser version") != null)
        check(HybridRoute.needsCloud("read https://example.com/docs") != null)
        check(HybridRoute.needsCloud("Add a score counter to game.js") == null)
        check(HybridRoute.needsCloud("make a simple snake game") == null)
    }

    @Test fun refusalsAreSpotted() {
        check(HybridRoute.refused("I cannot directly perform research on GitHub or generate meal planning applications. However, I can suggest a roadmap"))
        check(HybridRoute.refused("I'm unable to browse the internet."))
        check(HybridRoute.refused("As an AI, I do not have access to your files."))
        check(!HybridRoute.refused("Kotlin's ?: is the Elvis operator: it returns the left side unless it is null."))
        check(!HybridRoute.refused("I updated game.js. I cannot stress enough that you should test it."))
    }

    @Test fun nanoRefusalInTheAgentGoesToTheCloud() {
        val request = listOf(mapOf<String, Any?>("role" to "user", "content" to "Add a score counter"))
        val (t, why) = HybridAgent.phoneTurn("""{"reason":"x","tool":"answer","tool_args":{"text":"I cannot edit files in this project."}}""", request)
        check(t == null && why.contains("could not do it")) { why }
    }

    @Test fun handoffLineIsShownAndNotSentBack() {
        val h = HybridAgent.handoff("this needs the web, which Gemini Nano on the phone cannot reach", "the cloud model (gemini-3.1-flash-lite)")
        check(h.startsWith("⚡ Gemini Nano (on this phone): I'm passing this to the cloud model")) { h }
        val reply = h + "\n\nHere are three GitHub projects…\n\n☁ Ran in the cloud (gemini-3.1-flash-lite) · 2.0 s"
        check(HybridAgent.clean(reply) == "Here are three GitHub projects…") { "cleaned: " + HybridAgent.clean(reply) }
    }

    @Test fun strongModelFailureLineIsShownAndNotSentBack() {
        val f = HybridAgent.fallback("kilo/some-model could not answer: You need to sign in to use this model. (HTTP 401)", "gemini-3.1-flash-lite")
        check(f.contains("I'm passing this to the cloud model (gemini-3.1-flash-lite) instead") && f.contains("HTTP 401")) { f }
        check(HybridAgent.clean(f + "\n\nThe answer.") == "The answer.") { HybridAgent.clean(f + "\n\nThe answer.") }
    }

    @Test fun twoFailedToolCallsInARowAreAStreak() {
        fun tool(t: String) = mapOf<String, Any?>("role" to "tool", "tool_call_id" to "x", "content" to t)
        val start = listOf(mapOf<String, Any?>("role" to "user", "content" to "Fix it"))
        check(HybridAgent.failStreak(start + tool("Edited app.js.")) == 0)
        check(HybridAgent.failStreak(start + tool("Edited app.js.") + tool("NOT SAVED: app.js has a syntax error")) == 1)
        check(HybridAgent.failStreak(start + tool("NOT SAVED: syntax error") + tool("Edit not applied to app.js: the SEARCH text was not found")) == 2)
        check(HybridAgent.failStreak(start + tool("Not written: index.html has 35 lines.") + tool("Edited app.js.")) == 0) { "a success ends the streak" }
        val nudge = mapOf<String, Any?>("role" to "user", "content" to "From now on you have a few plain tools")
        check(HybridAgent.failStreak(start + tool("NOT SAVED: syntax error") + tool("Edit not applied") + nudge) == 2) { "the loop's own note does not end the streak" }
    }

    @Test fun aDeletedDefinitionThatIsStillUsedIsFound() {
        val before = "let selectedRecipeIds = [];\nfunction toggle(id) { selectedRecipeIds.push(id); }\n"
        val fresh = listOf(dev.pocketide.app.core.UndefinedNames.Use("selectedRecipeIds", 2))
        val gone = dev.pocketide.app.core.UndefinedNames.removedDefinitions(fresh, before)
        check(gone.map { it.name } == listOf("selectedRecipeIds")) { "$gone" }
        check(dev.pocketide.app.core.UndefinedNames.removedDefinitions(fresh, "function toggle(id) { selectedRecipeIds.push(id); }\n").isEmpty()) {
            "a name that was never defined is the old warning, not a deleted definition" }
        val msg = dev.pocketide.app.core.UndefinedNames.removedRefusal("app.js", gone)
        check(msg.startsWith("NOT SAVED") && msg.contains("`selectedRecipeIds` at line 2") && msg.contains("put back")) { msg }
    }

    @Test fun replaceAllRenamesEveryMatch() {
        val cur = "function calcTotal(items) {}\nmodule.exports = { calcTotal };\n"
        val one = dev.pocketide.app.core.EditBlocks.apply(cur, dev.pocketide.app.core.EditBlock("t.js", "calcTotal", "orderTotal"))
        check(one.error!!.contains("set all to true")) { one.error!! }
        val all = dev.pocketide.app.core.EditBlocks.applyAll(cur, dev.pocketide.app.core.EditBlock("t.js", "calcTotal", "orderTotal"))
        check(all.text == "function orderTotal(items) {}\nmodule.exports = { orderTotal };\n") { all.text.orEmpty() }
    }

    @Test fun aStepThatAlreadyFailedIsSpotted() {
        val call = mapOf("id" to "c1", "type" to "function", "function" to mapOf("name" to "edit_file", "arguments" to "{\"path\":\"t.js\",\"search\":\"a\",\"replace\":\"b\"}"))
        val msgs = listOf(mapOf<String, Any?>("role" to "user", "content" to "Rename"),
            mapOf<String, Any?>("role" to "assistant", "content" to "", "tool_calls" to listOf(call)),
            mapOf<String, Any?>("role" to "tool", "tool_call_id" to "c1", "content" to "Edit not applied to t.js: the SEARCH text matches more than once"))
        check(HybridAgent.repeatsFailure(dev.pocketide.app.core.ToolCall("x", "edit_file", "{\"search\":\"a\",\"path\":\"t.js\",\"replace\":\"b\"}"), msgs)) { "same call, other key order" }
        check(!HybridAgent.repeatsFailure(dev.pocketide.app.core.ToolCall("x", "edit_file", "{\"path\":\"t.js\",\"search\":\"a\",\"replace\":\"b\",\"all\":true}"), msgs)) { "a changed call is a new try" }
    }
}
