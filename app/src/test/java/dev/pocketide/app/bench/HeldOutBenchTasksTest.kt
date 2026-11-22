package dev.pocketide.app.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Proves the held-out checks measure the job: each fails on the start, passes a reference solution and rejects a shortcut. */
class HeldOutBenchTasksTest {
    companion object {
        @BeforeClass @JvmStatic fun tools() {
            assumeTrue("node is needed for the benchmark checks", try { ProcessBuilder("bash", "-c", "node --version").start().waitFor() == 0 } catch (e: Exception) { false })
        }
    }

    private fun run(name: String, change: (File) -> Unit): Pair<Int, String> {
        val t = (HeldOutBenchTasks.ALL + HardBenchTasks.ALL).single { it.name == name }  // settings-merge moved to the hard suite in 1.5.93
        val dir = Files.createTempDirectory("heldout").toFile()
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
    private fun fails(name: String, why: String, change: (File) -> Unit = {}) { val (c, o) = run(name, change); println("$name, $why: ${o.trim()}"); assertNotEquals("$name should fail when $why: $o", 0, c) }
    private fun File.put(p: String, t: String) = File(this, p).also { it.parentFile.mkdirs() }.writeText(t)
    private fun File.edit(p: String, f: (String) -> String) = File(this, p).let { it.writeText(f(it.readText())) }

    @Test fun everyTaskHasAUniqueName() {
        val names = HeldOutBenchTasks.ALL.map { it.name }
        assertEquals(names.size, names.toSet().size)
        val others = (BenchTasks.ALL + HardBenchTasks.ALL).map { it.name }.toSet()
        assertEquals(emptySet<String>(), names.toSet() intersect others)
    }

    @Test fun settingsMerge() {
        fails("settings-merge", "nothing was done")
        passes("settings-merge") { it.edit("src/config.js") { s -> s.replace("  return Object.assign({}, base, user);",
            "  const out = JSON.parse(JSON.stringify(base));\n  for (const [k, v] of Object.entries(user)) out[k] = v && typeof v === 'object' && !Array.isArray(v) && out[k] && typeof out[k] === 'object' && !Array.isArray(out[k]) ? merge(out[k], v) : v;\n  return out;") } }
        fails("settings-merge", "the merge changes the defaults") { it.edit("src/config.js") { s -> s.replace("  return Object.assign({}, base, user);",
            "  for (const [k, v] of Object.entries(user)) base[k] = v && typeof v === 'object' && !Array.isArray(v) && base[k] ? Object.assign(base[k], v) : v;\n  return base;") } }
        fails("settings-merge", "lists are joined instead of replaced") { it.edit("src/config.js") { s -> s.replace("  return Object.assign({}, base, user);",
            "  const out = JSON.parse(JSON.stringify(base));\n  for (const [k, v] of Object.entries(user)) out[k] = Array.isArray(v) ? (out[k] || []).concat(v) : v && typeof v === 'object' ? Object.assign({}, out[k], v) : v;\n  return out;") } }
    }

    @Test fun uniqueAddress() {
        fails("unique-address", "nothing was done")
        passes("unique-address") { it.edit("src/posts.js") { s -> s.replace("  const slug = slugify(title);",
            "  const base = slugify(title);\n  let slug = base, n = 1;\n  while (Object.prototype.hasOwnProperty.call(store, slug)) slug = base + '-' + (++n);") } }
        fails("unique-address", "a count is kept per title, so a title ending in a number collides") { it.edit("src/posts.js") { s -> s.replace("  const slug = slugify(title);",
            "  const base = slugify(title);\n  addPost.seen = addPost.seen || {};\n  const n = addPost.seen[base] = (addPost.seen[base] || 0) + 1;\n  const slug = n === 1 ? base : base + '-' + n;") } }
        fails("unique-address", "slugify is changed to add a random part") { it.edit("src/slug.js") { s -> s.replace(".replace(/^-+|-+$/g, '');", ".replace(/^-+|-+$/g, '') + '-' + Math.random().toString(36).slice(2, 6);") } }
    }

    @Test fun paging() {
        fails("paging", "nothing was done")
        passes("paging") { it.edit("src/pages.js") { s -> s.replace("start + size - 1", "start + size").replace("Math.floor(items.length / size)", "Math.ceil(items.length / size)") } }
        fails("paging", "only the page end is fixed") { it.edit("src/pages.js") { s -> s.replace("start + size - 1", "start + size") } }
        fails("paging", "everything is put on one page") { it.edit("src/pages.js") { s -> s.replace("return items.slice(start, start + size - 1);", "return n === 1 ? items.slice() : [];").replace("Math.floor(items.length / size)", "items.length ? 1 : 0") } }
    }

    @Test fun cliFlag() {
        fails("cli-flag", "nothing was done")
        passes("cli-flag") { it.edit("bin/todo.js") { s -> s.replace("else if (cmd === 'list') { list.forEach((t, i) => console.log(",
            "else if (cmd === 'list') { list.forEach((t, i) => (rest.includes('--done') && !t.done) || console.log(") } }
        fails("cli-flag", "the plain list output changed") { it.edit("bin/todo.js") { s -> s.replace("'. [' + (t.done ? 'x' : ' ') + '] '", "') ' + (t.done ? '✓ ' : '') ")
            .replace("else if (cmd === 'list') { list.forEach((t, i) => console.log(", "else if (cmd === 'list') { list.forEach((t, i) => (rest.includes('--done') && !t.done) || console.log(") } }
    }

    @Test fun orderDate() {
        fails("order-date", "nothing was done")
        passes("order-date") { it.edit("src/orders.js") { s -> s.replace("  const d = new Date(iso);\n  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());", "  return new Date(iso).toISOString().slice(0, 10);") } }
        fails("order-date", "the date is cut from the text, ignoring the offset") { it.edit("src/orders.js") { s -> s.replace("  const d = new Date(iso);\n  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());", "  return iso.slice(0, 10);") } }
    }

    @Test fun retryFetch() {
        fails("retry-fetch", "nothing was done")
        val good = """
const BASE = 'https://weather.example/api';

// The current weather for a city. fetchFn is fetch (passed in so it can be swapped in tests).
async function getWeather(city, fetchFn = fetch) {
  let last;
  for (let i = 0; i < 3; i++) {
    let res;
    try { res = await fetchFn(BASE + '/now?city=' + encodeURIComponent(city)); } catch (e) { last = e; continue; }
    if (res.ok) return res.json();
    last = new Error('weather service answered ' + res.status);
    if (res.status === 404) break;
  }
  throw last;
}

module.exports = { getWeather };
"""
        passes("retry-fetch") { it.put("src/weather.js", good) }
        fails("retry-fetch", "a 404 is retried too") { it.put("src/weather.js", good.replace("    if (res.status === 404) break;\n", "")) }
        fails("retry-fetch", "a thrown network error is not retried") { it.put("src/weather.js", good.replace("catch (e) { last = e; continue; }", "catch (e) { throw e; }")) }
        fails("retry-fetch", "it tries forever") { it.put("src/weather.js", good.replace("i < 3", "i < 9")) }
        fails("retry-fetch", "the card is changed to hide the error") { d -> d.put("src/weather.js", good); d.edit("src/card.js") { s -> s.replace("Weather unavailable", "") } }
    }

    @Test fun removeFeature() {
        fails("remove-feature", "nothing was done")
        val clean: (File) -> Unit = {
            File(it, "src/coupons.js").delete(); File(it, "test/coupons.test.js").delete()
            it.put("src/cart.js", "// The cart total.\nfunction total(items) {\n  let sum = 0;\n  for (const it of items) sum += it.price * it.qty;\n  return Math.round(sum * 100) / 100;\n}\n\nmodule.exports = { total };\n")
            it.put("src/routes.js", "const { total } = require('./cart.js');\nconst { money } = require('./format.js');\n\nconst routes = {\n  '/cart': body => ({ total: money(total(body.items || [])) }),\n};\n\nmodule.exports = { routes };\n")
            it.put("test/cart.test.js", "const test = require('node:test');\nconst assert = require('assert');\nconst { total } = require('../src/cart.js');\n\ntest('the cart adds up', () => { assert.strictEqual(total([{ price: 2, qty: 3 }, { price: 1.5, qty: 2 }]), 9); });\n")
        }
        passes("remove-feature", clean)
        fails("remove-feature", "only the file is deleted") { File(it, "src/coupons.js").delete() }
        fails("remove-feature", "the cart is removed too") { d -> clean(d); File(d, "src/cart.js").delete() }
        fails("remove-feature", "an untouched file is changed") { d -> clean(d); d.edit("src/format.js") { s -> s.replace("toFixed(2)", "toFixed(1)") } }
    }

    @Test fun csvExport() {
        fails("csv-export", "nothing was done")
        passes("csv-export") { it.put("src/export.js", """
function cell(v) {
  if (v === null || v === undefined) return '';
  const s = String(v);
  return /[",\r\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
}
function toCsv(rows) {
  if (!rows.length) return '';
  const cols = Object.keys(rows[0]);
  return [cols.join(','), ...rows.map(r => cols.map(c => cell(r[c])).join(','))].join('\r\n') + '\r\n';
}
module.exports = { toCsv };
""".trimStart()) }
        passes("csv-export") { it.put("src/export.js",
            "function cell(v) { if (v == null) return ''; const s = String(v); return /[\",\\r\\n]/.test(s) ? '\"' + s.replace(/\"/g, '\"\"') + '\"' : s; }\n" +
            "function toCsv(rows) { if (!rows.length) return ''; const c = Object.keys(rows[0]); return '\\uFEFF' + [c.join(','), ...rows.map(r => c.map(k => cell(r[k])).join(','))].join('\\r\\n') + '\\r\\n'; }\nmodule.exports = { toCsv };\n") }
        fails("csv-export", "values are joined without quoting") { it.put("src/export.js",
            "function toCsv(rows) { if (!rows.length) return ''; const c = Object.keys(rows[0]); return [c.join(','), ...rows.map(r => c.map(k => r[k] ?? '').join(','))].join('\\n'); }\nmodule.exports = { toCsv };\n") }
    }
}
