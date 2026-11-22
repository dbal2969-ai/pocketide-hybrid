package dev.pocketide.app.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Proves the hard suite's checks measure what they claim, without a model: every check fails on the starting project, passes
 * on a reference solution, and rejects the shortcuts a model is likely to take. Needs node and python3 (skipped without them).
 */
class HardBenchTasksTest {
    companion object {
        @BeforeClass @JvmStatic fun tools() {
            assumeTrue("node is needed for the benchmark checks", has("node --version"))
            assumeTrue("python3 is needed for the benchmark checks", has("python3 --version"))
        }

        private fun has(cmd: String) = try { ProcessBuilder("bash", "-c", cmd).start().waitFor() == 0 } catch (e: Exception) { false }
    }

    /** Runs [name]'s check on its starting files after [change], with [answer] as the model's final answer. */
    private fun check(name: String, answer: String, change: (File) -> Unit): Pair<Int, String> {
        val t = HardBenchTasks.ALL.single { it.name == name }
        val dir = Files.createTempDirectory("hardbench").toFile()
        val out = File.createTempFile("hardbench", ".out")
        try {
            for ((p, text) in t.files) dir.put(p, text)
            change(dir)
            dir.put(".bench/answer.txt", answer)
            val p = ProcessBuilder("bash", "-c", t.check).directory(dir).redirectErrorStream(true).redirectOutput(out).start()
            if (!p.waitFor(90, TimeUnit.SECONDS)) { p.destroyForcibly(); return 124 to "timed out" }
            return p.exitValue() to out.readText()
        } finally {
            dir.deleteRecursively(); out.delete()
        }
    }

    private fun passes(name: String, answer: String = "", change: (File) -> Unit) {
        val (code, out) = check(name, answer, change)
        assertEquals("$name should pass the reference solution; the check said: $out", 0, code)
    }

    private fun fails(name: String, why: String, answer: String = "", change: (File) -> Unit = {}) {
        val (code, out) = check(name, answer, change)
        println("$name, when $why: ${out.trim()}")
        assertNotEquals("$name should fail when $why; the check said: $out", 0, code)
        assertTrue("$name failed for an unexpected reason when $why: $out", code == 1)
    }

    private fun File.put(path: String, text: String) = File(this, path).also { it.parentFile.mkdirs() }.writeText(text)

    private fun File.edit(path: String, f: (String) -> String) {
        val file = File(this, path)
        val before = file.readText()
        val after = f(before)
        require(after != before) { "the edit of $path changed nothing" }
        file.writeText(after)
    }

    @Test fun shape() {
        assertEquals(16, HardBenchTasks.ALL.size)
        val names = (BenchTasks.ALL + HardBenchTasks.ALL).map { it.name }
        assertEquals("task names must be unique across suites", names.size, names.toSet().size)
        for (t in HardBenchTasks.ALL) assertTrue("${t.name}: the check must be ASCII", t.check.all { it.code in 1..126 })
        assertEquals("\"\\u00e9\\n\\\"\"", HardBenchTasks.jsStr("\u00e9\n\""))
        assertEquals(BenchTasks.ALL, BenchTasks.select(null, emptySet()))
        assertEquals(BenchTasks.ALL, BenchTasks.select("standard", emptySet()))
        assertEquals(HardBenchTasks.ALL, BenchTasks.select("HARD", emptySet()))
        assertEquals(HeldOutBenchTasks.ALL, BenchTasks.select("heldout", emptySet()))
        assertEquals(42 + ChatBenchTasks.ALL.size + AskBenchTasks.ALL.size + CauseBenchTasks.ALL.size + HarbourBenchTasks.ALL.size, BenchTasks.select("all", emptySet()).size)  // 1.5.125: the chat suite; 1.5.138: the asks suite
        for (t in AskBenchTasks.ALL) assertTrue("${t.name}: the check must be ASCII", t.check.all { it.code in 1..126 })
        assertEquals(RealProjectBenchTasks.ALL, BenchTasks.select("realproject", emptySet()))
        for (t in HeldOutBenchTasks.ALL) assertTrue("${t.name}: the check must be ASCII", t.check.all { it.code in 1..126 })
        assertEquals(listOf("report-only", "python-flag"), BenchTasks.select("standard", setOf("python-flag", "report-only")).map { it.name })
        assertThrows(IllegalArgumentException::class.java) { BenchTasks.select(null, setOf("python-flag", "nope")) }
        assertThrows(IllegalArgumentException::class.java) { BenchTasks.select("hardest", emptySet()) }
    }

    @Test fun everyCheckFailsOnTheStartingProject() {
        for (t in HardBenchTasks.ALL) fails(t.name, "nothing was done")
    }

    @Test fun renameEverywhere() {
        val n = "rename-everywhere"
        val word = Regex("\\bgetUser\\b")
        val rename = { d: File, skip: String? -> HardBenchTasks.rename.keys.filter { it != skip }.forEach { p -> File(d, p).writeText(word.replace(File(d, p).readText(), "findUserById")) } }
        passes(n) { d -> rename(d, null) }
        fails(n, "the name in a string in routes.js was missed") { d -> rename(d, "src/api/routes.js") }
        fails(n, "the README was not updated") { d -> rename(d, "README.md") }
        fails(n, "getUsers and getUserName were renamed too") { d ->
            HardBenchTasks.rename.keys.forEach { p -> File(d, p).writeText(File(d, p).readText().replace("getUser", "findUserById")) }
        }
    }

    @Test fun fixCodeNotTests() {
        val n = "fix-code-not-tests"
        val fixStart = { d: File -> d.edit("src/paginate.js") { it.replace("const start = page * size;", "const start = (page - 1) * size;") } }
        val fixPages = { d: File -> d.edit("src/paginate.js") { it.replace("Math.floor(list.length / size) + 1", "Math.ceil(list.length / size)") } }
        passes(n) { d -> fixStart(d); fixPages(d) }
        fails(n, "only one of the two bugs is fixed") { d -> fixStart(d) }
        fails(n, "a test file was changed") { d -> fixStart(d); fixPages(d); d.edit("test/api.test.js") { "$it\n// checked\n" } }
    }

    private val stripAccents = ".normalize('NFD').replace(/[\\u0300-\\u036f]/g, '')"

    @Test fun symptomInBigProject() {
        val n = "symptom-in-big-project"
        val fix = { d: File -> d.edit("src/util/text.js") { it.replace("    .toLowerCase()\n", "    $stripAccents\n    .toLowerCase()\n") } }
        passes(n) { d -> fix(d) }
        fails(n, "only the post model strips accents") { d ->
            d.edit("src/models/post.js") { it.replace("slug: slugify(title)", "slug: slugify(title$stripAccents)") }
        }
        fails(n, "the search decoy is changed too") { d ->
            fix(d); d.edit("src/features/search/index.js") { it.replace("trim().toLowerCase()", "trim().toLowerCase()$stripAccents") }
        }
    }

    private val durationJs = """
function parseDuration(text) {
  const bad = () => new RangeError('invalid duration: "' + text + '"');
  if (typeof text !== 'string') throw bad();
  const s = text.trim();
  if (s === '') throw bad();
  if (/^\d+(\.\d+)?$/.test(s)) return Number(s);
  const UNITS = { ms: 1, s: 1000, m: 60000, h: 3600000, d: 86400000 };
  const re = /(\d+(?:\.\d+)?)(ms|s|m|h|d)\s*/iy;
  const seen = new Set();
  let ms = 0;
  let pos = 0;
  while (pos < s.length) {
    re.lastIndex = pos;
    const m = re.exec(s);
    if (!m) throw bad();
    const u = m[2].toLowerCase();
    if (seen.has(u)) throw bad();
    seen.add(u);
    ms += Number(m[1]) * UNITS[u];
    pos = re.lastIndex;
  }
  return ms;
}

module.exports = { parseDuration };
""".trimStart()

    @Test fun specWithHiddenTests() {
        val n = "spec-with-hidden-tests"
        passes(n) { d -> d.put("src/duration.js", durationJs) }
        fails(n, "it only handles what the visible tests check") { d ->
            d.put("src/duration.js", "function parseDuration(text) {\n  if (/^\\d+$/.test(text)) return Number(text);\n" +
                "  const U = { ms: 1, s: 1000, m: 60000, h: 3600000, d: 86400000 };\n  let ms = 0;\n" +
                "  for (const m of text.matchAll(/(\\d+)(ms|s|m|h|d)/g)) ms += Number(m[1]) * U[m[2]];\n  return ms;\n}\n\nmodule.exports = { parseDuration };\n")
        }
        fails(n, "errors are plain Errors") { d -> d.put("src/duration.js", durationJs.replace("new RangeError(", "new Error(")) }
        fails(n, "a unit may repeat") { d -> d.put("src/duration.js", durationJs.replace("if (seen.has(u)) throw bad();", "")) }
    }

    @Test fun bigFileManyFixes() {
        val n = "big-file-many-fixes"
        val head = { i: Int -> "function level${"%03d".format(i)}(x) {\n" }
        val good = { i: Int -> "  return x * ${HardBenchTasks.levelA(i)} + ${HardBenchTasks.levelB(i)};" }
        val fix = { t: String, count: Int -> HardBenchTasks.BROKEN_LEVELS.entries.take(count).fold(t) { acc, e -> acc.replace(head(e.key) + e.value, head(e.key) + good(e.key)) } }
        passes(n) { d -> d.edit("src/levels.js") { fix(it, 3) } }
        fails(n, "one wrong level is left") { d -> d.edit("src/levels.js") { fix(it, 2) } }
        fails(n, "the file is rewritten without its comments") { d ->
            d.edit("src/levels.js") { t -> fix(t, 3).lines().filterNot { it.startsWith("/** Level ") }.joinToString("\n") }
        }
    }

    @Test fun wrongGuess() {
        val n = "wrong-guess"
        passes(n) { d -> d.edit("src/cart.js") { it.replace("return sum;", "return sum / 100;") } }
        fails(n, "money() is changed to divide by 100, as the person guessed") { d -> d.edit("src/format.js") { it.replace("dollars.toFixed(2)", "(dollars / 100).toFixed(2)") } }
        fails(n, "priceOf returns dollars, so the till gets dollars") { d -> d.edit("src/catalog.js") { it.replace("return PRICES[id] ?? 0;", "return (PRICES[id] ?? 0) / 100;") } }
    }

    @Test fun stayInFolder() {
        val n = "stay-in-folder"
        val health = "const { version } = require('../package.json');\n\nfunction health() {\n  return { status: 'ok', version };\n}\n\nmodule.exports = { health };\n"
        passes(n) { d -> d.put("packages/api/src/health.js", health) }
        fails(n, "the version is typed in") { d ->
            d.put("packages/api/src/health.js", health.replace("const { version } = require('../package.json');", "const version = '2.4.1';"))
        }
        fails(n, "shared/ is changed") { d ->
            d.put("packages/api/src/health.js", health)
            d.edit("shared/version.js") { "$it// api: 2.4.1\n" }
        }
        fails(n, "a file is added outside packages/api") { d ->
            d.put("packages/api/src/health.js", health.replace("require('../package.json')", "require('../../../shared/api-version.js')"))
            d.put("shared/api-version.js", "module.exports = { version: require('../packages/api/package.json').version };\n")
        }
    }

    @Test fun asyncOrder() {
        val n = "async-order"
        val loop = "  items.forEach(async item => {\n    await store.insert(item);\n    saved++;\n  });\n  return items.length;"
        passes(n) { d -> d.edit("src/importer.js") { it.replace(loop, "  for (const item of items) {\n    await store.insert(item);\n    saved++;\n  }\n  return saved;") } }
        fails(n, "Promise.all saves every row, but not in order") { d ->
            d.edit("src/importer.js") { it.replace(loop, "  await Promise.all(items.map(item => store.insert(item)));\n  return items.length;") }
        }
        fails(n, "a bad row is skipped silently") { d ->
            d.edit("src/importer.js") { it.replace(loop, "  for (const item of items) {\n    try { await store.insert(item); saved++; } catch (e) {}\n  }\n  return saved;") }
        }
    }

    private val fastDedupe = """
function dedupe(contacts) {
  const seen = new Set();
  const out = [];
  for (const c of contacts) {
    const key = c.email ? c.email.trim().toLowerCase() : null;
    if (key === null) { out.push(c); continue; }
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(c);
  }
  return out;
}

module.exports = { dedupe };
""".trimStart()

    @Test fun makeItFast() {
        val n = "make-it-fast"
        passes(n) { d -> d.put("src/contacts.js", fastDedupe) }
        fails(n, "emails of only spaces are now always kept") { d ->
            d.put("src/contacts.js", fastDedupe.replace("c.email ? c.email.trim().toLowerCase() : null", "c.email && c.email.trim() ? c.email.trim().toLowerCase() : null"))
        }
        fails(n, "it returns copies of the contacts") { d -> d.put("src/contacts.js", fastDedupe.replace("return out;", "return out.map(c => ({ ...c }));")) }
    }

    @Test fun pathTraversal() {
        val n = "path-traversal"
        val orig = "  const file = path.join(root, decodeURIComponent(urlPath));\n  if (!file.startsWith(root)) return null;\n"
        val decode = "  let decoded;\n  try { decoded = decodeURIComponent(urlPath); } catch (e) { return null; }\n"
        val safe = decode + "  const file = path.join(root, decoded);\n  if (file !== root && !file.startsWith(root + path.sep)) return null;\n"
        val crashOnly = decode + "  const file = path.join(root, decoded);\n  if (!file.startsWith(root)) return null;\n"
        passes(n) { d -> d.edit("src/static.js") { it.replace(orig, safe) } }
        fails(n, "only the crash is fixed") { d -> d.edit("src/static.js") { it.replace(orig, crashOnly) } }
        fails(n, "only a literal .. is blocked") { d -> d.edit("src/static.js") { it.replace(orig, "  if (urlPath.includes('..')) return null;\n$crashOnly") } }
        fails(n, "the backup is deleted instead") { d -> d.edit("src/static.js") { it.replace(orig, crashOnly) }; File(d, "public-backup/secrets.txt").delete() }
    }

    @Test fun traceTheError() {
        val n = "trace-the-error"
        passes(n, "deleteProject calls requirePermission(user, 'project:delete') in src/auth/permissions.js, which throws FORBIDDEN. " +
            "The guest sees: \"You don't have permission to do that. Ask a project owner.\"") {}
        passes(n, "`requirePermission` rejects it. Message: You don\u2019t have permission to do that. Ask a project owner.") {}
        fails(n, "it names the legacy module", "checkAcl in src/legacy/acl.js throws \"Access denied for guests\".")
        fails(n, "it trusts the old doc", "checkGuest() stops it, and the guest sees \"Guests can only view projects.\"")
        fails(n, "the function is right but the message is not", "requirePermission throws GUEST_READ_ONLY: \"Guests can only view projects.\"")
    }

    @Test fun pythonFlag() {
        val n = "python-flag"
        val flag = { argv: String -> { d: File ->
            d.edit("tools/stats.py") {
                it.replace("import csv\n", "import csv\nimport json\n").replace(
                    "    if len(argv) != 1:\n        print('usage: stats.py FILE.csv', file=sys.stderr)\n        return 2\n    s = summarize(read_amounts(argv[0]))\n",
                    argv + "    if len(argv) != 1:\n        print('usage: stats.py [--json] FILE.csv', file=sys.stderr)\n        return 2\n" +
                        "    s = summarize(read_amounts(argv[0]))\n    if as_json:\n        print(json.dumps(s))\n        return 0\n")
            }
        } }
        val anywhere = flag("    as_json = '--json' in argv\n    argv = [a for a in argv if a != '--json']\n")
        val test = { d: File ->
            d.edit("tests/test_stats.py") {
                it.replace("import os\n", "import json\nimport os\n").replace("\n\nif __name__", "\n    def test_json(self):\n" +
                    "        out = subprocess.run([sys.executable, os.path.join(ROOT, 'tools', 'stats.py'), '--json', os.path.join(ROOT, 'data', 'sales.csv')],\n" +
                    "                             capture_output=True, text=True, check=True).stdout\n" +
                    "        self.assertEqual(json.loads(out), {'count': 3, 'total': 34.75, 'mean': 11.58})\n\n\nif __name__")
            }
        }
        passes(n) { d -> anywhere(d); test(d) }
        fails(n, "the flag only works before the file") { d -> flag("    as_json = argv[:1] == ['--json']\n    argv = argv[1:] if as_json else argv\n")(d); test(d) }
        fails(n, "the plain output changed") { d -> anywhere(d); test(d); d.edit("tools/stats.py") { it.replace("'count: %d'", "'count: %d rows'") } }
        fails(n, "no test was added") { d -> anywhere(d) }
    }

    private val moneyTests = """
const test = require('node:test');
const assert = require('node:assert');
const { split, addPercent } = require('../src/money.js');

test('split', () => {
  assert.deepStrictEqual(split(10, 3), [4, 3, 3]);
  assert.deepStrictEqual(split(5, 3), [2, 2, 1]);
  assert.deepStrictEqual(split(0, 2), [0, 0]);
  assert.throws(() => split(10, 0), RangeError);
  assert.throws(() => split(10.5, 2), RangeError);
  assert.throws(() => split(-1, 2), RangeError);
});

test('addPercent', () => {
  assert.strictEqual(addPercent(1000, 8), 1080);
  assert.strictEqual(addPercent(105, 10), 116);
});
""".trimStart()

    @Test fun testsThatCatchBugs() {
        val n = "tests-that-catch-bugs"
        passes(n) { d -> d.put("test/money.test.js", moneyTests) }
        fails(n, "the tests only check the examples in the comments") { d ->
            d.put("test/money.test.js", moneyTests.lines().filterNot { "split(5, 3)" in it || "105, 10" in it || "10.5, 2" in it || "10, 0" in it }.joinToString("\n"))
        }
        fails(n, "money.js is changed") { d -> d.put("test/money.test.js", moneyTests); d.edit("src/money.js") { it.replace("Math.round(cents", "Math.round(+cents") } }
        fails(n, "the tests fail on the real code") { d -> d.put("test/money.test.js", moneyTests.replace("[4, 3, 3]", "[3, 3, 4]")) }
    }
}
