"""Proves every hard (or local) task is fair: `node test.js` fails on the starting project and passes once the reference fix is applied.
(h06 is checked by the app's play test on the phone; here only its files are checked to exist.)

Usage: python3 bench/hybrid/check_hard.py bench/hybrid/hard
"""
import os, shutil, subprocess, sys, tempfile

root = sys.argv[1]
bad = 0
for name in sorted(os.listdir(os.path.join(root, "tasks"))):
    task = os.path.join(root, "tasks", name)
    if not os.path.exists(os.path.join(task, "test.js")):
        print(f"{name}: no test.js (a question, or checked by the play test)"); continue
    if not os.path.isdir(os.path.join(root, "solutions", name)):
        print(f"{name}: no reference fix (a task that must change nothing)"); continue
    with tempfile.TemporaryDirectory() as d:
        work = os.path.join(d, "p"); shutil.copytree(task, work)
        before = subprocess.run(["node", "test.js"], cwd=work, capture_output=True, text=True, timeout=60)
        shutil.copytree(os.path.join(root, "solutions", name), work, dirs_exist_ok=True)
        after = subprocess.run(["node", "test.js"], cwd=work, capture_output=True, text=True, timeout=60)
        hid = os.path.join(root, "hidden", name + ".js")
        hidden = None
        if os.path.exists(hid):
            shutil.copy(hid, os.path.join(work, "hidden.js"))
            hidden = subprocess.run(["node", "hidden.js"], cwd=work, capture_output=True, text=True, timeout=60)
    ok = before.returncode != 0 and after.returncode == 0 and after.stdout.strip().endswith("ok") and (hidden is None or hidden.returncode == 0)
    if hidden is not None and hidden.returncode != 0: print(f"  {name}: the reference fix fails its hidden check: {hidden.stderr.strip()[:200]}")
    bad += not ok
    first = (before.stderr or before.stdout).strip().splitlines()[:1]
    print(f"{name}: {'fair' if ok else 'BROKEN'} · before: exit {before.returncode} {first} · after: exit {after.returncode} {after.stdout.strip()[-20:]} {after.stderr.strip()[:200]}")
sys.exit(1 if bad else 0)
