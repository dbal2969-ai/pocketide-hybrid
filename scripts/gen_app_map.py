#!/usr/bin/env python3
"""Writes core/AppMapData.kt: every setting, menu item and button PocketIDE shows, with the exact label and the path to it.

The labels are read from the screens' own source, so the assistant's app_help tool (AppHelp) answers from what the app really
shows. Run it after changing a label, a settings group or a menu; CI fails when the file is out of date, and AppMapTest checks
that every label is still in the source word for word.

Usage: python3 scripts/gen_app_map.py            (writes the file)
       python3 scripts/gen_app_map.py --check    (exit 1 when the file is out of date)
"""
import os, re, sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "java", "dev", "pocketide", "app")
OUT = os.path.join(ROOT, "core", "AppMapData.kt")

STR = r'"((?:[^"\\]|\\.)*)"'


def src(name):
    with open(os.path.join(ROOT, name), encoding="utf-8") as f:
        return f.read()


def unq(s):
    """A Kotlin string literal's text, or None when it has a template (its text is not fixed)."""
    if re.search(r'(?<!\\)\$[{a-zA-Z]', s):
        return None
    return s.replace('\\"', '"').replace("\\$", "$").replace("\\n", "\n").replace("\\\\", "\\")


def block(text, start, end_marker):
    i = text.index(start)
    j = text.index(end_marker, i)
    return text[i:j]


items = []  # (where, label, setting, kind)
seen = set()


def add(where, label, setting=None, kind="BUTTON"):
    if label is None or not label.strip():
        return
    k = (where, label)
    if k in seen:
        return
    seen.add(k)
    items.append((where, label, setting, kind))


main = src("MainActivity.kt")

# ---- the top bar and tabs ----
for label in ("Code", "Preview", "Terminal", "AI"):
    add("Tabs at the top of a project", label, kind="TAB")
add("Top bar: Run (long-press for targets, custom commands and checks)", "▶", kind="BUTTON")
add("Top bar: Settings", "⚙", kind="BUTTON")
add("Top bar: Menu", "⋮", kind="BUTTON")

# ---- ⋮ Menu ----
menu = block(main, "private fun moreSections()", "private fun moreDialog()")
section = None
for m in re.finditer(r'^\s*' + STR + r' to (listOf\(|\{)', menu, re.M):
    text = unq(m.group(1))
    if m.group(2) == "listOf(":
        section = text
    elif section:
        add("⋮ Menu › " + section, text, kind="MENU")

# ---- ⚙ Settings ----
dialog = block(main, "private fun settingsDialog(", "// keep per-provider edits until Save")
views = {}
for m in re.finditer(r'val (\w+) = CheckBox\(this\)\.also \{ it\.text = ' + STR + r'; it\.isChecked = (?:settings\.(\w+)|(\w+))', dialog):
    views[m.group(1)] = (unq(m.group(2)), m.group(3), "CHECKBOX")
for m in re.finditer(r'val (\w+) = EditText\(this\)\.also \{ it\.hint = ' + STR, dialog):
    views[m.group(1)] = (unq(m.group(2)), None, "FIELD")
for m in re.finditer(r'(?:val (\w+) = )?Ui\.button\(this, ' + STR, dialog):
    if m.group(1):
        views[m.group(1)] = (unq(m.group(2)), None, "BUTTON")
for m in re.finditer(r'(\w+)\.hint = ' + STR, dialog):
    if m.group(1) not in views:
        views[m.group(1)] = (unq(m.group(2)), None, "FIELD")
for m in re.finditer(r'val (\w+) = Ui\.text\(this, ' + STR, dialog):
    views.setdefault(m.group(1), (unq(m.group(2)), None, "NOTE"))
for m in re.finditer(r'^\s*group\(' + STR + r', (.*)\)\s*$', dialog, re.M):
    group = unq(m.group(1))
    where = "⚙ Settings › " + group
    args = m.group(2)
    if group == "Add-ons":
        add(where, "one box per add-on: its name and what it does", kind="NOTE")
        continue
    for name in re.findall(r'\b([a-zA-Z]\w*)\b', args):
        if name in views:
            label, setting, kind = views[name]
            if kind == "NOTE" and name.endswith("Label"):
                add(where, label, kind="NOTE")
            elif kind != "NOTE":
                add(where, label, setting, kind)
    for lit in re.findall(r'Ui\.text\(this, ' + STR, args):
        add(where, unq(lit), kind="NOTE")
for m in re.finditer(r'Ui\.chip\(this, ' + STR, dialog):
    add("⚙ Settings › AI provider", unq(m.group(1)))
add("⚙ Settings › AI provider", "How do I get a free key?")
add("⚙ Settings › AI provider", "API key", kind="FIELD")
add("⚙ Settings › AI provider", "Model", kind="FIELD")

# ---- Hybrid AI screen ----
hyb = src("HybridScreen.kt")
HYB = "⋮ Menu › AI › Models (local AI) › Open hybrid AI"
heading = None
for line in hyb.splitlines():
    h = re.search(r'fs\.heading\(' + STR + r'\)', line)
    if h:
        heading = unq(h.group(1))
        continue
    if heading in (None, "Not set up in this build", "Benchmark", "App Check (debug build)"):
        continue
    where = HYB + " › " + heading
    for b in re.findall(r'Ui\.button\(ctx, ' + STR, line):
        add(where, unq(b))
    t = re.search(r'^\s*text = ' + STR + r'\s*$', line)
    if t:
        add(where, unq(t.group(1)), {"Private": "hybrid.private", "Use the Full": "hybrid.full_model"}.get(
            next((k for k in ("Private", "Use the Full") if unq(t.group(1)).startswith(k)), ""), None), "CHECKBOX")
    for f in re.findall(r'field\(' + STR, line):
        add(where, unq(f), kind="FIELD")
add(HYB + " › Model settings", "Cloud model (used when Gemini Nano passes a request on)", "hybrid.cloud_model", "CHOICE")

# ---- Multi-model workflow and advisor ----
wf = src("WorkflowScreen.kt")
WF = "⋮ Menu › AI › Multi-model workflow and advisor"
for m in re.finditer(r'slotRow\(' + STR, wf):
    add(WF, unq(m.group(1)), kind="NOTE")
add(WF, "ADVISOR", kind="NOTE")

# ---- the AI tab ----
chat = src("ChatPane.kt")
AI = "AI tab"
for label in ("Model", "Chats"):
    add(AI + " (chat header)", label)
add(AI + " (chat header): More chat options", "⋯")
add(AI + " (chat header): Speak your request (voice input)", "🎤", kind="BUTTON")
mm = block(chat, "private fun moreMenu()", "// ---- export and summary ----")
for m in re.finditer(r'^\s*' + STR + r' to \{', mm, re.M):
    add(AI + " › ⋯", unq(m.group(1)), kind="MENU")
mode = block(chat, "private fun modeMenu()", "private fun scopeMenu()")
for m in re.finditer(r'^\s*' + STR + r'[,)]', mode, re.M):
    add(AI + " › mode chip (Ask / Plan / Agent)", unq(m.group(1)), "mode", "CHOICE")
ex = block(chat, "private fun exportMenu()", "android.app.AlertDialog.Builder(ctx).setTitle(\"Export or summarize\")") if "private fun exportMenu()" in chat else ""
for m in re.finditer(r'^\s*' + STR + r' to \{', ex, re.M):
    add(AI + " › ⋯ › Export or summarize this chat…", unq(m.group(1)), kind="MENU")
for lab in ("Undo changes", "■ Stop", "↪ Steer", "⏱ Queue", "Retry", "Switch model", "Try another provider"):
    add(AI + " (while or after the assistant works)", lab)


def kt(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$").replace("\n", "\\n") + '"'


lines = ["// Generated by scripts/gen_app_map.py from the app's screens. Do not edit by hand: change the screen, then run the script.",
         "package dev.pocketide.app.core", "",
         "/** Every setting, menu item and button the app shows, with its exact label and where it is (see AppMap). */",
         "object AppMapData {", "    val ITEMS: List<AppMap.Item> = listOf("]
for where, label, setting, kind in items:
    lines.append("        AppMap.Item(%s, %s, %s, AppMap.Kind.%s)," % (kt(where), kt(label), kt(setting) if setting else "null", kind))
lines += ["    )", "}", ""]
out = "\n".join(lines)

if "--check" in sys.argv:
    cur = open(OUT, encoding="utf-8").read() if os.path.exists(OUT) else ""
    if cur != out:
        print("core/AppMapData.kt is out of date: run python3 scripts/gen_app_map.py")
        sys.exit(1)
    print("AppMapData.kt is up to date (%d items)" % len(items))
else:
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(out)
    print("wrote %d items to %s" % (len(items), os.path.relpath(OUT)))
