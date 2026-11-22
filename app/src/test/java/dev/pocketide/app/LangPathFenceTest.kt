package dev.pocketide.app

import dev.pocketide.app.core.ReplyParser
import org.junit.Test

class LangPathFenceTest {
    /** The reply shape the hybrid model wrote for the snake game: a language, a colon and the file name. */
    @Test fun languageColonPathFence() {
        val reply = "I will update game.js.\n\n```javascript:game.js\nconst x = 1;\nconsole.log(x);\n```\n\nDone."
        val p = ReplyParser.parse(reply, emptyMap(), emptySet(), null)
        check(p.edits.size == 1 && p.edits[0].path == "game.js") { "expected one edit to game.js, got ${p.edits.map { it.path }}" }
        check(p.edits[0].content == "const x = 1;\nconsole.log(x);\n") { "content was: ${p.edits[0].content}" }
        check(!p.text.contains("```")) { "fence should not be shown: ${p.text}" }
    }

    /** A language and a colon with no file extension is not a file (so ```python:3 style text stays as text). */
    @Test fun notAFileWithoutExtension() {
        val reply = "Example:\n\n```python:3\nprint(1)\n```"
        val p = ReplyParser.parse(reply, emptyMap(), emptySet(), null)
        check(p.edits.isEmpty()) { "should not be a file: ${p.edits.map { it.path }}" }
    }
}
