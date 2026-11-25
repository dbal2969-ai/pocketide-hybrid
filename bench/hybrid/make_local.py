"""The local-model benchmark: twenty tiny tasks for Gemini Nano alone (Private mode), grouped by skill, to tune how PocketIDE drives it.

Gemini Nano's weights cannot be changed (AICore manages the model), so what is tuned is everything around it: its instructions,
examples, tools, settings and the app's repairs. Each task is small enough for the phone and checks one skill, so a run shows which
skill fails. Every task that changes code has a reference fix; check_hard.py proves `node test.js` fails as given and passes with it.

Usage: python3 bench/hybrid/make_local.py bench/hybrid/local
"""
import os, shutil, sys, zipfile

OUT = sys.argv[1]
T, S = {}, {}

def test(cases_js):
    return ("let bad = 0;\nfunction need(c, msg) { if (!c) { console.error(msg); bad++; } }\n" + cases_js +
            "if (bad) process.exit(1);\nconsole.log('ok');\n")

FAKE_DOM = r'''const handlers = {};
global.document = { addEventListener(t, f) { (handlers[t] = handlers[t] || []).push(f); }, getElementById() { return { addEventListener() {} }; } };
global.moves = [];
global.move = d => moves.push(d);
function fire(t, e) { for (const f of handlers[t] || []) f(Object.assign({ preventDefault() {} }, e)); }
'''

# ---------------- find and explain (no change) ----------------
T["l01-find-file"] = {
 "game.js": "const { MAX_LIVES } = require('./rules.js');\nlet lives = MAX_LIVES;\nmodule.exports = { lives };\n",
 "rules.js": "const MAX_LIVES = 3;\nconst SPEED = 150;\nmodule.exports = { MAX_LIVES, SPEED };\n",
 "ui.js": "function show(n) { return 'Lives: ' + n; }\nmodule.exports = { show };\n",
}
T["l02-explain-function"] = {
 "snake.js": "class Snake {\n  constructor() { this.body = [{ x: 1, y: 1 }]; }\n  step() {\n    const head = { x: this.body[0].x + 1, y: this.body[0].y };\n    this.body.unshift(head);\n    return this.body.length;\n  }\n}\nmodule.exports = { Snake };\n",
}
T["l03-explain-no-change"] = {
 "csv.js": "// Splits one line of CSV into fields. Commas inside double quotes do not split.\nfunction parse(line) {\n  const out = []; let cur = ''; let q = false;\n  for (const ch of line) {\n    if (ch === '\"') q = !q;\n    else if (ch === ',' && !q) { out.push(cur); cur = ''; }\n    else cur += ch;\n  }\n  out.push(cur);\n  return out;\n}\nmodule.exports = { parse };\n",
}
T["l04-report-test-result"] = {
 "app.js": "function add(a, b) { return a + b; }\nmodule.exports = { add };\n",
 "test.js": test("const { add } = require('./app.js');\nneed(add(2, 3) === 5, 'add');\n"),
}

# ---------------- fix one line ----------------
def fix(name, files, sol):
    T[name] = files; S[name] = sol
fix("l05-fix-operator",
    {"app.js": "function add(a, b) {\n  return a - b;\n}\nmodule.exports = { add };\n",
     "test.js": test("const { add } = require('./app.js');\nneed(add(2, 3) === 5, 'add(2, 3) gave ' + add(2, 3));\n")},
    {"app.js": "function add(a, b) {\n  return a + b;\n}\nmodule.exports = { add };\n"})
fix("l06-fix-text",
    {"greet.js": "function greet(name) {\n  return 'Helo, ' + name + '!';\n}\nmodule.exports = { greet };\n",
     "test.js": test("const { greet } = require('./greet.js');\nneed(greet('Ana') === 'Hello, Ana!', 'greet gave ' + greet('Ana'));\n")},
    {"greet.js": "function greet(name) {\n  return 'Hello, ' + name + '!';\n}\nmodule.exports = { greet };\n"})
fix("l07-fix-off-by-one",
    {"sum.js": "// Adds the numbers 1 to n.\nfunction sumTo(n) {\n  let total = 0;\n  for (let i = 1; i < n; i++) total += i;\n  return total;\n}\nmodule.exports = { sumTo };\n",
     "test.js": test("const { sumTo } = require('./sum.js');\nneed(sumTo(4) === 10, 'sumTo(4) gave ' + sumTo(4));\nneed(sumTo(1) === 1, 'sumTo(1) gave ' + sumTo(1));\n")},
    {"sum.js": "// Adds the numbers 1 to n.\nfunction sumTo(n) {\n  let total = 0;\n  for (let i = 1; i <= n; i++) total += i;\n  return total;\n}\nmodule.exports = { sumTo };\n"})
fix("l08-fix-property",
    {"user.js": "function label(user) {\n  return user.fullname + ' (' + user.age + ')';\n}\nmodule.exports = { label };\n",
     "test.js": test("const { label } = require('./user.js');\nconst got = label({ name: 'Ana', age: 30 });\nneed(got === 'Ana (30)', 'label gave ' + got);\n")},
    {"user.js": "function label(user) {\n  return user.name + ' (' + user.age + ')';\n}\nmodule.exports = { label };\n"})
fix("l09-fix-and-verify",
    {"price.js": "function withTax(p) {\n  return p * 0.2;\n}\nmodule.exports = { withTax };\n",
     "test.js": test("const { withTax } = require('./price.js');\nneed(withTax(100) === 120, 'withTax(100) gave ' + withTax(100));\n")},
    {"price.js": "function withTax(p) {\n  return p * 1.2;\n}\nmodule.exports = { withTax };\n"})

# ---------------- add new code ----------------
fix("l10-add-function",
    {"math.js": "function square(n) {\n  return n * n;\n}\n\nmodule.exports = { square };\n",
     "test.js": test("const m = require('./math.js');\nneed(m.square(3) === 9, 'square changed');\nneed(typeof m.double === 'function' && m.double(4) === 8, 'double(4) should be 8');\n")},
    {"math.js": "function square(n) {\n  return n * n;\n}\n\nfunction double(n) {\n  return n * 2;\n}\n\nmodule.exports = { square, double };\n"})
CONTROLS = "// Keyboard controls: each key calls move() with a direction.\ndocument.addEventListener('keydown', e => {\n  if (e.key === 'ArrowLeft') move('left');\n  if (e.key === 'ArrowRight') move('right');\n});\n"
fix("l11-add-key",
    {"controls.js": CONTROLS,
     "test.js": FAKE_DOM + test("require('./controls.js');\nfire('keydown', { key: 'ArrowLeft' });\nfire('keydown', { key: 'ArrowUp' });\nneed(moves.join() === 'left,up', 'moves were ' + moves.join());\n")},
    {"controls.js": CONTROLS.replace("  if (e.key === 'ArrowRight') move('right');", "  if (e.key === 'ArrowRight') move('right');\n  if (e.key === 'ArrowUp') move('up');")})
fix("l12-add-touch",
    {"controls.js": CONTROLS,
     "test.js": FAKE_DOM + test("require('./controls.js');\nfire('keydown', { key: 'ArrowRight' });\nfire('touchstart', { touches: [{ clientX: 5, clientY: 5 }] });\nneed(moves.join() === 'right,tap', 'moves were ' + moves.join());\n")},
    {"controls.js": CONTROLS + "document.addEventListener('touchstart', e => {\n  move('tap');\n});\n"})
fix("l13-add-switch-case",
    {"dir.js": "function delta(d) {\n  switch (d) {\n    case 'up': return [0, -1];\n    case 'down': return [0, 1];\n    case 'right': return [1, 0];\n    default: return [0, 0];\n  }\n}\nmodule.exports = { delta };\n",
     "test.js": test("const { delta } = require('./dir.js');\nneed(delta('left').join() === '-1,0', 'delta(left) gave ' + delta('left'));\nneed(delta('up').join() === '0,-1', 'up changed');\n")},
    {"dir.js": "function delta(d) {\n  switch (d) {\n    case 'up': return [0, -1];\n    case 'down': return [0, 1];\n    case 'left': return [-1, 0];\n    case 'right': return [1, 0];\n    default: return [0, 0];\n  }\n}\nmodule.exports = { delta };\n"})
fix("l14-add-field",
    {"config.js": "const config = {\n  lives: 3,\n  level: 1,\n};\nmodule.exports = config;\n",
     "test.js": test("const c = require('./config.js');\nneed(c.speed === 150, 'speed is ' + c.speed);\nneed(c.lives === 3 && c.level === 1, 'other fields changed');\n")},
    {"config.js": "const config = {\n  lives: 3,\n  level: 1,\n  speed: 150,\n};\nmodule.exports = config;\n"})
fix("l15-keep-existing",
    {"loop.js": "const state = { ticks: 0, frames: 0 };\nfunction tick() {\n  state.ticks++;\n}\nmodule.exports = { state, tick };\n",
     "test.js": test("const { state, tick } = require('./loop.js');\ntick(); tick();\nneed(state.ticks === 2, 'ticks is ' + state.ticks);\nneed(state.frames === 2, 'frames is ' + state.frames);\n")},
    {"loop.js": "const state = { ticks: 0, frames: 0 };\nfunction tick() {\n  state.ticks++;\n  state.frames++;\n}\nmodule.exports = { state, tick };\n"})

# ---------------- write a new file ----------------
fix("l16-new-json",
    {"README.md": "# Settings\n\nconfig.json holds the game settings.\n",
     "test.js": test("let c = {};\ntry { c = JSON.parse(require('fs').readFileSync(__dirname + '/config.json', 'utf8')); } catch (e) { need(false, 'config.json missing or not JSON: ' + e.message); }\nneed(c.speed === 150 && c.lives === 3, 'config.json is ' + JSON.stringify(c));\n")},
    {"config.json": '{ "speed": 150, "lives": 3 }\n'})
fix("l17-new-module",
    {"main.js": "const { greet } = require('./greet.js');\nconsole.log(greet('world'));\n",
     "test.js": test("let g = {};\ntry { g = require('./greet.js'); } catch (e) { need(false, 'greet.js is missing'); }\nneed(g.greet && g.greet('Ana') === 'Hello, Ana!', 'greet gave ' + (g.greet && g.greet('Ana')));\n")},
    {"greet.js": "function greet(name) {\n  return 'Hello, ' + name + '!';\n}\nmodule.exports = { greet };\n"})

# ---------------- two files ----------------
fix("l18-rename-two-files",
    {"score.js": "function getScore(s) {\n  return s.points * 10;\n}\nmodule.exports = { getScore };\n",
     "ui.js": "const { getScore } = require('./score.js');\nfunction banner(s) {\n  return 'Score: ' + getScore(s);\n}\nmodule.exports = { banner };\n",
     "test.js": test("const s = require('./score.js');\nconst { banner } = require('./ui.js');\nneed(typeof s.readScore === 'function' && !s.getScore, 'score.js should export readScore, not getScore');\nneed(banner({ points: 2 }) === 'Score: 20', 'banner gave ' + banner({ points: 2 }));\n")},
    {"score.js": "function readScore(s) {\n  return s.points * 10;\n}\nmodule.exports = { readScore };\n",
     "ui.js": "const { readScore } = require('./score.js');\nfunction banner(s) {\n  return 'Score: ' + readScore(s);\n}\nmodule.exports = { banner };\n"})
fix("l19-html-and-js",
    {"index.html": "<!DOCTYPE html>\n<html>\n<body>\n  <p id=\"count\">0</p>\n  <button id=\"add\">Add</button>\n  <script src=\"app.js\"></script>\n</body>\n</html>\n",
     "app.js": "let count = 0;\nfunction add() { count++; return count; }\nif (typeof module !== 'undefined') module.exports = { add, get: () => count };\n",
     "test.js": test("const fs = require('fs');\nconst html = fs.readFileSync(__dirname + '/index.html', 'utf8');\nneed(/<button[^>]*id=\"reset\"/.test(html), 'index.html needs a button with id=\"reset\"');\nconst a = require('./app.js');\nneed(typeof a.reset === 'function', 'app.js should export reset');\nif (a.reset) { a.add(); a.add(); a.reset(); need(a.get() === 0, 'reset left count at ' + a.get()); }\n")},
    {"index.html": "<!DOCTYPE html>\n<html>\n<body>\n  <p id=\"count\">0</p>\n  <button id=\"add\">Add</button>\n  <button id=\"reset\">Reset</button>\n  <script src=\"app.js\"></script>\n</body>\n</html>\n",
     "app.js": "let count = 0;\nfunction add() { count++; return count; }\nfunction reset() { count = 0; }\nif (typeof module !== 'undefined') module.exports = { add, reset, get: () => count };\n"})
fix("l20-stale-snippet",
    # the request quotes a line that differs from the file (an extra comment), as people often do
    {"timer.js": "const timer = {\n  seconds: 60, // one minute\n  running: false,\n};\nmodule.exports = timer;\n",
     "test.js": test("const t = require('./timer.js');\nneed(t.seconds === 90, 'seconds is ' + t.seconds);\nneed(t.running === false, 'running changed');\n")},
    {"timer.js": "const timer = {\n  seconds: 90, // one minute and a half\n  running: false,\n};\nmodule.exports = timer;\n"})

# hidden/ holds checks the model never sees (written by hand, run by analyze.py); keep them when the tasks are rebuilt
hidden = {}
hd = os.path.join(OUT, "hidden")
if os.path.isdir(hd):
    for f in os.listdir(hd): hidden[f] = open(os.path.join(hd, f)).read()
if os.path.exists(OUT): shutil.rmtree(OUT)
if hidden:
    os.makedirs(hd)
    for f, c in hidden.items(): open(os.path.join(hd, f), "w").write(c)
for kind, src in (("tasks", T), ("solutions", S)):
    for name, files in src.items():
        for p, c in files.items():
            fp = os.path.join(OUT, kind, name, p); os.makedirs(os.path.dirname(fp), exist_ok=True)
            open(fp, "w").write(c)
os.makedirs(os.path.join(OUT, "zips"))
for name, files in T.items():
    with zipfile.ZipFile(os.path.join(OUT, "zips", name + ".zip"), "w", zipfile.ZIP_DEFLATED) as z:
        for p, c in sorted(files.items()):
            zi = zipfile.ZipInfo(p, date_time=(2026, 11, 25, 0, 0, 0)); zi.external_attr = 0o644 << 16
            z.writestr(zi, c)
print("written", len(T), "local tasks")
