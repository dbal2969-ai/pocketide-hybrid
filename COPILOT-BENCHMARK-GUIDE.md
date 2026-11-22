# Benchmark guide for Copilot: monitor runs and interpret results

Roles (set by the owner, 2026-11-09):

- **Claude** writes and changes the benchmark tasks and the app, starts runs, and releases (only with `release.sh`).
- **Copilot** watches the runs, reads the results, and writes what they mean in `BENCHMARK-NOTES.md` on the branch
  `bench-notes` (never on `main`, so releases are not disturbed). Claude reads that file before deciding what to change.

Copilot does not change app code, task files, workflows or secrets, and does not start paid runs (see Budget).

## Where the runs are

Repository `dbal2969-ai/Test-Coder2.3`, workflow **Benchmark** (`.github/workflows/benchmark.yml`).

```sh
gh run list -R dbal2969-ai/Test-Coder2.3 -w benchmark.yml -L 10
gh run view <id> -R dbal2969-ai/Test-Coder2.3                      # jobs: one per model (and per ref and suite)
gh run download <id> -R dbal2969-ai/Test-Coder2.3 -n summary       # summary.md: the score table
gh run download <id> -R dbal2969-ai/Test-Coder2.3 -p 'bench-*'     # per job: report + one transcript per task run
```

A green run means the benchmark ran, not that tasks passed: the score is in `summary.md`. A red job is usually the runner
(Android SDK download, "ZipFile unknown archive") or a missing secret; say which, it is not a score.

Inputs worth knowing: `models` (`provider:model;…`), `suite` (standard, hard, heldout, realproject, polyglot, polyglot-js, polyglot-all, all), `repeat`,
`tasks` (names, comma-separated), `refs` (two branches or commits to compare in one run), `second_try`, `advisor`, `app_tools`.
Runs on one branch wait for each other; runs on different branches go in parallel.

## Reading a result

`summary.md` has one row per model, task and ref: passed/total, median requests, seconds, file reads; then totals.
Each transcript (`bench-*/…/<model>-<task>-<n>.md`) has the request, every tool call with its result, what the app told the
model ("App to model"), the model's thinking when the provider sends it, the final answer with its **ending**, and the check's
output (`## Check`). The first `FAIL:` line under `## Check` says what the hidden check found.

Endings: `finished`; `error: …` (a provider error the app could not retry: count it separately, it is not the model's score);
`stopped` / `not finished` (limit reached: look for "You have reached a limit" and "going in circles"); `2nd try, …` (second try on).

## Telling a real change from noise

- One or two trials either way on a task, or two or three on a 32-trial total, is run-to-run noise. Say so.
- Trust a difference only when it holds on 2+ repeats, and preferably when both versions ran in the **same** run (`refs` input),
  because providers vary by hour.
- Count provider errors (`error: …` endings) apart from failures; a model with half its runs ending on HTTP 500 has no score.
- A task every strong model fails is suspect: read two transcripts before blaming the models (see the examples below).

## Classify every failure

Put each failed run in one class, with the transcript line that shows it:

1. **Model limit**: the model misread the task or wrote wrong code, and the app gave it what it needed.
2. **App problem**: the app refused, misled, cut off or lost something (a guard that fires wrongly, a retry missing, a tool
   result that is wrong or confusing). These are what Claude fixes first.
3. **Task flaw**: the task or its check is unfair or unrealistic (ambiguous wording, a check that rejects a correct answer, a
   test project that does not behave like a real one).
4. **Provider / runner**: quota, 5xx, timeouts, runner setup.

Real examples from this project:

- Search refused after a model read a file as told (app problem, fixed in 1.5.110: Kimi 29 → 31 of 32).
- Cloudflare's "Internal server error" ended half of GLM 4.7 Flash's runs (provider, but the app lacked a retry: fixed 1.5.109).
- Haiku undid a correct fix because the test page never loaded the file it changed (task flaw, fixed in the fixture).
- "Maple trees can't be chopped" read as a rule to add, not a bug (task flaw: 0 of 10 for every model until reworded).
- gpt-oss reading one file per reply and running out of rounds (model limit; a nudge for it made scores worse and was dropped).

## Current baselines (hard suite unless noted)

| Model (provider) | Score | Notes |
|---|---|---|
| Kimi K2.6 (Cloudflare, free tier) | 31/32 | the reference model |
| Claude Haiku 5.5 (Haimaker) | 31/32 | held-out 12/12, real-project 6/6 |
| GLM 5.3 Flash (Haimaker) | 32/32 | held-out 12/12, real-project 6/6 |
| DeepSeek V4.1 Flash (Haimaker) | 15/16 | |
| GPT-5 mini (Haimaker) | 14/16 | |
| gpt-oss-120b (Cloudflare, free) | 19–24/32 | varies by day |
| GLM 4.7 Flash (Cloudflare, free) | 13–18/32 | |

**Polyglot** (Aider's benchmark, 34 Python exercises, added 1.5.113): the model sees the instructions and the stub, not the
tests, and gets no second try with test output. Compare with Aider's *first-try* rate (`pass_rate_1`), not the two-try rate
on its leaderboard, and say that in any comparison.

## Budget

- Cloudflare (Kimi, gpt-oss, GLM 4.7 Flash): free daily allowance; long runs can hit "Capacity temporarily exceeded".
- Haimaker: about $1 left (2026-11-09). Do not start Haimaker runs; Claude spends it.
- Kilo (no key): capped after a few tasks; fine for 3–6 tasks only. Groq free tier is too small to use.

## Rules

- Never write keys, tokens or account ids into any file or comment. Logs mask them; keep it that way.
- Do not edit the benchmark tasks or the app. Propose changes in `BENCHMARK-NOTES.md`.
- Name the run id and the transcript file for every claim.

## What to write in BENCHMARK-NOTES.md

Newest first, one section per run:

```md
## Run <id> — <date> — <what was compared>
Scores: <model: passed/total per ref> (provider errors: n)
Verdict: <real change / noise / cannot tell>, because …
Failures by class: app problem n, model limit n, task flaw n, provider n
- <task> (<model>, run n): <class> — <one line, with the transcript line that shows it>
Suggested next step for Claude: <one concrete change, or "none">
```
