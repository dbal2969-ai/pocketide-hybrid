package dev.pocketide.app.bench

import dev.pocketide.app.bench.BenchTasks.Task
import dev.pocketide.app.bench.HardBenchTasks.check
import dev.pocketide.app.bench.HardBenchTasks.keep

/**
 * The tuning suite for CauseGate (POCKETIDE_BENCH_SUITE=cause, 1.5.147). Each task is a problem report whose symptom can be made to
 * go away in the wrong place: a data file (shipping rates, a menu's action list, a scores table) or a shared helper used elsewhere.
 * The hidden check needs the real cause fixed and that file left exactly as it was. Written for new domains, so the realproject
 * tasks (whose largest failure group is this pattern) stay the held-out score.
 */
object CauseBenchTasks {
    private const val PKG = "{\n  \"name\": \"app\",\n  \"private\": true,\n  \"scripts\": { \"test\": \"node --test\" }\n}\n"
    private fun test(body: String) = "const test = require('node:test');\nconst assert = require('assert');\n" + body.trimStart()

    // ---- cause-shipping: the threshold is multiplied by 1000 instead of 100; editing the rates data hides it ----
    val shipping = mapOf(
        "package.json" to PKG,
        "data/rates.json" to "{\n  \"freeFrom\": 50,\n  \"flat\": 4.95\n}\n",
        "src/shipping.js" to """
const rates = require('../data/rates.json');

// Shipping for an order, in cents. order.total is in cents; the rates are in euros.
function shipping(order) {
  if (order.total >= rates.freeFrom * 1000) return 0;
  return Math.round(rates.flat * 100);
}

module.exports = { shipping };
""".trimStart(),
        "src/checkout.js" to """
const { shipping } = require('./shipping.js');

function summary(order) {
  const ship = shipping(order);
  return { total: order.total, shipping: ship, pay: order.total + ship };
}

module.exports = { summary };
""".trimStart(),
        "test/checkout.test.js" to test("""
const { summary } = require('../src/checkout.js');

test('a small order pays shipping', () => {
  assert.strictEqual(summary({ total: 1000 }).shipping, 495);
});
"""),
    )

    val causeShipping = Task("cause-shipping",
        "Orders of 50 euros or more should ship free, but a 60-euro order still pays 4.95 for shipping. Fix it.",
        shipping, check("""
const { shipping } = R('src/shipping.js');
const { summary } = R('src/checkout.js');
if (shipping({ total: 6000 }) !== 0) fail('a 60-euro order should ship free, got ' + shipping({ total: 6000 }));
if (shipping({ total: 5000 }) !== 0) fail('a 50-euro order should ship free, got ' + shipping({ total: 5000 }));
if (shipping({ total: 4999 }) !== 495) fail('a 49.99-euro order should pay 4.95, got ' + shipping({ total: 4999 }));
if (summary({ total: 6000 }).pay !== 6000) fail('a 60-euro order should pay 60.00 in all, got ' + summary({ total: 6000 }).pay);
""" + keep(shipping, listOf("data/rates.json")) + "testsPass();\n"), rounds = 30)

    // ---- cause-menu: the filter reads note.status, the notes have state; deleting the condition in the data hides it ----
    val notes = mapOf(
        "package.json" to PKG,
        "data/actions.json" to """
[
  { "id": "edit", "label": "Edit" },
  { "id": "pin", "label": "Pin", "when": "unpinned" },
  { "id": "unpin", "label": "Unpin", "when": "pinned" },
  { "id": "delete", "label": "Delete" }
]
""".trimStart(),
        "src/notes.js" to """
// A note as the app keeps it: its text and whether it is pinned ("pinned" or "unpinned").
function note(text, pinned) { return { text, state: pinned ? 'pinned' : 'unpinned' }; }

module.exports = { note };
""".trimStart(),
        "src/menu.js" to """
const actions = require('../data/actions.json');

// The labels in a note's long-press menu. Actions with "when" show only for notes in that state.
function menu(note) {
  return actions.filter(a => a.when === undefined || a.when === note.status).map(a => a.label);
}

module.exports = { menu };
""".trimStart(),
        "test/menu.test.js" to test("""
const { menu } = require('../src/menu.js');
const { note } = require('../src/notes.js');

test('every note can be edited and deleted', () => {
  const m = menu(note('x', false));
  assert.ok(m.includes('Edit') && m.includes('Delete'));
});
"""),
    )

    val causeMenu = Task("cause-menu",
        "Long-pressing a note shows Edit and Delete, but never Pin, so I can't pin anything. Fix it.",
        notes, check("""
const { menu } = R('src/menu.js');
const { note } = R('src/notes.js');
const a = JSON.stringify(menu(note('a', false)));
const b = JSON.stringify(menu(note('b', true)));
if (a !== JSON.stringify(['Edit', 'Pin', 'Delete'])) fail('an unpinned note should show Edit, Pin, Delete, got ' + a);
if (b !== JSON.stringify(['Edit', 'Unpin', 'Delete'])) fail('a pinned note should show Edit, Unpin, Delete, got ' + b);
""" + keep(notes, listOf("data/actions.json")) + "testsPass();\n"), rounds = 30)

    // ---- cause-badge: the badge passes a plural word to a correct shared helper; changing the helper hides it ----
    val badge = mapOf(
        "package.json" to PKG,
        "src/util/plural.js" to """
// "1 box", "2 boxes": count and the word, singular given. Used across the app.
function plural(n, word) {
  if (n === 1) return n + ' ' + word;
  return n + ' ' + (/(s|x|ch|sh)$/.test(word) ? word + 'es' : word + 's');
}

module.exports = { plural };
""".trimStart(),
        "src/badge.js" to """
const { plural } = require('./util/plural.js');

// The basket badge's text.
function badge(count) { return plural(count, 'items'); }

module.exports = { badge };
""".trimStart(),
        "src/shipping-note.js" to """
const { plural } = require('./util/plural.js');

function boxes(n) { return 'Ships in ' + plural(n, 'box'); }

module.exports = { boxes };
""".trimStart(),
        "test/plural.test.js" to test("""
const { plural } = require('../src/util/plural.js');

test('plural', () => {
  assert.strictEqual(plural(1, 'box'), '1 box');
  assert.strictEqual(plural(2, 'box'), '2 boxes');
});
"""),
    )

    val causeBadge = Task("cause-badge",
        "The basket badge says \"1 items\" when there is one thing in the basket. Fix it.",
        badge, check("""
const { badge } = R('src/badge.js');
const { boxes } = R('src/shipping-note.js');
if (badge(1) !== '1 item') fail('one item should read "1 item", got "' + badge(1) + '"');
if (badge(3) !== '3 items') fail('three items should read "3 items", got "' + badge(3) + '"');
if (badge(0) !== '0 items') fail('no items should read "0 items", got "' + badge(0) + '"');
if (boxes(2) !== 'Ships in 2 boxes' || boxes(1) !== 'Ships in 1 box') fail('the box note changed: ' + boxes(1) + ' / ' + boxes(2));
""" + keep(badge, listOf("src/util/plural.js")) + "testsPass();\n"), rounds = 30)

    // ---- cause-scores: scores are compared as text; reordering the CSV hides it ----
    val scores = mapOf(
        "package.json" to PKG,
        "data/scores.csv" to "name,score\nAda,9\nBo,10\nCy,100\nDi,25\n",
        "src/leaderboard.js" to """
const fs = require('fs');
const path = require('path');

function load(file = path.join(__dirname, '..', 'data', 'scores.csv')) {
  const [, ...rows] = fs.readFileSync(file, 'utf8').trim().split('\n');
  return rows.map(r => { const [name, score] = r.split(','); return { name, score }; });
}

// Highest score first; equal scores by name.
function leaderboard(rows = load()) {
  return rows.slice().sort((a, b) => (a.score < b.score ? 1 : a.score > b.score ? -1 : a.name.localeCompare(b.name)))
    .map(r => r.name + ' ' + r.score);
}

module.exports = { load, leaderboard };
""".trimStart(),
        "test/leaderboard.test.js" to test("""
const { load } = require('../src/leaderboard.js');

test('every row loads', () => {
  assert.strictEqual(load().length, 4);
});
"""),
    )

    val causeScores = Task("cause-scores",
        "The leaderboard puts Ada (9) above Bo (10) and Cy (100). The highest score should be first. Fix it.",
        scores, check("""
const { leaderboard } = R('src/leaderboard.js');
const got = JSON.stringify(leaderboard());
const want = JSON.stringify(['Cy 100', 'Di 25', 'Bo 10', 'Ada 9']);
if (got !== want) fail('the leaderboard should read ' + want + ', got ' + got);
const tie = JSON.stringify(leaderboard([{ name: 'Zed', score: 5 }, { name: 'Amy', score: 5 }, { name: 'Max', score: 40 }]));
if (tie !== JSON.stringify(['Max 40', 'Amy 5', 'Zed 5'])) fail('equal scores go by name, highest first: got ' + tie);
""" + keep(scores, listOf("data/scores.csv")) + "testsPass();\n"), rounds = 30)

    val ALL: List<Task> by lazy { listOf(causeShipping, causeMenu, causeBadge, causeScores) }
}
