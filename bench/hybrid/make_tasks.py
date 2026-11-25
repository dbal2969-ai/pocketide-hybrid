import os, zipfile, shutil, sys
OUT = sys.argv[1]
T = {}
T["01-fix-add"] = {
 "app.js": "function add(a, b) {\n  return a - b;\n}\nfunction mul(a, b) {\n  return a * b;\n}\nmodule.exports = { add, mul };\n",
 "test.js": "const { add, mul } = require('./app.js');\nlet bad = 0;\nif (add(2, 3) !== 5) { console.error('add(2, 3) gave ' + add(2, 3)); bad++; }\nif (mul(2, 3) !== 6) { console.error('mul broke'); bad++; }\nif (bad) process.exit(1);\nconsole.log('ok');\n",
}
T["02-explain"] = {
 "csv.js": "// Reads CSV text into rows of fields. Quoted fields may hold commas and doubled quotes.\nfunction parseCSV(text) {\n  const rows = [];\n  let row = [], field = '', quoted = false;\n  for (let i = 0; i < text.length; i++) {\n    const c = text[i];\n    if (quoted) {\n      if (c === '\"' && text[i + 1] === '\"') { field += '\"'; i++; }\n      else if (c === '\"') quoted = false;\n      else field += c;\n    } else if (c === '\"') quoted = true;\n    else if (c === ',') { row.push(field); field = ''; }\n    else if (c === '\\n') { row.push(field); rows.push(row); row = []; field = ''; }\n    else field += c;\n  }\n  if (field || row.length) { row.push(field); rows.push(row); }\n  return rows;\n}\nmodule.exports = { parseCSV };\n",
}
T["03-todo-feature"] = {
 "index.html": "<!DOCTYPE html>\n<html>\n<head><meta charset=\"utf-8\"><title>Todo</title><link rel=\"stylesheet\" href=\"style.css\"></head>\n<body>\n  <h1>Todo</h1>\n  <input id=\"new\" placeholder=\"What to do\">\n  <button id=\"add\">Add</button>\n  <ul id=\"list\"></ul>\n  <script src=\"app.js\"></script>\n</body>\n</html>\n",
 "style.css": "body { font-family: sans-serif; margin: 16px; }\nli.done { text-decoration: line-through; color: #888; }\n",
 "app.js": "const list = document.getElementById('list');\nconst input = document.getElementById('new');\nlet items = [];\n\nfunction render() {\n  list.innerHTML = '';\n  items.forEach((it, i) => {\n    const li = document.createElement('li');\n    li.textContent = it.text;\n    if (it.done) li.className = 'done';\n    li.onclick = () => { items[i].done = !items[i].done; render(); };\n    list.appendChild(li);\n  });\n}\n\ndocument.getElementById('add').onclick = () => {\n  const t = input.value.trim();\n  if (!t) return;\n  items.push({ text: t, done: false });\n  input.value = '';\n  render();\n};\n\nrender();\n",
 "tests/play.txt": "# clear completed removes only done items\nrun document.getElementById('new').value = 'milk'\ntap \"Add\"\nrun document.getElementById('new').value = 'eggs'\ntap \"Add\"\ntap \"milk\"\ntap \"Clear completed\"\nexpect document.querySelectorAll('li').length === 1\nexpect document.querySelector('li').textContent === 'eggs'\n",
}
T["04-rename"] = {
 "cart.js": "const { calcTotal } = require('./total.js');\nfunction checkout(items) {\n  return 'Total: ' + calcTotal(items).toFixed(2);\n}\nmodule.exports = { checkout };\n",
 "total.js": "function calcTotal(items) {\n  return items.reduce((s, it) => s + it.price * it.qty, 0);\n}\nmodule.exports = { calcTotal };\n",
 "report.js": "const { calcTotal } = require('./total.js');\nfunction report(orders) {\n  return orders.map(o => o.id + ': ' + calcTotal(o.items)).join('\\n');\n}\nmodule.exports = { report };\n",
 "test.js": "const { checkout } = require('./cart.js');\nconst { report } = require('./report.js');\nconst t = require('./total.js');\nif (!t.orderTotal) { console.error('orderTotal is missing'); process.exit(1); }\nif (checkout([{ price: 2, qty: 3 }]) !== 'Total: 6.00') { console.error('checkout broke'); process.exit(1); }\nif (report([{ id: 'a', items: [{ price: 1, qty: 2 }] }]) !== 'a: 2') { console.error('report broke'); process.exit(1); }\nconsole.log('ok');\n",
}
T["05-keep-definition"] = {
 "app.js": "let selected = [];\n\nfunction pick(id) {\n  if (!selected.includes(id)) selected.push(id);\n  return selected.length;\n}\n\nfunction unpick(id) {\n  selected = selected.filter(x => x !== id);\n  return selected.length;\n}\n\nmodule.exports = { pick, unpick };\n",
 "test.js": "const app = require('./app.js');\nif (app.pick(1) !== 1 || app.pick(2) !== 2 || app.unpick(1) !== 1) { console.error('pick/unpick broke'); process.exit(1); }\nif (typeof app.setDay !== 'function') { console.error('setDay is missing'); process.exit(1); }\napp.setDay(3, 2);\nif (app.dayOf(3) !== 2) { console.error('setDay/dayOf wrong'); process.exit(1); }\nconsole.log('ok');\n",
}
T["06-research-handoff"] = {
 "README.md": "# Date libraries\n\nNotes go in DATES.md.\n",
}
# 07: a long file with one bug (qty vs quantity)
lines = ["// Inventory helpers for a small shop. Each item: { sku, name, price, quantity, tags }"]
for i in range(1, 121):
    lines.append(f"function helper{i}(items) {{\n  // helper {i}: counts items whose sku ends with {i % 10}\n  return items.filter(it => String(it.sku).endsWith('{i % 10}')).length;\n}}")
lines.append("function total(items) {\n  return items.reduce((sum, it) => sum + it.price * it.qty, 0);\n}")
for i in range(121, 161):
    lines.append(f"function label{i}(it) {{\n  return it.name + ' #{i}: ' + it.price.toFixed(2);\n}}")
exports = ", ".join(["total"] + [f"helper{i}" for i in range(1, 121)] + [f"label{i}" for i in range(121, 161)])
lines.append("module.exports = { " + exports + " };")
T["07-long-file"] = {
 "inventory.js": "\n\n".join(lines) + "\n",
 "test.js": "const { total } = require('./inventory.js');\nconst v = total([{ sku: 1, name: 'a', price: 2, quantity: 3 }, { sku: 2, name: 'b', price: 1, quantity: 4 }]);\nif (v !== 10) { console.error('total gave ' + v); process.exit(1); }\nconsole.log('ok');\n",
}
T["08-private-validate"] = {
 "signup.js": "function signup(form) {\n  return { ok: true, email: form.email, name: form.name };\n}\nmodule.exports = { signup };\n",
 "test.js": "const { signup } = require('./signup.js');\nlet bad = 0;\nif (signup({ email: '', name: 'A' }).ok !== false) { console.error('empty email was accepted'); bad++; }\nif (signup({ email: 'not-an-email', name: 'A' }).ok !== false) { console.error('bad email was accepted'); bad++; }\nif (signup({ email: 'a@b.co', name: 'A' }).ok !== true) { console.error('good email was refused'); bad++; }\nif (bad) process.exit(1);\nconsole.log('ok');\n",
}
if os.path.exists(OUT): shutil.rmtree(OUT)
os.makedirs(os.path.join(OUT, "zips"))
for name, files in T.items():
    d = os.path.join(OUT, "tasks", name)
    for p, c in files.items():
        fp = os.path.join(d, p); os.makedirs(os.path.dirname(fp), exist_ok=True)
        open(fp, "w").write(c)
    with zipfile.ZipFile(os.path.join(OUT, "zips", name + ".zip"), "w", zipfile.ZIP_DEFLATED) as z:
        for p, c in sorted(files.items()):
            zi = zipfile.ZipInfo(p, date_time=(2026, 11, 24, 0, 0, 0)); zi.external_attr = 0o644 << 16
            z.writestr(zi, c)
print("written", len(T), "tasks; long file chars:", len(T["07-long-file"]["inventory.js"]))
