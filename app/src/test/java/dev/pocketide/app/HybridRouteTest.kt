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
}
