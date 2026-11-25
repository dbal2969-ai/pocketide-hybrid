package dev.pocketide.app.core

/** The hybrid benchmark's starting projects, generated from bench/hybrid/local/tasks by bench/hybrid/to_kotlin.py. Do not edit by hand. */
object HybridBenchLocalFiles {
    val FILES: Map<String, Map<String, String>> = linkedMapOf(
        "l01-find-file" to linkedMapOf(
            "game.js" to "const { MAX_LIVES } = require('./rules.js');\nlet lives = MAX_LIVES;\nmodule.exports = { lives };\n",
            "rules.js" to "const MAX_LIVES = 3;\nconst SPEED = 150;\nmodule.exports = { MAX_LIVES, SPEED };\n",
            "ui.js" to "function show(n) { return 'Lives: ' + n; }\nmodule.exports = { show };\n"
        ),
        "l02-explain-function" to linkedMapOf(
            "snake.js" to "class Snake {\n  constructor() { this.body = [{ x: 1, y: 1 }]; }\n  step() {\n    const head = { x: this.body[0].x + 1, y: this.body[0].y };\n    this.body.unshift(head);\n    return this.body.length;\n  }\n}\nmodule.exports = { Snake };\n"
        ),
        "l03-explain-no-change" to linkedMapOf(
            "csv.js" to "// Splits one line of CSV into fields. Commas inside double quotes do not split.\nfunction parse(line) {\n  const out = []; let cur = ''; let q = false;\n  for (const ch of line) {\n    if (ch === '\"') q = !q;\n    else if (ch === ',' && !q) { out.push(cur); cur = ''; }\n    else cur += ch;\n  }\n  out.push(cur);\n  return out;\n}\nmodule.exports = { parse };\n"
        ),
        "l04-report-test-result" to linkedMapOf(
            "app.js" to "function add(a, b) { return a + b; }\nmodule.exports = { add };\n",
            "test.js" to "let bad = 0;\nfunction need(c, msg) { if (!c) { console.error(msg); bad++; } }\nconst { add } = require('./app.js');\nneed(add(2, 3) === 5, 'add');\nif (bad) process.exit(1);\nconsole.log('ok');\n"
        ),
        "l05-fix-operator" to linkedMapOf(
            "app.js" to "function add(a, b) {\n  return a - b;\n}\nmodule.exports = { add };\n",
            "test.js" to "let bad = 0;\nfunction need(c, msg) { if (!c) { console.error(msg); bad++; } }\nconst { add } = require('./app.js');\nneed(add(2, 3) === 5, 'add(2, 3) gave ' + add(2, 3));\nif (bad) process.exit(1);\nconsole.log('ok');\n"
        ),
        "l06-fix-text" to linkedMapOf(
            "greet.js" to "function greet(name) {\n  return 'Helo, ' + name + '!';\n}\nmodule.exports = { greet };\n",
            "test.js" to "let bad = 0;\nfunction need(c, msg) { if (!c) { console.error(msg); bad++; } }\nconst { greet } = require('./greet.js');\nneed(greet('Ana') === 'Hello, Ana!', 'greet gave ' + greet('Ana'));\nif (bad) process.exit(1);\nconsole.log('ok');\n"
        ),
        "l07-fix-off-by-one" to linkedMapOf(
            "sum.js" to "// Adds the numbers 1 to n.\nfunction sumTo(n) {\n  let total = 0;\n  for (let i = 1; i < n; i++) total += i;\n  return total;\n}\nmodule.exports = { sumTo };\n",
            "test.js" to "let bad = 0;\nfunction need(c, msg) { if (!c) { console.error(msg); bad++; } }\nconst { sumTo } = require('./sum.js');\nneed(sumTo(4) === 10, 'sumTo(4) gave ' + sumTo(4));\nneed(sumTo(1) === 1, 'sumTo(1) gave ' + sumTo(1));\nif (bad) process.exit(1);\nconsole.log('ok');\n"
        ),
        "l08-fix-property" to linkedMapOf(
            "test.js" to "let bad = 0;\nfunction need(c, msg) { if (!c) { console.error(msg); bad++; } }\nconst { label } = require('./user.js');\nconst got = label({ name: 'Ana', age: 30 });\nneed(got === 'Ana (30)', 'label gave ' + got);\nif (bad) process.exit(1);\nconsole.log('ok');\n",
            "user.js" to "function label(user) {\n  return user.fullname + ' (' + user.age + ')';\n}\nmodule.exports = { label };\n"
        ),
        "l09-fix-and-verify" to linkedMapOf(
            "price.js" to "function withTax(p) {\n  return p * 0.2;\n}\nmodule.exports = { withTax };\n",
            "test.js" to "let bad = 0;\nfunction need(c, msg) { if (!c) { console.error(msg); bad++; } }\nconst { withTax } = require('./price.js');\nneed(withTax(100) === 120, 'withTax(100) gave ' + withTax(100));\nif (bad) process.exit(1);\nconsole.log('ok');\n"
        ),
        "l10-add-function" to linkedMapOf(
            "math.js" to "function square(n) {\n  return n * n;\n}\n\nmodule.exports = { square };\n",
            "test.js" to "let bad = 0;\nfunction need(c, msg) { if (!c) { console.error(msg); bad++; } }\nconst m = require('./math.js');\nneed(m.square(3) === 9, 'square changed');\nneed(typeof m.double === 'function' && m.double(4) === 8, 'double(4) should be 8');\nif (bad) process.exit(1);\nconsole.log('ok');\n"
        ),
        "l11-add-key" to linkedMapOf(
            "controls.js" to "// Keyboard controls: each key calls move() with a direction.\ndocument.addEventListener('keydown', e => {\n  if (e.key === 'ArrowLeft') move('left');\n  if (e.key === 'ArrowRight') move('right');\n});\n",
            "test.js" to "const handlers = {};\nglobal.document = { addEventListener(t, f) { (handlers[t] = handlers[t] || []).push(f); }, getElementById() { return { addEventListener() {} }; } };\nglobal.moves = [];\nglobal.move = d => moves.push(d);\nfunction fire(t, e) { for (const f of handlers[t] || []) f(Object.assign({ preventDefault() {} }, e)); }\nlet bad = 0;\nfunction need(c, msg) { if (!c) { console.error(msg); bad++; } }\nrequire('./controls.js');\nfire('keydown', { key: 'ArrowLeft' });\nfire('keydown', { key: 'ArrowUp' });\nneed(moves.join() === 'left,up', 'moves were ' + moves.join());\nif (bad) process.exit(1);\nconsole.log('ok');\n"
        ),
        "l12-add-touch" to linkedMapOf(
            "controls.js" to "// Keyboard controls: each key calls move() with a direction.\ndocument.addEventListener('keydown', e => {\n  if (e.key === 'ArrowLeft') move('left');\n  if (e.key === 'ArrowRight') move('right');\n});\n",
            "test.js" to "const handlers = {};\nglobal.document = { addEventListener(t, f) { (handlers[t] = handlers[t] || []).push(f); }, getElementById() { return { addEventListener() {} }; } };\nglobal.moves = [];\nglobal.move = d => moves.push(d);\nfunction fire(t, e) { for (const f of handlers[t] || []) f(Object.assign({ preventDefault() {} }, e)); }\nlet bad = 0;\nfunction need(c, msg) { if (!c) { console.error(msg); bad++; } }\nrequire('./controls.js');\nfire('keydown', { key: 'ArrowRight' });\nfire('touchstart', { touches: [{ clientX: 5, clientY: 5 }] });\nneed(moves.join() === 'right,tap', 'moves were ' + moves.join());\nif (bad) process.exit(1);\nconsole.log('ok');\n"
        ),
        "l13-add-switch-case" to linkedMapOf(
            "dir.js" to "function delta(d) {\n  switch (d) {\n    case 'up': return [0, -1];\n    case 'down': return [0, 1];\n    case 'right': return [1, 0];\n    default: return [0, 0];\n  }\n}\nmodule.exports = { delta };\n",
            "test.js" to "let bad = 0;\nfunction need(c, msg) { if (!c) { console.error(msg); bad++; } }\nconst { delta } = require('./dir.js');\nneed(delta('left').join() === '-1,0', 'delta(left) gave ' + delta('left'));\nneed(delta('up').join() === '0,-1', 'up changed');\nif (bad) process.exit(1);\nconsole.log('ok');\n"
        ),
        "l14-add-field" to linkedMapOf(
            "config.js" to "const config = {\n  lives: 3,\n  level: 1,\n};\nmodule.exports = config;\n",
            "test.js" to "let bad = 0;\nfunction need(c, msg) { if (!c) { console.error(msg); bad++; } }\nconst c = require('./config.js');\nneed(c.speed === 150, 'speed is ' + c.speed);\nneed(c.lives === 3 && c.level === 1, 'other fields changed');\nif (bad) process.exit(1);\nconsole.log('ok');\n"
        ),
        "l15-keep-existing" to linkedMapOf(
            "loop.js" to "const state = { ticks: 0, frames: 0 };\nfunction tick() {\n  state.ticks++;\n}\nmodule.exports = { state, tick };\n",
            "test.js" to "let bad = 0;\nfunction need(c, msg) { if (!c) { console.error(msg); bad++; } }\nconst { state, tick } = require('./loop.js');\ntick(); tick();\nneed(state.ticks === 2, 'ticks is ' + state.ticks);\nneed(state.frames === 2, 'frames is ' + state.frames);\nif (bad) process.exit(1);\nconsole.log('ok');\n"
        ),
        "l16-new-json" to linkedMapOf(
            "README.md" to "# Settings\n\nconfig.json holds the game settings.\n",
            "test.js" to "let bad = 0;\nfunction need(c, msg) { if (!c) { console.error(msg); bad++; } }\nlet c = {};\ntry { c = JSON.parse(require('fs').readFileSync(__dirname + '/config.json', 'utf8')); } catch (e) { need(false, 'config.json missing or not JSON: ' + e.message); }\nneed(c.speed === 150 && c.lives === 3, 'config.json is ' + JSON.stringify(c));\nif (bad) process.exit(1);\nconsole.log('ok');\n"
        ),
        "l17-new-module" to linkedMapOf(
            "main.js" to "const { greet } = require('./greet.js');\nconsole.log(greet('world'));\n",
            "test.js" to "let bad = 0;\nfunction need(c, msg) { if (!c) { console.error(msg); bad++; } }\nlet g = {};\ntry { g = require('./greet.js'); } catch (e) { need(false, 'greet.js is missing'); }\nneed(g.greet && g.greet('Ana') === 'Hello, Ana!', 'greet gave ' + (g.greet && g.greet('Ana')));\nif (bad) process.exit(1);\nconsole.log('ok');\n"
        ),
        "l18-rename-two-files" to linkedMapOf(
            "score.js" to "function getScore(s) {\n  return s.points * 10;\n}\nmodule.exports = { getScore };\n",
            "test.js" to "let bad = 0;\nfunction need(c, msg) { if (!c) { console.error(msg); bad++; } }\nconst s = require('./score.js');\nconst { banner } = require('./ui.js');\nneed(typeof s.readScore === 'function' && !s.getScore, 'score.js should export readScore, not getScore');\nneed(banner({ points: 2 }) === 'Score: 20', 'banner gave ' + banner({ points: 2 }));\nif (bad) process.exit(1);\nconsole.log('ok');\n",
            "ui.js" to "const { getScore } = require('./score.js');\nfunction banner(s) {\n  return 'Score: ' + getScore(s);\n}\nmodule.exports = { banner };\n"
        ),
        "l19-html-and-js" to linkedMapOf(
            "app.js" to "let count = 0;\nfunction add() { count++; return count; }\nif (typeof module !== 'undefined') module.exports = { add, get: () => count };\n",
            "index.html" to "<!DOCTYPE html>\n<html>\n<body>\n  <p id=\"count\">0</p>\n  <button id=\"add\">Add</button>\n  <script src=\"app.js\"></script>\n</body>\n</html>\n",
            "test.js" to "let bad = 0;\nfunction need(c, msg) { if (!c) { console.error(msg); bad++; } }\nconst fs = require('fs');\nconst html = fs.readFileSync(__dirname + '/index.html', 'utf8');\nneed(/<button[^>]*id=\"reset\"/.test(html), 'index.html needs a button with id=\"reset\"');\nconst a = require('./app.js');\nneed(typeof a.reset === 'function', 'app.js should export reset');\nif (a.reset) { a.add(); a.add(); a.reset(); need(a.get() === 0, 'reset left count at ' + a.get()); }\nif (bad) process.exit(1);\nconsole.log('ok');\n"
        ),
        "l20-stale-snippet" to linkedMapOf(
            "test.js" to "let bad = 0;\nfunction need(c, msg) { if (!c) { console.error(msg); bad++; } }\nconst t = require('./timer.js');\nneed(t.seconds === 90, 'seconds is ' + t.seconds);\nneed(t.running === false, 'running changed');\nif (bad) process.exit(1);\nconsole.log('ok');\n",
            "timer.js" to "const timer = {\n  seconds: 60, // one minute\n  running: false,\n};\nmodule.exports = timer;\n"
        )
    )
}
