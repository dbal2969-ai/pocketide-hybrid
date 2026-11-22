#!/usr/bin/env python3
"""Drive PocketIDE on an Android emulator from batches of steps (1.5.126; see .github/workflows/emulator.yml).

The tester (a person or an agent) pushes JSON files to the branch `emu/cmd` under `b/` (b/001.json, b/002.json, ...). This
driver, running inside the workflow next to a booted emulator, fetches that branch every few seconds, runs each new batch in name
order, and force-pushes everything it has produced so far to the branch `emu/out`:

  status.txt            emulator and APK details, last update time
  <batch>/log.txt       one line per step (ok / FAIL and why), plus the screen's text where a step asked to look
  <batch>/NN-label.jpg  screenshots asked for with "shot"
  <batch>/NN-label.txt  the screen's elements at that moment (text, description, id, clickable, bounds)
  <batch>/crash.txt     crash buffer and the app's warnings and errors from logcat during the batch

A batch:  {"fresh": true, "setup": {...}, "steps": [ ... ]}
  fresh   clear the app's data before the batch (a first run)
  setup   write the app's settings before it starts: {"provider": "cloudflare", "model": "...", "welcomed": true}.
          Keys come from the workflow's secrets (KEY_<PROVIDER>, BASE_<PROVIDER>) and never appear in the output.
Steps (one key each, plus options):
  {"launch": true}                  start the app
  {"tap": "Text"}                   tap the element whose text or description is Text (exact first, then contains);
                                    options: "index" (nth match), "id" instead of text
  {"long": "Text"}                  long-press it
  {"xy": [x, y]}                    tap a point (screen pixels)
  {"type": "text"}                  type into the focused field
  {"key": "BACK"}                   a key: BACK, ENTER, HOME, TAB, DEL, MENU, ...
  {"swipe": "up"}                   up, down, left, right
  {"wait": 2}                       seconds
  {"wait_text": "Done", "timeout": 60}   wait until the text is on screen ("exact": true: an element's whole text)
  {"gone_text": "Thinking", "timeout": 300} wait until it is not
  {"look": "label"}                 write the screen's text to the log
  {"shot": "label"}                 screenshot plus the screen's elements
  {"expect": "Text"}                FAIL unless the text is on screen; "expect_not" the opposite
  {"shell": "cmd"}                  adb shell command, output to the log
  {"app_cat": "files/x"}            print a file from the app's private folder (debug build)
  {"app_ls": "files"}               list a folder in the app's private folder
  {"install": "apk/other.apk"}      install another APK pushed to emu/cmd
A file named `stop` on `emu/cmd` ends the session (checked between batches). Batches and a stop file already on the branch when
the session starts are skipped (status.txt lists them), so send new batches after "ready", with new names.
"""
import json, os, re, subprocess, sys, time, xml.etree.ElementTree as ET

ADB = os.environ.get("ADB", "adb")
REPO = os.environ.get("GITHUB_WORKSPACE", ".")
OUT = os.environ.get("EMU_OUT", "/tmp/emu-out")
APK = os.environ.get("EMU_APK", "")
PKG = os.environ.get("EMU_PKG", "dev.pocketide.gpl")
ACTIVITY = os.environ.get("EMU_ACTIVITY", "dev.pocketide.app.MainActivity")
DEADLINE = time.time() + 60 * float(os.environ.get("EMU_MINUTES", "100"))
SECRETS = [v for k, v in os.environ.items() if (k.startswith("KEY_") or k.startswith("BASE_")) and v]


def hide(s):
    for v in SECRETS:
        s = s.replace(v, "***")
    return s


def run(args, timeout=90, inp=None):
    try:
        p = subprocess.run(args, capture_output=True, timeout=timeout, input=inp)
        return p.returncode, p.stdout.decode("utf-8", "replace"), p.stderr.decode("utf-8", "replace")
    except subprocess.TimeoutExpired:
        return 124, "", "timed out"


def adb(*a, timeout=90):
    return run([ADB, *a], timeout=timeout)


def shell(cmd, timeout=90):
    return adb("shell", cmd, timeout=timeout)


def git(*a, env=None, timeout=120):
    e = dict(os.environ, **(env or {}))
    p = subprocess.run(["git", "-C", REPO, *a], capture_output=True, timeout=timeout, env=e)
    return p.returncode, p.stdout.decode("utf-8", "replace").strip(), p.stderr.decode("utf-8", "replace").strip()


# ---------- the screen ----------

BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def parse_ui(xml_text):
    """The elements of a uiautomator dump, in document order."""
    nodes = []
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        return nodes
    for n in root.iter("node"):
        m = BOUNDS.match(n.get("bounds", ""))
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        nodes.append({
            "text": n.get("text", ""), "desc": n.get("content-desc", ""), "id": n.get("resource-id", "").split("/")[-1],
            "cls": n.get("class", "").split(".")[-1], "click": n.get("clickable") == "true",
            "check": n.get("checked") == "true", "checkable": n.get("checkable") == "true",
            "enabled": n.get("enabled") != "false", "focused": n.get("focused") == "true",
            "scroll": n.get("scrollable") == "true", "b": (x1, y1, x2, y2),
        })
    return nodes


def summary(nodes):
    lines = []
    for n in nodes:
        if not (n["text"] or n["desc"] or n["click"] or n["scroll"]):
            continue
        if n["b"][2] - n["b"][0] <= 0 or n["b"][3] - n["b"][1] <= 0:
            continue
        flags = "".join([
            " CLICK" if n["click"] else "", " SCROLL" if n["scroll"] else "",
            (" [x]" if n["check"] else " [ ]") if n["checkable"] else "",
            " DISABLED" if not n["enabled"] else "", " FOCUSED" if n["focused"] else "",
        ])
        label = n["text"].replace("\n", " / ")
        if n["desc"] and n["desc"] != n["text"]:
            label += f"  (desc: {n['desc']})"
        lines.append(f"{n['cls']}{' #' + n['id'] if n['id'] else ''}: {label!r}{flags} {list(n['b'])}")
    return hide("\n".join(lines))


def find(nodes, text=None, ident=None, index=0):
    """Exact text or description first, then case-insensitive contains. Visible elements only."""
    vis = [n for n in nodes if n["b"][2] > n["b"][0] and n["b"][3] > n["b"][1]]
    if ident:
        hits = [n for n in vis if n["id"] == ident]
    else:
        hits = [n for n in vis if text in (n["text"], n["desc"])]
        if not hits:
            low = text.lower()
            hits = [n for n in vis if low in n["text"].lower() or low in n["desc"].lower()]
    return hits[index] if len(hits) > index else None


def centre(n):
    x1, y1, x2, y2 = n["b"]
    return (x1 + x2) // 2, (y1 + y2) // 2


BUSY = [{"text": "(screen busy: Android could not read it; see the screenshot)", "desc": "", "id": "", "cls": "Note", "click": False,
         "check": False, "checkable": False, "enabled": True, "focused": False, "scroll": False, "b": (0, 0, 1, 1)}]


def dump():
    """The screen's elements. While an app animates (a spinner, a reply streaming in) uiautomator cannot get an idle screen; the old
    file was then read again and stale elements reported (the first emulator session). The file is removed first, so a failed read
    says the screen is busy instead."""
    for _ in range(5):
        code, out, _ = shell("rm -f /sdcard/ui.xml; uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml 2>/dev/null", timeout=60)
        if code == 0 and "<hierarchy" in out:
            return parse_ui(out[out.index("<"):])
        time.sleep(2)
    return list(BUSY)


def screen_text(nodes):
    return " ".join((n["text"] + " " + n["desc"]) for n in nodes).lower()


def on_screen(nodes, text, exact):
    """Contains-match over all the screen's text, or with exact, an element whose whole text or description is the text."""
    if exact:
        return any(text in (n["text"], n["desc"]) for n in nodes if n["b"][2] > n["b"][0] and n["b"][3] > n["b"][1])
    return text.lower() in screen_text(nodes)


def screenshot(path):
    code, _, _ = run(["sh", "-c", f"{ADB} exec-out screencap -p > /tmp/s.png"], timeout=60)
    if code != 0:
        return False
    try:
        from PIL import Image
        im = Image.open("/tmp/s.png").convert("RGB")
        w, h = im.size
        if w > 720:
            im = im.resize((720, int(h * 720 / w)))
        im.save(path, "JPEG", quality=72)
        return True
    except Exception:
        os.replace("/tmp/s.png", path.replace(".jpg", ".png"))
        return True


def type_text(s):
    # adb's input text: %s is a space; shell specials need a backslash. Sent in short pieces with a pause between them: with no
    # pause the end of a piece was lost when the next arrived (emulator test of 1.5.136).
    for i in range(0, len(s), 30):
        part = s[i:i + 30]
        esc = "".join("%s" if c == " " else ("\\" + c if c in "&|<>;()$`\\\"*?#~!{}[]" else c) for c in part)
        esc = esc.replace("'", "\\'")
        shell(f"input text {esc}")
        time.sleep(0.6)


# ---------- settings before a start ----------

def write_settings(setup):
    """The app's own settings file, written as the debug build allows (run-as). Keys come from the job's secrets."""
    prov = setup.get("provider", "")
    entries = []
    def s(k, v):
        entries.append(f'    <string name="{k}">{v.replace("&", "&amp;").replace("<", "&lt;")}</string>')
    def b(k, v):
        entries.append(f'    <boolean name="{k}" value="{"true" if v else "false"}" />')
    if prov:
        s("provider", prov)
        key = os.environ.get("KEY_" + prov.upper(), "")
        if not key:
            return f"no secret KEY_{prov.upper()} in this job"
        s("key." + prov, key)
        base = os.environ.get("BASE_" + prov.upper(), "")
        if base:
            s("base." + prov, base)
        if setup.get("model"):
            s("model." + prov, setup["model"])
    if setup.get("welcomed", True):
        b("welcomed", True)
    for k, v in (setup.get("strings") or {}).items():
        s(k, v)
    for k, v in (setup.get("booleans") or {}).items():
        b(k, v)
    xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n" + "\n".join(entries) + "\n</map>\n"
    with open("/tmp/prefs.xml", "w") as f:
        f.write(xml)
    shell(f"am force-stop {PKG}")
    adb("push", "/tmp/prefs.xml", "/data/local/tmp/pocketide-prefs.xml")
    shell("chmod 644 /data/local/tmp/pocketide-prefs.xml")
    code, out, err = shell(f"run-as {PKG} sh -c 'mkdir -p shared_prefs && cp /data/local/tmp/pocketide-prefs.xml shared_prefs/pocketide.xml'")
    shell("rm /data/local/tmp/pocketide-prefs.xml")
    return "" if code == 0 else f"run-as failed: {err.strip() or out.strip()}"


# ---------- a batch ----------

def run_batch(name, batch):
    d = os.path.join(OUT, name)
    os.makedirs(d, exist_ok=True)
    log = []
    def note(s):
        log.append(hide(s))
    shell("logcat -c; logcat -b crash -c")
    if batch.get("fresh"):
        shell(f"pm clear {PKG}")
        shell(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")   # clearing the data also took the permission back
        note("fresh: app data cleared")
    if "setup" in batch:
        err = write_settings(batch["setup"])
        note("setup: " + (err or f"settings written for {batch['setup'].get('provider') or 'no provider'}"))
    started = time.time()
    shots = 0
    for i, st in enumerate(batch.get("steps", []), 1):
        if time.time() > DEADLINE:
            note(f"{i:02d} STOPPED: session time is up")
            break
        op = next((k for k in st if k not in ("index", "id", "timeout", "label", "exact")), "")
        arg = st.get(op)
        res = "ok"
        try:
            if op == "launch":
                code, out, err = shell(f"am start -W -n {PKG}/{ACTIVITY}", timeout=120)
                res = "ok" if code == 0 and "Error" not in out else f"FAIL {out.strip()} {err.strip()}"
                time.sleep(2)
            elif op in ("tap", "long"):
                time.sleep(0.8)   # let a moving layout settle (the keyboard opening shifts dialogs), then find the element
                nodes = dump()
                n = find(nodes, text=None if st.get("id") else arg, ident=st.get("id"), index=st.get("index", 0))
                if not n:
                    res = f"FAIL not on screen: {arg or st.get('id')!r}"
                else:
                    x, y = centre(n)
                    if op == "tap":
                        shell(f"input tap {x} {y}")
                    else:
                        shell(f"input swipe {x} {y} {x} {y} 900")
                    res = f"ok at {x},{y} ({n['text'] or n['desc'] or n['id']!r})"
                time.sleep(1.2)
            elif op == "xy":
                shell(f"input tap {arg[0]} {arg[1]}"); time.sleep(1.2)
            elif op == "type":
                type_text(str(arg)); time.sleep(0.5)
            elif op == "key":
                shell(f"input keyevent KEYCODE_{str(arg).upper()}"); time.sleep(1)
            elif op == "swipe":
                pts = {"up": "540 1700 540 600", "down": "540 600 540 1700", "left": "900 1200 150 1200", "right": "150 1200 900 1200"}
                shell(f"input swipe {pts[arg]} 350"); time.sleep(1)
            elif op == "wait":
                time.sleep(float(arg))
            elif op in ("wait_text", "gone_text"):
                limit = time.time() + float(st.get("timeout", 60))
                want = op == "wait_text"
                res = f"FAIL after {st.get('timeout', 60)}s: {arg!r} {'never appeared' if want else 'still there'}"
                while time.time() < limit:
                    there = on_screen(dump(), str(arg), st.get("exact", False))
                    if there == want:
                        res = "ok"
                        break
                    time.sleep(2)
            elif op == "look":
                res = "ok\n" + summary(dump())
            elif op == "shot":
                shots += 1
                base = os.path.join(d, f"{i:02d}-{re.sub(r'[^a-zA-Z0-9_-]+', '-', str(arg))[:40]}")
                ok = screenshot(base + ".jpg")
                with open(base + ".txt", "w") as f:
                    f.write(summary(dump()))
                res = "ok" if ok else "FAIL screenshot"
            elif op in ("expect", "expect_not"):
                there = on_screen(dump(), str(arg), st.get("exact", False))
                res = "ok" if there == (op == "expect") else f"FAIL {arg!r} {'not on screen' if op == 'expect' else 'is on screen'}"
            elif op == "shell":
                code, out, err = shell(str(arg), timeout=float(st.get("timeout", 90)))
                res = f"exit {code}\n{(out + err).strip()[-3000:]}"
            elif op == "install":
                # an extra APK pushed to emu/cmd (for tests that need two apps, such as the MIT edition next to the GPL one)
                path = "/tmp/extra.apk"
                with open(path, "wb") as fh:
                    fh.write(subprocess.run(["git", "-C", REPO, "show", f"refs/remotes/origin/emu/cmd:{arg}"], capture_output=True).stdout)
                code, out, err = adb("install", "-r", "-g", path, timeout=300)
                res = f"exit {code}: {(out + err).strip()[-500:]}"
            elif op == "app_cat":
                code, out, err = shell(f"run-as {PKG} cat '{arg}'")
                res = f"exit {code}\n{(out + err)[-6000:]}"
            elif op == "app_ls":
                code, out, err = shell(f"run-as {PKG} ls -la '{arg}'")
                res = f"exit {code}\n{(out + err)[-4000:]}"
            else:
                res = f"FAIL unknown step {st!r}"
        except Exception as e:
            res = f"FAIL {type(e).__name__}: {e}"
        note(f"{i:02d} {op} {json.dumps(arg) if arg is not True else ''}: {res}")
    # Always end with the screen as it was left, and what crashed.
    screenshot(os.path.join(d, "99-end.jpg"))
    with open(os.path.join(d, "99-end.txt"), "w") as f:
        f.write(summary(dump()))
    _, crash, _ = adb("logcat", "-d", "-b", "crash")
    _, pid, _ = shell(f"pidof {PKG}")
    _, warn, _ = adb("logcat", "-d", "*:W")
    app_lines = [l for l in warn.splitlines() if ("pocketide" in l.lower() or "AndroidRuntime" in l or "FATAL" in l)]
    with open(os.path.join(d, "crash.txt"), "w") as f:
        f.write(hide(f"app running: {'yes, pid ' + pid.strip() if pid.strip() else 'NO'}\n\n== crash buffer ==\n{crash.strip() or '(empty)'}\n\n"
                     f"== app warnings and errors ==\n" + "\n".join(app_lines[-300:])))
    if crash.strip():
        note("CRASH: see crash.txt")
    note(f"batch took {int(time.time() - started)}s, app running: {'yes' if pid.strip() else 'NO'}")
    with open(os.path.join(d, "log.txt"), "w") as f:
        f.write("\n".join(log) + "\n")


# ---------- the session ----------

def publish(msg):
    """Everything produced so far, as one commit on emu/out (force-pushed, so the branch never grows)."""
    env = {"GIT_INDEX_FILE": "/tmp/emu-index", "GIT_AUTHOR_NAME": "emulator", "GIT_AUTHOR_EMAIL": "emulator@localhost",
           "GIT_COMMITTER_NAME": "emulator", "GIT_COMMITTER_EMAIL": "emulator@localhost"}
    if os.path.exists("/tmp/emu-index"):
        os.remove("/tmp/emu-index")
    git(f"--work-tree={OUT}", "add", "-A", "-f", ".", env=env)
    _, tree, _ = git("write-tree", env=env)
    _, commit, _ = git("commit-tree", tree, "-m", msg, env=env)
    code, _, err = git("push", "-f", "origin", f"{commit}:refs/heads/emu/out")
    if code != 0:
        print("push failed:", err, file=sys.stderr)


def status(extra=""):
    _, model, _ = shell("getprop ro.product.model")
    _, rel, _ = shell("getprop ro.build.version.release")
    _, ver, _ = shell(f"dumpsys package {PKG} | grep -m2 -E 'versionName|versionCode'")
    with open(os.path.join(OUT, "status.txt"), "w") as f:
        f.write(f"updated {time.strftime('%Y-%m-%d %H:%M:%S', time.gmtime())} UTC\nemulator: {model.strip()}, Android {rel.strip()}\n"
                f"package {PKG}\n{ver.strip()}\nsession ends {time.strftime('%H:%M:%S', time.gmtime(DEADLINE))} UTC\n{extra}\n")


def main():
    os.makedirs(OUT, exist_ok=True)
    code, out, err = adb("install", "-r", "-g", APK, timeout=300)
    shell("settings put global window_animation_scale 0; settings put global transition_animation_scale 0; settings put global animator_duration_scale 0")
    status(f"install: {out.strip()} {err.strip()}")
    # Batches and a stop file already on emu/cmd belong to an earlier session: they are skipped, so only batches pushed after
    # "ready" run (an old session's batches once ran first and hid the new ones, which had the same names).
    git("fetch", "-q", "--depth=1", "origin", "+refs/heads/emu/cmd:refs/remotes/origin/emu/cmd")
    _, old, _ = git("ls-tree", "--name-only", "refs/remotes/origin/emu/cmd", "b/")
    _, old_top, _ = git("ls-tree", "--name-only", "refs/remotes/origin/emu/cmd")
    done = set(f for f in old.splitlines() if f.endswith(".json"))
    old_stop = "stop" in old_top.splitlines()
    # the old stop file's contents: a stop pushed over it (the same name, new contents) is a real stop (1.5.146; one was ignored)
    old_stop_id = git("rev-parse", "refs/remotes/origin/emu/cmd:stop")[1].strip() if old_stop else ""
    status("skipped from an earlier session: " + (", ".join(sorted(done)) or "nothing") + (" and a stop file" if old_stop else "") +
           "\nsend new batches with names not in that list")
    publish("ready")
    while time.time() < DEADLINE:
        git("fetch", "-q", "--depth=1", "origin", "+refs/heads/emu/cmd:refs/remotes/origin/emu/cmd")
        _, files, _ = git("ls-tree", "--name-only", "refs/remotes/origin/emu/cmd", "b/")
        _, top, _ = git("ls-tree", "--name-only", "refs/remotes/origin/emu/cmd")
        stop_now = "stop" in top.splitlines() and (not old_stop or git("rev-parse", "refs/remotes/origin/emu/cmd:stop")[1].strip() != old_stop_id)
        if "stop" not in top.splitlines():
            old_stop = False   # a stop file pushed after this one was removed is a real stop
        if stop_now:
            break
        new = sorted(f for f in files.splitlines() if f.endswith(".json") and f not in done)
        for f in new:
            name = os.path.basename(f)[:-5]
            _, body, _ = git("show", f"refs/remotes/origin/emu/cmd:{f}")
            try:
                batch = json.loads(body)
            except json.JSONDecodeError as e:
                os.makedirs(os.path.join(OUT, name), exist_ok=True)
                with open(os.path.join(OUT, name, "log.txt"), "w") as fh:
                    fh.write(f"FAIL not valid JSON: {e}\n")
                batch = None
            if batch is not None:
                run_batch(name, batch)
            done.add(f)
            status(f"last batch: {name}")
            publish(f"batch {name}")
        time.sleep(6)
    status("session over")
    publish("session over")


if __name__ == "__main__":
    main()
