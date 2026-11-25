package dev.pocketide.app.core

import dev.pocketide.app.core.CloudTier.Choice
import dev.pocketide.app.core.CloudTier.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudTierTest {
    private fun pick(c: Choice, results: List<String> = emptyList(), said: List<String> = emptyList(), notes: List<String> = emptyList(), skip: Set<Tier> = emptySet()) =
        CloudTier.pick(c, said, results, notes, skip)

    @Test fun autoStartsOnBalanced() = assertEquals(CloudTier.Pick(Tier.BALANCED, null), pick(Choice.AUTO))

    @Test fun fixedChoicesStayPut() {
        assertEquals(Tier.FAST, pick(Choice.FAST, listOf("Edit not applied", "Edit not applied")).tier)
        assertEquals(CloudTier.Pick(Tier.BEST, null), pick(Choice.BEST))
    }

    @Test fun twoFailedCallsMoveAutoUpToBest() {
        val p = pick(Choice.AUTO, listOf("Edited app.js.", "Edit not applied to app.js: the SEARCH text was not found", "NOT SAVED: syntax error"))
        assertEquals(Tier.BEST, p.tier); assertEquals("the last 2 tool calls failed", p.why)
    }

    @Test fun aSecondFailedCheckMovesAutoUp() =
        assertEquals("the check failed 2 times", pick(Choice.AUTO, listOf("exit code 1\nadd gave -1", "Edited app.js.", "exit code 1\nstill wrong")).why)

    @Test fun oneFailedCheckIsNormal() = assertNull(pick(Choice.AUTO, listOf("exit code 1", "Edited app.js.", "exit code 0\nok")).why)

    @Test fun beingSentBackTwiceMovesAutoUp() =
        assertEquals(Tier.BEST, pick(Choice.AUTO, notes = listOf("Not finished: the plan has 2 unfinished steps", "The last check failed; fix it")).tier)

    @Test fun onceUpItStaysUpWithoutSayingItAgain() =
        assertEquals(CloudTier.Pick(Tier.BEST, null), pick(Choice.AUTO, said = listOf(CloudTier.escalation("gemini-4-hydrogen", "x"))))

    @Test fun aTierTheProjectCannotUseIsSkipped() {
        assertEquals(Tier.BALANCED, pick(Choice.BEST, skip = setOf(Tier.BEST)).tier)
        assertEquals(Tier.FAST, pick(Choice.BEST, skip = setOf(Tier.BEST, Tier.BALANCED)).tier)
        assertEquals("no move to a model that cannot answer", null, pick(Choice.AUTO, listOf("Error", "Error"), skip = setOf(Tier.BEST)).why)
    }

    @Test fun errorsAreReadRight() {
        assertTrue(CloudTier.unavailable("models/gemini-4-hydrogen is not found for API version v1beta"))
        assertTrue(CloudTier.unavailable("This model requires the pay-as-you-go Blaze pricing plan"))
        assertFalse(CloudTier.unavailable("Something unexpected happened. (IOException: Connection reset)"))
        assertTrue(CloudTier.thinkingRefused("INVALID_ARGUMENT: thinking_level is not supported for this model"))
        assertFalse(CloudTier.thinkingRefused("INVALID_ARGUMENT: bad schema"))
    }

    @Test fun theModelsAreTheFreeTierOnes() =
        assertEquals(listOf("gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-4-hydrogen"), Tier.values().map { it.model })

    /** Hard run 2, h07: the cloud reply stopped at MAX_TOKENS (1,024, the phone's setting) and the task ended. */
    @Test fun aCutOffReplyIsRecognised() {
        assertTrue(CloudTier.cutOff("Content generation stopped. Reason: MAX_TOKENS"))
        assertFalse(CloudTier.cutOff("Something unexpected happened."))
    }

    /** Hard run 3: a request error ("Role 'function' is not supported") was read as "this model is not available". */
    @Test fun aRequestErrorIsNotAMissingModel() {
        assertFalse(CloudTier.unavailable("Role 'function' is not supported. Please use a valid role: user, model."))
        assertFalse(CloudTier.unavailable("thinking_level is not supported for this request"))
        assertTrue(CloudTier.unavailable("Model gemini-4-hydrogen is not found"))
    }
}
