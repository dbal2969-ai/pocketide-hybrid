package dev.pocketide.app

import dev.pocketide.app.core.CommandRules
import dev.pocketide.app.core.Sandbox
import dev.pocketide.app.core.Sandbox.Risk
import org.junit.Assert.assertEquals
import org.junit.Test

/** 1.5.127: discarding output is not writing a file; writing one still is. */
class SandboxRiskTest {
    @Test fun discardedOutputIsNotAWrite() {
        assertEquals(Risk.SAFE, Sandbox.risk("""which node 2>/dev/null || which python3 2>/dev/null || echo "none""""))
        assertEquals(Risk.SAFE, Sandbox.risk("ls -la > /dev/null"))
        assertEquals(Risk.SAFE, Sandbox.risk("node test.js 2>&1"))
        assertEquals(Risk.SAFE, Sandbox.risk("cat a.txt &>/dev/null"))
        assertEquals(Risk.SAFE, Sandbox.risk("""grep -n "<script>" index.html && grep -n '</body>' index.html && echo "Tags look matched""""))
    }

    @Test fun realWritesAreStillWrites() {
        assertEquals(Risk.WRITES, Sandbox.risk("echo hi > out.txt"))
        assertEquals(Risk.WRITES, Sandbox.risk("node build.js 2> errors.log"))
        assertEquals(Risk.WRITES, Sandbox.risk("ls 2>/dev/null > list.txt"))
        assertEquals(Risk.WRITES, Sandbox.risk("cat a >> b"))
        assertEquals(Risk.WRITES, Sandbox.risk("rm old.js"))
        assertEquals(Risk.WRITES, Sandbox.risk("""grep "<b>" index.html > found.txt"""))
    }

    // 1.5.137, emulator test of 1.5.136: the card said "This only reads." and offered "Always allow cat" for these
    @Test fun pathsAfterRedirectionsQuotesAndEqualsAreOutside() {
        for (c in listOf("cat </etc/passwd", "cat < /etc/passwd", "cat \"/etc/passwd\"", "grep x --file=/etc/hosts", "head '~/x'",
                "cat \"${'$'}HOME/.bashrc\"", "cat ${'$'}{HOME}/.bashrc", "echo hi >/etc/motd"))
            assertEquals(c, Risk.OUTSIDE, Sandbox.risk(c))
        assertEquals("This looks like it reaches outside the project folder.", Sandbox.explain("cat </etc/passwd"))
        // still not outside: project paths, /dev/null, and a URL's slashes
        for (c in listOf("cat src/app.js", "ls -la > /dev/null", "cat a.txt 2>/dev/null"))
            assertEquals(c, Risk.SAFE, Sandbox.risk(c))
        assertEquals(Risk.NETWORK, Sandbox.risk("curl https://example.com/api"))
    }

    @Test fun anAllowRuleNeverSkipsTheQuestionForThem() {
        val rules = CommandRules.Rules(listOf("cat"), emptyList())
        assertEquals(CommandRules.Decision.ASK, CommandRules.decide("cat </etc/passwd", rules, autorun = false))
        assertEquals(CommandRules.Decision.RUN, CommandRules.decide("cat src/app.js", rules, autorun = false))
    }
}
