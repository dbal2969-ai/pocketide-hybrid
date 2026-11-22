package dev.pocketide.app

import dev.pocketide.app.core.ChatMarkdown
import dev.pocketide.app.core.ChatMarkdown.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.127: the assistant's replies show Markdown formatted, not with its marks. */
class ChatMarkdownTest {
    private fun styled(d: ChatMarkdown.Doc, k: Kind) = d.spans.filter { it.kind == k }.map { d.text.substring(it.start, it.end) }

    @Test fun theEmulatorReply() {
        val d = ChatMarkdown.parse("""switch to **Agent** mode or tap **"Build this"**, then I will create `index.html`.""")
        assertEquals("""switch to Agent mode or tap "Build this", then I will create index.html.""", d.text)
        assertEquals(listOf("Agent", "\"Build this\""), styled(d, Kind.BOLD))
        assertEquals(listOf("index.html"), styled(d, Kind.CODE))
    }

    @Test fun codeBlocksKeepTheirTextAndTakeNoInlineMarks() {
        val d = ChatMarkdown.parse("Run:\n```bash\nnpm test -- **/*.js\n```\nDone.")
        assertEquals("Run:\nnpm test -- **/*.js\nDone.", d.text)
        assertEquals(listOf("npm test -- **/*.js"), styled(d, Kind.CODE_BLOCK))
        assertTrue(styled(d, Kind.BOLD).isEmpty())
    }

    @Test fun anUnclosedFenceWhileTheReplyArrivesRunsToTheEnd() {
        val d = ChatMarkdown.parse("Here:\n```js\nconst a = 1\nconst b")
        assertEquals("Here:\nconst a = 1\nconst b", d.text)
        assertEquals(listOf("const a = 1\nconst b"), styled(d, Kind.CODE_BLOCK))
    }

    @Test fun headingsBulletsQuotesAndRules() {
        val d = ChatMarkdown.parse("## Steps\n- first *step*\n  * nested\n1. one\n> a quote\n---")
        assertEquals("Steps\n• first step\n  • nested\n1. one\na quote\n―――", d.text)
        assertEquals(listOf("Steps"), styled(d, Kind.HEADING))
        assertEquals(listOf("step"), styled(d, Kind.ITALIC))
        assertEquals(listOf("a quote"), styled(d, Kind.QUOTE))
    }

    @Test fun linksKeepTheirTextAndAddress() {
        val d = ChatMarkdown.parse("See [the docs](https://example.com/a) now.")
        assertEquals("See the docs now.", d.text)
        assertEquals("https://example.com/a", d.spans.single().url)
    }

    @Test fun ordinaryTextIsLeftAlone() {
        for (s in listOf("2 * 3 * 4 = 24", "snake_case_name and file_name.py", "a * b", "price: 5*", "**unclosed bold", "a `stray tick",
                "[not a link]", "x__init__y", "edit __init__.py now")) {
            val d = ChatMarkdown.parse(s)
            assertEquals(s, s, d.text)
            assertTrue(s, d.spans.isEmpty())
        }
    }

    @Test fun nestedMarksInsideBold() {
        val d = ChatMarkdown.parse("**use `npm ci` first**")
        assertEquals("use npm ci first", d.text)
        assertEquals(listOf("use npm ci first"), styled(d, Kind.BOLD))
        assertEquals(listOf("npm ci"), styled(d, Kind.CODE))
    }
}
