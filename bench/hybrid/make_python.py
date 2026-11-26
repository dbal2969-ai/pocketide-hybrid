#!/usr/bin/env python3
"""Builds the real-code Python track of the on-device benchmark from Kaggle's Gemma 4 Developer Agent tasks.

Each task is one real fix from Textualize/rich or psf/requests (single file, a few lines), taken from the competition's public
task list (data/tasks.jsonl: problem statement, base commit, reference patch, test patch). Kaggle graded these repo-scale with
hidden tests. Gemini Nano is the same model family (Fast = Gemma 4 E2B, which solved 5 of 114 sound tasks there; Full = E4B, 16 of
114), so here each task is "located": the prompt names the file to fix and the test to run, and the project holds only the
package and the test files the check needs.

  tasks/<id>/        the package at the base commit, the test file with the test patch applied, a conftest
  solutions/<id>/    the reference fix (the file the patch changes, as it is after the patch)
  hidden/<id>.txt    other test files run after the task, to catch collateral damage (pass-to-pass)
  python_tasks.json  id, repo, file to fix, visible test command, hidden command, statement, Kaggle resolve rate

Every task is checked as it is made: the visible test fails at the base commit, and it and the hidden tests pass with the
reference fix (run with the same packages the phone installs). A task that is not sound is left out.

Usage: python3 bench/hybrid/make_python.py <kaggle tasks.jsonl> <task_features.csv> <dir with rich/ and requests/ clones> <python>
"""
import csv, json, os, re, shutil, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "python")

# chosen: single-file fixes, sound in Kaggle's own check, no network (httpbin) in their tests, a spread of difficulty
PICK = ["requests_7427", "requests_6644", "requests_7315", "rich_3894", "rich_3905", "rich_3296", "rich_3518", "rich_3006",
        "rich_3718", "rich_3454", "rich_3067", "rich_3130", "rich_4077", "rich_3882", "rich_3043", "rich_2943"]

# hidden pass-to-pass tests per repo: quick test files that import much of the package
HIDDEN = {"rich": ["tests/test_text.py", "tests/test_segment.py", "tests/test_console.py"],
          "requests": ["tests/test_structures.py", "tests/test_hooks.py"]}

CONFTEST_REQUESTS = '''import os
import sys

import pytest

# the package lives in src/ (the phone runs pytest without PYTHONPATH)
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))


@pytest.fixture(autouse=True)
def clean_proxy_environ(monkeypatch):
    """Remove proxy related environment variables for every test."""
    for var in ("http_proxy", "https_proxy", "no_proxy", "ftp_proxy", "all_proxy"):
        monkeypatch.delenv(var, raising=False)
        monkeypatch.delenv(var.upper(), raising=False)
'''


def sh(cmd, cwd, env=None, timeout=300):
    return subprocess.run(cmd, cwd=cwd, shell=True, capture_output=True, text=True, timeout=timeout, env=env)


def apply(patch, cwd):
    with tempfile.NamedTemporaryFile("w", suffix=".diff", delete=False) as f:
        f.write(patch if patch.endswith("\n") else patch + "\n")   # the task list's patches lack their final newline
    r = sh(f"git apply --whitespace=nowarn {f.name}", cwd)
    os.unlink(f.name)
    return r.returncode == 0, r.stderr


def tests_of(test_patch):
    return sorted(set(re.findall(r"^\+\+\+ b/(\S+)", test_patch, re.M)))


def new_tests(test_patch):
    return re.findall(r"^\+\s*def (test_\w+)", test_patch, re.M)


def build(task, clones, python, rate):
    iid = task["instance_id"]
    repo = "rich" if iid.startswith("rich") else "requests"
    src = os.path.join(clones, repo)
    fix_files = re.findall(r"^--- a/(\S+)", task["patch"], re.M)
    tfiles = tests_of(task["test_patch"])
    with tempfile.TemporaryDirectory() as d:
        work = os.path.join(d, "repo")
        r = sh(f"git worktree add -f --detach {work} {task['base_commit']}", src)
        if r.returncode != 0:
            return None, "checkout: " + r.stderr[:200]
        try:
            ok, err = apply(task["test_patch"], work)
            if not ok:
                return None, "test patch: " + err[:200]
            pkg = "src/requests" if repo == "requests" else "rich"
            pypath = "src" if repo == "requests" else "."
            env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")   # no PYTHONPATH: as on the phone, conftest/rootdir must find the package
            hidden = [h for h in HIDDEN[repo] if os.path.exists(os.path.join(work, h)) and h not in tfiles]
            # the project the phone gets: the package, the test files, a conftest, and nothing else
            proj = os.path.join(d, "proj")
            shutil.copytree(os.path.join(work, pkg), os.path.join(proj, pkg), ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
            os.makedirs(os.path.join(proj, "tests"), exist_ok=True)
            for t in tfiles + hidden:
                shutil.copy(os.path.join(work, t), os.path.join(proj, t))
            for extra in ["tests/__init__.py"] + ([] if repo == "requests" else ["tests/conftest.py"]):
                if os.path.exists(os.path.join(work, extra)):
                    shutil.copy(os.path.join(work, extra), os.path.join(proj, extra))
            # helpers the chosen test files import from tests/ (rich's tests import each other rarely; requests' import .utils)
            for t in tfiles + hidden:
                for m in re.findall(r"^from (?:tests)?\.?(\w+) import|^from \.(\w+) import", open(os.path.join(work, t)).read(), re.M):
                    name = (m[0] or m[1]) + ".py"
                    p = os.path.join(work, "tests", name)
                    if os.path.exists(p) and not os.path.exists(os.path.join(proj, "tests", name)):
                        shutil.copy(p, os.path.join(proj, "tests", name))
            if repo == "requests":
                open(os.path.join(proj, "tests", "conftest.py"), "w").write(CONFTEST_REQUESTS)
            else:
                c = os.path.join(proj, "tests", "conftest.py")
                old = open(c).read() if os.path.exists(c) else ""
                open(c, "w").write("import os\nimport sys\n\nsys.path.insert(0, os.path.join(os.path.dirname(__file__), \"..\"))\n" + old)
            visible = f"{python} -B -m pytest -q -p no:cacheprovider " + " ".join(tfiles)
            hidden_cmd = (f"{python} -B -m pytest -q -p no:cacheprovider " + " ".join(hidden)) if hidden else ""
            before = sh(visible, proj, env)
            if before.returncode == 0:
                return None, "the visible tests already pass at the base commit"
            ok, err = apply(task["patch"], work)
            if not ok:
                return None, "patch: " + err[:200]
            for f in fix_files:
                shutil.copy(os.path.join(work, f), os.path.join(proj, f))
            after = sh(visible, proj, env)
            hid = sh(hidden_cmd, proj, env) if hidden_cmd else None
            if after.returncode != 0:
                return None, "the reference fix fails the visible tests: " + after.stdout[-300:]
            if hid is not None and hid.returncode != 0:
                return None, "the reference fix fails the hidden tests: " + hid.stdout[-300:]
            # write the task: the project at the base commit (put the original file back), the solution, the hidden list
            tdir, sdir = os.path.join(OUT, "tasks", iid), os.path.join(OUT, "solutions", iid)
            shutil.rmtree(tdir, ignore_errors=True); shutil.rmtree(sdir, ignore_errors=True)
            shutil.copytree(proj, tdir)
            for f in fix_files:
                os.makedirs(os.path.dirname(os.path.join(sdir, f)), exist_ok=True)
                shutil.copy(os.path.join(work, f), os.path.join(sdir, f))
            sh(f"git checkout -- {' '.join(fix_files)}", work)
            for f in fix_files:
                shutil.copy(os.path.join(work, f), os.path.join(tdir, f))
            statement = task["problem_statement"].strip()
            statement = re.sub(r"<!--.*?-->", "", statement, flags=re.S)
            statement = re.sub(r"\n{3,}", "\n\n", statement)[:1500]
            # the new tests' code, as the prompt shows it (1.5.187: "port bpo-39057" alone told Nano nothing)
            code = []
            import ast
            for t in tfiles:
                src_t = open(os.path.join(tdir, t)).read()
                lines = src_t.splitlines()
                wanted = set(new_tests(task["test_patch"]))
                # tests the patch changed rather than added: the functions holding its added lines (after-image line numbers)
                touched, cur, in_file = set(), 0, False
                for pl in task["test_patch"].splitlines():
                    if pl.startswith("+++ "): in_file = pl[6:] == t; continue
                    if not in_file: continue
                    m = re.match(r"@@ -\d+(?:,\d+)? \+(\d+)", pl)
                    if m: cur = int(m.group(1)); continue
                    if pl.startswith("+"): touched.add(cur); cur += 1
                    elif not pl.startswith("-"): cur += 1
                for node in ast.walk(ast.parse(src_t)):
                    if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name.startswith("test") and \
                            (node.name in wanted or any(node.lineno <= n <= node.end_lineno for n in touched)):
                        start = min([d.lineno for d in node.decorator_list] + [node.lineno]) - 1
                        code.append("\n".join(lines[start:node.end_lineno]))
            return {
                "test_code": "\n\n".join(dict.fromkeys(code))[:1800],
                "id": iid, "repo": repo, "fix": fix_files[0], "tests": tfiles, "new_tests": new_tests(task["test_patch"]),
                "visible": "python3 -m pytest -q -p no:cacheprovider " + " ".join(tfiles),
                "hidden": ("python3 -m pytest -q -p no:cacheprovider " + " ".join(hidden)) if hidden else "",
                "pythonpath": pypath, "statement": statement, "kaggle_resolve_rate": rate,
                "files": sum(len(fs) for _, _, fs in os.walk(tdir)),
                "bytes": sum(os.path.getsize(os.path.join(a, f)) for a, _, fs in os.walk(tdir) for f in fs),
            }, ""
        finally:
            sh(f"git worktree remove --force {work}", src)


def main():
    tasks_path, features_path, clones, python = sys.argv[1:5]
    rows = {json.loads(l)["instance_id"]: json.loads(l) for l in open(tasks_path)}
    rates = {r["task_id"]: float(r["resolve_rate"]) for r in csv.DictReader(open(features_path)) if r["resolve_rate"]}
    os.makedirs(OUT, exist_ok=True)
    made = []
    for iid in PICK:
        info, why = build(rows[iid], clones, python, rates.get(iid))
        print(f"{iid}: {'sound' if info else 'LEFT OUT'} {why}" + (f" · {info['files']} files, {info['bytes'] // 1024} KB" if info else ""))
        if info:
            made.append(info)
    json.dump(made, open(os.path.join(OUT, "python_tasks.json"), "w"), indent=1)
    # the app's assets: python.json (with each reference fix) and one zip per starting project
    import zipfile
    asset = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "bench", "reliable")
    shutil.rmtree(os.path.join(asset, "python"), ignore_errors=True)
    os.makedirs(os.path.join(asset, "python"), exist_ok=True)
    for t in made:
        sdir = os.path.join(OUT, "solutions", t["id"])
        t["solution"] = {os.path.relpath(os.path.join(a, f), sdir): open(os.path.join(a, f)).read() for a, _, fs in os.walk(sdir) for f in fs}
        tdir = os.path.join(OUT, "tasks", t["id"])
        with zipfile.ZipFile(os.path.join(asset, "python", t["id"] + ".zip"), "w", zipfile.ZIP_DEFLATED) as z:
            for a, _, fs in os.walk(tdir):
                for f in sorted(fs):
                    p = os.path.join(a, f)
                    zi = zipfile.ZipInfo(os.path.relpath(p, tdir), date_time=(2026, 11, 25, 0, 0, 0)); zi.external_attr = 0o644 << 16
                    z.writestr(zi, open(p, "rb").read(), zipfile.ZIP_DEFLATED)
    json.dump(made, open(os.path.join(asset, "python.json"), "w"), indent=0)
    print(f"{len(made)} of {len(PICK)} tasks are sound")


if __name__ == "__main__":
    main()
