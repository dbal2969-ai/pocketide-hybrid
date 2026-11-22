package dev.pocketide.app

import dev.pocketide.app.core.ContextRanker
import dev.pocketide.app.core.RepoRank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepoRankTest {
    private val texts = mapOf(
        "core.js" to "function loadWorld() {}\nfunction drawSprite() {}",
        "a.js" to "loadWorld(); drawSprite();",
        "b.js" to "loadWorld();",
        "c.js" to "function lonelyHelper() {}",
        "main.js" to "loadWorld(); drawSprite(); lonelyHelper();"
    )
    private val defs = { p: String, t: String -> ContextRanker.symbols(p, t) }

    @Test fun mostUsedFileRanksFirstAndSumsToOne() {
        val r = RepoRank.rank(texts, defs)
        assertEquals(1.0, r.values.sum(), 1e-6)
        assertEquals("core.js", r.entries.maxByOrNull { it.value }!!.key)
        assertTrue(r.getValue("core.js") > r.getValue("c.js"))
    }

    @Test fun seedsPullWeightTowardWhatTheyUse() {
        val plain = RepoRank.rank(texts, defs)
        val r = RepoRank.rank(texts, defs, seeds = setOf("c.js"))
        assertTrue(r.getValue("c.js") > plain.getValue("c.js"))
        assertTrue(r.getValue("a.js") < plain.getValue("a.js"))
    }

    @Test fun emptyInput() { assertTrue(RepoRank.rank(emptyMap(), defs).isEmpty()) }

    @Test fun taskContextFindsRelevantFilesWithoutFilenameMatches() {
        val results = dev.pocketide.app.core.Eval.contextTasks()
        assertEquals(10, results.size)
        assertTrue(dev.pocketide.app.core.Eval.format(results), results.all { it.ok })
    }
}
