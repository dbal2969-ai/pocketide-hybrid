package dev.pocketide.app

import dev.pocketide.app.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HabitsTest {
    @Test fun questionsAreAnsweredNotActedOn() {
        // the person's own messages that were questions
        for (q in listOf("What is that", "What is it doing?", "Command to delete backup-spawns?", "Is that the current build?",
            "What exactly does lsp do?", "How do I know if it is working? I see typescript in the one tap downloads, but I don't see pyright",
            "Does the assistant see that they made a mistake or that there is a problem that needs addressed?",
            "Unrelated to the server:  how can we make the app assistant more like talking with you? Can we analyze every prompt I've given you?",
            "What do I do with game preview fix?", "Can you explain why the map is cut off?", "Can we analyze why the build is slow?", "How can we make it faster?"))
            assertTrue(q, Habits.isQuestion(q))
        // and ones that asked for work
        for (o in listOf("Play button just re-downloads it", "Keep working on the fix and push the release", "Fix the game to play just like osrs",
            "Can you add dark mode?", "Could you fix the login bug?", "Why is the preview blank? Fix it.", "Push tree sitter and then wire lsp as a second release",
            "Yes and also make it so the app can know its limitations", "Analyze my zip and the apk. Can you take it apart and give my app the ability to make it?", "1,2,3", "",
            // 1.5.170: "can we" asks for a change the way "can you" does (the owner's wording; the whole run's edits were refused)
            "Can we sort the leaderboard by score, highest first?", "Could we make the name field ignore spaces at the start and end of the name?",
            "Can we also make the chat without a project able to work on projects?"))
            assertFalse(o, Habits.isQuestion(o))
    }

    @Test fun writingTestsAsksForProofTheyCatchBugs() {
        val r = "src/money.js has no tests. Write tests in test/money.test.js (node:test) that would catch it if someone later broke split or addPercent. Don't change src/money.js."
        assertTrue(Habits.asksForTests(r))
        assertTrue(Habits.notes(r).contains("over a loop of many inputs"))
        assertFalse("running tests is not writing them", Habits.asksForTests("Run the tests and tell me what fails"))
        assertFalse(Habits.asksForTests("What do the tests check?"))
        assertFalse(Habits.notes("The save button doesn't work. Fix it.").contains(Habits.TESTS_NOTE))
    }

    @Test fun aBugReportStartingWithWhenIsWork() {
        for (o in listOf("When a user saves only theme.color, the theme's font and size disappear from their settings. Saved settings should only change what the user set.",
            "When I tap save the app crashes", "Where the list is empty the page shows NaN. It should show 0.", "When the timer ends nothing happens"))
            assertFalse(o, Habits.isQuestion(o))
        for (q in listOf("When does the build run", "Where is the tax rate set", "When I tap save, does it upload?", "When is it called?", "When the timer ends, what happens?"))
            assertTrue(q, Habits.isQuestion(q))
    }

    @Test fun observationsAreQuestionsUnlessTheyGiveAnOrder() {
        for (q in listOf("Found a critical indexing bug! The regions are indexed by x*64+z but the files use z*64+x", "I noticed the map is cut off at the edge",
            "Looks like it loads the region twice", "Seems the cache only has 3 regions", "FYI the server is down"))
            assertTrue(q, Habits.isQuestion(q))
        for (o in listOf("I noticed the button is blank, make it red", "Found a bug and fix it", "I found the problem. Fix it now", "Noticed it is slow, we need to cache the map",
            "I found a bug, please fix", "Looks like it fails, change the loader"))
            assertFalse(o, Habits.isQuestion(o))
    }

    @Test fun questionsGetAShortReadOnlyRun() {
        assertTrue(dev.pocketide.app.core.Rounds.QUESTION_ROUNDS in 6..24)
    }

    @Test fun readOnlyCommandsOnly() {
        assertTrue(Habits.readOnlyCommand("ls -la game"))
        assertTrue(Habits.readOnlyCommand("grep -rn dagannoth tests/"))
        assertFalse(Habits.readOnlyCommand("rm -rf .backup-spawns"))
        assertFalse(Habits.readOnlyCommand("echo x > a.txt"))
        assertTrue(Habits.readOnlyCommand("git status && cat package.json | head -20"))
        // Sandbox.risk calls these SAFE, but they run code or change state
        for (c in listOf("node script.js", "python3 build.py", "npm install", "sed -i s/a/b/ app.js", "find . -delete", "cat .env"))
            assertFalse(c, Habits.readOnlyCommand(c))
    }

    @Test fun notesFitTheRequest() {
        assertEquals(Habits.QUESTION_NOTE, Habits.notes("What is it doing?"))
        val bug = Habits.notes("Play button just re-downloads it")
        assertTrue(bug.contains(Habits.REPRODUCE_NOTE)); assertFalse(bug.contains(Habits.REUSE_NOTE))
        val osrs = Habits.notes("Fix the game to play just like osrs with everything looking and feeling and acting identical.")
        assertTrue(osrs.contains(Habits.REUSE_NOTE)); assertTrue(osrs.contains(Habits.SCOPE_NOTE))
        assertEquals("", Habits.notes("Change the title to Hello"))
        // the hard suite's wrong-guess task: the named function is fine
        val guess = Habits.notes("The checkout summary says \"Total: \$450.00\" for two loaves of bread. I think money() in src/format.js is broken. Can you fix it?")
        assertTrue(guess, guess.contains(Habits.REPRODUCE_NOTE) && guess.contains(Habits.GUESS_NOTE))
        assertFalse(bug.contains(Habits.GUESS_NOTE))
        assertFalse(Habits.notes("I think the title should say Hello").contains(Habits.GUESS_NOTE))
        // the hard suite's async-order task: symptoms without "broken" or "error"
        assertTrue(Habits.reportsProblem("Our import says it saved every row, but right after it the store is often missing rows, they come out in a random order, and a bad row is silently ignored. Fix saveAll in src/importer.js."))
        assertFalse(Habits.reportsProblem("Add the missing tests for the parser"))
    }

    @Test fun limitsOfferResearchOrSayHowToFindOut() {
        assertTrue(Habits.limits(web = true, autonomous = false).contains("ask whether to research it"))
        assertTrue(Habits.limits(web = true, autonomous = true).contains("look it up with web_search"))
        assertTrue(Habits.limits(web = false, autonomous = false).contains("turning on web research"))
        assertTrue(Prompts.toolsSystem(true).contains(Habits.VOICE))
        assertTrue(Prompts.system(false).contains(Habits.VOICE))
    }

    @Test fun claimsNeedARunningCheck() {
        assertEquals(listOf("All 6 play tests pass"), Proof.claims("All 6 play tests pass and node --check is clean."))
        assertTrue(Proof.claims("The game now loads and the goblin attacks work correctly.").isNotEmpty())
        assertTrue(Proof.claims("I changed the menu. I could not run it here.").isEmpty())
        val syntax = Proof.command("node --check game/main.js", 0, 3)
        assertFalse(syntax.runtime)
        assertFalse(Proof.runtimeSince(listOf(syntax), 3))
        val test = Proof.command("npm test", 0, 4)
        assertTrue(Proof.runtimeSince(listOf(syntax, test), 3))
        assertFalse(Proof.runtimeSince(listOf(Proof.command("npm test", 1, 4)), 3))   // a failed run proves nothing
        assertFalse(Proof.runtimeSince(listOf(test), 5))                              // ran before the last change
        assertTrue(Proof.nudge(listOf("it works"), listOf(syntax), 3).contains("`node --check game/main.js`"))
        assertTrue(Proof.isCheckCommand("python3 -m pytest")); assertFalse(Proof.isCheckCommand("npm install"))
        assertFalse(Proof.isCheckCommand("ls -la"))
    }

    @Test fun footerTellsWhatRan() {
        assertTrue(Proof.footer(emptyList(), 2).contains("none after the last change"))
        val f = Proof.footer(listOf(Proof.command("node --check a.js", 0, 1), Proof.command("node --check b.js", 0, 3)), 2)
        assertTrue(f, f.contains("`node --check b.js` ✓") && !f.contains("a.js") && f.contains("syntax only"))
        val g = Proof.footer(listOf(Proof.Check("play tests", false, 4, true), Proof.command("npm test", 0, 4)), 2)
        assertTrue(g, g.contains("play tests ✗") && g.contains("`npm test` ✓") && !g.contains("syntax only"))
    }

    @Test fun advisorPicksTheStrongestOtherProvider() {
        val keys = setOf("cloudflare", "gemini", "anthropic")
        assertEquals("anthropic", Advisor.bestOther("cloudflare", { it in keys }) { "m-$it" }!!.provider)
        assertEquals("gemini", Advisor.bestOther("anthropic", { it in keys }) { "m" }!!.provider)
        assertNull(Advisor.bestOther("cloudflare", { it == "cloudflare" }) { "m" })
        assertTrue(Advisor.SYSTEM.contains("reproduce a problem"))
    }

    @Test fun checkingManyItemsAgainstAReferenceUsesAScript() {
        val n = Habits.notes("Some levels in src/levels.js give the wrong score. docs/levels.md has the right formula for every level. " +
            "Find every level that doesn't match the table and fix it, without changing anything else.")
        assertTrue(n, n.contains(Habits.AUDIT_NOTE))
        assertTrue(Habits.audits("Check all prices in prices.csv against the spec"))
        assertFalse(Habits.audits("Make all the buttons match the theme"))
        assertFalse(Habits.audits("Fix the table layout on the home page"))
        assertFalse(Habits.notes("Does every level match the table in docs/levels.md?").contains(Habits.AUDIT_NOTE))
    }

    // 1.5.147: "should …, but … still …" is a problem report; a feature request with "but" is not
    @Test fun shouldButStillIsAProblemReport() {
        assertTrue(Habits.reportsProblem("Orders of 50 euros or more should ship free, but a 60-euro order still pays 4.95 for shipping. Fix it."))
        assertTrue(Habits.reportsProblem("The total still shows the old price after I change the quantity."))
        assertFalse(Habits.reportsProblem("Make the header blue. It should stay at the top, but keep the logo on the left."))
        assertFalse(Habits.reportsProblem("Add a settings page with a dark mode switch."))
        assertTrue(Habits.reportsProblem("The basket badge says \"1 items\" when there is one thing in the basket. Fix it."))
        assertFalse(Habits.reportsProblem("Make the button say \"Save\" and put it on the right."))
        assertTrue(Habits.reportsProblem("The leaderboard puts Ada (9) above Bo (10). The highest score should be first. Fix it."))
    }
}
