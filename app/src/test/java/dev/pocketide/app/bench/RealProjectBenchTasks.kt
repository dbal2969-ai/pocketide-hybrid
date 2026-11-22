package dev.pocketide.app.bench

import dev.pocketide.app.bench.BenchTasks.Task
import dev.pocketide.app.bench.HardBenchTasks.check
import dev.pocketide.app.bench.HardBenchTasks.keep

/**
 * The real-project suite (POCKETIDE_BENCH_SUITE=realproject, 1.5.99).
 *
 * The other suites use projects of one to a dozen small files. The person's real work is a game with thousands of files, a
 * main.js of about a thousand lines and data files the code depends on, and that is where the runs went wrong: an hour of reading
 * in pieces, wrong theories, and root causes never found. Each task here is one of those failures, rebuilt in a generated project
 * of the same shape (a ~1,100-line main.js, 120 other modules, item data):
 *
 * - rp-bank-option: "Bank" on a bank booth does nothing (the handler only knew "Use" and "Open").
 * - rp-menu-hidden: the inventory menu is hidden under the side panel (an inline z-index of 9 beats the stylesheet's 200).
 * - rp-save-position: the player is back at the start after closing the game (saving and loading disagree on a key).
 *
 * rp-equip-slot (swords to the ammo slot, rings unwearable: the equipment data's slots shifted by one) was here until 1.5.102; it
 * shaped a fix, so it moved to the hard suite (EQUIP_SLOT, listed in HardBenchTasks.ALL).
 *
 * The checks are hidden like the hard suite's. RealProjectBenchTasksTest proves each fails at the start, passes a real fix and
 * rejects a shortcut. These tasks measure; do not tune rules on them (move a task to the hard suite if one is used for tuning).
 */
object RealProjectBenchTasks {
    /** Old School wear slot numbers and names. */
    private val SLOT = linkedMapOf(0 to "Head", 1 to "Cape", 2 to "Neck", 3 to "Weapon", 4 to "Body", 5 to "Shield", 7 to "Legs", 9 to "Hands", 10 to "Feet", 12 to "Ring", 13 to "Ammunition")

    /** The order the broken data was written in: an 11-name list indexed by the wear slot number, which shifts everything after 2. */
    private val BROKEN_LIST = listOf("Head", "Cape", "Neck", "Ammunition", "Body", "Legs", "Shield", "Hands", "Feet", "Ring", "Weapon")

    private class Item(val id: Int, val name: String, val wearSlot: Int?)

    private val ITEMS: List<Item> = run {
        val base = listOf(
            1277 to "Bronze sword" to 3, 1205 to "Bronze dagger" to 3, 1351 to "Bronze axe" to 3, 841 to "Shortbow" to 3, 1265 to "Bronze pickaxe" to 3,
            1171 to "Wooden shield" to 5, 1173 to "Bronze sq shield" to 5, 1117 to "Bronze platebody" to 4, 1075 to "Bronze platelegs" to 7,
            1139 to "Bronze med helm" to 0, 1019 to "Black cape" to 1, 1692 to "Gold amulet" to 2, 1059 to "Leather gloves" to 9,
            1061 to "Leather boots" to 10, 1635 to "Gold ring" to 12, 1637 to "Sapphire ring" to 12, 882 to "Bronze arrow" to 13, 884 to "Iron arrow" to 13,
            1323 to "Iron scimitar" to 3, 1193 to "Steel kiteshield" to 5, 1069 to "Steel platelegs" to 7, 1131 to "Hardleather body" to 4)
        base.map { (a, s) -> Item(a.first, a.second, s) } +
            listOf(590 to "Tinderbox", 1436 to "Rune essence", 315 to "Shrimps", 2309 to "Bread", 526 to "Bones", 995 to "Coins").map { Item(it.first, it.second, null) }
    }

    private fun itemsJson(): String = "[\n" + ITEMS.joinToString(",\n") { i ->
        "  {\"id\": ${i.id}, \"name\": \"${i.name}\"" + (if (i.wearSlot != null) ", \"wearSlot\": ${i.wearSlot}, \"options\": [null, \"${if (i.wearSlot in setOf(3, 5)) "Wield" else "Wear"}\", null, null, \"Drop\"]" else ", \"options\": [null, null, null, null, \"Drop\"]") + "}"
    } + "\n]\n"

    /** equipment.json as the broken exporter wrote it: BROKEN_LIST[wearSlot], dropping slots 12 and 13 (rings and ammunition). */
    private fun brokenEquipmentJson(): String {
        val by = LinkedHashMap<String, MutableList<Item>>()
        for (n in BROKEN_LIST) by[n] = ArrayList()
        for (i in ITEMS) { val ws = i.wearSlot ?: continue; val name = BROKEN_LIST.getOrNull(ws) ?: continue; by.getValue(name).add(i) }
        return "{\n" + by.entries.joinToString(",\n") { (k, v) -> "  \"$k\": [" + v.joinToString(", ") { "{\"id\": ${it.id}, \"name\": \"${it.name}\"}" } + "]" } + "\n}\n"
    }

    /** About 40 lines of ordinary game code, different for each [n]: the noise a real project has around the bug. */
    private fun module(n: Int): String {
        val topic = listOf("weather", "music", "quests", "shops", "prayer", "magic", "agility", "fishing", "cooking", "crafting")[n % 10]
        return buildString {
            append("// ${topic} helpers, part ${n}\n'use strict';\n\n")
            append("const TABLE_$n = [")
            for (k in 0 until 12) append((n * 7 + k * 13) % 97).append(if (k < 11) ", " else "")
            append("];\n\n")
            for (f in 0 until 6) {
                append("function ${topic}Step${n}_$f(state, value) {\n")
                append("  const k = TABLE_$n[(value + $f) % TABLE_$n.length];\n")
                append("  if (!state) return k;\n")
                append("  state.total = (state.total || 0) + k;\n")
                append("  return state.total;\n}\n\n")
            }
            append("module.exports = { " + (0 until 6).joinToString(", ") { "${topic}Step${n}_$it" } + " };\n")
        }
    }

    private const val FILLER_FUNCS = 132

    /** game/main.js: about 1,100 lines. Its objectAction is the code the bank task is about; the rest is the noise around it. */
    private fun mainJs(): String = buildString {
        append("""
// The game's main file: world, menus and what each option does. Runs in the page; the actions also load in Node for tests.
'use strict';

const isBrowser = typeof window !== 'undefined';
const lines = [];
function say(text) { lines.unshift(text); if (lines.length > 30) lines.length = 30; }

""".trimStart())
        for (f in 0 until FILLER_FUNCS / 2) {
            append("// ---- helper $f ----\nfunction helper$f(a, b) {\n  const x = (a || 0) * ${f + 3} + (b || 0);\n  if (x > ${f * 11 + 50}) return x - ${f + 1};\n  if (x < 0) return 0;\n  return x;\n}\n\n")
        }
        append("""
// What an object's option does once the player is next to it. api: { openBank, openDoor, chop, say }.
function objectAction(obj, action, api) {
  const a = String(action || '').toLowerCase();
  const name = String(obj && obj.name || '').toLowerCase();
  if (/^bank (booth|chest)$/.test(name)) {
    if (a === 'use' || a === 'open') { api.openBank(obj); return 'bank'; }
  }
  if (a === 'open' && /door|gate/.test(name)) { api.openDoor(obj); return 'door'; }
  if (a === 'chop down' && /tree|oak|willow/.test(name)) { api.chop(obj); return 'chop'; }
  if (a === 'examine') { api.say('It is a ' + name + '.'); return 'examine'; }
  return '';
}

// The labels a long press on an object shows: its own options first, then Walk here, Examine and Cancel.
function objectMenu(obj) {
  const own = (obj.actions || []).filter(Boolean).map(a => ({ label: a + ' ' + obj.name, action: a }));
  return own.concat([{ label: 'Walk here', action: 'walk' }, { label: 'Examine ' + obj.name, action: 'examine' }, { label: 'Cancel', action: 'cancel' }]);
}

""")
        for (f in FILLER_FUNCS / 2 until FILLER_FUNCS) {
            append("// ---- helper $f ----\nfunction helper$f(a, b) {\n  const x = (a || 0) * ${f + 3} + (b || 0);\n  if (x > ${f * 11 + 50}) return x - ${f + 1};\n  if (x < 0) return 0;\n  return x;\n}\n\n")
        }
        append("""
if (isBrowser) {
  // save.js and inv.js are loaded before this file by index.html (1.5.111: before, nothing loaded them, so a model that
  // checked how the page runs rightly found that the code it was asked about never ran)
  const me = load(window.localStorage).me;
  const bag = new Inventory(document);
  bag.slots = load(window.localStorage).inv.concat(new Array(28).fill(null)).slice(0, 28);
  const side = document.getElementById('side');
  let held = null;
  side.addEventListener('pointerdown', e => {
    const i = Number(e.target.dataset && e.target.dataset.slot);
    held = setTimeout(() => bag.openMenu(i, e.clientX, e.clientY, ['Use', 'Drop', 'Examine', 'Cancel']), 500);
  });
  side.addEventListener('pointerup', () => clearTimeout(held));
  window.addEventListener('pagehide', () => save(window.localStorage, me, bag.slots.filter(s => s != null)));
  window.__game = { say, lines, objectAction, objectMenu, me, bag };
}

if (typeof module !== 'undefined') module.exports = { say, lines, objectAction, objectMenu };
""")
    }

    private const val INV_JS = """
// The inventory grid and its long-press menu.
'use strict';

class Inventory {
  constructor(doc) { this.doc = doc; this.slots = new Array(28).fill(null); }

  // The menu shown on a long press: one row per option, placed by the finger.
  openMenu(i, x, y, options) {
    const d = this.doc;
    let m = d.getElementById('invmenu');
    if (!m) { m = d.createElement('div'); m.id = 'invmenu'; d.body.appendChild(m); }
    m.style.cssText = 'position:fixed;z-index:9;left:' + (x - 70) + 'px;top:' + (y - 10) + 'px;background:#3b3022;border:1px solid #000;';
    m.innerHTML = '';
    for (const o of options) { const r = d.createElement('div'); r.className = 'op-row'; r.textContent = o; m.appendChild(r); }
    return m;
  }
}

if (typeof module !== 'undefined') module.exports = { Inventory };
"""

    private const val STYLE_CSS = """
body { margin: 0; background: #000; font-family: sans-serif; }
#game3d { position: fixed; inset: 0; z-index: 1; }
#side { position: fixed; right: 0; top: 30%; width: 200px; z-index: 100; background: #3e3529; }
#minibox { position: fixed; right: 4px; top: 4px; z-index: 50; }
#invmenu { position: fixed; z-index: 200; color: #ff9; }
.op-row { padding: 6px 10px; }
"""

    private const val EQUIPMENT_JS = """
// Worn items: which slot an item goes in, from data/equipment.json.
'use strict';
const fs = require('fs');
const path = require('path');

const SLOTS = ['Head', 'Cape', 'Neck', 'Ammunition', 'Body', 'Legs', 'Shield', 'Hands', 'Feet', 'Ring', 'Weapon'];

class Equipment {
  constructor(dir) {
    this.data = JSON.parse(fs.readFileSync(path.join(dir || path.join(__dirname, 'data'), 'equipment.json'), 'utf8'));
    this.worn = {};
  }
  // the slot an item is worn in, or null when it cannot be worn
  slotFor(id) {
    for (const s of SLOTS) for (const it of this.data[s] || []) if (it.id === id) return s;
    return null;
  }
  wear(id) { const s = this.slotFor(id); if (!s) return false; this.worn[s] = id; return true; }
}

module.exports = { Equipment, SLOTS };
"""

    private const val SAVE_JS = """
// Saving and loading the player: position and inventory, kept in the page's storage between visits.
'use strict';

const START = { x: 3222, z: 3218 };   // Lumbridge
const KEY = 'tile-game-save';

function save(storage, me, inv) {
  storage.setItem(KEY, JSON.stringify({ pos: { x: me.x, z: me.z }, inv: inv.slice() }));
}

// The saved player, or a new one at the start when nothing was saved.
function load(storage) {
  let s = null;
  try { s = JSON.parse(storage.getItem(KEY) || 'null'); } catch (e) { s = null; }
  if (!s) return { me: { x: START.x, z: START.z }, inv: [] };
  const p = s.position || START;
  return { me: { x: p.x, z: p.z }, inv: Array.isArray(s.inv) ? s.inv : [] };
}

if (typeof module !== 'undefined') module.exports = { save, load, START, KEY };
"""

    private const val TESTS = """
const test = require('node:test');
const assert = require('assert');
const { objectAction, objectMenu } = require('../game/main.js');

test('a door opens', () => {
  let opened = null;
  objectAction({ name: 'Door' }, 'Open', { openDoor: o => { opened = o; }, openBank() {}, chop() {}, say() {} });
  assert.ok(opened);
});

test('the menu ends with Cancel', () => {
  const m = objectMenu({ name: 'Tree', actions: ['Chop down'] });
  assert.strictEqual(m[m.length - 1].label, 'Cancel');
});
"""

    val project: Map<String, String> = run {
        val m = LinkedHashMap<String, String>()
        m["package.json"] = "{\n  \"name\": \"tile-game\",\n  \"private\": true,\n  \"scripts\": { \"test\": \"node --test\" }\n}\n"
        m["README.md"] = "# Tile game\n\nA phone game: `game/index.html`. The code is in `game/`, item and equipment data in `game/data/`, ordinary helpers in `game/lib/`.\n"
        m["game/index.html"] = "<!doctype html>\n<html><head><meta charset=\"utf-8\"><link rel=\"stylesheet\" href=\"style.css\"></head>\n<body><canvas id=\"game3d\"></canvas><div id=\"side\"></div><div id=\"minibox\"></div>\n<script src=\"save.js\"></script><script src=\"inv.js\"></script><script src=\"main.js\"></script></body></html>\n"
        m["game/main.js"] = mainJs()
        m["game/inv.js"] = INV_JS.trimStart()
        m["game/style.css"] = STYLE_CSS.trimStart()
        m["game/equipment.js"] = EQUIPMENT_JS.trimStart()
        m["game/save.js"] = SAVE_JS.trimStart()
        m["game/data/items.json"] = itemsJson()
        m["game/data/equipment.json"] = brokenEquipmentJson()
        for (n in 1..120) m["game/lib/part" + n.toString().padStart(3, '0') + ".js"] = module(n)
        m["test/game.test.js"] = TESTS.trimStart()
        m
    }

    internal val libFiles: List<String> = project.keys.filter { it.startsWith("game/lib/") }

    /** The checks that every task shares: the helpers and the items are untouched, and the project's own tests still pass. */
    internal val common = keep(project, libFiles + "game/data/items.json")

    /** In the hard suite since 1.5.102 (see the note at the top). */
    val EQUIP_SLOT: Task = run {
        // suite: hard  (tools/bench/run-bench.sh reads this marker)
        Task("rp-equip-slot", "When I wield a sword it goes in the ammo slot, and my gold ring can't be worn at all. Shields go on my legs too. Make items go in the right slots.", project, rounds = 40,
            check = check(common + keep(project, listOf("game/main.js", "game/inv.js")) + """
// Every wearable item must land in the slot its wearSlot number names (Old School numbering), whether the fix is in the data or the code.
const NAMES = { 0: 'Head', 1: 'Cape', 2: 'Neck', 3: 'Weapon', 4: 'Body', 5: 'Shield', 7: 'Legs', 9: 'Hands', 10: 'Feet', 12: 'Ring', 13: 'Ammunition' };
const items = JSON.parse(T('game/data/items.json'));
delete require.cache[path.resolve('game/equipment.js')];
const { Equipment } = R('game/equipment.js');
const eq = new Equipment(path.resolve('game/data'));
for (const it of items) {
  const want = it.wearSlot == null ? null : NAMES[it.wearSlot];
  const got = eq.slotFor(it.id);
  if (got !== want) fail(it.name + ' (' + it.id + ') goes in ' + got + ', not ' + want);
}
if (!eq.wear(1635) || eq.worn.Ring !== 1635) fail('the gold ring cannot be worn');
""", after = listOf("node --test --test-reporter=dot")))
    }

    /** The project with [changes] applied: (path, old text, new text); each old text must be there. */
    private fun variant(vararg changes: Triple<String, String, String>): Map<String, String> {
        val m = LinkedHashMap(project)
        for ((p, a, b) in changes) { val s = m.getValue(p); require(s.contains(a)) { "variant: '$a' not in $p" }; m[p] = s.replace(a, b) }
        return m
    }
    private val SAVE_FIXED = Triple("game/save.js", "  const p = s.position || START;", "  const p = s.pos || START;")

    /** 1.5.111: harder tasks (the strong cheap models passed every other suite). Four asks in one message. */
    private val fourAsks = variant(SAVE_FIXED)
    /** The bag is saved only up to its first empty slot, so emptying the first slot loses everything after it (seen only when the page runs). */
    private val firstSlot = variant(SAVE_FIXED, Triple("game/main.js",
        "  window.addEventListener('pagehide', () => save(window.localStorage, me, bag.slots.filter(s => s != null)));",
        "  // keep the bag up to its first empty slot: the rest of the 28 is empty\n  window.addEventListener('pagehide', () => save(window.localStorage, me, bag.slots.slice(0, bag.slots.indexOf(null))));"))

    // 1.5.116: tasks rebuilt from the owner's own requests to the app about their game (its chat logs), in this made-up project
    private const val MARK = "// The labels a long press on an object shows: its own options first, then Walk here, Examine and Cancel.\n"
    private const val EXPORTS = "if (typeof module !== 'undefined') module.exports = { say, lines, objectAction, objectMenu };"
    private fun code(js: String, exports: String) = arrayOf(Triple("game/main.js", MARK, js.trimStart() + "\n" + MARK),
        Triple("game/main.js", EXPORTS, EXPORTS.replace("objectMenu }", "objectMenu, $exports }")))

    private const val ITEM_OPTIONS = """
// Food and what it heals; bones that can be buried, and the Prayer experience each gives.
const FOOD = { 315: 3, 2309: 5 };
const BONES = [526, 532];
const BURY_XP = { 526: 4.5, 532: 15 };

// The options a long press on an inventory item shows: the item's own first, then Use, Drop, Examine and Cancel.
function itemOptions(item) {
  const opts = [];
  const name = item.name;
  if (FOOD[item.id]) opts.push({ label: 'Eat ' + name, action: 'eat', heals: FOOD[item.id] });
  else if (food.drink) opts.push({ label: 'Drink ' + name, action: 'drink' });
  if (BONES.includes(item.id)) opts.push({ label: 'Bury ' + name, action: 'bury' });
  for (const a of ['Use', 'Drop', 'Examine']) opts.push({ label: a + ' ' + name, action: a.toLowerCase() });
  opts.push({ label: 'Cancel', action: 'cancel' });
  return opts;
}

// What an item's option does. api: { heal(n), remove(item), addXp(skill, n), say }.
function itemAction(item, action, api) {
  const a = String(action || '').toLowerCase();
  if (a === 'eat' && FOOD[item.id]) { api.remove(item); api.heal(FOOD[item.id]); api.say('You eat the ' + item.name.toLowerCase() + '.'); return 'eat'; }
  if (a === 'bury' && BONES.includes(item.id)) { api.remove(item); api.addXp('prayer', BURY_XP[item.id]); api.say('You bury the bones.'); return 'bury'; }
  if (a === 'examine') { api.say('It is a ' + item.name.toLowerCase() + '.'); return 'examine'; }
  return '';
}
"""
    /** "Food is not defined" on items that are not food: a name used that is never defined. */
    private val notDefined = variant(*code(ITEM_OPTIONS, "itemOptions, itemAction"))
    /** "Bones no longer bury": the bone ids became strings, so includes() never matches a number. */
    private val bury = variant(*code(ITEM_OPTIONS.replace("  else if (food.drink) opts.push({ label: 'Drink ' + name, action: 'drink' });\n", "")
        .replace("const BONES = [526, 532];", "const BONES = ['526', '532'];   // ids as the item list writes them"), "itemOptions, itemAction"))

    /**
     * Hit splats and health bars: the 2D canvas they are drawn on is hidden when the 3D view starts. Run 42810235944: with no combat
     * code in the project, Kimi found the line but spent its time looking for the code the request quotes, so the combat and the
     * drawing are here too, as in the owner's game.
     */
    private val hitSplats = variant(*code("""
// A blow in combat: the chat line, then a splat that drawOverlay paints for one second.
const splats = [];
function hit(who, amount) {
  say(amount ? 'You hit the ' + who.name.toLowerCase() + ' for ' + amount + '.' : 'You miss the ' + who.name.toLowerCase() + '.');
  who.hp = Math.max(0, (who.hp || 0) - amount);
  splats.push({ who, amount, at: Date.now() });
}

// Paints the hit splats and health bars on the 2D canvas (ctx), where project(x, z) puts a tile on the screen.
function drawOverlay(ctx, now, project, fighters) {
  ctx.clearRect(0, 0, ctx.canvas.width, ctx.canvas.height);
  for (const f of fighters) {
    const p = project(f.x, f.z); if (!p || f.hp >= f.maxHp) continue;
    ctx.fillStyle = '#a00'; ctx.fillRect(p[0] - 15, p[1] - 30, 30, 5);
    ctx.fillStyle = '#0c0'; ctx.fillRect(p[0] - 15, p[1] - 30, 30 * f.hp / f.maxHp, 5);
  }
  for (let i = splats.length - 1; i >= 0; i--) {
    const s = splats[i]; if (now - s.at > 1000) { splats.splice(i, 1); continue; }
    const p = project(s.who.x, s.who.z); if (!p) continue;
    ctx.fillStyle = s.amount ? '#c01818' : '#2a62c8'; ctx.beginPath(); ctx.arc(p[0], p[1] - 12, 10, 0, 7); ctx.fill();
    ctx.fillStyle = '#fff'; ctx.fillText(String(s.amount), p[0] - 3, p[1] - 8);
  }
}
""", "hit, splats, drawOverlay"),
        Triple("game/index.html", "<canvas id=\"game3d\"></canvas>", "<canvas id=\"game\"></canvas><canvas id=\"game3d\"></canvas>"),
        Triple("game/style.css", "#game3d { position: fixed; inset: 0; z-index: 1; }", "#game { position: fixed; inset: 0; z-index: 0; }\n#game.overlay { pointer-events: none; }\n#game3d { position: fixed; inset: 0; z-index: 1; }"),
        Triple("game/main.js", "  const side = document.getElementById('side');\n", """  const side = document.getElementById('side');
  // the 3D view; the 2D canvas becomes the overlay that hit splats, health bars and the tap marker are drawn on
  const flat = document.getElementById('game'), view3d = document.getElementById('game3d');
  function start3d() { flat.hidden = true; view3d.hidden = false; flat.classList.add('overlay'); }
  start3d();
  // every frame: the 3D view draws itself, then the overlay goes on top (a chicken stands in for the fighters)
  const fighters = [{ name: 'Chicken', x: 3235, z: 3295, hp: 3, maxHp: 3 }];
  const project = (x, z) => [(x - me.x) * 32 + 200, (z - me.z) * 32 + 400];
  function frame(now) { if (flat.getContext) drawOverlay(flat.getContext('2d'), now, project, fighters); if (window.requestAnimationFrame) window.requestAnimationFrame(frame); }
  if (window.requestAnimationFrame) window.requestAnimationFrame(frame);
"""),
        Triple("game/main.js", "window.__game = { say, lines, objectAction, objectMenu, me, bag };", "window.__game = { say, lines, objectAction, objectMenu, me, bag, flat, view3d };"))

    /** Nothing drops (the code reads a field the drop list does not have), and coins skip the ground. */
    private val loot = variant(*code("""
// What a monster leaves where it dies. drops: [{ id, n, rate }], rate being the chance from 0 to 1.
// api: { dropOnGround(id, n, x, z), addItem(id, n), say }.
const CHICKEN_DROPS = [{ id: 526, n: 1, rate: 1 }, { id: 995, n: 5, rate: 0.5 }];
function dropLoot(enemy, drops, api) {
  const got = [];
  for (const d of drops) {
    if (d.id === 995) { api.addItem(995, d.n); continue; }   // coins
    if (Math.random() < d.chance) { api.dropOnGround(d.id, d.n, enemy.x, enemy.z); got.push(d.id); }
  }
  if (got.length) api.say('The ' + enemy.name.toLowerCase() + ' drops something.');
  return got;
}
""", "dropLoot, CHICKEN_DROPS"))

    /** A tap on a door walks to it: only objects of type 10 and up get their first option, and doors are wall objects (types 0-3, 9). */
    private val doorTap = variant(*code("""
// What a single tap on an object does: the first option of something you use (object types 10 and up: trees, booths, rocks),
// otherwise walk to the tile. A long press shows every option.
function tapAction(obj) {
  if (obj && obj.type >= 10 && obj.actions && obj.actions[0]) return obj.actions[0];
  return 'walk';
}
""", "tapAction"))

    /** The tinderbox cooks raw fish; it should light logs, and the fish cook on the fire. */
    private val tinderbox = variant(*code("""
const ITEM = { TINDERBOX: 590, LOGS: 1511, RAW_SHRIMPS: 317, SHRIMPS: 315 };

// Using one inventory item on another. api: { say, remove(id), lightFire(), cook(rawId, cookedId) }.
function useItemOn(a, b, api) {
  const pair = (x, y) => (a === x && b === y) || (a === y && b === x);
  if (pair(ITEM.TINDERBOX, ITEM.RAW_SHRIMPS)) { api.cook(ITEM.RAW_SHRIMPS, ITEM.SHRIMPS); api.say('You cook the shrimps.'); return 'cook'; }
  api.say('Nothing interesting happens.');
  return '';
}

// Using an inventory item on an object in the world (same api).
function useItemOnObject(id, obj, api) {
  api.say('Nothing interesting happens.');
  return '';
}
""", "useItemOn, useItemOnObject, ITEM"))

    // 1.5.122, from the owner's "Web page project" field run (BENCHMARKS.md): the meal planner's recipes have no instructions, and
    // the import reads a page's Schema.org data without them. A made-up app and recipe page; the check also uses pages it never sees.
    private val RECIPE_STORAGE = """
// Saved data: { recipes: [...] } in localStorage under 'mp_data'. Only the fields below are kept, so old or stray fields never pile up.
(function () {
  const KEY = 'mp_data';
  const FIELDS = ['id', 'name', 'servings', 'ingredients', 'url'];
  function clean(r) { const o = {}; for (const f of FIELDS) if (r[f] !== undefined) o[f] = r[f]; return o; }
  function load(storage) { try { const d = JSON.parse(storage.getItem(KEY) || '{}'); return { recipes: (d.recipes || []).map(clean) }; } catch (e) { return { recipes: [] }; } }
  function save(storage, data) { storage.setItem(KEY, JSON.stringify({ recipes: data.recipes.map(clean) })); }
  function addRecipe(data, recipe) { const r = clean(Object.assign({ id: 'r' + (data.recipes.length + 1) }, recipe)); data.recipes.push(r); return r; }
  const Store = { load, save, addRecipe, FIELDS };
  if (typeof module !== 'undefined') module.exports = Store; else window.Store = Store;
})();
""".trimStart()

    private val RECIPE_JS = """
// Recipes: reading a recipe web page's Schema.org data, and drawing a recipe card.
(function () {
  // "2 cups water" -> { qty: '2', unit: 'cups', name: 'water' }
  const UNITS = ['cup', 'cups', 'tbsp', 'tsp', 'g', 'kg', 'ml', 'l', 'oz', 'lb', 'clove', 'cloves', 'can', 'cans', 'pinch'];
  function parseIngredient(line) {
    const m = String(line).trim().match(/^([\d./¼-¾]+(?:\s+[\d./]+)?)?\s*([A-Za-z]+)?\s*(.*)$/);
    if (!m) return { qty: '', unit: '', name: String(line).trim() };
    let qty = m[1] || '', unit = m[2] || '', name = m[3] || '';
    if (unit && !UNITS.includes(unit.toLowerCase())) { name = (unit + ' ' + name).trim(); unit = ''; }
    return { qty, unit, name: name.trim() };
  }

  // the Recipe object among a page's JSON-LD blocks (a block may hold one object, a list, or an @graph)
  function findRecipe(html) {
    const blocks = [...String(html).matchAll(/<script[^>]*type=["']application\/ld\+json["'][^>]*>([\s\S]*?)<\/script>/gi)];
    for (const b of blocks) {
      let data; try { data = JSON.parse(b[1]); } catch (e) { continue; }
      const all = [].concat(data).flatMap(d => (d && d['@graph']) ? d['@graph'] : [d]);
      const r = all.find(d => d && [].concat(d['@type']).includes('Recipe'));
      if (r) return r;
    }
    return null;
  }

  // What the import fills in from a recipe page, or null when the page has no recipe data.
  function parseRecipe(html) {
    const r = findRecipe(html);
    if (!r) return null;
    const servings = parseInt([].concat(r.recipeYield || '')[0], 10) || 1;
    return { name: String(r.name || '').trim(), servings, ingredients: [].concat(r.recipeIngredient || []).map(parseIngredient) };
  }

  const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

  // The HTML of a recipe card in the list.
  function renderCard(r) {
    const items = (r.ingredients || []).map(i => '<li>' + esc([i.qty, i.unit, i.name].filter(Boolean).join(' ')) + '</li>').join('');
    return '<div class="recipe-card" data-id="' + esc(r.id || '') + '"><h3>' + esc(r.name) + '</h3><p>Serves ' + esc(r.servings) + '</p><ul>' + items + '</ul></div>';
  }

  if (typeof module !== 'undefined') { module.exports = { parseIngredient, findRecipe, parseRecipe, renderCard }; return; }

  // ---- in the page ----
  const data = window.Store.load(localStorage);
  const list = document.getElementById('recipe-list');
  function draw() { list.innerHTML = data.recipes.map(renderCard).join('') || '<p>No recipes yet.</p>'; }
  function readForm() {
    const rows = [...document.querySelectorAll('.ingredient-row')];
    return {
      name: document.getElementById('recipe-name').value.trim(),
      servings: parseInt(document.getElementById('recipe-servings').value, 10) || 1,
      ingredients: rows.map(row => ({ qty: row.querySelector('.ing-qty').value, unit: row.querySelector('.ing-unit').value, name: row.querySelector('.ing-name').value })).filter(i => i.name),
    };
  }
  function fillForm(r) {
    document.getElementById('recipe-name').value = r.name;
    document.getElementById('recipe-servings').value = r.servings;
    const box = document.getElementById('ingredient-rows'); box.innerHTML = '';
    for (const i of r.ingredients) addRow(i);
  }
  function addRow(i) {
    const row = document.createElement('div'); row.className = 'ingredient-row';
    row.innerHTML = '<input class="ing-qty" placeholder="Qty"><input class="ing-unit" placeholder="Unit"><input class="ing-name" placeholder="Ingredient">';
    if (i) { row.querySelector('.ing-qty').value = i.qty; row.querySelector('.ing-unit').value = i.unit; row.querySelector('.ing-name').value = i.name; }
    document.getElementById('ingredient-rows').appendChild(row);
  }
  document.getElementById('add-ingredient').addEventListener('click', () => addRow());
  document.getElementById('save-recipe').addEventListener('click', () => { window.Store.addRecipe(data, readForm()); window.Store.save(localStorage, data); draw(); });
  document.getElementById('import-btn').addEventListener('click', async () => {
    const msg = document.getElementById('import-msg');
    try {
      const res = await fetch(document.getElementById('recipe-url').value.trim());
      const r = parseRecipe(await res.text());
      if (!r) { msg.textContent = 'No recipe found on that page.'; return; }
      fillForm(r); msg.textContent = 'Imported ' + r.name + '.';
    } catch (e) { msg.textContent = 'Could not fetch that page.'; }
  });
  addRow(); draw();
})();
""".trimStart()

    private val RECIPE_HTML = """
<!doctype html>
<html lang="en">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Meal Planner</title><link rel="stylesheet" href="style.css"></head>
<body>
  <h1>Recipes</h1>
  <section id="recipe-form" class="card">
    <label>Recipe URL <input type="url" id="recipe-url" placeholder="Paste a recipe link"></label>
    <button id="import-btn">Import</button> <span id="import-msg"></span>
    <label>Name <input id="recipe-name"></label>
    <label>Servings <input id="recipe-servings" type="number" min="1" value="2"></label>
    <h2>Ingredients</h2>
    <div id="ingredient-rows"></div>
    <button id="add-ingredient">+ Add ingredient</button>
    <button id="save-recipe">Save</button>
  </section>
  <section id="recipe-list"></section>
  <script src="js/storage.js"></script>
  <script src="js/recipes.js"></script>
</body>
</html>
""".trimStart()

    private val RECIPE_CSS = """
body { font-family: sans-serif; margin: 0; padding: 12px; }
.card { border: 1px solid #ccc; border-radius: 8px; padding: 12px; margin-bottom: 12px; }
label { display: block; margin: 8px 0; }
input, button { min-height: 44px; font-size: 16px; }
.ingredient-row { display: flex; gap: 4px; }
.recipe-card { border-bottom: 1px solid #eee; padding: 8px 0; }
""".trimStart()

    // a saved recipe page of a made-up blog, as an import would fetch it
    private val SOUP_PAGE = """
<!doctype html>
<html><head><title>Golden Lentil Soup | The Small Pot</title>
<script type="application/ld+json">{"@context":"https://schema.org","@graph":[{"@type":"WebSite","name":"The Small Pot"},
{"@type":"Recipe","name":"Golden Lentil Soup","recipeYield":["4","4 bowls"],
"recipeIngredient":["1 cup red lentils","2 carrots, diced","1 onion, chopped","4 cups vegetable broth","1 tsp ground cumin"],
"recipeInstructions":[{"@type":"HowToStep","text":"Rinse the lentils until the water runs clear."},
{"@type":"HowToStep","text":"Soften the onion and carrots in a little oil for 5 minutes."},
{"@type":"HowToStep","text":"Add the cumin, lentils and broth, then simmer for 25 minutes."},
{"@type":"HowToStep","text":"Blend half of the soup and stir it back in."}]}]}</script>
</head><body><article><h1>Golden Lentil Soup</h1><p>A cozy weeknight soup.</p></article></body></html>
""".trimStart()

    internal val recipeProject: Map<String, String> = mapOf(
        "index.html" to RECIPE_HTML, "style.css" to RECIPE_CSS, "js/storage.js" to RECIPE_STORAGE, "js/recipes.js" to RECIPE_JS,
        "tests/fixtures/soup.html" to SOUP_PAGE)

    private const val RECIPE_CHECK = """
const R2 = p => { delete require.cache[path.resolve(p)]; return require(path.resolve(p)); };
const rec = R2('js/recipes.js'), Store = R2('js/storage.js');
// instructions may be kept as a list of steps or as text with one step per line
const steps = v => (Array.isArray(v) ? v.map(s => typeof s === 'string' ? s : (s && s.text) || '') : String(v == null ? '' : v).split(/\n+/)).map(s => String(s).trim()).filter(Boolean);
const page = obj => '<html><head><script type="application/ld+json">' + JSON.stringify(obj) + '</script></head><body></body></html>';
function want(html, list, what) {
  const r = rec.parseRecipe(html);
  if (!r) fail(what + ': no recipe was read');
  const got = steps(r.instructions);
  if (JSON.stringify(got) !== JSON.stringify(list)) fail(what + ': the instructions read were ' + JSON.stringify(got) + ', not ' + JSON.stringify(list));
  return r;
}
const soup = want(T('tests/fixtures/soup.html'), ['Rinse the lentils until the water runs clear.', 'Soften the onion and carrots in a little oil for 5 minutes.',
  'Add the cumin, lentils and broth, then simmer for 25 minutes.', 'Blend half of the soup and stir it back in.'], 'the saved soup page');
if (soup.name !== 'Golden Lentil Soup' || soup.servings !== 4 || soup.ingredients.length !== 5 || soup.ingredients[0].name !== 'red lentils') fail('the name, servings or ingredients are no longer read right: ' + JSON.stringify(soup).slice(0, 300));
// pages it never saw: sections of steps, steps as plain strings, one block of text, no instructions at all
want(page({ '@type': 'Recipe', name: 'Flatbread', recipeYield: '6 pieces', recipeIngredient: ['2 cups flour'], recipeInstructions: [
  { '@type': 'HowToSection', name: 'Dough', itemListElement: [{ '@type': 'HowToStep', text: 'Mix the flour and water.' }, { '@type': 'HowToStep', text: 'Rest it for 10 minutes.' }] },
  { '@type': 'HowToSection', name: 'Cook', itemListElement: [{ '@type': 'HowToStep', text: 'Fry each piece in a dry pan.' }] }] }),
  ['Mix the flour and water.', 'Rest it for 10 minutes.', 'Fry each piece in a dry pan.'], 'a page with sections of steps');
want(page([{ '@type': ['Recipe'], name: 'Tea', recipeIngredient: ['1 tsp tea'], recipeInstructions: ['Boil water.', 'Steep for 3 minutes.'] }]), ['Boil water.', 'Steep for 3 minutes.'], 'a page with steps as plain text');
want(page({ '@type': 'Recipe', name: 'Toast', recipeIngredient: ['1 slice bread'], recipeInstructions: 'Toast the bread.\nButter it.' }), ['Toast the bread.', 'Butter it.'], 'a page with the steps in one block of text');
const none = rec.parseRecipe(page({ '@type': 'Recipe', name: 'Salad', recipeIngredient: ['1 lettuce'] }));
if (!none || steps(none.instructions).length) fail('a page without instructions gives ' + JSON.stringify(none && none.instructions));
if (rec.parseRecipe('<html><body>no data</body></html>') !== null) fail('a page with no recipe data no longer gives null');
// saving keeps them
const mem = { v: {}, getItem(k) { return this.v[k] || null; }, setItem(k, s) { this.v[k] = String(s); } };
const data = Store.load(mem);
Store.addRecipe(data, Object.assign({}, soup, { url: 'https://example.test/soup' }));
Store.save(mem, data);
const back = Store.load(mem).recipes[0];
if (JSON.stringify(steps(back.instructions)) !== JSON.stringify(steps(soup.instructions))) fail('the instructions are lost when a recipe is saved and loaded: ' + JSON.stringify(back).slice(0, 300));
if (back.name !== 'Golden Lentil Soup' || back.url !== 'https://example.test/soup') fail('saving lost other fields');
// the card shows them in order, and a recipe without them still draws
const card = rec.renderCard(back);
const at = steps(soup.instructions).map(s => card.indexOf(s.replace(/&/g, '&amp;')));
if (at.some(i => i < 0) || at.some((v, i) => i && v < at[i - 1])) fail('the recipe card does not show the instructions in order');
let plain; try { plain = rec.renderCard({ name: 'Old', servings: 2, ingredients: [] }); } catch (e) { fail('a recipe saved before instructions existed no longer draws: ' + e.message); }
if (!plain.includes('Old')) fail('a recipe without instructions does not draw');
// the form has a place to write them, and the page reads it when saving and fills it when importing
const html = T('index.html');
const field = /<(textarea|input)\b[^>]*\bid=["']([^"']*instruction[^"']*)["']/i.exec(html);
if (!field) fail('the recipe form has no instructions field');
const js = T('js/recipes.js');
if ((js.split(field[2]).length - 1) < 2) fail('the page does not both read and fill the #' + field[2] + ' field (it is used ' + (js.split(field[2]).length - 1) + ' time(s) in js/recipes.js)');
"""

    /** JS for a check: runs game/main.js as the page does (save.js and inv.js first, a fake window and document), as `page`. */
    private const val IN_PAGE = """
function inPage() {
  const m = new Map();
  const store = { getItem: k => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)), removeItem: k => m.delete(k) };
  const on = {};
  const listen = (n, f) => { (on[n] = on[n] || []).push(f); };
  const el = () => { const cls = new Set(); return { style: {}, dataset: {}, children: [], hidden: false, appendChild(c) { this.children.push(c); }, addEventListener: listen, set innerHTML(v) { this.children = []; },
    classList: { add: c => cls.add(c), remove: c => cls.delete(c), toggle: (c, on) => ((on === undefined ? !cls.has(c) : on) ? cls.add(c) : cls.delete(c)), contains: c => cls.has(c) },
    get className() { return [...cls].join(' '); }, set className(v) { cls.clear(); String(v).split(/\s+/).filter(Boolean).forEach(c => cls.add(c)); } }; };
  const els = { side: el(), minibox: el(), game3d: el(), game: el() };
  global.window = { localStorage: store, addEventListener: listen, document: null };
  global.document = { body: el(), getElementById: id => els[id] || null, createElement: el, addEventListener: listen, visibilityState: 'visible' };
  global.localStorage = store;
  for (const f of ['game/save.js', 'game/inv.js', 'game/main.js']) delete require.cache[path.resolve(f)];
  const S = R('game/save.js'); global.save = S.save; global.load = S.load; global.START = S.START; global.KEY = S.KEY;
  global.Inventory = R('game/inv.js').Inventory;
  R('game/main.js');
  const g = global.window.__game;
  if (!g || !g.bag || !g.me) fail('the game no longer starts in the page (window.__game.bag and .me are gone)');
  const close = () => { global.document.visibilityState = 'hidden'; for (const n of ['visibilitychange', 'pagehide', 'beforeunload', 'unload']) for (const f of on[n] || []) f({ type: n }); };
  return { g, store, S, close };
}
"""

    val ALL: List<Task> = listOf(
        Task("rp-bank-option", "Long pressing a bank booth shows Bank and Collect, but choosing Bank does nothing. Doors and trees work. Fix it.", project, rounds = 40,
            check = check(common + """
const { objectAction } = R('game/main.js');
function api() { const o = { bank: 0, door: 0, chopped: 0, said: [] }; o.openBank = () => o.bank++; o.openDoor = () => o.door++; o.chop = () => o.chopped++; o.say = t => o.said.push(t); return o; }
let a = api(); objectAction({ name: 'Bank booth', actions: ['Bank', 'Collect'] }, 'Bank', a); if (a.bank !== 1) fail('Bank on a bank booth does not open the bank');
a = api(); objectAction({ name: 'Bank booth', actions: ['Bank', 'Collect'] }, 'bank', a); if (a.bank !== 1) fail('the option name is matched by case');
a = api(); objectAction({ name: 'Bank chest', actions: ['Use'] }, 'Use', a); if (a.bank !== 1) fail('Use on a bank chest no longer opens the bank');
a = api(); objectAction({ name: 'Bank booth', actions: ['Bank', 'Collect'] }, 'Collect', a); if (a.bank !== 0) fail('Collect opens the bank too');
a = api(); objectAction({ name: 'Door', actions: ['Open'] }, 'Open', a); if (a.door !== 1 || a.bank !== 0) fail('doors broke');
a = api(); objectAction({ name: 'Tree', actions: ['Chop down'] }, 'Chop down', a); if (a.chopped !== 1) fail('trees broke');
a = api(); objectAction({ name: 'Crate', actions: ['Bank'] }, 'Bank', a); if (a.bank !== 0) fail('Bank opens the bank from anything, not only booths and chests');
""", after = listOf("node --test --test-reporter=dot"))),

        Task("rp-menu-hidden", "Long pressing an item in my inventory opens its options, but the menu is hidden behind the side panel so I can't tap them.", project, rounds = 40,
            check = check(common + keep(project, listOf("game/main.js", "game/equipment.js")) + """
// The menu's real z-index: the inline one wins over the stylesheet's when it is set.
const { Inventory } = R('game/inv.js');
function el() { return { style: { cssText: '' }, children: [], appendChild(c) { this.children.push(c); }, set innerHTML(v) { this.children = []; } }; }
const body = el(); const made = {};
const doc = { body, getElementById: id => made[id] || null, createElement: () => { const e = el(); return e; } };
const inv = new Inventory(doc);
const m = inv.openMenu(0, 300, 300, ['Eat Shrimps', 'Drop Shrimps', 'Cancel']);
const css = T('game/style.css');
function ruleZ(sel) { const r = new RegExp(sel.replace('#', '\\#') + '\\s*\\{([^}]*)\\}').exec(css); if (!r) return null; const z = /z-index\s*:\s*(\d+)/.exec(r[1]); return z ? Number(z[1]) : null; }
const side = ruleZ('#side');
if (side == null || side < 100) fail('the side panel was moved below the HUD (#side z-index ' + side + '); raise the menu instead');
const inline = /z-index\s*:\s*(\d+)/.exec(m.style.cssText || '') || (m.style.zIndex ? [0, m.style.zIndex] : null);
const z = inline ? Number(inline[1]) : ruleZ('#invmenu');
if (z == null) fail('the menu has no z-index at all');
if (!(z > side)) fail('the menu is still under the side panel: its z-index is ' + z + ', the side panel\'s ' + side);
if (m.children.length !== 3) fail('the menu does not show its options any more');
""", after = listOf("node --test --test-reporter=dot"))),
        // 1.5.102: replaces rp-equip-slot, which moved to the hard suite after it shaped the "fix the code, not the data it reads" note
        Task("rp-save-position", "When I close the game and open it again I'm back at the start instead of where I was. My inventory comes back fine.", project, rounds = 40,
            check = check(common + keep(project, listOf("game/main.js", "game/inv.js", "game/equipment.js", "game/data/equipment.json")) + """
const { save, load, START } = R('game/save.js');
function store(init) { const m = new Map(Object.entries(init || {})); return { getItem: k => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)), m }; }
let st = store();
const fresh = load(st);
if (fresh.me.x !== 3222 || fresh.me.z !== 3218) fail('a new player no longer starts at the start: ' + JSON.stringify(fresh.me));
save(st, { x: 3185, z: 3443 }, [1277, 1635]);
let back = load(st);
if (back.me.x !== 3185 || back.me.z !== 3443) fail('the position is not restored: ' + JSON.stringify(back.me));
if (JSON.stringify(back.inv) !== '[1277,1635]') fail('the inventory is not restored any more: ' + JSON.stringify(back.inv));
save(st, { x: 3100, z: 3200 }, []);
back = load(st);
if (back.me.x !== 3100 || back.me.z !== 3200) fail('a second save is not restored: ' + JSON.stringify(back.me));
// a save written before the fix (the format on players' phones) still loads
st = store({ 'tile-game-save': JSON.stringify({ pos: { x: 3000, z: 3300 }, inv: [995] }) });
back = load(st);
if (back.me.x !== 3000 || back.me.z !== 3300) fail('saves already on players\' phones do not load: ' + JSON.stringify(back.me));
if (JSON.stringify(back.inv) !== '[995]') fail('the old save lost its inventory');
""", after = listOf("node --test --test-reporter=dot"))),

        Task("rp-four-asks", "A few things for the game. I can't chop maple trees: Chop down does nothing on them, though it works on normal trees, oaks and willows. Make maples choppable too. Chopping should also say \"You swing your axe at the tree.\" The game should remember which way I'm facing (me.dir: north, south, east or west) when I close it, like it does my position; old saves don't have it, so those start facing south. And make the side panel 240px wide.", fourAsks, rounds = 40,
            check = check(common + keep(fourAsks, listOf("game/inv.js", "game/equipment.js", "game/data/equipment.json")) + """
const { objectAction } = R('game/main.js');
function api() { const o = { bank: 0, door: 0, chopped: 0, said: [] }; o.openBank = () => o.bank++; o.openDoor = () => o.door++; o.chop = () => o.chopped++; o.say = t => o.said.push(t); return o; }
for (const n of ['Maple tree', 'Tree', 'Oak', 'Willow']) {
  const a = api(); objectAction({ name: n, actions: ['Chop down'] }, 'Chop down', a);
  if (a.chopped !== 1) fail(n + ' cannot be chopped');
  if (!a.said.includes('You swing your axe at the tree.')) fail('chopping ' + n + ' does not say "You swing your axe at the tree.": ' + JSON.stringify(a.said));
}
let a = api(); objectAction({ name: 'Door', actions: ['Open'] }, 'Open', a); if (a.door !== 1 || a.chopped) fail('doors broke');
a = api(); objectAction({ name: 'Bank chest', actions: ['Use'] }, 'Use', a); if (a.bank !== 1) fail('the bank chest broke');
a = api(); objectAction({ name: 'Door', actions: ['Open'] }, 'Chop down', a); if (a.chopped) fail('doors can be chopped');
const { save, load } = R('game/save.js');
function store(init) { const m = new Map(Object.entries(init || {})); return { getItem: k => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)) }; }
let st = store(); save(st, { x: 3185, z: 3443, dir: 'west' }, [1277]);
let back = load(st);
if (back.me.dir !== 'west') fail('the facing is not remembered: ' + JSON.stringify(back.me));
if (back.me.x !== 3185 || back.me.z !== 3443 || JSON.stringify(back.inv) !== '[1277]') fail('the position or inventory is not restored any more: ' + JSON.stringify(back));
st = store(); save(st, { x: 3100, z: 3200, dir: 'north' }, []); if (load(st).me.dir !== 'north') fail('a second facing is not remembered');
st = store({ 'tile-game-save': JSON.stringify({ pos: { x: 3000, z: 3300 }, inv: [995] }) });
back = load(st);
if (back.me.dir !== 'south') fail('an old save does not start facing south: ' + JSON.stringify(back.me));
if (back.me.x !== 3000 || back.me.z !== 3300 || JSON.stringify(back.inv) !== '[995]') fail('old saves do not load any more: ' + JSON.stringify(back));
const css = T('game/style.css');
const side = /#side\s*\{([^}]*)\}/.exec(css);
if (!side || !/width\s*:\s*240px/.test(side[1])) fail('the side panel is not 240px wide: ' + (side ? side[1] : 'no #side rule'));
if (!/z-index\s*:\s*100\b/.test(side[1])) fail('the side panel lost its z-index');
""", after = listOf("node --test --test-reporter=dot"))),

        Task("rp-fishing", "Add fishing. Long pressing a fishing spot already shows its options, Net and Bait. Choosing Net should put Shrimps (item 315) in my inventory and say \"You catch some shrimps.\" If my inventory is full it should say \"Your inventory is too full to hold any more.\" and give nothing. The game gives objectAction an api.addItem(id) that returns false when the inventory is full. Bait isn't ready yet: choosing it should just say \"You need some bait.\"", project, rounds = 40,
            check = check(common + keep(project, listOf("game/inv.js", "game/save.js", "game/equipment.js", "game/data/equipment.json")) + """
const { objectAction, objectMenu } = R('game/main.js');
function api(room) { const o = { bank: 0, door: 0, chopped: 0, said: [], got: [] }; o.openBank = () => o.bank++; o.openDoor = () => o.door++; o.chop = () => o.chopped++; o.say = t => o.said.push(t); o.addItem = id => { if (!room) return false; o.got.push(id); return true; }; return o; }
const spot = { name: 'Fishing spot', actions: ['Net', 'Bait'] };
let a = api(true); objectAction(spot, 'Net', a);
if (JSON.stringify(a.got) !== '[315]') fail('Net does not give one Shrimps (315): ' + JSON.stringify(a.got));
if (!a.said.includes('You catch some shrimps.')) fail('Net does not say "You catch some shrimps.": ' + JSON.stringify(a.said));
a = api(true); objectAction(spot, 'net', a); if (JSON.stringify(a.got) !== '[315]') fail('the option name is matched by case');
a = api(false); objectAction(spot, 'Net', a);
if (a.got.length) fail('a full inventory still gets the fish');
if (!a.said.includes('Your inventory is too full to hold any more.')) fail('a full inventory does not say so: ' + JSON.stringify(a.said));
if (a.said.includes('You catch some shrimps.')) fail('a full inventory still says a fish was caught');
a = api(true); objectAction(spot, 'Bait', a);
if (a.got.length || !a.said.includes('You need some bait.')) fail('Bait does not just say "You need some bait.": ' + JSON.stringify(a));
a = api(true); objectAction({ name: 'Crate', actions: ['Net'] }, 'Net', a); if (a.got.length) fail('Net gives fish from anything, not only fishing spots');
a = api(true); objectAction({ name: 'Door', actions: ['Open'] }, 'Open', a); if (a.door !== 1 || a.got.length) fail('doors broke');
a = api(true); objectAction({ name: 'Tree', actions: ['Chop down'] }, 'Chop down', a); if (a.chopped !== 1) fail('trees broke');
a = api(true); objectAction({ name: 'Bank booth', actions: ['Use'] }, 'Use', a); if (a.bank !== 1) fail('the bank broke');
const labels = objectMenu(spot).map(o => o.label);
if (labels[0] !== 'Net Fishing spot' || labels[1] !== 'Bait Fishing spot' || labels[labels.length - 1] !== 'Cancel') fail('the fishing spot menu changed: ' + labels.join(', '));
""", after = listOf("node --test --test-reporter=dot"))),

        Task("rp-first-slot", "If I drop whatever is in my first inventory slot, next time I open the game my whole inventory is empty. Other times it comes back fine.", firstSlot, rounds = 40,
            check = check(common + keep(firstSlot, listOf("game/inv.js", "game/equipment.js", "game/data/equipment.json")) + IN_PAGE + """
let p = inPage();
p.g.bag.slots = [null, 1277, 1635].concat(new Array(25).fill(null));
p.close();
let back = p.S.load(p.store).inv.filter(x => x != null);
if (!(back.includes(1277) && back.includes(1635))) fail('emptying the first slot still loses the inventory: saved ' + JSON.stringify(back));
p = inPage();
p.g.bag.slots = new Array(28).fill(995);
p.close();
back = p.S.load(p.store).inv.filter(x => x != null);
if (back.length !== 28) fail('a full inventory is not all saved: ' + back.length + ' of 28');
p = inPage();
p.g.bag.slots = [1277, null, 1635].concat(new Array(25).fill(null));
p.close();
back = p.S.load(p.store).inv.filter(x => x != null);
if (!(back.includes(1277) && back.includes(1635))) fail('a gap in the middle of the bag loses items: ' + JSON.stringify(back));
p = inPage();
const before = p.g.me.x;
p.g.me.x = 3150; p.close();
if (p.S.load(p.store).me.x !== 3150) fail('the position is not saved any more');
""", after = listOf("node --test --test-reporter=dot"))),

        // 1.5.116: from the owner's requests (see the note above MARK)
        Task("rp-not-defined", "\"food is not defined\" appears when I long press the rune essence and when I long press the tinderbox. Neither of them is food.", notDefined, rounds = 40,
            check = check(common + keep(notDefined, listOf("game/inv.js", "game/save.js", "game/equipment.js", "game/data/equipment.json")) + """
const { itemOptions } = R('game/main.js');
const labels = it => { try { return itemOptions(it).map(o => o.label); } catch (e) { fail('long pressing ' + it.name + ' throws: ' + e.message); } };
for (const it of [{ id: 1436, name: 'Rune essence' }, { id: 590, name: 'Tinderbox' }, { id: 1277, name: 'Bronze sword' }, { id: 995, name: 'Coins' }]) {
  const l = labels(it);
  if (l.join('|') !== ['Use ', 'Drop ', 'Examine '].map(a => a + it.name).concat(['Cancel']).join('|')) fail(it.name + ' shows ' + JSON.stringify(l));
}
let o = itemOptions({ id: 315, name: 'Shrimps' });
if (o[0].label !== 'Eat Shrimps' || o[0].heals !== 3) fail('Shrimps no longer start with Eat (heals 3): ' + JSON.stringify(o[0]));
o = itemOptions({ id: 2309, name: 'Bread' });
if (o[0].label !== 'Eat Bread' || o[0].heals !== 5) fail('Bread no longer starts with Eat (heals 5)');
const b = labels({ id: 526, name: 'Bones' });
if (b[0] !== 'Bury Bones' || b.includes('Eat Bones')) fail('Bones show ' + JSON.stringify(b));
""", after = listOf("node --test --test-reporter=dot"))),

        Task("rp-bury", "Bones no longer bury. Long pressing them only shows Use, Drop and Examine, and I can't bury big bones either.", bury, rounds = 40,
            check = check(common + keep(bury, listOf("game/inv.js", "game/save.js", "game/equipment.js", "game/data/equipment.json")) + """
const { itemOptions, itemAction } = R('game/main.js');
function api() { const o = { removed: [], xp: [], healed: 0, said: [] }; o.remove = it => o.removed.push(it.id); o.addXp = (s, n) => o.xp.push([s, n]); o.heal = n => { o.healed += n; }; o.say = t => o.said.push(t); return o; }
for (const [id, name, xp] of [[526, 'Bones', 4.5], [532, 'Big bones', 15]]) {
  const l = itemOptions({ id, name }).map(o => o.label);
  if (l[0] !== 'Bury ' + name) fail(name + ' do not offer Bury first: ' + JSON.stringify(l));
  const a = api(); itemAction({ id, name }, 'Bury', a);
  if (JSON.stringify(a.removed) !== '[' + id + ']') fail('burying ' + name + ' does not take them from the inventory');
  if (JSON.stringify(a.xp) !== JSON.stringify([['prayer', xp]])) fail('burying ' + name + ' gives ' + JSON.stringify(a.xp) + ', not ' + xp + ' Prayer experience');
  if (!a.said.includes('You bury the bones.')) fail('burying says ' + JSON.stringify(a.said));
}
for (const [id, name] of [[315, 'Shrimps'], [590, 'Tinderbox']]) {
  if (itemOptions({ id, name }).some(o => /^Bury/.test(o.label))) fail(name + ' can be buried');
  const a = api(); itemAction({ id, name }, 'Bury', a); if (a.removed.length || a.xp.length) fail(name + ' were buried');
}
const a = api(); itemAction({ id: 315, name: 'Shrimps' }, 'Eat', a); if (a.healed !== 3) fail('eating broke');
""", after = listOf("node --test --test-reporter=dot"))),

        Task("rp-hitsplats", "Hit splats and health bars never show when I fight in the 3D view. Combat itself works: the chat says \"You hit the chicken for 1.\" Taps on the world still need to reach the 3D view.", hitSplats, rounds = 40,
            check = check(common + keep(hitSplats, listOf("game/inv.js", "game/save.js", "game/equipment.js", "game/data/equipment.json")) + IN_PAGE + """
const p = inPage();
const flat = p.g.flat || global.document.getElementById('game'), v3 = p.g.view3d || global.document.getElementById('game3d');
const css = T('game/style.css');
// the declarations that apply to an element: rules whose selector list names #id (or #id.class it has), in order, then its inline style
function decl(id, el, prop) {
  let val = null;
  for (const m of css.matchAll(/([^{}]+)\{([^}]*)\}/g)) {
    const hit = m[1].split(',').map(s => s.trim()).some(s => s === '#' + id || (s.startsWith('#' + id + '.') && s.slice(id.length + 2).split('.').every(c => el.classList.contains(c))));
    if (!hit) continue;
    const d = new RegExp('(?:^|;)\\s*' + prop + '\\s*:\\s*([^;!]+)').exec(m[2]); if (d) val = d[1].trim();
  }
  const camel = prop.replace(/-(\w)/g, (_, c) => c.toUpperCase());
  if (el.style && el.style[camel] != null && el.style[camel] !== '') val = String(el.style[camel]);
  return val;
}
if (v3.hidden || decl('game3d', v3, 'display') === 'none') fail('the 3D view is hidden now');
if (flat.hidden) fail('the 2D canvas the hit splats are drawn on is still hidden in the 3D view');
if (decl('game', flat, 'display') === 'none' || decl('game', flat, 'visibility') === 'hidden') fail('the 2D canvas is not shown');
const zf = Number(decl('game', flat, 'z-index')), z3 = Number(decl('game3d', v3, 'z-index'));
if (!(zf > z3)) fail('the hit splats are drawn under the 3D view (z-index ' + zf + ' vs ' + z3 + ')');
if (decl('game', flat, 'pointer-events') !== 'none') fail('the overlay now catches the taps meant for the 3D view');
""", after = listOf("node --test --test-reporter=dot"))),

        Task("rp-loot", "Monsters never drop anything when they die, and coins go straight into my inventory instead of dropping on the ground like everything else.", loot, rounds = 40,
            check = check(common + keep(loot, listOf("game/inv.js", "game/save.js", "game/equipment.js", "game/data/equipment.json")) + """
const { dropLoot, CHICKEN_DROPS } = R('game/main.js');
function api() { const o = { ground: [], bag: [], said: [] }; o.dropOnGround = (id, n, x, z) => o.ground.push([id, n, x, z]); o.addItem = (id, n) => { o.bag.push([id, n]); return true; }; o.say = t => o.said.push(t); return o; }
const chick = { name: 'Chicken', x: 3235, z: 3295 };
const real = Math.random;
function roll(r, drops) { Math.random = () => r; try { const a = api(); dropLoot(chick, drops, a); return a; } finally { Math.random = real; } }
let a = roll(0.99, [{ id: 526, n: 1, rate: 1 }, { id: 995, n: 5, rate: 1 }]);
if (!a.ground.some(g => g[0] === 526 && g[1] === 1 && g[2] === 3235 && g[3] === 3295)) fail('a sure drop does not land where the monster died: ' + JSON.stringify(a.ground));
if (!a.ground.some(g => g[0] === 995 && g[1] === 5 && g[2] === 3235 && g[3] === 3295)) fail('coins do not drop on the ground: ' + JSON.stringify(a.ground));
if (a.bag.length) fail('loot still goes into the inventory: ' + JSON.stringify(a.bag));
a = roll(0, [{ id: 526, n: 1, rate: 0 }, { id: 995, n: 9, rate: 0 }]);
if (a.ground.length || a.bag.length) fail('a drop with rate 0 still drops');
a = roll(0.4, [{ id: 526, n: 1, rate: 0.5 }]); if (a.ground.length !== 1) fail('a 50% drop does not drop on a roll of 0.4');
a = roll(0.6, [{ id: 526, n: 1, rate: 0.5 }, { id: 995, n: 5, rate: 0.5 }]); if (a.ground.length) fail('a 50% drop drops on a roll of 0.6: ' + JSON.stringify(a.ground));
if (!Array.isArray(CHICKEN_DROPS) || CHICKEN_DROPS[0].rate !== 1 || CHICKEN_DROPS[0].id !== 526) fail('the chicken\'s drop list changed');
""", after = listOf("node --test --test-reporter=dot"))),

        Task("rp-door-tap", "Tapping a door once makes me walk around it to the other side instead of opening it. Long pressing it and choosing Open works. Gates do the same.", doorTap, rounds = 40,
            check = check(common + keep(doorTap, listOf("game/inv.js", "game/save.js", "game/equipment.js", "game/data/equipment.json")) + """
const { tapAction, objectAction } = R('game/main.js');
const cases = [
  [{ name: 'Door', type: 0, actions: ['Open', null, null, null, null] }, 'Open'],
  [{ name: 'Large door', type: 0, rot: 2, actions: ['Open'] }, 'Open'],
  [{ name: 'Gate', type: 9, actions: ['Open'] }, 'Open'],
  [{ name: 'Door', type: 2, actions: ['Open'] }, 'Open'],
  [{ name: 'Tree', type: 10, actions: ['Chop down'] }, 'Chop down'],
  [{ name: 'Bank booth', type: 10, actions: ['Bank', 'Collect'] }, 'Bank'],
  [{ name: 'Wall', type: 0, actions: [] }, 'walk'],
  [{ name: 'Wall', type: 0, actions: [null, null] }, 'walk'],
  [{ name: 'Fence', type: 9, actions: [] }, 'walk'],
  [null, 'walk'],
];
for (const [o, want] of cases) { const got = tapAction(o); if (got !== want) fail('tapping ' + JSON.stringify(o) + ' gives ' + JSON.stringify(got) + ', not ' + want); }
let opened = 0; objectAction({ name: 'Door' }, 'Open', { openDoor: () => opened++, openBank() {}, chop() {}, say() {} }); if (opened !== 1) fail('doors no longer open');
""", after = listOf("node --test --test-reporter=dot"))),

        Task("rp-tinderbox", "You don't use the tinderbox when cooking. The tinderbox is used on logs to light a fire (take the logs and call api.lightFire()), and raw shrimps are cooked by using them on a fire (api.cook(raw, cooked)). Using the tinderbox on raw shrimps should do nothing.", tinderbox, rounds = 40,
            check = check(common + keep(tinderbox, listOf("game/inv.js", "game/save.js", "game/equipment.js", "game/data/equipment.json")) + """
const { useItemOn, useItemOnObject } = R('game/main.js');
function api() { const o = { said: [], removed: [], fires: 0, cooked: [] }; o.say = t => o.said.push(t); o.remove = id => o.removed.push(id); o.lightFire = () => o.fires++; o.cook = (r, c) => o.cooked.push([r, c]); return o; }
for (const [x, y] of [[590, 1511], [1511, 590]]) {
  const a = api(); useItemOn(x, y, a);
  if (a.fires !== 1) fail('the tinderbox on logs (' + x + ' on ' + y + ') does not light a fire');
  if (JSON.stringify(a.removed) !== '[1511]') fail('lighting a fire does not use up the logs: ' + JSON.stringify(a.removed));
  if (a.cooked.length) fail('lighting a fire cooks something');
}
for (const [x, y] of [[590, 317], [317, 590]]) {
  const a = api(); useItemOn(x, y, a);
  if (a.cooked.length || a.fires) fail('the tinderbox on raw shrimps still does something: ' + JSON.stringify(a));
  if (!a.said.includes('Nothing interesting happens.')) fail('the tinderbox on raw shrimps does not say "Nothing interesting happens."');
}
let a = api(); useItemOnObject(317, { name: 'Fire', actions: [] }, a);
if (JSON.stringify(a.cooked) !== '[[317,315]]') fail('raw shrimps used on a fire do not cook into shrimps: ' + JSON.stringify(a.cooked));
a = api(); useItemOnObject(317, { name: 'Tree', actions: ['Chop down'] }, a); if (a.cooked.length) fail('raw shrimps cook on a tree');
a = api(); useItemOnObject(1511, { name: 'Fire', actions: [] }, a); if (a.cooked.length) fail('logs cook on a fire');
a = api(); useItemOn(590, 1277, a); if (a.fires || a.cooked.length) fail('the tinderbox on a sword does something');
""", after = listOf("node --test --test-reporter=dot"))),

        // 1.5.122: the owner's "Web page project" (BENCHMARKS.md, field run): the request they made that was never done
        Task("rp-recipe-steps", "Why don't we have anywhere for recipe instructions? We have ingredients, but recipes use instructions on how to prepare it with the ingredients. " +
            "Add them, and when I import a recipe link, fill them in from the page too. tests/fixtures/soup.html is a recipe page I saved to test the import with.",
            recipeProject, rounds = 40,
            check = check(keep(recipeProject, listOf("tests/fixtures/soup.html")) + RECIPE_CHECK)),
    )
}
