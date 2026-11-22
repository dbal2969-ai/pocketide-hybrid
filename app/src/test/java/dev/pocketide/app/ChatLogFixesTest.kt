package dev.pocketide.app

import dev.pocketide.app.core.ActionVerb
import dev.pocketide.app.core.AgentActions
import dev.pocketide.app.core.ProjectGuard
import dev.pocketide.app.core.Tools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLogFixesTest {
    @Test fun boldActionLinesKeepTheCommandClean() {
        // the reply that failed six times with "ls: invalid option -- '*'"
        assertEquals("ls -la", AgentActions.find("**ACTION: run ls -la**").single().arg)
        assertEquals("ls -la", AgentActions.find("__ACTION: run ls -la__").single().arg)
        assertEquals("index.html", AgentActions.find("*ACTION: read index.html*").single().arg)
        // a bullet keeps a command that really ends in *
        assertEquals("ls *", AgentActions.find("* ACTION: run ls *").single().arg)
        assertEquals(ActionVerb.RUN, AgentActions.find("ACTION: run unzip -o -q a.zip -d a").single().verb)
    }

    @Test fun habitsAndRefusals() {
        assertTrue(AgentActions.repeatNote("exit code 2\nls: invalid option").contains("Not run again"))
        assertTrue(Tools.SYSTEM_NOTE.contains("unzip -o -q") && AgentActions.PROMPT.contains("Do not download"))
        assertNotNull(ProjectGuard.installRefusal("npm install jsdom"))
    }
}
