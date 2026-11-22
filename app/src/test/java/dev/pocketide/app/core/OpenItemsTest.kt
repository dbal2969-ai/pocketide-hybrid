package dev.pocketide.app.core

import dev.pocketide.app.core.CommandRules.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.83: the open items from the 1.5.81 emulator benchmarks. */
class OpenItemsTest {
    @Test fun copiesOfOneModelAreNamedAsCopies() {
        assertEquals("at the same time with 3 copies of @cf/moonshotai/kimi-k2.6 on Cloudflare",
            Parts.lanesLabel(List(3) { "Cloudflare" to "@cf/moonshotai/kimi-k2.6" }))
        val mixed = Parts.lanesLabel(listOf("Cloudflare" to "kimi", "OpenRouter" to "qwen"))
        assertTrue(mixed, mixed.startsWith("at the same time on 2 models (kimi on Cloudflare, qwen on OpenRouter)"))
    }

    @Test fun planningHasADeadline() {
        assertTrue(Parts.PLAN_DEADLINE_SECONDS in 60..300)
    }

    @Test fun readOnlyCommandsRunWithoutACard() {
        val none = CommandRules.EMPTY
        assertEquals(Decision.RUN, CommandRules.decide("cat tests/play.txt", none, autorun = false))
        assertEquals(Decision.RUN, CommandRules.decide("ls -la game", none, autorun = false))
        assertEquals(Decision.ASK, CommandRules.decide("cat .env", none, autorun = false))
        assertEquals(Decision.ASK, CommandRules.decide("cat local.properties", none, autorun = false))
        assertEquals(Decision.ASK, CommandRules.decide("node server.js", none, autorun = false))
        assertEquals(Decision.DENY, CommandRules.decide("cat app.js", CommandRules.Rules(emptyList(), listOf("cat")), autorun = false))
        assertEquals(Decision.RUN, CommandRules.decide("grep -n reload tests/play.txt | wc -l", none, autorun = false))
        assertEquals(Decision.RUN, CommandRules.decide("git status && git diff app.js", none, autorun = false))
        assertEquals(Decision.ASK, CommandRules.decide("cat a.js > b.js", none, autorun = false))
        assertEquals(Decision.ASK, CommandRules.decide("find . -name '*.tmp' -delete", none, autorun = false))
        assertEquals(Decision.ASK, CommandRules.decide("git commit -am x", none, autorun = false))
        assertEquals(Decision.ASK, CommandRules.decide("npm tester", none, autorun = false))
        assertFalse(CommandRules.readsOnly("echo $(rm -rf x)"))
    }

    @Test fun checkingAfterAPassIsStoppedOnlyWhenNothingIsLeft() {
        val checks = listOf(Proof.Check("npm test", true, 7, true))
        assertTrue(Proof.passedBefore(checks, writeMark = 6, round = 8))
        assertFalse("the passing check is this round's", Proof.passedBefore(checks, 6, 7))
        assertFalse("a change after the pass", Proof.passedBefore(checks, 9, 10))
        assertFalse(Proof.passedBefore(listOf(Proof.Check("npm test", false, 7, true)), 6, 8))
        assertTrue(Proof.checkedTooLong(Proof.AFTER_PASS_ROUNDS, openSteps = 0))
        assertFalse(Proof.checkedTooLong(Proof.AFTER_PASS_ROUNDS - 1, 0))
        assertFalse("open plan steps keep it going", Proof.checkedTooLong(Proof.AFTER_PASS_ROUNDS + 3, 2))
    }
}
