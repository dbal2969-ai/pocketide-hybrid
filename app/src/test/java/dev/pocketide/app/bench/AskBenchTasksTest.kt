package dev.pocketide.app.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Proves the ask tasks measure every ask: each fails on the start, passes a reference solution, and fails with any one ask left out. */
class AskBenchTasksTest {
    companion object {
        @BeforeClass @JvmStatic fun tools() {
            assumeTrue("node is needed for the benchmark checks", try { ProcessBuilder("bash", "-c", "node --version").start().waitFor() == 0 } catch (e: Exception) { false })
        }
    }

    private fun run(name: String, change: (File) -> Unit): Pair<Int, String> {
        val t = AskBenchTasks.ALL.single { it.name == name }
        val dir = Files.createTempDirectory("asks").toFile()
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
    private fun fails(name: String, why: String, change: (File) -> Unit = {}) { val (c, o) = run(name, change); println("$name, $why: ${o.trim()}"); assertNotEquals("$name should fail when $why", 0, c) }
    private fun File.edit(p: String, f: (String) -> String) = File(this, p).let { val before = it.readText(); val after = f(before); require(after != before) { "edit of $p matched nothing" }; it.writeText(after) }

    @Test fun namesAreUniqueAndTheSuiteIsSelectable() {
        val names = AskBenchTasks.ALL.map { it.name }
        assertEquals(names.size, names.toSet().size)
        val others = (BenchTasks.ALL + HardBenchTasks.ALL + HeldOutBenchTasks.ALL + RealProjectBenchTasks.ALL).map { it.name }.toSet()
        assertEquals(emptySet<String>(), names.toSet() intersect others)
        assertEquals(names, BenchTasks.select("asks", emptySet()).map { it.name })
        // 1.5.139: the word counter is not required to catch every request (ask-garden states its asks inside a description, as people
        // do); a run with a helper model set counts them with the helper. The counts are printed for reading the runs.
        for (t in AskBenchTasks.ALL) println("${t.name}: the word counter finds ${dev.pocketide.app.core.AskLedger.count(t.request)} asks")
        assertEquals(7, names.size)
    }

    // the reference solution, in three parts, so each test can leave one out
    private val subtotalFirst: (File) -> Unit = { it.edit("src/cart.js") { s -> s
        .replace("  tax() { return cents(this.subtotal() * TAX); }", "  tax() { return cents((this.subtotal() - this.saving()) * TAX); }")
        .replace("    const before = this.subtotal() + this.tax();\n", "    const before = this.subtotal();\n") } }
    private val savingLine: (File) -> Unit = { it.edit("src/receipt.js") { s -> s.replace("  lines.push('Total  ' + money(cart.total()));",
        "  if (cart.saving() > 0) lines.push('Saving  ' + money(cart.saving()));\n  lines.push('Total  ' + money(cart.total()));") } }
    private val oneCoupon: (File) -> Unit = { it.edit("src/cart.js") { s -> s.replace("    const pct = percentFor(code);\n", "    if (this.coupons.length) return false;\n    const pct = percentFor(code);\n") } }

    @Test fun askReceipt() {
        fails("ask-receipt", "nothing was done")
        passes("ask-receipt") { subtotalFirst(it); savingLine(it); oneCoupon(it) }
        fails("ask-receipt", "the coupon still comes off the total with tax") { savingLine(it); oneCoupon(it) }
        fails("ask-receipt", "there is no Saving line") { subtotalFirst(it); oneCoupon(it) }
        fails("ask-receipt", "a second coupon is accepted") { subtotalFirst(it); savingLine(it) }
        fails("ask-receipt", "the Saving line comes before Tax") { subtotalFirst(it); oneCoupon(it); it.edit("src/receipt.js") { s -> s.replace("  lines.push('Tax  '",
            "  if (cart.saving() > 0) lines.push('Saving  ' + money(cart.saving()));\n  lines.push('Tax  '") } }
        fails("ask-receipt", "a receipt with no coupon shows a Saving line") { subtotalFirst(it); oneCoupon(it); it.edit("src/receipt.js") { s -> s.replace("  lines.push('Total  ' + money(cart.total()));",
            "  lines.push('Saving  ' + money(cart.saving()));\n  lines.push('Total  ' + money(cart.total()));") } }
    }

    // ask-cli: the option, the help line, unknown options
    private val jsonOption: (File) -> Unit = { it.edit("src/cli.js") { s -> s
        .replace("const { text } = require('./report.js');", "const { text, rows } = require('./report.js');")
        .replace("  const report = text(sales);\n", "  if (argv.includes('--json')) { const rs = rows(sales); out(JSON.stringify({ rows: rs, total: rs.reduce((s, r) => s + r.amount, 0) })); return 0; }\n  const report = text(sales);\n") } }
    private val helpLine: (File) -> Unit = { it.edit("src/cli.js") { s -> s.replace("  '  --quiet    Print only the total',", "  '  --quiet    Print only the total',\n  '  --json     Print the report as JSON',") } }
    private val unknown: (File) -> Unit = { it.edit("src/cli.js") { s -> s.replace("  if (argv.includes('--help'))",
        "  for (const a of argv) if (!['--help', '--quiet', '--json'].includes(a)) { out('Unknown option: ' + a); return 2; }\n  if (argv.includes('--help'))") } }

    @Test fun askCli() {
        fails("ask-cli", "nothing was done")
        passes("ask-cli") { jsonOption(it); helpLine(it); unknown(it) }
        fails("ask-cli", "there is no --json") { helpLine(it); unknown(it) }
        fails("ask-cli", "the help does not list --json") { jsonOption(it); unknown(it) }
        fails("ask-cli", "unknown options are ignored") { jsonOption(it); helpLine(it) }
    }

    // ask-rename: users.js, format.js, old saves; the fixture must not change
    private val renameUsers: (File) -> Unit = { it.edit("src/users.js") { s -> s
        .replace("function create(id, name, email) { return { id, name, email }; }", "function create(id, displayName, email) { return { id, displayName, email }; }")
        .replace("({ id: u.id, name: u.name, email: u.email })", "({ id: u.id, displayName: u.displayName, email: u.email })") } }
    private val renameFormat: (File) -> Unit = { it.edit("src/format.js") { s -> s.replace("u.name", "u.displayName") } }
    private val oldSaves: (File) -> Unit = { it.edit("src/users.js") { s -> s.replace("displayName: u.displayName, email", "displayName: u.displayName !== undefined ? u.displayName : u.name, email") } }

    @Test fun askRename() {
        fails("ask-rename", "nothing was done")
        passes("ask-rename") { renameUsers(it); renameFormat(it); oldSaves(it) }
        fails("ask-rename", "format.js still shows name") { renameUsers(it); oldSaves(it) }
        fails("ask-rename", "old saves lose their name") { renameUsers(it); renameFormat(it) }
        fails("ask-rename", "the old save in test/fixtures was rewritten") { renameUsers(it); renameFormat(it); it.edit("test/fixtures/old-users.json") { s -> s.replace("\"name\"", "\"displayName\"") } }
    }

    // ask-garden: the level, the fill, the button
    private val level: (File) -> Unit = { it.edit("src/garden.js") { s -> s.replace("  if (can.empty) return false;\n", "  if (can.empty) return false;\n  plant.water = Math.min(100, plant.water + 30);\n") } }
    private val fill: (File) -> Unit = { it.edit("src/garden.js") { s -> s.replace("  return true;\n}", "  can.fills -= 1;\n  return true;\n}") } }
    private val button: (File) -> Unit = { it.edit("src/ui.js") { s -> s.replace("{ label: 'Water', disabled: false }", "{ label: 'Water', disabled: can.empty }") } }

    @Test fun askGarden() {
        fails("ask-garden", "nothing was done")
        passes("ask-garden") { level(it); fill(it); button(it) }
        fails("ask-garden", "the plant gets no water") { fill(it); button(it) }
        fails("ask-garden", "the level goes above 100") { fill(it); button(it); it.edit("src/garden.js") { s -> s.replace("  if (can.empty) return false;\n", "  if (can.empty) return false;\n  plant.water += 30;\n") } }
        fails("ask-garden", "the can is never used up") { level(it); button(it) }
        fails("ask-garden", "the Water button stays enabled") { level(it); fill(it) }
    }

    // ask-money: the product page, the invoice, VAT rounding
    private val COMMAS = ".replace(/\\B(?=(\\d{3})+(?!\\d))/g, ',')"
    private val productCommas: (File) -> Unit = { it.edit("src/product.js") { s -> s.replace("'EUR ' + (cents / 100).toFixed(2)",
        "'EUR ' + (cents / 100).toFixed(2).split('.').map((p, i) => i ? p : p$COMMAS).join('.')") } }
    private val invoiceCommas: (File) -> Unit = { it.edit("src/invoice.js") { s -> s.replace("(cents / 100).toFixed(2) + ' EUR'",
        "(cents / 100).toFixed(2).split('.').map((p, i) => i ? p : p$COMMAS).join('.') + ' EUR'") } }
    private val vatUp: (File) -> Unit = { it.edit("src/invoice.js") { s -> s.replace("Math.floor(cents * 21 / 100)", "Math.round(cents * 21 / 100)") } }

    @Test fun askMoney() {
        fails("ask-money", "nothing was done")
        passes("ask-money") { productCommas(it); invoiceCommas(it); vatUp(it) }
        fails("ask-money", "the product page has no commas") { invoiceCommas(it); vatUp(it) }
        fails("ask-money", "invoices have no commas") { productCommas(it); vatUp(it) }
        fails("ask-money", "VAT still rounds down") { productCommas(it); invoiceCommas(it) }
    }

    // ask-form: the rule and its message, Submit; the password rule must stay
    private val dotRule: (File) -> Unit = { it.edit("src/validate.js") { s -> s.replace("function email(v) { return v.includes('@') ? '' : 'Enter an email address'; }",
        "function email(v) {\n  if (!v.includes('@')) return 'Enter an email address';\n  return v.split('@').pop().includes('.') ? '' : 'Enter a full email address, like name@example.com';\n}") } }
    private val submit: (File) -> Unit = { it.edit("src/form.js") { s -> s
        .replace("  return {\n    errors:", "  const errors = { email: v.email(values.email || ''), password: v.password(values.password || '') };\n  return {\n    errors:")
        .replace("    submitDisabled: false,", "    submitDisabled: !!(errors.email || errors.password),") } }

    @Test fun askForm() {
        fails("ask-form", "nothing was done")
        passes("ask-form") { dotRule(it); submit(it) }
        fails("ask-form", "a@b is still accepted") { submit(it) }
        fails("ask-form", "the message is different") { submit(it); it.edit("src/validate.js") { s -> s.replace("v.includes('@') ? '' : 'Enter an email address'",
            "v.includes('@') && v.split('@').pop().includes('.') ? '' : 'Enter an email address'") } }
        fails("ask-form", "Submit is never disabled") { dotRule(it) }
        fails("ask-form", "the password rule changed") { dotRule(it); submit(it); it.edit("src/validate.js") { s -> s.replace("v.length >= 8", "v.length >= 6") } }
    }

    // ask-plan: yearlyPrice, the per-month line under the monthly one, and no yearly option under 10.00
    private val yearly: (File) -> Unit = { it.edit("src/plans.js") { s -> s.replace("module.exports = { PLANS };",
        "function yearlyPrice(plan) { return plan.monthly * 10; }\n\nmodule.exports = { PLANS, yearlyPrice };") } }
    private fun yearlyLine(rule: String, perMonth: String): (File) -> Unit = { it.edit("src/page.js") { s -> s
        .replace("const { PLANS } = require('./plans.js');", "const { PLANS, yearlyPrice } = require('./plans.js');")
        .replace("  return plans.map(p => p.name + ': ' + money(p.monthly) + '/month');",
            "  return plans.flatMap(p => {\n    const out = [p.name + ': ' + money(p.monthly) + '/month'];\n    if ($rule) out.push(p.name + ': ' + $perMonth + '/month billed yearly (' + money(yearlyPrice(p)) + '/year)');\n    return out;\n  });") } }

    @Test fun askPlan() {
        fails("ask-plan", "nothing was done")
        passes("ask-plan") { yearly(it); yearlyLine("p.monthly >= 1000", "money(yearlyPrice(p) / 12)")(it) }
        fails("ask-plan", "the yearly line shows the monthly price") { yearly(it); yearlyLine("p.monthly >= 1000", "money(p.monthly)")(it) }
        fails("ask-plan", "the yearly line shows the yearly price as per month") { yearly(it); yearlyLine("p.monthly >= 1000", "money(yearlyPrice(p))")(it) }
        fails("ask-plan", "Basic gets a yearly option") { yearly(it); yearlyLine("true", "money(yearlyPrice(p) / 12)")(it) }
        fails("ask-plan", "the page has no yearly lines") { yearly(it) }
    }
}
