package dev.pocketide.app

import dev.pocketide.app.core.ChatEntry
import dev.pocketide.app.core.ChatHome
import dev.pocketide.app.core.ChatSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.125: the Chat home's search over every project's chats, and the name "Build this" gives a project. */
class ChatHomeTest {
    private fun chat(id: String, title: String, updated: Long, vararg text: String) =
        ChatSession(id, title, 0L, updated, text.map { ChatEntry("user", it) }.toMutableList())

    @Test fun searchFindsEveryWordTitlesFirstThenNewest() {
        val all = listOf(
            "game" to chat("a", "Fix the hit splats", 10, "the splats do not show over the player"),
            ".chat" to chat("b", "Questions about CSS", 30, "how do I center a div with flex"),
            "meal planner" to chat("c", "Recipe import", 20, "import a recipe from a link, the splats word appears here too"),
        )
        assertEquals(listOf("a", "c"), ChatHome.search(all, "splats").map { it.chatId })   // the title match first
        assertEquals(listOf("b"), ChatHome.search(all, "center FLEX").map { it.chatId })
        assertEquals(emptyList<String>(), ChatHome.search(all, "splats flex").map { it.chatId })
        assertEquals(listOf("b", "c", "a"), ChatHome.search(all, "").map { it.chatId })    // empty: newest first
        val hit = ChatHome.search(all, "center").single()
        assertEquals(".chat", hit.project)
        assertTrue(hit.snippet, hit.snippet.contains("center a div"))
    }

    @Test fun theNewProjectIsNamedAfterTheChat() {
        assertEquals("Meal planner with recipe import", ChatHome.projectName("Meal planner, with recipe import!"))
        assertEquals("New project", ChatHome.projectName("???"))
        assertEquals(ChatHome.LABEL, ChatHome.projectLabel(".chat", ".chat"))
        assertEquals("game", ChatHome.projectLabel("game", ".chat"))
    }

    /** 1.5.127: the empty Chat home talks about asking; 1.5.168: and says it works on projects by name. */
    @Test fun emptyChatHomeHintsAreAboutAsking() {
        assertTrue(ChatHome.EMPTY.contains("work on your projects by name") && ChatHome.EMPTY.contains("Build this"))
        assertTrue(!ChatHome.EMPTY.contains("Agent"))
        assertEquals(3, ChatHome.STARTERS.size)
        assertTrue(ChatHome.STARTERS.none { Regex("(?i)\\b(add|fix|create|change)\\b").containsMatchIn(it) })
    }
}
