package dev.pocketide.app.bench

import dev.pocketide.app.bench.BenchTasks.Task
import dev.pocketide.app.bench.HardBenchTasks.check
import dev.pocketide.app.bench.HardBenchTasks.keep

/**
 * The held-out suite (POCKETIDE_BENCH_SUITE=heldout), added in 1.5.91. Since 1.5.89 the agent's rules have been tuned on the
 * standard and hard suites' transcripts, so their scores now partly measure that tuning. These six tasks were written by a
 * different author and must never be used to tune the agent: run them to measure, do not read their transcripts to design a
 * rule, a prompt note or a finish check. If a rule is ever made from one of them, move that task to the hard suite and write a
 * new held-out task in its place, so the held-out score stays honest. (1.5.93: settings-merge showed every model was refused edits
 * because the request began "When …"; it moved to the hard suite after that fix, and unique-address took its place. 1.5.105:
 * remove-feature showed models had no way to delete a file; it moved to the hard suite with the delete_file tool, and retry-fetch
 * took its place.)
 *
 * Like the hard suite, every check is hidden (written to .bench/ only after the run) and HeldOutBenchTasksTest proves each one
 * fails on the starting project, passes a reference solution and rejects a likely shortcut.
 */
object HeldOutBenchTasks {
    private const val PKG = "{\n  \"name\": \"app\",\n  \"private\": true,\n  \"scripts\": { \"test\": \"node --test\" }\n}\n"

    // ---- paging: two off-by-one bugs, reported only by their symptom ----
    val paging = mapOf(
        "package.json" to PKG,
        "src/pages.js" to """
// Splits a product list into pages for the shop screen. Pages are numbered from 1.
function page(items, n, size = 10) {
  const start = (n - 1) * size;
  return items.slice(start, start + size - 1);
}

function pageCount(items, size = 10) {
  return Math.floor(items.length / size);
}

module.exports = { page, pageCount };
""".trimStart(),
        "src/shop.js" to "const { page, pageCount } = require('./pages.js');\n\n// Every page of the list, for the screen's \"next\" button.\nfunction allPages(items, size) {\n  const out = [];\n  for (let n = 1; n <= pageCount(items, size); n++) out.push(page(items, n, size));\n  return out;\n}\n\nmodule.exports = { allPages };\n",
        "README.md" to "# Shop\n\nThe product list is shown 10 items to a page (`src/pages.js`).\n",
    )

    // ---- cli-flag: a new option; the old command's output must not change ----
    val cli = mapOf(
        "package.json" to PKG,
        "bin/todo.js" to """
#!/usr/bin/env node
// A tiny to-do list kept in a JSON file (TODO_FILE, default todos.json).
//   node bin/todo.js add <text>    adds a to-do
//   node bin/todo.js done <n>      marks to-do n as finished
//   node bin/todo.js list          lists every to-do
const fs = require('fs');
const file = process.env.TODO_FILE || 'todos.json';
const read = () => (fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : []);
const save = list => fs.writeFileSync(file, JSON.stringify(list, null, 2));

const [cmd, ...rest] = process.argv.slice(2);
const list = read();
if (cmd === 'add') { list.push({ text: rest.join(' '), done: false }); save(list); }
else if (cmd === 'done') { const i = Number(rest[0]) - 1; if (list[i]) { list[i].done = true; save(list); } }
else if (cmd === 'list') { list.forEach((t, i) => console.log((i + 1) + '. [' + (t.done ? 'x' : ' ') + '] ' + t.text)); }
else { console.log('usage: todo add <text> | done <n> | list'); }
""".trimStart(),
        "README.md" to "# todo\n\n`node bin/todo.js add buy milk`, `node bin/todo.js done 1`, `node bin/todo.js list`.\n",
    )

    // ---- order-date: the day is taken in the phone's time zone instead of UTC ----
    val orders = mapOf(
        "package.json" to PKG,
        "src/orders.js" to """
const pad = n => String(n).padStart(2, '0');

// The day an order was placed, as YYYY-MM-DD, for the order history.
function orderDay(iso) {
  const d = new Date(iso);
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
}

module.exports = { orderDay };
""".trimStart(),
        "README.md" to "# Orders\n\nOrder times are stored as ISO 8601 strings, for example `2026-03-01T23:30:00Z`.\n",
    )

    // ---- remove-feature: take a feature out completely without breaking the rest ----
    val shop = mapOf(
        "package.json" to PKG,
        "src/format.js" to "function money(x) { return '$' + x.toFixed(2); }\nmodule.exports = { money };\n",
        "src/coupons.js" to "const CODES = { SAVE10: 0.1, HALF: 0.5 };\n\n// The fraction a coupon takes off, or 0.\nfunction couponDiscount(code) { return CODES[(code || '').toUpperCase()] || 0; }\n\nmodule.exports = { couponDiscount };\n",
        "src/cart.js" to """
const { couponDiscount } = require('./coupons.js');

// The cart total, with a coupon code taken off when one is given.
function total(items, coupon) {
  let sum = 0;
  for (const it of items) sum += it.price * it.qty;
  if (coupon) sum = sum * (1 - couponDiscount(coupon));
  return Math.round(sum * 100) / 100;
}

module.exports = { total };
""".trimStart(),
        "src/routes.js" to """
const { total } = require('./cart.js');
const { couponDiscount } = require('./coupons.js');
const { money } = require('./format.js');

// The app's routes: path to handler.
const routes = {
  '/cart': body => ({ total: money(total(body.items || [], body.coupon)) }),
  '/coupons': body => ({ discount: couponDiscount(body.code) }),
};

module.exports = { routes };
""".trimStart(),
        "test/cart.test.js" to "const test = require('node:test');\nconst assert = require('assert');\nconst { total } = require('../src/cart.js');\n\ntest('the cart adds up', () => { assert.strictEqual(total([{ price: 2, qty: 3 }, { price: 1.5, qty: 2 }]), 9); });\ntest('a coupon takes 10% off', () => { assert.strictEqual(total([{ price: 10, qty: 1 }], 'SAVE10'), 9); });\n",
        "test/coupons.test.js" to "const test = require('node:test');\nconst assert = require('assert');\nconst { couponDiscount } = require('../src/coupons.js');\n\ntest('SAVE10 is 10%', () => { assert.strictEqual(couponDiscount('save10'), 0.1); });\n",
        "README.md" to "# Shop\n\nRoutes are in `src/routes.js`. Run the tests with `node --test`.\n",
    )

    // ---- unique-address (1.5.93, replaces settings-merge): posts with the same title overwrite each other ----
    val posts = mapOf(
        "package.json" to PKG,
        "src/slug.js" to """
// "Hello, World!" -> "hello-world"
function slugify(title) {
  return String(title).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
}

module.exports = { slugify };
""".trimStart(),
        "src/posts.js" to """
const { slugify } = require('./slug.js');

// Saves a post under an address made from its title and returns the address.
function addPost(store, title, body) {
  const slug = slugify(title);
  store[slug] = { title, body };
  return slug;
}

module.exports = { addPost };
""".trimStart(),
        "test/posts.test.js" to """
const test = require('node:test');
const assert = require('assert');
const { addPost } = require('../src/posts.js');

test('a post is saved under its address', () => {
  const store = {};
  assert.strictEqual(addPost(store, 'Hello, World!', 'hi'), 'hello-world');
  assert.strictEqual(store['hello-world'].body, 'hi');
});
""".trimStart(),
        "README.md" to "# Blog\n\n`addPost(store, title, body)` saves a post and returns its address.\n",
    )

    /** Moved to the hard suite in 1.5.105: its transcripts shaped the delete_file tool (retry-fetch took its place). */
    val REMOVE_FEATURE: Task =
        // suite: hard  (tools/bench/run-bench.sh reads this marker)
        Task("remove-feature", "We dropped coupons. Remove the coupon feature completely, its code, its route and its tests, and keep the cart working.", shop, rounds = 24, check = check("""
if (fs.existsSync('src/coupons.js')) fail('src/coupons.js is still there');
for (const p of walk('.')) {
  if (!(p.startsWith('src/') || p.startsWith('test/'))) continue;
  if (/coupon/i.test(T(p))) fail(p + ' still mentions coupons');
}
const { total } = R('src/cart.js');
if (total([{ price: 2, qty: 3 }, { price: 1.5, qty: 2 }]) !== 9) fail('the cart total is wrong');
const { routes } = R('src/routes.js');
if (typeof routes['/cart'] !== 'function') fail('the /cart route is gone');
if (routes['/coupons']) fail('the /coupons route is still there');
if (routes['/cart']({ items: [{ price: 2, qty: 3 }] }).total !== '$6.00') fail('the /cart route does not answer as before');
""" + keep(shop, listOf("src/format.js")), after = listOf("node --test --test-reporter=dot")))

    // ---- retry-fetch (1.5.105, replaces remove-feature): a flaky service, retried a few times but not for a 404 ----
    val weather = mapOf(
        "package.json" to PKG,
        "src/weather.js" to """
const BASE = 'https://weather.example/api';

// The current weather for a city. fetchFn is fetch (passed in so it can be swapped in tests).
async function getWeather(city, fetchFn = fetch) {
  const res = await fetchFn(BASE + '/now?city=' + encodeURIComponent(city));
  if (!res.ok) throw new Error('weather service answered ' + res.status);
  return res.json();
}

module.exports = { getWeather };
""".trimStart(),
        "src/card.js" to """
const { getWeather } = require('./weather.js');

// The text on the weather card.
async function cardText(city, fetchFn) {
  try { const w = await getWeather(city, fetchFn); return w.city + ': ' + w.temp + ' C'; }
  catch (e) { return 'Weather unavailable'; }
}

module.exports = { cardText };
""".trimStart(),
        "test/weather.test.js" to "const test = require('node:test');\nconst assert = require('assert');\nconst { getWeather } = require('../src/weather.js');\n\ntest('the weather comes back', async () => {\n  const w = await getWeather('Oslo', async () => ({ ok: true, status: 200, json: async () => ({ temp: 5 }) }));\n  assert.strictEqual(w.temp, 5);\n});\n",
        "README.md" to "# Weather card\n\n`getWeather(city, fetchFn)` asks the weather service. Run the tests with `node --test`.\n",
    )

    val ALL: List<Task> = listOf(
        Task("unique-address", "Two blog posts with the same title end up at the same address, and the second one replaces the first. Every post must keep its own address: the second gets -2 on the end, the third -3, and so on.", posts, rounds = 24, check = check("""
const { addPost } = R('src/posts.js');
const { slugify } = R('src/slug.js');
if (slugify('Hello, World!') !== 'hello-world') fail('slugify changed: ' + slugify('Hello, World!'));
const s = {};
const a = addPost(s, 'Hello World', 'one'), b = addPost(s, 'Hello World', 'two'), c = addPost(s, 'hello world!', 'three');
if (a !== 'hello-world' || b !== 'hello-world-2' || c !== 'hello-world-3') fail('expected hello-world, hello-world-2, hello-world-3, got ' + [a, b, c].join(', '));
if (s[a].body !== 'one' || s[b].body !== 'two' || s[c].body !== 'three') fail('a post was overwritten');
const s2 = {};
const x = addPost(s2, 'Notes 2', 'first'), y = addPost(s2, 'Notes', 'second'), z = addPost(s2, 'Notes', 'third');
if (new Set([x, y, z]).size !== 3) fail('two posts share an address: ' + [x, y, z].join(', '));
if (s2[x].body !== 'first' || s2[y].body !== 'second' || s2[z].body !== 'third') fail('a post whose title ends in a number was overwritten');
if (Object.keys(s2).length !== 3) fail('expected 3 posts, got ' + Object.keys(s2).length);
const s3 = {};
if (addPost(s3, 'Other', 'x') !== 'other') fail('a new title should keep its plain address');
""", after = listOf("node --test --test-reporter=dot"))),

        Task("paging", "Some products never show up in the shop's product list, mostly near the end of the list. Fix it.", paging, rounds = 24, check = check("""
const { page, pageCount } = R('src/pages.js');
const { allPages } = R('src/shop.js');
for (let len = 0; len <= 35; len++) for (let size = 1; size <= 12; size++) {
  const items = Array.from({ length: len }, (_, i) => i);
  const pages = allPages(items, size);
  const flat = [].concat(...pages);
  if (JSON.stringify(flat) !== JSON.stringify(items)) fail(len + ' items, ' + size + ' a page: the pages hold ' + JSON.stringify(flat));
  if (pages.some(p => p.length === 0 || p.length > size)) fail(len + ' items, ' + size + ' a page: a page is empty or too long');
  if (pageCount(items, size) !== Math.ceil(len / size)) fail(len + ' items, ' + size + ' a page: pageCount is ' + pageCount(items, size));
}
if (page([1, 2, 3], 5, 2).length !== 0) fail('a page past the end should be empty');
""")),

        Task("cli-flag", "Add a --done option to `node bin/todo.js list` that shows only the finished to-dos. Plain `list` must print exactly what it prints now.", cli, rounds = 24, check = check("""
const os = require('os');
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'todo'));
const file = path.join(tmp, 'todos.json');
fs.writeFileSync(file, JSON.stringify([{ text: 'buy milk', done: false }, { text: 'walk the dog', done: true }, { text: 'call mum', done: false }]));
const env = Object.assign({}, process.env, { TODO_FILE: file });
const plain = run('node', ['bin/todo.js', 'list'], { env });
if (plain.status !== 0) fail('list failed: ' + plain.stderr);
if (plain.stdout !== '1. [ ] buy milk\n2. [x] walk the dog\n3. [ ] call mum\n') fail('plain list changed: ' + JSON.stringify(plain.stdout));
const done = run('node', ['bin/todo.js', 'list', '--done'], { env });
if (done.status !== 0) fail('list --done failed: ' + done.stderr);
const lines = done.stdout.split('\n').filter(l => l.trim());
if (lines.length !== 1 || !/^\d+\. \[x\] walk the dog$/.test(lines[0])) fail('list --done should show only "walk the dog": ' + JSON.stringify(done.stdout));
""")),

        Task("order-date", "Orders placed late in the evening show the wrong day in the order history on some phones. The order day should be the UTC day the order was placed.", orders, rounds = 20, check = check("""
const cases = [['2026-03-01T23:30:00Z', '2026-03-01'], ['2026-03-01T00:15:00Z', '2026-03-01'], ['2026-03-01T20:00:00-05:00', '2026-03-02'], ['2026-12-31T23:59:59.900Z', '2026-12-31']];
for (const tz of ['America/New_York', 'Asia/Tokyo', 'UTC']) {
  const js = 'const { orderDay } = require(' + JSON.stringify(path.resolve('src/orders.js')) + '); console.log(JSON.stringify(' + JSON.stringify(cases.map(c => c[0])) + '.map(orderDay)))';
  const r = run('node', ['-e', js], { env: Object.assign({}, process.env, { TZ: tz }) });
  if (r.status !== 0) fail('orderDay failed in ' + tz + ': ' + r.stderr);
  const got = JSON.parse(r.stdout);
  cases.forEach((c, i) => { if (got[i] !== c[1]) fail('in ' + tz + ', ' + c[0] + ' gave ' + got[i] + ', not ' + c[1]); });
}
""")),

        Task("retry-fetch", "The weather card sometimes says \"Weather unavailable\" although the service works when you try again a moment later. Make getWeather try up to 3 times in all before it gives up. A city the service doesn't know (it answers 404) should still fail at once.", weather, rounds = 20, check = check("""
const { getWeather } = R('src/weather.js');
function service(answers) {
  const calls = [];
  const fetchFn = async url => { calls.push(url); const a = answers[Math.min(calls.length - 1, answers.length - 1)];
    if (a === 'throw') throw new Error('network down');
    return { ok: a === 200, status: a, json: async () => ({ temp: 21, city: 'Oslo' }) }; };
  return { fetchFn, calls };
}
(async () => {
  let s = service([503, 'throw', 200]);
  const w = await getWeather('Oslo', s.fetchFn);
  if (!w || w.temp !== 21) fail('two failures then success should give the weather, got ' + JSON.stringify(w));
  if (s.calls.length !== 3) fail('expected 3 tries, made ' + s.calls.length);
  if (!s.calls.every(u => u === s.calls[0])) fail('the retries asked for a different address');
  s = service([500, 502, 503, 200]);
  let err = null; try { await getWeather('Oslo', s.fetchFn); } catch (e) { err = e; }
  if (!err) fail('three failures should give up with an error');
  if (s.calls.length !== 3) fail('after three failures it should stop, made ' + s.calls.length + ' tries');
  s = service([404]);
  err = null; try { await getWeather('Atlantis', s.fetchFn); } catch (e) { err = e; }
  if (!err) fail('an unknown city should fail');
  if (s.calls.length !== 1) fail('an unknown city should not be retried, made ' + s.calls.length + ' tries');
  s = service([200]);
  await getWeather('Oslo', s.fetchFn);
  if (s.calls.length !== 1) fail('a working service should be asked once, made ' + s.calls.length);
})().catch(e => fail('getWeather threw: ' + e.message));
""" + keep(weather, listOf("src/card.js")), after = listOf("node --test --test-reporter=dot"))),

        Task("csv-export", "Add toCsv(rows) to src/export.js and export it. It turns an array of objects into CSV that Excel opens correctly; the columns come from the first row's keys.", mapOf("package.json" to PKG, "README.md" to "# Export\n"), rounds = 20, check = check("""
const mod = R('src/export.js');
const toCsv = typeof mod === 'function' ? mod : mod.toCsv;
if (typeof toCsv !== 'function') fail('src/export.js does not export toCsv');
function parse(s) {
  const rows = [[]]; let f = '', q = false;
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (q) { if (c === '"') { if (s[i + 1] === '"') { f += '"'; i++; } else q = false; } else f += c; }
    else if (c === '"') q = true;
    else if (c === ',') { rows[rows.length - 1].push(f); f = ''; }
    else if (c === '\r' && s[i + 1] === '\n') {}
    else if (c === '\n') { rows[rows.length - 1].push(f); f = ''; rows.push([]); }
    else f += c;
  }
  rows[rows.length - 1].push(f);
  if (rows.length && rows[rows.length - 1].length === 1 && rows[rows.length - 1][0] === '') rows.pop();
  return rows;
}
const out = toCsv([{ id: 1, note: 'plain' }, { id: 2, note: 'a, b' }, { id: 3, note: 'he said "hi"' }, { id: 4, note: 'two\nlines' }, { id: 5 }, { id: 6, note: null }]);
if (typeof out !== 'string') fail('toCsv should return a string');
assert.deepStrictEqual(parse(out.replace(/^\uFEFF/, '')), [['id', 'note'], ['1', 'plain'], ['2', 'a, b'], ['3', 'he said "hi"'], ['4', 'two\nlines'], ['5', ''], ['6', '']], 'the CSV does not read back as the rows');
if (toCsv([]).trim() !== '') fail('no rows should give an empty string');
""")),
    )
}
