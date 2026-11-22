package dev.pocketide.app

import dev.pocketide.app.core.ChatEntry
import dev.pocketide.app.core.ChatSession
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatLogTimeTest {
    @Test fun unansweredManualRequestSurvivesReloadUntilAnswered() {
        val session = ChatSession("c1", "T", 1L, 2L)
        session.entries.add(ChatEntry("user", "Finish the calculator", time = 3L))

        val restored = ChatSession.fromJson(session.toJson())!!
        assertEquals(0, restored.unansweredRequestIndex())
        restored.entries.add(ChatEntry("assistant", "Done"))
        assertEquals(null, restored.unansweredRequestIndex())
        restored.entries.add(ChatEntry("user", "internal repair", note = "auto"))
        assertEquals(null, restored.unansweredRequestIndex())
    }

    @Test fun timesSurviveSaveAndOldChatsHaveNone() {
        val s = ChatSession("c1", "T", 1L, 2L)
        s.entries.add(ChatEntry("user", "hi", time = 1_700_000_000_000L))
        s.entries.add(ChatEntry("assistant", "hello", time = 1_700_000_005_000L))
        val back = ChatSession.fromJson(s.toJson())!!
        assertEquals(1_700_000_000_000L, back.entries[0].time)
        assertEquals(1_700_000_005_000L, back.entries[1].time)
        // a chat saved before times were kept reads back with no time, not a made-up one
        val old = ChatSession.fromJson("{\"id\":\"c2\",\"title\":\"Old\",\"entries\":[{\"role\":\"user\",\"content\":\"x\"}]}")!!
        assertEquals(0L, old.entries[0].time)
        assertEquals("", ChatSession("c3", "T", 0L, 0L).toJson().let { if (it.contains("\"time\"")) "" else "" })
    }
}
