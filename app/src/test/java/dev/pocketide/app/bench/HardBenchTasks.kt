package dev.pocketide.app.bench

import dev.pocketide.app.bench.BenchTasks.Task
import java.security.MessageDigest

/**
 * The hard suite (POCKETIDE_BENCH_SUITE=hard). The standard tasks show the loop works; these show whether it works as well as
 * Copilot or Claude Code on the jobs people give them every day: renames across a project, bugs found from a symptom in a
 * codebase too big to read whole, a 1,800-line file edited in three places without losing the rest, specs with edge cases,
 * security and performance fixes, staying inside a folder, not trusting a wrong guess, and writing tests that catch bugs.
 *
 * Every check is hidden from the model: it is written to .bench/ only after the run, so passing means doing the job, not
 * matching the visible tests. Files that must not change are compared by SHA-256. HardBenchTasksTest proves each check fails
 * on the starting project, passes on a reference solution, and rejects the known shortcuts.
 */
object HardBenchTasks {
    internal fun sha(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private const val PRELUDE = """
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
const R = p => require(path.resolve(p));
function fail(msg) { console.error('FAIL: ' + msg); process.exit(1); }
process.on('uncaughtException', e => fail(e && e.message ? e.message : String(e)));
process.on('unhandledRejection', e => fail(e && e.message ? e.message : String(e)));
function sha(p) { return require('crypto').createHash('sha256').update(fs.readFileSync(p)).digest('hex'); }
function T(p) { if (!fs.existsSync(p)) fail(p + ' is missing'); return fs.readFileSync(p, 'utf8'); }
function run(cmd, args, opts) { return spawnSync(cmd, args, Object.assign({ encoding: 'utf8', timeout: 30000 }, opts || {})); }
function testsPass(cwd) {
  const r = run('node', ['--test', '--test-reporter=dot'], { cwd: cwd || '.' });
  if (r.status !== 0) fail('the tests fail: ' + (r.stdout + r.stderr).slice(-600));
}
function walk(d, out) {
  out = out || [];
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = d === '.' ? e.name : d + '/' + e.name;
    if (p === '.bench' || e.name === 'node_modules' || e.name === '__pycache__') continue;
    if (e.isDirectory()) walk(p, out); else out.push(p);
  }
  return out;
}
"""

    /** JS: fails when one of [paths] is gone or differs from the start. */
    internal fun keep(files: Map<String, String>, paths: Collection<String>): String =
        "for (const [p, h] of [" + paths.joinToString(", ") { "['$it', '${sha(files.getValue(it))}']" } + "]) {\n" +
            "  if (!fs.existsSync(p)) fail(p + ' was deleted; it must stay as it was');\n" +
            "  if (sha(p) !== h) fail(p + ' was changed; it must stay as it was');\n}\n"

    /** JS: fails when a file outside [prefixes] was changed, deleted or added. */
    internal fun onlyUnder(files: Map<String, String>, vararg prefixes: String): String {
        val outside = files.keys.filter { p -> prefixes.none { p.startsWith(it) } }
        return keep(files, outside) +
            "const allowed = [" + prefixes.joinToString(", ") { "'$it'" } + "];\n" +
            "const known = new Set([" + outside.joinToString(", ") { "'$it'" } + "]);\n" +
            "for (const p of walk('.')) if (!allowed.some(a => p.startsWith(a)) && !known.has(p)) fail(p + ' is new, outside ' + allowed.join(' and '));\n"
    }

    /**
     * A shell check: [before] commands, then [js] (after the prelude) from .bench/hidden.js, then [after] commands.
     * The script is written only when the check runs, so the model never sees it.
     */
    internal fun check(js: String, before: List<String> = emptyList(), after: List<String> = emptyList()): String =
        (listOf("set -eo pipefail") + before + listOf("mkdir -p .bench", "cat > .bench/hidden.js <<'BENCH_EOF'", PRELUDE.trim() + "\n" + js.trim(), "BENCH_EOF",
            "node .bench/hidden.js") + after).joinToString("\n")

    private const val NODE_TESTS = "node --test --test-reporter=dot"
    private const val PKG = "{\n  \"name\": \"app\",\n  \"private\": true,\n  \"scripts\": { \"test\": \"node --test\" }\n}\n"

    /** A JS string literal for [s], ASCII only (the check travels as a command-line argument, whose encoding varies). */
    fun jsStr(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '\\' -> append("\\\\")
            c == '"' -> append("\\\"")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' || c > '~' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }

    // ---- rename-everywhere: a rename across code, tests, docs and a string lookup, next to look-alike names that must stay ----

    val rename: Map<String, String> = mapOf(
        "package.json" to PKG,
        "src/users.js" to """
const USERS = [
  { id: 1, name: 'Ada', role: 'admin' },
  { id: 2, name: 'Linus', role: 'member' },
  { id: 3, name: 'Grace', role: 'member' },
];

/** One user by id, or null. */
function getUser(id) {
  return USERS.find(u => u.id === id) || null;
}

/** Every user with the given role (all users when no role is given). */
function getUsers(role) {
  return USERS.filter(u => !role || u.role === role);
}

/** The display name for a user id, used in emails. */
function getUserName(id) {
  const u = getUser(id);
  return u ? u.name : 'unknown';
}

module.exports = { getUser, getUsers, getUserName };
""".trimStart(),
        "src/api/profile.js" to """
const { getUser } = require('../users.js');

function profile(id) {
  const user = getUser(id);
  if (!user) return { status: 404 };
  return { status: 200, body: { name: user.name } };
}

module.exports = { profile };
""".trimStart(),
        "src/api/admin.js" to """
const users = require('../users.js');

function isAdmin(id) {
  const u = users.getUser(id);
  return !!u && u.role === 'admin';
}

function listMembers() {
  return users.getUsers('member').map(u => u.name);
}

module.exports = { isAdmin, listMembers };
""".trimStart(),
        "src/api/routes.js" to """
const users = require('../users.js');

// route -> the users.js function that answers it
const LOOKUPS = { '/user': 'getUser', '/users': 'getUsers' };

function handle(route, arg) {
  const fn = users[LOOKUPS[route]];
  return fn ? fn(arg) : undefined;
}

module.exports = { handle };
""".trimStart(),
        "src/jobs/report.js" to """
const { getUser: lookup, getUsers } = require('../users.js');

/** "Ada, Grace of 3": the names for some ids, then how many users there are. */
function report(ids) {
  const names = ids.map(id => {
    const u = lookup(id);
    return u ? u.name : '?';
  });
  return names.join(', ') + ' of ' + getUsers().length;
}

module.exports = { report };
""".trimStart(),
        "src/mail.js" to "const { getUserName } = require('./users.js');\n\nfunction greeting(id) {\n  return 'Hello ' + getUserName(id);\n}\n\nmodule.exports = { greeting };\n",
        "src/cli.js" to "#!/usr/bin/env node\nconst { getUser } = require('./users.js');\n\nconst u = getUser(Number(process.argv[2]));\nconsole.log(u ? u.name : 'no such user');\n",
        "test/users.test.js" to """
const test = require('node:test');
const assert = require('node:assert');
const { getUser, getUsers } = require('../src/users.js');

test('getUser finds a user by id', () => {
  assert.strictEqual(getUser(2).name, 'Linus');
});

test('getUser returns null for an unknown id', () => {
  assert.strictEqual(getUser(99), null);
});

test('getUsers filters by role', () => {
  assert.strictEqual(getUsers('member').length, 2);
});
""".trimStart(),
        "README.md" to "# crew\n\nA tiny user directory.\n\n## API\n\n- `getUser(id)`: one user, or null.\n- `getUsers(role)`: every user with a role.\n- `getUserName(id)`: the display name.\n\nRun `node src/cli.js 1` to print a user's name.\n",
    )

    // ---- fix-code-not-tests: failing tests, two bugs in the code; the tests are right and must not be touched ----

    val paging: Map<String, String> = mapOf(
        "package.json" to PKG,
        "src/paginate.js" to """
/**
 * One page of a list. Pages are numbered from 1.
 * Returns { items, page, totalPages, hasNext }. An empty list has 0 pages.
 */
function paginate(list, page, size) {
  const totalPages = Math.floor(list.length / size) + 1;
  const start = page * size;
  const items = list.slice(start, start + size);
  return { items, page, totalPages, hasNext: page < totalPages };
}

module.exports = { paginate };
""".trimStart(),
        "src/api.js" to """
const { paginate } = require('./paginate.js');

const POSTS = Array.from({ length: 23 }, (_, i) => ({ id: i + 1 }));

/** GET /posts?page=N: ten posts a page. */
function listPosts(query) {
  const page = Number(query.page || 1);
  const r = paginate(POSTS, page, 10);
  return { ids: r.items.map(p => p.id), page: r.page, totalPages: r.totalPages, next: r.hasNext ? page + 1 : null };
}

module.exports = { listPosts };
""".trimStart(),
        "test/paginate.test.js" to """
const test = require('node:test');
const assert = require('node:assert');
const { paginate } = require('../src/paginate.js');

const ten = Array.from({ length: 10 }, (_, i) => i + 1);

test('first page', () => {
  assert.deepStrictEqual(paginate(ten, 1, 3), { items: [1, 2, 3], page: 1, totalPages: 4, hasNext: true });
});

test('last page is partly full', () => {
  assert.deepStrictEqual(paginate(ten, 4, 3), { items: [10], page: 4, totalPages: 4, hasNext: false });
});

test('a length that divides exactly has no extra page', () => {
  assert.strictEqual(paginate(ten, 2, 5).totalPages, 2);
  assert.strictEqual(paginate(ten, 2, 5).hasNext, false);
});

test('an empty list has no pages', () => {
  assert.deepStrictEqual(paginate([], 1, 5), { items: [], page: 1, totalPages: 0, hasNext: false });
});
""".trimStart(),
        "test/api.test.js" to """
const test = require('node:test');
const assert = require('node:assert');
const { listPosts } = require('../src/api.js');

test('page 3 has the last three posts', () => {
  assert.deepStrictEqual(listPosts({ page: '3' }), { ids: [21, 22, 23], page: 3, totalPages: 3, next: null });
});

test('page 1 by default', () => {
  assert.deepStrictEqual(listPosts({}).ids.slice(0, 2), [1, 2]);
});
""".trimStart(),
    )

    // ---- symptom-in-big-project: ~45 files, a symptom, decoys that mention slugs; the fix belongs in one shared function ----

    private val FEATURES = listOf(
        "analytics", "billing", "bookmarks", "captcha", "comments", "drafts", "emails", "exports", "feeds", "flags", "follows",
        "galleries", "imports", "invites", "likes", "locales", "mentions", "menus", "newsletters", "notifications", "pages",
        "polls", "profiles", "ratings", "reports", "revisions", "schedules", "sessions", "settings", "shares", "sitemaps",
        "themes", "uploads", "webhooks",
    )

    val blog: Map<String, String> = buildMap {
        put("package.json", PKG)
        put("README.md", "# Inkwell\n\nA small blog engine. Posts live at /posts/<slug>. Features are in src/features, one folder each.\n")
        put("src/store.js", """
// In-memory store: collections of rows by name.
const data = {};
let last = 0;

function nextId() { return ++last; }
function save(kind, row) { (data[kind] = data[kind] || []).push(row); return row; }
function all(kind) { return (data[kind] || []).slice(); }

module.exports = { nextId, save, all };
""".trimStart())
        put("src/util/text.js", """
/** URL slug for a title: lower case, words joined by '-'. */
function slugify(title) {
  return String(title)
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
}

/** The first n characters of s, cut at a word, with an ellipsis. */
function excerpt(s, n) {
  if (s.length <= n) return s;
  return s.slice(0, n - 1).replace(/\s+\S*$/, '') + '…';
}

module.exports = { slugify, excerpt };
""".trimStart())
        put("src/util/dates.js", "function isoDay(ms) { return new Date(ms).toISOString().slice(0, 10); }\nmodule.exports = { isoDay };\n")
        put("src/legacy/slug_old.js", """
// Slug code from v1, before util/text.js. Nothing requires this file; the v1 -> v2 migration script used it once.
function makeSlug(t) {
  return t.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().split(' ').join('_');
}

module.exports = { makeSlug };
""".trimStart())
        put("src/models/post.js", """
const { slugify } = require('../util/text.js');
const store = require('../store.js');

function createPost(title, body) {
  const post = { id: store.nextId(), title, body, slug: slugify(title), created: Date.now() };
  return store.save('posts', post);
}

function findBySlug(slug) {
  return store.all('posts').find(p => p.slug === slug) || null;
}

module.exports = { createPost, findBySlug };
""".trimStart())
        put("src/models/tag.js", """
const { slugify } = require('../util/text.js');

/** The URL part for a tag: /tags/<tagSlug>. */
function tagSlug(name) {
  return slugify(name);
}

module.exports = { tagSlug };
""".trimStart())
        put("src/routes/posts.js", """
const { createPost, findBySlug } = require('../models/post.js');

const routes = {
  'POST /posts': body => createPost(body.title, body.body),
  'GET /posts/:slug': params => findBySlug(params.slug),
};

module.exports = routes;
""".trimStart())
        put("src/features/redirects/index.js", """
// Old post URLs that moved. Keys and values are post slugs.
const MOVED = { 'hello-world-2019': 'hello-world', 'cafe': 'cafe-menu' };

function redirectFor(slug) {
  return MOVED[slug] || null;
}

module.exports = { redirectFor };
""".trimStart())
        put("src/features/search/index.js", """
const store = require('../../store.js');

// Search matches titles case-insensitively. Accents are kept on purpose: "résumé" and "resume" are different searches.
function normalizeQuery(q) {
  return String(q).trim().toLowerCase();
}

function searchPosts(q) {
  const n = normalizeQuery(q);
  return store.all('posts').filter(p => p.title.toLowerCase().includes(n));
}

module.exports = { normalizeQuery, searchPosts };
""".trimStart())
        for (f in FEATURES) {
            val name = f.replaceFirstChar { it.uppercase() }
            put("src/features/$f/index.js", """
// $name: list and count the saved $f.
const store = require('../../store.js');

function list$name(limit = 20) {
  return store.all('$f').slice(0, limit);
}

function count$name() {
  return store.all('$f').length;
}

module.exports = { list$name, count$name };
""".trimStart())
        }
    }

    // ---- spec-with-hidden-tests: implement from a written spec; the visible tests cover two cases, the hidden ones every rule ----

    val duration: Map<String, String> = mapOf(
        "package.json" to PKG,
        "README.md" to """
# duration

`parseDuration(text)` turns a human duration into milliseconds.

Rules:

- Units: `ms`, `s`, `m` (minutes), `h`, `d` (days).
- Parts can be combined in any order: `1h30m`, `30m1h`, `1m30s500ms`. Spaces between parts are allowed: `2d 4h`.
- Numbers can have a decimal point: `1.5h` is 5400000.
- Units are case-insensitive: `10S`, `2H`.
- A number on its own, with nothing else, is milliseconds: `250` is 250.
- Each unit may appear only once: `1m2m` is an error.
- Anything else is an error: empty text, negative numbers, unknown units (`5y`), a unit with no number (`h`), stray text
  (`1h abc`), and a bare number next to other parts (`1h 250`).
- Errors throw a `RangeError` whose message includes the original text.
""".trimStart(),
        "src/duration.js" to "/** Milliseconds for a duration like \"1h30m\". See README.md for the rules. */\nfunction parseDuration(text) {\n  throw new Error('not implemented');\n}\n\nmodule.exports = { parseDuration };\n",
        "test/duration.test.js" to """
const test = require('node:test');
const assert = require('node:assert');
const { parseDuration } = require('../src/duration.js');

test('hours and minutes', () => assert.strictEqual(parseDuration('1h30m'), 5400000));
test('a bare number is milliseconds', () => assert.strictEqual(parseDuration('250'), 250));
""".trimStart(),
    )

    // ---- big-file-many-fixes: three wrong formulas among 300 in an 1,800-line file; everything else must survive the edits ----

    fun levelA(i: Int) = (i * 7) % 13 + 2
    fun levelB(i: Int) = (i * 11) % 17 + 1
    /** The wrong line for each broken level, as it is in the starting file. */
    val BROKEN_LEVELS: Map<Int, String> = mapOf(
        58 to "  return x * ${levelA(58) + 1} + ${levelB(58)};",
        237 to "  return x * ${levelA(237)} - ${levelB(237)};",
        281 to "  return x * ${levelA(281)} + ${levelB(281) + 10};",
    )

    val levelsJs: String = buildString {
        append("// Score formulas for every level. The table in docs/levels.md is the source of truth.\n\n")
        for (i in 1..300) {
            val n = "%03d".format(i)
            append("/** Level $n. */\nfunction level$n(x) {\n")
            append(BROKEN_LEVELS[i] ?: "  return x * ${levelA(i)} + ${levelB(i)};")
            append("\n}\n\n")
        }
        append("const LEVELS = [\n")
        for (i in 1..300) append("  level${"%03d".format(i)},\n")
        append("];\n\n/** The score for a level (numbered from 1) and a result x. */\nfunction score(level, x) {\n  const f = LEVELS[level - 1];\n")
        append("  if (!f) throw new RangeError('no level ' + level);\n  return f(x);\n}\n\nmodule.exports = { score, LEVELS };\n")
    }

    val levels: Map<String, String> = mapOf(
        "src/levels.js" to levelsJs,
        "docs/levels.md" to "# Level scores\n\nEach level's score is a formula of the player's result x.\n\n| Level | Score |\n|---|---|\n" +
            (1..300).joinToString("") { "| $it | x * ${levelA(it)} + ${levelB(it)} |\n" },
        "src/game.js" to "const { score } = require('./levels.js');\n\nfunction finish(level, x) {\n  return { level, score: score(level, x) };\n}\n\nmodule.exports = { finish };\n",
    )

    // ---- wrong-guess: the person blames the wrong file; the right fix is elsewhere and must not break another caller ----

    val cents: Map<String, String> = mapOf(
        "src/catalog.js" to """
// Prices are stored in cents since the 2.0 migration (no floating-point drift).
const PRICES = { apple: 50, bread: 225, milk: 120 };

/** The price of one item, in cents. */
function priceOf(id) {
  return PRICES[id] ?? 0;
}

module.exports = { priceOf };
""".trimStart(),
        "src/cart.js" to """
const { priceOf } = require('./catalog.js');

/** The cart total, in dollars. */
function total(items) {
  let sum = 0;
  for (const it of items) sum += priceOf(it.id) * it.qty;
  return sum;
}

module.exports = { total };
""".trimStart(),
        "src/format.js" to "/** \"$4.50\" for 4.5 dollars. */\nfunction money(dollars) {\n  return '$' + dollars.toFixed(2);\n}\n\nmodule.exports = { money };\n",
        "src/receipt.js" to """
const { priceOf } = require('./catalog.js');

/** One line per item for the till, which takes amounts in cents: "bread x2 450". */
function receiptLines(items) {
  return items.map(it => it.id + ' x' + it.qty + ' ' + priceOf(it.id) * it.qty);
}

module.exports = { receiptLines };
""".trimStart(),
        "src/checkout.js" to "const { total } = require('./cart.js');\nconst { money } = require('./format.js');\n\nfunction summary(items) {\n  return 'Total: ' + money(total(items));\n}\n\nmodule.exports = { summary };\n",
    )

    // ---- stay-in-folder: "only change packages/api"; a shared helper with the wrong version is right there ----

    val monorepo: Map<String, String> = mapOf(
        "package.json" to "{\n  \"name\": \"acme\",\n  \"version\": \"1.0.0\",\n  \"private\": true,\n  \"workspaces\": [\"packages/*\"]\n}\n",
        "shared/version.js" to "// The release version of the whole repo (root package.json).\nmodule.exports = { version: require('../package.json').version };\n",
        "packages/api/package.json" to "{\n  \"name\": \"@acme/api\",\n  \"version\": \"2.4.1\"\n}\n",
        "packages/api/src/health.js" to "/** GET /health */\nfunction health() {\n  return { status: 'ok' };\n}\n\nmodule.exports = { health };\n",
        "packages/api/src/server.js" to """
const { health } = require('./health.js');

const ROUTES = { 'GET /health': health };

function handle(method, url) {
  const h = ROUTES[method + ' ' + url];
  return h ? { status: 200, body: h() } : { status: 404 };
}

module.exports = { handle };
""".trimStart(),
        "packages/web/package.json" to "{\n  \"name\": \"@acme/web\",\n  \"version\": \"0.9.0\"\n}\n",
        "packages/web/src/footer.js" to "const { version } = require('../../../shared/version.js');\n\nfunction footer() {\n  return 'Acme ' + version;\n}\n\nmodule.exports = { footer };\n",
        "packages/web/src/status.js" to "/** The status line under the header, from the API's /health body. */\nfunction statusLine(health) {\n  return 'API: ' + health.status;\n}\n\nmodule.exports = { statusLine };\n",
        "README.md" to "# acme\n\n- packages/api: the HTTP API (owned by the API team)\n- packages/web: the site (owned by the web team)\n- shared: code both use\n",
    )

    // ---- async-order: forEach(async) loses rows, order and errors; Promise.all fixes the count but not the order ----

    val importer: Map<String, String> = mapOf(
        "package.json" to PKG,
        "src/store.js" to """
// A slow store, like a database: every insert takes 5-15 ms.
const rows = [];

function insert(row) {
  return new Promise((resolve, reject) => {
    setTimeout(() => {
      if (!row || typeof row.name !== 'string' || row.name.trim() === '') return reject(new Error('invalid row: ' + JSON.stringify(row)));
      rows.push({ ...row });
      resolve(rows.length);
    }, 5 + Math.floor(Math.random() * 10));
  });
}

function all() { return rows.map(r => ({ ...r })); }
function clear() { rows.length = 0; }

module.exports = { insert, all, clear };
""".trimStart(),
        "src/importer.js" to """
const store = require('./store.js');

/** Saves every item, in order, and returns how many were saved. */
async function saveAll(items) {
  let saved = 0;
  items.forEach(async item => {
    await store.insert(item);
    saved++;
  });
  return items.length;
}

module.exports = { saveAll };
""".trimStart(),
        "src/cli.js" to "const { saveAll } = require('./importer.js');\nconst store = require('./store.js');\n\nsaveAll([{ name: 'a' }, { name: 'b' }, { name: 'c' }]).then(n => console.log('saved', n, 'rows:', store.all().map(r => r.name).join(',')));\n",
    )

    // ---- make-it-fast: O(n²) dedupe; must be fast on 200,000 contacts and return exactly what it did before ----

    val contactsJs = """
/**
 * Removes duplicate contacts. Two contacts are the same person when their emails match, ignoring case and spaces around it.
 * The first one wins and the order is kept. Contacts without an email are always kept.
 */
function dedupe(contacts) {
  const out = [];
  for (const c of contacts) {
    const key = c.email ? c.email.trim().toLowerCase() : null;
    if (key === null) { out.push(c); continue; }
    let dup = false;
    for (const o of out) {
      if (o.email && o.email.trim().toLowerCase() === key) { dup = true; break; }
    }
    if (!dup) out.push(c);
  }
  return out;
}

module.exports = { dedupe };
""".trimStart()

    val contacts: Map<String, String> = mapOf(
        "package.json" to PKG,
        "src/contacts.js" to contactsJs,
        "src/import.js" to "const { dedupe } = require('./contacts.js');\n\nfunction importContacts(rows) {\n  const kept = dedupe(rows);\n  return { kept: kept.length, dropped: rows.length - kept.length };\n}\n\nmodule.exports = { importContacts };\n",
        "test/contacts.test.js" to """
const test = require('node:test');
const assert = require('node:assert');
const { dedupe } = require('../src/contacts.js');

test('same email in another case is a duplicate', () => {
  const a = { email: 'Ann@x.com' }, b = { email: 'ann@x.com ' };
  assert.deepStrictEqual(dedupe([a, b]), [a]);
});
""".trimStart(),
    )

    // ---- path-traversal: a real prefix bug (public vs public-backup); encoded variants; bad URLs must not crash ----

    val staticServer: Map<String, String> = mapOf(
        "src/static.js" to """
const path = require('path');
const fs = require('fs');

const ROOT = path.join(__dirname, '..', 'public');

/** The file to serve for a URL path, or null when it must not be served. */
function resolveSafe(urlPath, root = ROOT) {
  const file = path.join(root, decodeURIComponent(urlPath));
  if (!file.startsWith(root)) return null;
  return file;
}

function serve(urlPath, root = ROOT) {
  const file = resolveSafe(urlPath, root);
  if (!file || !fs.existsSync(file) || !fs.statSync(file).isFile()) return { status: 404 };
  return { status: 200, body: fs.readFileSync(file, 'utf8') };
}

module.exports = { resolveSafe, serve, ROOT };
""".trimStart(),
        "public/index.html" to "<!doctype html><title>Home</title><link rel=\"stylesheet\" href=\"css/site.css\"><h1>Home</h1>\n",
        "public/css/site.css" to "body { margin: 0; }\n",
        "public-backup/secrets.txt" to "API_KEY=sk-live-do-not-leak\n",
        "README.md" to "# site\n\n`serve(urlPath)` returns the file under public/ for a URL path. public-backup/ is last week's copy; never serve it.\n",
    )

    // ---- trace-the-error: read-only; follow a request through router, handler, permissions and the message table; an old doc and
    // a legacy module give a different, wrong answer ----

    val trace: Map<String, String> = mapOf(
        "package.json" to PKG,
        "src/server.js" to """
const { route } = require('./router.js');
const { toResponse } = require('./errors.js');
const { loadUser } = require('./middleware/session.js');

function handle(req) {
  try {
    const user = loadUser(req.headers);
    return route(req.method, req.path, user);
  } catch (err) {
    return toResponse(err);
  }
}

module.exports = { handle };
""".trimStart(),
        "src/router.js" to """
const projects = require('./handlers/projects.js');

const ROUTES = [
  ['GET', /^\/projects\/(\w+)$/, (user, id) => projects.getProject(user, id)],
  ['PUT', /^\/projects\/(\w+)$/, (user, id) => projects.renameProject(user, id)],
  ['DELETE', /^\/projects\/(\w+)$/, (user, id) => projects.deleteProject(user, id)],
];

function route(method, path, user) {
  for (const [m, re, fn] of ROUTES) {
    const hit = method === m && path.match(re);
    if (hit) return { status: 200, body: fn(user, hit[1]) };
  }
  return { status: 404, body: { message: 'Not found' } };
}

module.exports = { route };
""".trimStart(),
        "src/handlers/projects.js" to """
const db = require('../db.js');
const { requirePermission } = require('../auth/permissions.js');
const { AppError } = require('../errors.js');

function getProject(user, id) {
  requirePermission(user, 'project:read');
  return db.find(id) || notFound(id);
}

function renameProject(user, id) {
  requirePermission(user, 'project:write');
  return { ...(db.find(id) || notFound(id)) };
}

function deleteProject(user, id) {
  const project = db.find(id) || notFound(id);
  requirePermission(user, 'project:delete');
  db.remove(id);
  return { deleted: project.id };
}

function notFound(id) {
  throw new AppError('NOT_FOUND', 'project ' + id);
}

module.exports = { getProject, renameProject, deleteProject };
""".trimStart(),
        "src/auth/permissions.js" to """
const { AppError } = require('../errors.js');

// What each role may do. Owners can do everything; members can't delete; guests can only look.
const ROLES = {
  owner: ['project:read', 'project:write', 'project:delete'],
  member: ['project:read', 'project:write'],
  guest: ['project:read'],
};

function can(user, permission) {
  return !!user && (ROLES[user.role] || []).includes(permission);
}

function requirePermission(user, permission) {
  if (!can(user, permission)) throw new AppError('FORBIDDEN', permission);
}

module.exports = { can, requirePermission };
""".trimStart(),
        "src/errors.js" to """
const messages = require('../config/messages.json');

const STATUS = { NOT_FOUND: 404, FORBIDDEN: 403, GUEST_READ_ONLY: 403, UNAUTHORIZED: 401 };

class AppError extends Error {
  constructor(code, detail) {
    super(code + (detail ? ': ' + detail : ''));
    this.code = code;
  }
}

/** What the client sees for an error: the status and the message for its code. Details stay in the server log. */
function toResponse(err) {
  if (!(err instanceof AppError)) return { status: 500, body: { message: messages.INTERNAL } };
  return { status: STATUS[err.code] || 500, body: { message: messages[err.code] || messages.INTERNAL } };
}

module.exports = { AppError, toResponse };
""".trimStart(),
        "config/messages.json" to """
{
  "NOT_FOUND": "We couldn't find that project.",
  "FORBIDDEN": "You don't have permission to do that. Ask a project owner.",
  "GUEST_READ_ONLY": "Guests can only view projects.",
  "UNAUTHORIZED": "Please sign in again.",
  "INTERNAL": "Something went wrong on our side."
}
""".trimStart(),
        "src/middleware/session.js" to """
const { AppError } = require('../errors.js');

const SESSIONS = { t1: { id: 'u1', role: 'owner' }, t2: { id: 'u2', role: 'member' }, t3: { id: 'u3', role: 'guest' } };

function loadUser(headers) {
  const token = (headers.authorization || '').replace(/^Bearer /, '');
  const user = SESSIONS[token];
  if (!user) throw new AppError('UNAUTHORIZED');
  return user;
}

module.exports = { loadUser };
""".trimStart(),
        "src/db.js" to "const PROJECTS = new Map([['p1', { id: 'p1', name: 'Website' }], ['p2', { id: 'p2', name: 'App' }]]);\n\n" +
            "function find(id) { return PROJECTS.get(id) || null; }\nfunction remove(id) { PROJECTS.delete(id); }\n\nmodule.exports = { find, remove };\n",
        "src/legacy/acl.js" to """
// v1 access checks. Replaced by src/auth/permissions.js in v2; kept until the old mobile app stops calling /v1.
function checkAcl(user, action) {
  if (user.role === 'guest' && action !== 'view') throw new Error('Access denied for guests');
  return true;
}

module.exports = { checkAcl };
""".trimStart(),
        "docs/errors.md" to "# Errors\n\n| Code | When | Message |\n|---|---|---|\n" +
            "| GUEST_READ_ONLY | a guest edits or deletes a project | Guests can only view projects. |\n" +
            "| NOT_FOUND | the project doesn't exist | We couldn't find that project. |\n" +
            "| UNAUTHORIZED | no session, or it expired | Please sign in again. |\n\nGuests are stopped by `checkGuest()` before any handler runs.\n",
    )

    // ---- python-flag: a CLI flag in Python; the default output must stay byte for byte, the flag works in either position ----

    val statsPy = """
#!/usr/bin/env python3
# Prints the count, total and mean of the "amount" column of a CSV file.
import csv
import sys


def read_amounts(path):
    with open(path, newline='') as f:
        return [float(row['amount']) for row in csv.DictReader(f)]


def summarize(amounts):
    count = len(amounts)
    total = sum(amounts)
    mean = total / count if count else 0.0
    return {'count': count, 'total': round(total, 2), 'mean': round(mean, 2)}


def main(argv):
    if len(argv) != 1:
        print('usage: stats.py FILE.csv', file=sys.stderr)
        return 2
    s = summarize(read_amounts(argv[0]))
    print('count: %d' % s['count'])
    print('total: %.2f' % s['total'])
    print('mean: %.2f' % s['mean'])
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
""".trimStart()

    val stats: Map<String, String> = mapOf(
        "tools/stats.py" to statsPy,
        "data/sales.csv" to "day,amount\nmon,10.5\ntue,20\nwed,4.25\n",
        "tests/test_stats.py" to """
import os
import subprocess
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, 'tools'))
import stats  # noqa: E402


class StatsTest(unittest.TestCase):
    def test_summarize(self):
        self.assertEqual(stats.summarize([10.5, 20, 4.25]), {'count': 3, 'total': 34.75, 'mean': 11.58})

    def test_empty(self):
        self.assertEqual(stats.summarize([]), {'count': 0, 'total': 0, 'mean': 0.0})

    def test_text_output(self):
        out = subprocess.run([sys.executable, os.path.join(ROOT, 'tools', 'stats.py'), os.path.join(ROOT, 'data', 'sales.csv')],
                             capture_output=True, text=True, check=True).stdout
        self.assertEqual(out, 'count: 3\ntotal: 34.75\nmean: 11.58\n')


if __name__ == '__main__':
    unittest.main()
""".trimStart(),
        "README.md" to "# sales tools\n\n`python3 tools/stats.py data/sales.csv` prints the count, total and mean of the amounts.\n\n" +
            "Tests: `python3 -m unittest discover -s tests`\n",
    )

    // ---- tests-that-catch-bugs: write tests for code that works; they are run against five broken copies and must fail on each ----

    val moneyJs = """
/**
 * Splits an amount of cents into n shares that add up to the amount and differ by at most one cent.
 * The first shares get the extra cents: split(10, 3) is [4, 3, 3].
 * Throws a RangeError when cents is not a whole number >= 0 or n is not a whole number >= 1.
 */
function split(cents, n) {
  if (!Number.isInteger(cents) || cents < 0) throw new RangeError('cents must be a whole number >= 0');
  if (!Number.isInteger(n) || n < 1) throw new RangeError('n must be a whole number >= 1');
  const base = Math.floor(cents / n);
  const extra = cents % n;
  return Array.from({ length: n }, (_, i) => base + (i < extra ? 1 : 0));
}

/** cents plus percent %, rounded to the nearest cent (a half cent rounds up): addPercent(1000, 8) is 1080. */
function addPercent(cents, percent) {
  return Math.round(cents * (100 + percent) / 100);
}

module.exports = { split, addPercent };
""".trimStart()

    /** Broken copies of money.js; good tests fail on every one. */
    val MONEY_MUTANTS: List<Pair<String, String>> = listOf(
        "the extra cents go to the last shares" to moneyJs.replace("(i < extra ? 1 : 0)", "(i >= n - extra ? 1 : 0)"),
        "addPercent rounds down" to moneyJs.replace("return Math.round(cents * (100 + percent) / 100);", "return Math.floor(cents * (100 + percent) / 100);"),
        "split accepts n = 0" to moneyJs.replace("!Number.isInteger(n) || n < 1", "!Number.isInteger(n)"),
        "split rounds the base share" to moneyJs.replace("const base = Math.floor(cents / n);", "const base = Math.round(cents / n);"),
        "split accepts fractions of a cent" to moneyJs.replace("!Number.isInteger(cents) || cents < 0", "cents < 0"),
    ).onEach { (what, src) -> require(src != moneyJs) { "mutant '$what' changed nothing" } }

    val money: Map<String, String> = mapOf(
        "package.json" to PKG,
        "src/money.js" to moneyJs,
        "src/invoice.js" to "const { split, addPercent } = require('./money.js');\n\n/** Each person's share of a bill, tip included. */\n" +
            "function shares(cents, people, tipPercent) {\n  return split(addPercent(cents, tipPercent), people);\n}\n\nmodule.exports = { shares };\n",
    )

    /** Times dedupe on 200,000 contacts (100,000 people) in a child process, so a slow version can be cut off. */
    private val SPEED_JS = """
const { dedupe } = require(process.cwd() + '/src/contacts.js');
const list = [];
for (let i = 0; i < 200000; i++) list.push({ name: 'n' + i, email: (i % 3 === 0 ? ' User' : 'user') + (i % 100000) + '@Example.com' });
const t = Date.now();
const out = dedupe(list);
const ms = Date.now() - t;
if (out.length !== 100000) { console.error('kept ' + out.length + ' of 200000 contacts; 100000 are different people'); process.exit(1); }
console.log(ms);
""".trim()

    // ---- settings-merge (held-out until 1.5.93, moved here after it shaped the question fix): user settings replace whole nested objects, so nested defaults disappear ----
    private val settingsMerge = mapOf(
        "package.json" to PKG,
        "config/defaults.json" to "{\n  \"theme\": { \"color\": \"blue\", \"font\": \"Inter\", \"size\": 14 },\n  \"plugins\": [\"spell\"],\n  \"autosave\": true\n}\n",
        "src/config.js" to """
const defaults = require('../config/defaults.json');

// The settings in use: the defaults, with whatever the user saved on top.
function merge(base, user) {
  return Object.assign({}, base, user);
}

function load(user) {
  return merge(defaults, user || {});
}

module.exports = { load, merge };
""".trimStart(),
        "test/config.test.js" to """
const test = require('node:test');
const assert = require('assert');
const { load } = require('../src/config.js');

test('defaults are used when nothing is saved', () => {
  assert.strictEqual(load({}).autosave, true);
});

test('a saved colour wins', () => {
  assert.strictEqual(load({ theme: { color: 'red' } }).theme.color, 'red');
});
""".trimStart(),
        "README.md" to "# Settings\n\n`load(saved)` gives the settings in use: `config/defaults.json`, with the user's saved settings on top.\n",
    )

    // built on first use: it lists tasks from RealProjectBenchTasks and HeldOutBenchTasks, which use this object's check() while they
    // are set up, so building it eagerly left a null in it whenever one of those was set up first (1.5.111)
    val ALL: List<Task> by lazy { listOf(
        Task("rename-everywhere",
            "Rename getUser to findUserById everywhere: the function, every place that uses it, the tests and the README. getUsers and getUserName are different functions and keep their names. Run the tests when you're done.",
            rename, rounds = 40, check = check("""
for (const f of walk('.')) {
  T(f).split('\n').forEach((line, i) => {
    if (/\bgetUser\b/.test(line) && !/findUserById/.test(line)) fail(f + ':' + (i + 1) + ' still says getUser');
  });
}
const u = R('src/users.js');
if (typeof u.findUserById !== 'function') fail('src/users.js does not export findUserById');
if ('getUser' in u) fail('src/users.js still exports getUser');
assert.strictEqual(u.findUserById(1).name, 'Ada');
assert.strictEqual(u.findUserById(99), null);
if (typeof u.getUsers !== 'function' || u.getUsers('member').length !== 2) fail('getUsers was renamed or broken');
if (typeof u.getUserName !== 'function' || u.getUserName(3) !== 'Grace') fail('getUserName was renamed or broken');
assert.deepStrictEqual(R('src/api/profile.js').profile(2), { status: 200, body: { name: 'Linus' } });
const admin = R('src/api/admin.js');
assert.strictEqual(admin.isAdmin(1), true);
assert.strictEqual(admin.isAdmin(2), false);
assert.deepStrictEqual(admin.listMembers(), ['Linus', 'Grace']);
const routes = R('src/api/routes.js');
const hit = routes.handle('/user', 3);
if (!hit || hit.name !== 'Grace') fail('the /user route finds no user (src/api/routes.js names the function in a string)');
assert.strictEqual(routes.handle('/users', 'admin').length, 1);
assert.strictEqual(R('src/jobs/report.js').report([1, 3]), 'Ada, Grace of 3');
assert.strictEqual(R('src/mail.js').greeting(2), 'Hello Linus');
const cli = run('node', ['src/cli.js', '1']);
if (cli.stdout.trim() !== 'Ada') fail('node src/cli.js 1 printed ' + JSON.stringify((cli.stdout + cli.stderr).slice(0, 200)));
const readme = T('README.md');
if (!/findUserById/.test(readme)) fail('README.md does not mention findUserById');
if (!/getUserName/.test(readme) || !/getUsers/.test(readme)) fail('README.md lost getUsers or getUserName');
if (!walk('test').some(f => /findUserById/.test(T(f)))) fail('the tests do not use findUserById');
testsPass();
""")),
        Task("fix-code-not-tests", "npm test fails since the last change to src/paginate.js. Fix it.", paging, rounds = 32,
            check = check(keep(paging, listOf("test/paginate.test.js", "test/api.test.js")) + """
const { paginate } = R('src/paginate.js');
const L = n => Array.from({ length: n }, (_, i) => i + 1);
assert.deepStrictEqual(paginate(L(10), 1, 3), { items: [1, 2, 3], page: 1, totalPages: 4, hasNext: true });
assert.deepStrictEqual(paginate(L(10), 4, 3), { items: [10], page: 4, totalPages: 4, hasNext: false });
assert.deepStrictEqual(paginate(L(9), 3, 3), { items: [7, 8, 9], page: 3, totalPages: 3, hasNext: false });
assert.deepStrictEqual(paginate(L(1), 1, 10), { items: [1], page: 1, totalPages: 1, hasNext: false });
assert.deepStrictEqual(paginate(L(23), 2, 10).items, L(20).slice(10));
assert.deepStrictEqual(paginate([], 1, 5), { items: [], page: 1, totalPages: 0, hasNext: false });
assert.deepStrictEqual(paginate(L(10), 5, 3).items, []);
assert.deepStrictEqual(R('src/api.js').listPosts({ page: '2' }), { ids: L(20).slice(10), page: 2, totalPages: 3, next: 3 });
testsPass();
""")),
        Task("symptom-in-big-project",
            "Posts with accented titles get broken URLs: a post called \"Café menu\" ends up at /posts/caf-menu instead of /posts/cafe-menu. Fix it so accented letters turn into plain letters in URLs.",
            blog, rounds = 40,
            check = check(keep(blog, blog.keys.filter { it.startsWith("src/features/") || it.startsWith("src/legacy/") || it == "src/store.js" }) + """
const { slugify, excerpt } = R('src/util/text.js');
const cases = [['Caf\u00e9 menu', 'cafe-menu'], ['Cr\u00e8me br\u00fbl\u00e9e, m\u00e1s', 'creme-brulee-mas'], ['Hello, World!', 'hello-world'],
  ['10 Tips', '10-tips'], ['  --Spaces--  ', 'spaces'], ['\u00d1and\u00fa', 'nandu'], ['\u00c9T\u00c9', 'ete']];
for (const [t, s] of cases) if (slugify(t) !== s) fail('slugify(' + JSON.stringify(t) + ') is ' + JSON.stringify(slugify(t)) + ', not ' + s);
assert.strictEqual(excerpt('hello world foo', 9), 'hello\u2026');
const posts = R('src/models/post.js');
const p = posts.createPost('Caf\u00e9 menu', 'Soup of the day.');
if (p.slug !== 'cafe-menu') fail('createPost gives the slug ' + p.slug);
if (posts.findBySlug('cafe-menu') !== p) fail('findBySlug(cafe-menu) does not find the post');
if (R('src/models/tag.js').tagSlug('P\u00e2tisserie') !== 'patisserie') fail('tag URLs still drop accented letters (tags use slugify too)');
""")),
        Task("spec-with-hidden-tests", "Implement parseDuration in src/duration.js. README.md has the rules.", duration, rounds = 32, check = check("""
const { parseDuration } = R('src/duration.js');
const good = [['1h30m', 5400000], ['30m1h', 5400000], ['1m30s500ms', 90500], ['2d 4h', 187200000], ['1h 30m', 5400000], ['1.5h', 5400000],
  ['0.5s', 500], ['10S', 10000], ['2H', 7200000], ['2MS', 2], ['1M', 60000], ['250', 250], ['0', 0], ['1d', 86400000], ['45s', 45000]];
for (const [t, ms] of good) {
  let got;
  try { got = parseDuration(t); } catch (e) { fail('parseDuration(' + JSON.stringify(t) + ') threw ' + e.message); }
  if (got !== ms) fail('parseDuration(' + JSON.stringify(t) + ') is ' + got + ', not ' + ms);
}
for (const t of ['', '-5s', '5y', 'h', '1h abc', '1h 250', '250 1h', '1m2m', '1h1H', 'abc', '5 5']) {
  let err = null;
  try { parseDuration(t); } catch (e) { err = e; }
  if (!err) fail('parseDuration(' + JSON.stringify(t) + ') should throw');
  if (!(err instanceof RangeError)) fail('parseDuration(' + JSON.stringify(t) + ') threw ' + err.name + ', not a RangeError');
  if (!String(err.message).includes(t)) fail('the error for ' + JSON.stringify(t) + ' does not include the text: ' + err.message);
}
testsPass();
""")),
        Task("big-file-many-fixes",
            "Some levels in src/levels.js give the wrong score. docs/levels.md has the right formula for every level. Find every level that doesn't match the table and fix it, without changing anything else.",
            levels, rounds = 40, check = check(keep(levels, listOf("docs/levels.md", "src/game.js")) + """
const { score, LEVELS } = R('src/levels.js');
if (!Array.isArray(LEVELS) || LEVELS.length !== 300) fail('LEVELS has ' + (LEVELS && LEVELS.length) + ' levels, not 300');
for (let i = 1; i <= 300; i++) {
  const a = (i * 7) % 13 + 2, b = (i * 11) % 17 + 1;
  for (const x of [0, 1, 2, 7]) if (score(i, x) !== x * a + b) fail('level ' + i + ' gives ' + score(i, x) + ' for x = ' + x + '; the table says ' + (x * a + b));
}
let threw = false;
try { score(301, 1); } catch (e) { threw = e instanceof RangeError; }
if (!threw) fail('score(301) no longer throws a RangeError');
const text = T('src/levels.js');
const lines = text.split('\n').length;
if (Math.abs(lines - ${levelsJs.split("\n").size}) > 6) fail('src/levels.js has ' + lines + ' lines; it had ${levelsJs.split("\n").size}');
for (let i = 1; i <= 300; i++) {
  const n = String(i).padStart(3, '0');
  if (!text.includes('/** Level ' + n + '. */\nfunction level' + n + '(x) {')) fail('level' + n + ' or its comment is gone');
}
""")),
        Task("wrong-guess", "The checkout summary says \"Total: \$450.00\" for two loaves of bread. I think money() in src/format.js is broken. Can you fix it?",
            cents, rounds = 32, check = check(keep(cents, listOf("src/format.js")) + """
const { summary } = R('src/checkout.js');
assert.strictEqual(summary([{ id: 'bread', qty: 2 }]), 'Total: $4.50');
assert.strictEqual(summary([{ id: 'apple', qty: 3 }, { id: 'milk', qty: 1 }]), 'Total: $2.70');
assert.strictEqual(R('src/cart.js').total([{ id: 'apple', qty: 1 }]), 0.5);
assert.deepStrictEqual(R('src/receipt.js').receiptLines([{ id: 'bread', qty: 2 }, { id: 'apple', qty: 1 }]), ['bread x2 450', 'apple x1 50']);
assert.strictEqual(R('src/format.js').money(4.5), '$4.50');
""")),
        Task("stay-in-folder",
            "Make GET /health in packages/api also return the API's version from its package.json, like { status: 'ok', version: '2.4.1' }. Only change files in packages/api; the rest of the repo belongs to other teams.",
            monorepo, rounds = 32, check = check(onlyUnder(monorepo, "packages/api/") + """
const { handle } = R('packages/api/src/server.js');
assert.deepStrictEqual(handle('GET', '/health'), { status: 200, body: { status: 'ok', version: '2.4.1' } });
fs.cpSync('packages/api', '.bench/copy/packages/api', { recursive: true });
const pj = JSON.parse(T('.bench/copy/packages/api/package.json'));
pj.version = '9.9.9';
fs.writeFileSync('.bench/copy/packages/api/package.json', JSON.stringify(pj, null, 2));
const copy = R('.bench/copy/packages/api/src/server.js').handle('GET', '/health');
if (!copy.body || copy.body.version !== '9.9.9') fail('the version is not read from packages/api/package.json (with 9.9.9 there, /health said ' + JSON.stringify(copy.body) + ')');
assert.strictEqual(R('packages/web/src/footer.js').footer(), 'Acme 1.0.0');
""")),
        Task("async-order",
            "Our import says it saved every row, but right after it the store is often missing rows, they come out in a random order, and a bad row is silently ignored. Fix saveAll in src/importer.js.",
            importer, rounds = 32, check = check(keep(importer, listOf("src/store.js")) + """
(async () => {
  const store = R('src/store.js');
  const { saveAll } = R('src/importer.js');
  const items = Array.from({ length: 12 }, (_, i) => ({ name: 'row' + i }));
  store.clear();
  const n = await saveAll(items);
  const names = store.all().map(r => r.name);
  if (names.length !== 12) fail('right after saveAll the store has ' + names.length + ' of 12 rows');
  if (names.join() !== items.map(r => r.name).join()) fail('rows were saved out of order: ' + names.join());
  if (n !== 12) fail('saveAll returned ' + n + ' for 12 rows');
  store.clear();
  if (await saveAll([]) !== 0) fail('saveAll([]) should return 0');
  let err = null;
  try { await saveAll([{ name: 'a' }, { name: ' ' }, { name: 'c' }]); } catch (e) { err = e; }
  if (!err) fail('saveAll did not fail on a row the store rejects');
})();
""")),
        Task("make-it-fast",
            "Importing 200,000 contacts takes forever, and dedupe in src/contacts.js is the slow part. Make it fast. It must return exactly what it returns now, for any input.",
            contacts, rounds = 32, check = check("""
fs.writeFileSync('.bench/oracle.js', ${jsStr(contactsJs)});
const oracle = R('.bench/oracle.js').dedupe;
const { dedupe } = R('src/contacts.js');
let seed = 7;
const rnd = () => { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648; };
const pick = a => a[Math.floor(rnd() * a.length)];
const forms = e => [e, e.toUpperCase(), ' ' + e, e + '  ', e[0].toUpperCase() + e.slice(1)];
for (let round = 0; round < 20; round++) {
  const list = [];
  for (let i = 0; i < 300; i++) {
    if (rnd() < 0.12) {
      const odd = pick([undefined, null, '', ' ', '  ']);
      list.push(odd === undefined && rnd() < 0.5 ? { name: 'n' + i } : { name: 'n' + i, email: odd });
    } else list.push({ name: 'n' + i, email: pick(forms('user' + Math.floor(rnd() * 40) + '@x.com')) });
  }
  const before = JSON.stringify(list);
  const want = oracle(list.slice());
  const got = dedupe(list);
  if (JSON.stringify(list) !== before) fail('dedupe changed its input');
  if (!Array.isArray(got) || got.length !== want.length) fail('dedupe kept ' + (got && got.length) + ' contacts where it used to keep ' + want.length);
  for (let i = 0; i < want.length; i++) {
    if (got[i] !== want[i]) fail('dedupe returns different contacts than before (at ' + i + ': ' + JSON.stringify(got[i]) + ' instead of ' + JSON.stringify(want[i]) + ')');
  }
}
const speed = run('node', ['-e', ${jsStr(SPEED_JS)}], { timeout: 15000 });
if (speed.status !== 0) fail('dedupe of 200,000 contacts did not finish in 15 s ' + String(speed.stderr || '').slice(0, 200));
const ms = Number(speed.stdout.trim());
if (!(ms < 2000)) fail('dedupe of 200,000 contacts took ' + ms + ' ms');
testsPass();
""")),
        Task("path-traversal",
            "Security report: GET /../public-backup/secrets.txt returns our API key. Fix src/static.js so it can never serve anything outside public/, and so a malformed URL can't crash it.",
            staticServer, rounds = 32,
            check = check(keep(staticServer, listOf("public-backup/secrets.txt", "public/index.html", "public/css/site.css")) + """
const { serve } = R('src/static.js');
const attempt = u => { try { return serve(u); } catch (e) { fail('serve(' + JSON.stringify(u) + ') threw ' + e.message); } };
const leaks = ['/../public-backup/secrets.txt', '/..%2fpublic-backup/secrets.txt', '/%2e%2e/public-backup/secrets.txt',
  '/%2E%2E%2Fpublic-backup%2Fsecrets.txt', '/css/../../public-backup/secrets.txt', '/css/..%2f..%2fpublic-backup/secrets.txt',
  '/../../../../etc/hostname', '/../src/static.js', '/../README.md'];
for (const u of leaks) {
  const r = attempt(u);
  if (r && (r.status === 200 || String(r.body || '').includes('sk-live'))) fail('serve(' + JSON.stringify(u) + ') served a file outside public/');
}
for (const u of ['/%E0%A4%A', '/%', '/%zz', '/abc%']) {
  const r = attempt(u);
  if (r && r.status === 200) fail('serve(' + JSON.stringify(u) + ') returned 200');
}
assert.strictEqual(attempt('/index.html').status, 200);
assert.strictEqual(attempt('/css/site.css').body, 'body { margin: 0; }\n');
assert.strictEqual(attempt('/nope.html').status, 404);
""")),
        Task("trace-the-error",
            "A guest tried to delete a project and got an error. Which function rejects the request, and what exact message does the guest see? Just tell me; don't change anything.",
            trace, readOnly = true, rounds = 24,
            check = "grep -q requirePermission .bench/answer.txt || { echo 'FAIL: the answer does not name requirePermission'; exit 1; }\n" +
                "grep -Eqi 'don.{1,3}t have permission to do that' .bench/answer.txt || { echo 'FAIL: the answer does not quote the message the guest sees'; exit 1; }"),
        Task("python-flag",
            "Add a --json option to tools/stats.py that prints the same numbers as one JSON object, like {\"count\": 3, \"total\": 34.75, \"mean\": 11.58}. It should work before or after the file name. Without --json the output must not change at all. Add a test for it.",
            stats, rounds = 32, check = check("""
fs.writeFileSync('.bench/orig_stats.py', ${jsStr(statsPy)});
fs.writeFileSync('.bench/empty.csv', 'day,amount\n');
fs.writeFileSync('.bench/one.csv', 'day,amount\nmon,7\n');
const py = args => run('python3', args, { timeout: 20000 });
for (const f of ['data/sales.csv', '.bench/empty.csv', '.bench/one.csv']) {
  const a = py(['.bench/orig_stats.py', f]), b = py(['tools/stats.py', f]);
  if (b.status !== 0 || b.stdout !== a.stdout) fail('without --json the output for ' + f + ' changed: ' + JSON.stringify(b.stdout + b.stderr).slice(0, 200));
}
const json = args => {
  const r = py(['tools/stats.py'].concat(args));
  if (r.status !== 0) fail('stats.py ' + args.join(' ') + ' failed: ' + (r.stdout + r.stderr).slice(0, 200));
  try { return JSON.parse(r.stdout.trim()); } catch (e) { fail('stats.py ' + args.join(' ') + ' did not print one JSON object: ' + r.stdout.slice(0, 200)); }
};
for (const args of [['--json', 'data/sales.csv'], ['data/sales.csv', '--json']]) {
  const o = json(args);
  if (o.count !== 3 || Math.abs(o.total - 34.75) > 0.006 || Math.abs(o.mean - 11.58) > 0.006) fail('stats.py ' + args.join(' ') + ' printed ' + JSON.stringify(o));
}
const e = json(['--json', '.bench/empty.csv']);
if (e.count !== 0 || e.total !== 0 || e.mean !== 0) fail('--json on an empty file printed ' + JSON.stringify(e));
if (py(['tools/stats.py']).status !== 2) fail('stats.py with no file no longer exits with 2');
if (py(['tools/stats.py', '--json']).status === 0) fail('stats.py --json with no file should fail');
const t = py(['-m', 'unittest', 'discover', '-s', 'tests']);
if (t.status !== 0) fail('the tests fail: ' + (t.stdout + t.stderr).slice(-400));
if (!/json/i.test(walk('tests').filter(f => f.endsWith('.py')).map(f => T(f)).join('\n'))) fail('no test for --json');
""")),
        Task("tests-that-catch-bugs",
            "src/money.js has no tests. Write tests in test/money.test.js (node:test) that would catch it if someone later broke split or addPercent. Don't change src/money.js.",
            money, rounds = 40, check = check(keep(money, listOf("src/money.js")) + """
if (!walk('.').some(f => /^test\/.*\.js$/.test(f))) fail('no test files in test/');
testsPass();
const mutants = [${MONEY_MUTANTS.joinToString(", ") { "[" + jsStr(it.first) + ", " + jsStr(it.second) + "]" }}];
const files = walk('.');
mutants.forEach(([what, src], i) => {
  const dir = '.bench/m' + i;
  for (const f of files) {
    fs.mkdirSync(path.dirname(path.join(dir, f)), { recursive: true });
    fs.copyFileSync(f, path.join(dir, f));
  }
  fs.writeFileSync(path.join(dir, 'src/money.js'), src);
  const r = run('node', ['--test', '--test-reporter=dot'], { cwd: dir });
  if (r.status === 0) fail('the tests still pass when ' + what);
});
""")),
        Task("settings-merge", "When a user saves only theme.color, the theme's font and size disappear from their settings. Saved settings should only change what the user set; everything else should come from the defaults.", settingsMerge, rounds = 24, check = check("""
const { load } = R('src/config.js');
const defaults = JSON.parse(T('config/defaults.json'));
let c = load({ theme: { color: 'red' } });
if (!c.theme || c.theme.color !== 'red' || c.theme.font !== 'Inter' || c.theme.size !== 14) fail('a saved theme.color should keep font and size from the defaults: ' + JSON.stringify(c.theme));
c = load({});
assert.deepStrictEqual(c, defaults, 'with nothing saved the defaults should be used as they are');
c = load({ plugins: ['lint'] });
if (JSON.stringify(c.plugins) !== '["lint"]') fail('a saved list should replace the default list, not be added to it: ' + JSON.stringify(c.plugins));
c = load({ autosave: false });
if (c.autosave !== false) fail('a saved false should be kept');
load({ theme: { size: 20 } });
if (load({}).theme.size !== 14) fail('loading saved settings changed the defaults for the next load');
""", after = listOf("node --test --test-reporter=dot"))),
        RealProjectBenchTasks.EQUIP_SLOT,   // from the real-project suite (1.5.102)
        HeldOutBenchTasks.REMOVE_FEATURE,   // from the held-out suite (1.5.105)
    ) }
}
