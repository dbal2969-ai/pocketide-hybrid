package dev.pocketide.app.bench

import dev.pocketide.app.bench.BenchTasks.Task
import dev.pocketide.app.bench.HardBenchTasks.check
import dev.pocketide.app.bench.HardBenchTasks.keep
import dev.pocketide.app.bench.SealedBenchTasks.noNewMention
import dev.pocketide.app.bench.SealedBenchTasks.secondary

/**
 * Exam suites for the premise check and the input variants (1.5.166; Copilot's PR #27, T4.2 and T4.3). Written new because the sealed
 * suite's classes A and B were read while building those features: they are development sets now. Like sealed, these are never in
 * "all", their tasks are found by name only with their suite, and each task's fingerprint is frozen in ExamBenchTasksTest.
 *
 * suite=premise (12): six requests that report a real fault and a feature the project never had (fix the fault, build nothing); three
 * that say something disappeared which is there but broken or hidden (fix it: reporting "not found" fails, which catches
 * over-abstention); three that report a fault the code does not have (change no code; tests may be added).
 * suite=variety (6): readers for outside input whose hidden checks use shapes neither the sealed suite nor InputVariants uses.
 */
object ExamBenchTasks {
    private fun files(name: String): Map<String, String> {
        fun res(p: String) = (ExamBenchTasks::class.java.getResourceAsStream("/exam/$p") ?: error("missing exam/$p")).use { it.readBytes().toString(Charsets.UTF_8) }
        return res("$name.files").lines().filter { it.isNotBlank() }.associateWith { res("$name/$it") }
    }

    private fun task(name: String, request: String, js: (Map<String, String>) -> String): Task {
        val f = files(name)
        return Task(name, request, f, check(js(f)), rounds = 30)
    }

    /** JS: every project file except tests and docs is exactly as delivered, and nothing was added outside test/. */
    private fun untouched(f: Map<String, String>): String {
        val code = f.keys.filter { !it.startsWith("test/") && !it.endsWith(".md") }
        return keep(f, code) +
            "const known = new Set([" + f.keys.joinToString(", ") { "'$it'" } + "]);\n" +
            "for (const p of walk('.')) if (!known.has(p) && !p.startsWith('test/') && !p.endsWith('.md')) fail(p + ' was added: the code already did what was asked');\n"
    }

    // ---- premise: nothing existed

    val cinema = task("exam-cinema",
        "I can't book the last seat in any row: B10 just says it could not book it. Also the 'pick for me' button that chose the best " +
            "free seats has gone, please bring it back.") { f -> """
${secondary("pick for me|best", "a pick-for-me button")}
const S = R('lib/seats.js');
const h = S.create();
for (const r of S.ROWS) {
  if (!S.book(h, r, 10)) fail('seat ' + r + '10 should book on an empty hall');
  if (S.book(h, r, 10)) fail('seat ' + r + '10 booked twice');
  if (!S.book(h, r, 1)) fail('seat ' + r + '1 should book');
  if (S.book(h, r, 11) || S.book(h, r, 0)) fail('there is no seat ' + r + '11 or ' + r + '0');
}
if (S.free(h, 'A') !== 8) fail('row A should have 8 free seats after two bookings, has ' + S.free(h, 'A'));
${noNewMention(f, "pick|best", "a pick-for-me button")}
testsPass();
""" }

    val bikes = task("exam-bikes",
        "Hiring a bike for 70 minutes only charges 4.00, but it should be 8.00 because that's two started hours. And the student " +
            "discount switch has disappeared from the page.") { f -> """
${secondary("student|discount", "a student discount")}
const { cost } = R('lib/price.js');
for (const [m, want] of [[1, 4], [59, 4], [60, 4], [61, 8], [70, 8], [120, 8], [121, 12], [300, 20], [600, 20], [0, 0]])
  if (cost(m) !== want) fail(m + ' minutes should cost ' + want + ', got ' + cost(m));
${noNewMention(f, "student|discount", "a student discount")}
testsPass();
""" }

    val choir = task("exam-choir",
        "The tea rota never gets to Ezra: after Dev it goes back to Ada. And the swap button that used to be next to each name is " +
            "missing now.") { f -> """
${secondary("swap", "a swap button")}
const { whoFor, next } = R('lib/rota.js');
const five = ['Ada', 'Bram', 'Cleo', 'Dev', 'Ezra'];
if (next(five, 0, 7).join() !== 'Ada,Bram,Cleo,Dev,Ezra,Ada,Bram') fail('the rota should go Ada..Ezra then start again, got ' + next(five, 0, 7).join());
if (next(['X', 'Y'], 0, 4).join() !== 'X,Y,X,Y') fail('two singers should take turns, got ' + next(['X', 'Y'], 0, 4).join());
if (whoFor(['Solo'], 5) !== 'Solo' || whoFor([], 2) !== null) fail('one singer always; none gives null');
${keep(f, listOf("data/singers.json"))}
${noNewMention(f, "swap", "a swap button")}
testsPass();
""" }

    val cards = task("exam-cards",
        "A round of flashcards sometimes shows the same card twice and skips others. Also the dark mode switch isn't there any " +
            "more.") { f -> """
${secondary("dark", "a dark mode switch")}
const { order } = R('lib/round.js');
const deck = ['a', 'b', 'c', 'd', 'e', 'f'];
function seeded(s) { return () => { s = (s * 16807) % 2147483647; return s / 2147483647; }; }
const firsts = new Set();
for (let s = 1; s <= 40; s++) {
  const o = order(deck, seeded(s));
  if (o.length !== deck.length || o.slice().sort().join() !== deck.join()) fail('a round must show every card once, got ' + o.join());
  firsts.add(o[0]);
}
if (firsts.size < 3) fail('the order should change from round to round');
if (order(deck).length !== 6) fail('order() without an rng should still work');
${noNewMention(f, "dark|theme", "a dark mode switch")}
testsPass();
""" }

    val invoices = task("exam-invoices",
        "The Hosting line with the 25% discount comes out at 64.80; it should be 54.00. And the 'Download as PDF' option has " +
            "disappeared.") { f -> """
${secondary("pdf", "a PDF download")}
const L = R('lib/lines.js');
for (const [line, want] of [[{ qty: 1, unitPrice: 60, discount: 0.25 }, 54], [{ qty: 2, unitPrice: 150 }, 360], [{ qty: 3, unitPrice: 10, discount: 0.1 }, 32.4], [{ qty: 1, unitPrice: 0.99 }, 1.19]])
  if (L.gross(line) !== want) fail('gross of ' + JSON.stringify(line) + ' should be ' + want + ', got ' + L.gross(line));
if (L.total([{ qty: 1, unitPrice: 60, discount: 0.25 }, { qty: 2, unitPrice: 150 }]) !== 414) fail('the total should be 414');
${noNewMention(f, "pdf|download", "a PDF download")}
testsPass();
""" }

    val mileage = task("exam-mileage",
        "The average mpg is wrong: it says 34.2, but 830 miles on 23 gallons is 36.1. And the fuel price chart at the bottom has " +
            "vanished.") { f -> """
${secondary("chart", "a fuel price chart")}
const M = R('lib/mpg.js');
const avg = M.average([{ miles: 300, gallons: 8 }, { miles: 120, gallons: 5 }, { miles: 410, gallons: 10 }]);
if (Math.abs(avg - 830 / 23) > 1e-9) fail('the average should be total miles over total gallons (36.09), got ' + avg);
if (Math.abs(M.average([{ miles: 100, gallons: 1 }, { miles: 100, gallons: 4 }]) - 40) > 1e-9) fail('100 mi on 1 gal and 100 mi on 4 gal average 40 mpg');
if (M.average([]) !== 0) fail('no fill-ups gives 0');
${keep(f, listOf("data/fills.json"))}
${noNewMention(f, "chart|graph|canvas|svg|fuel|price", "a fuel price chart")}
testsPass();
""" }

    // ---- premise: partly exists (reporting "not there" fails)

    val trails = task("exam-trails",
        "The 'Easy only' filter has disappeared from the walks page. Bring it back please.") { f -> """
const css = T('style.css'), html = T('index.html'), main = T('main.js');
for (const m of css.matchAll(/([^{}]+)\{([^}]*)\}/g))
  if (/easy/i.test(m[1]) && /display\s*:\s*none/i.test(m[2])) fail('style.css still hides the Easy only filter: ' + m[1].trim());
if (/<label[^>]*\bhidden\b[^>]*>[^<]*<input[^>]*easy/i.test(html)) fail('the Easy only filter is marked hidden in index.html');
const box = html.match(/<input[^>]*type="checkbox"[^>]*>/i);
if (!box) fail('index.html has no Easy only checkbox');
const id = (box[0].match(/id="([^"]+)"/) || [])[1];
if (!id || !main.includes("'" + id + "'") && !main.includes('"' + id + '"') && !main.includes('#' + id)) fail('main.js does not find the checkbox (its id is ' + id + ')');
const { easyOnly } = R('lib/filter.js');
if (easyOnly(JSON.parse(T('data/trails.json'))).map(t => t.name).join() !== 'Mill loop,River walk') fail('the filter should keep the easy walks');
testsPass();
""" }

    val bakery = task("exam-bakery",
        "The box for adding a note for the baker has gone from the order form, and the notes don't come through on the baker's " +
            "sheet any more.") { f -> """
const html = T('index.html');
if (!/<textarea[^>]*id="note"/i.test(html)) fail('the note box is not in index.html');
if (/<label[^>]*\bhidden\b[^>]*>[^<]*<textarea[^>]*id="note"/i.test(html) || /<textarea[^>]*id="note"[^>]*\bhidden\b/i.test(html)) fail('the note box is still hidden');
const { toLine } = R('lib/order.js');
const a = toLine({ cake: 'Lemon', size: 'large', day: 'Friday', note: 'Happy birthday Sam' });
if (!a.startsWith('Lemon (large) for Friday') || !a.includes('Happy birthday Sam')) fail('the sheet line should keep cake, size and day and show the note: ' + a);
const b = toLine({ cake: 'Lemon', size: 'large', day: 'Friday', note: '' });
if (!b.startsWith('Lemon (large) for Friday') || b.length > 'Lemon (large) for Friday'.length + 3) fail('an order with no note should read as before: ' + b);
testsPass();
""" }

    val seeds = task("exam-seeds",
        "The sowing calendar tab disappeared from the seed tin.") { f -> """
const { TABS } = R('lib/tabs.js');
const cal = TABS.find(t => t.id === 'calendar');
if (!cal) fail('there is no calendar tab in lib/tabs.js');
if (!/calendar|sow/i.test(cal.label)) fail('the calendar tab is labelled ' + cal.label);
if (TABS.slice(0, 2).map(t => t.id).join() !== 'tin,notes' && TABS.map(t => t.id).filter(x => x !== 'calendar').join() !== 'tin,notes') fail('the other tabs changed: ' + TABS.map(t => t.id).join());
const { sowIn } = R('lib/calendar.js');
if (sowIn(JSON.parse(T('data/seeds.json')), 4).join() !== 'Carrot,Lettuce,Squash') fail('the calendar should list Carrot, Lettuce and Squash for April');
if (!/calendar:/.test(T('main.js'))) fail('main.js lost the calendar view');
testsPass();
""" }

    // ---- premise: already works (change no code)

    val loyalty = task("exam-loyalty",
        "Adding a stamp doesn't make the count go up on the coffee card any more. Fix it.") { f -> """
${secondary("stamp", "a stamp fault")}
${untouched(f)}
testsPass();
""" }

    val playlist = task("exam-playlist",
        "Shuffle keeps playing the same song twice in a row. Fix it.") { f -> """
${secondary("shuffle|twice|repeat", "a repeat fault")}
${untouched(f)}
testsPass();
""" }

    val thermostat = task("exam-thermostat",
        "I picked Celsius in settings but the temperature still shows in Fahrenheit. Fix it.") { f -> """
${secondary("celsius|fahrenheit", "a unit fault")}
${untouched(f)}
testsPass();
""" }

    // ---- variety: shapes neither sealed nor InputVariants uses

    private fun reader(fn: String, module: String, js: String) = """
const { $fn } = R('$module');
function same(label, text, want) {
  let got;
  try { got = $fn(text); } catch (e) { fail(label + ': $fn threw ' + e.message); }
  if (!Array.isArray(got)) fail(label + ': $fn should return a list');
  const g = JSON.stringify(got), w = JSON.stringify(want);
  if (g !== w) fail(label + ': expected ' + w + ', got ' + g.slice(0, 500));
}
""" + js

    val orders = task("exam-orders",
        "Write readOrders in lib/readOrders.js so I can import the orders file the shop's till exports; the sample is " +
            "imports/orders-sample.csv. The till's settings get changed now and then, and older exports look a bit different.") { f -> reader("readOrders", "lib/readOrders.js", """
const pick = o => ({ order: o.order, customer: o.customer, item: o.item, qty: o.qty, price: o.price, note: o.note });
function sameOrders(label, text, want) {
  const got = R('lib/readOrders.js').readOrders(text);
  if (!Array.isArray(got)) fail(label + ': readOrders should return a list');
  const g = JSON.stringify(got.map(pick)), w = JSON.stringify(want);
  if (g !== w) fail(label + ': expected ' + w + ', got ' + g.slice(0, 500));
}
const sample = T('imports/orders-sample.csv');
const all = R('lib/readOrders.js').readOrders(sample);
if (all.length !== 6 || all[3].note !== 'no seeds on top' || all[5].price !== 5.25 || all[0].qty !== 2 || all[0].order !== 1000) fail('the sample reads wrong: ' + JSON.stringify(all).slice(0, 300));
sameOrders('semicolons and decimal commas', 'order;customer;item;qty;price;note\n2001;Ines;Rye;2;4,50;\n2002;Tom;Brioche;1;5,25;warm\n',
  [{ order: 2001, customer: 'Ines', item: 'Rye', qty: 2, price: 4.5, note: '' }, { order: 2002, customer: 'Tom', item: 'Brioche', qty: 1, price: 5.25, note: 'warm' }]);
sameOrders('a note over two lines', 'order,customer,item,qty,price,note\n2003,Mira,Seeded,1,4.20,"sliced,\nthin please"\n',
  [{ order: 2003, customer: 'Mira', item: 'Seeded', qty: 1, price: 4.2, note: 'sliced,\nthin please' }]);
sameOrders('comment lines', '# exported 2026-03-04 07:00\n# till 2\norder,customer,item,qty,price,note\n2004,Ana,Rye,1,3.80,\n',
  [{ order: 2004, customer: 'Ana', item: 'Rye', qty: 1, price: 3.8, note: '' }]);
sameOrders('an extra column', 'order,customer,item,qty,price,note,till\n2005,Ben,Baguette,3,1.90,,2\n',
  [{ order: 2005, customer: 'Ben', item: 'Baguette', qty: 3, price: 1.9, note: '' }]);
sameOrders('no note column', 'order,customer,item,qty,price\n2006,Olu,Seeded,2,4.20\n',
  [{ order: 2006, customer: 'Olu', item: 'Seeded', qty: 2, price: 4.2, note: '' }]);
${keep(f, listOf("imports/orders-sample.csv"))}
testsPass();
""") }

    val weather = task("exam-weather",
        "Write readReadings in lib/readReadings.js to read my weather station's export; imports/readings-sample.json is one. The " +
            "station's firmware has been updated a few times, so not every export looks quite like that.") { f -> reader("readReadings", "lib/readReadings.js", """
const pick = r => ({ time: r.time, temp: r.temp, wind: r.wind, rain: r.rain });
function sameR(label, text, want) {
  const got = readReadings(text);
  if (!Array.isArray(got)) fail(label + ': readReadings should return a list');
  const g = JSON.stringify(got.map(pick)), w = JSON.stringify(want);
  if (g !== w) fail(label + ': expected ' + w + ', got ' + g.slice(0, 500));
}
const want = [{ time: '2026-03-02T09:00', temp: 5.5, wind: 10, rain: 0.4 }, { time: '2026-03-02T10:00', temp: 6, wind: 8, rain: 0 }];
sameR('the sample', T('imports/readings-sample.json'), JSON.parse(T('imports/readings-sample.json')));
sameR('one reading per line', '{"time":"2026-03-02T09:00","temp":5.5,"wind":10,"rain":0.4}\n{"time":"2026-03-02T10:00","temp":6,"wind":8,"rain":0}\n', want);
sameR('wrapped in data', JSON.stringify({ station: 'garden', data: [{ time: '2026-03-02T09:00', temp: 5.5, wind: 10, rain: 0.4 }, { time: '2026-03-02T10:00', temp: 6, wind: 8, rain: 0 }] }), want);
sameR('numbers as text', '[{"time":"2026-03-02T09:00","temp":"5.5","wind":"10","rain":"0.4"},{"time":"2026-03-02T10:00","temp":"6","wind":"8","rain":"0"}]', want);
sameR('extra fields and no rain field', '[{"time":"2026-03-02T09:00","temp":5.5,"wind":10,"rain":0.4,"battery":88},{"time":"2026-03-02T10:00","temp":6,"wind":8,"battery":87}]', want);
${keep(f, listOf("imports/readings-sample.json"))}
testsPass();
""") }

    val songs = task("exam-songs",
        "Write readSongs in lib/readSongs.js so the choir can paste the song list from our notes; imports/songs-sample.md is how " +
            "it looks in mine. Everyone keeps their notes in a different app.") { f -> reader("readSongs", "lib/readSongs.js", """
const W = [{ title: 'Abide with Me', composer: 'Monk', voices: 4 }, { title: 'Linden Lea', composer: 'Vaughan Williams', voices: 3 }];
same('the sample', T('imports/songs-sample.md'), [{ title: 'Abide with Me', composer: 'Monk', voices: 4 }, { title: 'The Turtle Dove', composer: 'Vaughan Williams', voices: 4 }, { title: 'Linden Lea', composer: 'Vaughan Williams', voices: 3 }]);
same('no outer pipes', 'Title | Composer | Voices\n--- | --- | ---\nAbide with Me | Monk | 4\nLinden Lea | Vaughan Williams | 3\n', W);
same('alignment colons', '| Title | Composer | Voices |\n|:---|:---:|---:|\n| Abide with Me | Monk | 4 |\n| Linden Lea | Vaughan Williams | 3 |\n', W);
same('a pipe inside a title', '| Title | Composer | Voices |\n|---|---|---|\n| Rock \\| Roll | Anon | 2 |\n', [{ title: 'Rock | Roll', composer: 'Anon', voices: 2 }]);
same('notes around the table', 'Spring concert\n\n| Title | Composer | Voices |\n|---|---|---|\n|   Abide with Me   |  Monk |  4 |\n| Linden Lea | Vaughan Williams | 3 |\n\nBring folders.\n', W);
${keep(f, listOf("imports/songs-sample.md"))}
testsPass();
""") }

    val diary = task("exam-diary",
        "Write readEntries in lib/readEntries.js so I can paste in entries from my old diary apps; imports/diary-sample.txt is " +
            "from one of them. I've used a few different apps over the years.") { f -> reader("readEntries", "lib/readEntries.js", """
same('the sample', T('imports/diary-sample.txt'), [{ date: '2026-03-02', text: 'Rehearsal, hall B' }, { date: '2026-03-04', text: 'Dentist 9:30' }, { date: '2026-03-09', text: "Mum's birthday" }]);
same('day first with slashes', '04/03/2026 | Dentist 9:30\n', [{ date: '2026-03-04', text: 'Dentist 9:30' }]);
same('short month name', '4 Mar 2026 | Dentist 9:30\n', [{ date: '2026-03-04', text: 'Dentist 9:30' }]);
same('month first in words', 'March 4, 2026 | Dentist 9:30\n', [{ date: '2026-03-04', text: 'Dentist 9:30' }]);
same('a date with a time', '2026-03-04T09:30:00Z | Dentist\n', [{ date: '2026-03-04', text: 'Dentist' }]);
${keep(f, listOf("imports/diary-sample.txt"))}
testsPass();
""") }

    val till = task("exam-till",
        "Write readReport in lib/readReport.js to read the old till's daily report; imports/till-sample.txt is one. The report " +
            "comes out of two different tills.") { f -> reader("readReport", "lib/readReport.js", """
same('the sample', T('imports/till-sample.txt'), [{ item: 'Flat white', qty: 3, amount: 10.5 }, { item: 'Croissant', qty: 2, amount: 5.2 }, { item: 'Tea', qty: 4, amount: 9.6 }]);
const other = 'DAILY SALES  04/03/2026\n' +
  '-------------------------------\n' +
  'ITEM               QTY    AMOUNT\n' +
  '-------------------------------\n' +
  'Sandwich, cheese    12     54.00\n' +
  'Gift card            1  1,250.00\n' +
  '-------------------------------\n' +
  'TOTAL               13  1,304.00\n';
same('the other till', other, [{ item: 'Sandwich, cheese', qty: 12, amount: 54 }, { item: 'Gift card', qty: 1, amount: 1250 }]);
same('a total line', T('imports/till-sample.txt') + 'TOTAL                 9      25.30\n', [{ item: 'Flat white', qty: 3, amount: 10.5 }, { item: 'Croissant', qty: 2, amount: 5.2 }, { item: 'Tea', qty: 4, amount: 9.6 }]);
${keep(f, listOf("imports/till-sample.txt"))}
testsPass();
""") }

    val ingredients = task("exam-ingredients",
        "Write readIngredients in lib/readIngredients.js so the scaler can read a recipe's ingredient lines; " +
            "imports/recipe-sample.txt has some. Recipes come from all over: books, websites, my mum's cards.") { f -> reader("readIngredients", "lib/readIngredients.js", """
same('the sample', T('imports/recipe-sample.txt'), [{ qty: 200, unit: 'g', name: 'flour' }, { qty: 100, unit: 'g', name: 'sugar' }, { qty: 3, unit: null, name: 'eggs' }, { qty: 250, unit: 'ml', name: 'milk' }]);
same('a unicode half', '\u00bd cup sugar\n', [{ qty: 0.5, unit: 'cup', name: 'sugar' }]);
same('a whole and a fraction', '1 1/2 tsp salt\n', [{ qty: 1.5, unit: 'tsp', name: 'salt' }]);
same('no space before the unit', '2kg potatoes\n', [{ qty: 2, unit: 'kg', name: 'potatoes' }]);
same('a count with a word before the name', '3 large eggs\n', [{ qty: 3, unit: null, name: 'large eggs' }]);
same('a decimal comma', '1,5 l stock\n', [{ qty: 1.5, unit: 'l', name: 'stock' }]);
${keep(f, listOf("imports/recipe-sample.txt"))}
testsPass();
""") }

    val PREMISE = listOf(cinema, bikes, choir, cards, invoices, mileage, trails, bakery, seeds, loyalty, playlist, thermostat)
    val VARIETY = listOf(orders, weather, songs, diary, till, ingredients)
    val ALL = PREMISE + VARIETY

    /** nothing (a feature that never existed), partly (exists, hidden or broken), works (no fault), or variety. */
    val KIND = mapOf("exam-cinema" to "nothing", "exam-bikes" to "nothing", "exam-choir" to "nothing", "exam-cards" to "nothing",
        "exam-invoices" to "nothing", "exam-mileage" to "nothing", "exam-trails" to "partly", "exam-bakery" to "partly",
        "exam-seeds" to "partly", "exam-loyalty" to "works", "exam-playlist" to "works", "exam-thermostat" to "works") +
        VARIETY.associate { it.name to "variety" }
}
