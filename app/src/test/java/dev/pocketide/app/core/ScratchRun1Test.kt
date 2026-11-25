package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.185: fixes from the from-scratch run 1125-1218 (quota and busy errors, a kit finished as it came, a Node CLI for an app). */
class ScratchRun1Test {
    @Test fun aTierWithNothingBelowFallsBackUp() {
        val T = CloudTier.Tier.values().associateBy { it.id }
        assertEquals(CloudTier.Tier.FAST, CloudTier.fallback(CloudTier.Tier.BALANCED, setOf(CloudTier.Tier.BALANCED), emptySet()))
        // Balanced is cooling down after its quota ran out, Fast is out too: Best answers instead of the request dying
        assertEquals(CloudTier.Tier.BEST, CloudTier.fallback(CloudTier.Tier.FAST, setOf(CloudTier.Tier.FAST), setOf(CloudTier.Tier.BALANCED)))
        assertNull(CloudTier.fallback(CloudTier.Tier.FAST, T.values.toSet(), emptySet()))
    }

    @Test fun anAppKitIsShapedNotKeptAsItCame() {
        assertTrue(Design.builtNote("crud", "app", "Wrote the crud app kit").contains("remove the samples"))
        assertTrue(Design.builtNote("crud", "app", "Wrote").contains(Design.KIT_MARK))
    }

    @Test fun anAppWithNoLanguageIsAWebPage() {
        assertTrue(Prompts.PHONE_NOTE.contains("is a web page"))
        assertTrue(SmallLocal.system("Tools:\n").contains("a short index.html that loads script.js"))
    }

    @Test fun run2_aCutOffWriteIsSeenAndWaitsFollowTheError() {
        val cut = "```json\n{\n  \"reason\": \"page\",\n  \"tool\": \"write_file\",\n  \"tool_args\": {\n    \"path\": \"index.html\",\n    \"content\": \"<!DOCTYPE html>\\n<html lang=\\\"en"
        assertTrue(SmallLocal.cutOff(cut))
        assertTrue(!SmallLocal.cutOff("""{"reason":"x","tool":"write_file","tool_args":{"path":"a.js","content":"let a = '{';"}}"""))
        assertTrue(!SmallLocal.cutOff("I cannot do that."))
        assertEquals(23_400L, CloudTier.retryAfterMs("Quota exceeded. Please retry in 23.4s."))
        assertEquals(30_000L, CloudTier.retryAfterMs("You exceeded your current quota, please check your plan and billing details."))
        assertEquals(60_000L, CloudTier.retryAfterMs("retryDelay\": \"300s"))
    }
}
