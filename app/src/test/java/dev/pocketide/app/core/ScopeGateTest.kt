package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopeGateTest {
    private val files = setOf("game/main.js", "game/data/items.json", "game/inv.js", "ui/hud.js", "state/events.js", "test/ui.test.js")
    private val exists: (String) -> Boolean = { p -> files.any { it == p || it.endsWith("/$p") } }

    // the rp-bury shape: the cause fixed, then an item the project did not have added to the data
    @Test fun aProblemReportQuestionsWhatIsNeitherAskedNorTheCause() {
        val said = "The bug is in game/main.js: BONES holds strings but item ids are numbers.\nI also added Big bones to items.json."
        val q = ScopeGate.unasked(listOf("game/main.js", "game/data/items.json", "test/ui.test.js"), "I can't bury big bones. Fix it.", said, true, exists)
        assertEquals(listOf("game/data/items.json"), q)
    }

    // the harbour crew-3 shape: the cause fixed in events.js, the top bar edited too
    @Test fun anExtraEditBesideTheCauseIsQuestioned() {
        val said = "The cause is in state/events.js: the sick are subtracted without the clamp."
        assertEquals(listOf("ui/hud.js"), ScopeGate.unasked(listOf("state/events.js", "ui/hud.js"), "My crew was -2. Fix it.", said, true, exists))
    }

    @Test fun namedFilesTestsAndFeaturesPass() {
        assertTrue(ScopeGate.unasked(listOf("ui/hud.js"), "The top bar in ui/hud.js shows -2. Fix it.", "", true, exists).isEmpty())
        assertTrue(ScopeGate.unasked(listOf("test/ui.test.js"), "Fix it.", "", true, exists).isEmpty())
        // a feature: code files it did not name are fine, a data file is questioned
        assertEquals(listOf("game/data/items.json"),
            ScopeGate.unasked(listOf("game/inv.js", "ui/hud.js", "game/data/items.json"), "Add a sort button to the inventory.", "", false, exists))
    }

    @Test fun testFiles() {
        for (p in listOf("test/a.js", "src/a.test.js", "a_test.go", "tests/x.py", "test_x.py", "pkg/test_x.py", "__tests__/a.js", "a.spec.ts"))
            assertTrue(p, ScopeGate.isTest(p))
        for (p in listOf("src/latest.js", "contest.js", "ui/testimonials.js")) assertTrue(p, !ScopeGate.isTest(p))
    }

    // 1.5.158: naming the data file as "the cause" does not exempt it (the harbour-pearls runs that invented pearls did exactly that)
    @Test fun aDataFileCalledTheCauseIsStillQuestioned() {
        val said = "Root cause of missing pearls: data/goods.json had no pearls entry, so I added one."
        val ex: (String) -> Boolean = { it == "data/goods.json" || it == "loader/schema.js" }
        assertEquals(listOf("data/goods.json"), ScopeGate.unasked(listOf("loader/schema.js", "data/goods.json"),
            "Rope shows 0.00, and the pearls are gone. Fix it.", said + "\nThe bug is in loader/schema.js: price is an int.", true, ex))
    }
}
