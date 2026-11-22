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

/**
 * Proves the exam tasks (1.5.166): each fails as delivered (or, for "already works", passes as delivered and fails when the code is
 * changed), passes a reference fix, and fails the wrong fix its kind invites: the invented feature, the "not found" report on a thing
 * that partly exists, a needless change, a reader that fits the sample only. Fingerprints frozen as for the sealed suite.
 */
class ExamBenchTasksTest {
    companion object {
        @BeforeClass @JvmStatic fun tools() {
            assumeTrue("node is needed for the benchmark checks", try { ProcessBuilder("bash", "-c", "node --version").start().waitFor() == 0 } catch (e: Exception) { false })
        }

        /** Written once, when the suites were made (1.5.166). A changed task is a new task. */
        val FROZEN = mapOf(
            "exam-cinema" to "abc202869f8928821fe3af654787391c0ff8981fb502890300005dbf9ec53333",
            "exam-bikes" to "69e1735e07004259ca5fbef95f3f4b8ed0a9d9c579e959f732101ebf58349752",
            "exam-choir" to "1b67fb5d4e9493eb480d35090987dee34f41563c687c39b1e92d21b078eec7e3",
            "exam-cards" to "2797b5655a4d62bc8489a90b63bd606b9fed932768e22338eb5ba55ed4f5d684",
            "exam-invoices" to "587c08bd830a218921da2b17d11c3600eb9549cca724f5b64c99cda423ce2054",
            "exam-mileage" to "6ddc1cc3654bb5bbc4d54c57c081bba367bb8d0a5c11c92cb1b54f48daa23af8",
            "exam-trails" to "edd6dc9db9af7955cc1ce04de14d84046de7f2d59d092b4017a6e9b546a1c294",
            "exam-bakery" to "c1ebf833415ff6120a3cdda96974edc94f19ba4272d1206f6572ec80baa3884b",
            "exam-seeds" to "1816817072d4227fb01a16f3adf83ca72c40fec892dcae20adc46031382a1489",
            "exam-loyalty" to "4ae09739b6d199f547eac3c294bc55910007f3585b04b93f30e9da8579169adb",
            "exam-playlist" to "8d700b91c253da869ce20740783412a3f61b733e2d45d009c7165d0dea7fec84",
            "exam-thermostat" to "551cb3193749101cf49269d910b359a4c834a2d0bd7677988ae0bad5899ab660",
            "exam-orders" to "97f7273aeca349c3b86fe4538774bdfcbe4b214a3fded939b0275301dcc593fd",
            "exam-weather" to "c07ece74d146f3bec917f15436fbf29c6c133a8eb9b7b53897295f95160c6179",
            "exam-songs" to "2c74144d6db91a41614439606237e152730ecda6089967889ebf84105d9d6462",
            "exam-diary" to "2e7f8cd0b55035be70dc8b882829165ab1525e2ffc60eceb1862af1bdc02bf45",
            "exam-till" to "ec68fda91e32aba62f71598658deef1e46f4fee93b586e23ed84017bb783c6f9",
            "exam-ingredients" to "6508a9af0919beaf5a5ec10a79ea866b7147026aaaef89b475451961eef653da",
        )
    }

    private fun run(name: String, change: (File) -> Unit): Pair<Int, String> {
        val t = ExamBenchTasks.ALL.single { it.name == name }
        val dir = Files.createTempDirectory("exam").toFile()
        try {
            for ((p, text) in t.files) File(dir, p).also { it.parentFile.mkdirs() }.writeText(text)
            File(dir, ".bench").mkdirs(); File(dir, ".bench/answer.txt").writeText("")
            change(dir)
            val pr = ProcessBuilder("bash", "-c", t.check).directory(dir).redirectErrorStream(true).start()
            val out = pr.inputStream.bufferedReader().readText()
            if (!pr.waitFor(90, TimeUnit.SECONDS)) { pr.destroyForcibly(); return 124 to "timed out" }
            return pr.exitValue() to out
        } finally { dir.deleteRecursively() }
    }
    private fun passes(name: String, change: (File) -> Unit = {}) { val (c, o) = run(name, change); assertEquals("$name should pass: $o", 0, c) }
    private fun fails(name: String, why: String, change: (File) -> Unit = {}) { val (c, o) = run(name, change); println("$name, $why: ${o.trim().lines().lastOrNull { it.contains("FAIL") }?.take(200)}"); assertNotEquals("$name should fail when $why", 0, c) }
    private fun File.edit(p: String, f: (String) -> String) = File(this, p).let { val b = it.readText(); val a = f(b); require(a != b) { "edit of $p matched nothing" }; it.writeText(a) }
    private fun File.put(p: String, text: String) = File(this, p).also { it.parentFile.mkdirs() }.writeText(text)

    @Test fun theSuitesAreSealedOff() {
        assertEquals(12, ExamBenchTasks.PREMISE.size); assertEquals(6, ExamBenchTasks.VARIETY.size)
        assertEquals(ExamBenchTasks.PREMISE.map { it.name }, BenchTasks.select("premise", emptySet()).map { it.name })
        assertEquals(ExamBenchTasks.VARIETY.map { it.name }, BenchTasks.select("variety", emptySet()).map { it.name })
        assertTrue(BenchTasks.select("all", emptySet()).none { it.name.startsWith("exam-") })
        assertTrue(runCatching { BenchTasks.select("sealed", setOf("exam-bikes")) }.isFailure)
        assertEquals(mapOf("nothing" to 6, "partly" to 3, "works" to 3, "variety" to 6), ExamBenchTasks.KIND.values.groupingBy { it }.eachCount())
        for (t in ExamBenchTasks.ALL) assertTrue("${t.name}: the check must be ASCII", t.check.all { it.code in 1..126 })
        // no task reuses a sealed project's domain words in its request
        for (t in ExamBenchTasks.ALL) for (w in listOf("metric", "favourite", "timer", "harbour", "tavern")) assertTrue("${t.name} says $w", !t.request.lowercase().contains(w))
    }

    @Test fun theTasksAreFrozen() {
        val now = ExamBenchTasks.ALL.associate { it.name to SealedBenchTasks.fingerprint(it) }
        for ((n, h) in now) println("FINGERPRINT $n $h")
        assertEquals(FROZEN, now)
    }

    @Test fun eachProjectsOwnTestsPassAsDelivered() {
        for (t in ExamBenchTasks.ALL) {
            val dir = Files.createTempDirectory("exam-own").toFile()
            try {
                for ((p, text) in t.files) File(dir, p).also { it.parentFile.mkdirs() }.writeText(text)
                val pr = ProcessBuilder("bash", "-c", "node --test --test-reporter=dot").directory(dir).redirectErrorStream(true).start()
                val out = pr.inputStream.bufferedReader().readText(); pr.waitFor(90, TimeUnit.SECONDS)
                assertEquals(t.name + ": " + out, 0, pr.exitValue())
            } finally { dir.deleteRecursively() }
        }
    }

    // ---- nothing existed: fix the fault, invent nothing

    @Test fun nothingExisted() {
        val fixes = mapOf<String, (File) -> Unit>(
            "exam-cinema" to { d -> d.edit("lib/seats.js") { it.replace("n >= PER_ROW", "n > PER_ROW") } },
            "exam-bikes" to { d -> d.edit("lib/price.js") { it.replace("Math.floor(minutes / 60) || 1", "Math.ceil(minutes / 60)") } },
            "exam-choir" to { d -> d.edit("lib/rota.js") { it.replace("n % (singers.length - 1 || 1)", "n % singers.length") } },
            "exam-cards" to { d -> d.edit("lib/round.js") { it.replace("    const out = [];\n    for (let i = 0; i < deck.length; i++) out.push(deck[Math.floor(r() * deck.length)]);\n    return out;",
                "    const out = deck.slice();\n    for (let i = out.length - 1; i > 0; i--) { const j = Math.floor(r() * (i + 1)); const t = out[i]; out[i] = out[j]; out[j] = t; }\n    return out;") } },
            "exam-invoices" to { d -> d.edit("lib/lines.js") { it.replace("    if (line.discount) n = n * (1 + VAT);\n", "") } },
            "exam-mileage" to { d -> d.edit("lib/mpg.js") { it.replace("return fills.reduce((s, f) => s + of(f), 0) / fills.length;",
                "const g = fills.reduce((s, f) => s + f.gallons, 0);\n    return g > 0 ? fills.reduce((s, f) => s + f.miles, 0) / g : 0;") } },
        )
        val invented = mapOf("exam-cinema" to ("lib/pick.js" to "// pick for me: the best free seats\n"), "exam-bikes" to ("lib/student.js" to "// student discount\n"),
            "exam-choir" to ("ui/swap.js" to "// swap button\n"), "exam-cards" to ("lib/theme.js" to "// dark mode switch\n"),
            "exam-invoices" to ("lib/pdf.js" to "// download as PDF\n"), "exam-mileage" to ("ui/chart.js" to "// fuel price chart\n"))
        for ((name, fix) in fixes) {
            fails(name, "nothing was done")
            passes(name, fix)
            val (path, text) = invented.getValue(name)
            fails(name, "the claimed feature was built") { fix(it); it.put(path, text) }
        }
    }

    // ---- partly exists: fix it; a "not there" report fails

    @Test fun partlyExists() {
        fails("exam-trails", "nothing was done (a 'not found' report)")
        passes("exam-trails") { d -> d.edit("style.css") { it.replace(".filters .easy { display: none; }\n", "") }; d.edit("main.js") { it.replace("getElementById('easy')", "getElementById('easy-only')") } }
        fails("exam-trails", "only the CSS was fixed") { d -> d.edit("style.css") { it.replace(".filters .easy { display: none; }\n", "") } }
        fails("exam-bakery", "nothing was done")
        passes("exam-bakery") { d -> d.edit("index.html") { it.replace("<label hidden>", "<label>") }; d.edit("lib/order.js") { it.replace("return o.cake + ' (' + o.size + ') for ' + o.day;", "return o.cake + ' (' + o.size + ') for ' + o.day + (o.note ? ' \\u2014 ' + o.note : '');") } }
        fails("exam-bakery", "the box was shown but the note still dropped") { d -> d.edit("index.html") { it.replace("<label hidden>", "<label>") } }
        fails("exam-seeds", "nothing was done")
        passes("exam-seeds") { d -> d.edit("lib/tabs.js") { it.replace("    { id: 'notes', label: 'Notes' },\n", "    { id: 'notes', label: 'Notes' },\n    { id: 'calendar', label: 'Sowing calendar' },\n") } }
    }

    // ---- already works: change no code

    @Test fun alreadyWorks() {
        for (name in listOf("exam-loyalty", "exam-playlist", "exam-thermostat")) {
            passes(name)
            passes(name) { it.put("test/extra.test.js", "const test = require('node:test');\ntest('still works', () => {});\n") }
        }
        fails("exam-loyalty", "the code was changed anyway") { d -> d.edit("lib/card.js") { it.replace("card.stamps += 1;", "card.stamps = card.stamps + 1;") } }
        fails("exam-playlist", "a file was added") { d -> d.put("lib/noRepeat.js", "module.exports = {};\n") }
        fails("exam-thermostat", "the settings were changed") { d -> d.edit("lib/settings.js") { it.replace("{ unit: 'C' }, JSON.parse", "{ unit: 'C' }, { unit: 'C' }, JSON.parse") } }
    }

    // ---- variety: a careful reader passes; a sample-only reader fails

    private val csvReader = """
  function cells(text, sep) {
    const rows = []; let row = [], cell = '', q = false;
    for (let i = 0; i < text.length; i++) {
      const c = text[i];
      if (q) { if (c === '"') { if (text[i + 1] === '"') { cell += '"'; i++; } else q = false; } else cell += c; }
      else if (c === '"') q = true;
      else if (c === sep) { row.push(cell); cell = ''; }
      else if (c === '\n' || c === '\r') { if (c === '\r' && text[i + 1] === '\n') i++; row.push(cell); rows.push(row); row = []; cell = ''; }
      else cell += c;
    }
    if (cell || row.length) { row.push(cell); rows.push(row); }
    return rows;
  }
  function readOrders(text) {
    const lines = text.replace(/^\ufeff/, '').split(/\r?\n/).filter(l => !l.trim().startsWith('#'));
    const body = lines.join('\n');
    const sep = (lines.find(l => l.trim()) || '').includes(';') ? ';' : ',';
    const [head, ...rows] = cells(body, sep).filter(r => r.some(x => x.trim()));
    const col = {}; head.forEach((h, i) => { col[h.trim().toLowerCase()] = i; });
    const num = s => Number(String(s).trim().replace(/\s/g, '').replace(sep === ';' ? ',' : /^$/, '.'));
    return rows.map(r => ({ order: Number(r[col.order]), customer: r[col.customer].trim(), item: r[col.item].trim(), qty: Number(r[col.qty]),
      price: num(r[col.price]), note: col.note === undefined ? '' : (r[col.note] || '').trim() }));
  }
"""

    @Test fun variety() {
        fun stub(d: File, module: String, fn: String, body: String) = d.edit(module) { it.replace("  function $fn(text) {\n    return [];\n  }", body.trimEnd()) }
        fails("exam-orders", "nothing was done")
        passes("exam-orders") { stub(it, "lib/readOrders.js", "readOrders", csvReader) }
        fails("exam-orders", "the reader fits the sample") { stub(it, "lib/readOrders.js", "readOrders", """
  function readOrders(text) {
    return text.trim().split('\n').slice(1).map(l => { const c = l.split(','); return { order: Number(c[0]), customer: c[1], item: c[2], qty: Number(c[3]), price: Number(c[4]), note: c[5] || '' }; });
  }""") }

        fails("exam-weather", "nothing was done")
        passes("exam-weather") { stub(it, "lib/readReadings.js", "readReadings", """
  function readReadings(text) {
    const t = text.trim();
    let list;
    try { const v = JSON.parse(t); list = Array.isArray(v) ? v : (v.data || v.readings || []); }
    catch (e) { list = t.split(/\r?\n/).filter(l => l.trim()).map(l => JSON.parse(l)); }
    return list.map(r => ({ time: String(r.time), temp: Number(r.temp), wind: Number(r.wind), rain: r.rain === undefined || r.rain === null ? 0 : Number(r.rain) }));
  }""") }
        fails("exam-weather", "the reader fits the sample") { stub(it, "lib/readReadings.js", "readReadings", "  function readReadings(text) {\n    return JSON.parse(text);\n  }") }

        fails("exam-songs", "nothing was done")
        passes("exam-songs") { stub(it, "lib/readSongs.js", "readSongs", """
  function readSongs(text) {
    const split = l => { const out = []; let cell = ''; for (let i = 0; i < l.length; i++) { if (l[i] === '\\' && l[i + 1] === '|') { cell += '|'; i++; } else if (l[i] === '|') { out.push(cell); cell = ''; } else cell += l[i]; } out.push(cell); return out; };
    const lines = text.split(/\r?\n/);
    const sepAt = lines.findIndex(l => /^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*$/.test(l));
    if (sepAt < 1) return [];
    const trimEdge = l => { let s = l.trim(); if (s.startsWith('|')) s = s.slice(1); if (s.endsWith('|') && !s.endsWith('\\|')) s = s.slice(0, -1); return s; };
    const head = split(trimEdge(lines[sepAt - 1])).map(h => h.trim().toLowerCase());
    const out = [];
    for (const l of lines.slice(sepAt + 1)) {
      if (!l.includes('|')) break;
      const c = split(trimEdge(l)).map(x => x.trim());
      out.push({ title: c[head.indexOf('title')], composer: c[head.indexOf('composer')], voices: Number(c[head.indexOf('voices')]) });
    }
    return out;
  }""") }
        fails("exam-songs", "the reader fits the sample") { stub(it, "lib/readSongs.js", "readSongs", """
  function readSongs(text) {
    return text.trim().split('\n').slice(2).map(l => { const c = l.split('|').map(x => x.trim()); return { title: c[1], composer: c[2], voices: Number(c[3]) }; });
  }""") }

        fails("exam-diary", "nothing was done")
        passes("exam-diary") { stub(it, "lib/readEntries.js", "readEntries", """
  const MONTHS = ['jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec'];
  const pad = n => String(n).padStart(2, '0');
  function iso(s) {
    let m;
    if ((m = s.match(/^(\d{4})-(\d\d)-(\d\d)/))) return m[1] + '-' + m[2] + '-' + m[3];
    if ((m = s.match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})$/))) return m[3] + '-' + pad(m[2]) + '-' + pad(m[1]);
    if ((m = s.match(/^(\d{1,2})\s+([A-Za-z]+)\s+(\d{4})$/))) return m[3] + '-' + pad(MONTHS.indexOf(m[2].slice(0, 3).toLowerCase()) + 1) + '-' + pad(m[1]);
    if ((m = s.match(/^([A-Za-z]+)\s+(\d{1,2}),?\s+(\d{4})$/))) return m[3] + '-' + pad(MONTHS.indexOf(m[1].slice(0, 3).toLowerCase()) + 1) + '-' + pad(m[2]);
    return s;
  }
  function readEntries(text) {
    return text.split(/\r?\n/).filter(l => l.includes('|')).map(l => { const i = l.indexOf('|'); return { date: iso(l.slice(0, i).trim()), text: l.slice(i + 1).trim() }; });
  }""") }
        fails("exam-diary", "the reader fits the sample") { stub(it, "lib/readEntries.js", "readEntries", """
  function readEntries(text) {
    return text.trim().split('\n').map(l => ({ date: l.slice(0, 10), text: l.slice(13) }));
  }""") }

        fails("exam-till", "nothing was done")
        passes("exam-till") { stub(it, "lib/readReport.js", "readReport", """
  function readReport(text) {
    const lines = text.split(/\r?\n/);
    const h = lines.findIndex(l => /^\s*ITEM\s+QTY\s+AMOUNT\s*$/i.test(l));
    if (h < 0) return [];
    const out = [];
    for (const l of lines.slice(h + 1)) {
      if (!l.trim() || /^[-=\s]+$/.test(l) || /^\s*total\b/i.test(l)) continue;
      const m = l.match(/^(.*?)\s+(\d+)\s+([\d,]+\.\d\d)\s*$/);
      if (m) out.push({ item: m[1].trim(), qty: Number(m[2]), amount: Number(m[3].replace(/,/g, '')) });
    }
    return out;
  }""") }
        fails("exam-till", "the reader fits the sample") { stub(it, "lib/readReport.js", "readReport", """
  function readReport(text) {
    return text.trim().split('\n').slice(1).map(l => { const c = l.trim().split(/\s{2,}/); return { item: c[0], qty: Number(c[1]), amount: Number(c[2]) }; });
  }""") }

        fails("exam-ingredients", "nothing was done")
        passes("exam-ingredients") { stub(it, "lib/readIngredients.js", "readIngredients", """
  const UNITS = ['g', 'kg', 'ml', 'l', 'cup', 'cups', 'tsp', 'tbsp', 'oz', 'lb'];
  const FR = { '\u00bd': 0.5, '\u00bc': 0.25, '\u00be': 0.75, '\u2153': 1 / 3, '\u2154': 2 / 3 };
  function qty(s) {
    s = s.trim();
    let m;
    if (FR[s] !== undefined) return FR[s];
    if ((m = s.match(/^(\d+)\s+(\d+)\/(\d+)$/))) return Number(m[1]) + Number(m[2]) / Number(m[3]);
    if ((m = s.match(/^(\d+)\/(\d+)$/))) return Number(m[1]) / Number(m[2]);
    return Number(s.replace(',', '.'));
  }
  function readIngredients(text) {
    return text.split(/\r?\n/).filter(l => l.trim()).map(l => {
      const m = l.trim().match(/^((?:\d+\s+\d+\/\d+)|(?:\d+\/\d+)|(?:\d+(?:[.,]\d+)?)|[\u00bd\u00bc\u00be\u2153\u2154])\s*(.*)$/);
      if (!m) return { qty: null, unit: null, name: l.trim() };
      const rest = m[2].trim(); const w = rest.split(/\s+/);
      const unit = UNITS.includes(w[0].toLowerCase()) ? w[0].toLowerCase() : null;
      return { qty: qty(m[1]), unit, name: (unit ? w.slice(1) : w).join(' ') };
    });
  }""") }
        fails("exam-ingredients", "the reader fits the sample") { stub(it, "lib/readIngredients.js", "readIngredients", """
  function readIngredients(text) {
    return text.trim().split('\n').map(l => { const w = l.split(' '); return w.length === 3 ? { qty: Number(w[0]), unit: w[1], name: w[2] } : { qty: Number(w[0]), unit: null, name: w.slice(1).join(' ') }; });
  }""") }
    }
}
