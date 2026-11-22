package dev.pocketide.app

import dev.pocketide.app.addon.osrs.*

import dev.pocketide.app.core.*
import org.junit.Assert.*
import org.junit.Test

class GameCheckTest {
    private fun run(files: Map<String, String>): String =
        GameCheck.check(files.keys.toList(), { files[it] }, { (files[it]?.length ?: 0).toLong() })

    @Test fun cleanGameIsReady() {
        val r = run(mapOf("game/index.html" to "<script type=\"module\" src=\"main.js\"></script>", "game/main.js" to "import { x } from './render.js'; fetch('data/places/m1_1.json')",
            "game/render.js" to "export const x = 1;", "game/data/places/m1_1.json" to "{\"objects\":[[5,1,1,0,1,1,0]]}", "game/data/objects/5.png" to "x"))
        assertTrue(r, r.contains("reads correctly"))
    }
    @Test fun missingImportAndBadJsonAreReported() {
        val r = run(mapOf("g/main.js" to "import './missing.js'; fetch('data/maps/m1_1.json')", "g/data/floors.json" to "{ broken"))
        assertTrue(r, r.contains("missing.js") && r.contains("data/maps/m1_1.json") && r.contains("floors.json is not valid JSON"))
    }
    @Test fun placeWithoutPictureIsReported() {
        val r = run(mapOf("g/main.js" to "", "g/data/places/m1_1.json" to "{\"objects\":[[7,1,1,0,1,1,0]]}"))
        assertTrue(r, r.contains("without a picture"))
    }
    @Test fun wrongImportNamesAreReported() {
        val r = run(mapOf("g/index.html" to "<canvas id=\"c\"></canvas><script type=\"module\" src=\"main.js\"></script>",
            "g/main.js" to "import { View } from './view.js';\nimport { N, FULL, nope } from './world.js';\nimport Def from './world.js';\ndocument.getElementById('c'); document.getElementById('side');",
            "g/view.js" to "export class View3D {}", "g/world.js" to "export const N = 1, E = 2, FULL = 16;\nexport function wallBits() {}"))
        assertTrue(r, r.contains("imports 'View' from ./view.js") && r.contains("View3D"))
        assertTrue(r, r.contains("'nope'") && !r.contains("'FULL'") && r.contains("'default'"))
        assertTrue(r, r.contains("#side") && !r.contains("element #c,") && !r.contains("element #c."))
    }
    @Test fun cutOffScriptIsReported() {
        val r = run(mapOf("g/index.html" to "<script type=\"module\" src=\"main.js\"></script>",
            "g/main.js" to "async function boot() {\n  const s = '}';\n  // }\n  for (const u of x) { try { y(); } catch (e) {} }\n"))
        assertTrue(r, r.contains("main.js looks cut off") && r.contains("line 1"))
        assertNull(GameCheck.unclosed("const t = `a ${'{'} b`; function f() { return 1; }"))
    }
    @Test fun templatePathsMatchExportedFiles() {
        val ok = run(mapOf("g/main.js" to "fetch(`data/maps/m\${x}_\${z}.json`)", "g/data/maps/m50_50.json" to "{}"))
        assertFalse(ok, ok.contains("m\${x}"))
        val bad = run(mapOf("g/main.js" to "fetch(`data/maps/m\${x}_\${z}.json`)"))
        assertTrue(bad, bad.contains("no file in the project matches"))
    }
    @Test fun idsMadeInScriptsCount() {
        val r = run(mapOf("g/index.html" to "<script type=\"module\" src=\"main.js\"></script>",
            "g/main.js" to "const d = document.createElement('div'); d.id = 'statsv'; document.getElementById('statsv');"))
        assertFalse(r, r.contains("#statsv"))
    }
    @Test fun resolveStaysInsideFolder() {
        assertEquals("a/c.js", GameCheck.resolve("a/b", "../c.js"))
        assertNull(GameCheck.resolve("", "../x.js"))
    }
    @Test fun recipeNeedsCacheAndGameWords() {
        assertTrue(GameRecipe.wanted("make an osrs style game in lumbridge from the cache", true))
        assertFalse(GameRecipe.wanted("make a game from the cache", false))
        assertFalse(GameRecipe.wanted("fix the login page of my shop from the cache", true))
    }

    @Test fun unusedScriptIsFlagged() {
        val r = run(mapOf("g/index.html" to "<script type=module src=main.js></script>", "g/main.js" to "import './used.js';", "g/used.js" to "export const a = 1;", "g/old.js" to "const b = 2;"))
        assertTrue(r, r.contains("old.js is never loaded"))
        assertFalse(r.contains("used.js is never loaded"))
    }

    // The problems from the world streaming run (2026-10-29): each one broke the game while the earlier checks passed.
    @Test fun idsLookedUpThroughAHelperAreChecked() {
        val r = run(mapOf("g/index.html" to "<div id=\"side\"></div><script type=\"module\" src=\"ui.js\"></script>",
            "g/ui.js" to "function byId(id) { const e = document.getElementById(id); return e; }\nconst \$ = id => document.getElementById(id);\nbyId('side'); byId('p-bar'); \$('p-note');"))
        assertTrue(r, r.contains("#p-bar (with byId)") && r.contains("#p-note (with \$)") && !r.contains("#side"))
    }
    @Test fun aClassLookedUpInTheWrongModuleIsReported() {
        val r = run(mapOf("g/index.html" to "<script type=\"module\" src=\"main.js\"></script>",
            "g/main.js" to "const A = await getCtor('ActorManager', './content.js'); const W = await getCtor('World', './world.js');",
            "g/content.js" to "export class Content {}", "g/world.js" to "export class World {}"))
        assertTrue(r, r.contains("looks up 'ActorManager' in ./content.js") && r.contains("Content") && !r.contains("'World'"))
    }
    @Test fun aModuleNamedInAStringCountsAsLoaded() {
        val r = run(mapOf("g/index.html" to "<script type=\"module\" src=\"main.js\"></script>",
            "g/main.js" to "const A = await getCtor('ActorManager', './npcspawns.js');", "g/npcspawns.js" to "export class ActorManager {}"))
        assertFalse(r, r.contains("npcspawns.js is never loaded"))
    }
    @Test fun dataPathsOutsideTheDataFolderAreChecked() {
        val bad = run(mapOf("g/index.html" to "<script type=\"module\" src=\"main.js\"></script>",
            "g/main.js" to "this.enemies = await json('game/data/enemies.json') || [];", "g/data/enemies.json" to "[]"))
        assertTrue(bad, bad.contains("loads game/data/enemies.json, which is not in the project"))
        val fallback = run(mapOf("g/index.html" to "<script type=\"module\" src=\"main.js\"></script>",
            "g/main.js" to "this.enemies = await json('game/data/enemies.json') || await json('levels/enemies.json') || [];", "g/levels/enemies.json" to "[]"))
        assertFalse(fallback, fallback.contains("game/data/enemies.json"))
    }
    @Test fun thePreviewPageIsChecked() {
        val files = mapOf("index.html" to "<script src=\"engine/phaser.min.js\"></script><script type=\"module\" src=\"game/main.js\"></script>", "game/main.js" to "")
        assertEquals("index.html", GameCheck.entryPage(files.keys.toList()))
        val p = GameCheck.pageProblems("index.html", files.keys, { files[it] })
        assertEquals(1, p.size); assertTrue(p[0], p[0].contains("engine/phaser.min.js"))
    }
    @Test fun unusedFilesDoNotBlockFinishing() {
        val report = "Checked 3 files. 2 problem(s):\n- main.js uses region.js, which is not in the folder.\n- levels.js is never loaded by a page or script. Import it or delete it."
        assertEquals(listOf("- main.js uses region.js, which is not in the folder."), GameCheck.blocking(report))
    }
}
