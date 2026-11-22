package dev.pocketide.app

import dev.pocketide.app.core.FolderScope
import dev.pocketide.app.core.Requirements
import org.junit.Test

class FolderScopeTest {
    @Test
    fun folderScopeChecks() {
        FolderScopeSelfTest.runAll()
    }
}

object FolderScopeSelfTest {
    private var fails = 0
    private var passes = 0

    private fun ok(c: Boolean, m: String) {
        if (c) passes++ else fails++
        println((if (c) "PASS  " else "FAIL  ") + m)
    }

    fun runAll() {
        detecting(); allowing(); messages(); requirements()
        println("\n$passes passed, $fails failed")
        check(fails == 0) { "$fails folder scope checks failed" }
    }

    private val dirs = listOf("Runescape 2009 Clone", "src", "src/ui", "tmp")

    private fun detecting() {
        ok(FolderScope.detect("Only working in the \"Runescape 2009 Clone\" folder i want you to find resources", dirs) == "Runescape 2009 Clone", "the exact words from the real request are understood")
        ok(FolderScope.detect("work only inside the src folder please", dirs) == "src", "work only inside X folder")
        ok(FolderScope.detect("Stay within 'src/ui' directory", dirs) == "src/ui", "stay within a nested folder")
        ok(FolderScope.detect("keep everything in the runescape 2009 clone folder", dirs) == "Runescape 2009 Clone", "matching ignores case and returns the real folder name")
        ok(FolderScope.detect("Do not touch files outside the src folder", dirs) == "src", "do not touch files outside X")
        ok(FolderScope.detect("restrict all work to the Game folder", dirs) == "Game", "a folder that does not exist yet is accepted as typed")
        ok(FolderScope.detect("make a snake game in the src folder", dirs) == null, "a plain mention without 'only' is not a rule")
        ok(FolderScope.detect("build a todo app", dirs) == null, "no folder means no rule")
        ok(FolderScope.detect("only in the ../etc folder", dirs) == null, "parent paths are never accepted")
    }

    private fun allowing() {
        ok(FolderScope.allows("", "anything/at/all.txt"), "no scope allows everything")
        ok(FolderScope.allows("src", "src/main.js") && FolderScope.allows("src", "./src/a/b.js") && FolderScope.allows("src/", "src/x"), "files inside the folder are allowed")
        ok(!FolderScope.allows("src", "emberwind.html") && !FolderScope.allows("src", "srcother/x.js") && !FolderScope.allows("src", "AGENTS.md"), "files outside, and lookalike names, are refused")
        ok(!FolderScope.allows("src", "src/../other.txt".replace("src/../", "")), "a path that escapes to the root is refused")
        ok(FolderScope.allows("Runescape 2009 Clone", "Runescape 2009 Clone/index.html"), "folders with spaces work")
    }

    private fun messages() {
        val r = FolderScope.refusal("src", "game.html")
        ok(r.contains("Refused") && r.contains("src/game.html"), "the refusal names a path inside the folder")
        ok(FolderScope.note("src").contains("starts with \"src/\"") && FolderScope.note("").isEmpty(), "prompt note only when a folder is set")
        ok(FolderScope.label("src/") == "Working only in: src", "chip label")
    }

    private fun requirements() {
        ok(Requirements.request("make a game", "src").contains("inside the folder \"src\""), "the list must include the folder rule")
        ok(!Requirements.request("make a game").contains("inside the folder"), "no rule, no line")
        ok(Requirements.work("t", listOf("a"), "src").contains("Work only inside the folder \"src\""), "the work message repeats the rule")
        ok(Requirements.check("t", listOf("a"), "src").contains("Folder rule"), "the check looks for files outside the folder")
        ok(Requirements.isResearch("I want you to find resources online to clone a playable game"), "a request to find resources is research")
        ok(!Requirements.isResearch("find the bug and fix it in the login code"), "find plus fix is not research")
        ok(!Requirements.isResearch("build me a snake game"), "building is not research")
        ok(Requirements.isFollowUp("Complete the task") && Requirements.isFollowUp("continue") && !Requirements.isFollowUp("I want a fully build game"), "follow-ups are recognised")
        val t = Requirements.task("Make a RuneScape 2009 clone in the Runescape folder", "I want a fully build game")
        ok(t.contains("RuneScape 2009 clone") && t.contains("fully build game"), "a short message is joined to the earlier request")
        ok(Requirements.task(null, "build x") == "build x" && Requirements.task("a", "b".repeat(200)) == "b".repeat(200), "no earlier request, or a long new one, stands alone")
        ok(!Requirements.wanted(true, false, false, true, "find resources online to clone a game", "find resources online to clone a game"), "research gets no list")
        ok(!Requirements.wanted(true, false, false, true, "Complete the task", "earlier\n\nComplete the task"), "a bare follow-up gets no list")
        ok(Requirements.wanted(true, false, false, true, "I want a fully build game", t), "a real build request joined to context gets a list")
    }
}
