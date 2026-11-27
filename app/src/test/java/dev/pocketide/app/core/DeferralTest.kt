package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeferralTest {
    @Test fun anAnswerThatStopsAtTheNextStep() {
        assertTrue(Deferral.stopsShort("The tax rate is set in the file src/tax.js. To find the current rate, I should look at this file."))
        assertTrue(Deferral.stopsShort("The file that sets the tax rate is tax.js, and the rate is not known. To find the rate, we need to read the file."))
        assertTrue(Deferral.stopsShort("I updated cart.js. Next, I'll run the tests to confirm."))
        assertTrue(Deferral.stopsShort("Let me check the console output."))
        assertTrue(Deferral.stopsShort("The tax rate is set in the file `src/tax.js`. To find the exact value, we can read this file."))
        assertTrue(Deferral.stopsShort("The file that sets the tax rate is src/tax.js. \nread_file(\"src/tax.js\")"))
        // Llama 3.3 on the hard suite (1.5.89)
        assertTrue(Deferral.stopsShort("The total is wrong for discounted items. I'll take a look at the total function."))
        assertTrue(Deferral.stopsShort("Let's have a closer look at src/cart.js."))
        assertTrue(Deferral.stopsShort("Since the search did not return any results, I will try to find the function that handles project deletion using the repo_map function."))
        assertTrue(Deferral.stopsShort("To address the report, we need to keep requests inside public/. First, let's list all the files in the project to see which ones might be relevant."))
    }

    @Test fun aFinishedAnswerOrAnOfferIsLeftAlone() {
        assertFalse(Deferral.stopsShort("src/tax.js sets the tax rate: TAX_RATE = 0.08 (8%)."))
        assertFalse(Deferral.stopsShort("Fixed the discount. node test.js prints ok. You should run it on your phone too."))
        assertFalse(Deferral.stopsShort("Done. If you want, I can run the tests as well."))
        assertFalse(Deferral.stopsShort("Fixed. You will find the new option in tools/stats.py."))
        assertFalse(Deferral.stopsShort("Fixed the three levels. Let me list the changes:\n- level 58\n- level 237\n- level 281"))
        assertFalse(Deferral.stopsShort(""))
        // only the closing words count: a plan stated early and then carried out is fine
        assertFalse(Deferral.stopsShort("I should read cart.js first. " + "x".repeat(400) + " The total is 4.50."))
    }

    @Test fun aChangeLeftForLater() {
        assertTrue(Deferral.stopsBeforeChange("The test expects 0 pages. To fix this, I will change the line where totalPages is calculated."))
        assertTrue(Deferral.stopsBeforeChange("Let me fix the formula in src/levels.js."))
        assertFalse(Deferral.stopsBeforeChange("Changed the formula in src/levels.js; node test.js passes."))
        assertFalse(Deferral.stopsBeforeChange("Done. If you want, I can add a test as well."))
        // Llama 3.3 70B, hard suite (1.5.90 candidate): the tool named on the way to the change
        assertTrue(Deferral.stopsBeforeChange("The cause is an incorrect calculation of totalPages.\nNext, I will use the edit_file function to fix the bug in the paginate.js file."))
        assertTrue(Deferral.stopsShort("Next, I will use the read_file tool to look at src/auth/permissions.js."))
        assertFalse(Deferral.stopsShort("I used the read_file tool to look at src/auth/permissions.js; the message is \"Guests can only view projects.\""))
    }

    @Test fun aReplyThatEndsAtWhatItAnnounces() {
        // Llama 3.3 70B, hard suite (1.5.90 candidate): the fix it had found, then nothing
        assertEquals("Here is the change:", Deferral.announced("This line should be `const start = (page - 1) * size;`.\nHere is the change:"))
        assertEquals("**Here's the updated test file:**", Deferral.announced("Let's use the assert module.\n\n**Here's the updated test file:**\n"))
        assertNotNull(Deferral.announced("I will apply the fix below:"))
        assertNull(Deferral.announced("Here is the change:\n```js\nconst start = (page - 1) * size;\n```"))
        assertNull(Deferral.announced("Fixed it. Where to go next: nothing, the tests pass."))
        assertNull(Deferral.announced("Fixed paginate; npm test passes."))
    }

    @Test fun theFileANamedReadIsFound() {
        val files = setOf("src/checkout.js", "src/format.js", "src/auth/permissions.js", "README.md")
        // Llama 3.3 70B, hard suite (1.5.90 candidate), after the one-time ask
        assertEquals("src/checkout.js", Deferral.fileToRead("The cause is in the total() function in src/checkout.js. Let's read that to find the problem.", files))
        assertEquals("src/auth/permissions.js", Deferral.fileToRead("The function that rejects the request is requirePermission in src/auth/permissions.js. To find the exact " +
            "error message, we need to look at the code that handles permission checks.\nNext, I will use the read_file function to look at the definitions in the src/auth/permissions.js file.", files))
        assertEquals("src/format.js", Deferral.fileToRead("Let me open `./src/format.js`.", files))
        // the last file named that exists wins; one that is not in the project is skipped
        assertEquals("src/format.js", Deferral.fileToRead("money() in src/format.js may be wrong. I should look at src/missing.js and src/format.js.", files))
        assertNull(Deferral.fileToRead("I should look at src/missing.js.", files))
        // a run, not a read; a finished answer
        assertNull(Deferral.fileToRead("I updated src/checkout.js. Next, I'll run the tests to confirm.", files))
        assertNull(Deferral.fileToRead("src/checkout.js multiplies by 100 twice; fixed it there.", files))
    }

    @Test fun aFileNamedWithoutItsFolderIsFoundWhenOnlyOneMatches() {
        val files = setOf("src/paginate.js", "test/paginate.test.js", "src/a/index.js", "src/b/index.js")
        // Llama 3.3 70B, hard suite (1.5.90 candidate): the second stop named the test file without its folder
        assertEquals("test/paginate.test.js", Deferral.fileToRead("The cause of the failing test is an AssertionError in the paginate.test.js file. " +
            "To fix it, I will first read the paginate.test.js file to understand the test cases.", files))
        assertNull(Deferral.fileToRead("I will read index.js next.", files))
        assertNull(Deferral.fileToRead("Let me open paginate.test.js.", files) { it != "test/paginate.test.js" })
    }

    /** 1.5.211: the snake game in the judges' build stopped after index.html, at "Now I need to create the JavaScript logic in app.js". */
    @Test fun workNamedAfterChangesIsLeft() {
        assertTrue(Deferral.workLeft("I created index.html with the canvas and the score. Now I need to create the JavaScript logic in app.js."))
        assertTrue(Deferral.workLeft("The page is ready. Next I will build the game loop in game.js."))
        assertFalse(Deferral.workLeft("The snake game is done: index.html and app.js. Open the Preview tab to play."))
        assertFalse(Deferral.workLeft("Done. You could add a high score list next."))
    }
}
