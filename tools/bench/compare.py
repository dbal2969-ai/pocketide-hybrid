#!/usr/bin/env python3
"""Paired, task-clustered comparison of two benchmark runs (1.5.164; the research handoff's §7).

Runs of the same task are not independent: a 12-task suite run 5 times is closer to 12 observations than to 60 (Miller, "Adding Error
Bars to Evals", Anthropic 2024). So arms are compared task by task: each task's pass rate in B minus its pass rate in A, then the mean
of those differences with a task-clustered 95% interval (t on k-1 degrees of freedom) and an exact sign-flip test over tasks (all 2^k
flips up to k = 16, else 20,000 random ones). A naive pooled Fisher p is printed beside it for comparison with older entries.

Usage: compare.py <folder or summary.md of arm A> <folder or summary.md of arm B> [--min-detectable]
Each argument is a downloaded run (gh run download) or its summary/summary.md. Tasks in only one arm are listed and left out.
"""
import glob
import itertools
import math
import os
import random
import re
import statistics
import sys

ROW = re.compile(r"^\|\s*(?P<model>[^|]+?)\s*\|\s*(?P<ref>[^|]+?)\s*\|\s*(?P<suite>[^|]+?)\s*\|\s*(?P<task>[a-z0-9-]+)\s*\|\s*(?P<p>\d+)/(?P<n>\d+)\s*\|")

# two-sided 97.5% t quantiles for 1..30 degrees of freedom; 1.96 beyond
T975 = [12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228, 2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110,
        2.101, 2.093, 2.086, 2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042]


def t975(df):
    return T975[df - 1] if 1 <= df <= len(T975) else 1.96


def per_task(path):
    """{task: (passed, runs)} from a summary.md, or from every bench-out report under a folder (via summarize.py)."""
    if os.path.isdir(path):
        found = glob.glob(os.path.join(path, "**", "summary.md"), recursive=True)
        if found:
            path = found[0]
        else:
            sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
            import summarize
            text = summarize.summarize(path)
            return parse_summary(text.splitlines())
    with open(path, encoding="utf-8") as f:
        return parse_summary(f.read().splitlines())


def parse_summary(lines):
    out = {}
    for line in lines:
        m = ROW.match(line)
        if not m or m.group("task") in ("all",):
            continue
        p, n = int(m.group("p")), int(m.group("n"))
        if n == 0:
            continue
        a, b = out.get(m.group("task"), (0, 0))
        out[m.group("task")] = (a + p, b + n)
    return out


def sign_flip_p(diffs):
    """Two-sided p of the mean difference under random signs (exact up to 16 tasks)."""
    k = len(diffs)
    obs = abs(sum(diffs))
    nz = [d for d in diffs if d != 0]
    if not nz:
        return 1.0
    if len(nz) <= 16:
        hits = total = 0
        for signs in itertools.product((1, -1), repeat=len(nz)):
            total += 1
            if abs(sum(s * d for s, d in zip(signs, nz))) >= obs - 1e-12:
                hits += 1
        return hits / total
    rng = random.Random(7)
    hits = sum(1 for _ in range(20000) if abs(sum(d if rng.random() < 0.5 else -d for d in nz)) >= obs - 1e-12)
    return (hits + 1) / 20001


def fisher_two_sided(a, n1, b, n2):
    """Pooled Fisher exact p (for comparison with older BENCHMARKS entries; it ignores the clustering)."""
    total = a + b
    def pmf(x):
        return math.comb(n1, x) * math.comb(n2, total - x) / math.comb(n1 + n2, total)
    lo, hi = max(0, total - n2), min(total, n1)
    p0 = pmf(a)
    return min(1.0, sum(pmf(x) for x in range(lo, hi + 1) if pmf(x) <= p0 + 1e-12))


def compare(a, b):
    common = sorted(set(a) & set(b))
    lines = ["| Task | A | B | B - A |", "|---|---|---|---|"]
    diffs = []
    for t in common:
        ra, rb = a[t][0] / a[t][1], b[t][0] / b[t][1]
        diffs.append(rb - ra)
        lines.append(f"| {t} | {a[t][0]}/{a[t][1]} | {b[t][0]}/{b[t][1]} | {rb - ra:+.2f} |")
    k = len(diffs)
    if k == 0:
        return "No task is in both runs.\n", None
    mean = statistics.mean(diffs)
    se = statistics.stdev(diffs) / math.sqrt(k) if k > 1 else float("nan")
    half = t975(k - 1) * se if k > 1 else float("nan")
    pa, na = sum(a[t][0] for t in common), sum(a[t][1] for t in common)
    pb, nb = sum(b[t][0] for t in common), sum(b[t][1] for t in common)
    res = {"k": k, "mean": mean, "se": se, "lo": mean - half, "hi": mean + half, "p": sign_flip_p(diffs),
           "pooled": (pa, na, pb, nb), "fisher": fisher_two_sided(pa, na, pb, nb)}
    lines += ["", f"Pooled: A {pa}/{na}, B {pb}/{nb} (naive Fisher p {res['fisher']:.2f}, which ignores that runs of a task are alike).",
              f"Paired over {k} tasks: mean difference {mean * 100:+.1f} points, task-clustered SE {se * 100:.1f}, "
              f"95% CI {res['lo'] * 100:+.1f} to {res['hi'] * 100:+.1f}; sign-flip p {res['p']:.3f}."]
    if res["lo"] > 0:
        lines.append("The interval excludes 0: B is better on these tasks.")
    elif res["hi"] < 0:
        lines.append("The interval excludes 0: B is worse on these tasks.")
    else:
        lines.append("The interval includes 0: no difference shown (not the same as no difference).")
    only = sorted(set(a) ^ set(b))
    if only:
        lines.append("Left out (in one arm only): " + ", ".join(only) + ".")
    return "\n".join(lines) + "\n", res


def min_detectable(res):
    """The smallest mean difference this design would usually detect (80% power, two-sided 5%), from its own task-level spread."""
    if not res or not res["se"] == res["se"]:
        return ""
    return f"With this spread, a difference smaller than about {2.8 * res['se'] * 100:.0f} points would usually go undetected.\n"


if __name__ == "__main__":
    args = [x for x in sys.argv[1:] if not x.startswith("--")]
    if len(args) != 2:
        sys.exit(__doc__)
    text, res = compare(per_task(args[0]), per_task(args[1]))
    sys.stdout.write(text)
    if "--min-detectable" in sys.argv:
        sys.stdout.write(min_detectable(res))
