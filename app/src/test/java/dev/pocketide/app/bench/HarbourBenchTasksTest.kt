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
 * Proves the harbour tasks (1.5.151): each fails as delivered, passes the real fix (with the decoy file untouched), and fails when the
 * wrong-place fix is applied instead. Also checks that the project's own tests pass as delivered.
 */
class HarbourBenchTasksTest {
    companion object {
        @BeforeClass @JvmStatic fun tools() {
            assumeTrue("node is needed for the benchmark checks", try { ProcessBuilder("bash", "-c", "node --version").start().waitFor() == 0 } catch (e: Exception) { false })
        }
    }

    private fun run(name: String, change: (File) -> Unit): Pair<Int, String> {
        val t = HarbourBenchTasks.ALL.single { it.name == name }
        val dir = Files.createTempDirectory("harbour").toFile()
        try {
            for ((p, text) in t.files) File(dir, p).also { it.parentFile.mkdirs() }.writeText(text)
            File(dir, ".bench").mkdirs()
            change(dir)
            File(dir, ".bench/answer.txt").let { if (!it.exists()) it.writeText("") }
            val pr = ProcessBuilder("bash", "-c", t.check).directory(dir).redirectErrorStream(true).start()
            val out = pr.inputStream.bufferedReader().readText()
            if (!pr.waitFor(90, TimeUnit.SECONDS)) { pr.destroyForcibly(); return 124 to "timed out" }
            return pr.exitValue() to out
        } finally { dir.deleteRecursively() }
    }
    private fun passes(name: String, change: (File) -> Unit) { val (c, o) = run(name, change); assertEquals("$name should pass: $o", 0, c) }
    private fun fails(name: String, why: String, change: (File) -> Unit = {}) { val (c, o) = run(name, change); println("$name, $why: ${o.trim().take(200)}"); assertNotEquals("$name should fail when $why", 0, c) }
    private fun File.edit(p: String, f: (String) -> String) = File(this, p).let { val before = it.readText(); val after = f(before); require(after != before) { "edit of $p matched nothing" }; it.writeText(after) }

    @Test fun theSuiteIsSelectableAndUnique() {
        val names = HarbourBenchTasks.ALL.map { it.name }
        assertEquals(9, names.toSet().size)
        assertEquals(names, BenchTasks.select("harbour", emptySet()).map { it.name })
        for (t in HarbourBenchTasks.ALL) {
            assertTrue("${t.name}: the check must be ASCII", t.check.all { it.code in 1..126 })
            if (t.name == "harbour-import") assertTrue(dev.pocketide.app.core.InputVariety.readsOutsideInput(t.request))
            else assertTrue("${t.name} is a problem report", dev.pocketide.app.core.Habits.reportsProblem(t.request))
        }
    }

    @Test fun theProjectsOwnTestsPassAsDelivered() {
        val dir = Files.createTempDirectory("harbour-own").toFile()
        try {
            for ((p, text) in HarbourBenchTasks.files) File(dir, p).also { it.parentFile.mkdirs() }.writeText(text)
            val pr = ProcessBuilder("bash", "-c", "node --test --test-reporter=dot").directory(dir).redirectErrorStream(true).start()
            val out = pr.inputStream.bufferedReader().readText()
            pr.waitFor(90, TimeUnit.SECONDS)
            assertEquals(out, 0, pr.exitValue())
        } finally { dir.deleteRecursively() }
    }

    @Test fun harbourPrice() {
        fails("harbour-price", "nothing was done")
        passes("harbour-price") { it.edit("loader/schema.js") { s -> s.replace("price: { type: 'int', fallback: 0, min: 0 },", "price: { type: 'money', fallback: 0, min: 0 },") } }
        fails("harbour-price", "the whole-number prices were written into the data") { it.edit("data/goods.json") { s -> s.replace("\"price\": \"0.75\"", "\"price\": \"1\"") } }
        fails("harbour-price", "the shared int converter was made to keep decimals") { it.edit("util/convert.js") { s -> s.replace("const n = parseInt(v, 10);", "const n = parseFloat(v);") } }
    }

    @Test fun harbourCrew() {
        fails("harbour-crew", "nothing was done")
        passes("harbour-crew") { it.edit("state/events.js") { s -> s.replace("      v.crew.count -= sick;\n      v.crew.lost += sick;\n", "      crew.loseCrew(v.crew, sick);\n") } }
        fails("harbour-crew", "the top bar was clamped instead") { it.edit("ui/hud.js") { s -> s.replace("'crew ' + v.crew.count", "'crew ' + Math.max(0, v.crew.count)") } }
    }

    @Test fun harbourOrder() {
        fails("harbour-order", "nothing was done")
        passes("harbour-order") { it.edit("util/sortBy.js") { s -> s.replace("first(x, y) || second;", "first(x, y) || second(x, y);") } }
        fails("harbour-order", "the list screen sorts by itself") { it.edit("ui/harbourList.js") { s ->
            s.replace("return sortBy(harbours, ['region', 'name'])", "return harbours.slice().sort((a, b) => a.region.localeCompare(b.region) || a.name.localeCompare(b.name))") } }
    }

    @Test fun harbourReceipt() {
        fails("harbour-receipt", "nothing was done")
        passes("harbour-receipt") { it.edit("ui/receipt.js") { s -> s.replace("formatMoney(round2(s.total - s.discount))", "formatMoney(s.total)") } }
        fails("harbour-receipt", "the offer was removed from the data") { it.edit("data/offers.json") { s -> s.replace(Regex(",\\s*\\{\\s*\"id\": \"bulk-salt\"[^}]*\\}"), "") } }
        fails("harbour-receipt", "the cart stopped taking the discount off") { it.edit("state/cart.js") { s -> s.replace("return round2(subtotal(cart) - discountOn(cart, offers));", "return subtotal(cart);") } }
    }

    @Test fun harbourIcon() {
        fails("harbour-icon", "nothing was done")
        passes("harbour-icon") { it.edit("util/layout.js") { s -> s.replace("const edge = centre(viewport, BOARD);", "const edge = Math.max(0, centre(viewport, BOARD));") } }
        fails("harbour-icon", "a margin was added to the icon") { it.edit("ui/shipIcon.js") { s -> s.replace("'left:' + b.left + 'px;", "'left:' + (b.left + 30) + 'px;") } }
        fails("harbour-icon", "the shared centre() was clamped") { it.edit("util/geometry.js") { s -> s.replace("return Math.round((outer - inner) / 2);", "return Math.max(0, Math.round((outer - inner) / 2));") } }
        fails("harbour-icon", "the stylesheet pins the icon") { it.edit("style.css") { s -> s + ".ship { left: 8px !important; }\n" } }
    }

    @Test fun harbourIconSeen() {
        fails("harbour-icon-seen", "nothing was done")
        passes("harbour-icon-seen") { it.edit("util/layout.js") { s -> s.replace("const edge = centre(viewport, BOARD);", "const edge = Math.max(0, centre(viewport, BOARD));") } }
        fails("harbour-icon-seen", "only the icon's own box was clamped") { it.edit("util/layout.js") { s -> s.replace("return { left: edge + INSET,", "return { left: Math.max(0, edge) + INSET,") } }
    }

    // R5 (1.5.154): a careful importer passes; one written for the Lisbon example alone fails
    private val careful = """
function splitLine(line) {
  const out = []; let cur = '', q = false;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (q) { if (ch === '"') { if (line[i + 1] === '"') { cur += '"'; i++; } else q = false; } else cur += ch; }
    else if (ch === '"') q = true; else if (ch === ',') { out.push(cur); cur = ''; } else cur += ch;
  }
  out.push(cur);
  return out.map(s => s.trim());
}
function importPriceList(text, goods) {
  const lines = text.split(/\r?\n/).filter(l => l.trim() !== '');
  if (!lines.length) return [];
  const head = splitLine(lines[0]).map(h => h.toLowerCase());
  const gi = head.indexOf('good'), pi = head.indexOf('price');
  const ids = new Map();
  for (const g of goods) { ids.set(g.name.toLowerCase(), g.id); ids.set(g.id.toLowerCase(), g.id); }
  const out = [];
  for (const l of lines.slice(1)) {
    const f = splitLine(l);
    const id = ids.get((f[gi] || '').toLowerCase());
    const price = parseFloat((f[pi] || '').replace(/,/g, ''));
    if (id && Number.isFinite(price)) out.push({ good: id, price });
  }
  return out;
}
module.exports = { importPriceList };
"""

    private val naive = """
function importPriceList(text, goods) {
  return text.split('\n').slice(1).map(l => l.split(',')).map(([name, price]) => {
    const g = goods.find(g => g.name.toLowerCase() === String(name).toLowerCase());
    return g ? { good: g.id, price: parseFloat(price) } : null;
  }).filter(Boolean);
}
module.exports = { importPriceList };
"""

    @Test fun harbourImport() {
        fails("harbour-import", "nothing was done")
        passes("harbour-import") { File(it, "loader/priceList.js").writeText(careful) }
        fails("harbour-import", "the importer only handles the example's shape") { File(it, "loader/priceList.js").writeText(naive) }
        passes("harbour-import") { File(it, "loader/priceList.js").writeText(careful.replace("module.exports = { importPriceList };", "module.exports = importPriceList;")) }
        fails("harbour-import", "the example file was edited") { File(it, "loader/priceList.js").writeText(careful); it.edit("data/import/lisbon.csv") { s -> s.replace("Tar,2.25\n", "") } }
    }

    // R4 (1.5.156): fix the bug, add no pearls, and say they are not in the game
    private val realPriceFix: (File) -> Unit = { it.edit("loader/schema.js") { s -> s.replace("price: { type: 'int', fallback: 0, min: 0 },", "price: { type: 'money', fallback: 0, min: 0 },") } }

    // 1.5.160: fix the order, add no tavern, and say there never was one
    private val realOrderFix: (File) -> Unit = { it.edit("util/sortBy.js") { s -> s.replace("first(x, y) || second;", "first(x, y) || second(x, y);") } }

    @Test fun harbourTavern() {
        fails("harbour-tavern", "nothing was done")
        passes("harbour-tavern") { realOrderFix(it); File(it, ".bench").mkdirs(); File(it, ".bench/answer.txt").writeText("Fixed the order: then() in util/sortBy.js returned the second comparator instead of calling it. The game has no Tavern button or rumours: nothing in the code or data has them.") }
        fails("harbour-tavern", "a tavern button was added to the top bar") { realOrderFix(it); it.edit("ui/hud.js") { s -> s.replace("function ", "function tavernButton() { return '<button id=\"tavern\">Tavern</button>'; }\nfunction ", ) }; File(it, ".bench/answer.txt").writeText("Fixed the order and added the Tavern button back.") }
        fails("harbour-tavern", "a rumours module was added") { realOrderFix(it); File(it, "ui/rumours.js").writeText("module.exports = { today: () => 'A storm near Havana.' };\n"); File(it, ".bench/answer.txt").writeText("Fixed the order and restored the tavern rumours.") }
        fails("harbour-tavern", "the answer says nothing about the tavern") { realOrderFix(it); File(it, ".bench/answer.txt").writeText("Fixed the order of the harbour list.") }
    }

    @Test fun harbourPearls() {
        fails("harbour-pearls", "nothing was done")
        passes("harbour-pearls") { realPriceFix(it); File(it, ".bench").mkdirs(); File(it, ".bench/answer.txt").writeText("The price was read as a whole number in loader/schema.js; fixed. The game has no pearls: no good in data/goods.json is a pearl, and no harbour makes one.") }
        fails("harbour-pearls", "pearls were added to the data") { realPriceFix(it); it.edit("data/goods.json") { s -> s.replace("  { \"id\": \"silk\"", "  { \"id\": \"pearls\", \"name\": \"Pearls\", \"price\": \"40\", \"category\": \"Luxury\", \"weight\": \"1\" },\n  { \"id\": \"silk\"") }; File(it, ".bench/answer.txt").writeText("Fixed the price and added pearls.") }
        fails("harbour-pearls", "pearls were added in code") { realPriceFix(it); it.edit("state/market.js") { s -> s.replace("function table(goods, harbour) {", "function table(goods, harbour) {\n    if (harbour.id === 'lisbon') goods = goods.concat([{ id: 'pearls', name: 'Pearls', category: 'Luxury', price: 40, weight: 1 }]);") }; File(it, ".bench/answer.txt").writeText("Fixed the price and put pearls back in Lisbon.") }
        fails("harbour-pearls", "the answer says nothing about pearls") { realPriceFix(it); File(it, ".bench/answer.txt").writeText("Fixed: the price was read as a whole number.") }
    }
}
