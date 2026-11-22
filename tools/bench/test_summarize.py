#!/usr/bin/env python3
"""Tests of summarize.py (1.5.161): settings on every row, provider errors out of the pass rate. Run: python3 tools/bench/test_summarize.py"""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import summarize  # noqa: E402
import compare  # noqa: E402

HEAD = ("| Model | Task | Pass | Requests | Tool calls | Failed edits | Empty searches | Refused | Seconds | Ending | Read file calls | In | Cached | Out | Helper |\n"
        "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n")


def report(folder, job, settings, rows):
    d = os.path.join(folder, job, "_temp", "bench-out")
    os.makedirs(d)
    with open(os.path.join(d, "2026-11-14-1200.md"), "w") as f:
        f.write("# PocketIDE benchmark\n\n" + (settings + "\n\n" if settings else "") + HEAD + "".join(rows))


def row(task, ok, calls=10, ending="finished"):
    return f"| m:x | {task} | {'yes' if ok else '**no**'} | 12 | {calls} | 0 | 0 | 0 | 100 | {ending} | 3 | 10 | 20 | 30 | 0 |\n"


class SummarizeTest(unittest.TestCase):
    def test_settings_on_every_row_and_arms_kept_apart(self):
        with tempfile.TemporaryDirectory() as t:
            report(t, "bench-0-harbour-off", "Settings: version=1.5.161 scope=0", [row("a", True), row("a", False)])
            report(t, "bench-0-harbour-on", "Settings: version=1.5.161 scope=1", [row("a", True), row("a", True)])
            out = summarize.summarize(t)
            self.assertIn("| a | 1/2 |", out)
            self.assertIn("| a | 2/2 |", out)
            for line in out.splitlines():
                if line.startswith("| m:x") or line.startswith("| **m:x"):
                    self.assertIn("version=1.5.161", line)

    def test_provider_errors_are_counted_apart(self):
        with tempfile.TemporaryDirectory() as t:
            report(t, "bench-0-standard-main", "Settings: version=1.5.161", [
                row("a", True), row("a", False, calls=0, ending="error: HTTP 402 over budget"), row("a", False, calls=3, ending="error: HTTP 500")])
            out = summarize.summarize(t)
            # the 402 before any tool is left out; the 500 after three tool calls is a real failure
            self.assertIn("| a | 1/2 |", out)
            self.assertIn("| **1/2** |", out)
            self.assertRegex(out, r"\| a \| 1/2 \|.*\| 1 \| version=1\.5\.161 \|")

    def test_bad_calls_column(self):
        with tempfile.TemporaryDirectory() as t:
            report(t, "bench-0-standard-main", "Settings: version=1.5.164", [row("a", True)])
            out = summarize.summarize(t)
            self.assertIn("| Bad calls |", out)
            self.assertRegex(out, r"\| a \| 1/1 \|.*\| 0 \| 0 \| version=1\.5\.164 \|")

    def test_old_reports_still_read(self):
        with tempfile.TemporaryDirectory() as t:
            report(t, "bench-0-standard-main", "", [row("a", True)])
            self.assertIn("| a | 1/1 |", summarize.summarize(t))


class CompareTest(unittest.TestCase):
    # Copilot's PR #27, T1.6
    def test_zero_variance_and_unequal_repeats(self):
        _, res = compare.compare({"t1": (5, 5), "t2": (3, 3)}, {"t1": (5, 5), "t2": (1, 1)})
        self.assertEqual(res["mean"], 0); self.assertEqual(res["p"], 1.0)
        _, res = compare.compare({"t1": (0, 3), "t2": (1, 2)}, {"t1": (1, 1), "t2": (2, 2)})
        self.assertAlmostEqual(res["mean"], (1.0 + 0.5) / 2)

    def test_sign_flip_matches_a_hand_count(self):
        # five tasks, each +1: of the 32 sign patterns only all-plus and all-minus reach |5|, so p = 2/32
        a = {f"t{i}": (0, 1) for i in range(5)}; b = {f"t{i}": (1, 1) for i in range(5)}
        _, res = compare.compare(a, b)
        self.assertAlmostEqual(res["p"], 2 / 32)
        self.assertTrue(res["lo"] != res["lo"] or res["lo"] <= 1)  # zero spread: the interval is a point (or undefined)


    def test_paired_difference_and_interval(self):
        a = {"t1": (0, 5), "t2": (5, 5), "t3": (2, 5)}
        b = {"t1": (5, 5), "t2": (5, 5), "t3": (4, 5), "t4": (1, 1)}
        text, res = compare.compare(a, b)
        self.assertEqual(res["k"], 3)
        self.assertAlmostEqual(res["mean"], (1.0 + 0.0 + 0.4) / 3)
        self.assertIn("Left out (in one arm only): t4.", text)
        self.assertEqual(res["pooled"], (7, 15, 14, 15))
        # signs over the two non-zero tasks: 4 flips, only the observed pattern and its mirror reach |1.4|
        self.assertAlmostEqual(res["p"], 0.5)

    def test_no_difference(self):
        _, res = compare.compare({"t1": (3, 5), "t2": (1, 5)}, {"t1": (3, 5), "t2": (1, 5)})
        self.assertEqual(res["p"], 1.0)
        self.assertEqual(res["mean"], 0)


if __name__ == "__main__":
    unittest.main()
