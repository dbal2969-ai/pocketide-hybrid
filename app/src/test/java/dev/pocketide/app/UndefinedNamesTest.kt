package dev.pocketide.app

import dev.pocketide.app.bench.NodeNames
import dev.pocketide.app.core.UndefinedNames
import dev.pocketide.app.core.UndefinedNames.Use
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class UndefinedNamesTest {
    private fun names(path: String, text: String): List<String> {
        assumeTrue("needs Node", NodeNames.available)
        return UndefinedNames.parse(NodeNames.run(path, text)).map { it.name }
    }

    @Test fun findsTheNamesThatBrokeARealGame() {
        // the inventory tap (every tap threw "FOOD is not defined") and the fishing spots (never drawn) from a real project
        val src = """
            import { useFood, foodList } from './consumables.js';
            export function onTap(inv, i, sid) { const cooked = FOOD[sid]?.cook; const food = foodList(); if (food[sid]) useFood(inv, i); return cooked; }
            export function spots(groups, v3) { for (const g of groups) { (async () => { await ensureNpcPic(g.picId); g.sprite = await v3.addSprite(g.picId); })(); } }
        """.trimIndent()
        assertEquals(listOf("FOOD", "ensureNpcPic"), names("game/main.js", src))
    }

    @Test fun declarationsOfEveryKindCount() {
        val src = """
            import { a as b } from './x.js';
            const { c, d: [e = 1], ...g } = b; let h;
            function k(p = c, { q } = {}, ...r) { return p + q + r.length + arguments.length + hoisted(); }
            function hoisted() { return later; }
            const later = 1;
            class Z { #p = 1; m() { return this.#p + k(); } static s = Z.name; }
            label: for (const i of [1]) { if (i) continue label; else break label; }
            try { k(); } catch ({ message }) { console.log(message, typeof maybeThere); }
            const o = { e, [h]: 1, f: () => window.thing, g() { return o.e; } };
            export { o };
            new Image(); document.body.append(String(performance.now())); requestAnimationFrame(() => {});
            if (typeof maybeThere !== 'undefined') maybeThere();
        """.trimIndent()
        assertEquals(emptyList<String>(), names("a.js", src))
        assertEquals(listOf("Base", "tooBad"), names("b.js", "export class Y extends Base {}\ntooBad(1);"))
    }

    @Test fun scriptsMayMakeGlobalsByAssigning() {
        assertEquals(emptyList<String>(), names("s.js", "score = 0;\nfunction add() { score++; return score; }"))
        // a module is strict: the same assignment throws
        assertEquals(listOf("score"), names("m.mjs", "score = 0;\nexport const s = () => score;"))
        assertEquals(emptyList<String>(), names("n.js", "const fs = require('fs'); module.exports = { dir: __dirname, env: process.env };"))
        assertEquals(emptyList<String>(), names("t.test.js", "describe('x', () => { it('y', () => { expect(1).toBe(1); }); });"))
    }

    @Test fun otherFilesCanDefineAScriptsGlobals() {
        val files = mapOf(
            "index.html" to "<script src=\"util.js\"></script><script src=\"game.js\"></script>",
            "util.js" to "function clamp(v, a, b) { return Math.max(a, Math.min(b, v)); }",
            "game.js" to "clamp(1, 0, 2); missing(); window.hud = 1;",
            "boot.js" to "window.helper = () => 1;")
        val uses = listOf(Use("clamp", 1), Use("missing", 1), Use("helper", 2))
        val fresh = UndefinedNames.fresh("game.js", files.getValue("game.js"), uses, null, files.keys) { files[it] }
        assertEquals(listOf("missing"), fresh.map { it.name })
        // only what this write added: a name already undefined before the change is left alone
        assertEquals(emptyList<Use>(), UndefinedNames.fresh("game.js", files.getValue("game.js"), uses, listOf(Use("missing", 3)), files.keys) { files[it] })
    }

    @Test fun pagesThatLoadLibrariesHoldTheCheckBack() {
        val files = mapOf(
            "index.html" to "<script src=\"https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.min.js\"></script><script type=\"module\" src=\"main.js\"></script><script src=\"sketch.js\"></script>",
            "main.js" to "import './x.js';\nconst s = new THREE.Scene(); helper();",
            "sketch.js" to "function setup() { createCanvas(200, 200); }")
        assertTrue(UndefinedNames.unseenGlobals(files.keys) { files[it] })
        // a module still reports lower-case names (a library global starts with a capital); a classic script reports nothing
        assertEquals(listOf("helper"), UndefinedNames.fresh("main.js", files.getValue("main.js"), listOf(Use("THREE", 2), Use("helper", 2)), null, files.keys) { files[it] }.map { it.name })
        assertEquals(emptyList<Use>(), UndefinedNames.fresh("sketch.js", files.getValue("sketch.js"), listOf(Use("createCanvas", 1)), null, files.keys) { files[it] })
    }

    @Test fun theNoteSaysWhereANameIsDeclared() {
        val files = mapOf("game/consumables.js" to "export const FOOD = {};", "game/main.js" to "FOOD[1]; nowhere();")
        assertEquals("game/consumables.js", UndefinedNames.definedIn("FOOD", "game/main.js", files.keys) { files[it] })
        assertNull(UndefinedNames.definedIn("nowhere", "game/main.js", files.keys) { files[it] })
        val note = UndefinedNames.note("game/main.js", listOf(Use("FOOD", 1), Use("nowhere", 1))) { UndefinedNames.definedIn(it, "game/main.js", files.keys) { f -> files[f] } }
        assertTrue(note, note.contains("`FOOD` (game/main.js line 1) — declared in game/consumables.js") && note.contains("`nowhere` (game/main.js line 1) — defined nowhere"))
        assertEquals(listOf(Use("FOOD", 245)), UndefinedNames.parse("\"FOOD 245\""))
        assertEquals(emptyList<Use>(), UndefinedNames.parse("OK")); assertEquals(emptyList<Use>(), UndefinedNames.parse("SKIP"))
        assertTrue(UndefinedNames.checks("game/main.js")); assertTrue(!UndefinedNames.checks("game/engine/three.module.min.js"))
    }
}
