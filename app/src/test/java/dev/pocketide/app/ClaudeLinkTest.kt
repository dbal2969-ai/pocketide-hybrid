package dev.pocketide.app

import dev.pocketide.app.core.ClaudeLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeLinkTest {
    @Test fun shortChatIsSentWhole() {
        val body = ClaudeLink.shareBody("Dark mode", "# Dark mode\n\nhello")
        assertEquals("Continuing a chat from PocketIDE: Dark mode\n\n# Dark mode\n\nhello", body)
    }

    @Test fun longChatIsCutAndSaysSo() {
        val md = "x".repeat(100)
        val body = ClaudeLink.shareBody("", md, max = 40)
        assertTrue(body.startsWith("Continuing a chat from PocketIDE: untitled\n\n" + "x".repeat(40)))
        assertTrue(body.endsWith("(60 more characters left out)"))
    }
}
