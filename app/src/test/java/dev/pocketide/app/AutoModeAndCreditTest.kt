package dev.pocketide.app

import dev.pocketide.app.core.AutoMode
import dev.pocketide.app.core.CreditSwitch
import dev.pocketide.app.core.McpPresets
import dev.pocketide.app.core.McpServer
import dev.pocketide.app.core.ProviderKind
import dev.pocketide.app.core.Providers
import dev.pocketide.app.core.Router
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoModeAndCreditTest {
    private fun mode(text: String, docs: Boolean = false) = AutoMode.choose(text, Router.route(text, docs, true))

    @Test fun questionsStayInReview() {
        assertFalse(mode("What does the loadUser function do?").agent)
        assertFalse(mode("explain how promises work").agent)
    }

    @Test fun buildsAndFixesApplyChanges() {
        assertTrue(mode("build a todo app with login and a sqlite database").agent)
        assertTrue(mode("the app crashes when I tap save\nTraceback (most recent call last):\n  File \"a.py\"").agent)
        assertTrue(mode("rename the Save button to Submit").agent)
    }

    @Test fun carryingOnWorkApplies() {
        assertTrue(mode("Finish creating the game").agent)
        assertTrue(mode("continue").agent)
        assertTrue(mode("Next make sure the goblin drops coins").agent)
        assertFalse(mode("now what does the loadUser function do?").agent)
    }

    @Test fun documentEditsStayInReview() {
        assertFalse(mode("add a heading to Report.docx", docs = true).agent)
    }

    @Test fun askingForNoChangeWins() {
        assertFalse(mode("build a todo app, but just explain the plan first").agent)
        assertFalse(mode("don't change anything, the header colour is wrong").agent)
    }

    @Test fun creditErrorsSwitchButRateLimitsWait() {
        assertTrue(CreditSwitch.outOfCredit("You have no credits remaining. Add credits to continue (HTTP 429)."))
        assertTrue(CreditSwitch.outOfCredit("A payment method is required. (HTTP 402)"))
        assertTrue(CreditSwitch.outOfCredit("You exceeded your current quota, please check your plan and billing details."))
        assertFalse(CreditSwitch.outOfCredit("Rate limit exceeded: limited to 15 requests per minute. Please retry shortly. (HTTP 429)"))
        assertFalse(CreditSwitch.outOfCredit("Could not reach the server"))
    }

    @Test fun nextProviderHasKeyAndIsNotTheFailedOne() {
        val all = Providers.all
        val next = CreditSwitch.next("openai", all, { it == "openai" || it == "groq" }, { false })
        assertNotNull(next)
        assertEquals("groq", next!!.id)
        assertNull(CreditSwitch.next("openai", all, { it == "openai" }, { false }))
        assertNull(CreditSwitch.next("openai", all, { it == "openai" || it == "groq" }, { it == "groq" }))
        assertTrue(all.none { it.kind == ProviderKind.DEMO && CreditSwitch.next("x", listOf(it), { true }, { false }) != null })
    }

    @Test fun presetsSkipServersAlreadyAdded() {
        val all = McpPresets.ALL
        assertEquals(all.size, McpPresets.missing(all, emptyList()).size)
        val left = McpPresets.missing(all, listOf(McpServer("Context7", "https://mcp.context7.com/mcp")))
        assertEquals(all.map { it.name } - "context7", left.map { it.name })
        assertTrue(all.all { it.url.startsWith("https://") })
    }
}
