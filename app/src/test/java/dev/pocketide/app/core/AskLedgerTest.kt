package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ask checklist (1.5.138). Requests here are written for this test, not taken from the benchmark's tasks. */
class AskLedgerTest {
    @Test fun countsAsksNotDescriptions() {
        assertEquals(1, AskLedger.count("Fix add in app.js so the test passes"))
        assertEquals(0, AskLedger.count("The cart total is wrong when a coupon is used."))
        // a symptom, then one ask
        assertEquals(1, AskLedger.count("The cart total is wrong when a coupon is used. Make the coupon take 10% off the subtotal."))
        // two asks joined by "and", plus a third sentence
        assertEquals(3, AskLedger.count("Make the coupon take 10% off and show the saving on the receipt. Don't let two coupons stack."))
        assertEquals(2, AskLedger.count("- add a dark mode toggle\n- save the choice in localStorage"))
        assertTrue(AskLedger.wanted("The export should include the date column. Also sort rows by date."))
        assertFalse(AskLedger.wanted("Why does the login page reload twice?"))
    }

    @Test fun readsTheListAndJudgesEachProof() {
        val reply = "All done.\n\nASKS\n1. take 10% off | src/cart.js:42 applies 0.9 to the subtotal\n2. show the saving | ran `node test.js`, printed \"saving: 2.00\"\n" +
            "3. no stacking | not done yet\n4. keep the old total line | \n"
        val lines = AskLedger.parse(reply)!!
        assertEquals(4, lines.size)
        assertEquals(listOf(true, true, false, false), lines.map { AskLedger.proven(it) })
        assertTrue(AskLedger.again(lines.filter { !AskLedger.proven(it) }).contains("- no stacking (you wrote: not done yet)"))
        assertNull(AskLedger.parse("Done: everything works."))
        assertEquals(1, AskLedger.parse("**ASKS:**\n1) one | a.js:3")!!.size)
    }

    @Test fun theAskNamesTheFormatAndTheRequest() {
        val a = AskLedger.ask("Add X and remove Y.")
        assertTrue(a.contains("ASKS\n1. <the ask"))
        assertTrue(a.contains("Request: \"Add X and remove Y.\""))
    }

    // 1.5.139: a file name in backticks shows nothing was run (1.5.138 counted it as a command); with :line after it, it is a path:line
    @Test fun aBacktickedPathAloneIsNotProof() {
        fun line(p: String) = AskLedger.Line("ask", p)
        assertFalse(AskLedger.proven(line("`src/receipt.js`")))
        assertFalse(AskLedger.proven(line("see `src/receipt.js` (Saving line inserted)")))
        assertTrue(AskLedger.proven(line("`src/receipt.js`:7-9")))
        assertTrue(AskLedger.proven(line("src/receipt.js:8")))
        assertTrue(AskLedger.proven(line("`node -e \"console.log(receipt(c))\"` printed Saving 1.00 then Total 10.80")))
        assertTrue(AskLedger.proven(line("ran `src/run.js` and it printed 2")))
    }

    // 1.5.139 (F1): the answer is the summary, never the bare list
    @Test fun theAnswerIsTheSummaryNotTheList() {
        val list = "ASKS\n1. fix add | app.js:2"
        assertEquals("Fixed add.", AskLedger.answer(list, "Fixed add.", preferReply = false))
        assertEquals("Fixed add.", AskLedger.answer("Let me list them.\n" + list, "Fixed add.", preferReply = false))
        assertEquals("All done now.", AskLedger.answer("All done now.\n\n" + list, "", preferReply = false))
        assertEquals("Added subtract too.", AskLedger.answer("Added subtract too.\n" + list, "Fixed add.", preferReply = true))
        assertEquals("Fixed add.", AskLedger.answer(list, "Fixed add.", preferReply = true))
        assertEquals("Done.", AskLedger.answer(list, "", preferReply = true))
    }

    @Test fun readsTheHelpersCount() {
        assertEquals(3, AskLedger.parseCount("3"))
        assertEquals(2, AskLedger.parseCount("There are 2 asks."))
        // 1.5.142: a numbered list counts its items, not its first number
        assertEquals(3, AskLedger.parseCount("1. Coupons come off the subtotal\n2. Show a Saving line\n3. Refuse a second coupon"))
        assertEquals(4, AskLedger.parseCount("**4**"))
        assertEquals(2, AskLedger.parseCount("- fix add\n- add subtract"))
        assertEquals(3, AskLedger.parseCount("The request has 2 parts, or 3 if you count the test."))
        assertNull(AskLedger.parseCount("several"))
        assertNull(AskLedger.parseCount("0"))
        assertTrue(AskLedger.countQuestion("Do X and Y").contains("\"Do X and Y\""))
    }

    // 1.5.140 (Copilot, ANALYSIS-1.5.139-ASKS G2): "unknown", "missing", "todo", "n/a" and "skipped" mean not done only when they are
    // the whole proof or are stated of the ask; in 1.5.139 a proof quoting "Unknown option: --jsno" was sent back
    @Test fun weakWordsCountOnlyWhenTheyAreTheProof() {
        fun line(p: String) = AskLedger.Line("ask", p)
        assertTrue(AskLedger.proven(line("src/cli.js:18-19; command `node -e \"main(['--jsno'])\"` printed `Unknown option: --jsno` and exit 2")))
        assertTrue(AskLedger.proven(line("src/validate.js:3 rejects a missing @; `node --test` passes")))
        assertTrue(AskLedger.proven(line("src/todo.js:12 moves done items to the end; `npm test` printed ok")))
        assertTrue(AskLedger.proven(line("src/ui.js:4, the skipped-row style")))
        assertFalse(AskLedger.proven(line("missing")))
        assertFalse(AskLedger.proven(line("N/A")))
        assertFalse(AskLedger.proven(line("- todo -")))
        assertFalse(AskLedger.proven(line("src/cli.js:20, the help line is still missing")))
        assertFalse(AskLedger.proven(line("src/form.js:8 but this part is skipped")))
        // the strong words still count anywhere
        assertFalse(AskLedger.proven(line("src/cli.js:20 not done yet")))
        assertFalse(AskLedger.proven(line("src/cli.js:20, I couldn't run it")))
    }
}
