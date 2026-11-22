package dev.pocketide.app

import dev.pocketide.app.core.Escalate
import dev.pocketide.app.core.Workflow.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.125: a stalled request carries on with the strong model the person picked, never with the same one or one without a key. */
class EscalateTest {
    private val kimi = Slot("cloudflare", "@cf/moonshotai/kimi-k2.6")
    private val opus = Slot("anthropic", "claude-opus-5-5")

    @Test fun onlyAPickedDifferentModelWithAKeyIsOffered() {
        assertEquals(opus, Escalate.target(kimi, opus) { true })
        assertNull("none picked", Escalate.target(kimi, null) { true })
        assertNull("the same model", Escalate.target(kimi, kimi.copy()) { true })
        assertNull("no key", Escalate.target(kimi, opus) { it != "anthropic" })
        assertEquals("same provider, other model", Slot("cloudflare", "big"), Escalate.target(kimi, Slot("cloudflare", "big")) { true })
    }

    @Test fun theStrongModelGetsTheRequestWhyAndTheNotes() {
        val p = Escalate.prompt("Add recipe import", "it was stopped before it finished (the run was going in circles)", "kimi-k2.6",
            "Stopped before the request was finished.\nFiles changed: js/recipes.js.")
        assertTrue(p.startsWith("Add recipe import\n\nAnother model (kimi-k2.6)"))
        assertTrue(p.contains("going in circles)."))
        assertTrue(p.contains("Files changed: js/recipes.js."))
        assertTrue(p.contains("The files are as it left them"))
        assertFalse(Escalate.note("x", "m", "").contains("Its notes"))
        assertEquals("Carry on with Opus", Escalate.button("Opus"))
    }

    /** 1.5.126 (review finding 13): a carried-on run that never reached the tool-calling end must not block the next request's offer. */
    @Test fun oncePerRequestAndANewRequestClearsIt() {
        val once = Escalate.Once()
        assertFalse("an ordinary run is not the carried-on one", once.ended())
        once.start()
        assertTrue("the carried-on run ends: no second escalation", once.ended())
        assertFalse("the next run may be offered again", once.ended())
        once.start()
        once.newRequest()   // the carried-on run ended some other way, then the person sent a new request
        assertFalse(once.carrying)
        assertFalse("the new request's stall is offered", once.ended())
    }
}
