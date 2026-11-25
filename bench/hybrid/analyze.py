"""Full analysis of a hybrid benchmark results zip: every task, pass or fail, checked independently of the app's own verdict.

For each task it compares the finished files with the starting project (and the reference fix where there is one), lists every
line the model deleted (flagging those the reference keeps), checks the syntax of every JS, JSON and HTML file, reruns the task's
own test with the original test file, opens index.html in Chromium (console and page errors, files that failed to load, and every
play test replayed with the app's own play-test code), and reads the chat log and Nano's record (nano.jsonl): tool calls,
refusals, attempts to change tests, handoffs, errors and how the request ended. Writes analysis.md and prints a summary.

Usage: python3 bench/hybrid/analyze.py <results.zip> [play scripts dir from GamePlayScriptDump]
"""
import collections, html.parser, json, os, re, shutil, subprocess, sys, tempfile, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
SUITES = {"(hard)": "hard", "(games)": "games", "(local model)": "local", "(from scratch)": "scratch", "(in-app questions)": "app"}
APP_SRC = os.path.join(HERE, "..", "..", "app", "src", "main", "java", "dev", "pocketide", "app")


def app_strings():
    """Every string literal in the app's source, lower-cased: what PocketIDE can show (labels, menus, Help). For the app suite."""
    out = set()
    for root, _, fs in os.walk(APP_SRC):
        for f in fs:
            if f.endswith(".kt"):
                for m in re.finditer(r'"((?:[^"\\]|\\.)*)"', open(os.path.join(root, f), encoding="utf-8").read()):
                    out.add(m.group(1).replace('\\"', '"').replace("\\$", "$").lower())
    return out


def real_label(text, strings):
    t = text.strip().strip(".,:;").lower()
    return len(t) < 3 or any(t in s for s in strings)


def app_answer_problems(answer, strings):
    """Labels and menu paths an answer quotes that the app never shows: invented buttons, menus or settings."""
    found = []
    for q in re.findall(r'["“]([^"”\n]{3,90})["”]|\*\*([^*\n]{3,60})\*\*', answer):
        if q[1] and q[1].rstrip().endswith(":"): continue   # a bold heading of the answer's own list ("**Server Status:**")
        q = q[0] or q[1]
        if not real_label(q, strings): found.append(f'quotes "{q}", which the app never shows')
    for chain in re.findall(r"[^\n.]*?(?:›| > )[^\n.]*", answer):   # → is how answers show a value changing (on → off), not a path
        parts = [x.strip(" *\"“”'`") for x in re.split(r"›| > ", chain)]
        for x in parts:
            x = re.sub(r"^(open|tap|go to|choose|pick|then|in)\s+", "", x, flags=re.I).strip()
            if 2 < len(x) <= 60 and x[:1].isupper() and not real_label(x, strings):
                found.append(f'names "{x}" in a menu path, which the app never shows')
    return list(dict.fromkeys(found))
TEST_NAMES = re.compile(r"(^|/)(test\.js|tests/play\.txt|.*\.test\.js|spec\..*)$")


def read_tree(d):
    out = {}
    if not os.path.isdir(d): return out
    for root, _, files in os.walk(d):
        for f in files:
            p = os.path.relpath(os.path.join(root, f), d).replace(os.sep, "/")
            try: out[p] = open(os.path.join(root, f), encoding="utf-8").read()
            except Exception: out[p] = ""
    return out


def deleted_lines(orig, final):
    have = collections.Counter(l.strip() for l in final.splitlines())
    gone = []
    for l in orig.splitlines():
        k = l.strip()
        if not k: continue
        if have[k] > 0: have[k] -= 1
        else: gone.append(l)
    return gone


KEYWORDS = set("""const let var function return if else for while do switch case break continue new this true false null undefined
typeof instanceof in of class extends super import export from default try catch finally throw async await yield delete void with
module exports require document window console log length push pop map filter reduce forEach join split slice html head body div span
button input script style link meta title type text value width height px auto none block flex the and for you are""".split())


def names(text):
    """The names in [text] that code depends on: identifiers, ids, file names, quoted strings (not keywords or short words)."""
    # comments do not count (a rewritten file may drop "// See SPEC.md"), and only real file extensions make a file name
    text = re.sub(r"/\*.*?\*/|<!--.*?-->", " ", text, flags=re.S)
    text = re.sub(r"(?m)(^|[^:\"'])//.*$", r"\1", text)
    found = set(re.findall(r"[A-Za-z_$][\w$-]*(?:\.(?:js|mjs|json|css|html|md|txt|png|jpg|svg)\b)?", text)) | set(re.findall(r"[\"']([\w./ -]{2,40})[\"']", text))
    return {n for n in found if len(n) > 2 and n.lower() not in KEYWORDS}


def names_elsewhere(files, path):
    """Names that moved to another file of the project count as kept (a refactor)."""
    out = set()
    for p, t in files.items():
        if p != path and not TEST_NAMES.search(p): out |= names(t)
    return out


class Tags(html.parser.HTMLParser):
    VOID = {"meta", "link", "br", "img", "input", "hr", "source", "area", "base", "col", "embed", "param", "track", "wbr"}
    def __init__(self):
        super().__init__(); self.stack = []; self.problems = []; self.counts = collections.Counter(); self.scripts = []
    def handle_starttag(self, tag, attrs):
        self.counts[tag] += 1
        if tag == "script":
            src = dict(attrs).get("src")
            if src: self.scripts.append(src)
        if tag not in self.VOID: self.stack.append(tag)
    def handle_endtag(self, tag):
        self.counts["/" + tag] += 1
        if tag in self.VOID: return
        if tag in self.stack:
            while self.stack and self.stack[-1] != tag: self.problems.append(f"<{self.stack.pop()}> not closed before </{tag}>")
            self.stack.pop()
        else: self.problems.append(f"</{tag}> closes nothing")


def html_problems(text, files, orig_text, where=""):
    t = Tags()
    try: t.feed(text); t.close()
    except Exception as e: return [f"HTML does not parse: {e}"]
    probs = list(dict.fromkeys(t.problems))[:6]
    for tag in ("html", "head", "body"):
        if t.counts["/" + tag] > 1 or t.counts[tag] > 1: probs.append(f"<{tag}> opens {t.counts[tag]} times and closes {t.counts['/' + tag]} times")
    for src in t.scripts:
        # 1.5.185: a script is found relative to its page (app/index.html loads app/app.js), as the browser does
        full = os.path.normpath(os.path.join(os.path.dirname(where), src.split("?")[0])).replace(os.sep, "/") if where else src
        if not src.startswith("http") and full not in files and src not in files: probs.append(f"script {src} does not exist")
    if orig_text is not None:
        o = Tags(); o.feed(orig_text)
        for src in o.scripts:
            if src not in t.scripts: probs.append(f"the page no longer loads {src} (it did before)")
    return probs


def syntax(path, text, tmp):
    if path.endswith(".json"):
        try: json.loads(text); return None
        except Exception as e: return f"{path}: not valid JSON ({e})"
    if path.endswith((".js", ".mjs", ".cjs")):
        f = os.path.join(tmp, "check" + os.path.splitext(path)[1]); open(f, "w").write(text)
        r = subprocess.run(["node", "--check", f], capture_output=True, text=True)
        if r.returncode != 0:
            msg = [l for l in r.stderr.splitlines() if "Error" in l][:1]
            return f"{path}: syntax error ({msg[0] if msg else r.stderr.strip()[:120]})"
    return None


def transcript_facts(t):
    f = {}
    tools = re.findall(r"· round \d+ · tool (\w+)", t)
    f["tools"] = collections.Counter(tools)
    results = re.findall(r"(?m)^→ (.*)$", t)
    f["refused"] = [r[:140] for r in results if re.match(r"(Edit not applied|NOT SAVED|Not written|Not applied|Not done|Error\b|Could not|No such file)", r)]
    f["test_edits"] = re.findall(r"· tool (?:edit_file|write_file|edit_lines)[^\n]*\n\{\"path\":\"([^\"]*(?:test[^\"]*|play\.txt))\"", t)
    f["handoffs"] = sorted(set(re.findall(r"I'm passing this to (.+?)\. Why: (.+?)\.?\s*$", t, re.M)))
    f["escalations"] = re.findall(r"Passing this to the strongest cloud model \(([^)]*)\)\. Why: ([^\n]*)", t)
    f["phone"] = len(re.findall(r"⚡ Ran on this phone", t)); f["cloud"] = re.findall(r"☁ Ran in the cloud \(([^)]*)\)", t)
    f["errors"] = re.findall(r"(?m)^===== [^·]*· error: (.*)$", t)
    f["outcomes"] = re.findall(r"(?m)^===== [^·]*· outcome: (.*)$", t)
    f["ended"] = re.findall(r"(?m)request ended: ([^·]*)", t)
    f["requests"] = len(re.findall(r"request started", t))
    f["notes"] = re.findall(r"(?m)^.*(?:not available to this project|was busy|used up its free request quota|No internet connection|Gemini Nano tried to undo).*$", t)
    i = t.rfind("· assistant said\n")
    f["answer"] = t[i + 17:].split("\n-----")[0].split("\n=====")[0].strip()[:300] if i >= 0 else ""
    return f


def nano_facts(path):
    if not os.path.exists(path): return None
    rows = [json.loads(l) for l in open(path) if l.strip()]
    secs = [float(r.get("seconds", 0)) for r in rows]
    return {"steps": len(rows), "unusable": [r["unusable"] for r in rows if r.get("unusable")],
            "tools": collections.Counter(r.get("tool") or "?" for r in rows), "slowest": max(secs) if secs else 0,
            "mean": sum(secs) / len(secs) if secs else 0}


def main():
    zpath = sys.argv[1]; scripts_root = sys.argv[2] if len(sys.argv) > 2 else None
    work = tempfile.mkdtemp(prefix="analyze-")
    zipfile.ZipFile(zpath).extractall(work)
    report = open(os.path.join(work, "report.md")).read() if os.path.exists(os.path.join(work, "report.md")) else ""
    title = report.splitlines()[0] if report else ""
    suite = next((v for k, v in SUITES.items() if k in title), "")
    suite_dir = os.path.join(HERE, suite) if suite else HERE
    rows = re.findall(r"(?m)^\| (\w+) \| [^|]* \| (✓ passed|✗ failed) \|", report)
    verdicts = dict(rows)
    in_order = [v for _, v in rows]   # the first hard report numbered every task "h0"; then the order decides
    out = [f"# Analysis of {os.path.basename(zpath)}", "", title.lstrip("# "), ""]
    summary = []
    for tid in sorted(d for d in os.listdir(work) if os.path.isdir(os.path.join(work, d, "files")) or os.path.exists(os.path.join(work, d, "transcript.txt"))):
        tdir = os.path.join(work, tid)
        final = read_tree(os.path.join(tdir, "files"))
        orig = read_tree(os.path.join(suite_dir, "project")) if suite == "app" else read_tree(os.path.join(suite_dir, "tasks", tid))
        sol_dir = os.path.join(suite_dir, "solutions", tid)
        ref = {**orig, **read_tree(sol_dir)} if os.path.isdir(sol_dir) else None
        tr = open(os.path.join(tdir, "transcript.txt")).read() if os.path.exists(os.path.join(tdir, "transcript.txt")) else ""
        tf = transcript_facts(tr); nf = nano_facts(os.path.join(tdir, "nano.jsonl"))
        short = tid.split("-")[0]
        app_verdict = verdicts.get(short) if len(verdicts) == len(rows) else None
        if app_verdict is None:
            k = len(summary); app_verdict = in_order[k] if k < len(in_order) else "?"
        problems, notes = [], []
        same = lambda a, b: [l.rstrip() for l in a.rstrip().splitlines()] == [l.rstrip() for l in b.rstrip().splitlines()]
        changed = [p for p in final if p in orig and not same(final[p], orig[p])]   # whitespace at line ends and the file end does not count
        added = [p for p in final if p not in orig]
        removed = [p for p in orig if p not in final]
        tmp = tempfile.mkdtemp()
        # syntax of everything the model touched (and the page it ships)
        for p in changed + added:
            s = syntax(p, final[p], tmp)
            if s: problems.append(s)
            if p.endswith(".html"):
                for h in html_problems(final[p], final, orig.get(p), p): problems.append(f"{p}: {h}")
        for p in removed: problems.append(f"{p} was deleted")
        # deleted lines, and those the reference keeps
        for p in changed:
            gone = deleted_lines(orig[p], final[p])
            if not gone: continue
            # what matters is not the layout but the names: functions, ids, files, selectors the original and the reference both
            # have and the finished file lost (a rewrite in another layout keeps them; l19's deleted <script src="app.js"> does not)
            lost = sorted((names(orig[p]) & names((ref or {}).get(p, orig[p]))) - names(final[p]) - names_elsewhere(final, p))
            if lost and not TEST_NAMES.search(p):
                (problems if ref is not None and not p.endswith(".css") else notes).append(f"{p}: lost {', '.join(lost[:8])}" + ("" if ref is not None else " (no reference fix to compare with; check by hand)"))
            notes.append(f"{p}: {len(gone)} line(s) of the original replaced or rewritten")
        for p in changed:
            if TEST_NAMES.search(p): problems.append(f"{p}: the model changed a test file")
        # the task's own test, with the original test files
        if "test.js" in orig:
            d = tempfile.mkdtemp();
            for p, s in {**final, **{k: v for k, v in orig.items() if TEST_NAMES.search(k)}}.items():
                fp = os.path.join(d, p); os.makedirs(os.path.dirname(fp), exist_ok=True); open(fp, "w").write(s)
            r = subprocess.run(["node", "test.js"], cwd=d, capture_output=True, text=True, timeout=60)
            if r.returncode != 0 or not r.stdout.strip().endswith("ok"):
                problems.append("node test.js (original test) fails: " + (r.stderr or r.stdout).strip().splitlines()[-1][:140] if (r.stderr or r.stdout).strip() else "node test.js fails")
            shutil.rmtree(d, ignore_errors=True)
        # the hidden check: inputs the model never saw (a hard-coded answer passes the visible test but not this)
        hid = os.path.join(suite_dir, "hidden", tid + ".js")
        if os.path.exists(hid) and app_verdict.startswith("✓"):
            d = tempfile.mkdtemp()
            for p, s in final.items():
                fp = os.path.join(d, p); os.makedirs(os.path.dirname(fp), exist_ok=True); open(fp, "w").write(s)
            shutil.copy(hid, os.path.join(d, "hidden.js"))
            r = subprocess.run(["node", "hidden.js"], cwd=d, capture_output=True, text=True, timeout=60)
            if r.returncode != 0:
                problems.append("hidden check fails (inputs the model never saw): " + "; ".join(l for l in (r.stderr or r.stdout).strip().splitlines() if "hidden check" in l or "Error" in l)[:200])
            else: notes.append("hidden check passes")
            shutil.rmtree(d, ignore_errors=True)
        # from scratch: the app played as a person would (scratch/checks.js), by what it is: snake, calculator, todo, tictactoe, tip
        if suite == "scratch":
            kind = tid.split("-")[1] if "-" in tid else ""
            # the page the Preview tab opens: index.html, else the shallowest index.html (s05 built app/index.html)
            pages = sorted((p for p in final if p.split("/")[-1] == "index.html"), key=lambda p: p.count("/"))
            if not pages: problems.append("no index.html was built")
            else:
                if pages[0] != "index.html": notes.append(f"the page is {pages[0]} (not at the top of the project)")
                d = tempfile.mkdtemp()
                for p, s in final.items():
                    fp = os.path.join(d, p); os.makedirs(os.path.dirname(fp), exist_ok=True); open(fp, "w").write(s)
                r = subprocess.run(["node", os.path.join(HERE, "scratch", "checks.js"), os.path.join(d, os.path.dirname(pages[0])), kind], capture_output=True, text=True, timeout=600,
                                   env={**os.environ, "NODE_PATH": subprocess.run(["npm", "root", "-g"], capture_output=True, text=True).stdout.strip()})
                try:
                    cs = json.loads(r.stdout.strip().splitlines()[-1])["checks"]
                    for c in cs:
                        if not c["ok"]: problems.append(f"played: {c['name']} — no" + (f" ({c['detail']})" if c["detail"] else ""))
                    notes.append(f"played in a browser: {sum(c['ok'] for c in cs)} of {len(cs)} checks pass")
                except Exception: problems.append("the play checks did not run: " + (r.stderr.strip()[-200:] or "no output"))
                shutil.rmtree(d, ignore_errors=True)
            notes.append("files built: " + ", ".join(sorted(final)) if final else "no files built")
        # in-app questions: the whole answer, every label and path it quotes checked against the app's source, settings proposals
        if suite == "app":
            strings = app_strings() if "strings" not in dir() else strings
            i = tr.rfind("· assistant said\n")
            full = tr[i + 17:].split("\n-----")[0].split("\n=====")[0].strip() if i >= 0 else ""
            full = "\n".join(l for l in full.splitlines() if not l.startswith(("⚡", "☁")))
            if not full: problems.append("no answer")
            for pr in app_answer_problems(full, strings): problems.append("possibly invented: " + pr)
            props = re.findall(r"settings proposal: ([\w.]+) = (\w+) \(([^)]*)\)", tr)
            for k, v, how in props: notes.append(f"proposed {k} = {v} ({how})")
            for k in re.findall(r"settings proposal refused: ([^\n]*)", tr): notes.append(f"a proposal was refused by the app: {k.strip()}")
            chk = open(os.path.join(tdir, "check.txt")).read() if os.path.exists(os.path.join(tdir, "check.txt")) else ""
            m = re.search(r"Settings changed during the task: (.*)", chk)
            if m: problems.append("settings changed during the task: " + m.group(1))
            if not tf["tools"].get("app_help"): notes.append("app_help was not called")
            notes.append("full answer: " + full.replace("\n", " ⏎ ")[:900])
        # the page in a browser, and the play tests
        if "index.html" in final and suite != "app":
            d = tempfile.mkdtemp()
            for p, s in final.items():
                fp = os.path.join(d, p); os.makedirs(os.path.dirname(fp), exist_ok=True); open(fp, "w").write(s)
            for p, s in orig.items():
                if TEST_NAMES.search(p):
                    fp = os.path.join(d, p); os.makedirs(os.path.dirname(fp), exist_ok=True); open(fp, "w").write(s)
            sd = os.path.join(scripts_root, tid) if scripts_root and os.path.isdir(os.path.join(scripts_root, tid)) else ""
            r = subprocess.run(["node", os.path.join(HERE, "browser_check.js"), d] + ([sd] if sd else []), capture_output=True, text=True, timeout=600,
                               env={**os.environ, "NODE_PATH": subprocess.run(["npm", "root", "-g"], capture_output=True, text=True).stdout.strip()})
            try:
                b = json.loads(r.stdout.strip().splitlines()[-1])
                for e in dict.fromkeys(b["errors"]): problems.append("in the browser: " + e[:160])
                for pt in b["play"]:
                    if not pt["ok"]: problems.append(f"play test \"{pt['name']}\" fails: " + "; ".join(pt["failed"][:2] + pt["errors"][:1])[:200])
                if b["play"]: notes.append(f"play tests replayed: {sum(p['ok'] for p in b['play'])} of {len(b['play'])} pass")
            except Exception: problems.append("the browser check did not run: " + (r.stderr.strip()[-200:] or "no output"))
            shutil.rmtree(d, ignore_errors=True)
        # how the work went
        if tf["test_edits"]: notes.append("tried to change tests: " + ", ".join(sorted(set(tf["test_edits"]))))
        if tf["errors"]: problems.append("request error: " + "; ".join(tf["errors"])[:200])
        if any("stopped or failed" in e for e in tf["ended"]) and app_verdict == "✓ passed": notes.append("ended \"stopped or failed\" although judged passed")
        bad_outcomes = [o for o in tf["outcomes"] if o.startswith("✗")]
        if bad_outcomes: notes.append("app outcome: " + " | ".join(bad_outcomes)[:200])
        my = "fail" if app_verdict.startswith("✗") else ("pass with problems" if problems else "clean pass")
        summary.append((short, tid, app_verdict, my, problems))
        out += [f"## {tid}: {my} (app: {app_verdict})", ""]
        out.append(f"- Files: changed {', '.join(changed) or 'none'}; added {', '.join(added) or 'none'}; deleted {', '.join(removed) or 'none'}")
        out.append(f"- Steps: {tf['phone']} on the phone, {len(tf['cloud'])} in the cloud ({', '.join(f'{m} ×{n}' for m, n in collections.Counter(tf['cloud']).items()) or 'none'}); "
                   f"tools {dict(tf['tools'])}; {len(tf['refused'])} refused")
        if nf: out.append(f"- Nano: {nf['steps']} exchanges, mean {nf['mean']:.1f} s, slowest {nf['slowest']:.0f} s; unusable replies {len(nf['unusable'])}")
        for h in tf["handoffs"]: out.append(f"- Handoff to {h[0]}: {h[1]}")
        for e in tf["escalations"]: out.append(f"- Moved up to {e[0]}: {e[1]}")
        for n in dict.fromkeys(tf["notes"]): out.append(f"- Note: {n.strip()[:200]}")
        if tf["answer"]: out.append(f"- Final answer: {tf['answer'][:200]!r}")
        for r in tf["refused"][:5]: out.append(f"- Refused: {r}")
        for p in problems: out.append(f"- **Problem:** {p}")
        for n in notes: out.append(f"- {n}")
        out.append("")
        shutil.rmtree(tmp, ignore_errors=True)
    head = ["| Task | App | Analysis | Problems |", "| --- | --- | --- | --- |"] + [
        f"| {s} | {a} | {m} | {'; '.join(p)[:220].replace('|', '/') or ''} |" for s, _, a, m, p in summary]
    counts = collections.Counter(m for *_, m, _ in [(x[0], x[1], x[2], x[3], x[4]) for x in summary] for m in [m])
    verdict_line = f"**App: {sum(a.startswith('✓') for _, _, a, _, _ in summary)} passed of {len(summary)} · analysis: " + \
        f"{sum(m == 'clean pass' for *_, m, _ in summary)} clean passes, {sum(m == 'pass with problems' for *_, m, _ in summary)} passes with problems, " + \
        f"{sum(m == 'fail' for *_, m, _ in summary)} fails**"
    text = "\n".join(out[:4] + [verdict_line, ""] + head + [""] + out[4:])
    dest = os.path.splitext(zpath)[0] + "-analysis.md"
    try: open(dest, "w").write(text)
    except Exception: dest = os.path.join(work, "analysis.md"); open(dest, "w").write(text)
    print(verdict_line); print("\n".join(head)); print("written", dest)


main()
