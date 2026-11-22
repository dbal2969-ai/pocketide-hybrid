package dev.pocketide.app

import dev.pocketide.app.core.AppLog
import dev.pocketide.app.core.ChatArchive
import dev.pocketide.app.core.ChatEntry
import dev.pocketide.app.core.ChatSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatArchiveTest {
    @Test fun oneFileNewestFirst() {
        val old = ChatSession("a", "Old idea", 1_000_000L, 1_000_000L, arrayListOf(ChatEntry("user", "make a game")))
        val new = ChatSession("b", "New idea", 2_000_000L, 9_000_000L, arrayListOf(ChatEntry("user", "add a score")))
        val md = ChatArchive.markdown(listOf(old, new), "My Game", listOf("Model: Test"))
        assertTrue(md, md.startsWith("# All chats in My Game\n\n- Chats: 2\n- Model: Test\n"))
        assertTrue(md.indexOf("## New idea") in 1 until md.indexOf("## Old idea"))
        assertTrue(md.contains("make a game") && md.contains("add a score"))
    }

    @Test fun eachChatsRecordGoesUnderItWithKeysHidden() {
        val a = ChatSession("a", "Game", 2_000_000L, 2_000_000L, arrayListOf(ChatEntry("user", "add a score")))
        val b = ChatSession("b", "Other", 1_000_000L, 1_000_000L, arrayListOf(ChatEntry("user", "hi")))
        val md = ChatArchive.markdown(listOf(a, b), "My Game", emptyList()) { c ->
            if (c.id == "a") "\$ npm test\nFAIL sk-abcdefghijklmnop\n" else null
        }
        assertTrue(md, md.contains("### Assistant record: the commands it ran, with their output"))
        assertTrue(md, md.contains("\$ npm test\nFAIL sk-abc…[hidden]"))
        assertFalse(md.contains("abcdefghijklmnop"))
        assertTrue("only the chat that has a record gets one", md.indexOf("Assistant record") in 0 until md.indexOf("## Other"))
    }

    @Test fun aLongRecordKeepsItsStartAndEnd() {
        val big = "START" + "x".repeat(ChatArchive.RECORD_MAX) + "END"
        val md = ChatArchive.markdown(listOf(ChatSession("a", "Game", 1L, 1L, arrayListOf())), "p", emptyList()) { big }
        assertTrue(md.contains("START") && md.contains("END") && md.contains("characters left out"))
        assertTrue(md.length < ChatArchive.RECORD_MAX * 2)
    }

    @Test fun pathIsInTheChatLogsFolder() {
        val p = ChatArchive.path(0)
        assertTrue(p, p.startsWith("chat-logs/all-chats-") && p.endsWith(".md"))
    }

    @Test fun logSurvivesARestartAndMasksKeys() {
        val f = File.createTempFile("applog", ".txt"); f.delete()
        AppLog.clear(); AppLog.attach(f)
        AppLog.add("model", "rejected sk-abcdefghijklmnop")
        AppLog.run("openrouter", "x/y", "done", 1200, 2, 9)
        assertTrue(f.readText().contains("rejected"))
        assertFalse(f.readText().contains("abcdefghijklmnop"))
        AppLog.clear(); AppLog.attach(f)  // a restart: the file is read again
        val entries = AppLog.entries()
        assertTrue(entries.any { it.contains("|model|rejected sk-…") })
        assertTrue(entries.any { it.endsWith("|run|openrouter/x/y · done · 9s · 1200 tokens · 2 files changed") })
        f.delete()
    }
}
