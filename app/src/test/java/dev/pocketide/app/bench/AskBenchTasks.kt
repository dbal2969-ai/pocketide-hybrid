package dev.pocketide.app.bench

import dev.pocketide.app.bench.BenchTasks.Task
import dev.pocketide.app.bench.HardBenchTasks.check
import dev.pocketide.app.bench.HardBenchTasks.keep

/**
 * The tuning suite for the ask checklist (POCKETIDE_BENCH_SUITE=asks, 1.5.138; rebuilt in 1.5.139 after runs 43410222685/43410238192,
 * where the settings and to-do tasks passed every run either way and so measured nothing). Each request holds three or four asks, one
 * of them easy to leave out, in a small multi-file project: a second file needing the same change (money, rename), a help text
 * (cli), asks inside a description (garden), a file or rule to leave alone (rename, form), an exact place or message (receipt, form).
 * Every hidden check maps to an ask the request states. ask-plan (1.5.141, Copilot ANALYSIS-1.5.139-ASKS) adds a misreading other than
 * "just before": a per-month figure for a yearly price. Written for tuning, so the real-project suite (rp-*) stays a held-out score
 * (Copilot's HANDOFF-RAISE-CEILING: "Do not tune on the rp-* tasks"). Read these transcripts freely when changing the loop.
 * Every check is hidden (written to .bench/ after the run); AskBenchTasksTest proves each fails on the start, passes a reference
 * solution and fails when any one ask is left out.
 */
object AskBenchTasks {
    private const val PKG = "{\n  \"name\": \"app\",\n  \"private\": true,\n  \"scripts\": { \"test\": \"node --test\" }\n}\n"

    // ---- ask-receipt: a shop cart; three asks, the third (no stacking) the one most often dropped ----
    val shop = mapOf(
        "package.json" to PKG,
        "src/prices.js" to """
// Prices are kept in cents so sums are exact.
const TAX = 0.2;

function cents(n) { return Math.round(n); }

module.exports = { TAX, cents };
""".trimStart(),
        "src/coupons.js" to """
// Known coupon codes and the percentage they take off.
const COUPONS = { SAVE10: 10, SAVE25: 25, STAFF: 40 };

function percentFor(code) {
  return Object.prototype.hasOwnProperty.call(COUPONS, code) ? COUPONS[code] : null;
}

module.exports = { percentFor };
""".trimStart(),
        "src/cart.js" to """
const { TAX, cents } = require('./prices.js');
const { percentFor } = require('./coupons.js');

class Cart {
  constructor() { this.items = []; this.coupons = []; }

  add(name, price, qty = 1) { this.items.push({ name, price, qty }); }

  subtotal() { return this.items.reduce((s, i) => s + i.price * i.qty, 0); }

  tax() { return cents(this.subtotal() * TAX); }

  // Adds a coupon by code. Returns true when it was added.
  addCoupon(code) {
    const pct = percentFor(code);
    if (pct === null) return false;
    this.coupons.push({ code, pct });
    return true;
  }

  // What the coupons take off, in cents.
  saving() {
    const before = this.subtotal() + this.tax();
    return this.coupons.reduce((s, c) => s + cents(before * c.pct / 100), 0);
  }

  total() { return this.subtotal() + this.tax() - this.saving(); }
}

module.exports = { Cart };
""".trimStart(),
        "src/receipt.js" to """
// The receipt printed at the till: one line per item, then the sums. Amounts are shown in euros.
function money(c) { return (c / 100).toFixed(2); }

function receipt(cart) {
  const lines = cart.items.map(i => i.qty + ' x ' + i.name + '  ' + money(i.price * i.qty));
  lines.push('Subtotal  ' + money(cart.subtotal()));
  lines.push('Tax  ' + money(cart.tax()));
  lines.push('Total  ' + money(cart.total()));
  return lines.join('\n');
}

module.exports = { receipt, money };
""".trimStart(),
        "test/cart.test.js" to """
const test = require('node:test');
const assert = require('assert');
const { Cart } = require('../src/cart.js');

test('a cart adds up its items with tax', () => {
  const c = new Cart();
  c.add('Tea', 300, 2);
  assert.strictEqual(c.subtotal(), 600);
  assert.strictEqual(c.total(), 720);
});
""".trimStart(),
        "README.md" to "# Till\n\nA shop cart (`src/cart.js`) and its printed receipt (`src/receipt.js`). Prices are in cents.\n",
    )

    val askReceipt = Task("ask-receipt",
        "Coupons take their percentage off the total with tax, but they should come off the subtotal before tax (tax is then worked out on the " +
            "discounted subtotal). Show what the coupon saved as its own \"Saving\" line on the receipt, just before the Total line (with no coupon there is no Saving line), and don't let a " +
            "second coupon be added to a cart that already has one (addCoupon returns false).",
        shop, check("""
const { Cart } = R('src/cart.js');
const { receipt } = R('src/receipt.js');
function cart() { const c = new Cart(); c.add('Tea', 300, 2); c.add('Cake', 400); return c; }
let c = cart();
if (!c.addCoupon('SAVE10')) fail('a known coupon was refused');
// subtotal 1000, 10% off -> 900, tax 180, total 1080; saving 100
if (c.total() !== 1080) fail('a 10% coupon on a 10.00 subtotal should give a total of 1080 cents (900 + 180 tax), got ' + c.total());
const r = receipt(c);
const lines = r.split('\n');
const s = lines.findIndex(l => /^Saving\b/.test(l.trim()));
const t = lines.findIndex(l => /^Total\b/.test(l.trim()));
if (s < 0) fail('the receipt has no Saving line:\n' + r);
if (!/1\.00/.test(lines[s])) fail('the Saving line should show 1.00, got: ' + lines[s]);
if (t !== s + 1) fail('the Saving line should come just before Total:\n' + r);
if (!/10\.80/.test(lines[t])) fail('the Total line should show 10.80, got: ' + lines[t]);
c = cart(); c.addCoupon('SAVE10');
if (c.addCoupon('SAVE25') !== false) fail('a second coupon was accepted');
if (c.total() !== 1080) fail('a refused second coupon still changed the total: ' + c.total());
c = cart();
if (c.addCoupon('NOPE') !== false) fail('an unknown coupon was accepted');
if (!c.addCoupon('SAVE25')) fail('a coupon was refused on an empty coupon list');
c = cart();
if (/Saving/.test(receipt(c))) fail('a receipt with no coupon should have no Saving line');
if (c.total() !== 1200) fail('a cart with no coupon should still total 1200, got ' + c.total());
testsPass();
""" + keep(shop, listOf("src/coupons.js", "src/prices.js"))), rounds = 30)

    private fun test(file: String, body: String) = "const test = require('node:test');\nconst assert = require('assert');\n" + body.trimStart()

    // ---- ask-cli: a report command; a new option, the help text that should list it (often left out), and unknown options ----
    val cli = mapOf(
        "package.json" to PKG,
        "src/report.js" to """
// The sales report: one line per region, then the total.
function rows(sales) { return Object.keys(sales).sort().map(r => ({ region: r, amount: sales[r] })); }

function text(sales) {
  const rs = rows(sales);
  const total = rs.reduce((s, r) => s + r.amount, 0);
  return rs.map(r => r.region + ': ' + r.amount).concat(['Total: ' + total]).join('\n');
}

module.exports = { rows, text };
""".trimStart(),
        "src/cli.js" to """
const { text } = require('./report.js');

const SALES = { north: 120, south: 80, east: 45 };

const HELP = [
  'Usage: report [options]',
  '',
  'Options:',
  '  --help     Show this help',
  '  --quiet    Print only the total',
].join('\n');

// Runs the report command with its arguments; out(line) prints a line. Returns the exit code.
function main(argv, out, sales = SALES) {
  if (argv.includes('--help')) { out(HELP); return 0; }
  const report = text(sales);
  if (argv.includes('--quiet')) { out(report.split('\n').pop()); return 0; }
  out(report);
  return 0;
}

module.exports = { main, HELP };
""".trimStart(),
        "test/cli.test.js" to test("cli", """
const { main } = require('../src/cli.js');

test('the report ends with the total', () => {
  const lines = [];
  assert.strictEqual(main([], l => lines.push(l)), 0);
  assert.match(lines.join('\n'), /Total: 245/);
});
"""),
    )

    val askCli = Task("ask-cli",
        "Add a --json option to the report command in src/cli.js that prints the report as JSON instead of text, in the form " +
            "{\"rows\": [{\"region\": ..., \"amount\": ...}], \"total\": ...}. List --json in the help text with a short description. " +
            "An unknown option such as --jsno should print \"Unknown option: --jsno\" and return exit code 2 instead of being ignored.",
        cli, check("""
const { main } = R('src/cli.js');
function cmd(args) { const lines = []; const code = main(args, l => lines.push(String(l))); return { code, out: lines.join('\n') }; }
let r = cmd(['--json']);
let j = null;
try { j = JSON.parse(r.out); } catch (e) { fail('--json should print JSON, got:\n' + r.out); }
if (r.code !== 0) fail('--json should return 0, got ' + r.code);
if (j.total !== 245) fail('the JSON total should be 245, got ' + JSON.stringify(j));
if (!Array.isArray(j.rows) || j.rows.length !== 3 || j.rows[0].region !== 'east' || j.rows[0].amount !== 45) fail('the JSON rows are wrong: ' + JSON.stringify(j.rows));
r = cmd(['--help']);
if (!/--json\s+\S/.test(r.out)) fail('the help text should list --json with a description:\n' + r.out);
if (!/--quiet/.test(r.out)) fail('the help text lost --quiet:\n' + r.out);
r = cmd(['--jsno']);
if (!r.out.includes('Unknown option: --jsno')) fail('an unknown option should print "Unknown option: --jsno", got:\n' + r.out);
if (r.code !== 2) fail('an unknown option should return exit code 2, got ' + r.code);
r = cmd([]);
if (r.code !== 0 || !/Total: 245/.test(r.out)) fail('the plain report changed:\n' + r.out);
r = cmd(['--quiet']);
if (r.out.trim() !== 'Total: 245') fail('--quiet changed: ' + r.out);
testsPass();
"""), rounds = 30)

    // ---- ask-rename: a field renamed in two files, old saves still loading, and a file that must not change ----
    val users = mapOf(
        "package.json" to PKG,
        "src/users.js" to """
// Users as saved on disk: { id, name, email }.
function create(id, name, email) { return { id, name, email }; }

function load(json) { return JSON.parse(json).map(u => ({ id: u.id, name: u.name, email: u.email })); }

function save(users) { return JSON.stringify(users); }

module.exports = { create, load, save };
""".trimStart(),
        "src/format.js" to """
// How a user is shown in lists and greetings.
function listItem(u) { return u.name + ' <' + u.email + '>'; }

function greeting(u) { return 'Hello, ' + u.name + '!'; }

module.exports = { listItem, greeting };
""".trimStart(),
        "test/fixtures/old-users.json" to "[{\"id\":1,\"name\":\"Ada\",\"email\":\"ada@example.com\"}]\n",
        "test/users.test.js" to test("users", """
const users = require('../src/users.js');

test('a user survives a save and a load', () => {
  const u = users.create(2, 'Grace', 'grace@example.com');
  assert.deepStrictEqual(users.load(users.save([u])), [u]);
});
"""),
    )

    val askRename = Task("ask-rename",
        "Rename the user field name to displayName throughout src/ (creating, saving and showing users). Users saved before this change still " +
            "have name in their JSON, so load() must turn that into displayName and they keep working. Don't edit anything in test/fixtures/: " +
            "old-users.json there is an old save to test against.",
        users, check("""
const users = R('src/users.js');
const fmt = R('src/format.js');
const u = users.create(2, 'Grace', 'grace@example.com');
if (u.displayName !== 'Grace') fail('create() should set displayName, got ' + JSON.stringify(u));
if ('name' in u) fail('create() still sets name: ' + JSON.stringify(u));
const saved = JSON.parse(users.save([u]));
if (saved[0].displayName !== 'Grace' || 'name' in saved[0]) fail('save() should write displayName and not name: ' + JSON.stringify(saved));
const back = users.load(users.save([u]));
if (back[0].displayName !== 'Grace') fail('a new save does not load back: ' + JSON.stringify(back));
if (fmt.listItem(u) !== 'Grace <grace@example.com>') fail('listItem() should show displayName, got ' + fmt.listItem(u));
if (fmt.greeting(u) !== 'Hello, Grace!') fail('greeting() should use displayName, got ' + fmt.greeting(u));
const old = users.load(T('test/fixtures/old-users.json'));
if (old[0].displayName !== 'Ada') fail('an old save with name should load as displayName, got ' + JSON.stringify(old));
if (fmt.greeting(old[0]) !== 'Hello, Ada!') fail('a user from an old save is greeted wrongly: ' + fmt.greeting(old[0]));
""" + keep(users, listOf("test/fixtures/old-users.json")) + "testsPass();\n"), rounds = 30)

    // ---- ask-garden: the asks are inside a description of what should happen ("does nothing ... it should ...") ----
    val garden = mapOf(
        "package.json" to PKG,
        "src/garden.js" to """
// A watering can and the plants it waters. A plant's water level runs from 0 to 100; a full can holds 3 fills.
class Can {
  constructor(fills = 3) { this.fills = fills; }
  get empty() { return this.fills === 0; }
  refill() { this.fills = 3; }
}

class Plant {
  constructor(name, water = 50) { this.name = name; this.water = water; }
}

// Waters the plant from the can. Returns true when the plant got water.
function water(plant, can) {
  if (can.empty) return false;
  return true;
}

module.exports = { Can, Plant, water };
""".trimStart(),
        "src/ui.js" to """
// The buttons shown for the selected plant.
function buttons(can) {
  return [{ label: 'Water', disabled: false }, { label: 'Refill', disabled: false }];
}

module.exports = { buttons };
""".trimStart(),
        "test/garden.test.js" to test("garden", """
const { Can, Plant, water } = require('../src/garden.js');

test('an empty can waters nothing', () => {
  const p = new Plant('fern', 40);
  assert.strictEqual(water(p, new Can(0)), false);
  assert.strictEqual(p.water, 40);
});
"""),
    )

    val askGarden = Task("ask-garden",
        "Watering a plant does nothing. With an empty can that is right, but with water in the can it should raise the plant's water level by 30, " +
            "never above 100, and use up one of the can's fills. The Water button in src/ui.js should also be disabled while the can is empty.",
        garden, check("""
const { Can, Plant, water } = R('src/garden.js');
const { buttons } = R('src/ui.js');
const can = new Can();
const p = new Plant('fern', 50);
if (water(p, can) !== true) fail('watering from a full can should return true');
if (p.water !== 80) fail('watering should raise the level by 30 (50 to 80), got ' + p.water);
if (can.fills !== 2) fail('watering should use one fill (3 to 2), got ' + can.fills);
water(p, can);
if (p.water !== 100) fail('the level should stop at 100, got ' + p.water);
water(p, can);
if (can.fills !== 0) fail('three waterings should empty the can, fills = ' + can.fills);
const q = new Plant('ivy', 10);
if (water(q, can) !== false || q.water !== 10) fail('an empty can should not water, level ' + q.water);
if (can.fills !== 0) fail('an empty can went below 0 fills: ' + can.fills);
const b = buttons(can).find(x => x.label === 'Water');
if (!b || b.disabled !== true) fail('the Water button should be disabled when the can is empty: ' + JSON.stringify(buttons(can)));
const full = buttons(new Can()).find(x => x.label === 'Water');
if (!full || full.disabled !== false) fail('the Water button should be enabled when the can has water: ' + JSON.stringify(buttons(new Can())));
const r = buttons(can).find(x => x.label === 'Refill');
if (!r || r.disabled !== false) fail('the Refill button changed: ' + JSON.stringify(buttons(can)));
testsPass();
"""), rounds = 30)

    // ---- ask-money: the same change in two files (the second often missed), and a rounding change ----
    val money = mapOf(
        "package.json" to PKG,
        "src/product.js" to """
// The product page's price label. Amounts are in cents.
function priceLabel(cents) { return 'EUR ' + (cents / 100).toFixed(2); }

module.exports = { priceLabel };
""".trimStart(),
        "src/invoice.js" to """
// Invoice lines. Amounts are in cents.
function amount(cents) { return (cents / 100).toFixed(2) + ' EUR'; }

function line(item) { return item.name + ' x' + item.qty + '  ' + amount(item.cents * item.qty); }

// VAT at 21%, in cents.
function vat(cents) { return Math.floor(cents * 21 / 100); }

module.exports = { amount, line, vat };
""".trimStart(),
        "test/money.test.js" to test("money", """
const { priceLabel } = require('../src/product.js');
const { amount } = require('../src/invoice.js');

test('small amounts', () => {
  assert.strictEqual(priceLabel(500), 'EUR 5.00');
  assert.strictEqual(amount(500), '5.00 EUR');
});
"""),
    )

    val askMoney = Task("ask-money",
        "Amounts of 1000 euros or more should have a comma between the thousands: EUR 1,250.00 on the product page and 1,250.00 EUR on invoices. " +
            "Keep the rest of each format as it is. Also, VAT on an invoice is rounded down to the cent today; a half cent or more should round up instead.",
        money, check("""
const { priceLabel } = R('src/product.js');
const inv = R('src/invoice.js');
if (priceLabel(125000) !== 'EUR 1,250.00') fail('the product page should show EUR 1,250.00, got ' + priceLabel(125000));
if (priceLabel(123456789) !== 'EUR 1,234,567.89') fail('the product page should show EUR 1,234,567.89, got ' + priceLabel(123456789));
if (priceLabel(99999) !== 'EUR 999.99') fail('the product page should show EUR 999.99, got ' + priceLabel(99999));
if (inv.amount(125000) !== '1,250.00 EUR') fail('an invoice should show 1,250.00 EUR, got ' + inv.amount(125000));
if (inv.amount(500) !== '5.00 EUR') fail('an invoice should still show 5.00 EUR, got ' + inv.amount(500));
const l = inv.line({ name: 'Desk', qty: 2, cents: 60000 });
if (l !== 'Desk x2  1,200.00 EUR') fail('an invoice line should read "Desk x2  1,200.00 EUR", got ' + l);
if (inv.vat(1050) !== 221) fail('VAT on 10.50 is 220.5 cents and should round up to 221, got ' + inv.vat(1050));
if (inv.vat(1049) !== 220) fail('VAT on 10.49 is 220.29 cents and should stay 220, got ' + inv.vat(1049));
if (inv.vat(1000) !== 210) fail('VAT on 10.00 should be 210, got ' + inv.vat(1000));
testsPass();
"""), rounds = 30)

    // ---- ask-form: a new rule with an exact message, a Submit state, and a rule that must stay as it is ----
    val form = mapOf(
        "package.json" to PKG,
        "src/validate.js" to """
// Checks for the sign-up form. Each returns an error message, or '' when the value is fine.
function email(v) { return v.includes('@') ? '' : 'Enter an email address'; }

function password(v) { return v.length >= 8 ? '' : 'Use at least 8 characters'; }

module.exports = { email, password };
""".trimStart(),
        "src/form.js" to """
const v = require('./validate.js');

// The form's state for the screen: an error under each field, and whether Submit can be pressed.
function state(values) {
  return {
    errors: { email: v.email(values.email || ''), password: v.password(values.password || '') },
    submitDisabled: false,
  };
}

module.exports = { state };
""".trimStart(),
        "test/form.test.js" to test("form", """
const v = require('../src/validate.js');

test('an email needs an @', () => {
  assert.strictEqual(v.email('ab'), 'Enter an email address');
  assert.strictEqual(v.email('a@b.co'), '');
});
"""),
    )

    val askForm = Task("ask-form",
        "An email without a dot after the @ (like a@b) is accepted. Reject it too, with the message \"Enter a full email address, like name@example.com\"; " +
            "the message for an email with no @ stays as it is. Keep the Submit button disabled until both fields are valid. Don't change the password rule.",
        form, check("""
const v = R('src/validate.js');
const { state } = R('src/form.js');
const MSG = 'Enter a full email address, like name@example.com';
if (v.email('a@b') !== MSG) fail('a@b should give "' + MSG + '", got "' + v.email('a@b') + '"');
if (v.email('a@b.co') !== '') fail('a@b.co should be valid, got "' + v.email('a@b.co') + '"');
if (v.email('ab') !== 'Enter an email address') fail('the message for an email with no @ changed: "' + v.email('ab') + '"');
if (v.password('1234567') !== 'Use at least 8 characters' || v.password('12345678') !== '') fail('the password rule changed');
let s = state({ email: 'a@b', password: '12345678' });
if (s.errors.email !== MSG) fail('the form should show the new email error, got "' + s.errors.email + '"');
if (s.submitDisabled !== true) fail('Submit should be disabled while the email is not valid');
s = state({ email: 'a@b.co', password: 'short' });
if (s.submitDisabled !== true) fail('Submit should be disabled while the password is not valid');
s = state({ email: 'a@b.co', password: '12345678' });
if (s.submitDisabled !== false) fail('Submit should be enabled when both fields are valid');
testsPass();
"""), rounds = 30)

    // ---- ask-plan (1.5.141): a price asked "per month" for a yearly option (easy to show the monthly or yearly price instead), its
    // place under the monthly line, and a rule that leaves some plans out ----
    val plans = mapOf(
        "package.json" to PKG,
        "src/plans.js" to """
// The plans on sale. Prices are in cents.
const PLANS = [
  { name: 'Basic', monthly: 800 },
  { name: 'Pro', monthly: 2000 },
  { name: 'Team', monthly: 4500 },
];

module.exports = { PLANS };
""".trimStart(),
        "src/page.js" to """
const { PLANS } = require('./plans.js');

function money(cents) { return (cents / 100).toFixed(2); }

// The pricing page's lines, one per plan.
function lines(plans = PLANS) {
  return plans.map(p => p.name + ': ' + money(p.monthly) + '/month');
}

module.exports = { lines };
""".trimStart(),
        "test/page.test.js" to test("page", """
const { lines } = require('../src/page.js');

test('each plan has its monthly line', () => {
  assert.ok(lines().includes('Pro: 20.00/month'));
});
"""),
    )

    val askPlan = Task("ask-plan",
        "Add a yearly option to the plans: a year costs 10 months' price. Add yearlyPrice(plan) to src/plans.js, returning cents. " +
            "On the pricing page, show each plan's yearly option as what it works out to per month, with the yearly total in brackets, " +
            "like \"Pro: 16.67/month billed yearly (200.00/year)\", on the line right under that plan's monthly line. " +
            "Plans that cost less than 10.00 a month get no yearly option.",
        plans, check("""
const plans = R('src/plans.js');
const { lines } = R('src/page.js');
if (typeof plans.yearlyPrice !== 'function') fail('src/plans.js should export yearlyPrice(plan)');
const pro = plans.PLANS.find(p => p.name === 'Pro');
if (plans.yearlyPrice(pro) !== 20000) fail('a year of Pro should cost 10 months, 20000 cents, got ' + plans.yearlyPrice(pro));
const want = ['Basic: 8.00/month', 'Pro: 20.00/month', 'Pro: 16.67/month billed yearly (200.00/year)', 'Team: 45.00/month', 'Team: 37.50/month billed yearly (450.00/year)'];
const got = lines();
if (JSON.stringify(got) !== JSON.stringify(want)) fail('the pricing page should read:\n' + want.join('\n') + '\ngot:\n' + got.join('\n'));
testsPass();
"""), rounds = 30)

    val ALL: List<Task> by lazy { listOf(askReceipt, askCli, askRename, askGarden, askMoney, askForm, askPlan) }
}
