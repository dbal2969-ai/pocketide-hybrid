package dev.pocketide.app

import dev.pocketide.app.core.InputVariants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.164: other shapes of the person's sample, written by the app. */
class InputVariantsTest {
    @Test fun samplesAreDataFilesOutsideTestsAndTheAppsFolders() {
        assertEquals(listOf("import/books.csv", "data/page.html"),
            InputVariants.samples(listOf("app.js", "import/books.csv", "test/fixture.csv", ".pocketide/x.csv", "data/page.html", "requirements.txt")))
    }

    @Test fun aCsvGetsItsShapesWithTheSameContent() {
        val vs = InputVariants.of("import/books.csv", "title,author\nSalt,Rowe\n")
        assertEquals(listOf("crlf", "bom-blank", "columns", "quoted"), vs.map { it.path.substringAfter("books-").substringBefore('.') })
        assertEquals("title,author\r\nSalt,Rowe\r\n", vs[0].text)
        assertEquals("AUTHOR,TITLE\nRowe,Salt\n", vs[2].text)
        assertEquals("title,author\n\"Salt\",\"Rowe\"\n", vs[3].text)
        assertTrue(vs.all { it.path.startsWith(".pocketide/variants/") })
    }

    @Test fun htmlAndColumnTextToo() {
        val h = InputVariants.of("p.html", "<table><tr><td>Mon 2 Mar</td></tr></table>")
        assertTrue(h.any { it.text.contains("<TD>") } && h.any { it.text.contains("Mon&nbsp;2&nbsp;Mar") })
        val t = InputVariants.of("s.txt", "2026-03-01  Cafe  -3.20\n")
        assertTrue(t.any { it.text == "2026-03-01\tCafe\t-3.20\n" })
        assertTrue(InputVariants.ask(listOf("s.txt" to t)).contains(".pocketide/variants/s-tabs.txt: s.txt with tabs"))
    }

    /** Copilot PR #29: every variety request names a sample under imports/ and a reading verb; all six now fire, plain code reads do not. */
    @Test fun readingASampleInputFileCountsAsOutsideInput() {
        val v = dev.pocketide.app.core.InputVariety
        for (r in listOf(
            "Write readOrders in lib/readOrders.js so I can import the orders file the shop's till exports; the sample is imports/orders-sample.csv.",
            "Write readReadings in lib/readReadings.js to read my weather station's export; imports/readings-sample.json is one.",
            "Write readSongs in lib/readSongs.js so the choir can paste the song list from our notes; imports/songs-sample.md is how it looks in mine.",
            "Write readEntries in lib/readEntries.js so I can paste in entries from my old diary apps; imports/diary-sample.txt is from one of them.",
            "Write readReport in lib/readReport.js to read the old till's daily report; imports/till-sample.txt is one.",
            "Write readIngredients in lib/readIngredients.js so the scaler can read a recipe's ingredient lines; imports/recipe-sample.txt has some."))
            assertTrue(r, v.readsOutsideInput(r))
        for (r in listOf("Read app.js and fix the bug in add.", "Read the README and tell me what the project does.", "Fix the score display in game/main.js."))
            assertTrue(r, !v.readsOutsideInput(r))
    }
}
