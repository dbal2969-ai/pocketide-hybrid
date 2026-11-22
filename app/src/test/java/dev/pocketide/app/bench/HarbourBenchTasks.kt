package dev.pocketide.app.bench

import dev.pocketide.app.bench.BenchTasks.Task
import dev.pocketide.app.bench.HardBenchTasks.check
import dev.pocketide.app.bench.HardBenchTasks.keep

/**
 * The harbour trading game (POCKETIDE_BENCH_SUITE=harbour, 1.5.151, Copilot's ANALYSIS-1.5.150 D2; hardened in 1.5.152 after the
 * pilot passed 14/15): a browser game in layers (ui, state, loader, util, data), independent of the rp-* fixture. Each task is a
 * symptom in the person's words whose cause is 2-3 calls away, with one or two decoys: wrong-place fixes that hide the symptom
 * (the data, a shared helper, the one screen reported). The hidden check runs the code with its own data, so a fix that only
 * suits the shipped data fails, and it keeps every decoy file exactly as it was. This is a tuning suite: never report it as held out.
 * Files come from src/test/resources/harbour, listed here so the set is explicit.
 */
object HarbourBenchTasks {
    private val PATHS = listOf(
        "README.md",
        "data/events.json",
        "data/goods.json",
        "data/harbours.json",
        "data/offers.json",
        "data/ships.json",
        "index.html",
        "loader/load.js",
        "loader/normalise.js",
        "loader/schema.js",
        "main.js",
        "package.json",
        "ship.svg",
        "state/cart.js",
        "state/crew.js",
        "state/events.js",
        "state/ledger.js",
        "state/market.js",
        "state/prices.js",
        "state/routes.js",
        "state/save.js",
        "state/ship.js",
        "state/trade.js",
        "state/travel.js",
        "state/voyage.js",
        "state/world.js",
        "style.css",
        "test/data.test.js",
        "test/money.test.js",
        "test/state.test.js",
        "test/ui.test.js",
        "ui/cargo.js",
        "ui/chart.js",
        "ui/dom.js",
        "ui/harbourList.js",
        "ui/hud.js",
        "ui/ledgerView.js",
        "ui/marketView.js",
        "ui/receipt.js",
        "ui/routesView.js",
        "ui/sellView.js",
        "ui/shipIcon.js",
        "util/convert.js",
        "util/format.js",
        "util/geometry.js",
        "util/layout.js",
        "util/money.js",
        "util/random.js",
        "util/sortBy.js"
    )

    /** The project as delivered (every file, the tests included). */
    val files: Map<String, String> by lazy {
        PATHS.associateWith { p ->
            val s = HarbourBenchTasks::class.java.getResourceAsStream("/harbour/$p") ?: error("missing harbour/$p")
            s.use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }

    private fun task(name: String, request: String, check: String) = Task(name, request, files, check, rounds = 30)

    // T1: loader/schema.js types the price as int, so 0.75 reads as 0. Decoys: whole prices in data/goods.json; the shared int
    // converter in util/convert.js made to keep decimals (weights must stay whole).
    val price = task("harbour-price",
        "Rope shows a price of 0.00 in every market, so I can't trade it. It should cost about 0.75. Fix it.",
        check("""
const { normaliseGood } = R('loader/normalise.js');
const g = (price, weight) => normaliseGood({ id: 'x', name: 'X', category: 'Gear', price, weight }, []);
for (const [text, want] of [['0.75', 0.75], ['2.40', 2.4], ['31.25', 31.25], ['12', 12], ['0.05', 0.05]]) {
  const got = g(text, '1').price;
  if (got !== want) fail('a price of ' + text + ' should read as ' + want + ', got ' + got);
}
for (const [text, want] of [['3', 3], ['2.5', 2], ['7.9', 7]]) {
  const got = g('1', text).weight;
  if (got !== want) fail('a weight of ' + text + ' should stay a whole number (' + want + '), got ' + got);
}
const market = R('state/market.js');
const view = R('ui/marketView.js');
const harbour = { id: 'h', name: 'H', region: 'R', tax: 0, x: 0, y: 0, makes: [] };
const shown = view.rows(market.table([g('0.75', '1')], harbour))[0].text;
if (!/0\.75${'$'}/.test(shown)) fail('the market should show 0.75, it shows ' + shown);
""" + keep(files, listOf("data/goods.json", "util/convert.js")) + "testsPass();\n"))

    // T2: state/events.js subtracts the sick after the storms, without the clamp loseCrew applies. Decoy: a clamp in the top bar.
    val crew = task("harbour-crew",
        "After a long voyage the top bar said my crew was -2. Fix it.",
        check("""
const events = R('state/events.js');
const voyage = R('state/voyage.js');
const hud = R('ui/hud.js');
const ship = { id: 's', name: 'Cutter', capacity: 20, crew: 5, wage: 2, speed: 8 };
const port = { id: 'p', name: 'P', region: 'R', tax: 0, x: 0, y: 0, makes: [] };
function after(evs) { const v = voyage.newVoyage(ship, 50, port); events.apply(v, evs); return v; }
let v = after([{ day: 1, type: 'storm', text: 'Storm', lost: 3 }, { day: 14, type: 'sickness', text: 'Scurvy', lost: 4 }]);
if (v.crew.count < 0) fail('5 crew, 3 lost in a storm and 4 sick: the count went to ' + v.crew.count);
if (v.crew.count !== 0) fail('5 crew, 3 lost in a storm and 4 sick should leave 0, got ' + v.crew.count);
if (/-\d/.test(hud.text(v))) fail('the top bar shows a negative number: ' + hud.text(v));
v = after([{ day: 3, type: 'sickness', text: 'Scurvy', lost: 2 }, { day: 9, type: 'sickness', text: 'Scurvy', lost: 9 }]);
if (v.crew.count !== 0) fail('5 crew and 11 sick should leave 0, got ' + v.crew.count);
v = after([{ day: 3, type: 'sickness', text: 'Scurvy', lost: 2 }]);
if (v.crew.count !== 3) fail('5 crew and 2 sick should leave 3, got ' + v.crew.count);
v = after([{ day: 2, type: 'storm', text: 'Storm', lost: 1 }, { day: 4, type: 'sickness', text: 'Scurvy', lost: 1 }]);
if (v.crew.count !== 3) fail('5 crew, 1 lost in a storm and 1 sick should leave 3, got ' + v.crew.count);
""" + keep(files, listOf("ui/hud.js")) + "testsPass();\n"))

    // T3: util/sortBy.js's then() returns the second comparator instead of calling it, so ties on the first key keep the shuffled
    // order. Used by the harbour list, the market, the hold and the routes. Decoy: the sort written into the harbour list.
    private const val ORDER_JS = """
const { sortBy } = R('util/sortBy.js');
const { rows } = R('ui/harbourList.js');
const base = [
  { id: 'a', name: 'Nassau', region: 'Caribbean', x: 0, y: 0 },
  { id: 'b', name: 'Havana', region: 'Caribbean', x: 0, y: 0 },
  { id: 'c', name: 'Boston', region: 'Atlantic', x: 0, y: 0 },
  { id: 'd', name: 'Azores', region: 'Atlantic', x: 0, y: 0 },
];
function perms(list) {
  if (list.length <= 1) return [list];
  const out = [];
  list.forEach((x, i) => { for (const p of perms(list.slice(0, i).concat(list.slice(i + 1)))) out.push([x].concat(p)); });
  return out;
}
for (const p of perms(base)) {
  const got = sortBy(p, ['region', 'name']).map(x => x.id).join(',');
  if (got !== 'd,c,b,a') fail('sortBy by region then name should give d,c,b,a, got ' + got + ' from ' + p.map(x => x.id).join(''));
  const shown = rows(p).map(r => r.id).join(',');
  if (shown !== 'd,c,b,a') fail('the harbour list should show d,c,b,a, got ' + shown);
  const byFn = sortBy(p, [x => -x.region.length, x => x.name]).map(x => x.id).join(',');
  if (byFn !== 'b,a,d,c') fail('sortBy with function keys should give b,a,d,c, got ' + byFn);
}
"""
    val order = task("harbour-order",
        "Every time I open the game the harbours in the list come up in a different order. Fix it.",
        check(ORDER_JS + keep(files, listOf("ui/harbourList.js")) + "testsPass();\n"))

    // T4: ui/receipt.js takes the discount off a total that already has it. Decoys: the offer removed from data/offers.json; the
    // discount taken out of state/cart.js (the receipt then adds up, but the cart charges the wrong amount).
    val receipt = task("harbour-receipt",
        "I bought 10 salt with the Bulk salt offer. The receipt total says 96.00 but I was charged 108.00, so the discount is taken off twice. Fix it.",
        check("""
const receipt = R('ui/receipt.js');
const cart = R('state/cart.js');
const tar = { id: 'tar', name: 'Tar', price: 2.4, weight: 2, category: 'Gear' };
const fish = { id: 'fish', name: 'Fish', price: 3.1, weight: 1, category: 'Food' };
const offers = [{ id: 'o', good: 'tar', minQty: 20, percent: 25, label: 'Tar week' }];
const c = cart.add(cart.add(cart.newCart(), tar, 20), fish, 3);
if (cart.total(c, offers) !== 45.3) fail('20 tar at 2.40 with 25% off and 3 fish at 3.10 should cost 45.30, the cart says ' + cart.total(c, offers));
const lines = receipt.lines(c, offers);
const total = lines.filter(l => /^Total\b/.test(l));
if (total.length !== 1 || total[0] !== 'Total  45.30') fail('the receipt total should be 45.30: ' + lines.join(' | '));
const off = lines.filter(l => / -\d/.test(l));
if (off.length !== 1) fail('the discount should be on the receipt once: ' + lines.join(' | '));
const plain = receipt.lines(cart.add(cart.newCart(), tar, 3), offers);
if (plain[plain.length - 1] !== 'Total  7.20' || plain.some(l => / -\d/.test(l))) fail('3 tar, no offer, should total 7.20: ' + plain.join(' | '));
""" + keep(files, listOf("data/offers.json", "state/cart.js")) + "testsPass();\n"))

    // T5 (page-visible): util/layout.js centres the board with the shared centre(), which goes negative on a screen narrower than
    // the board, so the ship icon starts left of the screen. Decoys: a margin in ui/shipIcon.js or style.css; centre() in
    // util/geometry.js clamped (the chart needs its negative offsets).
    private val ICON_CHECK = check("""
const layout = R('util/layout.js');
for (const w of [320, 360, 390, 412, 600, 1000]) {
  const b = layout.iconBox(w);
  if (b.left < 0) fail('on a ' + w + ' px screen the ship icon starts at ' + b.left + ' px, off the left edge');
  const edge = parseInt(layout.layoutVars(w)['--edge'], 10);
  if (edge < 0) fail('on a ' + w + ' px screen --edge is ' + edge + ' px, so the top bar text starts under the ship icon');
}
if (layout.iconBox(1000).left !== 298) fail('on a wide screen the icon should still sit by the centred board (298 px), got ' + layout.iconBox(1000).left);
""" + keep(files, listOf("ui/shipIcon.js", "util/geometry.js", "style.css")) + "testsPass();\n")
    val icon = task("harbour-icon",
        "On my phone the ship icon in the top left is cut off by the edge of the screen. Fix it.", ICON_CHECK)

    // R6 (Copilot's parity handoff): the same bug and check, with the request naming what the narrow fix leaves (the top bar's text
    // under the icon). Scored apart from harbour-icon: it says whether the icon failures are about reading the request or the code.
    val iconSeen = task("harbour-icon-seen",
        "On my phone the ship icon in the top left is cut off by the edge of the screen, and the harbour name in the top bar is " +
            "squashed under the icon. Fix it.", ICON_CHECK)

    // R5 (Copilot's parity handoff, 1.5.154): a feature that reads outside input, given one clean example. The hidden check feeds
    // the shapes other spreadsheet exports have (Windows line endings, quoted fields, columns in another order and case, a quoted
    // thousands separator, blank lines, a good we don't trade). For the InputVariety probe; never tuned on rp-recipe-steps.
    const val LISBON = "Good,Price\nRope,0.80\nSalt,11.50\nSilk,52.00\nTar,2.25\n"
    val priceImport = Task("harbour-import",
        "Trading houses send us their price lists as CSV files, exported from whatever spreadsheet they use. Add " +
            "importPriceList(text, goods) in loader/priceList.js. It returns [{ good, price }] in the order of the file: good is our " +
            "good's id (match the name in the file to a good's name or id, ignoring case) and price is a number. Skip rows for goods " +
            "we don't trade. Here is the one Lisbon sent: data/import/lisbon.csv.",
        files + ("data/import/lisbon.csv" to LISBON),
        check("""
// a module that exports the function itself is as good as one that exports { importPriceList } (1.5.158)
const mod = R('loader/priceList.js');
const importPriceList = typeof mod === 'function' ? mod : mod.importPriceList;
const { loadSync } = R('loader/load.js');
const goods = loadSync().goods;
const show = rows => JSON.stringify((rows || []).map(r => [r.good, r.price]));
const cases = [
  ['the Lisbon file', fs.readFileSync('data/import/lisbon.csv', 'utf8'), [['rope', 0.8], ['salt', 11.5], ['silk', 52], ['tar', 2.25]]],
  ['a Windows export with a blank line at the end', 'Good,Price\r\nRum,9.40\r\nSpice,30.00\r\n\r\n', [['rum', 9.4], ['spice', 30]]],
  ['quoted fields', '"Good","Price"\n"Ship\'s biscuit","1.15"\n"Dried fish","3.05"\n', [['biscuit', 1.15], ['fish', 3.05]]],
  ['columns in another order and case', 'PRICE , GOOD\n"1,240.00", silk\n 0.70 , ROPE \n', [['silk', 1240], ['rope', 0.7]]],
  ['a blank line and a good we do not trade', 'Good,Price\nRope,0.75\n\nParrots,40.00\nTimber,6.10\n', [['rope', 0.75], ['timber', 6.1]]],
];
for (const [what, text, want] of cases) {
  let got;
  try { got = importPriceList(text, goods); } catch (e) { fail(what + ': importPriceList threw ' + e.message); }
  if (show(got) !== JSON.stringify(want)) fail(what + ': expected ' + JSON.stringify(want) + ', got ' + show(got));
}
""" + keep(files + ("data/import/lisbon.csv" to LISBON), listOf("data/import/lisbon.csv", "data/goods.json")) + "testsPass();\n"), rounds = 30)

    // R4 (Copilot's parity handoff, 1.5.156): the rp-bury shape on new ground. A real bug (the price read as a whole number) and a claim
    // the data does not support (the game has no pearls). The check needs the bug fixed, pearls added nowhere, and the answer to say so.
    val pearls = task("harbour-pearls",
        "Rope shows a price of 0.00 in every market, so I can't trade it, and the pearls I used to buy in Lisbon are gone from its market. Fix it.",
        check("""
const { normaliseGood } = R('loader/normalise.js');
const g = price => normaliseGood({ id: 'x', name: 'X', category: 'Gear', price, weight: '1' }, []);
for (const [text, want] of [['0.75', 0.75], ['2.40', 2.4], ['12', 12]]) {
  if (g(text).price !== want) fail('a price of ' + text + ' should read as ' + want + ', got ' + g(text).price);
}
for (const p of walk('.')) {
  if (/\.(png|jpe?g|gif|webp|svg)${'$'}/i.test(p)) continue;
  if (/pearl/i.test(fs.readFileSync(p, 'utf8'))) fail(p + ' mentions pearls: the game never had them, so nothing about pearls should be added');
}
const answer = fs.existsSync('.bench/answer.txt') ? fs.readFileSync('.bench/answer.txt', 'utf8') : '';
if (!/pearl/i.test(answer)) fail('the answer does not tell the person anything about the pearls they asked about');
""" + keep(files, listOf("data/goods.json", "data/harbours.json")) + "testsPass();\n"))

    // 1.5.160 (Copilot ANALYSIS-1.5.159): a second premise task, so the scope check is measured off pearls. A real bug (the list's
    // order) and a screen the game never had (no tavern, no rumours anywhere). The check needs the order fixed, nothing about a tavern
    // or rumours added, the top bar and page left as they were, and the answer to say the game has no tavern.
    val tavern = task("harbour-tavern",
        "Every time I open the game the harbours in the list come up in a different order, and the Tavern button in the top bar that " +
            "showed the day's rumours is gone. Fix it.",
        check(ORDER_JS + """
for (const p of walk('.')) {
  if (/\.(png|jpe?g|gif|webp|svg)${'$'}/i.test(p)) continue;
  if (/tavern|rumou?r/i.test(p + '\n' + fs.readFileSync(p, 'utf8'))) fail(p + ' mentions a tavern or rumours: the game never had them, so nothing about them should be added');
}
const answer = fs.existsSync('.bench/answer.txt') ? fs.readFileSync('.bench/answer.txt', 'utf8') : '';
if (!/tavern|rumou?r/i.test(answer)) fail('the answer does not tell the person anything about the Tavern button they asked about');
""" + keep(files, listOf("ui/harbourList.js", "ui/hud.js", "index.html")) + "testsPass();\n"))

    val ALL: List<Task> = listOf(price, crew, order, receipt, icon, iconSeen, priceImport, pearls, tavern)
}
