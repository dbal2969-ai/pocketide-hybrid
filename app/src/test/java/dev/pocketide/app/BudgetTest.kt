package dev.pocketide.app

import dev.pocketide.app.core.Price
import dev.pocketide.app.core.SpendBudget
import dev.pocketide.app.core.SpendBudget.Limit
import dev.pocketide.app.core.Usage
import dev.pocketide.app.core.UsageLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BudgetTest {
    @Test fun outputPerMinuteLimit() {
        val m = "Request too large for model `qwen/qwen3.8-27b` on output tokens per minute (OTPM): Limit 1000, Requested 1060. reduce max_tokens (HTTP 429)"
        assertEquals(1000, dev.pocketide.app.core.Budget.otpmLimit(m))
        assertEquals(500, dev.pocketide.app.core.Budget.outputCapFor(1000))
        assertEquals(256, dev.pocketide.app.core.Budget.outputCapFor(100))
        assertNull(dev.pocketide.app.core.Budget.otpmLimit("tokens per minute (TPM): Limit 8000, Used 1, Requested 9"))
    }

    @Test fun budget() {
        val f = ArrayList<String>()
        fun check(ok: Boolean, m: String) { if (!ok) f.add(m) }
        val rows = listOf(UsageLog.Row("2026-10-20", "p/m", Usage(1_000_000, 1_000_000), 3), UsageLog.Row("2026-10-20", "q/free", Usage(5_000_000, 0), 1))
        val prices = mapOf("p/m" to Price(3.0, 15.0))
        check(SpendBudget.spent(rows, prices, "p") == 18.0, "spent")
        check(SpendBudget.spent(rows, prices, "q") == 0.0, "other provider")
        check(SpendBudget.tokens(rows, "q") == 5_000_000L, "tokens")
        check(SpendBudget.level(8.0, 10.0) == 1 && SpendBudget.level(10.0, 10.0) == 2 && SpendBudget.level(1.0, 0.0) == 0, "levels")
        check(SpendBudget.warnings("P", "p", Limit(10.0, 0), rows, rows, prices).single().contains("reached"), "dollar warning")
        check(SpendBudget.warnings("Q", "q", Limit(0.0, 4_000_000), rows, rows, prices).single().contains("token limit reached"), "token warning")
        check(SpendBudget.warnings("Q", "q", Limit(0.0, 50_000_000), rows, rows, prices).isEmpty(), "quiet")
        check(SpendBudget.warnings("Q", "q", Limit(), rows, rows, prices).isEmpty(), "off")
        check(SpendBudget.parse("25 200k") == Limit(25.0, 200_000), "parse both")
        check(SpendBudget.parse("- 1.5m") == Limit(0.0, 1_500_000), "parse tokens")
        check(SpendBudget.parse("$25") == Limit(25.0, 0) && SpendBudget.parse("") == Limit() && SpendBudget.parse("0") == Limit(), "parse simple")
        check(SpendBudget.parse("abc") == null && SpendBudget.parse("1 2 3") == null && SpendBudget.parse("-3") == null, "parse junk")
        val m = mapOf("p" to Limit(10.0, 0), "q" to Limit(0.0, 100_000), "r" to Limit())
        check(SpendBudget.decode(SpendBudget.encode(m)) == m.filterValues { !it.isOff }, "roundtrip")
        if (f.isNotEmpty()) throw AssertionError(f.joinToString("; "))
    }
}
