package dev.pocketide.app.bench

/**
 * The benchmark's task set (1.5.86). Each task is a small project, a request worded the way people write them on a phone, and a
 * check: a shell command run in the project afterwards (exit 0 = pass). The checks use Node, so they run anywhere Node is
 * installed (a Codespace, a laptop). The model's final answer is in .bench/answer.txt for the checks that need it.
 *
 * The set covers what PocketIDE must do as well as Copilot or Claude: fix a bug from a symptom, add a feature across files,
 * keep what exists, find code by name, answer a question without changing anything, run and report without changing anything.
 */
object BenchTasks {
    class Task(
        val name: String,
        val request: String,
        val files: Map<String, String>,
        /** Shell command run in the project after the run; exit 0 = pass. */
        val check: String,
        /** The task must leave every file as it was (questions and report-only requests). */
        val readOnly: Boolean = false,
        val rounds: Int = 24,
        /** Aider's scoring (1.5.115): when the check fails, one more attempt with its output shown, from the files as left. */
        val twoTries: Boolean = false,
        /** Files the check writes (hidden tests): left out of the project the second attempt starts from. */
        val hidden: List<String> = emptyList(),
        /** Asked in the Chat home: no project, read-only (ChatHome, 1.5.125). */
        val chatHome: Boolean = false,
        /** A scripted person (1.5.170, the trust suite): this message is sent while the run works, before model request number first (from 1). */
        val steer: Pair<Int, String>? = null,
    )

    private val cart = mapOf(
        "src/cart.js" to """
const { priceOf } = require('./catalog.js');
const { discountFor } = require('./discounts.js');

function total(items, code) {
  let sum = 0;
  for (const it of items) sum += priceOf(it.id) * it.qty;
  const d = discountFor(code);
  return Math.round(sum * 100) / 100;
}

module.exports = { total };
""".trimStart(),
        "src/catalog.js" to "const PRICES = { apple: 0.5, bread: 2.25, milk: 1.2 };\nfunction priceOf(id) { return PRICES[id] ?? 0; }\nmodule.exports = { priceOf };\n",
        "src/discounts.js" to "const CODES = { SAVE10: 0.10, HALF: 0.5 };\n/** The fraction taken off for a code (0.10 = 10% off), or 0. */\nfunction discountFor(code) { return CODES[(code || '').toUpperCase()] ?? 0; }\nmodule.exports = { discountFor };\n",
        "src/tax.js" to "const TAX_RATE = 0.08;\nfunction withTax(x) { return Math.round(x * (1 + TAX_RATE) * 100) / 100; }\nmodule.exports = { TAX_RATE, withTax };\n",
        "src/format.js" to "function money(x) { return '$' + x.toFixed(2); }\nmodule.exports = { money };\n",
        "README.md" to "# Shop\nA tiny cart. `node -e \"console.log(require('./src/cart.js').total([{id:'bread',qty:2}], 'SAVE10'))\"`\n",
    )

    private val page = mapOf(
        "index.html" to """
<!doctype html>
<html>
<head><meta charset="utf-8"><title>Hello</title><link rel="stylesheet" href="style.css"></head>
<body>
  <h1>Hello</h1>
  <button id="hi">Say hi</button>
  <p id="out"></p>
  <script src="app.js"></script>
</body>
</html>
""".trimStart(),
        "style.css" to "body { font-family: sans-serif; padding: 16px; }\n",
        "app.js" to "document.getElementById('hi').addEventListener('click', () => {\n  document.getElementById('out').textContent = 'Hi!';\n});\n",
    )

    private val world = mapOf(
        "game/world.js" to """
// The walkable area. Tiles are numbered from 0; the map is 192 tiles wide and 192 tall.
const WIDTH = 192, HEIGHT = 192;
const blocked = new Set(['10,10', '11,10']);

function inBounds(x, z) {
  return x >= 0 && z >= 0 && x < 64 && z < 64;
}

function canWalk(x, z) {
  return inBounds(x, z) && !blocked.has(x + ',' + z);
}

module.exports = { WIDTH, HEIGHT, canWalk };
""".trimStart(),
        "game/player.js" to "const { canWalk } = require('./world.js');\nfunction step(p, dx, dz) { const x = p.x + dx, z = p.z + dz; if (canWalk(x, z)) { p.x = x; p.z = z; } return p; }\nmodule.exports = { step };\n",
        "game/main.js" to "const { step } = require('./player.js');\nconst me = { x: 60, z: 60 };\nfor (let i = 0; i < 10; i++) step(me, 1, 0);\nconsole.log(me);\n",
    )

    val ALL: List<Task> = listOf(
        Task("fix-from-symptom", "The cart total ignores discount codes: SAVE10 should take 10% off but the total is unchanged. Fix it.", cart,
            check = "node -e \"const {total}=require('./src/cart.js'); const t=total([{id:'bread',qty:2}],'SAVE10'); if (Math.abs(t-4.05)>0.001) { console.error('got '+t); process.exit(1) } const n=total([{id:'bread',qty:2}],''); if (Math.abs(n-4.5)>0.001) { console.error('no code: got '+n); process.exit(1) }\""),
        Task("add-feature", "Add a function celsiusToFahrenheit to a new file convert.js and export it. 0 should give 32 and 100 should give 212.", emptyMap(),
            check = "node -e \"const c=require('./convert.js'); const f=typeof c==='function'?c:c.celsiusToFahrenheit; if (typeof f!=='function') process.exit(1); if (f(0)!==32||f(100)!==212||Math.abs(f(-40)+40)>1e-9) process.exit(1)\""),
        Task("fix-and-test", "Discount codes do nothing in the cart. Make total() apply them, then add test/cart.test.js that checks SAVE10 (10% off) and HALF (half price) and passes with node test/cart.test.js.", cart,
            check = "node -e \"const {total}=require('./src/cart.js'); if (Math.abs(total([{id:'milk',qty:5}],'half')-3)>0.001) process.exit(1)\" && test -f test/cart.test.js && node test/cart.test.js"),
        Task("keep-what-exists", "Add a dark mode button to the page that toggles a 'dark' class on the body. Keep the Say hi button working.", page,
            check = "grep -q 'id=\"hi\"' index.html && grep -q \"getElementById('hi')\" app.js && grep -qi 'dark' index.html && grep -q 'dark' style.css && grep -qi 'classList' app.js"),
        Task("remove-the-barrier", "The player can't walk past tile 63 in either direction, but the map is 192 tiles wide and tall. Remove that barrier so the whole map can be walked. Blocked tiles must stay blocked.", world,
            check = "node -e \"const w=require('./game/world.js'); if(!w.canWalk(100,150)||!w.canWalk(191,191)||w.canWalk(192,5)||w.canWalk(-1,5)||w.canWalk(10,10)) process.exit(1)\""),
        Task("answer-a-question", "Which file sets the tax rate, and what is it?", cart, readOnly = true, rounds = 12,
            check = "grep -qi 'tax.js' .bench/answer.txt && grep -Eq '0\\.08|8 ?%' .bench/answer.txt"),
        Task("report-only", "Run node -e \"console.log(require('./src/cart.js').total([{id:'bread',qty:2}], 'SAVE10'))\" and report what it prints.", cart, readOnly = true, rounds = 12,
            check = "grep -q '4.5' .bench/answer.txt"),
    )

    /**
     * The tasks to run: [suite] is standard (default), hard (HardBenchTasks), heldout (HeldOutBenchTasks: measure only, never
     * tune on them), realproject (RealProjectBenchTasks: a big generated game project) or all; [only] names tasks from any suite instead.
     * Unknown names fail loudly, so a typo can't make a run that tests nothing.
     *
     * 1.5.125: polyglot stays Aider's 34 Python exercises, so its scores still compare with the ones recorded since 1.5.113;
     * polyglot-js is the 49 JavaScript exercises and polyglot-all both (83). chat is the Chat home's questions (ChatBenchTasks).
     */
    fun select(suite: String?, only: Set<String>): List<Task> {
        val every = ALL + HardBenchTasks.ALL + HeldOutBenchTasks.ALL + RealProjectBenchTasks.ALL + PolyglotTasks.ALL + PolyglotTasks.JS + ChatBenchTasks.ALL + AskBenchTasks.ALL + CauseBenchTasks.ALL + HarbourBenchTasks.ALL
        // 1.5.161: the sealed suite is never part of "all" and its tasks are found by name only together with suite=sealed
        val sealed = suite?.trim()?.lowercase() == "sealed"
        // 1.5.166: the exam suites are sealed in the same way (ExamBenchTasks)
        val exam = when (suite?.trim()?.lowercase()) { "premise" -> ExamBenchTasks.PREMISE; "variety" -> ExamBenchTasks.VARIETY; else -> null }
        if (only.isNotEmpty()) {
            if (exam != null) return exam.filter { it.name in only }.also { require(it.size == only.size) { "no $suite task named ${(only - it.map { t -> t.name }.toSet()).joinToString()}" } }
            if (sealed) return SealedBenchTasks.ALL.filter { it.name in only }.also { require(it.size == only.size) { "no sealed task named ${(only - it.map { t -> t.name }.toSet()).joinToString()}" } }
            // 1.5.170: the trust suite's tasks are found by name, but the suite is never part of "all"
            val named = every + TrustBenchTasks.ALL
            val unknown = only - named.map { it.name }.toSet()
            require(unknown.isEmpty()) { "no benchmark task named ${unknown.joinToString()}" }
            return named.filter { it.name in only }
        }
        return when (suite?.trim()?.lowercase().orEmpty()) {
            "", "standard" -> ALL
            "hard" -> HardBenchTasks.ALL
            "heldout", "held-out" -> HeldOutBenchTasks.ALL
            "realproject", "real-project", "real" -> RealProjectBenchTasks.ALL
            "chat" -> ChatBenchTasks.ALL
            "asks" -> AskBenchTasks.ALL   // 1.5.138: tuning tasks for the ask checklist
            "cause" -> CauseBenchTasks.ALL   // 1.5.147: tuning tasks for CauseGate (fixes in the wrong place)
            "harbour" -> HarbourBenchTasks.ALL   // 1.5.151: the harbour trading game (D2), independent of rp-*; a development set since 1.5.159
            "sealed" -> SealedBenchTasks.ALL   // 1.5.161: run only on a final candidate (BENCHMARKS.md, "Sealed suite")
            "premise" -> ExamBenchTasks.PREMISE   // 1.5.166: exam for the premise check (BENCHMARKS.md)
            "variety" -> ExamBenchTasks.VARIETY   // 1.5.166: exam for the input variants
            "trust" -> TrustBenchTasks.ALL   // 1.5.170: does the app's end label tell the truth (BENCHMARKS.md, "The trust suite")
            "polyglot-js" -> PolyglotTasks.JS.also { require(it.isNotEmpty()) { NEED_JS } }
            "polyglot-all" -> (PolyglotTasks.ALL + PolyglotTasks.JS).also { require(PolyglotTasks.ALL.isNotEmpty() && PolyglotTasks.JS.isNotEmpty()) { NEED_JS } }
            "polyglot" -> PolyglotTasks.ALL.also { require(it.isNotEmpty()) { "set POCKETIDE_POLYGLOT_DIR to a checkout of github.com/Aider-AI/polyglot-benchmark" } }
            "all" -> every
            else -> throw IllegalArgumentException("POCKETIDE_BENCH_SUITE must be standard, hard, heldout, realproject, polyglot, polyglot-js, polyglot-all, chat, asks, cause, harbour, sealed, premise, variety or all, not '$suite'")
        }
    }

    private const val NEED_JS = "set POCKETIDE_POLYGLOT_DIR to a checkout of github.com/Aider-AI/polyglot-benchmark and POCKETIDE_POLYGLOT_JEST to a folder " +
        "where jest is installed (npm install jest@29.7.0 babel-jest@29.6.4 @babel/core@7.25.2 @exercism/babel-preset-javascript@0.2.1 core-js@3.37.1)"
}
