package dev.pocketide.app.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Proves the real-project checks measure the job: each fails on the start, passes real fixes and rejects a shortcut (1.5.99). */
class RealProjectBenchTasksTest {
    companion object {
        @BeforeClass @JvmStatic fun tools() {
            assumeTrue("node is needed for the benchmark checks", try { ProcessBuilder("bash", "-c", "node --version").start().waitFor() == 0 } catch (e: Exception) { false })
        }
    }

    private fun run(name: String, change: (File) -> Unit): Pair<Int, String> {
        val t = (RealProjectBenchTasks.ALL + RealProjectBenchTasks.EQUIP_SLOT).single { it.name == name }
        val dir = Files.createTempDirectory("realproject").toFile()
        try {
            for ((p, text) in t.files) File(dir, p).also { it.parentFile.mkdirs() }.writeText(text)
            change(dir)
            File(dir, ".bench").mkdirs(); File(dir, ".bench/answer.txt").writeText("")
            val pr = ProcessBuilder("bash", "-c", t.check).directory(dir).redirectErrorStream(true).start()
            val out = pr.inputStream.bufferedReader().readText()
            if (!pr.waitFor(90, TimeUnit.SECONDS)) { pr.destroyForcibly(); return 124 to "timed out" }
            return pr.exitValue() to out
        } finally { dir.deleteRecursively() }
    }
    private fun passes(name: String, change: (File) -> Unit) { val (c, o) = run(name, change); assertEquals("$name should pass: $o", 0, c) }
    private fun fails(name: String, why: String, change: (File) -> Unit = {}) { val (c, o) = run(name, change); println("$name, $why: ${o.trim().take(300)}"); assertNotEquals("$name should fail when $why: $o", 0, c) }
    private fun File.edit(p: String, f: (String) -> String) = File(this, p).let { val before = it.readText(); val after = f(before); assertNotEquals("the test's own edit did nothing in $p", before, after); it.writeText(after) }

    @Test fun theProjectIsTheSizeOfARealOne() {
        val p = RealProjectBenchTasks.project
        assertTrue(p.size > 120)
        val main = p.getValue("game/main.js").lines().size
        assertTrue("main.js has $main lines", main in 900..1400)
        val names = RealProjectBenchTasks.ALL.map { it.name }.toSet()
        assertEquals(emptySet<String>(), names intersect (BenchTasks.ALL + HardBenchTasks.ALL.filter { it !== RealProjectBenchTasks.EQUIP_SLOT } + HeldOutBenchTasks.ALL).map { it.name }.toSet())
    }

    // 1.5.111: the page loads and calls the code the menu and save tasks are about; before, nothing loaded save.js or inv.js, and a
    // model that checked how the page runs (Haiku 5.5) rightly undid a correct fix because the file it changed never ran
    @Test fun thePageRunsTheCodeTheTasksAreAbout() {
        val p = RealProjectBenchTasks.project
        val page = p.getValue("game/index.html")
        assertTrue(page.indexOf("save.js") in 0 until page.indexOf("main.js") && page.indexOf("inv.js") in 0 until page.indexOf("main.js"))
        val main = p.getValue("game/main.js")
        assertTrue(main.contains("load(window.localStorage)") && main.contains("new Inventory(document)") && main.contains("bag.openMenu(") && main.contains("save(window.localStorage"))
        System.getenv("RP_OUT")?.let { d -> for ((f, text) in p) File(d, f).also { it.parentFile.mkdirs() }.writeText(text) }
    }

    @Test fun bankOption() {
        fails("rp-bank-option", "nothing was done")
        passes("rp-bank-option") { it.edit("game/main.js") { s -> s.replace("if (a === 'use' || a === 'open') { api.openBank(obj); return 'bank'; }", "if (a === 'use' || a === 'open' || a === 'bank') { api.openBank(obj); return 'bank'; }") } }
        fails("rp-bank-option", "every Bank option opens the bank") { it.edit("game/main.js") { s -> s.replace("  if (/^bank (booth|chest)$/.test(name)) {", "  if (a === 'bank') { api.openBank(obj); return 'bank'; }\n  if (/^bank (booth|chest)$/.test(name)) {") } }
        fails("rp-bank-option", "any option on a booth opens the bank") { it.edit("game/main.js") { s -> s.replace("    if (a === 'use' || a === 'open') { api.openBank(obj); return 'bank'; }", "    api.openBank(obj); return 'bank';") } }
    }

    @Test fun menuHidden() {
        fails("rp-menu-hidden", "nothing was done")
        passes("rp-menu-hidden") { it.edit("game/inv.js") { s -> s.replace("position:fixed;z-index:9;", "position:fixed;z-index:200;") } }
        passes("rp-menu-hidden") { it.edit("game/inv.js") { s -> s.replace("position:fixed;z-index:9;", "position:fixed;") } }
        fails("rp-menu-hidden", "the side panel is pushed down instead") { it.edit("game/style.css") { s -> s.replace("width: 200px; z-index: 100;", "width: 200px; z-index: 5;") } }
    }

    @Test fun savePosition() {
        fails("rp-save-position", "nothing was done")
        passes("rp-save-position") { it.edit("game/save.js") { s -> s.replace("  const p = s.position || START;", "  const p = s.pos || START;") } }
        fails("rp-save-position", "the save is changed to the key the load reads, so saves on phones are lost") { it.edit("game/save.js") { s -> s.replace("JSON.stringify({ pos: { x: me.x, z: me.z }", "JSON.stringify({ position: { x: me.x, z: me.z }") } }
        fails("rp-save-position", "the start is moved instead") { it.edit("game/save.js") { s -> s.replace("const START = { x: 3222, z: 3218 };", "const START = { x: 3185, z: 3443 };") } }
    }

    // 1.5.111: the harder tasks
    @Test fun fourAsks() {
        fails("rp-four-asks", "nothing was done")
        val chop = "  if (a === 'chop down' && /tree|oak|willow/.test(name)) { api.chop(obj); return 'chop'; }"
        val chopFixed = "  if (a === 'chop down' && /tree|oak|willow|maple/.test(name)) { api.say('You swing your axe at the tree.'); api.chop(obj); return 'chop'; }"
        val all: (File) -> Unit = { d ->
            d.edit("game/main.js") { it.replace(chop, chopFixed) }
            d.edit("game/save.js") { it.replace("pos: { x: me.x, z: me.z }", "pos: { x: me.x, z: me.z }, dir: me.dir || 'south'").replace("return { me: { x: p.x, z: p.z }", "return { me: { x: p.x, z: p.z, dir: s.dir || 'south' }") }
            d.edit("game/style.css") { it.replace("width: 200px;", "width: 240px;") }
        }
        passes("rp-four-asks", all)
        fails("rp-four-asks", "the panel is left as it was") { d ->
            d.edit("game/main.js") { it.replace(chop, chopFixed) }
            d.edit("game/save.js") { it.replace("pos: { x: me.x, z: me.z }", "pos: { x: me.x, z: me.z }, dir: me.dir || 'south'").replace("return { me: { x: p.x, z: p.z }", "return { me: { x: p.x, z: p.z, dir: s.dir || 'south' }") }
        }
        fails("rp-four-asks", "the facing is always south") { d ->
            d.edit("game/main.js") { it.replace(chop, chopFixed) }
            d.edit("game/save.js") { it.replace("return { me: { x: p.x, z: p.z }", "return { me: { x: p.x, z: p.z, dir: 'south' }") }
            d.edit("game/style.css") { it.replace("width: 200px;", "width: 240px;") }
        }
        fails("rp-four-asks", "chopping says nothing") { d ->
            d.edit("game/main.js") { it.replace(chop, chop.replace("willow", "willow|maple")) }
            d.edit("game/save.js") { it.replace("pos: { x: me.x, z: me.z }", "pos: { x: me.x, z: me.z }, dir: me.dir").replace("return { me: { x: p.x, z: p.z }", "return { me: { x: p.x, z: p.z, dir: s.dir || 'south' }") }
            d.edit("game/style.css") { it.replace("width: 200px;", "width: 240px;") }
        }
    }

    @Test fun fishing() {
        fails("rp-fishing", "nothing was done")
        val examine = "  if (a === 'examine') {"
        val fish = "  if (/fishing spot/.test(name)) {\n    if (a === 'net') { if (api.addItem(315)) api.say('You catch some shrimps.'); else api.say('Your inventory is too full to hold any more.'); return 'fish'; }\n    if (a === 'bait') { api.say('You need some bait.'); return 'fish'; }\n  }\n"
        passes("rp-fishing") { d -> d.edit("game/main.js") { it.replace(examine, fish + examine) } }
        fails("rp-fishing", "a full inventory is not handled") { d -> d.edit("game/main.js") { it.replace(examine, fish.replace("if (api.addItem(315)) api.say('You catch some shrimps.'); else api.say('Your inventory is too full to hold any more.');", "api.addItem(315); api.say('You catch some shrimps.');") + examine) } }
        fails("rp-fishing", "Net works on anything") { d -> d.edit("game/main.js") { it.replace(examine, fish.replace("if (/fishing spot/.test(name)) {", "{") + examine) } }
    }

    @Test fun firstSlot() {
        fails("rp-first-slot", "nothing was done")
        val bug = "bag.slots.slice(0, bag.slots.indexOf(null))"
        passes("rp-first-slot") { d -> d.edit("game/main.js") { it.replace(bug, "bag.slots.filter(s => s != null)") } }
        passes("rp-first-slot") { d -> d.edit("game/main.js") { it.replace(bug, "bag.slots") } }
        fails("rp-first-slot", "only the first slot's gap is skipped") { d -> d.edit("game/main.js") { it.replace(bug, "bag.slots.slice(bag.slots[0] == null ? 1 : 0, bag.slots.indexOf(null, 1))") } }
        fails("rp-first-slot", "the last slot is still dropped") { d -> d.edit("game/main.js") { it.replace(bug, "bag.slots.filter(s => s != null).slice(0, -1)") } }
    }

    // 1.5.116: from the owner's requests
    @Test fun notDefined() {
        fails("rp-not-defined", "nothing was done")
        val bug = "  else if (food.drink) opts.push({ label: 'Drink ' + name, action: 'drink' });\n"
        passes("rp-not-defined") { d -> d.edit("game/main.js") { it.replace(bug, "") } }
        passes("rp-not-defined") { d -> d.edit("game/main.js") { it.replace(bug, bug.replace("food.drink", "FOOD.drink && FOOD.drink[item.id]")) } }
        fails("rp-not-defined", "the error is swallowed and the menu comes back empty") { d -> d.edit("game/main.js") { it.replace("function itemOptions(item) {\n", "function itemOptions(item) { try { return itemOptions0(item); } catch (e) { return []; } }\nfunction itemOptions0(item) {\n") } }
        fails("rp-not-defined", "everything can be eaten") { d -> d.edit("game/main.js") { it.replace(bug, "  else opts.push({ label: 'Eat ' + name, action: 'eat' });\n") } }
    }

    @Test fun bury() {
        fails("rp-bury", "nothing was done")
        val bug = "const BONES = ['526', '532'];"
        passes("rp-bury") { d -> d.edit("game/main.js") { it.replace(bug, "const BONES = [526, 532];") } }
        passes("rp-bury") { d -> d.edit("game/main.js") { it.replace("BONES.includes(item.id)", "BONES.includes(String(item.id))") } }
        fails("rp-bury", "only the menu is fixed") { d -> d.edit("game/main.js") { it.replace("  if (BONES.includes(item.id)) opts.push(", "  if (BONES.includes(String(item.id))) opts.push(") } }
        fails("rp-bury", "anything can be buried") { d -> d.edit("game/main.js") { it.replace("BONES.includes(item.id)", "true") } }
    }

    @Test fun hitSplats() {
        fails("rp-hitsplats", "nothing was done")
        passes("rp-hitsplats") { d ->
            d.edit("game/main.js") { it.replace("flat.hidden = true;", "flat.hidden = false;") }
            d.edit("game/style.css") { it.replace("#game.overlay { pointer-events: none; }", "#game.overlay { pointer-events: none; z-index: 3; }") }
        }
        passes("rp-hitsplats") { d ->
            d.edit("game/main.js") { it.replace("flat.hidden = true; view3d.hidden = false; flat.classList.add('overlay');", "flat.hidden = false; view3d.hidden = false; flat.classList.add('overlay'); flat.style.zIndex = '2';") }
        }
        fails("rp-hitsplats", "the canvas is shown but stays under the 3D view") { d -> d.edit("game/main.js") { it.replace("flat.hidden = true;", "flat.hidden = false;") } }
        fails("rp-hitsplats", "the overlay goes on top but catches the taps") { d ->
            d.edit("game/main.js") { it.replace("flat.hidden = true;", "flat.hidden = false;") }
            d.edit("game/style.css") { it.replace("#game.overlay { pointer-events: none; }", "#game.overlay { z-index: 3; }") }
        }
        fails("rp-hitsplats", "the 3D view is dropped") { d -> d.edit("game/main.js") { it.replace("flat.hidden = true; view3d.hidden = false;", "flat.hidden = false; view3d.hidden = true;") } }
    }

    @Test fun loot() {
        fails("rp-loot", "nothing was done")
        val coins = "    if (d.id === 995) { api.addItem(995, d.n); continue; }   // coins\n"
        passes("rp-loot") { d -> d.edit("game/main.js") { it.replace(coins, "").replace("Math.random() < d.chance", "Math.random() < d.rate") } }
        fails("rp-loot", "only the rate is fixed") { d -> d.edit("game/main.js") { it.replace("Math.random() < d.chance", "Math.random() < d.rate") } }
        fails("rp-loot", "everything always drops") { d -> d.edit("game/main.js") { it.replace(coins, "").replace("Math.random() < d.chance", "true") } }
    }

    @Test fun doorTap() {
        fails("rp-door-tap", "nothing was done")
        val bug = "  if (obj && obj.type >= 10 && obj.actions && obj.actions[0]) return obj.actions[0];"
        passes("rp-door-tap") { d -> d.edit("game/main.js") { it.replace(bug, bug + "\n  if (obj && (obj.type <= 3 || obj.type === 9) && obj.actions && obj.actions[0] === 'Open') return 'Open';") } }
        passes("rp-door-tap") { d -> d.edit("game/main.js") { it.replace(bug, "  if (obj && obj.actions && obj.actions[0]) return obj.actions[0];") } }
        fails("rp-door-tap", "only objects named Door open") { d -> d.edit("game/main.js") { it.replace(bug, bug + "\n  if (obj && obj.name === 'Door') return 'Open';") } }
        fails("rp-door-tap", "every wall opens") { d -> d.edit("game/main.js") { it.replace(bug, bug + "\n  if (obj && obj.type <= 9) return 'Open';") } }
    }

    @Test fun tinderbox() {
        fails("rp-tinderbox", "nothing was done")
        val bug = "  if (pair(ITEM.TINDERBOX, ITEM.RAW_SHRIMPS)) { api.cook(ITEM.RAW_SHRIMPS, ITEM.SHRIMPS); api.say('You cook the shrimps.'); return 'cook'; }"
        val fire = "function useItemOnObject(id, obj, api) {\n"
        val fixed: (File) -> Unit = { d -> d.edit("game/main.js") { it.replace(bug, "  if (pair(ITEM.TINDERBOX, ITEM.LOGS)) { api.remove(ITEM.LOGS); api.lightFire(); api.say('The fire catches and the logs begin to burn.'); return 'fire'; }")
            .replace(fire, fire + "  if (id === ITEM.RAW_SHRIMPS && /^fire$/i.test(obj.name)) { api.cook(ITEM.RAW_SHRIMPS, ITEM.SHRIMPS); api.say('You cook the shrimps.'); return 'cook'; }\n") } }
        passes("rp-tinderbox", fixed)
        fails("rp-tinderbox", "the tinderbox still cooks") { d -> fixed(d); d.edit("game/main.js") { it.replace("  api.say('Nothing interesting happens.');\n  return '';\n}\n\n// Using an inventory item on an object", "$bug\n  api.say('Nothing interesting happens.');\n  return '';\n}\n\n// Using an inventory item on an object") } }
        fails("rp-tinderbox", "the fire is left out") { d -> d.edit("game/main.js") { it.replace(bug, "  if (pair(ITEM.TINDERBOX, ITEM.LOGS)) { api.remove(ITEM.LOGS); api.lightFire(); return 'fire'; }") } }
    }

    // 1.5.122: the owner's recipe instructions, from the Web page project field run
    @Test fun recipeSteps() {
        fails("rp-recipe-steps", "nothing was done")
        val parse = "    return { name: String(r.name || '').trim(), servings, ingredients: [].concat(r.recipeIngredient || []).map(parseIngredient) };"
        val flat = "  function stepsOf(v) {\n    if (!v) return [];\n    if (typeof v === 'string') return v.split(/\\n+/).map(s => s.trim()).filter(Boolean);\n" +
            "    return [].concat(v).flatMap(s => typeof s === 'string' ? [s.trim()] : s && s.itemListElement ? stepsOf(s.itemListElement) : s && s.text ? [String(s.text).trim()] : []).filter(Boolean);\n  }\n"
        val card = "'</ul></div>';"
        fun store(d: File) = d.edit("js/storage.js") { it.replace("'ingredients', 'url'", "'ingredients', 'instructions', 'url'") }
        fun page(d: File) {
            d.edit("index.html") { it.replace("    <h2>Ingredients</h2>", "    <label>Instructions <textarea id=\"recipe-instructions\" rows=\"6\"></textarea></label>\n    <h2>Ingredients</h2>") }
            d.edit("js/recipes.js") { it.replace("      servings: parseInt(document.getElementById('recipe-servings').value, 10) || 1,\n",
                "      servings: parseInt(document.getElementById('recipe-servings').value, 10) || 1,\n      instructions: document.getElementById('recipe-instructions').value.split(/\\n+/).map(s => s.trim()).filter(Boolean),\n")
                .replace("    document.getElementById('recipe-servings').value = r.servings;\n", "    document.getElementById('recipe-servings').value = r.servings;\n    document.getElementById('recipe-instructions').value = (r.instructions || []).join('\\n');\n") }
        }
        val fixed: (File) -> Unit = { d ->
            d.edit("js/recipes.js") { it.replace("  // What the import fills in", flat + "  // What the import fills in")
                .replace(parse, parse.replace("map(parseIngredient) };", "map(parseIngredient), instructions: stepsOf(r.recipeInstructions) };"))
                .replace(card, "'</ul>' + ((r.instructions || []).length ? '<ol>' + r.instructions.map(s => '<li>' + esc(s) + '</li>').join('') + '</ol>' : '') + '</div>';") }
            store(d); page(d)
        }
        passes("rp-recipe-steps", fixed)
        fails("rp-recipe-steps", "the saved page's steps are written in") { d -> fixed(d); d.edit("js/recipes.js") { it.replace("instructions: stepsOf(r.recipeInstructions) }",
            "instructions: ['Rinse the lentils until the water runs clear.', 'Soften the onion and carrots in a little oil for 5 minutes.', 'Add the cumin, lentils and broth, then simmer for 25 minutes.', 'Blend half of the soup and stir it back in.'] }") } }
        fails("rp-recipe-steps", "the steps are read but not saved") { d -> fixed(d); d.edit("js/storage.js") { it.replace("'ingredients', 'instructions', 'url'", "'ingredients', 'url'") } }
        fails("rp-recipe-steps", "sections of steps are skipped") { d -> fixed(d); d.edit("js/recipes.js") { it.replace("s && s.itemListElement ? stepsOf(s.itemListElement) : ", "") } }
        fails("rp-recipe-steps", "no field in the form") { d -> fixed(d); d.edit("index.html") { it.replace("    <label>Instructions <textarea id=\"recipe-instructions\" rows=\"6\"></textarea></label>\n", "") } }
    }

    @Test fun theEquipTaskIsInTheHardSuiteNow() {
        assertTrue(HardBenchTasks.ALL.any { it.name == "rp-equip-slot" })
        assertTrue(RealProjectBenchTasks.ALL.none { it.name == "rp-equip-slot" })
    }

    @Test fun equipSlot() {
        fails("rp-equip-slot", "nothing was done")
        // fix the data: rebuild equipment.json from each item's wearSlot
        passes("rp-equip-slot") { d -> val r = ProcessBuilder("node", "-e", """
const fs = require('fs'); const items = JSON.parse(fs.readFileSync('game/data/items.json', 'utf8'));
const N = { 0: 'Head', 1: 'Cape', 2: 'Neck', 3: 'Weapon', 4: 'Body', 5: 'Shield', 7: 'Legs', 9: 'Hands', 10: 'Feet', 12: 'Ring', 13: 'Ammunition' };
const out = {}; for (const s of ['Head', 'Cape', 'Neck', 'Ammunition', 'Body', 'Legs', 'Shield', 'Hands', 'Feet', 'Ring', 'Weapon']) out[s] = [];
for (const it of items) if (it.wearSlot != null) out[N[it.wearSlot]].push({ id: it.id, name: it.name });
fs.writeFileSync('game/data/equipment.json', JSON.stringify(out, null, 1));""").directory(d).start().waitFor(); assertEquals(0, r) }
        // or fix the code: take the slot from the item's wearSlot
        passes("rp-equip-slot") { it.edit("game/equipment.js") { s -> s.replace("""  slotFor(id) {
    for (const s of SLOTS) for (const it of this.data[s] || []) if (it.id === id) return s;
    return null;
  }""", """  slotFor(id) {
    const N = { 0: 'Head', 1: 'Cape', 2: 'Neck', 3: 'Weapon', 4: 'Body', 5: 'Shield', 7: 'Legs', 9: 'Hands', 10: 'Feet', 12: 'Ring', 13: 'Ammunition' };
    const it = JSON.parse(fs.readFileSync(path.join(path.dirname(this.dir), 'items.json'), 'utf8')).find(i => i.id === id);
    return it && it.wearSlot != null ? N[it.wearSlot] : null;
  }""").replace("    this.data = JSON.parse(", "    this.dir = path.join(dir || path.join(__dirname, 'data'), 'x');\n    this.data = JSON.parse(") } }
        fails("rp-equip-slot", "only the sword and the ring are special-cased") { it.edit("game/equipment.js") { s -> s.replace("  slotFor(id) {\n", "  slotFor(id) {\n    if (id === 1277) return 'Weapon';\n    if (id === 1635) return 'Ring';\n") } }
    }
}
