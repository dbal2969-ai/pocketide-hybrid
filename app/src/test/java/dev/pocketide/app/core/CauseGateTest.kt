package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.147: CauseGate's pure parts. */
class CauseGateTest {
    @Test fun aCauseNeedsAFile() {
        assertTrue(CauseGate.stated("The cause is in src/cart.js:14, where tax is added twice."))
        assertTrue(CauseGate.stated("total() in checkout.js returns the wrong value because it rounds early"))
        assertFalse(CauseGate.stated("I will fix the bug now."))
        assertFalse(CauseGate.stated("The cause is that tax is added twice."))   // no file named
        assertFalse(CauseGate.stated("Let me read src/cart.js."))
        // 1.5.150 (M5): the file must be one of the project's
        val files = setOf("src/cart.js", "app.js")
        val exists: (String) -> Boolean = { p -> p in files || files.any { it.substringAfterLast('/') == p } }
        assertTrue(CauseGate.stated("The cause is in src/cart.js:14.", exists))
        assertTrue(CauseGate.stated("The bug is in cart.js because tax is added twice.", exists))
        assertFalse(CauseGate.stated("The cause is in made.up because of reasons.", exists))
    }

    @Test fun dataFiles() {
        assertTrue(CauseGate.isData("game/data/items.json"))
        assertTrue(CauseGate.isData("config/levels.yml"))
        assertFalse(CauseGate.isData("package.json"))
        assertFalse(CauseGate.isData("src/inv.js"))
        assertFalse(CauseGate.isData("README.txt"))
        assertFalse(CauseGate.isData("notes.txt"))
        assertEquals(listOf("data/items.json"), CauseGate.unnamedData(listOf("src/a.js", "data/items.json", "data/prices.csv"), "the price in prices.csv is wrong"))
    }
}
