package dev.pocketide.app

import dev.pocketide.app.core.HistoryTrim
import dev.pocketide.app.core.Tools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotTrimTest {
    private val img = "data:image/jpeg;base64," + "A".repeat(200_000)

    @Test fun keepsOnlyTheNewestScreenshotAndPeoplesPictures() {
        for (anthropic in listOf(false, true)) {
            val msgs = mutableListOf(
                Tools.userText(anthropic, "Make the page look like this picture", listOf(img)),
                Tools.userText(anthropic, "Screenshot of the page from look_at_preview (what the player sees):", listOf(img)),
                Tools.userText(anthropic, "Screenshot of the page from look_at_preview (what the player sees):", listOf(img)),
                Tools.userText(anthropic, "Screenshot of the page from look_at_preview (what the player sees):", listOf(img)))
            assertEquals(2, HistoryTrim.dropOldScreenshots(msgs))
            fun images(i: Int) = (msgs[i]["content"] as List<*>).count { (it as Map<*, *>)["type"].toString().startsWith("image") }
            assertEquals(1, images(0))      // the person's own picture stays
            assertEquals(0, images(1)); assertEquals(0, images(2))
            assertEquals(1, images(3))      // newest screenshot stays
            assertTrue((msgs[1]["content"] as List<*>).toString().contains("older screenshot removed"))
        }
    }

    @Test fun imagesCountAtAFixedCost() {
        val msgs = listOf(Tools.userText(false, "look_at_preview", listOf(img)))
        assertTrue(HistoryTrim.total(msgs) < 5000)   // was over 200,000, which made every request cut the text history
    }
}
