package dev.pocketide.app.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BenchIsolationTest {
    @Test fun commandsDoNotInheritBenchmarkCredentials() {
        val dir = Files.createTempDirectory("bench-isolation").toFile()
        try {
            val (code, out) = AgentHarness.shell(dir, "env", isolate = false)
            assertEquals(0, code)
            assertFalse(out, out.contains("POCKETIDE_BENCH_"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun liveCommandsSeeOnlyTheFixtureAndSystemRuntimes() {
        assumeTrue(File("/usr/bin/bwrap").canExecute())
        val dir = Files.createTempDirectory("bench-isolation").toFile()
        try {
            File(dir, "value.json").writeText("""{"ok":true}""")
            val (code, out) = AgentHarness.shell(dir,
                """node -e "console.log(require('./value.json').ok)" && python3 -c "print('python ok')" && test ! -e /workspaces && test ! -e /home/codespace && test -z "${'$'}POCKETIDE_BENCH_KEY_CLOUDFLARE" """,
                isolate = true)
            assertEquals(out, 0, code)
            assertTrue(out, out.contains("true") && out.contains("python ok"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun aQuietCommandStillHasATimeLimit() {
        val dir = Files.createTempDirectory("bench-timeout").toFile()
        try {
            val started = System.nanoTime()
            val (code, out) = AgentHarness.shell(dir, "sleep 30", isolate = false, timeoutSeconds = 1)
            assertEquals(124, code)
            assertTrue(out.contains("(timed out)"))
            assertTrue((System.nanoTime() - started) / 1_000_000_000 < 10)
        } finally { dir.deleteRecursively() }
    }
}
