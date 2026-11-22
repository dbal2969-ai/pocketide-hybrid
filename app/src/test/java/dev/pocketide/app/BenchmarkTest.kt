package dev.pocketide.app

import dev.pocketide.app.core.FolderScope
import dev.pocketide.app.core.Requirements
import dev.pocketide.app.core.TestFirst
import org.junit.Test

/**
 * The release benchmark, offline half: ten real requests and how the app must treat each one (does it get a
 * requirements list, is a folder limit picked up, is it a bug fix that needs a test first, is it research).
 * The phone half is in BENCHMARKS.md. A change that breaks any row here must not ship.
 */
class BenchmarkTest {
    private class Case(val name: String, val text: String, val research: Boolean = false, val followUp: Boolean = false, val folder: String? = null, val bugFix: Boolean = false)

    private val dirs = listOf("Runescape 2009 Clone", "src", "notes")

    private val cases = listOf(
        Case("RuneScape clone in a folder", "Build a full RuneScape 2009 clone as a playable browser game. Only work in the \"Runescape 2009 Clone\" folder. Include skills, combat, a map, inventory and a bank.", folder = "Runescape 2009 Clone"),
        Case("Snake game", "Make a snake game in one HTML file with a score and a restart button."),
        Case("To-do app", "Create a to-do list app with add, complete, delete and local storage."),
        Case("Bug fix", "Fix the bug where the score does not reset after game over.", bugFix = true),
        Case("Research only", "Find resources for learning pixel art and list the best five.", research = true),
        Case("Bare follow-up", "continue", followUp = true),
        Case("Short follow-up", "now make it fully built", followUp = false),
        Case("Stay-in-folder wording", "Keep everything in the src folder and add a settings page.", folder = "src"),
        Case("Outside wording", "Add a notes page, and don't touch anything outside the notes folder.", folder = "notes"),
        Case("Plain question", "How does the game loop work in main.js?")
    )

    @Test fun benchmark() {
        val f = ArrayList<String>()
        for (c in cases) {
            if (Requirements.isResearch(c.text) != c.research) f.add("${c.name}: research expected ${c.research}")
            if (Requirements.isFollowUp(c.text) != c.followUp) f.add("${c.name}: follow-up expected ${c.followUp}")
            val folder = FolderScope.detect(c.text, dirs)
            if (folder != c.folder) f.add("${c.name}: folder expected ${c.folder} got $folder")
            if (TestFirst.isBugFix(c.text) != c.bugFix) f.add("${c.name}: bug fix expected ${c.bugFix}")
        }
        // a short follow-up keeps the named game from the earlier big request
        val joined = Requirements.task(cases[0].text, "I want a fully built game")
        if (!joined.contains("RuneScape") || !joined.contains("Runescape 2009 Clone")) f.add("follow-up lost the original request")
        // writes: inside allowed, outside refused, in the file tools and the shell
        val scope = "Runescape 2009 Clone"
        if (!FolderScope.allows(scope, "Runescape 2009 Clone/index.html")) f.add("inside write refused")
        if (FolderScope.allows(scope, "index.html")) f.add("outside write allowed")
        if (FolderScope.commandViolation(scope, "echo hi > emberwilde/readme.md") == null) f.add("shell write outside allowed")
        if (FolderScope.commandViolation(scope, "cd 'Runescape 2009 Clone' && npm test") != null) f.add("shell inside refused")
        if (f.isNotEmpty()) throw AssertionError(f.joinToString("; "))
    }
}
