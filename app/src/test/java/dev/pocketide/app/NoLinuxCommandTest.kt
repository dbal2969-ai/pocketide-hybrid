package dev.pocketide.app

import dev.pocketide.app.core.NoLinuxCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.129: without Linux, commands that need it are answered at once instead of showing a Run card. */
class NoLinuxCommandTest {
    @Test fun theEmulatorCommands() {
        assertEquals("python3", NoLinuxCommand.program("cd /work && python3 -m http.server 8080 & sleep 2 && curl -s localhost:8080"))
        assertEquals("node", NoLinuxCommand.program("""which node 2>/dev/null || which python3 2>/dev/null || echo "none""""))
        assertNull(NoLinuxCommand.program("""grep -n "<script>" index.html && echo ok"""))
    }

    @Test fun throughPrefixesAndPaths() {
        assertEquals("npm", NoLinuxCommand.program("NODE_ENV=test npm test"))
        assertEquals("git", NoLinuxCommand.program("env GIT_PAGER=cat /usr/bin/git log -1"))
        assertEquals("python", NoLinuxCommand.program("command -v python"))
        assertEquals("pytest", NoLinuxCommand.program("ls && pytest -q"))
    }

    @Test fun androidShellCommandsRun() {
        for (c in listOf("ls -la", "cat index.html | grep title", "sed -n 1,20p app.js", "find . -name '*.js'", "echo hi > /dev/null", "wc -l *.html"))
            assertNull(c, NoLinuxCommand.program(c))
    }

    @Test fun theReplyPointsAtThePageCheckForPages() {
        assertTrue(NoLinuxCommand.reply("python3", true).contains("look_at_preview"))
        assertTrue(!NoLinuxCommand.reply("python3", false).contains("look_at_preview"))
    }
}
