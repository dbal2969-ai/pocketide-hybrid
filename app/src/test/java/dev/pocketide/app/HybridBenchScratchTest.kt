package dev.pocketide.app

import dev.pocketide.app.core.HybridBenchTasks
import dev.pocketide.app.core.HybridBenchTasks.Entry
import dev.pocketide.app.core.HybridBenchTasks.Facts
import org.junit.Test

/** The from-scratch suite: five prompts, each in an empty project and from the chat with no project. */
class HybridBenchScratchTest {
    private val s = HybridBenchTasks.SCRATCH

    @Test fun fivePromptsTwice() {
        check(s.size == 10)
        check(s.count { it.entry == Entry.CHAT } == 5 && s.count { it.entry == Entry.PROJECT } == 5)
        check(s.all { it.files.isEmpty() && it.command == null })
        val kinds = s.map { it.id.split("-")[1] }.toSet()
        check(kinds == setOf("snake", "calculator", "todo", "tictactoe", "tip")) { kinds.toString() }   // analyze.py picks its checks by this
        val all = HybridBenchTasks.ALL + HybridBenchTasks.HARD + HybridBenchTasks.GAMES + HybridBenchTasks.LOCAL + s
        check(all.map { it.id }.toSet().size == all.size)
    }

    @Test fun theAppsVerdict() {
        val t = s.first()
        val page = mapOf("index.html" to "<html></html>")
        check(t.judge(Facts(page, emptyMap(), null, "", "===== x · outcome: ✓ Done", "done")) == null)
        check(t.judge(Facts(mapOf("notes.md" to "x"), emptyMap(), null, "", "outcome: ✓", "done"))!!.contains("no page"))
        check(t.judge(Facts(page, emptyMap(), null, "", "outcome: ✗ Not finished", "done"))!!.contains("did not pass"))
        check(t.judge(Facts(emptyMap(), emptyMap(), null, "", "", "never built: done"))!!.contains("never turned into a project"))
    }
}
