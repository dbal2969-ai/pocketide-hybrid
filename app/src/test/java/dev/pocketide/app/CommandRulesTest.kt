package dev.pocketide.app

import dev.pocketide.app.core.CommandRules
import dev.pocketide.app.core.CommandRules.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandRulesTest {
    private val rules = CommandRules.Rules(allow = listOf("npm test", "ls", "npm install"), deny = listOf("git push"))

    @Test fun splitsCompoundCommandsOutsideQuotes() {
        assertEquals(listOf("npm test", "echo 'a && b'", "wc -l"), CommandRules.parts("npm test && echo 'a && b' | wc -l"))
        assertEquals(listOf("ls", "pwd"), CommandRules.parts("ls; pwd"))
    }

    @Test fun everyPartMustBeAllowed() {
        assertEquals(Decision.RUN, CommandRules.decide("npm test -- --watch", rules, autorun = false))
        assertEquals(Decision.RUN, CommandRules.decide("ls && npm test", rules, autorun = false))
        assertEquals(Decision.ASK, CommandRules.decide("ls && cat secrets.txt", rules, autorun = false))
        assertEquals(Decision.ASK, CommandRules.decide("npm tester", rules, autorun = false))
        assertEquals(Decision.RUN, CommandRules.decide("npm install", rules, autorun = false))   // network, but allowed by the person
    }

    @Test fun denyWinsAndRiskyCommandsStillAsk() {
        assertEquals(Decision.DENY, CommandRules.decide("npm test && git push origin main", rules, autorun = true))
        assertEquals("git push", CommandRules.blockedBy("ls; git push", rules))
        val loose = CommandRules.Rules(listOf("rm"), emptyList())
        assertEquals(Decision.ASK, CommandRules.decide("rm -rf src", loose, autorun = true))
    }

    @Test fun suggestionsAndStorage() {
        assertEquals("npm test", CommandRules.suggest("npm test --silent 2>&1"))
        assertEquals("node", CommandRules.suggest("node server.js"))
        val r = CommandRules.add(CommandRules.EMPTY, "  git   push ", false)
        assertEquals(listOf("git push"), r.deny)
        assertEquals(r, CommandRules.decode(CommandRules.encode(r)))
        assertEquals(CommandRules.EMPTY, CommandRules.remove(r, "git push"))
    }
}
