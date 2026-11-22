package dev.pocketide.app.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** 1.5.125: each chat question's check passes a right answer and fails a wrong one, so the suite scores answers, not luck. */
class ChatBenchTasksTest {
    private val right = mapOf(
        "chat-teapot" to "It means \"I'm a teapot\", an April Fools' joke from RFC 2324.",
        "chat-typeof-null" to "typeof null returns \"object\", a long-standing quirk.",
        "chat-binary-search" to "O(log n).",
        "chat-gold" to "Gold's symbol is Au, from the Latin aurum.",
        "chat-fahrenheit" to "100 °F is 37.8 °C.",
        "chat-border-box" to "Width then includes padding and border, not only the content.",
        "chat-git-range" to "git log main..feature",
        "chat-moon" to "1969, with Apollo 11.",
        "chat-let-var" to "let is block-scoped, while var is function-scoped and hoisted.",
        "chat-zip-regex" to "^\\d{5}(-\\d{4})?$",
        "chat-arithmetic" to "17 × 23 = 391.",
        "chat-map" to "Use Array.map: it returns a new array.",
    )
    private val wrong = mapOf(
        "chat-teapot" to "It means the server is busy.",
        "chat-typeof-null" to "It returns \"null\".",
        "chat-binary-search" to "O(n).",
        "chat-gold" to "Gold is Ag.",
        "chat-fahrenheit" to "About 38 degrees.",
        "chat-border-box" to "Nothing changes.",
        "chat-git-range" to "git status",
        "chat-moon" to "1972.",
        "chat-let-var" to "They are the same.",
        "chat-zip-regex" to "[0-9]+",
        "chat-arithmetic" to "381.",
        "chat-map" to "Use a for loop.",
    )

    private fun passes(name: String, answer: String): Boolean {
        val t = ChatBenchTasks.ALL.single { it.name == name }
        val dir = Files.createTempDirectory("chat-bench").toFile()
        java.io.File(dir, ".bench").mkdirs(); java.io.File(dir, ".bench/answer.txt").writeText(answer)
        return AgentHarness.shell(dir, t.check).first == 0
    }

    @Test fun everyQuestionScoresTheAnswer() {
        assertEquals(right.keys, ChatBenchTasks.ALL.map { it.name }.toSet())
        for (t in ChatBenchTasks.ALL) {
            assertTrue("${t.name} must be asked in the Chat home, read-only", t.chatHome && t.readOnly && t.files.isEmpty())
            assertTrue("${t.name} should pass: ${right[t.name]}", passes(t.name, right.getValue(t.name)))
            assertTrue("${t.name} should fail: ${wrong[t.name]}", !passes(t.name, wrong.getValue(t.name)))
        }
    }
}
