#!/usr/bin/env python3
"""The on-device reliability benchmark's held-out Node tasks (1.5.186).

Gemini Nano alone (Private mode, no cloud, no quota), on tasks it has never been tuned on. The local suite's twenty tasks
(make_local.py) have been used to tune the app since 1125-0955, so they are the development set. These twenty-four are held out:
nothing in the app is changed because of one of them, so their score is the honest one. The method is the one used for the
Kaggle Gemma 4 entry (PocketSWE): every task is sound (its test fails as given and passes with the reference fix, checked by
check_hard.py), carries hidden checks the model never sees (other behaviour that must still work), and is run several times,
because one run of a sampling model is luck.

Each task: files (the project), prompt, skill, kind ("fix": node test.js and the hidden check must pass; "answer": no file may
change and the answer must match `answer`), solution (the reference fix), hidden (hidden.js, run after the task).

Usage: python3 bench/hybrid/make_reliable.py   (writes bench/hybrid/reliable/ and the app's asset)
"""
import json, os, shutil

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "reliable")
ASSET = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "bench", "reliable")

TASKS = []


def test(cases_js):
    return ("let bad = 0;\nfunction need(c, msg) { if (!c) { console.error(msg); bad++; } }\n" + cases_js +
            "if (bad) process.exit(1);\nconsole.log('ok');\n")


def task(id, title, skill, prompt, files, solution=None, hidden=None, kind="fix", answer=None):
    TASKS.append({"id": id, "title": title, "skill": skill, "prompt": prompt, "files": files, "solution": solution or {},
                  "hidden": test(hidden) if hidden else "", "kind": kind, "answer": answer})


# ---------------- find and explain (no change) ----------------
task("r01-find-constant", "Find which file sets a value", "Find and explain",
     "Which file sets the starting gold? Change nothing; just tell me the file name.",
     {"shop.js": "const { START_GOLD } = require('./economy.js');\nlet gold = START_GOLD;\nfunction buy(cost) { if (cost <= gold) gold -= cost; return gold; }\nmodule.exports = { buy };\n",
      "economy.js": "const START_GOLD = 250;\nconst TAX = 0.1;\nmodule.exports = { START_GOLD, TAX };\n",
      "hero.js": "const hero = { hp: 20, speed: 3 };\nmodule.exports = hero;\n",
      "map.js": "const SIZE = 32;\nmodule.exports = { SIZE };\n"},
     kind="answer", answer=r"economy\.js")
task("r02-explain-return", "Say what a call returns", "Find and explain",
     "What does fee(200) return in bank.js? Change nothing; give the number.",
     {"bank.js": "// The fee is 2% of the amount, but never less than 5.\nfunction fee(amount) {\n  return Math.max(5, amount * 0.02);\n}\nmodule.exports = { fee };\n"},
     kind="answer", answer=r"(?<![\d.])5(?![\d.])")
task("r03-which-test-fails", "Report which check fails", "Find and explain",
     "Run node test.js and tell me which check fails. Do not fix anything.",
     {"text.js": "function shout(s) { return s.toUpperCase() + '!'; }\nfunction whisper(s) { return s.toLowerCase() + '...'; }\nmodule.exports = { shout, whisper };\n",
      "test.js": test("const t = require('./text.js');\nneed(t.shout('hi') === 'HI!', 'shout');\nneed(t.whisper('Hi') === 'hi…', 'whisper');\n")},
     kind="answer", answer=r"(?i)whisper")
task("r04-find-the-line", "Find the line that causes a bug", "Find and explain",
     "The cart total is wrong. Which function in cart.js causes it? Explain in one sentence and change nothing.",
     {"cart.js": "function lineTotal(item) {\n  return item.price * item.qty;\n}\nfunction total(items) {\n  let sum = 0;\n  for (const it of items) sum += it.price;\n  return sum;\n}\nmodule.exports = { lineTotal, total };\n"},
     kind="answer", answer=r"(?i)\btotal\b")

# ---------------- fix ----------------
task("r05-fix-comparison", "Fix a comparison", "Fix one line",
     "max.js returns the wrong answer when the largest number comes last. Fix it; node test.js should print ok.",
     {"max.js": "function largest(list) {\n  let best = list[0];\n  for (let i = 1; i < list.length - 1; i++) {\n    if (list[i] > best) best = list[i];\n  }\n  return best;\n}\nmodule.exports = { largest };\n",
      "test.js": test("const { largest } = require('./max.js');\nneed(largest([1, 5, 9]) === 9, 'largest([1,5,9]) gave ' + largest([1, 5, 9]));\n")},
     {"max.js": "function largest(list) {\n  let best = list[0];\n  for (let i = 1; i < list.length; i++) {\n    if (list[i] > best) best = list[i];\n  }\n  return best;\n}\nmodule.exports = { largest };\n"},
     "const { largest } = require('./max.js');\nneed(largest([9, 1, 2]) === 9, 'first');\nneed(largest([3, 8, 1]) === 8, 'middle');\nneed(largest([4]) === 4, 'one item');\n")
task("r06-fix-wrong-variable", "Fix a wrong variable", "Fix one line",
     "area() in shapes.js gives the wrong area for rectangles. Fix it so node test.js prints ok.",
     {"shapes.js": "function area(r) {\n  return r.width * r.width;\n}\nfunction perimeter(r) {\n  return 2 * (r.width + r.height);\n}\nmodule.exports = { area, perimeter };\n",
      "test.js": test("const { area } = require('./shapes.js');\nneed(area({ width: 3, height: 4 }) === 12, 'area gave ' + area({ width: 3, height: 4 }));\n")},
     {"shapes.js": "function area(r) {\n  return r.width * r.height;\n}\nfunction perimeter(r) {\n  return 2 * (r.width + r.height);\n}\nmodule.exports = { area, perimeter };\n"},
     "const { area, perimeter } = require('./shapes.js');\nneed(area({ width: 2, height: 5 }) === 10, 'area');\nneed(perimeter({ width: 2, height: 5 }) === 14, 'perimeter changed');\n")
task("r07-fix-string-number", "Fix text added to a number", "Fix one line",
     "nextLevel('3') should give 4, but it gives '31'. Fix level.js; node test.js should print ok.",
     {"level.js": "// The level comes from a text box, so it arrives as text.\nfunction nextLevel(level) {\n  return level + 1;\n}\nmodule.exports = { nextLevel };\n",
      "test.js": test("const { nextLevel } = require('./level.js');\nneed(nextLevel('3') === 4, 'nextLevel(\\'3\\') gave ' + JSON.stringify(nextLevel('3')));\n")},
     {"level.js": "// The level comes from a text box, so it arrives as text.\nfunction nextLevel(level) {\n  return Number(level) + 1;\n}\nmodule.exports = { nextLevel };\n"},
     "const { nextLevel } = require('./level.js');\nneed(nextLevel('9') === 10, '9');\nneed(nextLevel(2) === 3, 'a number still works');\n")
task("r08-fix-missing-return", "Fix a missing return", "Fix one line",
     "average() in stats.js returns undefined. Fix it so node test.js prints ok.",
     {"stats.js": "function average(nums) {\n  const sum = nums.reduce((a, b) => a + b, 0);\n  sum / nums.length;\n}\nmodule.exports = { average };\n",
      "test.js": test("const { average } = require('./stats.js');\nneed(average([2, 4]) === 3, 'average gave ' + average([2, 4]));\n")},
     {"stats.js": "function average(nums) {\n  const sum = nums.reduce((a, b) => a + b, 0);\n  return sum / nums.length;\n}\nmodule.exports = { average };\n"},
     "const { average } = require('./stats.js');\nneed(average([1, 2, 3, 6]) === 3, 'four numbers');\n")
task("r09-fix-last-item", "Fix reading the last item", "Fix one line",
     "last() in list.js returns undefined instead of the last item. Fix it; node test.js should print ok.",
     {"list.js": "function first(a) { return a[0]; }\nfunction last(a) { return a[a.length]; }\nmodule.exports = { first, last };\n",
      "test.js": test("const { last } = require('./list.js');\nneed(last([1, 2, 3]) === 3, 'last gave ' + last([1, 2, 3]));\n")},
     {"list.js": "function first(a) { return a[0]; }\nfunction last(a) { return a[a.length - 1]; }\nmodule.exports = { first, last };\n"},
     "const { first, last } = require('./list.js');\nneed(last(['x']) === 'x', 'one item');\nneed(first([7, 8]) === 7, 'first changed');\n")
task("r10-fix-missing-default", "Handle a missing value", "Fix one line",
     "greet() in hello.js should say 'Hello, friend!' when no name is given. Fix it; node test.js should print ok.",
     {"hello.js": "function greet(name) {\n  return 'Hello, ' + name + '!';\n}\nmodule.exports = { greet };\n",
      "test.js": test("const { greet } = require('./hello.js');\nneed(greet() === 'Hello, friend!', 'greet() gave ' + greet());\n")},
     {"hello.js": "function greet(name = 'friend') {\n  return 'Hello, ' + name + '!';\n}\nmodule.exports = { greet };\n"},
     "const { greet } = require('./hello.js');\nneed(greet('Bo') === 'Hello, Bo!', 'a name still works');\n")

# ---------------- add new code ----------------
task("r11-add-method", "Add a method to a class", "Add new code",
     "Add a reset() method to the Counter class in counter.js that sets the count back to 0. node test.js should print ok.",
     {"counter.js": "class Counter {\n  constructor() { this.count = 0; }\n  inc() { this.count++; return this.count; }\n}\nmodule.exports = { Counter };\n",
      "test.js": test("const { Counter } = require('./counter.js');\nconst c = new Counter();\nc.inc(); c.inc();\nneed(typeof c.reset === 'function', 'reset is missing');\nif (c.reset) { c.reset(); need(c.count === 0, 'count is ' + c.count); }\n")},
     {"counter.js": "class Counter {\n  constructor() { this.count = 0; }\n  inc() { this.count++; return this.count; }\n  reset() { this.count = 0; }\n}\nmodule.exports = { Counter };\n"},
     "const { Counter } = require('./counter.js');\nconst c = new Counter();\nneed(c.inc() === 1 && c.inc() === 2, 'inc changed');\n")
task("r12-add-parameter", "Add a parameter with a default", "Add new code",
     "Let repeat() in repeat.js take a separator as a third argument, defaulting to ''. Old calls must still work. node test.js should print ok.",
     {"repeat.js": "function repeat(word, times) {\n  return Array(times).fill(word).join('');\n}\nmodule.exports = { repeat };\n",
      "test.js": test("const { repeat } = require('./repeat.js');\nneed(repeat('ab', 3, '-') === 'ab-ab-ab', 'gave ' + repeat('ab', 3, '-'));\n")},
     {"repeat.js": "function repeat(word, times, sep = '') {\n  return Array(times).fill(word).join(sep);\n}\nmodule.exports = { repeat };\n"},
     "const { repeat } = require('./repeat.js');\nneed(repeat('x', 3) === 'xxx', 'old calls changed');\n")
task("r13-add-branch", "Add a case for negative numbers", "Add new code",
     "describe() in sign.js should return 'negative' for numbers below zero. Add that case; node test.js should print ok.",
     {"sign.js": "function describe(n) {\n  if (n > 0) return 'positive';\n  return 'zero';\n}\nmodule.exports = { describe };\n",
      "test.js": test("const { describe } = require('./sign.js');\nneed(describe(-2) === 'negative', 'describe(-2) gave ' + describe(-2));\n")},
     {"sign.js": "function describe(n) {\n  if (n > 0) return 'positive';\n  if (n < 0) return 'negative';\n  return 'zero';\n}\nmodule.exports = { describe };\n"},
     "const { describe } = require('./sign.js');\nneed(describe(3) === 'positive' && describe(0) === 'zero', 'other cases changed');\n")
task("r14-add-list-item", "Add an item to a list", "Add new code",
     "Add 'grape' to the fruits list in menu.js, after 'pear'. node test.js should print ok.",
     {"menu.js": "const fruits = [\n  'apple',\n  'pear',\n];\nmodule.exports = { fruits };\n",
      "test.js": test("const { fruits } = require('./menu.js');\nneed(fruits.join() === 'apple,pear,grape', 'fruits are ' + fruits.join());\n")},
     {"menu.js": "const fruits = [\n  'apple',\n  'pear',\n  'grape',\n];\nmodule.exports = { fruits };\n"},
     "const { fruits } = require('./menu.js');\nneed(fruits.length === 3, 'three fruits');\n")
task("r15-add-validation", "Add input checking", "Add new code",
     "setAge() in person.js should throw an Error when the age is negative. Add that check; node test.js should print ok.",
     {"person.js": "const person = { age: 0 };\nfunction setAge(a) {\n  person.age = a;\n}\nmodule.exports = { person, setAge };\n",
      "test.js": test("const { person, setAge } = require('./person.js');\nlet threw = false;\ntry { setAge(-1); } catch (e) { threw = e instanceof Error; }\nneed(threw, 'setAge(-1) did not throw');\n")},
     {"person.js": "const person = { age: 0 };\nfunction setAge(a) {\n  if (a < 0) throw new Error('age cannot be negative');\n  person.age = a;\n}\nmodule.exports = { person, setAge };\n"},
     "const { person, setAge } = require('./person.js');\nsetAge(30);\nneed(person.age === 30, 'a good age is no longer set');\n")
task("r16-add-export", "Add and export a function", "Add new code",
     "Add a function isEven(n) to numbers.js and export it next to isOdd. node test.js should print ok.",
     {"numbers.js": "function isOdd(n) {\n  return n % 2 === 1;\n}\nmodule.exports = { isOdd };\n",
      "test.js": test("const m = require('./numbers.js');\nneed(typeof m.isEven === 'function' && m.isEven(4) && !m.isEven(3), 'isEven is missing or wrong');\n")},
     {"numbers.js": "function isOdd(n) {\n  return n % 2 === 1;\n}\nfunction isEven(n) {\n  return n % 2 === 0;\n}\nmodule.exports = { isOdd, isEven };\n"},
     "const m = require('./numbers.js');\nneed(m.isOdd(3) && !m.isOdd(4), 'isOdd changed');\n")

# ---------------- write a new file ----------------
task("r17-new-module", "Create a module", "New file",
     "Create temp.js exporting toF(c), which converts Celsius to Fahrenheit. node test.js should print ok.",
     {"test.js": test("let t = {};\ntry { t = require('./temp.js'); } catch (e) { need(false, 'temp.js is missing'); }\nneed(t.toF && t.toF(100) === 212, 'toF(100) gave ' + (t.toF && t.toF(100)));\n")},
     {"temp.js": "function toF(c) {\n  return c * 9 / 5 + 32;\n}\nmodule.exports = { toF };\n"},
     "const { toF } = require('./temp.js');\nneed(toF(0) === 32, 'toF(0)');\nneed(toF(-40) === -40, 'toF(-40)');\n")
task("r18-new-json", "Create a data file", "New file",
     "Create levels.json: a JSON array of three levels, each with name and enemies, where the enemies are 2, 4 and 6. node test.js should print ok.",
     {"test.js": test("let l = [];\ntry { l = JSON.parse(require('fs').readFileSync(__dirname + '/levels.json', 'utf8')); } catch (e) { need(false, 'levels.json missing or not JSON: ' + e.message); }\nneed(Array.isArray(l) && l.length === 3, 'need three levels');\nneed(l.map(x => x.enemies).join() === '2,4,6', 'enemies are ' + l.map(x => x.enemies).join());\n")},
     {"levels.json": '[\n  { "name": "One", "enemies": 2 },\n  { "name": "Two", "enemies": 4 },\n  { "name": "Three", "enemies": 6 }\n]\n'},
     "const l = JSON.parse(require('fs').readFileSync(__dirname + '/levels.json', 'utf8'));\nneed(l.every(x => typeof x.name === 'string' && x.name.length > 0), 'every level has a name');\n")

# ---------------- two or more files ----------------
task("r19-move-function", "Move a function to another file", "Two files",
     "Move formatMoney from app.js into a new file money.js, export it there, and make app.js use it from money.js. node test.js should print ok.",
     {"app.js": "function formatMoney(n) {\n  return '$' + n.toFixed(2);\n}\nfunction receipt(total) {\n  return 'Total: ' + formatMoney(total);\n}\nmodule.exports = { receipt };\n",
      "test.js": test("let m = {};\ntry { m = require('./money.js'); } catch (e) { need(false, 'money.js is missing'); }\nneed(m.formatMoney && m.formatMoney(3) === '$3.00', 'money.js should export formatMoney');\nconst { receipt } = require('./app.js');\nneed(receipt(2.5) === 'Total: $2.50', 'receipt gave ' + receipt(2.5));\nneed(!/function formatMoney/.test(require('fs').readFileSync(__dirname + '/app.js', 'utf8')), 'app.js still defines formatMoney');\n")},
     {"money.js": "function formatMoney(n) {\n  return '$' + n.toFixed(2);\n}\nmodule.exports = { formatMoney };\n",
      "app.js": "const { formatMoney } = require('./money.js');\nfunction receipt(total) {\n  return 'Total: ' + formatMoney(total);\n}\nmodule.exports = { receipt };\n"},
     "const { receipt } = require('./app.js');\nneed(receipt(10) === 'Total: $10.00', 'receipt(10)');\n")
task("r20-rename-three-files", "Rename a setting in three files", "Two files",
     "Rename the setting MAX_HP to MAX_HEALTH everywhere (config.js, player.js and hud.js). node test.js should print ok.",
     {"config.js": "const MAX_HP = 10;\nmodule.exports = { MAX_HP };\n",
      "player.js": "const { MAX_HP } = require('./config.js');\nconst player = { hp: MAX_HP };\nmodule.exports = { player };\n",
      "hud.js": "const { MAX_HP } = require('./config.js');\nfunction bar(hp) { return hp + '/' + MAX_HP; }\nmodule.exports = { bar };\n",
      "test.js": test("const fs = require('fs');\nconst all = ['config.js', 'player.js', 'hud.js'].map(f => fs.readFileSync(__dirname + '/' + f, 'utf8')).join('\\n');\nneed(!/MAX_HP\\b/.test(all), 'MAX_HP is still used');\nneed(require('./config.js').MAX_HEALTH === 10, 'config.js should export MAX_HEALTH');\nneed(require('./hud.js').bar(4) === '4/10', 'bar gave ' + require('./hud.js').bar(4));\n")},
     {"config.js": "const MAX_HEALTH = 10;\nmodule.exports = { MAX_HEALTH };\n",
      "player.js": "const { MAX_HEALTH } = require('./config.js');\nconst player = { hp: MAX_HEALTH };\nmodule.exports = { player };\n",
      "hud.js": "const { MAX_HEALTH } = require('./config.js');\nfunction bar(hp) { return hp + '/' + MAX_HEALTH; }\nmodule.exports = { bar };\n"},
     "const { player } = require('./player.js');\nneed(player.hp === 10, 'player starts with ' + player.hp);\n")
task("r21-html-and-css", "Add a class in HTML and its style", "Two files",
     "Give the Start button in index.html the class big, and add a .big rule to style.css with font-size: 24px. node test.js should print ok.",
     {"index.html": "<!DOCTYPE html>\n<html>\n<head><link rel=\"stylesheet\" href=\"style.css\"></head>\n<body>\n  <button id=\"start\">Start</button>\n  <button id=\"help\">Help</button>\n</body>\n</html>\n",
      "style.css": "body {\n  font-family: sans-serif;\n}\n",
      "test.js": test("const fs = require('fs');\nconst html = fs.readFileSync(__dirname + '/index.html', 'utf8');\nconst css = fs.readFileSync(__dirname + '/style.css', 'utf8');\nneed(/<button[^>]*id=\"start\"[^>]*class=\"[^\"]*\\bbig\\b|<button[^>]*class=\"[^\"]*\\bbig\\b[^>]*id=\"start\"/.test(html), 'the Start button needs class big');\nneed(/\\.big\\s*\\{[^}]*font-size:\\s*24px/.test(css), 'style.css needs .big { font-size: 24px }');\n")},
     {"index.html": "<!DOCTYPE html>\n<html>\n<head><link rel=\"stylesheet\" href=\"style.css\"></head>\n<body>\n  <button id=\"start\" class=\"big\">Start</button>\n  <button id=\"help\">Help</button>\n</body>\n</html>\n",
      "style.css": "body {\n  font-family: sans-serif;\n}\n.big {\n  font-size: 24px;\n}\n"},
     "const fs = require('fs');\nconst html = fs.readFileSync(__dirname + '/index.html', 'utf8');\nneed(/id=\"help\"/.test(html) && !/id=\"help\"[^>]*big/.test(html), 'the Help button changed');\nneed(/font-family:\\s*sans-serif/.test(fs.readFileSync(__dirname + '/style.css', 'utf8')), 'the body rule changed');\n")

# ---------------- robustness ----------------
task("r22-stale-quote", "Edit from a quote that differs", "Robustness",
     "In speed.js change `const SPEED = 5;` to 8. node test.js should print ok.",
     {"speed.js": "const SPEED = 5; // tiles per second\nconst TURBO = 2;\nmodule.exports = { SPEED, TURBO };\n",
      "test.js": test("const s = require('./speed.js');\nneed(s.SPEED === 8, 'SPEED is ' + s.SPEED);\n")},
     {"speed.js": "const SPEED = 8; // tiles per second\nconst TURBO = 2;\nmodule.exports = { SPEED, TURBO };\n"},
     "const s = require('./speed.js');\nneed(s.TURBO === 2, 'TURBO changed');\n")
LONG = "".join(f"function helper{i}(x) {{\n  // helper {i}: returns x plus {i}\n  return x + {i};\n}}\n\n" for i in range(1, 46))
task("r23-long-file", "Fix a bug far down a long file", "Robustness",
     "helpers.js is long. helper40 should return x plus 40, but it does not. Fix only that; node test.js should print ok.",
     {"helpers.js": LONG.replace("return x + 40;", "return x - 40;") + "module.exports = { " + ", ".join(f"helper{i}" for i in range(1, 46)) + " };\n",
      "test.js": test("const h = require('./helpers.js');\nneed(h.helper40(1) === 41, 'helper40(1) gave ' + h.helper40(1));\n")},
     {"helpers.js": LONG + "module.exports = { " + ", ".join(f"helper{i}" for i in range(1, 46)) + " };\n"},
     "const h = require('./helpers.js');\nfor (let i = 1; i <= 45; i++) need(h['helper' + i](0) === i, 'helper' + i + ' changed');\n")
task("r24-report-only", "Report, even when there is a bug", "Robustness",
     "Run node test.js and tell me whether it passes. Only report; do not change any file, even if something is wrong.",
     {"discount.js": "function discount(price) {\n  return price * 0.9 + 1;\n}\nmodule.exports = { discount };\n",
      "test.js": test("const { discount } = require('./discount.js');\nneed(discount(100) === 90, 'discount(100) gave ' + discount(100));\n")},
     kind="answer", answer=r"(?i)(fail|does not pass|doesn't pass|did not pass|not pass|error|91)")


def main():
    if os.path.exists(OUT):
        shutil.rmtree(OUT)
    for t in TASKS:
        for kind, files in (("tasks", t["files"]), ("solutions", t["solution"])):
            for p, c in files.items():
                fp = os.path.join(OUT, kind, t["id"], p)
                os.makedirs(os.path.dirname(fp), exist_ok=True)
                open(fp, "w").write(c)
        if t["hidden"]:
            os.makedirs(os.path.join(OUT, "hidden"), exist_ok=True)
            open(os.path.join(OUT, "hidden", t["id"] + ".js"), "w").write(t["hidden"])
    os.makedirs(ASSET, exist_ok=True)
    json.dump(TASKS, open(os.path.join(ASSET, "node.json"), "w"), indent=0)
    # 1.5.187: the dev split's hidden checks (bench/hybrid/local/hidden), so the phone runs them too, not only analyze.py
    dev = {}
    hd = os.path.join(HERE, "local", "hidden")
    for f in sorted(os.listdir(hd)):
        if f.endswith(".js"): dev[f[:-3]] = open(os.path.join(hd, f)).read()
    json.dump(dev, open(os.path.join(ASSET, "dev_hidden.json"), "w"), indent=0)
    print("written", len(TASKS), "held-out tasks")


if __name__ == "__main__":
    main()
