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
        assertTrue(SmallLocal.system("Tools:\n").contains("write_file index.html"))
    }
}
