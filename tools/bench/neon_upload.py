#!/usr/bin/env python3
"""Upload LiveBench reports to a Neon Postgres database (1.5.175), so runs from any machine land in one place.

Usage: neon_upload.py [--source NAME] [--suite SUITE] [--commit SHA] [--notes TEXT] REPORT_OR_FOLDER...
  NEON_DATABASE_URL  the database's connection string (postgresql://user:password@host/db?sslmode=require); never put it in a file
                     in the repository. GitHub Actions: a secret of that name.
  --source           where the runs happened: workspace, actions, phone, codespace (default workspace)
  --suite            the suite name when it is not a folder name in the path (standard, hard, heldout, trust, ...)

Each report (.md written by LiveBench) becomes one row in bench_runs per model it holds, and one row per task run in bench_results.
Uploading the same report again replaces its rows, so a report that is still being written can be uploaded as often as wanted.
Labels follow tools/bench/trust.py: done, unchecked, not done, or error (a provider error; counted as void, not scored).
It talks to Neon's HTTPS SQL endpoint, so it needs no Postgres driver and works wherever HTTPS does.
"""
import glob
import json
import os
import re
import sys
import urllib.parse
import urllib.request
from datetime import datetime

SUITES = ("standard", "hard", "heldout", "realproject", "trust", "chat", "asks", "cause", "harbour", "sealed", "premise", "variety")
ROW = re.compile(r"^\| (?P<model>[^|]+?) \| (?P<task>[a-z0-9-]+) \| (?P<pass>yes|\*\*no\*\*|no) \| (?P<requests>\d+) \| (?P<calls>\d+) \|"
                 r" (?P<failed>\d+) \| [^|]* \| [^|]* \| (?P<seconds>\d+) \| (?P<ending>[^|]*) \| [^|]* \| ~?(?P<tin>\d+) \| ~?(?P<tcached>\d+) \| ~?(?P<tout>\d+) \|")
CHECK = re.compile(r"^- \*{0,2}(?P<model>\S+?)\*{0,2} / (?P<task>[a-z0-9-]+): .*?Check said: (?P<said>.*)$")


def kind(ending):
    e = ending.strip().replace("2nd try, ", "").replace("escalated, ", "")
    if e.startswith("error:"):
        return "error"
    if e.startswith("finished (not checked)"):
        return "unchecked"
    if e.startswith("finished"):
        return "done"
    return "not done"


def parse(path):
    """{model: [row dict]} and the settings line of one report."""
    settings, rows, said = "", {}, {}
    for line in open(path, encoding="utf-8", errors="replace"):
        line = line.rstrip("\n")
        if line.startswith("Settings: "):
            settings = line[len("Settings: "):]
        m = ROW.match(line)
        if m:
            model = m.group("model").strip()
            rs = rows.setdefault(model, [])
            attempt = 1 + sum(1 for r in rs if r["task"] == m.group("task"))
            label = kind(m.group("ending"))
            passed = m.group("pass") == "yes"
            rs.append(dict(task=m.group("task"), attempt=attempt, passed=passed, requests=int(m.group("requests")),
                           tool_calls=int(m.group("calls")), failed_edits=int(m.group("failed")), seconds=int(m.group("seconds")),
                           ending=m.group("ending").strip(), label=label,
                           overclaim=label == "done" and not passed,
                           underclaim=label == "not done" and passed and not m.group("task").startswith("trust-missing-"),
                           tokens_in=int(m.group("tin")), tokens_cached=int(m.group("tcached")), tokens_out=int(m.group("tout")),
                           check_said=None))
            continue
        c = CHECK.match(line)
        if c:
            said.setdefault((c.group("model").strip(), c.group("task")), []).append(c.group("said").strip()[:2000])
    for model, rs in rows.items():   # "Check said" lines come in run order for the failed runs of each task
        for r in rs:
            if not r["passed"]:
                q = said.get((model, r["task"]))
                if q:
                    r["check_said"] = q.pop(0)
    return settings, rows


def started(path):
    m = re.search(r"(\d{4}-\d{2}-\d{2}-\d{4})\.md$", path)
    return datetime.strptime(m.group(1), "%Y-%m-%d-%H%M").astimezone().isoformat() if m else None


def suite_of(path, given):
    if given:
        return given
    for part in reversed(path.split(os.sep)):
        if part in SUITES:
            return part
        m = re.match(r"bench-\d+-([a-z0-9-]+?)-", part)   # an Actions artifact folder
        if m:
            return m.group(1)
    return "unknown"


class Neon:
    def __init__(self, url):
        self.url = url
        self.endpoint = "https://" + urllib.parse.urlparse(url).hostname + "/sql"

    def tx(self, queries):
        body = json.dumps({"queries": [{"query": q, "params": p} for q, p in queries]}).encode()
        req = urllib.request.Request(self.endpoint, data=body, headers={
            "Content-Type": "application/json", "Neon-Connection-String": self.url, "Neon-Array-Mode": "false"})
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            raise SystemExit(f"Neon refused the upload ({e.code}): {e.read()[:300].decode(errors='replace')}")


def upload(db, path, source, suite, commit, notes):
    settings, rows = parse(path)
    version = (re.search(r"version=(\S+)", settings) or [None, None])[1]
    n = 0
    for model, rs in rows.items():
        provider, _, name = model.partition(":")
        # the folder too: reports written one task at a time share a minute, so the file name alone is not unique
        key = f"{source}:{suite}:{model}:{os.path.basename(os.path.dirname(path))}/{os.path.basename(path)}"
        res = db.tx([("INSERT INTO bench_runs (report_key, started_at, source, app_version, git_commit, provider, model, suite, settings, notes) "
                      "VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10) ON CONFLICT (report_key) DO UPDATE SET settings = EXCLUDED.settings, "
                      "notes = COALESCE(EXCLUDED.notes, bench_runs.notes), git_commit = COALESCE(EXCLUDED.git_commit, bench_runs.git_commit), "
                      "uploaded_at = now() RETURNING id",
                      [key, started(path), source, version, commit, provider, name, suite, settings, notes])])
        run_id = res["results"][0]["rows"][0]["id"]
        qs = [("DELETE FROM bench_results WHERE run_id = $1", [run_id])]
        for r in rs:
            qs.append(("INSERT INTO bench_results (run_id, task, attempt, passed, requests, tool_calls, failed_edits, seconds, ending, label, "
                       "overclaim, underclaim, tokens_in, tokens_cached, tokens_out, check_said) "
                       "VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16)",
                       [run_id, r["task"], r["attempt"], r["passed"], r["requests"], r["tool_calls"], r["failed_edits"], r["seconds"],
                        r["ending"], r["label"], r["overclaim"], r["underclaim"], r["tokens_in"], r["tokens_cached"], r["tokens_out"],
                        r["check_said"]]))
        db.tx(qs)
        n += len(rs)
        print(f"{path}: {model} {suite}: {len(rs)} task runs, {sum(r['passed'] for r in rs)} passed")
    return n


def main(argv):
    args, opts = [], {"--source": "workspace", "--suite": "", "--commit": None, "--notes": None}
    it = iter(argv)
    for a in it:
        if a in opts:
            opts[a] = next(it)
        else:
            args.append(a)
    url = os.environ.get("NEON_DATABASE_URL", "").strip()
    if not url or not args:
        raise SystemExit(__doc__)
    db = Neon(url)
    paths = []
    for a in args:
        paths += sorted(glob.glob(os.path.join(a, "**", "20*.md"), recursive=True)) if os.path.isdir(a) else [a]
    total = sum(upload(db, p, opts["--source"], suite_of(p, opts["--suite"]), opts["--commit"], opts["--notes"]) for p in paths)
    print(f"uploaded {total} task runs from {len(paths)} reports")


if __name__ == "__main__":
    main(sys.argv[1:])
