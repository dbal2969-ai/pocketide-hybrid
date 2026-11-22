package dev.pocketide.app

import dev.pocketide.app.core.BuildTag
import dev.pocketide.app.core.ChatEntry
import dev.pocketide.app.core.ChatExport
import dev.pocketide.app.core.ChatSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildTagTest {
    @Test fun tagJoinsWhatIsKnown() {
        assertEquals("v1.5.69-gpl (118)", BuildTag.app("1.5.69-gpl", 118))
        assertEquals("", BuildTag.app(null, 118))
        assertEquals("v1.5.69-gpl (118) · cloudflare/@cf/moonshotai/kimi-k2.6", BuildTag.of("v1.5.69-gpl (118)", "cloudflare", "@cf/moonshotai/kimi-k2.6"))
        assertEquals("cloudflare", BuildTag.of("", "cloudflare", ""))
        assertEquals("v1 (1)", BuildTag.of("v1 (1)", "", "x"))
    }

    @Test fun tagSurvivesSaveAndLoadAndShowsInExport() {
        val s = ChatSession("id1", "Chat", 1L, 2L)
        s.entries.add(ChatEntry("user", "Fix the minimap", "", 0, 5L, "v1.5.69-gpl (118) · cloudflare/kimi_k2"))
        s.entries.add(ChatEntry("assistant", "Done.", "", 0, 6L, "v1.5.69-gpl (118) · cloudflare/kimi_k2"))
        s.entries.add(ChatEntry("assistant", "Old reply"))
        val back = ChatSession.fromJson(s.toJson())!!
        assertEquals("v1.5.69-gpl (118) · cloudflare/kimi_k2", back.entries[0].tag)
        assertEquals("", back.entries[2].tag)
        val md = ChatExport.markdown(back)
        assertTrue(md.contains("### You\n\n_v1.5.69-gpl (118) · cloudflare/kimi\\_k2_\n\nFix the minimap"))
        assertTrue(md.contains("### Assistant\n\n_v1.5.69-gpl (118) · cloudflare/kimi\\_k2_\n\nDone."))
        assertTrue(md.contains("### Assistant\n\nOld reply"))   // older chats have no tag line
        assertFalse(s.toJson().contains("\"tag\":\"\""))       // an empty tag is not saved
    }
}
