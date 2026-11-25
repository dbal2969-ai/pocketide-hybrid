"""The hard hybrid benchmark: ten tasks that need more than one step of reasoning, more than one file, or more than the phone has.

Writes bench/hybrid/hard/tasks/<id>/ (the starting projects), bench/hybrid/hard/zips/<id>.zip (to import by hand) and
bench/hybrid/hard/solutions/<id>/ (reference fixes, only the files they change). check_hard.py proves each task fails as given
and passes with its reference fix.

Usage: python3 bench/hybrid/make_hard.py bench/hybrid/hard
"""
import os, shutil, sys, zipfile

OUT = sys.argv[1]
T, S = {}, {}

# ---------------------------------------------------------------------------------------------------------------- h01
# Two bugs in two files behind one symptom: the coupon is subtracted as money instead of a percentage, and the bulk price
# starts one item late. The test names the symptom, not the files.
T["h01-two-bugs-two-files"] = {
 "pricing.js": r'''// Unit prices. Ten or more of one item get the bulk price.
const BULK_AT = 10;
function unitPrice(item, qty) {
  return qty > BULK_AT ? item.bulkPrice : item.price;
}
module.exports = { unitPrice, BULK_AT };
''',
 "discount.js": r'''// Coupons: { kind: 'percent', value: 15 } takes 15% off; { kind: 'fixed', value: 5 } takes 5 off. Never below zero.
function applyCoupon(total, coupon) {
  if (!coupon) return total;
  let out = total;
  if (coupon.kind === 'percent') out = total - coupon.value;
  if (coupon.kind === 'fixed') out = total - coupon.value;
  return Math.max(0, Math.round(out * 100) / 100);
}
module.exports = { applyCoupon };
''',
 "cart.js": r'''const { unitPrice } = require('./pricing.js');
const { applyCoupon } = require('./discount.js');
function cartTotal(lines, coupon) {
  const sub = lines.reduce((s, l) => s + unitPrice(l.item, l.qty) * l.qty, 0);
  return applyCoupon(sub, coupon);
}
module.exports = { cartTotal };
''',
 "test.js": r'''const { cartTotal } = require('./cart.js');
const pen = { price: 2, bulkPrice: 1.5 };
const book = { price: 20, bulkPrice: 18 };
const cases = [
  ['no coupon', cartTotal([{ item: pen, qty: 3 }]), 6],
  ['bulk at exactly ten', cartTotal([{ item: pen, qty: 10 }]), 15],
  ['15% off 40', cartTotal([{ item: book, qty: 2 }], { kind: 'percent', value: 15 }), 34],
  ['5 off 40', cartTotal([{ item: book, qty: 2 }], { kind: 'fixed', value: 5 }), 35],
  ['never below zero', cartTotal([{ item: pen, qty: 1 }], { kind: 'fixed', value: 5 }), 0],
  ['bulk and percent', cartTotal([{ item: pen, qty: 12 }], { kind: 'percent', value: 10 }), 16.2],
];
let bad = 0;
for (const [name, got, want] of cases) if (got !== want) { console.error(name + ': got ' + got + ', want ' + want); bad++; }
if (bad) process.exit(1);
console.log('ok');
''',
}
S["h01-two-bugs-two-files"] = {
 "pricing.js": T["h01-two-bugs-two-files"]["pricing.js"].replace("qty > BULK_AT", "qty >= BULK_AT"),
 "discount.js": T["h01-two-bugs-two-files"]["discount.js"].replace("out = total - coupon.value;\n  if (coupon.kind === 'fixed')", "out = total * (1 - coupon.value / 100);\n  if (coupon.kind === 'fixed')"),
}

# ---------------------------------------------------------------------------------------------------------------- h02
# Write two functions from a spec with edge cases; the stubs throw.
T["h02-spec-to-code"] = {
 "SPEC.md": r'''# Durations

`parseDuration(text)` returns a number of seconds, or `null` when the text is not a duration.

- Units: `h` (hours), `m` (minutes), `s` (seconds), in that order, each at most once: `1h30m`, `45s`, `2h`, `90m`, `1h 5m 3s`.
- Spaces between parts are allowed. Letters may be upper or lower case (`1H30M`).
- Not durations: an empty string, `null`, a number with no unit (`5`), an unknown unit (`5x`), a unit with no number (`h1`),
  units out of order (`30m1h`) or repeated (`1h2h`).

`formatDuration(seconds)` is the reverse: whole seconds to the shortest text, parts with a value only, separated by one space:
`5400` → `"1h 30m"`, `45` → `"45s"`, `3723` → `"1h 2m 3s"`, `0` → `"0s"`. A negative or non-whole number gives `null`.
''',
 "duration.js": r'''// See SPEC.md.
function parseDuration(text) {
  throw new Error('not implemented');
}

function formatDuration(seconds) {
  throw new Error('not implemented');
}

module.exports = { parseDuration, formatDuration };
''',
 "test.js": r'''const { parseDuration: p, formatDuration: f } = require('./duration.js');
const cases = [
  ['1h30m', p('1h30m'), 5400], ['45s', p('45s'), 45], ['2h', p('2h'), 7200], ['90m', p('90m'), 5400],
  ['1h 5m 3s', p('1h 5m 3s'), 3903], ['1H30M', p('1H30M'), 5400],
  ['empty', p(''), null], ['null', p(null), null], ['5', p('5'), null], ['5x', p('5x'), null],
  ['h1', p('h1'), null], ['30m1h', p('30m1h'), null], ['1h2h', p('1h2h'), null],
  ['f 5400', f(5400), '1h 30m'], ['f 45', f(45), '45s'], ['f 3723', f(3723), '1h 2m 3s'], ['f 0', f(0), '0s'],
  ['f -1', f(-1), null], ['f 1.5', f(1.5), null], ['round trip', p(f(3903)), 3903],
];
let bad = 0;
for (const [name, got, want] of cases) if (got !== want) { console.error(name + ': got ' + JSON.stringify(got) + ', want ' + JSON.stringify(want)); bad++; }
if (bad) process.exit(1);
console.log('ok');
''',
}
S["h02-spec-to-code"] = {
 "duration.js": r'''// See SPEC.md.
function parseDuration(text) {
  if (typeof text !== 'string') return null;
  const m = /^\s*(?:(\d+)h)?\s*(?:(\d+)m)?\s*(?:(\d+)s)?\s*$/i.exec(text);
  if (!m || (m[1] === undefined && m[2] === undefined && m[3] === undefined)) return null;
  return (+(m[1] || 0)) * 3600 + (+(m[2] || 0)) * 60 + (+(m[3] || 0));
}

function formatDuration(seconds) {
  if (!Number.isInteger(seconds) || seconds < 0) return null;
  if (seconds === 0) return '0s';
  const parts = [];
  const h = Math.floor(seconds / 3600), m = Math.floor(seconds % 3600 / 60), s = seconds % 60;
  if (h) parts.push(h + 'h');
  if (m) parts.push(m + 'm');
  if (s) parts.push(s + 's');
  return parts.join(' ');
}

module.exports = { parseDuration, formatDuration };
''',
}

# ---------------------------------------------------------------------------------------------------------------- h03
# An async bug: forEach(async …) returns before the loads finish, results come back in finishing order, and one failure
# throws. The fake fetch is deterministic (later ids answer sooner).
T["h03-async-order"] = {
 "api.js": r'''// A stand-in for a network call: id 'bad' fails; later ids answer sooner.
function fetchItem(id, index) {
  return new Promise((resolve, reject) => setTimeout(() => {
    if (id === 'bad') reject(new Error('not found: ' + id));
    else resolve({ id, name: 'Item ' + id });
  }, 40 - index * 10));
}
module.exports = { fetchItem };
''',
 "loader.js": r'''const { fetchItem } = require('./api.js');

// Loads every id. Should return one entry per id, in the same order as ids.
async function loadAll(ids) {
  const results = [];
  ids.forEach(async (id, i) => {
    const item = await fetchItem(id, i);
    results.push(item);
  });
  return results;
}

module.exports = { loadAll };
''',
 "test.js": r'''const { loadAll } = require('./loader.js');
(async () => {
  let bad = 0;
  const r = await loadAll(['a', 'b', 'c']);
  if (r.length !== 3) { console.error('got ' + r.length + ' results, want 3'); bad++; }
  else if (r.map(x => x.id).join() !== 'a,b,c') { console.error('order ' + r.map(x => x.id).join() + ', want a,b,c'); bad++; }
  let r2;
  try { r2 = await loadAll(['a', 'bad', 'c']); } catch (e) { console.error('a failed id threw: ' + e.message); bad++; }
  if (r2) {
    if (r2.length !== 3 || r2[1].id !== 'bad' || r2[1].error !== 'not found: bad' || r2[2].name !== 'Item c') {
      console.error('failed id should give { id, error } in its place: ' + JSON.stringify(r2)); bad++;
    }
  }
  if (bad) process.exit(1);
  console.log('ok');
})();
''',
}
S["h03-async-order"] = {
 "loader.js": r'''const { fetchItem } = require('./api.js');

// Loads every id. Should return one entry per id, in the same order as ids.
async function loadAll(ids) {
  return Promise.all(ids.map((id, i) => fetchItem(id, i).catch(e => ({ id, error: e.message }))));
}

module.exports = { loadAll };
''',
}

# ---------------------------------------------------------------------------------------------------------------- h04
# A refactor that must not change behaviour: split a module, keep the old entry point working for existing callers.
T["h04-split-module"] = {
 "utils.js": r'''function slugify(s) {
  return s.toLowerCase().trim().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
}
function titleCase(s) {
  return s.split(' ').map(w => w ? w[0].toUpperCase() + w.slice(1).toLowerCase() : w).join(' ');
}
function clamp(n, lo, hi) {
  return Math.min(hi, Math.max(lo, n));
}
function sum(xs) {
  return xs.reduce((a, b) => a + b, 0);
}
function avg(xs) {
  return xs.length ? sum(xs) / xs.length : 0;
}
function unique(xs) {
  return [...new Set(xs)];
}
module.exports = { slugify, titleCase, clamp, sum, avg, unique };
''',
 "app.js": r'''const { slugify, avg, unique } = require('./utils.js');
function summary(posts) {
  return {
    slugs: posts.map(p => slugify(p.title)),
    meanLikes: avg(posts.map(p => p.likes)),
    tags: unique(posts.flatMap(p => p.tags)),
  };
}
module.exports = { summary };
''',
 "test.js": r'''const fs = require('fs');
let bad = 0;
function need(cond, msg) { if (!cond) { console.error(msg); bad++; } }
let strings = {}, numbers = {};
try { strings = require('./strings.js'); } catch (e) { need(false, 'strings.js is missing'); }
try { numbers = require('./numbers.js'); } catch (e) { need(false, 'numbers.js is missing'); }
const utils = require('./utils.js');
need(typeof strings.slugify === 'function' && typeof strings.titleCase === 'function', 'strings.js must export slugify and titleCase');
need(typeof numbers.clamp === 'function' && typeof numbers.sum === 'function' && typeof numbers.avg === 'function', 'numbers.js must export clamp, sum and avg');
for (const f of ['slugify', 'titleCase', 'clamp', 'sum', 'avg', 'unique']) need(typeof utils[f] === 'function', 'utils.js must still export ' + f);
const src = fs.readFileSync(__dirname + '/utils.js', 'utf8');
need(!/function\s+(slugify|titleCase|clamp|sum|avg)\b/.test(src), 'utils.js should re-export the moved functions, not define them again');
if (strings.slugify) need(strings.slugify('  Hello, World! ') === 'hello-world', 'slugify changed');
if (strings.titleCase) need(strings.titleCase('the DAY of days') === 'The Day Of Days', 'titleCase changed');
if (numbers.clamp) need(numbers.clamp(12, 0, 10) === 10, 'clamp changed');
const { summary } = require('./app.js');
const s = summary([{ title: 'A post', likes: 2, tags: ['x'] }, { title: 'B!', likes: 4, tags: ['x', 'y'] }]);
need(JSON.stringify(s) === '{"slugs":["a-post","b"],"meanLikes":3,"tags":["x","y"]}', 'app.js summary changed: ' + JSON.stringify(s));
if (bad) process.exit(1);
console.log('ok');
''',
}
S["h04-split-module"] = {
 "strings.js": r'''function slugify(s) {
  return s.toLowerCase().trim().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
}
function titleCase(s) {
  return s.split(' ').map(w => w ? w[0].toUpperCase() + w.slice(1).toLowerCase() : w).join(' ');
}
module.exports = { slugify, titleCase };
''',
 "numbers.js": r'''function clamp(n, lo, hi) {
  return Math.min(hi, Math.max(lo, n));
}
function sum(xs) {
  return xs.reduce((a, b) => a + b, 0);
}
function avg(xs) {
  return xs.length ? sum(xs) / xs.length : 0;
}
module.exports = { clamp, sum, avg };
''',
 "utils.js": r'''const { slugify, titleCase } = require('./strings.js');
const { clamp, sum, avg } = require('./numbers.js');
function unique(xs) {
  return [...new Set(xs)];
}
module.exports = { slugify, titleCase, clamp, sum, avg, unique };
''',
}

# ---------------------------------------------------------------------------------------------------------------- h05
# Debug from the failing output alone: the bug is in a nested helper the request does not name, used by two callers.
T["h05-find-the-cause"] = {
 "src/lib/format/date.js": r'''// Dates as YYYY-MM-DD, used by reports and invoices.
function pad(n) {
  return String(n);
}
function isoDay(d) {
  return d.getUTCFullYear() + '-' + pad(d.getUTCMonth()) + '-' + pad(d.getUTCDate());
}
module.exports = { isoDay, pad };
''',
 "src/lib/format/money.js": r'''function money(n) {
  return n.toFixed(2);
}
module.exports = { money };
''',
 "src/report.js": r'''const { isoDay } = require('./lib/format/date.js');
const { money } = require('./lib/format/money.js');
function line(sale) {
  return isoDay(sale.at) + ' ' + sale.what + ' ' + money(sale.amount);
}
module.exports = { line };
''',
 "src/invoice.js": r'''const { isoDay } = require('./lib/format/date.js');
function dueLine(issued, days) {
  const due = new Date(issued.getTime() + days * 86400000);
  return 'Due ' + isoDay(due);
}
module.exports = { dueLine };
''',
 "test.js": r'''const { line } = require('./src/report.js');
const { dueLine } = require('./src/invoice.js');
let bad = 0;
const a = line({ at: new Date(Date.UTC(2026, 0, 5)), what: 'tea', amount: 3.5 });
if (a !== '2026-01-05 tea 3.50') { console.error('report line: ' + a); bad++; }
const b = dueLine(new Date(Date.UTC(2026, 10, 25)), 14);
if (b !== 'Due 2026-12-09') { console.error('invoice: ' + b); bad++; }
if (bad) process.exit(1);
console.log('ok');
''',
}
S["h05-find-the-cause"] = {
 "src/lib/format/date.js": r'''// Dates as YYYY-MM-DD, used by reports and invoices.
function pad(n) {
  return String(n).padStart(2, '0');
}
function isoDay(d) {
  return d.getUTCFullYear() + '-' + pad(d.getUTCMonth() + 1) + '-' + pad(d.getUTCDate());
}
module.exports = { isoDay, pad };
''',
}

# ---------------------------------------------------------------------------------------------------------------- h06
# A UI feature across HTML and JS, checked by the app's play test: filters that keep working as items change.
T["h06-ui-filters"] = {
 "index.html": r'''<!DOCTYPE html>
<html>
<head><meta charset="utf-8"><title>Todo</title><link rel="stylesheet" href="style.css"></head>
<body>
  <h1>Todo</h1>
  <input id="new" placeholder="What to do">
  <button id="add">Add</button>
  <ul id="list"></ul>
  <p id="count"></p>
  <script src="app.js"></script>
</body>
</html>
''',
 "style.css": "body { font-family: sans-serif; margin: 16px; }\nli.done { text-decoration: line-through; color: #888; }\n",
 "app.js": r'''const list = document.getElementById('list');
const input = document.getElementById('new');
const count = document.getElementById('count');
let items = [];

function render() {
  list.innerHTML = '';
  items.forEach((it, i) => {
    const li = document.createElement('li');
    li.textContent = it.text;
    if (it.done) li.className = 'done';
    li.onclick = () => { items[i].done = !items[i].done; render(); };
    list.appendChild(li);
  });
  count.textContent = items.filter(it => !it.done).length + ' left';
}

document.getElementById('add').onclick = () => {
  const t = input.value.trim();
  if (!t) return;
  items.push({ text: t, done: false });
  input.value = '';
  render();
};

render();
''',
 "tests/play.txt": r'''# filters show the matching items, and keep working when items change
run document.getElementById('new').value = 'milk'
tap "Add"
run document.getElementById('new').value = 'eggs'
tap "Add"
run document.getElementById('new').value = 'bread'
tap "Add"
tap "milk"
tap "Done"
expect document.querySelectorAll('#list li').length === 1
expect document.querySelector('#list li').textContent === 'milk'
tap "Active"
expect document.querySelectorAll('#list li').length === 2
tap "eggs"
expect document.querySelectorAll('#list li').length === 1
expect document.querySelector('#list li').textContent === 'bread'
tap "All"
expect document.querySelectorAll('#list li').length === 3
expect document.getElementById('count').textContent === '1 left'
''',
}
S["h06-ui-filters"] = {
 "index.html": T["h06-ui-filters"]["index.html"].replace('<ul id="list"></ul>', '<div id="filters"><button data-f="all">All</button> <button data-f="active">Active</button> <button data-f="done">Done</button></div>\n  <ul id="list"></ul>'),
 "app.js": T["h06-ui-filters"]["app.js"].replace("let items = [];", "let items = [];\nlet filter = 'all';\ndocument.querySelectorAll('#filters button').forEach(b => b.onclick = () => { filter = b.dataset.f; render(); });")
   .replace("    const li = document.createElement('li');", "    if (filter === 'active' && it.done) return;\n    if (filter === 'done' && !it.done) return;\n    const li = document.createElement('li');"),
}

# ---------------------------------------------------------------------------------------------------------------- h07
# A file far over the on-phone limit with two bugs in different places (one near the top, one deep in the file).
lines = ["// Inventory for a small shop. Each item: { sku, name, price, quantity, reorderAt }"]
lines.append("function totalValue(items) {\n  return items.reduce((s, it) => s + it.price * it.qty, 0);\n}")
for i in range(1, 181):
    lines.append(f"function count{i}(items) {{\n  // how many items have a sku ending in {i % 10}\n  return items.filter(it => String(it.sku).endsWith('{i % 10}')).length;\n}}")
lines.append("function needsReorder(items) {\n  // an item needs reordering when its quantity is at or below reorderAt\n  return items.filter(it => it.quantity < it.reorderAt).map(it => it.sku);\n}")
for i in range(1, 121):
    lines.append(f"function label{i}(it) {{\n  return it.name + ' #{i}: ' + it.price.toFixed(2);\n}}")
lines.append("module.exports = { totalValue, needsReorder };")
T["h07-long-file-two-bugs"] = {
 "inventory.js": "\n\n".join(lines) + "\n",
 "test.js": r'''const { totalValue, needsReorder } = require('./inventory.js');
const items = [
  { sku: 1, name: 'a', price: 2, quantity: 3, reorderAt: 3 },
  { sku: 2, name: 'b', price: 1, quantity: 4, reorderAt: 2 },
  { sku: 3, name: 'c', price: 5, quantity: 0, reorderAt: 1 },
];
let bad = 0;
const v = totalValue(items);
if (v !== 10) { console.error('totalValue gave ' + v + ', want 10'); bad++; }
const r = needsReorder(items).join();
if (r !== '1,3') { console.error('needsReorder gave [' + r + '], want [1,3]'); bad++; }
if (bad) process.exit(1);
console.log('ok');
''',
}
S["h07-long-file-two-bugs"] = {
 "inventory.js": T["h07-long-file-two-bugs"]["inventory.js"].replace("it.price * it.qty", "it.price * it.quantity").replace("it.quantity < it.reorderAt", "it.quantity <= it.reorderAt"),
}

# ---------------------------------------------------------------------------------------------------------------- h08
# Private mode (Gemini Nano only): several rules with exact messages in a set order.
T["h08-private-password"] = {
 "password.js": r'''// Returns the problems with a password, in this order:
// "too short" (under 8 characters), "no digit", "no uppercase", "has spaces". An empty list means it is fine.
function checkPassword(pw) {
  return [];
}
module.exports = { checkPassword };
''',
 "test.js": r'''const { checkPassword: c } = require('./password.js');
const cases = [
  ['Secret123', []],
  ['short1A', ['too short']],
  ['longenough', ['no digit', 'no uppercase']],
  ['Has Space 9', ['has spaces']],
  ['abc', ['too short', 'no digit', 'no uppercase']],
  ['a b', ['too short', 'no digit', 'no uppercase', 'has spaces']],
];
let bad = 0;
for (const [pw, want] of cases) {
  const got = c(pw);
  if (JSON.stringify(got) !== JSON.stringify(want)) { console.error(JSON.stringify(pw) + ': got ' + JSON.stringify(got) + ', want ' + JSON.stringify(want)); bad++; }
}
if (bad) process.exit(1);
console.log('ok');
''',
}
S["h08-private-password"] = {
 "password.js": r'''// Returns the problems with a password, in this order:
// "too short" (under 8 characters), "no digit", "no uppercase", "has spaces". An empty list means it is fine.
function checkPassword(pw) {
  const out = [];
  if (pw.length < 8) out.push('too short');
  if (!/[0-9]/.test(pw)) out.push('no digit');
  if (!/[A-Z]/.test(pw)) out.push('no uppercase');
  if (/\s/.test(pw)) out.push('has spaces');
  return out;
}
module.exports = { checkPassword };
''',
}

# ---------------------------------------------------------------------------------------------------------------- h09
# The web, then code that uses what was found: a machine-checkable research result.
T["h09-research-to-json"] = {
 "README.md": "# Licences\n\nlicences.json maps npm package names to SPDX licence identifiers.\n",
 "test.js": r'''const fs = require('fs');
let data;
try { data = JSON.parse(fs.readFileSync(__dirname + '/licences.json', 'utf8')); }
catch (e) { console.error('licences.json is missing or not JSON: ' + e.message); process.exit(1); }
const want = { express: 'MIT', lodash: 'MIT', react: 'MIT' };
let bad = 0;
for (const [k, v] of Object.entries(want)) if (data[k] !== v) { console.error(k + ': got ' + JSON.stringify(data[k]) + ', want ' + JSON.stringify(v)); bad++; }
if (bad) process.exit(1);
console.log('ok');
''',
}
S["h09-research-to-json"] = {"licences.json": '{ "express": "MIT", "lodash": "MIT", "react": "MIT" }\n'}

# ---------------------------------------------------------------------------------------------------------------- h10
# A fix in a shared helper with two callers that use it differently: fixing it for one must not break the other.
T["h10-shared-helper"] = {
 "format.js": r'''// Prices for display. symbol defaults to '$'.
function formatPrice(n, symbol) {
  return (symbol || '$') + n;
}
module.exports = { formatPrice };
''',
 "invoice.js": r'''const { formatPrice } = require('./format.js');
function invoiceTotal(lines) {
  const total = lines.reduce((s, l) => s + l.price * l.qty, 0);
  return 'Total ' + formatPrice(total);
}
module.exports = { invoiceTotal };
''',
 "receipt.js": r'''const { formatPrice } = require('./format.js');
// Receipts in euros. A free item shows as "free", not as a price.
function receiptLine(name, n) {
  return name + ': ' + (n === 0 ? 'free' : formatPrice(n, '€'));
}
module.exports = { receiptLine };
''',
 "test.js": r'''const { invoiceTotal } = require('./invoice.js');
const { receiptLine } = require('./receipt.js');
const cases = [
  ['invoice', invoiceTotal([{ price: 6.25, qty: 2 }]), 'Total $12.50'],
  ['invoice whole', invoiceTotal([{ price: 3, qty: 1 }]), 'Total $3.00'],
  ['receipt', receiptLine('tea', 3), 'tea: €3.00'],
  ['receipt cents', receiptLine('cake', 2.5), 'cake: €2.50'],
  ['receipt free', receiptLine('water', 0), 'water: free'],
];
let bad = 0;
for (const [name, got, want] of cases) if (got !== want) { console.error(name + ': got ' + JSON.stringify(got) + ', want ' + JSON.stringify(want)); bad++; }
if (bad) process.exit(1);
console.log('ok');
''',
}
S["h10-shared-helper"] = {"format.js": T["h10-shared-helper"]["format.js"].replace("(symbol || '$') + n;", "(symbol || '$') + n.toFixed(2);")}

# ---------------------------------------------------------------------------------------------------------------- write
if os.path.exists(OUT): shutil.rmtree(OUT)
for kind, src in (("tasks", T), ("solutions", S)):
    for name, files in src.items():
        for p, c in files.items():
            fp = os.path.join(OUT, kind, name, p); os.makedirs(os.path.dirname(fp), exist_ok=True)
            open(fp, "w").write(c)
os.makedirs(os.path.join(OUT, "zips"))
for name, files in T.items():
    with zipfile.ZipFile(os.path.join(OUT, "zips", name + ".zip"), "w", zipfile.ZIP_DEFLATED) as z:
        for p, c in sorted(files.items()):
            zi = zipfile.ZipInfo(p, date_time=(2026, 11, 24, 0, 0, 0)); zi.external_attr = 0o644 << 16
            z.writestr(zi, c)
print("written", len(T), "hard tasks; long file chars:", len(T["h07-long-file-two-bugs"]["inventory.js"]))
