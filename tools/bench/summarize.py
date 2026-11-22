#!/usr/bin/env python3
"""Combine the reports of one benchmark workflow run into a single comparison (1.5.102).

Each job of .github/workflows/benchmark.yml uploads an artifact named bench-<job>-<suite>-<ref>, holding bench-out/<date>.md
(the LiveBench report: one table row per trial). This reads every report under a folder and prints Markdown: per model and ref,
each task's passes, median requests, median seconds and median read_file calls, then the totals, so a candidate branch can be
compared with main in one table. Usage: summarize.py <folder with the downloaded artifacts>
"""
import glob
import os
import re
import statistics
import sys
from collections import defaultdict

ROW = re.compile(r"^\| (?P<model>[^|]+?) \| (?P<task>[a-z0-9-]+) \| (?P<pass>yes|\*\*no\*\*|no) \| (?P<requests>\d+) \|"
                 r"(?P<rest>.*)$")


def ref_of(path):
    """The ref from the artifact folder name bench-<job>-<suite>-<ref>; '' for older artifacts without one."""
    for part in path.split(os.sep):
        m = re.match(r"bench-\d+-[a-z]+-(.+)$", part)
        if m:
            return m.group(1)
    return ""


def suite_of(path):
    """The suite from the artifact folder name bench-<job>-<suite>-<ref>; '' when there is none (1.5.138)."""
    for part in path.split(os.sep):
        m = re.match(r"bench-\d+-([a-z0-9]+)-", part)
        if m:
            return m.group(1)
    return ""


def tokens(cell):
    """A token cell ("1234", "~1234" when estimated, or missing in reports before 1.5.138) as a number, or None."""
    if cell is None:
        return None
    try:
        return float(cell.lstrip("~"))
    except ValueError:
        return None


SETTINGS = re.compile(r"^Settings: (.+)$")


def settings_of(path):
    """The report's 'Settings: version=... ask=0 ...' line (1.5.161), as written; '' for older reports."""
    for line in open(path, encoding="utf-8", errors="replace"):
        m = SETTINGS.match(line.strip())
        if m:
            return m.group(1).strip()
    return ""


def provider_error(row):
    """A run that ended in an error before any tool ran (HTTP 4xx/5xx from the model call, a bad key): not a score (1.5.161)."""
    ending = row.get("Ending", "").replace("2nd try, ", "")
    return ending.startswith("error:") and row.get("Tool calls", "") == "0"


def parse(path):
    """Trials in one report: (model, task, passed, requests, seconds, reads, tokens in, cached, out, helper, provider error)."""
    out = []
    head = None
    for line in open(path, encoding="utf-8", errors="replace"):
        line = line.rstrip("\n")
        if line.startswith("| Model |"):
            head = [c.strip() for c in line.strip("|").split("|")]
            continue
        m = ROW.match(line)
        if not m or not head:
            continue
        cells = [c.strip() for c in line.strip("|").split("|")]
        if len(cells) != len(head):
            continue
        row = dict(zip(head, cells))

        def num(name):
            try:
                return float(row.get(name, ""))
            except ValueError:
                return None
        out.append((row["Model"], row["Task"], row["Pass"] == "yes", num("Requests"), num("Seconds"), num("Read file calls"),
                    tokens(row.get("In")), tokens(row.get("Cached")), tokens(row.get("Out")), tokens(row.get("Helper")), provider_error(row),
                    tokens(row.get("Bad calls")) or 0))
    return out


def med(xs):
    xs = [x for x in xs if x is not None]
    return statistics.median(xs) if xs else None


def fmt(x):
    return "–" if x is None else (str(int(x)) if float(x).is_integer() else f"{x:.1f}")


def summarize(folder):
    trials = defaultdict(list)  # (model, ref, suite, settings, task) -> [trial]
    errors = defaultdict(int)   # (model, ref, suite, settings, task) -> provider errors, left out of the pass rates (1.5.161)
    for path in glob.glob(os.path.join(folder, "**", "bench-out", "*.md"), recursive=True):
        rel = os.path.relpath(path, folder)
        ref, suite, sets = ref_of(rel), suite_of(rel), settings_of(path)
        for model, task, ok, req, sec, reads, tin, cached, tout, helper, err, bad in parse(path):
            key = (model, ref, suite, sets, task)
            if err:
                errors[key] += 1
                trials.setdefault(key, [])
            else:
                trials[key].append((ok, req, sec, reads, tin, cached, tout, helper, bad))
    if not trials:
        return "No benchmark reports were found.\n"
    refs = sorted({k[1] for k in trials})
    lines = ["# Benchmark summary", ""]
    lines.append("| Model | Ref | Suite | Task | Passed | Median requests | Median seconds | Median reads | Median tokens in | Median out | Median helper | Bad calls | Provider errors | Settings |")
    lines.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    by_suite = defaultdict(lambda: [0, 0, 0.0, [], 0.0, 0.0, 0.0, 0.0, 0, 0])
    by_run = defaultdict(lambda: [0, 0, 0.0, [], 0.0, 0.0, 0.0, 0.0, 0, 0])
    for key in sorted(trials, key=lambda k: (k[0], k[2], k[4], k[3], k[1])):
        model, ref, suite, sets, task = key
        t = trials[key]
        e = errors.get(key, 0)
        p = sum(1 for x in t if x[0])
        lines.append(f"| {model} | {ref or 'this run'} | {suite or '–'} | {task} | {p}/{len(t)} | {fmt(med([x[1] for x in t]))} | "
                     f"{fmt(med([x[2] for x in t]))} | {fmt(med([x[3] for x in t]))} | {fmt(med([x[4] for x in t]))} | "
                     f"{fmt(med([x[6] for x in t]))} | {fmt(med([x[7] for x in t]))} | {int(sum(x[8] for x in t))} | {e} | {sets or '–'} |")
        for tot in (by_suite[(model, ref, suite, sets)], by_run[(model, ref, sets)]):
            tot[0] += p; tot[1] += len(t); tot[2] += sum(x[2] or 0 for x in t); tot[3] += [x[1] for x in t]
            tot[4] += sum(x[4] or 0 for x in t); tot[5] += sum(x[5] or 0 for x in t); tot[6] += sum(x[6] or 0 for x in t); tot[7] += sum(x[7] or 0 for x in t)
            tot[8] += e; tot[9] += sum(x[8] for x in t)
    head = "| Model | Ref | Suite | Passed | Median requests | Seconds in all | Tokens in | Cached | Out | Helper | Bad calls | Provider errors | Settings |"
    lines += ["", "Totals per suite and for the whole run (1.5.138):", "", head, "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for (model, ref, suite, sets), (p, n, sec, req, tin, cached, tout, helper, e, bad) in sorted(by_suite.items()):
        lines.append(f"| {model} | {ref or 'this run'} | {suite or '–'} | {p}/{n} | {fmt(med(req))} | {int(sec)} | {int(tin)} | {int(cached)} | {int(tout)} | {int(helper)} | {int(bad)} | {e} | {sets or '–'} |")
    for (model, ref, sets), (p, n, sec, req, tin, cached, tout, helper, e, bad) in sorted(by_run.items()):
        lines.append(f"| **{model}** | {ref or 'this run'} | **all** | **{p}/{n}** | {fmt(med(req))} | {int(sec)} | {int(tin)} | {int(cached)} | {int(tout)} | {int(helper)} | {int(bad)} | {e} | {sets or '–'} |")
    lines += ["", "Tokens are 0 for reports written before 1.5.138, which did not record them. Provider errors (the model call failed "
              "before any tool ran) are left out of Passed (1.5.161). Bad calls: tool calls that could not run as sent (unknown names, calls written "
              "as text, calls cut off), the tool-call health of each model and provider (1.5.164). Settings are as each job's report gives them; '–' for reports "
              "written before 1.5.161."]
    if len(refs) > 1:
        lines += ["", "Refs compared: " + ", ".join(r or "this run" for r in refs) + ". Differences of a trial or two per task "
                  "are within run-to-run noise at 3 runs per task."]
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    sys.stdout.write(summarize(sys.argv[1] if len(sys.argv) > 1 else "."))
