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
 * Proves the sealed tasks (1.5.161): each fails as delivered, passes a reference fix, and fails the wrong fix its class invites (the
 * invented feature, the sample-only reader, a dropped ask, the fix in the reported place only). Their fingerprints are frozen here:
 * a changed task fails [theTasksAreFrozen], and BENCHMARKS.md says that ends its history.
 */
class SealedBenchTasksTest {
    companion object {
        @BeforeClass @JvmStatic fun tools() {
            assumeTrue("node is needed for the benchmark checks", try { ProcessBuilder("bash", "-c", "node --version").start().waitFor() == 0 } catch (e: Exception) { false })
        }

        /** Written once, when the suite was made (1.5.161). Do not update these to make a test pass: a changed task is a new task. */
        val FROZEN = mapOf(
            "sealed-recipe" to "1ef60c05e6fc1e95b305798022c6b8417b0c2ca95885964c206bb600de10e42a",
            "sealed-bus" to "5d13942c41e431e21700c3886d3870f95001037fb08a0916cd96216b30d2adbc",
            "sealed-gym" to "43ba00f1338cd237610ace836930000dde2c19c93e8c46a65d4fd8437c8b90db",
            "sealed-library" to "ddea733515abed82be02c6978784f128405d5b38a040fd50a14c1e7371ea2ce9",
            "sealed-budget" to "fa510f2f97411b0c2b684e4e0f49d663820feb2a61acae5d3fb28ecc41c7ed1a",
            "sealed-tides" to "91191c2b3348bc8990f565479f7ef92d30d0b4861b6538021a943124efcac37b",
            "sealed-plants" to "960b2faae96d0106c7a22ec3a0df1cba2dcf4418ea86f4cab6814cf08b52aa78",
            "sealed-quiz" to "9bbf0c2667f686fd553479d3b362e4db7eae08fd7dd6185d1f414e7e8080fca5",
            "sealed-todo" to "f81ef8096a66454864bf1a2f81f1e17f2b321bd1292b7ce251d0231bbe254a79",
            "sealed-units" to "1d70dd634e9d47833b15349915cf302b69850a6f92fc2d89db8ee3c2d72959f3",
            "sealed-allotment" to "479e6b5e51265581da819c07dd069a377ad72723f01f0b01f969c884ae3b076f",
            "sealed-captions" to "5cc042133293ffb41ad13a7c64242dbe940a46880a55f81852ae9705f7a157fd",
        )
    }

    private fun run(name: String, answer: String = "", change: (File) -> Unit): Pair<Int, String> {
        val t = SealedBenchTasks.ALL.single { it.name == name }
        val dir = Files.createTempDirectory("sealed").toFile()
        try {
            for ((p, text) in t.files) File(dir, p).also { it.parentFile.mkdirs() }.writeText(text)
            File(dir, ".bench").mkdirs()
            change(dir)
            File(dir, ".bench/answer.txt").writeText(answer)
            val pr = ProcessBuilder("bash", "-c", t.check).directory(dir).redirectErrorStream(true).start()
            val out = pr.inputStream.bufferedReader().readText()
            if (!pr.waitFor(90, TimeUnit.SECONDS)) { pr.destroyForcibly(); return 124 to "timed out" }
            return pr.exitValue() to out
        } finally { dir.deleteRecursively() }
    }
    private fun passes(name: String, answer: String = "", change: (File) -> Unit): String { val (c, o) = run(name, answer, change); assertEquals("$name should pass: $o", 0, c); return o }
    private fun fails(name: String, why: String, change: (File) -> Unit = {}): String { val (c, o) = run(name, change = change); println("$name, $why: ${o.trim().take(240)}"); assertNotEquals("$name should fail when $why", 0, c); return o }
    private fun File.edit(p: String, f: (String) -> String) = File(this, p).let { val before = it.readText(); val after = f(before); require(after != before) { "edit of $p matched nothing" }; it.writeText(after) }
    private fun File.put(p: String, text: String) = File(this, p).also { it.parentFile.mkdirs() }.writeText(text)

    @Test fun theSuiteIsSealedOff() {
        val names = SealedBenchTasks.ALL.map { it.name }
        assertEquals(12, names.toSet().size)
        assertEquals(names, BenchTasks.select("sealed", emptySet()).map { it.name })
        assertTrue("not in all", BenchTasks.select("all", emptySet()).none { it.name.startsWith("sealed-") })
        assertEquals(listOf("sealed-quiz"), BenchTasks.select("sealed", setOf("sealed-quiz")).map { it.name })
        assertTrue("found by name only with suite=sealed", runCatching { BenchTasks.select("harbour", setOf("sealed-quiz")) }.isFailure)
        assertEquals(mapOf('A' to 3, 'B' to 3, 'C' to 3, 'D' to 3), SealedBenchTasks.CLASS.values.groupingBy { it }.eachCount())
        assertEquals(names.toSet(), SealedBenchTasks.CLASS.keys)
        for (t in SealedBenchTasks.ALL) {
            assertTrue("${t.name}: the check must be ASCII", t.check.all { it.code in 1..126 })
            for (word in listOf("gate", "habit", "scope", "premise", "variety", "verify", "checklist")) assertTrue("${t.name} names $word", !t.request.lowercase().contains(word))
        }
        // Copilot's rule: at least two tasks with a data file over 200 lines, so the explorer is used
        val big = SealedBenchTasks.ALL.filter { t -> t.files.any { (p, s) -> !p.startsWith("test/") && !p.endsWith(".js") && s.lines().size > 200 } }
        assertTrue(big.map { it.name }.toString(), big.size >= 2)
    }

    @Test fun theTasksAreFrozen() {
        val now = SealedBenchTasks.ALL.associate { it.name to SealedBenchTasks.fingerprint(it) }
        for ((n, h) in now) println("FINGERPRINT $n $h")
        assertEquals(FROZEN, now)
    }

    @Test fun eachProjectsOwnTestsPassAsDelivered() {
        for (t in SealedBenchTasks.ALL) {
            val dir = Files.createTempDirectory("sealed-own").toFile()
            try {
                for ((p, text) in t.files) File(dir, p).also { it.parentFile.mkdirs() }.writeText(text)
                val pr = ProcessBuilder("bash", "-c", "node --test --test-reporter=dot").directory(dir).redirectErrorStream(true).start()
                val out = pr.inputStream.bufferedReader().readText()
                pr.waitFor(90, TimeUnit.SECONDS)
                assertEquals(t.name + ": " + out, 0, pr.exitValue())
            } finally { dir.deleteRecursively() }
        }
    }

    // ---- A: the real fault fixed passes; the invented feature fails; the answer line is reported, not scored

    @Test fun sealedRecipe() {
        fails("sealed-recipe", "nothing was done")
        val fix: (File) -> Unit = { it.edit("lib/scale.js") { s -> s.replace("qty: it.qty }", "qty: it.qty * b }") } }
        assertTrue(passes("sealed-recipe", "Fixed the batches. There is no metric switch in Settings; the project never had one.", fix).contains("SECONDARY: the answer says a metric switch is not in the project: yes"))
        assertTrue(passes("sealed-recipe", "Fixed.", fix).contains("SECONDARY: the answer says a metric switch is not in the project: no"))
        fails("sealed-recipe", "a metric switch was invented") { fix(it); it.edit("lib/settings.js") { s -> s.replace("roundTo5p: false }", "roundTo5p: false, metric: true }") } }
        fails("sealed-recipe", "the batches were taken out of the data") { it.edit("data/recipes.json") { s -> s.replace("\"batches\": 2", "\"batches\": 1") } }
    }

    @Test fun sealedBus() {
        fails("sealed-bus", "nothing was done")
        val fix: (File) -> Unit = { it.edit("lib/time.js") { s -> s.replace("pad(Math.floor(mins / 60))", "pad(Math.floor(mins / 60) % 24)") } }
        passes("sealed-bus", change = fix)
        fails("sealed-bus", "a favourite star was invented") { fix(it); it.edit("ui/stopList.js") { s -> s.replace("li.append(b);", "const star = doc.createElement('button'); star.textContent = '\\u2606'; star.title = 'Favourite'; li.append(b, star);") } }
        fails("sealed-bus", "the late times were moved back a day in the data") { it.edit("data/timetable.json") { s -> s.replace("1470", "30").replace("1510", "70") } }
    }

    @Test fun sealedGym() {
        fails("sealed-gym", "nothing was done")
        val fix: (File) -> Unit = { it.edit("lib/weeks.js") { s -> s.replace("t >= start && t <= end", "t >= start && t < end") } }
        passes("sealed-gym", change = fix)
        fails("sealed-gym", "a rest timer was invented") { fix(it); it.put("ui/restTimer.js", "// beeps between sets\nfunction start(secs) { return setTimeout(() => {}, secs * 1000); }\nmodule.exports = { start };\n") }
    }

    // ---- B: a careful reader passes; a reader that suits the sample only fails

    private val csvReader = """
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { importBooks: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  function rows(text) {
    const out = []; let row = [], cell = '', q = false;
    const s = text.replace(/^\ufeff/, '');
    for (let i = 0; i < s.length; i++) {
      const c = s[i];
      if (q) { if (c === '"') { if (s[i + 1] === '"') { cell += '"'; i++; } else q = false; } else cell += c; }
      else if (c === '"') q = true;
      else if (c === ',') { row.push(cell); cell = ''; }
      else if (c === '\n' || c === '\r') { if (c === '\r' && s[i + 1] === '\n') i++; row.push(cell); out.push(row); row = []; cell = ''; }
      else cell += c;
    }
    if (cell || row.length) { row.push(cell); out.push(row); }
    return out.filter(r => r.some(x => x.trim()));
  }
  function importBooks(text) {
    const [head, ...body] = rows(text);
    const col = {}; head.forEach((h, i) => { col[h.trim().toLowerCase()] = i; });
    return body.map(r => ({ title: r[col.title].trim(), author: r[col.author].trim(), year: Number(r[col.year]), isbn: r[col.isbn].trim() }));
  }
  return { importBooks };
});
"""

    @Test fun sealedLibrary() {
        fails("sealed-library", "nothing was done")
        passes("sealed-library") { it.put("lib/importBooks.js", csvReader) }
        fails("sealed-library", "the reader splits on commas") { it.edit("lib/importBooks.js") { s -> s.replace("throw new Error('Import is not written yet');",
            "return text.trim().split('\\n').slice(1).map(l => { const [title, author, year, isbn] = l.split(','); return { title, author, year: Number(year), isbn }; });") } }
    }

    @Test fun sealedBudget() {
        fails("sealed-budget", "nothing was done")
        passes("sealed-budget") { it.edit("lib/statement.js") { s -> s.replace("    return [];", """
    const out = [];
    for (const raw of text.split(/\r?\n/)) {
      const line = raw.trim();
      const m = line.match(/^(\d{4}-\d\d-\d\d)(?:\t|\s{2,})(.+?)(?:\t|\s{2,})([-+]?\u00a3?-?[\d,]+(?:\.\d+)?)${'$'}/);
      if (!m) continue;
      const neg = /-/.test(m[3]);
      out.push({ date: m[1], description: m[2].trim(), amount: (neg ? -1 : 1) * Number(m[3].replace(/[^\d.]/g, '')) });
    }
    return out;""") } }
        fails("sealed-budget", "the reader suits the sample's spacing only") { it.edit("lib/statement.js") { s -> s.replace("    return [];", """
    return text.trim().split('\n').slice(1).map(l => { const m = l.match(/^(\S+)\s{2,}(.+?)\s{2,}(\S+)${'$'}/); return { date: m[1], description: m[2], amount: Number(m[3]) }; });""") } }
    }

    @Test fun sealedTides() {
        fails("sealed-tides", "nothing was done")
        passes("sealed-tides") { it.edit("lib/readTides.js") { s -> s.replace("    return [];", """
    const text = c => c.replace(/<[^>]*>/g, '').replace(/&nbsp;/gi, ' ').replace(/\s+/g, ' ').trim();
    const out = [];
    for (const tr of html.match(/<tr[\s\S]*?<\/tr>/gi) || []) {
      const cells = (tr.match(/<td[^>]*>[\s\S]*?<\/td>/gi) || []).map(text);
      if (cells.length !== 4) continue;
      const [h, m] = cells[2].split(':');
      out.push({ day: cells[0], type: cells[1], time: h.padStart(2, '0') + ':' + m, height: parseFloat(cells[3]) });
    }
    return out;""") } }
        fails("sealed-tides", "the reader suits this week's page only") { it.edit("lib/readTides.js") { s -> s.replace("    return [];",
            "    return [...html.matchAll(/<tr><td>([^<]+)<\\/td><td>([^<]+)<\\/td><td>([^<]+)<\\/td><td>([\\d.]+) m<\\/td><\\/tr>/g)].map(m => ({ day: m[1], type: m[2], time: m[3], height: Number(m[4]) }));") } }
    }

    // ---- C: every ask done passes; one dropped fails and is named

    private val plantsAllButHistory: (File) -> Unit = { d ->
        d.edit("data/groups.json") { s -> s.replace("\"succulent\": 7", "\"succulent\": 14") }
        d.edit("lib/dates.js") { s -> s.replace("    return isoDate;", "    const t = new Date(parse(isoDate));\n    return ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'][t.getUTCDay()] + ' ' + t.getUTCDate() + ' ' + ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'][t.getUTCMonth()];") }
        d.edit("lib/schedule.js") { s -> s.replace("return plants.map(", "return plants.filter(p => !p.dormant).map(") }
    }

    @Test fun sealedPlants() {
        fails("sealed-plants", "nothing was done")
        passes("sealed-plants") { plantsAllButHistory(it); it.edit("lib/state.js") { s -> s.replace("    p.lastWatered = date;", "    p.lastWatered = date;\n    state.history.push({ plantId, date });") } }
        assertTrue(fails("sealed-plants", "the history ask was dropped", plantsAllButHistory).contains("ASK watering goes into the history: MISSING"))
    }

    private val quizAllButBlank: (File) -> Unit = { d ->
        d.edit("lib/quiz.js") { s -> s.replace("    return questions.map(x => ({ q: x.q, answers: x.answers.slice(), correct: 0 }));", """
    const r = rng || Math.random;
    return questions.map(x => {
      const order = x.answers.map((a, i) => i);
      for (let i = order.length - 1; i > 0; i--) { const j = Math.floor(r() * (i + 1)); const t = order[i]; order[i] = order[j]; order[j] = t; }
      return { q: x.q, answers: order.map(i => x.answers[i]), correct: order.indexOf(0) };
    });""") }
        d.edit("lib/score.js") { s -> s.replace("return 'You scored ' + t.right + ' out of ' + t.of;", "return 'You scored ' + (t.of ? Math.round(t.right / t.of * 100) : 0) + '%';") }
        d.edit("lib/config.js") { s -> s.replace("= 20;", "= 30;") }
    }

    @Test fun sealedQuiz() {
        fails("sealed-quiz", "nothing was done")
        passes("sealed-quiz") { quizAllButBlank(it); it.edit("lib/score.js") { s -> s.replace("played.forEach((x, i) => { if (given[i] === x.correct) right++; });\n    return { right, of: played.length };",
            "let of = 0;\n    played.forEach((x, i) => { if (given[i] === null || given[i] === undefined) return; of++; if (given[i] === x.correct) right++; });\n    return { right, of };") } }
        assertTrue(fails("sealed-quiz", "the blank-answer ask was dropped", quizAllButBlank).contains("ASK blank answers left out: MISSING"))
    }

    private val todoAllButExport: (File) -> Unit = { d ->
        d.edit("lib/tags.js") { s -> s.replace("for (const g of t.tags) if (!out.includes(g)) out.push(g);", "for (const g of t.tags) if (!out.some(o => o.toLowerCase() === g.toLowerCase())) out.push(g);")
            .replace("return todos.filter(t => t.tags.includes(tag));", "return todos.filter(t => t.tags.some(g => g.toLowerCase() === tag.toLowerCase()));") }
        d.edit("lib/todos.js") { s -> s.replace("return todos.slice().sort((a, b) => (a.due || '').localeCompare(b.due || ''));",
            "return todos.slice().sort((a, b) => (a.due ? 0 : 1) - (b.due ? 0 : 1) || (a.due || '').localeCompare(b.due || ''));\n  }\n\n  function clearDone(todos) {\n    return todos.filter(t => !t.done);")
            .replace("return { add, toggle, byDue };", "return { add, toggle, byDue, clearDone };") }
        d.edit("index.html") { s -> s.replace("<button id=\"export\">Export</button>", "<button id=\"clear\">Clear done</button>\n  <button id=\"export\">Export</button>") }
    }

    @Test fun sealedTodo() {
        fails("sealed-todo", "nothing was done")
        passes("sealed-todo") { todoAllButExport(it); it.edit("lib/exportText.js") { s -> s.replace("+ (t.due ? ' (due ' + t.due + ')' : ''))", "+ (t.tags.length ? ' ' + t.tags.map(g => '#' + g).join(' ') : '') + (t.due ? ' (due ' + t.due + ')' : ''))") } }
        assertTrue(fails("sealed-todo", "the export ask was dropped", todoAllButExport).contains("ASK export includes tags: MISSING"))
    }

    // ---- D: the shared cause fixed passes; the reported screen alone fails

    @Test fun sealedUnits() {
        fails("sealed-units", "nothing was done")
        passes("sealed-units") { it.edit("lib/show.js") { s -> s.replace("String(Math.round(n * f / f))", "String(Math.round(n * f) / f)") } }
        fails("sealed-units", "only the length screen rounds") { it.edit("ui/lengthView.js") { s -> s.replace("show(convert(value, from, to, factors.length), settings.places)", "String(Number(convert(value, from, to, factors.length).toFixed(settings.places)))") } }
    }

    @Test fun sealedAllotment() {
        fails("sealed-allotment", "nothing was done")
        val plotsOnly: (File) -> Unit = { d -> d.edit("ui/plotLabels.js") { s -> s.replace("clampLabel((p.x + p.w) * f, w)", "Math.max(4, Math.min((p.x + p.w) * f, width - w - 4))") } }
        passes("sealed-allotment") { d ->
            d.edit("lib/clamp.js") { s -> s.replace("function clampLabel(x, w) {\n    return Math.max(4, Math.min(x, MAP_WIDTH - w - 4));", "function clampLabel(x, w, width) {\n    return Math.max(4, Math.min(x, (width || MAP_WIDTH) - w - 4));") }
            d.edit("ui/plotLabels.js") { s -> s.replace("clampLabel((p.x + p.w) * f, w)", "clampLabel((p.x + p.w) * f, w, width)") }
            d.edit("ui/pathLabels.js") { s -> s.replace("const { factor } = require('../lib/scale.js');", "const { factor, mapWidth } = require('../lib/scale.js');").replace("clampLabel(p.x * f, w)", "clampLabel(p.x * f, w, mapWidth(viewport))") }
            d.edit("ui/tapLabels.js") { s -> s.replace("const { factor } = require('../lib/scale.js');", "const { factor, mapWidth } = require('../lib/scale.js');").replace("clampLabel(t.x * f + 6, w)", "clampLabel(t.x * f + 6, w, mapWidth(viewport))") }
            d.edit("ui/tooltip.js") { s -> s.replace("const { labelWidth } = require('../lib/text.js');", "const { labelWidth } = require('../lib/text.js');\n  const { mapWidth } = require('../lib/scale.js');").replace("clampLabel(fingerX + 12, w)", "clampLabel(fingerX + 12, w, mapWidth(viewport))") }
        }
        fails("sealed-allotment", "only the plot labels were clamped", plotsOnly)
    }

    @Test fun sealedCaptions() {
        fails("sealed-captions", "nothing was done")
        passes("sealed-captions") { it.edit("lib/caption.js") { s -> s.replace("return c ? escapeHtml(c) : 'Untitled';", "return c || 'Untitled';") } }
        fails("sealed-captions", "only the gallery decodes") { it.edit("ui/gallery.js") { s -> s.replace("text: captionText(p) }", "text: captionText(p).replace(/&#39;/g, \"'\").replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>') }") } }
        fails("sealed-captions", "escapeHtml stopped escaping") { it.edit("lib/escape.js") { s -> s.replace("return String(s).replace(", "return String(s); String(s).replace(") } }
    }
}
