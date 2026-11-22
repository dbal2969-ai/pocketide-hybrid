package dev.pocketide.app

import dev.pocketide.app.core.AssetEnv
import dev.pocketide.app.addon.osrs.CacheTool
import dev.pocketide.app.core.Design
import dev.pocketide.app.core.GameAssets
import java.io.File
import dev.pocketide.app.core.PageLook
import dev.pocketide.app.core.PageSteps
import dev.pocketide.app.core.PlayTests
import org.junit.Assert.*
import org.junit.Test

class PlayTestsTest {
    @Test fun checksAndScriptsParseAndKeepTheirSemicolons() {
        val p = PageSteps.parse("tap #start; until window.__game && window.__game.ready; run window.__game.tapTile(3, 4); var a = 1\nexpect document.title.length > 0; until 3000 x === 1; tap text:New game")
        assertNull(p.error)
        assertEquals(listOf("tapel", "until", "run", "expect", "until", "tapel"), p.steps.map { it.kind })
        assertEquals("window.__game.tapTile(3, 4); var a = 1", p.steps[2].text)   // the ';' inside JavaScript stays
        assertEquals(3000, p.steps[4].ms); assertEquals("x === 1", p.steps[4].text); assertEquals(5000, p.steps[1].ms)
        assertEquals("text:New game", p.steps[5].text)
        assertEquals(3, p.checks)
        // the natural form models write; before this it was tried as a CSS selector and matched nothing
        assertEquals("text:Say hi", PageSteps.parse("tap \"Say hi\"").steps.single().text)
        assertEquals("text:Add", PageSteps.parse("click 'Add'").steps.single().text)
        assertEquals("#add-btn", PageSteps.parse("tap #add-btn").steps.single().text)
        assertEquals("location.reload()", PageSteps.parse("run JS location.reload()").steps.single().text)
        assertEquals("jsonify()", PageSteps.parse("run jsonify()").steps.single().text)   // only a separate word is dropped
        assertTrue(PageSteps.parse("expect").error!!.contains("expression"))
        assertTrue(PageSteps.parse("until 2000").error!!.contains("wait for"))
        assertTrue(PageSteps.script(p.steps).contains("window.__pt"))
    }

    @Test fun resultsBecomeChecksAndDecideTheVerdict() {
        val raw = "\"[{\\\"s\\\":\\\"expect a\\\",\\\"ok\\\":true,\\\"v\\\":\\\"true\\\"},{\\\"s\\\":\\\"until b\\\",\\\"ok\\\":false,\\\"v\\\":\\\"still false after 5000 ms\\\"}]\""
        val c = PageSteps.results(raw)!!
        assertEquals(2, c.size); assertTrue(c[0].ok); assertFalse(c[1].ok)
        assertNull(PageSteps.results("\"\""))   // still running
        val look = mapOf<String, Any?>("w" to 360, "h" to 640)
        val s = PageLook.summary(look, emptyList(), "", c)
        assertTrue(s, s.startsWith("VERDICT: FAIL - 1 of 2 checks failed"))
        assertTrue(s.contains("Checks (1 of 2 passed):") && s.contains("- FAIL until b -> still false after 5000 ms"))
        assertTrue(PageLook.summary(look, emptyList(), "", listOf(c[0])).startsWith("VERDICT: PASS - all 1 check held"))
    }

    @Test fun savedTestsParsePickAndReport() {
        val text = "# walking\ntap 200 300\nuntil window.__game.me.path.length === 0\n\n# chopping a tree\npage: game/index.html\nrun window.__game.tapTile(1, 2)\nexpect window.__game.inv.count(1511) > 0\n# looks\nwait 500\n"
        val t = PlayTests.parse(text)
        assertEquals(listOf("walking", "chopping a tree", "looks"), t.map { it.name })
        assertEquals("game/index.html", t[1].page)
        assertEquals(listOf("chopping a tree"), PlayTests.pick(t, "chop").map { it.name })
        assertEquals(3, PlayTests.pick(t, "all").size)
        assertEquals(listOf("\"looks\" has no expect or until line, so it can never fail: add a check of what the feature must do"), PlayTests.problems(text))
        val pass = "VERDICT: PASS - all 1 check held.\nChecks (1 of 1 passed):\n- PASS until x -> true after 300 ms\nScreen: 360x640"
        val fail = "VERDICT: FAIL - 1 of 1 checks failed\nChecks (0 of 1 passed):\n- FAIL expect y -> false\nScreen: 360x640\nConsole errors and missing files:\n- TypeError: boom (main.js:3)"
        val s = PlayTests.summary(listOf("walking" to pass, "chopping a tree" to fail))
        assertTrue(s, s.startsWith("PLAY TESTS: FAIL - 1 of 2 saved tests"))
        assertTrue(s.contains("## chopping a tree\nVERDICT: FAIL") && s.contains("- TypeError: boom") && !s.contains("Screen:"))
        assertTrue(PlayTests.summary(listOf("walking" to pass)).startsWith("PLAY TESTS: PASS"))
    }

    @Test fun buildRequestsGetTheDesignSteps() {
        val skyrim = "make me a game that is 3d, open world with a top level world, medeval villages (some building with multiple floors) various creatures inhabiting the world. dungeons with creatures like trolls, imps, dragons, bandits, vampires etc. Quests. Include a multibranched leveling hierarchy. Make it like Skyrim."
        assertTrue(Design.wanted(skyrim))
        assertTrue(Design.wanted("build a habit tracker app with streaks"))
        assertFalse(Design.wanted("why does my game crash when I jump?"))
        assertFalse(Design.wanted("fix the bug in the score counter"))
        assertFalse(Design.wanted("hello"))
        val n = Design.note(skyrim)
        assertTrue(n.contains("PLAN.md") && n.contains("tests/play.txt"))
        val asks = Design.asks(skyrim).joinToString("|").lowercase()
        for (w in listOf("dungeons", "quests", "trolls", "imps", "dragons")) assertTrue("$w in $asks", asks.contains(w))
        assertEquals("rpg3d", Design.kitFor(skyrim)); assertTrue(n.contains("kind=rpg3d"))
        assertEquals("platformer", Design.kitFor("make a mario style platformer game"))
        assertNull(Design.kitFor("make me runescape with quests and dungeons"))
        assertEquals("crud", Design.kitFor("build a habit tracker app with streaks"))
        assertEquals("landing", Design.kitFor("make a website for my bakery with a contact form"))
        assertEquals("dashboard", Design.kitFor("create an analytics dashboard with charts of my sales"))
        assertEquals("store", Design.kitFor("build an online shop for my plants with a cart"))
        assertEquals("form", Design.kitFor("make a quiz game about space"))
        assertEquals("notes", Design.kitFor("I want a journal app to write notes"))
        assertEquals("booking", Design.kitFor("make a booking calendar for my salon"))
        assertEquals("recipe", Design.kitFor("build a recipe box with a servings scaler"))
        assertEquals("chat", Design.kitFor("make a group chat for my team"))
        assertEquals("assistant", Design.kitFor("build an AI assistant app"))
        assertEquals("rpg3d", Design.preflight(skyrim, listOf("README.md")))
        assertNull(Design.preflight(skyrim, listOf("index.html")))          // something already runs: build on it, not over it
        assertNull(Design.preflight("fix the bug in my tracker app", emptyList()))
        assertEquals("app", Design.folderFor("crud")); assertEquals("game", Design.folderFor("rpg3d"))
        assertTrue(Design.builtNote("crud", "app", "Wrote the crud app kit to app.").contains("Do not scaffold again"))
    }

    @Test fun theOpenWorldKitIsWrittenWithItsDataAndTests() {
        val root = java.nio.file.Files.createTempDirectory("rpg").toFile()
        val tests = "# the world loads\nuntil window.__game\n# walking\nkey ArrowUp 500\nexpect window.__game.moved() > 1\n"
        val e = AssetEnv(resolve = { File(root, it) }, put = { p, b -> File(root, p).also { it.parentFile.mkdirs() }.writeBytes(b); true },
            read = { p -> File(root, p).takeIf { it.isFile }?.readBytes() }, files = { emptyList() },
            asset = { n -> (if (n == "templates/rpg3d/play.txt") tests else "// $n").toByteArray() })
        val r = GameAssets.run(mapOf("action" to "scaffold", "kind" to "rpg3d", "out" to "mygame"), e)
        assertTrue(r, r.startsWith("Wrote the open-world 3D RPG kit to mygame"))
        for (f in listOf("index.html", "main.js", "design.json", "data/content.json", "content.js", "inv.js", "skills.js", "core/worldgen.js", "core/creatures.js", "core/skilltree.js", "core/noise.js", "core/input.js", "engine/three.module.min.js"))
            assertTrue(f, File(root, "mygame/$f").isFile)
        // the play tests go to the project root and name the game's page
        assertEquals(listOf("mygame/index.html", "mygame/index.html"), PlayTests.parse(File(root, PlayTests.FILE).readText()).map { it.page })
        // a second scaffold keeps the person's own design, content and tests
        File(root, "mygame/design.json").writeText("{\"title\":\"Mine\"}"); File(root, PlayTests.FILE).writeText("# mine\nexpect true\n")
        GameAssets.run(mapOf("action" to "scaffold", "kind" to "rpg3d", "out" to "mygame"), e)
        assertEquals("{\"title\":\"Mine\"}", File(root, "mygame/design.json").readText()); assertEquals("# mine\nexpect true\n", File(root, PlayTests.FILE).readText())
        val kits = GameAssets.run(mapOf("action" to "kits"), e)
        assertTrue(kits, kits.contains("- rpg3d: open-world") && kits.contains("- platformer"))
        val b = GameAssets.run(mapOf("action" to "blocks", "files" to "worldgen.js", "out" to "other"), e)
        assertTrue(b, b.contains("core/worldgen.js") && b.contains("core/noise.js") && File(root, "other/core/noise.js").isFile)
    }
}
