package dev.pocketide.app

import dev.pocketide.app.core.KnownPrices
import dev.pocketide.app.core.Price
import dev.pocketide.app.core.RunBudget
import dev.pocketide.app.core.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunBudgetTest {
    @Test fun pausesAtTheDollarLimitAndExtends() {
        val b = RunBudget(1.0, 0)
        val p = Price(2.0, 10.0)
        b.add(Usage(input = 200_000, output = 20_000), p)   // 0.40 + 0.20
        assertNull(b.over())
        assertEquals("$0.60 of $1.00", b.meter())
        b.add(Usage(input = 200_000, output = 20_000), p)
        assertNotNull(b.over())
        b.extend()
        assertNull(b.over())
        assertEquals(2.2, b.maxUsd, 0.001)
    }

    @Test fun tokenLimitCoversUnpricedModels() {
        val b = RunBudget(1.0, 1_000_000)
        b.add(Usage(input = 600_000, output = 500_000), null)
        assertTrue(b.unpriced)
        assertTrue(b.over()!!.contains("tokens"))
        assertTrue(b.meter().contains("tokens"))
    }

    @Test fun offWhenBothZero() {
        val b = RunBudget(0.0, 0)
        b.add(Usage(input = 9_000_000), Price(10.0, 50.0))
        assertTrue(b.isOff)
        assertNull(b.over())
    }

    @Test fun parsing() {
        assertEquals(1.5, RunBudget.parseUsd("$1.50")!!, 0.0001)
        assertEquals(0.0, RunBudget.parseUsd("off")!!, 0.0)
        assertNull(RunBudget.parseUsd("lots"))
        assertEquals(3_000_000L, RunBudget.parseTokens("3M"))
        assertEquals(500_000L, RunBudget.parseTokens("500k"))
        assertEquals(1_234L, RunBudget.parseTokens("1,234"))
        assertNull(RunBudget.parseTokens("many"))
        assertEquals("3.0M", RunBudget.count(3_000_000))
    }

    @Test fun knownClaudePrices() {
        assertEquals(2.0, KnownPrices.find("anthropic", "claude-sonnet-5-5")!!.inPerM, 0.0)
        assertEquals(0.20, KnownPrices.find("anthropic", "claude-sonnet-5")!!.cachedPerM, 0.0)
        assertEquals(4.0, KnownPrices.find("anthropic", "claude-opus-5-5")!!.inPerM, 0.0)
        assertEquals(5.0, KnownPrices.find("anthropic", "claude-opus-4-5-20251101")!!.inPerM, 0.0)
        assertEquals(3.0, KnownPrices.find("openrouter", "anthropic/claude-sonnet-4-6")!!.inPerM, 0.0)
        assertNull(KnownPrices.find("groq", "llama-3.3-70b"))
        assertFalse(KnownPrices.find("anthropic", "claude-haiku-4-5")!!.inPerM == 0.10)
    }

    @Test fun cacheFigures() {
        val u = Usage(input = 40_000, cached = 260_000, cacheWrite = 5_000, output = 5_000)
        assertEquals(85, dev.pocketide.app.core.UsageText.cacheRate(u))
        assertTrue(dev.pocketide.app.core.UsageText.line(u, null).contains("cached, 85%"))
        assertTrue(dev.pocketide.app.core.UsageText.line(u, null).contains("saved to cache"))
        assertFalse(dev.pocketide.app.core.UsageText.lowCache(u))
        assertTrue(dev.pocketide.app.core.UsageText.lowCache(Usage(input = 300_000)))
    }
}
