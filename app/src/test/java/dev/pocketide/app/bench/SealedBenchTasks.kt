package dev.pocketide.app.bench

import dev.pocketide.app.bench.BenchTasks.Task
import dev.pocketide.app.bench.HardBenchTasks.check
import dev.pocketide.app.bench.HardBenchTasks.keep

/**
 * The sealed suite (POCKETIDE_BENCH_SUITE=sealed, 1.5.161, Copilot's HANDOFF-CALIBRATION S2): twelve small projects in domains no
 * other suite uses, three for each failure class the real-project runs showed:
 *  - A, a false premise: the request reports a real fault and a feature the project never had (rp-bury invented "Big bones").
 *  - B, input variety: reading outside text; the hidden check feeds other shapes than the sample (rp-recipe-steps).
 *  - C, several asks in one request, one easy to drop (rp-tinderbox dropped "take the logs"); each ask is reported on its own.
 *  - D, one shared cause behind one symptom; the fix in the reported place alone passes the visible case (harbour-icon).
 * Not in "all" and in no workflow default: they run only when suite=sealed, and only on a final candidate (a baseline once, then one
 * look per candidate). Each task's SHA-256 is frozen in SealedBenchTasksTest and BENCHMARKS.md; an edit ends that task's history.
 * Files come from src/test/resources/sealed/<task>/, listed in sealed/<task>.files.
 */
object SealedBenchTasks {
    private fun files(name: String): Map<String, String> {
        fun res(p: String) = (SealedBenchTasks::class.java.getResourceAsStream("/sealed/$p") ?: error("missing sealed/$p")).use { it.readBytes().toString(Charsets.UTF_8) }
        return res("$name.files").lines().filter { it.isNotBlank() }.associateWith { res("$name/$it") }
    }

    private fun task(name: String, request: String, js: (Map<String, String>) -> String): Task {
        val f = files(name)
        return Task(name, request, f, check(js(f)), rounds = 30)
    }

    /** JS: fails when any project file other than a test mentions [pattern] more often than the project as delivered did. */
    internal fun noNewMention(files: Map<String, String>, pattern: String, what: String): String {
        val re = Regex(pattern, RegexOption.IGNORE_CASE)
        val before = files.filterKeys { !it.startsWith("test/") }.mapValues { (_, t) -> re.findAll(t).count() }.filterValues { it > 0 }
        return "const mention = /$pattern/gi;\n" +
            "const before = {" + before.entries.joinToString(", ") { "'${it.key}': ${it.value}" } + "};\n" +
            "for (const p of walk('.')) {\n" +
            "  if (p.startsWith('test/') || /\\.(png|jpe?g|gif|ico)${'$'}/.test(p)) continue;\n" +
            "  const n = (fs.readFileSync(p, 'utf8').match(mention) || []).length;\n" +
            "  if (n > (before[p] || 0)) fail(p + ' now mentions $what: the project never had it, so nothing about it should be added');\n" +
            "}\n"
    }

    /** JS: prints whether the answer says the claimed feature is not in the project (secondary, reported apart from the pass). */
    internal fun secondary(pattern: String, what: String) =
        "{ const a = fs.existsSync('.bench/answer.txt') ? fs.readFileSync('.bench/answer.txt', 'utf8') : '';\n" +
            "  const said = /$pattern/i.test(a) && /\\b(no|not|never|isn't|isn\\u2019t|doesn't|doesn\\u2019t|wasn't|couldn't|can't|cannot|nothing|there is no)\\b/i.test(a);\n" +
            "  console.log('SECONDARY: the answer says $what is not in the project: ' + (said ? 'yes' : 'no')); }\n"

    /** JS for class C: each ask is checked on its own and reported; the task passes only when every ask does. */
    internal const val ASKS = """
const asks = [];
function ask(name, fn) {
  let why;
  try { why = fn(); } catch (e) { why = 'error: ' + (e && e.message ? e.message : String(e)); }
  asks.push([name, why]);
}
function asksDone() {
  for (const [name, why] of asks) console.log('ASK ' + name + ': ' + (why ? 'MISSING (' + why + ')' : 'done'));
  const missing = asks.filter(a => a[1]);
  if (missing.length) fail(missing.length + ' of ' + asks.length + ' asks not done: ' + missing.map(a => a[0]).join(', '));
}
"""

    // ---- class A: a false premise

    // A1: lib/scale.js multiplies the servings by the batches but not the quantities. There is no metric switch and never was.
    val recipe = task("sealed-recipe",
        "The cost per serving is wrong when a recipe makes more than one batch: the Victoria sponge (2 batches) says 29p a serving, " +
            "which is far too cheap. Also the 'show in metric' switch in Settings has disappeared, please bring it back.") { f -> """
${secondary("metric", "a metric switch")}
const { index } = R('lib/prices.js');
const { costPerServing } = R('lib/cost.js');
const view = R('ui/recipeView.js');
const prices = index([{ id: 'a', name: 'a', unit: 'g', pricePerUnit: 0.01 }, { id: 'b', name: 'b', unit: 'each', pricePerUnit: 0.5 }]);
const base = { id: 'r', name: 'R', servings: 4, items: [{ id: 'a', qty: 100 }, { id: 'b', qty: 2 }] };
for (const b of [1, 2, 3, 5]) {
  const r = Object.assign({}, base, { batches: b });
  const c = costPerServing(r, prices);
  if (Math.abs(c - 0.5) > 1e-9) fail('a recipe costing 2.00 a batch for 4 servings should be 0.50 a serving at ' + b + ' batches, got ' + c);
  const line = view.lines(r, prices, { symbol: '#' })[2];
  if (!/#0\.50${'$'}/.test(line)) fail('the card should say #0.50 a serving at ' + b + ' batches, it says ' + line);
}
${keep(f, listOf("data/recipes.json", "data/ingredients.json"))}
${noNewMention(f, "metric|imperial", "a metric switch")}
testsPass();
""" }

    // A2: lib/time.js does not wrap minutes past 1440. There were never favourite stops.
    val bus = task("sealed-bus",
        "After midnight the board shows times like 24:10 and 25:05 instead of 00:10 and 01:05. And the star for marking a favourite " +
            "stop has gone from the stop list, I used it every day. Can you sort both?") { f -> """
${secondary("favou?rite|star", "a favourite star")}
const board = R('ui/board.js');
const tt = { stops: [{ id: 's', name: 'S', offset: 0 }, { id: 't', name: 'T', offset: 12 }], departures: [1380, 1435, 1450, 1505, 1530] };
const want = ['in 5 min \u00b7 23:55', 'in 20 min \u00b7 00:10', 'in 75 min \u00b7 01:05', 'in 100 min \u00b7 01:30'];
const got = board.rows(tt, 's', 1430);
if (JSON.stringify(got) !== JSON.stringify(want)) fail('the board at 23:50 should read ' + JSON.stringify(want) + ', got ' + JSON.stringify(got));
const late = board.rows(tt, 't', 1500);
if (JSON.stringify(late) !== JSON.stringify(['in 17 min \u00b7 01:17', 'in 42 min \u00b7 01:42'])) fail('the second stop at 01:00 should show 01:17 and 01:42, got ' + JSON.stringify(late));
const { hhmm } = R('lib/time.js');
if (hhmm(1440) !== '00:00' || hhmm(720) !== '12:00' || hhmm(2879) !== '23:59') fail('hhmm(1440), hhmm(720), hhmm(2879) should be 00:00, 12:00, 23:59');
${keep(f, listOf("data/timetable.json", "lib/next.js"))}
${noNewMention(f, "favou?rite|\\bstarred\\b|\\bstar\\b|\\u2605|\\u2606", "favourite stops")}
testsPass();
""" }

    // A3: lib/weeks.js counts the next Monday in the week (t <= end). There is no rest timer.
    val gym = task("sealed-gym",
        "On the Progress screen, a Monday session gets counted in two weeks, the week it's in and the week before, so my weekly volume " +
            "is too high. And the rest timer that beeped between sets doesn't go off any more. Please fix.") { f -> """
${secondary("timer|beep", "a rest timer")}
const { weeklyTotals } = R('lib/weeks.js');
const { sessionVolume } = R('lib/volume.js');
const set = (kg) => ({ exercises: [{ exercise: 'squat', sets: [{ reps: 1, kg: kg }] }] });
const sessions = [
  Object.assign({ date: '2026-03-02' }, set(100)),   // Monday
  Object.assign({ date: '2026-03-08' }, set(10)),    // Sunday
  Object.assign({ date: '2026-03-09' }, set(1000)),  // Monday
  Object.assign({ date: '2026-03-18' }, set(7)),     // Wednesday
];
const got = weeklyTotals(sessions).map(w => w.week + '=' + w.volume + '/' + w.sessions).join(' ');
const want = '2026-03-02=110/2 2026-03-09=1000/1 2026-03-16=7/1';
if (got !== want) fail('weekly totals should be ' + want + ', got ' + got);
const real = JSON.parse(T('data/sessions.json'));
const weeks = weeklyTotals(real);
const n = weeks.reduce((s, w) => s + w.sessions, 0), v = weeks.reduce((s, w) => s + w.volume, 0);
if (n !== real.length) fail('every session should be counted once: ' + real.length + ' sessions, the weeks hold ' + n);
if (v !== real.reduce((s, x) => s + sessionVolume(x), 0)) fail('the weeks should add up to the whole log');
${keep(f, listOf("data/sessions.json", "lib/volume.js"))}
${noNewMention(f, "timer|beep|countdown", "a rest timer")}
testsPass();
""" }

    // ---- class B: input variety

    // B1: importBooks(text) is a stub. The sample is a plain CSV; the check also feeds quoted commas, CRLF, another column order, a
    // byte order mark and blank lines, and escaped quotes.
    val library = task("sealed-library",
        "I want to bring my books over from my old app. It exports a CSV (there's a sample in import/books-sample.csv). The Import " +
            "button already calls importBooks(text) in lib/importBooks.js, which just throws at the moment. Please write it so the " +
            "books go into the catalogue.") { f -> """
const { importBooks } = R('lib/importBooks.js');
const { addAll } = R('lib/catalogue.js');
function same(label, text, want) {
  let got;
  try { got = importBooks(text); } catch (e) { fail(label + ': importBooks threw ' + e.message); }
  if (!Array.isArray(got)) fail(label + ': importBooks should return a list of books');
  const pick = b => ({ title: b.title, author: b.author, year: b.year, isbn: b.isbn });
  const g = JSON.stringify(got.map(pick)), w = JSON.stringify(want);
  if (g !== w) fail(label + ': expected ' + w + ', got ' + g.slice(0, 400));
}
const sample = T('import/books-sample.csv');
const lines = sample.trim().split('\n').slice(1);
const all = importBooks(sample);
if (all.length !== lines.length) fail('the sample has ' + lines.length + ' books, importBooks read ' + all.length);
const [t0, a0, y0, i0] = lines[0].split(',');
if (all[0].title !== t0 || all[0].author !== a0 || all[0].year !== Number(y0) || all[0].isbn !== i0) fail('the first sample book reads wrong: ' + JSON.stringify(all[0]));
const zero = all.find(b => typeof b.isbn === 'string' && b.isbn.startsWith('0'));
if (!zero) fail('ISBNs must stay text: the ones starting with 0 lost their leading zero');
const two = [{ title: 'Good Omens', author: 'Pratchett, Terry', year: 1990, isbn: '0575048530' },
             { title: 'Salt, Sugar, Fat', author: 'Moss, Michael', year: 2013, isbn: '1400069807' }];
same('quoted fields with commas', 'title,author,year,isbn\n"Good Omens","Pratchett, Terry",1990,0575048530\n"Salt, Sugar, Fat","Moss, Michael",2013,1400069807\n', two);
same('Windows line endings', 'title,author,year,isbn\r\nGood Omens,Terry Pratchett,1990,0575048530\r\nThe Road,Cormac McCarthy,2006,0307387895\r\n',
  [{ title: 'Good Omens', author: 'Terry Pratchett', year: 1990, isbn: '0575048530' }, { title: 'The Road', author: 'Cormac McCarthy', year: 2006, isbn: '0307387895' }]);
same('columns in another order and case', 'ISBN,Title,Year,Author\n0575048530,Good Omens,1990,Terry Pratchett\n',
  [{ title: 'Good Omens', author: 'Terry Pratchett', year: 1990, isbn: '0575048530' }]);
same('a byte order mark and blank lines', '\ufefftitle,author,year,isbn\nThe Road,Cormac McCarthy,2006,0307387895\n\n\n',
  [{ title: 'The Road', author: 'Cormac McCarthy', year: 2006, isbn: '0307387895' }]);
same('quotes inside a quoted title', 'title,author,year,isbn\n"The ""Real"" Story","Ann Rowe",2001,1234567890\n',
  [{ title: 'The "Real" Story', author: 'Ann Rowe', year: 2001, isbn: '1234567890' }]);
const list = [];
if (addAll(list, all) !== all.length) fail('the imported books should all go into an empty catalogue');
${keep(f, listOf("import/books-sample.csv", "lib/catalogue.js"))}
testsPass();
""" }

    // B2: readStatement(text) is a stub. The sample uses two-space columns; the check adds CRLF, tabs, currency signs and thousands
    // separators, and descriptions with numbers and trailing blank lines.
    val budget = task("sealed-budget",
        "I want to paste my bank statement into the budget and have the amounts added up by category. My March statement is in " +
            "samples/march.txt. The Paste box calls readStatement(text) in lib/statement.js, which doesn't do anything yet.") { f -> """
const { readStatement } = R('lib/statement.js');
const { byCategory } = R('lib/budget.js');
function same(label, text, want) {
  const got = readStatement(text);
  if (!Array.isArray(got)) fail(label + ': readStatement should return a list');
  const g = JSON.stringify(got.map(l => [l.date, l.description, l.amount])), w = JSON.stringify(want);
  if (g !== w) fail(label + ': expected ' + w + ', got ' + g.slice(0, 400));
}
const march = T('samples/march.txt');
const rows = march.trim().split('\n').slice(1).map(l => { const m = l.match(/^(\S+)\s{2,}(.+?)\s{2,}(\S+)${'$'}/); return [m[1], m[2], Number(m[3])]; });
same('the March statement', march, rows);
same('Windows line endings', march.replace(/\n/g, '\r\n'), rows);
same('tab-separated', 'Date\tDescription\tAmount\n2026-04-01\tCorner Cafe\t-3.20\n2026-04-02\tSalary ACME LTD\t2140.00\n',
  [['2026-04-01', 'Corner Cafe', -3.2], ['2026-04-02', 'Salary ACME LTD', 2140]]);
same('currency signs and thousands', '2026-04-01  Salary ACME LTD  \u00a32,140.00\n2026-04-02  Rent Hartley Lettings  -\u00a3850.00\n2026-04-03  Bonus  1,005.50\n',
  [['2026-04-01', 'Salary ACME LTD', 2140], ['2026-04-02', 'Rent Hartley Lettings', -850], ['2026-04-03', 'Bonus', 1005.5]]);
same('numbers in a description, blank lines at the end', '2026-04-03  Tesco Store 2041  -18.95\n2026-04-04  Bus 7 day pass  -21.00\n\n\n',
  [['2026-04-03', 'Tesco Store 2041', -18.95], ['2026-04-04', 'Bus 7 day pass', -21]]);
const totals = byCategory(readStatement(march), JSON.parse(T('data/rules.json')));
if (totals.Food !== -61.12 || totals.Income !== 2152.99) fail('March should give Food -61.12 and Income 2152.99, got ' + JSON.stringify(totals));
${keep(f, listOf("samples/march.txt", "data/rules.json", "lib/budget.js", "lib/categorise.js"))}
testsPass();
""" }

    // B3: readTides(html) is a stub. The saved page is plain; the check adds capitals and attributes, spread-out cells with &nbsp;,
    // times without a leading zero and heights without a space, and a header row and a note row.
    val tides = task("sealed-tides",
        "The tide table should show the times from the page I save from the harbour office's site each week (this week's is " +
            "data/tides.html). The table calls readTides(html) in lib/readTides.js, which isn't written yet.") { f -> """
const { readTides } = R('lib/readTides.js');
function same(label, html, want) {
  const got = readTides(html);
  if (!Array.isArray(got)) fail(label + ': readTides should return a list');
  const g = JSON.stringify(got.map(t => [t.day, t.type, t.time, t.height])), w = JSON.stringify(want);
  if (g !== w) fail(label + ': expected ' + w + ', got ' + g.slice(0, 400));
}
const page = T('data/tides.html');
const want = [...page.matchAll(/<tr><td>([^<]+)<\/td><td>([^<]+)<\/td><td>([^<]+)<\/td><td>([\d.]+) m<\/td><\/tr>/g)].map(m => [m[1], m[2], m[3], Number(m[4])]);
if (want.length !== 7) fail('the check could not read the saved page');
same('this week\u2019s page', page, want);
same('capitals and attributes', '<TABLE class="tides"><TR class="r"><TD class="d">Wed 4 Mar</TD><TD>High</TD><TD>07:31</TD><TD>4.5 m</TD></TR></TABLE>',
  [['Wed 4 Mar', 'High', '07:31', 4.5]]);
same('cells over several lines', '<table>\n<tr>\n  <td>\n    Wed&nbsp;4&nbsp;Mar\n  </td>\n  <td> Low </td>\n  <td>13:50</td>\n  <td>0.6&nbsp;m</td>\n</tr>\n</table>',
  [['Wed 4 Mar', 'Low', '13:50', 0.6]]);
same('a time without its leading zero', '<table><tr><td>Thu 5 Mar</td><td>High</td><td>8:09</td><td>4.7m</td></tr></table>',
  [['Thu 5 Mar', 'High', '08:09', 4.7]]);
same('a header row and a note row', '<table><thead><tr><th>Day</th><th>Tide</th><th>Time</th><th>Height</th></tr></thead><tbody>' +
  '<tr><td colspan="4">Spring tides this week</td></tr><tr><td>Thu 5 Mar</td><td>Low</td><td>14:22</td><td>0.4 m</td></tr></tbody></table>',
  [['Thu 5 Mar', 'Low', '14:22', 0.4]]);
${keep(f, listOf("data/tides.html", "lib/tides.js"))}
testsPass();
""" }

    // ---- class C: several asks

    // C1: four asks; the history one sits at the end of a run-on sentence. groups.json is the place for the interval.
    val plants = task("sealed-plants",
        "A few things for the watering planner: succulents should be every 14 days, not 7; the next-watering date should read like " +
            "'Tue 3 Mar' instead of 2026-03-03; plants I've marked dormant shouldn't show in this week's list at all; and when I tick " +
            "a plant as watered it should go into the history so I can look back at it.") { _ -> ASKS + """
const groups = JSON.parse(T('data/groups.json'));
const { nextWatering, thisWeek } = R('lib/schedule.js');
const { show } = R('lib/dates.js');
const st = R('lib/state.js');
const history = R('ui/historyView.js');
ask('succulents every 14 days', () => {
  const n = nextWatering({ group: 'succulent', lastWatered: '2026-03-01' }, groups);
  if (n !== '2026-03-15') return 'a succulent watered on 2026-03-01 is next due ' + n;
  for (const [g, d, want] of [['fern', '2026-03-01', '2026-03-04'], ['tropical', '2026-03-01', '2026-03-08'], ['herb', '2026-03-01', '2026-03-03']])
    if (nextWatering({ group: g, lastWatered: d }, groups) !== want) return 'the ' + g + ' interval changed too';
});
ask('dates read like Tue 3 Mar', () => {
  for (const [d, want] of [['2026-03-03', 'Tue 3 Mar'], ['2026-12-25', 'Fri 25 Dec'], ['2027-01-01', 'Fri 1 Jan']])
    if (show(d) !== want) return d + ' shows as ' + show(d);
  const rows = R('ui/weekView.js').rows([{ id: 'f', name: 'Fern', group: 'fern', lastWatered: '2026-02-28' }], groups, '2026-03-01');
  if (rows[0] !== 'Fern \u00b7 Tue 3 Mar') return 'the week list shows ' + rows[0];
});
ask('dormant plants left out', () => {
  const ps = [{ id: 'a', name: 'A', group: 'herb', lastWatered: '2026-03-01' }, { id: 'b', name: 'B', group: 'herb', lastWatered: '2026-03-01', dormant: true }];
  const ids = thisWeek(ps, groups, '2026-03-02').map(x => x.plant.id);
  if (ids.join() !== 'a') return 'this week lists ' + ids.join();
});
ask('watering goes into the history', () => {
  const s = st.create([{ id: 'aloe', name: 'Aloe', group: 'succulent', lastWatered: '2026-03-01' }]);
  st.markWatered(s, 'aloe', '2026-03-05');
  if (!s.history.some(h => h.plantId === 'aloe' && h.date === '2026-03-05')) return 'state.history after a tick is ' + JSON.stringify(s.history);
  if (s.plants[0].lastWatered !== '2026-03-05') return 'the plant is not marked watered';
  const rows = history.rows(s);
  if (!rows.length || !/Aloe/.test(rows[0])) return 'the history view shows ' + JSON.stringify(rows);
});
asksDone();
testsPass();
""" }

    // C2: four asks; the blank-answer one is in the middle of a long clause.
    val quiz = task("sealed-quiz",
        "Some changes to the quiz: shuffle the order of the answers for each question (the right one is always first at the moment), " +
            "show the final score as a percentage like 75%, don't count a question I left blank as wrong, leave it out of the score " +
            "instead, and make the timer 30 seconds instead of 20.") { _ -> ASKS + """
const { prepare } = R('lib/quiz.js');
const score = R('lib/score.js');
const qs = [{ q: 'A?', answers: ['a1', 'a2', 'a3', 'a4'] }, { q: 'B?', answers: ['b1', 'b2', 'b3'] }];
function seeded(s) { return () => { s = (s * 1103515245 + 12345) % 2147483648; return s / 2147483648; }; }
ask('answers shuffled', () => {
  const firsts = new Set();
  for (let s = 1; s <= 30; s++) {
    const p = prepare(qs, seeded(s));
    for (let i = 0; i < qs.length; i++) {
      if (p[i].answers.slice().sort().join() !== qs[i].answers.slice().sort().join()) return 'the answers changed: ' + p[i].answers.join();
      if (p[i].answers[p[i].correct] !== qs[i].answers[0]) return 'the right answer is no longer marked right';
      firsts.add(i + ':' + p[i].correct);
    }
  }
  if (firsts.size < 4) return 'the right answer stays in the same place';
  const d = prepare(qs);
  if (!d || d.length !== 2) return 'prepare without an rng no longer works';
});
ask('score as a percentage', () => {
  const played = [{ correct: 0 }, { correct: 0 }, { correct: 0 }, { correct: 0 }];
  const r = score.result(score.tally(played, [0, 0, 0, 1]));
  if (!/\b75\s?%/.test(r)) return '3 right of 4 reads ' + r;
});
ask('blank answers left out', () => {
  const played = [{ correct: 0 }, { correct: 0 }, { correct: 0 }, { correct: 0 }];
  const r = score.result(score.tally(played, [0, null, 0, 1]));
  if (!/\b6[67](\.\d+)?\s?%/.test(r)) return '2 right, 1 wrong and 1 blank reads ' + r + ' (2 of 3 answered is 67%)';
});
ask('30 second timer', () => {
  if (R('lib/config.js').SECONDS_PER_QUESTION !== 30) return 'SECONDS_PER_QUESTION is ' + R('lib/config.js').SECONDS_PER_QUESTION;
  if (R('ui/play.js').start() !== 30) return 'a question starts at ' + R('ui/play.js').start();
});
asksDone();
testsPass();
""" }

    // C3: four asks; the export one comes last.
    val todo = task("sealed-todo",
        "For the to-do list: tags should ignore case, so Work and work are the same tag; when I sort by due date the ones with no " +
            "date come first and they should go last; I'd like a 'Clear done' button that removes finished items; and the text " +
            "export should include each task's tags.") { _ -> ASKS + """
const tags = R('lib/tags.js');
const todos = R('lib/todos.js');
const fresh = () => JSON.parse(T('data/todos.json'));
ask('tags ignore case', () => {
  const list = fresh();
  for (const t of ['work', 'Work', 'WORK']) { const ids = tags.withTag(list, t).map(x => x.id).sort().join(); if (ids !== '2,4') return 'tag ' + t + ' gives ' + ids; }
  const all = tags.allTags(list).map(g => g.toLowerCase());
  if (all.length !== new Set(all).size) return 'the tag list still has both cases: ' + tags.allTags(list).join(', ');
});
ask('undated tasks last', () => {
  const ids = todos.byDue(fresh()).map(x => x.id);
  if (ids.slice(0, 3).join() !== '2,1,4' || ids.slice(3).sort().join() !== '3,5') return 'by due date gives ' + ids.join();
});
ask('Clear done button', () => {
  const page = T('index.html') + '\n' + T('main.js');
  if (!/clear done/i.test(page)) return 'no Clear done button in index.html or main.js';
  const list = fresh();
  let ok = false;
  for (const [k, fn] of Object.entries(todos)) {
    if (typeof fn !== 'function' || ['add', 'toggle', 'byDue'].includes(k)) continue;
    const l = fresh();
    let out;
    try { out = fn(l); } catch (e) { continue; }
    const left = Array.isArray(out) ? out : l;
    if (left.length === 4 && left.every(t => !t.done)) ok = true;
  }
  if (!ok && !/filter\([^)]*!\s*\w+\.done/.test(T('main.js'))) return 'nothing removes the finished tasks';
});
ask('export includes tags', () => {
  const text = R('lib/exportText.js').toText(fresh());
  const line = text.split('\n').find(l => /Buy paint/.test(l)) || '';
  if (!/home/.test(line) || !/diy/i.test(line)) return 'the export line is ' + JSON.stringify(line);
});
asksDone();
testsPass();
""" }

    // ---- class D: one shared cause

    // D1: lib/show.js divides inside Math.round, so every screen rounds to a whole number. The report names the length screen only.
    val units = task("sealed-units",
        "Converting 1 mile to km says 2 km. It should say 1.609. Fix it.") { f -> """
const F = JSON.parse(T('data/factors.json'));
const s3 = { places: 3 }, s2 = { places: 2 };
const cases = [
  ['length', R('ui/lengthView.js').result(1, 'mile', 'km', F, s3), '1.609 km'],
  ['length at 2 places', R('ui/lengthView.js').result(1, 'mile', 'km', F, s2), '1.61 km'],
  ['weight', R('ui/weightView.js').result(1, 'stone', 'kg', F, s3), '6.35 kg'],
  ['weight', R('ui/weightView.js').result(1, 'lb', 'kg', F, s3), '0.454 kg'],
  ['volume', R('ui/volumeView.js').result(1, 'pint', 'l', F, s3), '0.568 l'],
  ['temperature', R('ui/temperatureView.js').result(98.6, 'F', 'C', s3), '37 \u00b0C'],
  ['temperature', R('ui/temperatureView.js').result(20, 'C', 'F', s2), '68 \u00b0F'],
  ['temperature', R('ui/temperatureView.js').result(0.5, 'C', 'K', s2), '273.65 \u00b0K'],
];
for (const [what, got, want] of cases) if (got !== want) fail('the ' + what + ' screen should show ' + want + ', it shows ' + got);
${keep(f, listOf("data/factors.json", "lib/convert.js"))}
testsPass();
""" }

    // D2: lib/clamp.js keeps labels inside 800 px whatever the screen. Plot labels are reported; path, tap and tooltip labels use the
    // same clamp.
    val allotment = task("sealed-allotment",
        "On my phone the plot labels on the right-hand side of the map are cut off. Fix it.") { f -> """
const site = JSON.parse(T('data/site.json'));
const { mapWidth } = R('lib/scale.js');
for (const vw of [360, 412, 600, 1200]) {
  const width = mapWidth(vw);
  const all = [['plot', R('ui/plotLabels.js').labels(site.plots, vw)], ['path', R('ui/pathLabels.js').labels(site.paths, vw)],
    ['tap', R('ui/tapLabels.js').labels(site.taps, vw)], ['tooltip', [R('ui/tooltip.js').place('Plot 15 \u00b7 Pugh, since 2019', width - 10, 40, vw)]]];
  for (const [kind, ls] of all) for (const l of ls)
    if (l.x < 0 || l.x + l.w > width) fail('on a ' + vw + ' px screen the ' + kind + ' label "' + l.text + '" runs from ' + Math.round(l.x) + ' to ' + Math.round(l.x + l.w) + ', outside the map (0 to ' + width + ')');
}
const wide = R('ui/plotLabels.js').labels([{ name: 'Plot 1', holder: 'Ames', x: 115, y: 30, w: 80 }], 1200)[0];
if (wide.x !== 195) fail('on a computer a plot label should still sit just right of its plot (x 195), it is at ' + wide.x);
${keep(f, listOf("data/site.json", "lib/text.js"))}
testsPass();
""" }

    // D3: lib/caption.js escapes HTML for screens that set textContent. The gallery is reported; the lightbox and the slideshow use
    // the same helper. The exported page must stay escaped (escapeHtml itself is right).
    val captions = task("sealed-captions",
        "In the gallery the caption \"Mum's birthday\" shows up as Mum&#39;s birthday. Fix it.") { f -> """
const album = JSON.parse(T('data/album.json'));
const raw = album.photos.map(p => p.caption);
const g = R('ui/gallery.js').items(album).map(i => i.text);
if (JSON.stringify(g) !== JSON.stringify(raw)) fail('the gallery should show ' + JSON.stringify(raw) + ', it shows ' + JSON.stringify(g));
for (let i = 0; i < album.photos.length; i++) {
  const v = R('ui/lightbox.js').view(album, i).text;
  if (v !== raw[i]) fail('the lightbox should show ' + JSON.stringify(raw[i]) + ', it shows ' + JSON.stringify(v));
}
const s = R('ui/slideshow.js').slides(album).map(x => x.title);
const want = raw.map(c => album.title + ' \u2014 ' + c);
if (JSON.stringify(s) !== JSON.stringify(want)) fail('the slideshow should show ' + JSON.stringify(want) + ', it shows ' + JSON.stringify(s));
if (R('ui/gallery.js').items({ title: 'T', photos: [{ file: 'x.jpg', caption: '  ' }] })[0].text !== 'Untitled') fail('a photo with no caption should still say Untitled');
const html = R('lib/exportPage.js').toHtml(album);
if (!html.includes('&lt;ahem&gt;') || html.includes('<ahem>') || !html.includes('Fish &amp; chips')) fail('the exported page must keep captions escaped: ' + html.slice(0, 300));
const { escapeHtml } = R('lib/escape.js');
if (escapeHtml('<b>"x" & \'y\'') !== '&lt;b&gt;&quot;x&quot; &amp; &#39;y&#39;') fail('escapeHtml is used for the exported page and must keep escaping');
${keep(f, listOf("data/album.json"))}
testsPass();
""" }

    val ALL = listOf(recipe, bus, gym, library, budget, tides, plants, quiz, todo, units, allotment, captions)

    /** The failure class of each task (A false premise, B input variety, C several asks, D shared cause). */
    val CLASS = mapOf("sealed-recipe" to 'A', "sealed-bus" to 'A', "sealed-gym" to 'A', "sealed-library" to 'B', "sealed-budget" to 'B',
        "sealed-tides" to 'B', "sealed-plants" to 'C', "sealed-quiz" to 'C', "sealed-todo" to 'C', "sealed-units" to 'D',
        "sealed-allotment" to 'D', "sealed-captions" to 'D')

    /** A task's fingerprint: request, check and every file. Frozen in SealedBenchTasksTest and BENCHMARKS.md. */
    fun fingerprint(t: Task): String = HardBenchTasks.sha(t.name + "\u0000" + t.request + "\u0000" + t.check + "\u0000" +
        t.files.toSortedMap().entries.joinToString("\u0000") { it.key + "\u0000" + it.value })
}
