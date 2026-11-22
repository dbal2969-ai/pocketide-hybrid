package dev.pocketide.app

import dev.pocketide.app.core.RequestReminder
import dev.pocketide.app.core.Requirements
import dev.pocketide.app.core.Rounds
import dev.pocketide.app.core.Tools
import dev.pocketide.app.core.WebResearch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantDirectionTest {
    @Test fun requirementsLookFirstAndNameSources() {
        val r = Requirements.researchRequest("Fix the HUD to look like OSRS", "")
        assertTrue(r.startsWith(Requirements.REQUEST_START))
        assertFalse(r.contains(Requirements.NO_TOOLS))
        assertTrue(r.contains("First look, read-only"))
        assertTrue(r.contains("[picture: <path>]") && r.contains("[assumed]"))
        assertTrue(r.trimEnd().endsWith("Request: Fix the HUD to look like OSRS"))
        assertTrue(Requirements.request("x").contains(Requirements.NO_TOOLS))   // the text format still writes the list without tools
    }

    @Test fun researchWordingFromRealRequests() {
        assertTrue(Requirements.asksForResearch("Dig deeper on the internet to fill in the gaps"))
        assertTrue(Requirements.asksForResearch("Find resources for the OSRS minimap and orbs"))
        assertFalse(Requirements.asksForResearch("Find and import code from online resources using code from runelite to fix this game's ui"))
    }

    @Test fun toolCallsWrittenAsTextAreCaught() {
        assertTrue(Tools.toolCallAsText("""{"tool_calls": [{"name": "run", "arguments": "ls -R game/"}]}"""))
        assertTrue(Tools.toolCallAsText("<|channel|>commentary to=functions.search <|message|>{\"q\":\"x\"}"))
        assertTrue(Tools.toolCallAsText("<function=read_file>\n<parameter=path>a.js</parameter>\n</function>"))
        assertTrue(Tools.toolCallAsText("""{"name": "read_file", "arguments": {"path": "a.js"}}"""))
        assertFalse(Tools.toolCallAsText("I changed game/main.js so the status line has its own element."))
        assertFalse(Tools.toolCallAsText("Use the read_file tool's name parameter."))
        // Python form on its own line (Llama 3.3 at 1.5.89), but not in a code block or mid-sentence
        assertTrue(Tools.toolCallAsText("To fix it:\nedit_file(\"src/format.js\", \"function money(dollars) {\", \"x\")"))
        assertTrue(Tools.toolCallAsText("I'll read the error.\nrun_command(\"npm test\")"))
        assertTrue(Tools.toolCallAsText("read_file(path=\"a.js\")"))
        assertFalse(Tools.toolCallAsText("Example:\n```python\nrun_command(\"ls\")\n```"))
        assertFalse(Tools.toolCallAsText("I called read_file(\"a.js\") and it shows add subtracts."))
        assertFalse(Tools.toolCallAsText("run_command() needs a command."))
    }

    @Test fun licensedCodeMayBeReusedAndNamedResourcesUsed() {
        assertTrue(WebResearch.NOTE.contains("MIT, BSD, Apache-2.0"))
        assertTrue(WebResearch.NOTE.contains("never swap in something else from memory"))
        assertFalse(WebResearch.NOTE.contains("write your own code"))
    }

    @Test fun toolRefusalExpires() {
        val day = 86_400_000L; val now = 100 * day
        assertTrue(Rounds.rejectionFresh(now - day, now))
        assertFalse(Rounds.rejectionFresh(now - 8 * day, now))
        assertFalse(Rounds.rejectionFresh(0L, now))   // marks from before 1.5.70 have no date
    }

    @Test fun reminderKeepsThePersonsWords() {
        val m = RequestReminder.text("  Use RuneLite's sprites for the orbs  ")
        assertTrue(m.contains("\"Use RuneLite's sprites for the orbs\""))
        assertTrue(m.contains("use the resources it names"))
    }
}
