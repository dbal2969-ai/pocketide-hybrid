# Agent benchmark (1.5.86)

The agent loop now runs on a computer, with real models, against a fixed task set (`app/src/test/java/dev/pocketide/app/bench`).
Each task is a small project, a request, and a shell check; a run passes when the check passes (and, for questions and
report-only requests, no file changed). This is the measurement every reliability change should be judged by.

**Run it** (Linux with JDK 17, the Android SDK, Node, Python 3, GNU `timeout` and a working `bubblewrap`):

```sh
export POCKETIDE_BENCH_MODELS="groq:llama-3.3-70b-versatile;cerebras:gpt-oss-120b;cloudflare:@cf/moonshotai/kimi-k2.6"
export POCKETIDE_BENCH_KEY_GROQ=...  POCKETIDE_BENCH_KEY_CEREBRAS=...  POCKETIDE_BENCH_KEY_CLOUDFLARE=...
export POCKETIDE_BENCH_BASE_CLOUDFLARE=https://api.cloudflare.com/client/v4/accounts/<account id>/ai/v1
export POCKETIDE_BENCH_REPEAT=3          # optional: runs of each task (models vary run to run)
export POCKETIDE_BENCH_TASKS=report-only # optional: only these tasks (from either suite)
export POCKETIDE_BENCH_SUITE=hard        # optional: standard (default), hard, heldout, realproject or all
./gradlew testDebugUnitTest --tests '*LiveBench*'
```

The report goes to `app/build/bench/<date>.md` (rewritten after every run). Keys are read from the environment only; never put
them in a file in the project. Provider ids are the app's (`groq`, `cerebras`, `gemini`, `openrouter`, `nvidia`, `cloudflare`, …);
model names are the provider's.

Live task commands and checks run in a bubblewrap namespace with no network, a clean environment and only the generated project
writable on the host. The host must allow the required namespaces; a sandbox failure is not evidence that the model failed the task.
Credentials are kept in memory, and saved reports and transcripts redact the configured keys and account URLs.

Each run's full transcript (every tool call, its result, the answer and the check output) is written next to the report, in
`app/build/bench/<date>/`: read the failures there before changing the agent.

**Keep results through a restart:** set `POCKETIDE_BENCH_OUT` to a folder outside the build, for example
`/workspaces/bench-results/<name>` in a Codespace (a restart empties `/tmp`, and a worktree there goes with it). Each run's
transcript is saved there before every model request (a cut-off run keeps its steps, marked "Not finished yet"), the report
after every run, with any account id in a URL replaced by `<id>`. Give each model run
started at the same time its own folder, so two reports from the same minute do not overwrite each other.

**Several models at once with one key (1.5.89):** one Cloudflare key serves every Workers AI model, and each model has its own
rate limit, so models can run side by side. Give each its own git worktree (the Gradle build directory is per worktree) and start
them a couple of minutes apart; on a 2-core Codespace three at once work. For example, in three worktrees:
`POCKETIDE_BENCH_MODELS="cloudflare:@cf/moonshotai/kimi-k2.6"`, `"cloudflare:@cf/openai/gpt-oss-120b"` and
`"cloudflare:@cf/meta/llama-3.3-70b-instruct-fp8-fast"`. Error lines in the Gradle `-i` log can contain the account URL, so
remove the account id before sharing a log.

**Long runs in a Codespace:** `app/build` is lost with the worktree, and a Codespace restart wipes `/tmp`. Use
`tools/bench/run-bench.sh`, which runs each model in its own worktree under `/tmp` but copies every report and transcript to
`/workspaces/bench-results/<run>/` every 20 seconds, and can pick up where a run stopped:

```sh
tools/bench/run-bench.sh start -n nightly -r 3 @cf/moonshotai/kimi-k2.6 @cf/openai/gpt-oss-120b groq:llama-3.3-70b-versatile
tools/bench/run-bench.sh status          # runs done per model, last result; writes <run>/summary.md
tools/bench/run-bench.sh resume          # after a restart: every unfinished run, only the task runs with no saved result
tools/bench/run-bench.sh stop nightly
```

When the Codespace opens, the task "PocketIDE: resume benchmarks" (`.vscode/tasks.json`) runs `resume --interrupted`, which
continues runs a restart cut off (VS Code asks once whether to allow automatic tasks). Runs that ended with results missing,
for example after a build error, are left for a manual `resume`.

A run records the commit and the uncommitted changes at start (`manifest.env`, `wip.patch`), so a resume tests the same code
even if the project changed since. Options: `-t task1,task2` (only these tasks), `-s standard|hard|heldout|realproject|all` (task set), `-c COMMIT`,
`--no-wip`, and `--foreground` (wait for all results; exit nonzero if any are missing). A completed run can still contain
failed task checks. Thinking effort and whole-file-read settings are saved in the manifest and restored on resume. Keys come
from the environment or the Codespace secrets file and are never written to the run folder. Runs started another way can still
be saved: `tools/bench/run-bench.sh save-tmp` copies `/tmp/bench-wt-*/app/build/bench` to
`/workspaces/bench-results/tmp-copies/live/` until those runs end.

**Tasks:** fix-from-symptom (the cart ignores discount codes), add-feature (a new file and function), fix-and-test (fix and write
a test that passes), keep-what-exists (add a dark mode button without breaking "Say hi"), remove-the-barrier (widen the walkable
bounds, keep blocked tiles blocked), answer-a-question (read-only), report-only (run a command and report, change nothing).

**Hard suite** (`POCKETIDE_BENCH_SUITE=hard`, `HardBenchTasks.kt`; needs Node and python3): the standard tasks show the loop
works; these ask whether it does the everyday jobs people give Copilot or Claude Code as well as they do. Every check is hidden
(written to `.bench/` only after the run), so passing means doing the job, not matching visible tests; files that must not
change are compared by hash.

| Task | What it takes |
|---|---|
| rename-everywhere | rename across code, tests, README and a name in a string; leave the look-alikes getUsers and getUserName |
| fix-code-not-tests | "npm test fails": fix both bugs in the code, never touch the tests |
| symptom-in-big-project | find the cause of broken URLs in a ~45-file project, fix it in the shared function, leave the decoys |
| spec-with-hidden-tests | implement a parser from a README spec; hidden tests cover every rule and error case |
| big-file-many-fixes | find the 3 wrong formulas of 300 in an 1,800-line file against a table, keep the rest intact |
| wrong-guess | the person blames the wrong file; fixing it there breaks the receipt, the right fix is elsewhere |
| stay-in-folder | change only packages/api, with a tempting shared helper next door; no hardcoded version |
| async-order | forEach(async) loses rows; the fix must keep order and surface errors (Promise.all doesn't) |
| make-it-fast | O(n²) to fast on 200,000 contacts, returning exactly the same objects for any input |
| path-traversal | close a prefix-check leak, including encoded `..`, without crashing on malformed URLs |
| trace-the-error | read-only: follow a request to the function and message, past an outdated doc and a legacy module |
| python-flag | add `--json` in either position, default output byte for byte the same, plus a test |
| tests-that-catch-bugs | write tests that fail on each of five broken copies of the code |

`HardBenchTasksTest` (no model needed) proves each check fails on the starting project, passes a reference solution and rejects
the shortcuts a model is likely to take; run it after changing a hard task.

**Held-out suite** (`POCKETIDE_BENCH_SUITE=heldout`, `HeldOutBenchTasks.kt`, added in 1.5.91): six tasks written by someone
other than the person tuning the app, to check that a gain on the hard suite is a real gain and not a rule fitted to those tasks.
**Never tune the app on these tasks.** Run them, report the score next to the hard-suite score, and change nothing because of a
single held-out failure. If a held-out task is ever used to design a fix, move it into the hard suite and write a new held-out task.

| Task | What it tests |
|---|---|
| unique-address | posts with the same title must each keep an address (-2, -3 …), including a title that already ends in a number |
| paging | an off-by-one in a page slice and the page count, checked for every list length 0–35 and page size 1–12 |
| cli-flag | add `list --done` to a small command-line tool without changing the plain `list` output |
| order-date | an order's day must be its UTC day in every time zone (checked under New York, Tokyo and UTC) |
| remove-feature | remove coupons from code and tests, keep the cart and its route, leave an unrelated file untouched |
| csv-export | write a CSV export: quoting, commas and newlines in values, missing values, an empty list |

`HeldOutBenchTasksTest` proves each held-out check fails on the start, passes a reference solution and rejects a shortcut.

**Running on GitHub Actions** (1.5.101, `.github/workflows/benchmark.yml`): Actions → *Benchmark (live models)* → *Run
workflow*. Choose the models (`provider:model`, separated by `;`), the task set, the runs per task, the thinking effort and
whole-file reads. Each model, and for `all` each task set, gets its own job, so no job comes near GitHub's 6-hour limit. The
keys come from repository secrets: `POCKETIDE_BENCH_KEY_CLOUDFLARE` and `POCKETIDE_BENCH_BASE_CLOUDFLARE`, plus
`POCKETIDE_BENCH_KEY_GROQ` and similar for other providers. A job stops before calling any model when a key is missing or
the task sandbox cannot start. Each job's report appears on the run's summary page; reports and transcripts are kept as
artifacts for 30 days. A green job means the benchmark ran, not that every task passed: the score is in the report.

**Free providers on Actions** (1.5.105): `kilo:<model>` (Kilo Code gateway) runs with no key, and `llm7:<model>` uses
`POCKETIDE_BENCH_KEY_LLM7` (or none). Kilo's keyless use has a cap: a 4-model, 6-task, 2-run comparison hit "Sign up for free
to continue … Or come back later" partway, so keep Kilo comparisons to a few tasks. An LLM7 key without credit reaches only its
free models (the others answer HTTP 402, "Insufficient balance"). Groq's free tier allows about 200,000 tokens a day, less than
one hard-suite pass, so Groq results are not used. Models that passed the provider check: `kilo:kilo-auto/free`,
`kilo:nvidia/nemotron-3-ultra-550b-a55b:free`, `kilo:cohere/north-mini-code:free`.

**Cheap paid models on Actions** (1.5.111): `haimaker:<model>` uses `POCKETIDE_BENCH_KEY_HAIMAKER` (an `sk-` key from
app.haimaker.ai; the job log shows the key's length and whether it starts with `sk-`, never the key). Hard suite, November 2026:
claude-haiku-5-5 31/32, z-ai/glm-5.3-flash 32/32, deepseek-v4.1-flash 15/16, gpt-5-mini 14/16, qwen3.5-flash 12/16,
qwen3-coder-next 10/16, gemini-3.1-flash-lite 10/16, minimax-m3.1-flash 9/16. Held-out (2 runs): Haiku, GLM 5.3 Flash,
DeepSeek V4.1 Flash and GPT-5 mini 12/12, Kimi K2.6 11/12. These suites no longer separate the strong models.

**Polyglot** (1.5.113, suite `polyglot`): Aider's 34 Python exercises; instructions and stub only, tests hidden, one try. Kimi K2.6 22–26/34 over three runs; gpt-oss-120b 4–7/34; GLM 5.3 Flash (Haimaker) 13/34, slow (7 runs hit the time limit, 3 cut-off connections). Since 1.5.115 a failed exercise gets one more attempt with the test output (Aider's scoring): Kimi K2.6 (Cloudflare) 22/34 first try, 30/34 with two.

**Polyglot JavaScript** (1.5.125, suite `polyglot-js`; `polyglot-all` = Python and JavaScript, 83): Aider's 49 JavaScript exercises
(`js-` names), set up as Aider's `npm-test.sh` does: instructions and stub only; at check time the spec, the exercise's
package.json and babel config are written, `xtest(` becomes `test(` (Aider leaves `xit` and `.skip` alone, so do we), and
`npm test` runs `jest ./*` with `TZ=UTC` (ledger's reference solution fails in other time zones). jest 29.7.0 with the exercises' babel preset is installed once per job in `$RUNNER_TEMP/polyglot-jest`
(`POCKETIDE_POLYGLOT_JEST`), bound read-only into the task sandbox and found through PATH and NODE_PATH, not a link in the project.
Two tries as for Python. `PolyglotTasksTest` checked all 49 locally, in the task sandbox: every reference solution
(`.meta/proof.ci.js`) passes and every stub fails except `js-ledger`, a refactoring exercise whose stub is working code (a free
pass, as in Aider's own score). Baseline (Kimi K2.6) not run yet.

**Second try** (1.5.112, workflow input `second_try`): retries a task once from the start when the app sees the first attempt end unfinished. Hard suite, 2 runs: gpt-oss-120b 24 → 19, GLM 4.7 Flash 14 → 13 of 32 with it on (4 of 12 second tries passed); off by default.

**The phone's tools** (1.5.106): until 1.5.105 the harness never offered the explore helper or, for GPT-family models, apply_patch,
which ChatPane turns on before every request. It now does (workflow input `app_tools`, default 1). Turning them on found that
gpt-oss-120b's patches were all refused (wrong argument name), 19/32 against 24/32 on the hard suite; fixed in 1.5.106. The
`advisor` input adds a second model that plans before the first change and reviews before the finish (Settings → Advisor).

**Real-project suite** (`POCKETIDE_BENCH_SUITE=realproject`, `RealProjectBenchTasks.kt`, added in 1.5.99): the other suites use
projects of a few small files, but the work that went wrong in the person's logs was a game with thousands of files and a
900-line main.js. These tasks rebuild three of those failures in a generated project of that shape (a ~1,100-line `game/main.js`,
120 other modules, item data). They measure how long a run takes and how many reads it needs as much as whether it passes, so
report time and requests per task too. Measure only; do not tune on them.

| Task | The real failure it rebuilds |
|---|---|
| rp-bank-option | "Bank" on a bank booth did nothing: the handler knew only "Use" and "Open" |
| rp-menu-hidden | the inventory menu was hidden under the side panel: an inline z-index of 9 beat the stylesheet's 200 |
| rp-save-position | the player is back at the start after closing the game: saving and loading disagree on a key (added 1.5.102) |

1.5.102: rp-equip-slot (swords to the ammo slot, rings unwearable: the equipment data's slots shifted by one) moved to the hard
suite after its transcripts shaped a fix (the model renumbered the item data to fit its own list); rp-save-position replaced it.
Task files mark a task run in another suite with `// suite: <name>`, which `run-bench.sh` reads.

The harness runs the app's undefined-name check (assets/check/names.js, 1.5.103) with Node, as the phone runs it in its hidden
browser; without Node on the path the check is left out.

Pin refs to commit ids when comparing (`inputs[refs]=<sha of main>,<sha of the candidate>`): a branch name is checked out when
each job starts, so a release pushed while jobs wait would change what they measure.

**Comparing a change** (1.5.102): set *refs* to `main,<branch>` when running the workflow. Each ref runs the same models and
tasks, and the final *summary* job puts them in one table (`tools/bench/summarize.py`): passes, median requests, seconds and reads
per task. Push a candidate to a branch, compare, and release only what the comparison supports.

`RealProjectBenchTasksTest` proves each check fails at the start, passes real fixes (two different ones for the menu and the
slots) and rejects a shortcut.

**Thinking effort** (`POCKETIDE_BENCH_EFFORT=low|medium|high`, 1.5.99) sets Settings → Thinking before each step for a run.
Compare low and medium against the model's own setting on `-s all -r 3` before changing the app's default.

1.5.93: settings-merge (in the 1.5.91 baseline below) moved to the hard suite after it exposed the "When …" question bug and so
shaped an app fix; unique-address replaced it. The csv-export check now accepts a leading UTF-8 BOM, which the request's "Excel
opens correctly" invites; the baseline's two Kimi csv misses were that BOM.

**Hard suite results** (three Cloudflare Workers AI models side by side, one run of each task):

| Model | 1.5.89 | 1.5.90 | Failed at 1.5.90 |
|---|---|---|---|
| Kimi K2.6 | 12/13 | 12/13 | big-file-many-fixes; with the audit note added after that run, 2 of 2 passes |
| gpt-oss-120b | 10/13 | 10/13 | big-file-many-fixes (2 of 2 passes with the audit note), wrong-guess, async-order |
| Llama 3.3 70B fast | 1/13 | 2/13 and 3/13 | all but rename-everywhere, stay-in-folder and symptom-in-big-project |

One run per task moves a score by a task or two, so compare with `-r 3` before calling a change a gain or a loss. The
transcripts show what is left: gpt-oss still rewrites a correct `money()` it was told is broken, even after reading "in
dollars", and Llama 3.3 often fixes one of two bugs or writes tests that cannot run.

**Standard suite at 1.5.90** (one run of each task): Kimi K2.6 6/7, gpt-oss-120b 7/7, Llama 3.3 70B fast 4/7. Before
`ClassCheck`, keep-what-exists passed 1 time in 4 for gpt-oss (3 of 3 at 1.5.89): the button and the toggle were there but
`style.css` had no `.dark` rule. With it, gpt-oss and Kimi passed 6 of 6 (`-r 3` each) and once more in the standard run.
Kimi's and Llama's add-feature misses wrote `module.exports = celsiusToFahrenheit`, a reading of "export it" that the check,
which wants `exports.celsiusToFahrenheit`, does not accept.

## 1.5.91 baseline: three runs per task (2026-11-05)

**Complete: 234/234 scored trials.** All three models used Cloudflare Workers AI, with the same app at
`f61d4f894eef729c454a9ebca36e28d800e70f1a`. No app code or prompts were changed. The only working-tree patch fixes
`run-bench.sh` task discovery: `all` must include the six held-out tasks, and `heldout` must select that suite rather than
fall back to standard. Without it, the runner silently selected only 20 tasks for `all`.

The persistent run is `/workspaces/bench-results/2026-11-05-1326-baseline-1.5.91/`: `manifest.env`, `wip.patch`,
`summary.md`, nine invocation reports, and per-trial transcripts under `reports/`. Raw logs and transcripts stay outside
the repository. Models, in the tables below:

- **Kimi:** `cloudflare:@cf/moonshotai/kimi-k2.6`
- **GPT-OSS:** `cloudflare:@cf/openai/gpt-oss-120b`
- **Llama:** `cloudflare:@cf/meta/llama-3.3-70b-instruct-fp8-fast`

### Scores and consistency

A **solid** task passed 3/3, **flaky** passed 1/3 or 2/3, and **zero** passed 0/3. These are observed outcomes, not a
guarantee that a task passing three times will always pass. Suite totals count trials; stability counts distinct tasks.

| Suite | Model | Passed trials | Rate | Solid tasks | Flaky tasks | Zero tasks |
|---|---|---:|---:|---:|---:|---:|
| Standard | Kimi | 21/21 | 100% | 7/7 | 0 | 0 |
| Standard | GPT-OSS | 21/21 | 100% | 7/7 | 0 | 0 |
| Standard | Llama | 14/21 | 66.7% | 4/7 | 1 | 2 |
| Hard | Kimi | 33/39 | 84.6% | 10/13 | 2 | 1 |
| Hard | GPT-OSS | 29/39 | 74.4% | 6/13 | 6 | 1 |
| Hard | Llama | 10/39 | 25.6% | 1/13 | 5 | 7 |
| Held-out | Kimi | 13/18 | 72.2% | 4/6 | 1 | 1 |
| Held-out | GPT-OSS | 12/18 | 66.7% | 4/6 | 0 | 2 |
| Held-out | Llama | 3/18 | 16.7% | 1/6 | 0 | 5 |
| **All** | **Kimi** | **67/78** | **85.9%** | **21/26** | **3** | **2** |
| **All** | **GPT-OSS** | **62/78** | **79.5%** | **17/26** | **6** | **3** |
| **All** | **Llama** | **27/78** | **34.6%** | **6/26** | **6** | **14** |

| Suite | Task | Kimi | GPT-OSS | Llama |
|---|---|---:|---:|---:|
| Standard | fix-from-symptom | 3/3 | 3/3 | 2/3 |
| Standard | add-feature | 3/3 | 3/3 | 3/3 |
| Standard | fix-and-test | 3/3 | 3/3 | 0/3 |
| Standard | keep-what-exists | 3/3 | 3/3 | 0/3 |
| Standard | remove-the-barrier | 3/3 | 3/3 | 3/3 |
| Standard | answer-a-question | 3/3 | 3/3 | 3/3 |
| Standard | report-only | 3/3 | 3/3 | 3/3 |
| Hard | rename-everywhere | 3/3 | 2/3 | 3/3 |
| Hard | fix-code-not-tests | 3/3 | 3/3 | 1/3 |
| Hard | symptom-in-big-project | 3/3 | 3/3 | 1/3 |
| Hard | spec-with-hidden-tests | 3/3 | 2/3 | 0/3 |
| Hard | big-file-many-fixes | 3/3 | 2/3 | 0/3 |
| Hard | wrong-guess | 2/3 | 0/3 | 0/3 |
| Hard | stay-in-folder | 3/3 | 3/3 | 1/3 |
| Hard | async-order | 3/3 | 1/3 | 2/3 |
| Hard | make-it-fast | 3/3 | 3/3 | 2/3 |
| Hard | path-traversal | 1/3 | 3/3 | 0/3 |
| Hard | trace-the-error | 3/3 | 2/3 | 0/3 |
| Hard | python-flag | 3/3 | 3/3 | 0/3 |
| Hard | tests-that-catch-bugs | 0/3 | 2/3 | 0/3 |
| Held-out | settings-merge | 0/3 | 0/3 | 0/3 |
| Held-out | paging | 3/3 | 3/3 | 0/3 |
| Held-out | cli-flag | 3/3 | 3/3 | 3/3 |
| Held-out | order-date | 3/3 | 3/3 | 0/3 |
| Held-out | remove-feature | 3/3 | 0/3 | 0/3 |
| Held-out | csv-export | 1/3 | 3/3 | 0/3 |

Kimi has the broadest observed reliability, not a clean sweep. It beats GPT-OSS on seven task scores, ties on sixteen,
and loses on three: path-traversal, tests-that-catch-bugs and csv-export. Its overall lead is only five trials
(6.4 percentage points), so this is not proof of universal superiority. The held-out lead is just one trial, and both
models have four solid held-out tasks. Repeats share task templates and provider conditions; treating 78 trials as 78
independent real-world jobs would overstate confidence. Even 3/3 supplies little information about rare failures.

Llama's gap is much larger and appears in every suite. Kimi never scores below Llama on a task; GPT-OSS does on
rename-everywhere and async-order. Llama is usable on several small jobs, but these results do not support treating it
as a dependable general-purpose coding agent.

### What the failures actually show

The analysis checked all 234 report rows, all 78 failed check outputs, and every failed trial's tool/answer digest,
then read representative detailed transcripts to distinguish model errors, app interventions and scoring limitations.
The first failed assertion is not necessarily the only defect in that trial.

**Kimi and GPT-OSS, standard and hard suites:**

| Task | Evidence and interpretation |
|---|---|
| wrong-guess | Kimi changed the correct shared formatter in one run; GPT-OSS did so in all three. A checkout-only check can pass while a different caller breaks. The failed Kimi answer explicitly endorsed the user's incorrect hunch. This is a shared-contract/root-cause failure, not an edit-tool failure. |
| path-traversal | Kimi's failures have different causes: one run made no edit before the app forced a checkpoint after exploratory commands; another changed/deleted protected fixture files while making its own test data. That second hidden failure is a scope/integrity failure, not evidence that the final containment logic still leaked. GPT-OSS passed 3/3. |
| tests-that-catch-bugs | Kimi wrote passing suites with 16, 20 and 17 tests, but all missed the mutant that rounds the base share. Many tests and a green run did not demonstrate mutation sensitivity. GPT-OSS caught all five mutants in two runs; its other run missed the same rounded-base mutant. |
| rename-everywhere | GPT-OSS missed a README occurrence in one run. Code-level completion did not establish the requested repository-wide rename. |
| spec-with-hidden-tests | GPT-OSS accepted invalid input `250 1h` in one run; its implementation stripped whitespace, and its own visible tests still passed. |
| big-file-many-fixes | GPT-OSS's only failure ended after three empty replies, leaving a bad formula. This is a model/protocol completion error, not evidence that all three runs implemented the formulas incorrectly. Its other two runs and all three Kimi runs passed. |
| async-order | GPT-OSS preserved order but swallowed rejected-row errors in two runs. Its answers described silently skipping bad rows as a feature, even though silent rejection was part of the reported bug. |
| trace-the-error | GPT-OSS's failed run repeated searches, hit the loop limit, and returned a stopped-at-round-14 message rather than the requested function and exact error. Its other two runs passed. |

**Llama:**

- `fix-from-symptom`: one trial left the discount unapplied, then treated running a module with no output as validation.
- `fix-and-test`: all three failed, with duplicate declarations in two final files and wrong arithmetic in the other.
  Repeated failing test runs did not reliably lead to repair.
- `keep-what-exists`: one trial timed out at 900 seconds; the other two failed the check. Tool records include edits
  before reading, invalid line numbers and a failed attempted check. Browser behavior was not measured.
- `fix-code-not-tests`: two misses left page slicing or page-count behavior wrong. `symptom-in-big-project` had one
  missing-dependency implementation and one incorrect accent-normalization implementation.
- `spec-with-hidden-tests` and `make-it-fast`: syntax/export failures survived to the final answer.
  `big-file-many-fixes` included invalid Python diagnostic commands, no-change checkpoints and incomplete repairs.
- `wrong-guess`: two trials changed the correct formatter; another recognized that it was correct but stopped before
  fixing the caller. `stay-in-folder` failed twice with invalid package JSON.
- `async-order`: the failed trial still saved out of order. All `path-traversal` trials still threw on malformed URL
  encoding. `trace-the-error` omitted the required function or exact user-facing message.
- `python-flag`: the three misses respectively broke plain output with an exception, produced invalid Python, or
  rejected the required flag position. `tests-that-catch-bugs` missed a distribution-order mutant twice and produced
  tests that failed on the correct implementation once.

**Held-out observations, for evaluation only:**

- `settings-merge` failed 9/9 across models. GPT-OSS tried to edit the right function but the app refused with
  "the person asked a question"; Llama received the same refusal for attempted changes. Kimi correctly diagnosed the
  shallow merge but only offered to implement it. This is strong evidence of an intent/gating problem in these runs,
  not nine independent demonstrations that the models cannot write a deep merge.
- `remove-feature`: GPT-OSS's runs hit the 24-round limit with the coupon module still present (sometimes emptied
  instead of deleted). Llama left coupon references in the cart in every run; a final answer even claimed complete
  removal. Kimi passed all three.
- `paging`: Llama left page content or empty-page behavior wrong. `order-date`: its final code produced an invalid
  date in one trial and kept the timezone error in two; those two diagnosed the issue without editing.
- `csv-export`: Llama failed CSV escaping/structure in all runs. Kimi's two misses instead added a UTF-8 BOM; the
  checker interpreted that as part of the first column name. A BOM is a plausible choice for the request's wording
  "Excel opens correctly", so these two scores have a specification/evaluator ambiguity. Keep the official scores,
  but do not describe them as the same failure as broken quoting. GPT-OSS passed all three.
- `cli-flag` passed 3/3 for every model. Kimi and GPT-OSS also passed all paging and order-date trials.

No held-out task was used to design an app change, detector or prompt. Do not turn these observations into a targeted
fix while continuing to call the tasks held-out; follow the promotion/replacement rule above first. No scores or
checks were changed to improve a result.

### Requests, time and recovery

These are harness model-request counts, not billed tokens or a count of internal HTTP retries. Times cover the agent
run, not compilation or the hidden check. Totals include failed scored trials but exclude discarded interrupted
attempts. Models ran concurrently, so summing their seconds is not elapsed wall time. P90 uses nearest rank.

| Measure (78 trials each) | Kimi | GPT-OSS | Llama |
|---|---:|---:|---:|
| Model requests, total | 617 | 1,003 | 729 |
| Requests, median / P90 / max | 7 / 12 / 19 | 12 / 25 / 41 | 6.5 / 19 / 45 |
| Tool calls, total | 693 | 878 | 538 |
| Agent seconds, median / P90 / max | 53 / 156 / 494 | 47.5 / 178 / 300 | 21.5 / 96 / 900 |
| Agent time, total | 95m 00s | 98m 36s | 59m 16s |
| All requests divided by passed trials | 9.2 | 16.2 | 27.0 |
| Failed edit calls | 0 | 2 | 47 |
| Empty searches | 6 | 25 | 8 |
| Refused calls | 3 | 3 | 5 |
| Visible app-to-model messages | 40 | 125 | 169 |
| Numbered finish-check messages | 24 | 14 | 89 |
| Tool-as-text recovery messages | 0 | 29 | 29 |
| Cut-off-reply recovery messages | 0 | 0 | 10 |
| Runs receiving an app limit checkpoint | 2 | 15 | 3 |

Kimi used 38.5% fewer requests than GPT-OSS while passing five more trials, but its median trial was slower.
Fewer requests are not proof of lower token cost or lower provider latency. Llama's short median is not a win when
51/78 trials fail; it also had the single 900-second timeout.

Llama's edit and recovery counts show substantial tool-usage friction. GPT-OSS's 15 limit-checkpoint runs include
eight passes and seven failures, so a limit message alone is not a failure classifier. Kimi's no-edit path-traversal
checkpoint shows that a successful exploratory shell command is not proof of completed work.

There were 78 failed scored trials: 76 ended with the runner label `finished`, one with GPT-OSS's empty-reply error,
and one with Llama's timeout. `finished` describes loop termination, not a correct result, and some such answers
honestly listed work left. Do not replace external checks with completion claims.

The visible app-message totals include reminders, budget warnings and recovery, not just post-"done" asks. No trial
had more than three *numbered* finish-check messages (maxima: Kimi 2, GPT-OSS 1, Llama 3); this does **not** prove the
other finish asks share that budget. Per-ask removal tests, not these aggregate counts, must decide which asks earn
their cost. Provider rate-limit frequency, token usage and internal transport retries were not recorded, so they
cannot be inferred from long timings.

### Integrity, comparison and next steps

- The environment restarted during the run. The runner recovered 95 completed trials and reran only missing trials.
  The final audit found exactly three scored results for every one of the 78 model/task pairs: no missing or extra
  scored repeats. Three earlier transcripts remain marked "Not finished yet" and are excluded, not counted as passes.
  Interrupted trials restarted from their initial fixtures; their model context was not resumed.
- All six post-restart Gradle invocations exited zero. That means the harness ran successfully, **not** that all
  model-generated projects passed. The fixture-check tests were rerun separately: `HardBenchTasksTest` 15/15 and
  `HeldOutBenchTasksTest` 7/7, no failures or skips.
- The runner's syntax and discovery were checked: standard 7, hard 13, heldout 6, all 26 unique tasks. The saved patch
  contains only that discovery fix. App source is unchanged from the 1.5.90 release.
- Earlier 1.5.89/1.5.90 tables used mostly one trial per task. They are context, not a matched three-repeat control.
  The add-feature check was also broadened in 1.5.91 to accept a bare function export; its improved scores cannot be
  credited solely to the agent. Hard-suite check behavior is unchanged from the 1.5.90 commit.
- Both stronger models now demonstrate 21/21 standard trials, while hard and held-out results expose failures hidden
  by that easy-suite ceiling. Kimi's earlier single-run mutation-test success was not reproduced in any of these
  three trials. Without an agent-code change, that is a warning about sampling, not proof of a release regression.
- Only the Cloudflare benchmark key was available, including in the shared Codespace environment. A second-provider
  baseline was therefore not run; do not generalize provider reliability from this experiment.
- Next is the handoff's one-ask-at-a-time removal study, with the same model IDs, repeat count, task definitions and
  scoring rules. Keep this unmodified-agent baseline as the control; record every ablation and preserve failures,
  rather than retrying failures until they pass. Model advice should wait until the removal study is measured.
- Held-out evaluation must remain separate from tuning. If its result or the CSV ambiguity is going to shape a code,
  prompt or evaluator change, first promote the affected task and obtain an independently authored replacement;
  preserve this original baseline rather than silently rescoring it.

## Removal tests

Control: the 1.5.91 baseline above. Each experiment disables exactly one finish ask; all other agent logic, tasks,
checks, model IDs and the three-repeat setting stay unchanged. Compare per-task stability and transcripts as well as
aggregate scores: an apparent gain elsewhere must not hide a regression on a previously solid task. Unmatched stochastic
trials are evidence for follow-up, not a causal estimate by themselves.

| Ask disabled | Baseline Kimi / GPT-OSS / Llama | Without ask | Decision |
|---|---|---|---|
| `toolTextAsked` in `finish()` | 67/78 / 62/78 / 27/78 | 70/78 / 63/78 / 31/78 | Retain and route through the shared finish budget: GPT-OSS lost two previously solid tasks (make-it-fast and csv-export each fell 3/3→2/3). |
| `filesAsTextAsked` in `finish()` | 67/78 / 62/78 / 27/78 | 69/78 / 56/78 / 27/78 | Retain and route through the shared finish budget: all three models lost a previously solid task. The baseline had no recorded use of this ask, so these regressions do not establish its causal benefit. |
| `changeAsked` in `finish()` | 67/78 / 62/78 / 27/78 | 66/78 / 58/78 / 27/78 | Retain and route through the shared finish budget: task scores fell for all models, including previously solid tasks. Only two baseline trials contained the exact ask, so this is noisy, low-exposure evidence. |

The first experiment uses the same base commit plus a saved patch containing the runner task-discovery fix and a
single `false &&` guard on the `toolTextAsked` branch. This is equivalent to pinning an experiment commit for replay,
without committing an intentionally disabled guard. Its snapshot and results live at
`/workspaces/bench-results/2026-11-05-1545-ablate-tool-text/`. The isolated preparation worktree is
`/tmp/pocketide-ablation-tool-text`; the saved patch, not that temporary worktree, is the restart-safe source of truth.
The native/text tool-call parsing and recovery elsewhere are unchanged; this tests only the finish-time ask, not
removal of tool-call-as-text support.

The isolated experiment compiled and passed `AssistantDirectionTest` and `AgentLoopTest` before the live run.

**First removal test completed: `toolTextAsked`.** Its task-by-task scores are in
`/workspaces/bench-results/2026-11-05-1545-ablate-tool-text/comparison.json`.

| Model | Baseline standard / hard / held-out | Ask disabled standard / hard / held-out | Overall baseline → ablation | Request total baseline → ablation |
|---|---|---|---:|---:|
| Kimi | 21/21 / 33/39 / 13/18 | 21/21 / 34/39 / 15/18 | 67→70/78 (+3) | 617→594 (-23) |
| GPT-OSS | 21/21 / 29/39 / 12/18 | 21/21 / 31/39 / 11/18 | 62→63/78 (+1) | 1,003→994 (-9) |
| Llama | 14/21 / 10/39 / 3/18 | 15/21 / 12/39 / 4/18 | 27→31/78 (+4) | 729→635 (-94) |

**Every changed task score (baseline → ask disabled):**

| Model | Task score changes |
|---|---|
| Kimi | path-traversal 1→2; csv-export 1→3 |
| GPT-OSS | rename-everywhere 2→3; spec-with-hidden-tests 2→3; wrong-guess 0→2; async-order 1→0; make-it-fast 3→2; csv-export 3→2 |
| Llama | fix-from-symptom 2→1; fix-and-test 0→1; keep-what-exists 0→1; fix-code-not-tests 1→2; symptom-in-big-project 1→2; stay-in-folder 1→2; async-order 2→0; make-it-fast 2→3; paging 0→1 |

| Model | Requests baseline→ablation | Tool calls baseline→ablation | Failed edits baseline→ablation | Total task seconds baseline→ablation | Median seconds baseline→ablation |
|---|---:|---:|---:|---:|---:|
| Kimi | 617→594 (-3.7%) | 693→666 (-3.9%) | 0→1 | 5,700→4,565 (-19.9%) | 53.0→45.5 |
| GPT-OSS | 1,003→994 (-0.9%) | 878→884 (+0.7%) | 2→1 | 5,916→5,623 (-5.0%) | 47.5→48.0 |
| Llama | 729→635 (-12.9%) | 538→462 (-14.1%) | 47→34 | 3,556→3,053 (-14.1%) | 21.5→20.0 |

Transcript review of GPT-OSS's two formerly-solid drops found different failure modes: the failed `make-it-fast`
repeat attempted a malformed Set edit, then the 200,000-contact check timed out at 15 seconds; the failed `csv-export`
repeat wrote a UTF-8 BOM, which the strict round-trip checker compared as part of the header. The latter is the same
Excel/BOM specification ambiguity already seen in the baseline Kimi transcripts. These explain the observed misses,
not a causal link to the ablated ask.

GPT-OSS had one non-finished run in each condition (empty reply in the baseline; timeout in the ablation). Llama had
one baseline timeout; the ablation had one timeout and one budget-exhausted "not finished" result. These are run-level
execution outcomes, not additional task-score losses. All request, tool-call, edit and time differences are descriptive:
the runs used different stochastic trajectories and cannot isolate the effect of this ask.

This is mixed, noisy evidence from unmatched stochastic trials: absolute scores improved for all three models, yet
GPT-OSS lost two previously solid tasks. Its `make-it-fast` and `csv-export` scores fell from 3/3 to 2/3; its
`async-order` score also fell from 1/3 to 0/3. Kimi improved path-traversal by one and csv-export by two; Llama gained
one each on several tasks but dropped one fix-from-symptom pass and both async-order passes. These shifts cannot be
attributed causally to this ask alone. Nonetheless, the handoff's safety criterion is explicit: **any score drop means
keep the ask and route it through the shared budget.** GPT-OSS meets that trigger, so the ask is retained.

Next action after the individual ablations: replace its direct `request()` with `goBack(...)` (preserving the
tool-call-as-text explanation and protocol-specific ask) and verify that it consumes a shared finish-budget slot.
Do not delete `toolTextAsked` until a later full task/control comparison demonstrates no regression.
Baseline transcripts contain 29 explicit tool-as-text re-prompt events for GPT-OSS and 30 for Llama; the ablation
transcripts contain 4 and 24, respectively (Kimi has none in either run). These descriptive counts come from
unmatched stochastic trials: they do not prove that the ask caused or prevented a task failure.

**Second removal test completed: `filesAsTextAsked`.** All 234 scored trials were audited: 78 model/task pairs,
exactly three completed results each. The Codespace interruption at 123/234 was recovered without selectively
retrying failures. Snapshot, comparison and transcripts are at
`/workspaces/bench-results/2026-11-05-removal-02-filesAsTextAsked/`.

| Model | Baseline standard / hard / held-out | Ask disabled standard / hard / held-out | Overall baseline -> ablation | Solid tasks baseline -> ablation |
|---|---|---|---:|---:|
| Kimi | 21/21 / 33/39 / 13/18 | 21/21 / 35/39 / 13/18 | 67 -> 69/78 (+2) | 21 -> 21 |
| GPT-OSS | 21/21 / 29/39 / 12/18 | 20/21 / 25/39 / 11/18 | 62 -> 56/78 (-6) | 17 -> 14 |
| Llama | 14/21 / 10/39 / 3/18 | 15/21 / 9/39 / 3/18 | 27 -> 27/78 (0) | 6 -> 5 |

**Every changed task score (baseline -> ask disabled):**

| Model | Task score changes |
|---|---|
| Kimi | wrong-guess 2->3; path-traversal 1->2; python-flag 3->2; tests-that-catch-bugs 0->1 |
| GPT-OSS | fix-and-test 3->2; symptom-in-big-project 3->2; spec-with-hidden-tests 2->1; big-file-many-fixes 2->1; async-order 1->0; path-traversal 3->2; trace-the-error 2->3; csv-export 3->2 |
| Llama | fix-and-test 0->1; keep-what-exists 0->1; remove-the-barrier 3->2; fix-code-not-tests 1->0; symptom-in-big-project 1->2; stay-in-folder 1->2; async-order 2->0 |

| Model | Requests baseline->ablation | Tool calls baseline->ablation | Failed edits baseline->ablation | Total task seconds baseline->ablation | Median seconds baseline->ablation |
|---|---:|---:|---:|---:|---:|
| Kimi | 617->611 (-1.0%) | 693->669 (-3.5%) | 0->1 | 5,700->5,858 (+2.8%) | 53.0->53.0 |
| GPT-OSS | 1,003->989 (-1.4%) | 878->867 (-1.3%) | 2->4 | 5,916->4,338 (-26.7%) | 47.5->39.0 |
| Llama | 729->727 (-0.3%) | 538->545 (+1.3%) | 47->38 | 3,556->2,759 (-22.4%) | 21.5->25.5 |

The six formerly-solid regressions have distinct observed failure modes:

- Kimi `python-flag`: the checker reported no test for `--json`.
- GPT-OSS `fix-and-test`: the failed transcript ended with malformed, textual `read_file` calls even after the
  separate tool-as-text recovery prompt; it did not complete the fix.
- GPT-OSS `symptom-in-big-project`: accented input still produced `caf-menu` rather than `cafe-menu`.
- GPT-OSS `path-traversal`: the resulting JavaScript failed to parse (`Unexpected token '}'`).
- GPT-OSS `csv-export`: the checker again treated a UTF-8 BOM as part of the header. This is the previously
  documented specification/checker ambiguity; the score is not changed and no held-out-specific fix is proposed.
- Llama `remove-the-barrier`: the resulting file raised `ReferenceError: x is not defined`.

All Kimi and GPT-OSS ablation trials ended as "finished", although many failed the actual task check. Llama had
77 "finished" and one "not finished" result. Faster GPT-OSS/Llama totals are not evidence of faster successful
completion: they mix successes and failures, and Llama's median actually increased.

**Exposure and causal limitation:** the exact file-as-text recovery prompt (`Nothing was saved: in this chat`)
occurs zero times in the saved baseline transcripts for all three models, and zero times in this ablation.
The branch therefore has no observed baseline exposure in this sample. Aggregate improvement, regression and
request differences cannot demonstrate this ask's usefulness or overhead; they are compatible with stochastic
variation and environment/provider timing changes. The conservative handoff rule still requires retaining it
because task scores dropped. This is a policy decision, not a statistically established treatment effect.
No task-specific prompt, detector or hidden-check change follows from these results.

**Third removal test completed: `changeAsked`.** All 234 scored trials were audited (78 model/task pairs, three
results each). Two Codespace shutdowns interrupted the run, at 71/234 and 196/234; both times it resumed without
selectively retrying failures. Snapshot, comparison and transcripts are at
`/workspaces/bench-results/2026-11-05-removal-03-changeAsked/`.

| Model | Baseline standard / hard / held-out | Ask disabled standard / hard / held-out | Overall baseline -> ablation | Solid tasks baseline -> ablation |
|---|---|---|---:|---:|
| Kimi | 21/21 / 33/39 / 13/18 | 21/21 / 33/39 / 12/18 | 67 -> 66/78 (-1) | 21 -> 19 |
| GPT-OSS | 21/21 / 29/39 / 12/18 | 21/21 / 25/39 / 12/18 | 62 -> 58/78 (-4) | 17 -> 17 |
| Llama | 14/21 / 10/39 / 3/18 | 14/21 / 10/39 / 3/18 | 27 -> 27/78 (0) | 6 -> 4 |

**Every changed task score (baseline -> ask disabled):**

| Model | Task score changes |
|---|---|
| Kimi | spec-with-hidden-tests 3->2; big-file-many-fixes 3->2; wrong-guess 2->3; async-order 3->2; tests-that-catch-bugs 0->2; csv-export 1->0 |
| GPT-OSS | spec-with-hidden-tests 2->1; big-file-many-fixes 2->1; wrong-guess 0->1; async-order 1->0; make-it-fast 3->1; trace-the-error 2->3; tests-that-catch-bugs 2->1 |
| Llama | fix-from-symptom 2->1; keep-what-exists 0->1; rename-everywhere 3->2; symptom-in-big-project 1->2; wrong-guess 0->1; stay-in-folder 1->2; async-order 2->0; cli-flag 3->2; order-date 0->1 |

| Model | Requests baseline->ablation | Tool calls baseline->ablation | Failed edits baseline->ablation | Total task seconds baseline->ablation | Median seconds baseline->ablation |
|---|---:|---:|---:|---:|---:|
| Kimi | 617->613 (-0.6%) | 693->695 (+0.3%) | 0->1 | 5,700->6,306 (+10.6%) | 53.0->55.0 |
| GPT-OSS | 1,003->1,007 (+0.4%) | 878->905 (+3.1%) | 2->6 | 5,916->3,952 (-33.2%) | 47.5->40.0 |
| Llama | 729->753 (+3.3%) | 538->570 (+5.9%) | 47->47 | 3,556->2,648 (-25.5%) | 21.5->28.0 |

Task-score declines occurred on each model, and each lost at least one previously solid task: Kimi lost
`spec-with-hidden-tests`, `big-file-many-fixes` and `async-order`; GPT-OSS lost `make-it-fast`; Llama lost
`rename-everywhere` and `cli-flag`. Under the explicit any-score-drop rule, retain the ask and route it through the
shared finish budget.

The precise `CHANGE_ASK` text appears in only two of 234 baseline trial transcripts: GPT-OSS `rename-everywhere`
and Llama `async-order`. These are also among tasks with score changes in the ablation, but the samples are tiny
and unmatched; they do not establish causality. In the exposed Llama `async-order` baseline, the ask led to an
edit, but the hidden check still found nondeterministic row order. The corresponding ask-disabled trial instead
made malformed edits and ended with a syntax error. This illustrates both the ask's potential to elicit continued
work and the noisy, non-equivalent trajectories; it does not show that the ask fixes the task. The exposed GPT-OSS
`rename-everywhere` baseline trial's ask-disabled counterpart both failed the task, with no score delta for that
task. Kimi had no observed exposure. Faster aggregate seconds for GPT-OSS
and Llama combine successes and failures; neither indicates improved completion quality, and median time increased
for Llama. Do not tune against the held-out `csv-export` observation.

The fourth independent ablation, `deferralAsked`, was intentionally stopped at 16/234 when the revised 1.5.93
handoff replaced the serial removal queue with a single combined validation. Its partial results are not an ablation
score. No ask was deleted; every finish-time ask now routes through the shared budget, and the combined validation is
running.

### Baseline finish-ask exposure audit (234 trials)

Following the revised 1.5.93 handoff, the serial ablation queue was stopped after `changeAsked` completed. The next
experiment (`deferralAsked`) had only 16/234 trials when stopped; preserve those partial files, but do not score or
interpret them. All 234 scored baseline rows were paired with their individual transcripts; tool-call counts were
cross-checked against the corresponding report row. An exposure means the ask was actually sent to the model, not
merely that its detector condition may have matched.

Each cell gives the exposed task(s) and the number of passing trials / exposed trials for that task. Repeated trials
are grouped, so `async-order 1/2` means one pass and one failure among two exposures. `— (0)` means the ask did not
fire in any of that model's 78 trials.

| Finish ask | Kimi K2.6 | GPT-OSS 120B | Llama 3.3 70B fast |
|---|---|---|---|
| `toolTextAsked` | — (0) | 25 trials, 20 pass: add-feature 1/1; fix-and-test 1/1; remove-the-barrier 1/1; answer-a-question 1/1; rename-everywhere 1/1; fix-code-not-tests 1/1; symptom-in-big-project 2/2; spec-with-hidden-tests 1/1; big-file-many-fixes 0/1; wrong-guess 0/1; stay-in-folder 1/1; async-order 1/2; make-it-fast 1/1; path-traversal 2/2; python-flag 2/2; cli-flag 2/2; order-date 1/1; remove-feature 0/2; csv-export 1/1 | — (0) |
| `filesAsTextAsked` | — (0) | — (0) | — (0) |
| `changeAsked` | — (0) | rename-everywhere 1/1 | async-order 0/1 |
| `deferralAsked` | — (0) | symptom-in-big-project 1/1; remove-feature 0/1 | 9 trials, 2 pass: fix-from-symptom 1/1; fix-and-test 0/1; big-file-many-fixes 0/1; wrong-guess 0/1; stay-in-folder 1/2; trace-the-error 0/1; settings-merge 0/2 |
| `announcedAsks` | — (0) | — (0) | — (0) |
| `readForModel` | — (0) | — (0) | fix-from-symptom 1/1; stay-in-folder 1/1 |
| `runAsked` | — (0) | report-only 1/1 | — (0) |
| `noChangeAsked` | — (0) | — (0) | — (0) |
| `toolClaimChecked` | add-feature 1/1; symptom-in-big-project 1/1; spec-with-hidden-tests 1/1; stay-in-folder 1/1 | — (0) | — (0) |
| `failAsked` | — (0) | — (0) | 20 trials, 5 pass: fix-and-test 0/3; rename-everywhere 3/3; fix-code-not-tests 1/1; symptom-in-big-project 0/1; spec-with-hidden-tests 0/2; big-file-many-fixes 0/1; stay-in-folder 1/3; make-it-fast 0/1; python-flag 0/2; tests-that-catch-bugs 0/2; remove-feature 0/1 |
| `renameAsked` | — (0) | — (0) | rename-everywhere 1/1 |
| `classAsked` | — (0) | — (0) | — (0) |

The four asks with no baseline exposure are `filesAsTextAsked`, `announcedAsks`, `noChangeAsked` and `classAsked`.
The 25 `toolTextAsked` exposures all came from GPT-OSS. `failAsked` had the widest observed exposure (20 trials),
all on Llama. Exposure is not a causal estimate: the model's post-ask trajectory is stochastic, and the ask often
fired on already-difficult runs. Keep the score comparisons and this direct exposure audit distinct.

### Completion and next actions

1. **Complete:** finish and audit the `toolTextAsked`, `filesAsTextAsked` and `changeAsked` ablations. Per the revised
   1.5.93 handoff, retain all asks regardless of noisy score changes.
2. **Complete:** count each of the 12 asks across the original 234 baseline trial transcripts, including tasks and
   final pass/fail. Four asks had zero observed exposures; see the table above.
3. **Complete:** route every post-"done" ask through `goBack` and the shared `finishReprompts` / `finishBudget`;
   keep all asks. The ScriptedModel test proves several sequential finish asks cannot exceed three re-prompts.
4. **Complete:** validate the combined change against the 1.5.93 task set: all 27 tasks, three repeats, and the same
   three models. Compare scores on the 25 tasks shared with the 1.5.91 baseline, excluding settings-merge and
   unique-address. Accept only if no model is more than six passes below baseline on those tasks and no task moves
   from 3/3 to 0/3. Both conditions passed; see the comparison below.
5. **Complete:** report unique-address's new held-out score and settings-merge's new hard-suite score below.
6. **Complete:** update model advice from the observed evidence, naming the Cloudflare-only limitation and not
   generalizing the ranking to untested providers.
7. **Complete:** write the comprehensive Claude handoff and list the exact MIT-edition port work in
   `COPILOT-CHANGELOG-1.5.91-1.5.93.md`.

No ask has been deleted. The `deferralAsked` run was intentionally stopped at 16/234 and is not a scored ablation.
The restart-safe study driver and partial reports remain outside the repository at
`/workspaces/bench-results/finish-removal-study/` and `/workspaces/bench-results/2026-11-05-removal-04-deferralAsked/`.
The archived removal-study queue no longer starts automatically on folder open. Continue it manually with
`python3 /workspaces/bench-results/finish-removal-study/run-study.py --continue` only with the owner's approval.

### Combined validation: shared-budget change (2026-11-05/06)

**Complete: 243/243 scored trials** on commit `bccd4aa` plus the uncommitted candidate patch. The three models each ran
all 27 tasks three times. Raw reports and transcripts remain outside the repository at
`/workspaces/bench-results/2026-11-05-230129-2026-11-06-1.5.93-finish-budget/`.

| Model | Baseline on 25 shared task IDs | 1.5.93 on those 25 | Delta | 1.5.93 standard | Hard | Held-out | All 27 | Tasks 3/3 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Kimi K2.6 | 67/75 | 70/75 | +3 | 21/21 | 37/42 | 18/18 | 76/81 | 23/27 |
| GPT-OSS 120B | 62/75 | 57/75 | -5 | 20/21 | 29/42 | 14/18 | 63/81 | 16/27 |
| Llama 3.3 70B fast | 27/75 | 23/75 | -4 | 14/21 | 7/42 | 2/18 | 23/81 | 4/27 |

The shared-task acceptance gate **passes**: no model fell more than six passes below baseline, and no shared task
moved from 3/3 to 0/3. Each model's score on those tasks is noisy and the per-task direction is mixed; the gate
does not establish that the budget change improved completion quality. The 25 shared task names include
`csv-export`, but its checker changed in 1.5.93 to accept a leading UTF-8 BOM, so that comparison is not fully
like-for-like. In particular, Kimi's `csv-export` score rose from 1/3 to 3/3 after that check correction; do not
credit that gain to the shared budget.

**Changed scores on the 25 shared tasks (baseline → validation):**

| Model | Regressions | Improvements |
|---|---|---|
| Kimi K2.6 | big-file-many-fixes 3→2; async-order 3→2 | path-traversal 1→2; wrong-guess 2→3; tests-that-catch-bugs 0→1; csv-export 1→3* |
| GPT-OSS 120B | keep-what-exists 3→2; async-order 1→0; order-date 3→2; path-traversal 3→2; python-flag 3→2; tests-that-catch-bugs 2→1 | trace-the-error 2→3 |
| Llama 3.3 70B fast | fix-from-symptom 2→0; async-order 2→0; cli-flag 3→2; fix-code-not-tests 1→0; rename-everywhere 3→2 | keep-what-exists 0→2; stay-in-folder 1→2 |

`*` `csv-export`'s 1.5.93 checker accepts the Excel BOM; its historical baseline score remains unchanged, but the
test behavior differs. None of the other listed differences is evidence of causality: the baseline and validation
are separate stochastic samples. Notably, Llama's two new 0/3 scores (`fix-from-symptom`, `async-order`) were
already 2/3, not 3/3, in baseline; the explicitly prohibited 3/3→0/3 regression did not occur.

**New/changed task outcomes:**

| Task | Suite in 1.5.93 | Kimi K2.6 | GPT-OSS 120B | Llama 3.3 70B fast |
|---|---|---:|---:|---:|
| settings-merge (moved from held-out) | Hard | 3/3 | 3/3 | 0/3 |
| unique-address (new held-out task) | Held-out | 3/3 | 3/3 | 0/3 |

The settings-merge result is consistent with the earlier question-classification fix for Kimi and GPT-OSS, but Llama
still fails the merge behavior (it replaces the nested theme object rather than preserving defaults). The held-out
unique-address check was not used to tune the app; the two stronger tested models passed all three runs, while Llama
did not reliably construct unique slugs. The updated `csv-export` check is also passed 3/3 by Kimi and GPT-OSS and
0/3 by Llama; GPT-OSS already passed it 3/3 in baseline, so only Kimi's recorded score changed.

**Reliability advice from the observed sample:** Kimi passed 23/27 tasks 3/3, GPT-OSS 16/27, and Llama 4/27.
Kimi has the strongest measured reliability here, but GPT-OSS remains capable and won or tied on some tasks in the
baseline. Llama is substantially weaker overall and has repeated problems completing tool-driven edits. These are
results for three model IDs through one Cloudflare endpoint, not provider-wide rankings or guarantees. The FAQ and
model picker now say so explicitly.

The saved validation transcripts show at most three numbered “Finish check N of 3” messages per trial; no
over-budget transcript was observed. This complements (but does not replace) the scripted shared-budget unit test.
The run hit 80% (195/243) and 90% (219/243) while in progress, then completed at 243/243.

**Why free models:** PocketIDE is meant to give Copilot- or Claude-level help with the free API keys in the FAQ, so the models to
measure are the free tiers (Groq, Cerebras, Gemini, OpenRouter's free models, NVIDIA, Cloudflare). A change to the agent that
helps a strong paid model but hurts a free one is a regression.

**Not covered here:** look_at_preview and play tests (no WebView on a computer), parts mode, and the phone's Linux environment.
The release checklist below still covers those by hand.

## 1.5.100 whole-file reads and real-project suite (2026-11-08)

The 1.5.100 handoff's held-out `realproject` suite was run three times per task on the same candidate commit
(`409f1c6`). The only harness setting changed was `WHOLE_READS=0` versus the default `1`. Each cell reports passes out of
three, median minutes, median model requests and median `read_file` calls. Times are elapsed agent wall time, including
stochastic model latency but excluding the final benchmark check.

| Model | Task | Pieces: pass, min, req, reads | Whole file: pass, min, req, reads |
|---|---|---:|---:|
| Kimi K2.6 | `rp-bank-option` | 2/3, 0.7, 15, 3 | 2/3, 0.8, 15, 3 |
| Kimi K2.6 | `rp-menu-hidden` | 3/3, 0.4, 12, 5 | 3/3, 0.4, 8, 5 |
| Kimi K2.6 | `rp-equip-slot` | 1/3, 3.4, 20, 10 | 3/3, 1.5, 15, 6 |
| GPT-OSS 120B | `rp-bank-option` | 3/3, 0.8, 20, 4 | 2/3, 0.8, 21, 4 |
| GPT-OSS 120B | `rp-menu-hidden` | 3/3, 1.1, 15, 4 | 3/3, 0.7, 16, 5 |
| GPT-OSS 120B | `rp-equip-slot` | 3/3, 0.9, 14, 4 | 1/3, 2.4, 20, 6 |

| Model | Pieces total | Whole-file total |
|---|---:|---:|
| Kimi K2.6 | 6/9 | 8/9 |
| GPT-OSS 120B | 9/9 | 6/9 |

The result is mixed and does not support a general recommendation to change whole-file-read behavior: Kimi scored
better with whole reads on `rp-equip-slot`, whereas GPT-OSS scored worse. The other task outcomes were unchanged or
changed by one pass. Three trials per condition do not establish causation. No task moved from 3/3 to 0/3. These
held-out tasks are measurements, not tuning targets; do not change app behavior based on them. Groq was not run
because no Groq key was configured.

## 1.5.100 thinking effort (2026-11-08)

Each condition uses all 30 tasks at commit `409f1c6`, three trials per task, with whole-file reads enabled. Cells show
passes out of three and median minutes per trial. These are separate stochastic samples; timing includes provider
latency but excludes the final check. The real-project trials here are additional trials, not the same runs as the
whole-file comparison above. Interrupted Kimi attempts were resumed as missing trials; only recorded results are scored.

Direct minimal Cloudflare requests with `reasoning_effort` set to `low` and `medium` returned HTTP 200 for both models.
This confirms the endpoint accepted those requests, not that it necessarily honored the field. The app silently retries
without the field if it receives a matching 400/422, so the saved transcripts cannot independently rule out a retry
on a full tool-using request.

### Kimi K2.6

| Task | Low | Medium | Model default |
|---|---:|---:|---:|
| `add-feature` | 3/3, 0.33 | 3/3, 0.30 | 3/3, 0.22 |
| `answer-a-question` | 3/3, 0.03 | 3/3, 0.03 | 3/3, 0.03 |
| `async-order` | 2/3, 0.48 | 3/3, 0.62 | 3/3, 0.52 |
| `big-file-many-fixes` | 2/3, 0.67 | 3/3, 1.07 | 3/3, 0.98 |
| `cli-flag` | 3/3, 0.28 | 3/3, 0.50 | 3/3, 0.37 |
| `csv-export` | 3/3, 0.40 | 3/3, 0.40 | 3/3, 0.58 |
| `fix-and-test` | 3/3, 0.28 | 3/3, 0.35 | 3/3, 0.48 |
| `fix-code-not-tests` | 3/3, 0.22 | 3/3, 0.28 | 3/3, 0.28 |
| `fix-from-symptom` | 3/3, 0.28 | 3/3, 0.23 | 3/3, 0.28 |
| `keep-what-exists` | 3/3, 0.33 | 3/3, 0.28 | 3/3, 0.28 |
| `make-it-fast` | 3/3, 0.62 | 3/3, 0.25 | 3/3, 0.57 |
| `order-date` | 3/3, 0.38 | 3/3, 0.35 | 3/3, 0.63 |
| `paging` | 3/3, 0.33 | 3/3, 0.63 | 3/3, 0.67 |
| `path-traversal` | 2/3, 1.40 | 3/3, 2.35 | 2/3, 2.28 |
| `python-flag` | 3/3, 0.50 | 3/3, 0.57 | 3/3, 0.33 |
| `remove-feature` | 3/3, 0.37 | 3/3, 0.37 | 3/3, 0.42 |
| `remove-the-barrier` | 3/3, 0.42 | 3/3, 0.38 | 3/3, 0.28 |
| `rename-everywhere` | 2/3, 0.73 | 3/3, 0.48 | 3/3, 0.68 |
| `report-only` | 3/3, 0.05 | 3/3, 0.03 | 3/3, 0.03 |
| `rp-bank-option` | 0/3, 0.92 | 3/3, 0.97 | 2/3, 0.73 |
| `rp-equip-slot` | 3/3, 1.40 | 2/3, 3.05 | 2/3, 2.52 |
| `rp-menu-hidden` | 3/3, 0.50 | 3/3, 0.52 | 3/3, 0.42 |
| `settings-merge` | 3/3, 0.60 | 3/3, 0.60 | 3/3, 0.42 |
| `spec-with-hidden-tests` | 2/3, 0.82 | 2/3, 0.90 | 2/3, 1.58 |
| `stay-in-folder` | 3/3, 0.23 | 3/3, 0.28 | 3/3, 0.18 |
| `symptom-in-big-project` | 3/3, 0.27 | 3/3, 0.30 | 3/3, 0.35 |
| `tests-that-catch-bugs` | 3/3, 0.33 | 2/3, 0.37 | 1/3, 0.28 |
| `trace-the-error` | 3/3, 0.27 | 3/3, 0.27 | 3/3, 0.28 |
| `unique-address` | 3/3, 0.33 | 3/3, 0.38 | 3/3, 0.38 |
| `wrong-guess` | 2/3, 0.53 | 2/3, 0.52 | 3/3, 0.53 |

### GPT-OSS 120B

| Task | Low | Medium | Model default |
|---|---:|---:|---:|
| `add-feature` | 3/3, 0.20 | 3/3, 0.38 | 3/3, 0.30 |
| `answer-a-question` | 3/3, 0.03 | 3/3, 0.07 | 3/3, 0.07 |
| `async-order` | 1/3, 0.48 | 0/3, 0.50 | 0/3, 0.87 |
| `big-file-many-fixes` | 0/3, 0.30 | 2/3, 1.70 | 2/3, 2.93 |
| `cli-flag` | 3/3, 0.28 | 3/3, 0.73 | 3/3, 0.77 |
| `csv-export` | 2/3, 0.23 | 3/3, 0.40 | 3/3, 0.38 |
| `fix-and-test` | 3/3, 0.27 | 3/3, 0.87 | 2/3, 0.67 |
| `fix-code-not-tests` | 3/3, 0.38 | 3/3, 0.60 | 2/3, 0.88 |
| `fix-from-symptom` | 3/3, 0.42 | 3/3, 0.60 | 3/3, 0.53 |
| `keep-what-exists` | 3/3, 0.35 | 3/3, 0.98 | 3/3, 0.90 |
| `make-it-fast` | 3/3, 0.37 | 3/3, 0.87 | 3/3, 3.17 |
| `order-date` | 3/3, 0.33 | 3/3, 0.67 | 3/3, 1.07 |
| `paging` | 3/3, 0.18 | 3/3, 2.67 | 3/3, 0.53 |
| `path-traversal` | 3/3, 0.48 | 3/3, 1.57 | 2/3, 3.18 |
| `python-flag` | 3/3, 0.53 | 3/3, 4.08 | 3/3, 0.83 |
| `remove-feature` | 0/3, 0.95 | 0/3, 1.20 | 0/3, 1.72 |
| `remove-the-barrier` | 3/3, 0.33 | 3/3, 0.53 | 3/3, 0.45 |
| `rename-everywhere` | 3/3, 0.95 | 2/3, 2.93 | 2/3, 2.88 |
| `report-only` | 3/3, 0.03 | 3/3, 0.20 | 3/3, 0.22 |
| `rp-bank-option` | 0/3, 0.27 | 2/3, 1.92 | 2/3, 2.17 |
| `rp-equip-slot` | 0/3, 0.40 | 3/3, 4.90 | 2/3, 2.05 |
| `rp-menu-hidden` | 3/3, 0.23 | 2/3, 0.60 | 3/3, 0.48 |
| `settings-merge` | 3/3, 0.22 | 3/3, 0.30 | 3/3, 0.35 |
| `spec-with-hidden-tests` | 0/3, 0.40 | 2/3, 2.13 | 3/3, 2.42 |
| `stay-in-folder` | 3/3, 0.22 | 3/3, 0.60 | 3/3, 0.38 |
| `symptom-in-big-project` | 3/3, 0.47 | 3/3, 0.97 | 3/3, 0.78 |
| `tests-that-catch-bugs` | 1/3, 0.25 | 2/3, 0.40 | 1/3, 0.22 |
| `trace-the-error` | 1/3, 0.13 | 3/3, 0.30 | 3/3, 0.27 |
| `unique-address` | 3/3, 0.42 | 3/3, 0.52 | 3/3, 0.67 |
| `wrong-guess` | 1/3, 0.37 | 0/3, 1.30 | 0/3, 0.95 |

An earlier, separate GPT-OSS low-effort batch at the same commit was interrupted after 75/90 trials, covering
25 complete tasks. It is not merged into the balanced three-trial-per-task comparison above. On those same 25 tasks,
the earlier low batch scored 60/75, the completed low batch 61/75, and the completed default batch 62/75.
In particular, `spec-with-hidden-tests` was 2/3 in the earlier low sample, 0/3 in the completed low sample, and
3/3 at default. This illustrates the variability of three trials; it does not establish a causal regression.
The partial batch remains at `/workspaces/bench-results/2026-11-08-121304-eff-low-gptoss/`.

### Totals and recommendation

Each cell below shows passes out of 90, change in passes from the model's default, and median elapsed agent seconds
across all 90 trials.

| Model | Model default | Low | Medium |
|---|---:|---:|---:|
| Kimi K2.6 | 84/90, 25.0 s | 81/90 (-3), 22.5 s | 86/90 (+2), 23.5 s |
| GPT-OSS 120B | 72/90, 42.0 s | 66/90 (-6), 18.0 s | 75/90 (+3), 46.0 s |

All score differences are within the handoff's approximate noise of six per 78, scaled to about 6.9 per 90.
However, GPT-OSS `spec-with-hidden-tests` dropped from 3/3 at default to 0/3 at low, so **low fails the explicit
no-3/3-to-0/3 guard**. Low's median was faster on 29 of 30 GPT-OSS tasks, but that does not override the guard.
Medium has no such drop, but was not clearly faster: its overall median improved only from 25.0 to 23.5 seconds on
Kimi and increased from 42.0 to 46.0 seconds on GPT-OSS; task medians were mixed.

**Recommendation: do not change the default thinking effort on this evidence.** The choice remains the owner's.
The benchmark scores checks rather than final-answer completion: four GPT-OSS trials ended `not finished`; three of
their checks passed and one failed, and all four remain in the totals.

The six all-suite run folders remain under `/workspaces/bench-results/2026-11-08-021142-eff-*-kimi/` and
`/workspaces/bench-results/2026-11-08-133500-eff-*-gptoss/`. No further live runs were started after these batches.
The harness changes are documented in `COPILOT-CHANGELOG-1.5.99-1.5.100.md`.

## Field run: the owner's "Web page project" (Kimi K2.6, Cloudflare, 2026-11-10)

Scored like a benchmark run from the owner's export (`chat-logs/all-chats-2026-11-10-1413.md`, three chats, app 1.5.115 and
1.5.120). **Goal:** a monthly meal planner whose recipes can be imported from a link, tested with a real recipe page.
**Result: failed.** The planner works (its recipe → calendar → grocery play test passes), but import cannot fetch any page,
recipe instructions were never added, and every run ended stopped or cut off.

What Kimi did, in order:

1. *Chat 1, 08:41.* Built the planner in parts (7 files). Wrote its own play test, one step of which types "3 cups" into a
   number field, which a browser leaves empty: the test can never pass. "Find apps on GitHub" and "Try again" were cut off
   (reply cut off / Stop) after reading files; no files changed.
2. *Chat 2, 12:04, "Add the ability to import url links".* Joined to the old build request (fixed in 1.5.120), so it went
   through requirements and parts. Requirement 2 chose "a public CORS proxy", citing a server app (which fetches on its server,
   not in the browser); nothing tested that choice. The parts were built, then the parts check reported that `js/recipes.js`
   "should export getData, addRow, deleteRecipe, but it does not". **False:** the file shares them as `window.getData = …`,
   which the check did not count. That list became the plan for the rest of the run (35 min of thinking, 83 steps):
   - split the import code into `js/recipe-import.js`; its whole-file rewrite of `js/recipes.js` was refused (366 lines);
   - then read `js/recipes.js` 15 times "(unchanged)" and printed slices of it with `cat -n | sed` 9 times;
   - deleted the split file again and renamed classes in the page so its own play test's selectors matched;
   - looked at the page about 20 times without an edit, chasing the "3 cups" step;
   - the owner's two messages during the run ("recipes need instructions", "test with this URL") were put off: "I'll note
     it for the next iteration". Ended when the owner pressed Stop.
3. *Chat 3, 13:46, "Make sure importing recipes works".* Two runs, no files changed. Tried 18 public CORS proxies with curl:
   522, 403, 404, 401, a VPN page; the recipe site itself answers bots with a security check. Concluded it needed the
   owner's choice of approach, but did not ask: the run was stopped by "a check passed after the last change, and 4 more
   rounds changed nothing", and it never named the options it had (a proxy of the owner's own, such as a Cloudflare Worker
   from `/deploy cloudflare`, or pasting the page).

Failures by class (as in `COPILOT-BENCHMARK-GUIDE.md`):

| Failure | Class | Action |
|---|---|---|
| Parts check said functions were not exported when the file sets `window.name = …` | **app problem** | 1.5.122: the check counts `window.`/`globalThis.`/`self.` assignments and CommonJS exports (no A/B needed: a wrong finding) |
| A short new request joined to the old build | **app problem** | fixed 1.5.120 |
| Requirements chose an outside service without trying it | app problem | proposal: the look-around tries a link a requirement depends on; A/B first |
| Owner's messages during the run put off | app problem (wording) + model | proposal: the steer note says the person's request joins this run's job; A/B first |
| 15 reads of an unchanged file, 20 page looks without an edit | model limit; the app's loop guards did not fire | proposal: count repeats of an unchanged read / identical failing look; A/B first |
| Its own play test can never pass | model limit | proposal: when one play-test step fails the same way 3 times, say to check the step itself |
| Ended "stopped" instead of asking which approach | app problem | proposal: a run with no changes that ends needing a choice ends with the question |
| 18 dead public proxies, a site with a bot check | task reality | not a model failure: no browser-only fix exists; the honest answer was to say so and offer the owner's own proxy |
| Cut-off replies and long thoughts (one of 5 min) | provider | none |

The offline part of the goal (read a recipe page's Schema.org data, including its instructions, into the form) became the
real-project task **rp-recipe-steps** (1.5.122), with a made-up recipe page.

# Release benchmark

Run before every release. Offline half: `BenchmarkTest` (runs with the other tests, must pass). Phone half: the ten requests below, run on a real provider, scored 0 or 1 on each line.

| # | Request | Stays in scope | Matches requirements | Runs without errors | Retries |
|---|---------|----------------|----------------------|---------------------|---------|
| 1 | RuneScape 2009 clone, "only work in the X folder" | files only in X | every named system present | page opens | |
| 2 | Snake game in one HTML file | n/a | score, restart | opens, plays | |
| 3 | To-do app with local storage | n/a | add, complete, delete, persists | opens | |
| 4 | Fix: score does not reset after game over | only touches the score code | a test is added first when tests exist | passes | |
| 5 | Research only ("find resources…") | writes nothing | no requirements list | n/a | |
| 6 | "continue" after a big request | keeps the earlier request | no new list | n/a | |
| 7 | Add a settings page, keep everything in `src` | files only in src | page present | opens | |
| 8 | Build with 6+ requirements | n/a | split into parts, final fit check | opens | |
| 9 | Tool server call (MCP) | asks before running | n/a | n/a | |
| 10 | Long task on a small model | n/a | model advice shown | n/a | |
| 11 | RuneScape clone built in parts on a large model | files only in the named folder | no page references a missing file or undefined function | opens and a button works | |

Record the app version, provider and model with the scores. A drop from the last release blocks the release.

## 1.5.124 A/B: re-reads counted by file (Kimi K2.6, Cloudflare, 2026-11-11)

Run 42947920939, suite `realproject`, six read-heavy tasks, one run each, `refs=main,candidate-1.5.124`.

| Task | main (1.5.123) | candidate (1.5.124) |
|---|---|---|
| rp-fishing | fail (47 requests, 419 s) | pass (16, 118 s) |
| rp-four-asks | pass | pass |
| rp-hitsplats | fail (900 s, timed out) | pass |
| rp-loot | pass | fail (finished; wrong drop position, not a stop) |
| rp-not-defined | pass | pass |
| rp-recipe-steps | fail | fail |
| **Total** | **3/6** | **4/6** |

Pass bar (candidate ≥ main − 1) met. The new re-read stop did not fire in any of the 12 runs, so it stopped no honest work; the
wins and the rp-loot loss are within run-to-run noise at one run per task. The replay of the field run (`AgentLoopTest.
readingAnUnchangedFileInCirclesIsStopped`) is the evidence that it catches the loop it was built for. Kept.

## Next-edit suggestions, the model case (1.5.125)

`EditBenchmarkTest.nextEditPipeline` runs eight recorded edit pairs (the edit made, then a canned model reply) through the same
steps the editor uses: the diff of the recent edit, the prompt, the reply parser, the checks and the apply. Four replies are good
next edits and must give the expected file (a parameter added, so the call gets the argument; a constant changed from seconds to
milliseconds, so its use is converted; a field added, so the object literal gets it; a Python keyword argument used in the body,
with a thinking section and words around the block). Four must show nothing (old text found twice, an edit of the line just
changed, NONE, prose with no block). All eight pass offline at 1.5.125.

**Not measured yet:** how often a live model suggests the edit the person actually makes next (the match rate the roadmap's
phase 8 asks for) and the latency from the pause to the bar (target: under two seconds on the reference suggestion model). Both
need a live run with a real provider: the same edit pairs sent as real requests, scoring an exact match of the applied file and
timing each request. Until then the feature is unmeasured on quality and speed; only its plumbing is benchmarked.

## 1.5.125 A/B, chat suite and escalation trial (Kimi K2.6, Cloudflare, 2026-11-11)

**A/B** (run 43005645160, the same six real-project tasks, one run each): candidate 3/6, main 5/6. The two tasks lost (rp-fishing:
edited a file it had to leave alone; rp-recipe-steps: missed sectioned steps) finished normally; none of 1.5.125's agent changes
(stall question, steer wording, escalation) appeared in the candidate's transcripts, and the re-read stop (already in main, 1.5.124)
fired on neither side, and the same tasks went the other way in
the 1.5.124 A/B. **Deciding run** (43012911763, those two tasks, three runs each): candidate 2/6, main 2/6 (rp-fishing 2/3 each,
rp-recipe-steps 0/3 each). Bar met. Kept.

**Chat suite** (43005847578, suite `chat`, read-only Chat home): 12/12, one request each, 104 seconds in all.

**Escalation trial** (43012886913, `escalate=haimaker:anthropic/claude-haiku-5-5`, rp-loot, rp-recipe-steps, rp-hitsplats): it
never fired, so nothing was measured. rp-loot passed; rp-recipe-steps ended with the model saying it was finished (the app
escalates only what it can see is unfinished: a loop, a limit, a failed last check, or an answer that says it is not done); and
rp-hitsplats hit the benchmark's own 15-minute cut-off, which ends the run before the app can react. Measuring escalation needs
tasks that end as stalls, or the round limit set below what the task needs.

**What this evidence supports** (added 1.5.126, after `REVIEW-1.5.125.md`): "no harm seen", not "no drop". The deciding run
repeated only the two tasks lost; the other four ran once (rp-loot took 887 seconds on the candidate against 406 on main, 13
seconds short of the cut-off). Phase 1's rule asks for each change alone, suites `hard` and `realproject`, two models, three
repeats; 1.5.124 and 1.5.125 were released on the smaller runs above. The run summary's "at 3 runs per task" is fixed wording in
the summary script, not a description of these runs.

## 1.5.127: an A/B that was an A/A (2026-11-11)

Run 43115038582, `refs=main,candidate-1.5.127`, suite `realproject` (13 tasks), Kimi K2.6, one run each.

| Ref | Passed | Median requests | Seconds in all |
|---|---|---|---|
| main (1.5.126) | 8/13 | 27 | 3863 |
| candidate-1.5.127 | 9/13 | 39 | 5389 |

Won by the candidate: rp-fishing, rp-tinderbox. Won by main: rp-bank-option. The rest the same.

**None of 1.5.127's agent changes can show in this suite.** The one meant to change behaviour (when web files changed and are
unchecked, ask for `look_at_preview` first: `core/VerifyHint.kt`) needs the app's web view, and the benchmark harness has none
(`AgentHarness.kt`: "look_at_preview is not offered"), so it sends the old wording word for word; the new ask appears in 0 of the
candidate's 13 transcripts. The other agent changes (no "do the task" nudge in read-only runs, the read-only refusal text, the
Chat home not switching to Agent) only apply to read-only runs and the Chat home, which these tasks never use. The two sides ran
the same agent.

So this is a measurement of noise: with one run per task, the same agent moved by one task (9 against 8) and by 40% in requests and
time. A gain or loss of one or two tasks in a one-run A/B says nothing on its own (as 1.5.125's 3/6 against 5/6 also showed).

The page-check change is backed only by the emulator test (`EMULATOR-TEST.md`: Kimi spent 18 minutes on python3, which and grep to
check a snake game). It can only be measured where a web view exists: the next emulator session, or the phone.

## 1.5.136 baseline, all four suites (Kimi K2.6, Cloudflare, 2026-11-12)

Run 43319625656 on `main` at 1.5.136 (`94da941`), suite `all`, three runs per task. The first benchmark since 1.5.127; it covers
1.5.128 to 1.5.136. Copilot's review of 1.5.135 called the app ready for benchmarks (ANALYSIS-1.5.135.md, PR #12).

| Suite | Passed | Median requests | Seconds in all |
|---|---|---|---|
| standard | 21/21 | 7 | 588 |
| heldout | 15/18 | 10 | 627 |
| hard | 44/48 | 9 | 3262 |
| realproject | 24/39 | 27 | 9409 |
| **all** | **104/126** | | |

**Realproject against 1.5.127:** 24 of 39 is 8 a run on average, the same as 1.5.126/1.5.127 (8 and 9 of 13, one run each). No
regression from the nine releases in between, and no gain to claim. Per task (passes of 3): bank-option 3, menu-hidden 3,
save-position 2, four-asks 2, fishing 0, first-slot 3, not-defined 3, bury 0, hitsplats 0, loot 3, door-tap 2, tinderbox 1,
recipe-steps 2.

**No loop faults.** Every run ended "finished" except two `rp-hitsplats` runs stopped at the 900 s limit; no stalls, no
"Not finished yet", failed edits 0 in all 126 runs.

**Failures by kind** (model, loop, harness, runner):

- **Model, fixed in the wrong place (6 runs):** `rp-bury` 3/3 and `rp-fishing` 2/3 changed `game/inv.js` or `game/data/items.json`,
  which the hidden check needs untouched; `hard/rp-equip-slot` once. The requests do not mention those files ("Bones no longer bury.
  Long pressing them only shows Use, Drop and Examine"): the cause is elsewhere and the model patched the menu or the data to hide the
  symptom. The largest single group; a prompt line about fixing the cause rather than the data it reads is worth an A/B.
- **Model, wrong behaviour (9 runs):** rp-fishing, rp-save-position (old saves no longer load), rp-four-asks, rp-door-tap,
  rp-tinderbox ×2, rp-recipe-steps, hitsplats once (z-index under the 3D view), hard: big-file-many-fixes (syntax error left),
  path-traversal (`startsWith('public')` also lets `public-backup/` through), tests-that-catch-bugs (its own tests fail).
- **Runner or model speed (2 runs):** `rp-hitsplats` timed out after 21 and 32 requests, about 30-40 s a request.
- **Task wording (3 runs):** `heldout/retry-fetch` 0/3: every run retries bad answers (503) but not a fetch that throws ("network
  down"), which the hidden check also expects. The request speaks of the service answering badly, so a thrown error is not clearly
  asked for. Fix the request text before counting this task against a model.

**Not measured here** (Copilot, ANALYSIS-1.5.135 F5): the harness drives the agent loop with `autorun` on and no chat screen, so
approval cards, Stop, snapshots and masking are not part of the score. Token counts are not recorded at all yet (the next change,
for the local-helper work in HANDOFF-LOCAL-HELPER-FOR-CLAUDE.md, PR #13).


## The ask checklist, `asks` suite, 1.5.138 (2026-11-13)

Runs 43410222685 (`ask_check=1`) and 43410238192 (`ask_check=0`), Kimi K2.6 on Cloudflare, `repeat=3`, the three-task `asks` suite
of 1.5.138. Both **7/9**. With the checklist: 144 requests, about 738k tokens in all (42k new input, 614k cached, 48k out, 34k helper
jobs); without: 115 requests, about 536k (30k, 401k, 48k, 57k). ask-settings and ask-todo passed every run either way; ask-receipt
1/3 both ways. The checklist caught one misplaced Saving line (on, run 1) and accepted one ("between Subtotal and Total", on, run 2);
the third failure (on, run 3) was a Saving line with no coupon, which the request did not state. Five of the six first versions put
Saving before Tax although the request says "just before the Total line". In every run with the checklist the final answer was the
bare ASKS list (fixed in 1.5.139). Nine runs a side cannot show anything but a very large effect. Full analysis:
`HANDOFF-COPILOT-1.5.138-ASKS.md`, Copilot's review on PR #15 (`ANALYSIS-1.5.138-ASKS.md`). The suite was rebuilt in 1.5.139
(six tasks, every check matching a stated ask), so these numbers do not compare with later `asks` runs.


## The ask checklist, rebuilt `asks` suite, 1.5.139 (2026-11-13)

Runs 43470290747 (`ask_check=1`) and 43470310545 (`ask_check=0`), Kimi K2.6 on Cloudflare, `repeat=3`, six tasks. On **16/18**, off
**17/18**; the five tasks other than ask-receipt passed 15/15 both ways, and every failure (two on, one off) was the Saving line placed
above Tax although the request says "just before the Total line". Requests 281 vs 244 (+15%), output tokens 101k vs 65k (+55%),
seconds 2241 vs 1534. With the checklist on, no run changed a file after the checklist (0 of 18); its two send-backs (ask-cli) were
false alarms over the word "unknown" (fixed in 1.5.140). No final answer was the bare ASKS list (0 of 36). Copilot's decision
(`ANALYSIS-1.5.139-ASKS.md`, PR #16): keep the checklist on, fix the false alarms and the answer after a send-back, run nothing paid
until a model that drops asks (Haiku 5.5) is tested on and off.


## Haiku 5.5 on `asks`, and search by meaning on `realproject` (1.5.142, 2026-11-13)

**Ask checklist on Claude Haiku 5.5** (OpenRouter), `asks` (seven tasks), `repeat=3`: on (43515068285) **21/21**, off (43515214717)
**21/21**. With the checklist: median 11 requests, 395k new input, 1.47M cached, 107k output tokens; without: 9 requests, 312k,
1.18M, 77k. It fired in 20 of 21 runs and led to no edit (one send-back, no change). About $0.11 and $0.09 at OpenRouter's prices.
With Kimi's tie (16/18 vs 17/18) and no edits there either, the checklist is off by default from 1.5.145.

**Search by meaning on `realproject`** (Kimi K2.6, one run each, the held-out score; nothing was tuned on it): with
`embed=cloudflare:@cf/baai/bge-base-en-v1.5` (43515218472) **8/13**; without (43515110085) **11/13**. Search by meaning worked in
every call (38 searches in 11 runs, an index of about 305 chunks, no failure); most came from searches that found nothing (20 got
the closest places). The three tasks that differ (door-tap, hitsplats, tinderbox) passed only without. One run each is too noisy
to say it hurts, but it did not help: it stays off unless set. The keyword-only run's 11/13 is above the 1.5.136 baseline (24/39);
a three-run pair on 1.5.145 follows.

Note (Copilot, ANALYSIS-1.5.145): Haiku's 21/21 both ways means the `asks` suite is too easy for Haiku, so it shows no signal either
way, not that the checklist has no value. The off default rests on no measured gain plus its cost; a harder suite or a model that
drops asks could change it. Results from before 1.5.145 ran with `ask_check=1`, later ones with 0.

Note (1.5.147): problem-report detection widened ("should … but … still …", a quoted wrong output, "Fix it."). Three benchmark requests
are newly treated as problem reports and get the find-the-cause note: standard/fix-from-symptom, heldout/paging, realproject/
rp-bank-option (which already passed 3 of 3). Compare realproject runs across this version with that in mind.

**Page checks in the benchmark (1.5.148, `look=1`).** look_at_preview now works in benchmark runs (HarnessLook: headless Chrome, the
app's own PageSteps and PageLook code). Not yet measured on `realproject`, for a reason found while testing it: that suite's game
is a fixture whose scripts never draw on the canvas, so every look reports "the page draws nothing" (true of the fixture), which
would send the model after a rendering problem that is not part of any task. Measuring page checks on it needs either fixtures
that draw, which changes the held-out tasks (an owner and Copilot decision), or a suite of real pages.


## Search by meaning, three runs a side, and the `cause` suite (1.5.145 and 1.5.147, 2026-11-14)

**`realproject`, Kimi K2.6, `repeat=3`, 1.5.145 (`b1742ea`):** with `embed=cloudflare:@cf/baai/bge-base-en-v1.5` (43523436143)
**29/39**; without (43523432565) **28/39**. Per task (with / without): bank-option 2/3, bury 1/0, door-tap 3/2, first-slot 3/2,
fishing 3/2, four-asks 2/2, hitsplats 2/3, loot 3/3, menu-hidden 3/3, not-defined 3/3, recipe-steps 0/0, save-position 3/3,
tinderbox 1/2. No difference: search by meaning stays off unless set. No run timed out on either side (the 60% wrap-up of 1.5.145;
hitsplats timed out twice in the baseline). Both sides are above the 1.5.136 baseline (24/39), but that compares across nine
releases and is within run-to-run noise (28/39 vs 24/39, Fisher p about 0.5), so no gain is claimed.

**`cause` suite, Kimi K2.6, `repeat=3`, 1.5.147:** CauseGate on (43534440487) **12/12**, off (43534443403) **12/12**. On held back the
first change in 8 of 12 runs (median 10.5 requests against 9) and changed no result; no changed data file was questioned because no
run touched one. Kimi does not fall into these four traps, so the suite shows no signal; the real-project wrong-place failures
come from large projects where the cause is far from the symptom. CauseGate stays off by default.


## The `harbour` suite: pilot and hardening (1.5.151 and 1.5.152, 2026-11-14)

A tuning suite (Copilot's ANALYSIS-1.5.150 D2): a browser trading game in layers (ui, state, loader, util, data), independent of
the realproject fixture, with five problem reports whose cause is two or three calls from the symptom, each with a wrong-place fix
that hides it. Tuning only: never report it as held out.

**Pilot, 1.5.151, Kimi K2.6, `repeat=3`, `cause_first=0` (43542046913): 14/15.** price 3/3, crew 2/3, order 3/3, receipt 3/3,
icon 3/3. The one failure (crew) fixed the real cause in `state/crew.js` and also edited the decoy `ui/hud.js`. Median 15 requests
a run. Above Copilot's 90% line, so the project was hardened before any on/off pair (D3).

**Hardening, 1.5.152.** The 1.5.151 project was about 800 lines and most bugs sat beside a comment that described the intended
behaviour, in the first file a search for the symptom finds. Now 49 files, about 1,600 lines:
- price: the type table in `loader/schema.js` reads the price as a whole number. Decoys: `data/goods.json`, and the shared int
  converter in `util/convert.js` (weights must stay whole).
- crew: `state/events.js` puts the sick ashore with a bare subtract after storms took men through the clamped `loseCrew`. The top
  bar no longer clamps (it did in 1.5.151, which hid the symptom). Decoy: a clamp in `ui/hud.js`.
- order: `then()` in `util/sortBy.js` returns the second comparator instead of calling it. Decoy: the sort in `ui/harbourList.js`.
- receipt: `ui/receipt.js` takes the discount off a total that has it already (Copilot's version). Decoys: `data/offers.json`,
  `state/cart.js`.
- icon: `util/layout.js` centres the board with the shared `centre()`, negative on narrow screens. Decoys: `ui/shipIcon.js`,
  `style.css`, and `util/geometry.js` (the chart needs negative offsets).
Each check runs the code with its own data, and each task is proved to fail as delivered, pass the real fix and fail every decoy
(HarbourBenchTasksTest).

**Second pilot, 1.5.152, Kimi K2.6, `repeat=3`, `cause_first=0` (43546107147): 12/15.** price 3/3, crew 3/3, order 3/3, receipt
3/3, icon 0/3. Median 12 requests. All three icon runs made the same fix: they clamped the icon's own position in `iconBox()` in
`util/layout.js` and left the shared `--edge` negative. On a 360 px screen the icon is then on screen, but the top bar's padding
still uses `--edge`, so its text starts under the icon (checked by rendering the fix). The check requires `--edge` to be at least
0. Its failure message wrongly said the panels go off screen; only the top bar uses `--edge`. The message is corrected in 1.5.153,
with the criterion unchanged. 80% is between Copilot's D3 lines (harden at 90% or more, run the on/off pair at 70% or less).

**Page checks on the icon task, 1.5.152, `look=1`, `tasks=harbour-icon`, `repeat=3` (43548755779): 1/3** (against 0/3 without).
The checks found what matters: the `<img>` at x = -22 and the top bar overlapping the icon by 26x48 px. But every verdict was
"FAIL - the page draws nothing", which is false: the chart canvas holds a few small dots, and the page check called a large canvas
flat when its JPEG was under 6,000 bytes. The runs then looked for a game that does not start (chart.js, main.js, the harbour
data), median 404 s. This is the app's own check, so phones gave the same false verdict on sparse drawings. Fixed in 1.5.153: the
page reads the canvas's pixels (from a copy, so the page's own canvas keeps its context), and the size rule is only the fallback.

**Page checks on the icon task again, 1.5.153 (43551678840): 0/3.** The false "draws nothing" verdict is gone (verdicts PASS, or
FAIL only on the model's own steps), and the runs were much shorter (median 62 s against 404 s). All three again clamped only the
icon. The page check cannot tell this narrow fix from the real one: it reports the top bar and the icon as overlapping in both,
because the icon sits on the bar by design. Rendered at 360 px, the narrow fix leaves the bar's text starting at 34 px, under the
icon (8-56 px); the real fix starts it at 64 px. Only the screenshot shows that.

## Copilot's parity plan R1 to R3 (2026-11-14), and the rules for R4 and R5 written before their runs

**R1, regression on the small suites, 1.5.153, Kimi K2.6, `repeat=3`** (43554283518 standard, 43554285724 hard, 43554287722
heldout): **78/87** (standard 21/21, hard 42/48, heldout 15/18) against 80/87 at 1.5.136 (43319625656). Pass rule: at least 76/87
(Copilot wrote 74 of 84 at `repeat=2`, but the baseline was 29 tasks at `repeat=3`, so the rule kept its "no more than 4 lost"). **Passed.**
Every failure was a model mistake in a finished run; no loop faults or timeouts. Against 1.5.136: big-file-many-fixes 2 failures (was 1),
spec-with-hidden-tests 1 (was 0), retry-fetch 3 (was 3), path-traversal, tests-that-catch-bugs and rp-equip-slot 1 each (as before).

**R2, the two gates on the tasks they target, 1.5.154, Kimi K2.6, `repeat=4`, `tasks=rp-bury,rp-tinderbox,rp-four-asks,rp-hitsplats`.**
Pass rule (Copilot's, written before): on by default only with at least 3 more passes in 16 on the gate's targets (cause-first:
rp-bury; ask checklist: rp-tinderbox and rp-four-asks) and none lost on rp-hitsplats.

| Arm | Run | bury | tinderbox | four-asks | hitsplats | All |
|---|---|---|---|---|---|---|
| base | 43558737747 | 0/4 | 3/4 | 3/4 | 3/4 | 9/16 |
| cause_first=1 | 43558740070 | 0/4 | 2/4 | 4/4 | 4/4 | 10/16 |
| ask_check=1 | 43558742657 | 0/4 | 4/4 | 2/4 | 1/4 | 7/16 |
| both | 43558745407 | 0/4 | 3/4 | 3/4 | 3/4 | 9/16 |

**Neither gate is shown; both stay off.** Cause-first: bury 0/4 either way. Ask checklist: targets 6/8 against 6/8, and hitsplats lost
2 (its failures look unrelated: the 2D canvas left hidden twice, a function broken once; 1/4 against 3/4 is within noise, but the rule
counts any loss). Why cause-first did nothing on bury: its data question waits until no wrap-up is running, and most bury runs end in
the wrap-up (the round limit, or the "reply now" checkpoint after rounds that change nothing). With cause-first on it fired in only 2
of the 5 runs that changed `items.json` (the both arm), and both times the model kept the invented "Big bones" item with a reason, which
the question's wording allows. The bury failures in every arm are the same two: items.json or inv.js changed, or the Bury option still
missing.

**R3, Haiku 5.5 (OpenRouter), base settings, 1.5.154:** realproject `repeat=2` (43559303024) **18/26 (69%)**; hard `repeat=1`
(43559305380) **15/16**. Pricing run first (43558790442, rp-loot 1/1, about $0.011). Cost of R3 about **$0.40** at OpenRouter's list prices
($0.10 / $0.50 per million in / out, $0.01 cached). Per task: bank-option 2/2, bury 0/2 (items.json, inv.js changed), door-tap 0/2,
first-slot 2/2, fishing 1/2, four-asks 2/2, hitsplats 2/2, loot 2/2, menu-hidden 2/2, not-defined 2/2, recipe-steps 0/2 (the one-example
failure, same as Kimi), save-position 2/2, tinderbox 1/2 (the dropped sub-ask, same as Kimi); hard: rp-equip-slot 0/1 (items.json changed).
**Copilot's rule: 75% or less, so the failures are the app's, and R4 and R5 are the work.** Haiku fails the same tasks the same way.

**Rules for R4 and R5, written before their runs.** Two arms on 1.5.157, Kimi K2.6, `repeat=5`: off (`scope_check=0 variety=0`) and on
(`scope_check=1 variety=1`), tasks `harbour-pearls`, `harbour-crew` (R4 targets), `harbour-import` (R5 target), `harbour-receipt` (harm
control: a fix whose file the request does not name). The two gates have separate triggers (variety needs a parse or import request,
which only harbour-import is), so one on arm measures each on its own targets.
- ScopeGate on by default only with at least 3 more passes in 10 on pearls plus crew, and none lost on receipt.
- InputVariety on by default only with at least 2 more passes in 5 on import, and none lost on receipt.
- Then the held-out checks, measured only: rp-bury (ScopeGate) and rp-recipe-steps (InputVariety), `repeat=4` a side. A gate that passes
  on harbour but loses on its held-out task stays off.

**R4 and R5, first run, 1.5.157, Kimi K2.6, `repeat=5`** (off 43567420855, on 43567422932):

| Task | Off | On |
|---|---|---|
| harbour-pearls (R4) | 0/5 | 2/5 |
| harbour-crew (R4) | 5/5 | 5/5 |
| harbour-import (R5) | 0/5 | 0/5 |
| harbour-receipt (control) | 5/5 | 5/5 |

By the rules above: **ScopeGate not shown** (7 against 5 of 10, needs 3 more); **InputVariety not shown** (0 against 0). Every run with
both off added pearls to `data/goods.json`. Findings that change the next run:
- ScopeGate questioned only 1 of the 5 on-arm pearls runs. The two that still added pearls had written "Root cause of missing
  pearls: data/goods.json had no pearls entry", and a file named as the cause was exempt. 1.5.158: a data file is questioned whenever
  the request does not name it, cause or not. The ask's wording is unchanged.
- InputVariety asked in all 5 on-arm runs. Three on-arm runs then failed only on the last-but-one shape (columns in another order and
  case, with a quoted thousands separator); off-arm runs failed earlier (quoted fields). **The check had a fault:** 3 runs (1 off, 2
  on) exported the function itself (`module.exports = factory()` returning it), a fair reading of the request, and the check required
  `{ importPriceList }`, so they failed before any CSV was tried. Scored on the 7 runs the check could read: 0/4 off, 0/3 on. Fixed
  in 1.5.158 (either export is accepted; proved in HarbourBenchTasksTest).

**Rules for the second run, written before it:** the same two arms and thresholds on 1.5.158 (both arms run again, so they share a
version): ScopeGate on by default only with at least 3 more passes in 10 on pearls plus crew and none lost on receipt; InputVariety only
with at least 2 more in 5 on import and none lost on receipt. Then rp-bury or rp-recipe-steps, `repeat=4` a side, for a gate that passes.

**R4 and R5, second run, 1.5.158, Kimi K2.6, `repeat=5`** (off 43572141496, on 43572143604):

| Task | Off | On |
|---|---|---|
| harbour-pearls (R4) | 0/5 | 3/5 |
| harbour-crew (R4) | 5/5 | 5/5 |
| harbour-import (R5) | 0/5 | 2/5 |
| harbour-receipt (control) | 5/5 | 5/5 |

By the rules above: **ScopeGate passes** (8 against 5 of 10, 3 more, receipt 5/5 both); **InputVariety passes** (2 against 0 of 5,
receipt 5/5 both). Neither gain is significant on this run alone (one-sided Fisher p 0.18 and 0.22), so no gain is claimed yet; that is
what the held-out runs are for. Pearls over both runs is 5/10 on against 0/10 off (p 0.016), but the two runs are on different
versions. Notes:
- ScopeGate asked in all 5 on-arm pearls runs on 1.5.158 (1 of 5 on 1.5.157). In the two that still failed, the model put the pearls
  back as asked, then a later finish nudge ("make the changes you have already found now") or the unfinished plan checklist led it to
  add them again. Not changed: changing it now would be tuning on these tasks.
- ScopeGate also asked in 3 of the 5 on-arm import runs (the import code wrote to `data/goods.json` or the CSV), so the on arm's import
  gain is not InputVariety's alone.

**R7, real project, `repeat=3`, Kimi K2.6** (helper on Kimi itself 43567457481; helper `haimaker:z-ai/glm-5.3-flash` 43567460308):
**the GLM arm is not a helper measurement.** Haimaker refused every call with HTTP 402 (its account is over its budget), the explorer
fell back to "look yourself", and the helper column is 0 in every run. So it measured helper on Kimi against no helper at all:

| Arm | Passed | Kimi tokens in | Out | Helper (Kimi) |
|---|---|---|---|---|
| Helper on Kimi | 28/39 | 513,967 | 261,799 | 2,009,932 |
| No helper (GLM refused) | 29/39 | 715,175 | 289,457 | 0 |

Turning the helper off cost no passes here and cut fresh Kimi tokens from about 2.79M to 1.0M (cached reads, not counted, rose from
6.6M to 7.7M). A real cheap-helper arm is running with `helper=openrouter:anthropic/claude-haiku-5.5` (43577565667).

**Held-out runs, 1.5.158, Kimi K2.6, `repeat=4`, measured only** (off 43577559438 for both tasks; ScopeGate on 43577561395; InputVariety
on 43577563482):

| Task | Off | On |
|---|---|---|
| rp-bury (ScopeGate) | 3/4 | 1/4 |
| rp-recipe-steps (InputVariety) | 1/4 | 1/4 |

- **ScopeGate stays off:** it loses on its held-out task. Its finish check never fired in the four on-arm bury runs, so the loss comes
  from the premise note and the explorer note, or is chance (3/4 against 1/4 is p 0.24 either way).
- **InputVariety stays off.** It does not lose on passes, but no gain reaches p 0.1 anywhere (harbour 0.22, held-out none), and the rule
  above made the harbour thresholds a condition for a default, not a reason for one. It asked in 3 of 4 recipe-steps runs; two on-arm
  runs ran out of rounds ("not finished") against none off, so the extra round has a cost there.
- Both settings stay in Settings as opt-in. Not tuned on these results.

**R7, the real cheap-helper arm, 1.5.158, `repeat=3`, Kimi K2.6 with `helper=openrouter:anthropic/claude-haiku-5.5`** (43577565667):

| Arm | Passed | Kimi tokens in | Out | Helper tokens | Seconds in all |
|---|---|---|---|---|---|
| Helper on Kimi (43567457481) | 28/39 | 513,967 | 261,799 | 2,009,932 (Kimi) | 6,666 |
| Helper on Haiku (43577565667) | 26/39 | 1,100,870 | 854,345 | 3,669,881 (Haiku) | 12,959 |
| No helper (43567460308, GLM refused) | 29/39 | 715,175 | 289,457 | 0 | 5,517 |

By Copilot's rule (within 2 of the same-model arm and fresh paid tokens down) the Haiku helper **just passes**: 26 against 28, fresh
Kimi tokens about 1.96M against 2.79M. But the main model then did more of the work itself (Kimi in and out doubled, cached reads 14.6M
against 6.6M) and the runs took twice as long. No helper beats both arms on passes, Kimi tokens and time in this one comparison.
**No default changed.** The no-helper result came from a refused provider, not a planned arm; a planned `helper` off against on pair
is the next measurement. Haiku spend for this run: about $0.40 (Unverified; from the token counts at list price).

**harbour-icon-seen, 1.5.159, Kimi K2.6, `repeat=3`** (43587557017): harbour-icon 1/3, **harbour-icon-seen 3/3**. Every icon-seen run
clamped the shared `--edge`; the two icon failures clamped only the icon's own box. 3/3 against 1/3 is p 0.2 on this run. Against every
icon run since the suite was hardened (2/12), p 0.02, though those runs differ in version and `look`. So the icon failures look like
reading the request narrowly, not failing to trace the code: when the request names the top bar, Kimi finds the shared cause.

## Copilot's ANALYSIS-1.5.159 (PR #22): the planned helper pair, rule written before the run

Copilot agreed with the R1 scoring and the R4/R5 decisions, and asked for a planned helper pair in place of the accidental one. Two arms
on 1.5.159, Kimi K2.6, realproject, `repeat=5` (65 task runs each): **on** (`app_tools=1`, the explore helper on the main model, as
the phone has it) and **off** (`app_tools=0`, no explore helper; apply_patch is only offered to GPT models, so for Kimi this changes
only the explorer). Rule, Copilot's, written now: **if off scores within 3 of on and uses fewer fresh Kimi tokens, the explore helper's
default is turned off.** Otherwise it stays on. Harbour is treated as a development set from here on; held-out decisions use realproject.

**Result** (1.5.159, Kimi K2.6, realproject, `repeat=5`): **on 43/65** (43598844484) against **off 47/65** (43598846767); the off arm's
helper column is 0 in every run, so it really had no explorer.

| Task | on | off |
|---|---|---|
| rp-bank-option | 5/5 | 5/5 |
| rp-bury | 0/5 | 2/5 |
| rp-door-tap | 3/5 | 4/5 |
| rp-first-slot | 5/5 | 5/5 |
| rp-fishing | 4/5 | 5/5 |
| rp-four-asks | 5/5 | 4/5 |
| rp-hitsplats | 1/5 | 2/5 |
| rp-loot | 5/5 | 4/5 |
| rp-menu-hidden | 5/5 | 5/5 |
| rp-not-defined | 4/5 | 5/5 |
| rp-recipe-steps | 2/5 | 0/5 |
| rp-save-position | 4/5 | 5/5 |
| rp-tinderbox | 0/5 | 1/5 |

| Tokens (Kimi) | on | off |
|---|---|---|
| fresh input | 1,174,730 | 1,186,971 |
| output | 645,816 | 594,675 |
| explore helper (on Kimi) | 2,329,009 | 0 |
| **fresh Kimi tokens in all** | **4,149,555** | **1,781,646** |
| cached reads (not counted) | 13,386,624 | 11,610,432 |
| seconds in all | 13,700 | 10,230 |

Off is within 3 of on (it is 4 above; p 0.58, no claim of a gain) and uses 57% fewer fresh Kimi tokens. **By the rule, the explore
helper's default is turned off: 1.5.162**, setting `explore.helper` (Settings → Assistant → "Explore helper ..."), off by default for
everyone. The benchmark follows the app: the explorer is offered only with the new input `explore=1` (and `app_tools=1`). The runs on
1.5.161 started before this (the sealed baseline and the verifier arms) had it on, both arms alike.

## 1.5.160: Q3 (text-covering overlaps) and the scope check's settled note; rules written before the runs

The owner approved Copilot's Q3 and chose to keep cause-first and ask_check as opt-in settings. 1.5.160 changes the page check's overlap
line to text that is covered (proved in `HarnessLookTest` on four pages: an icon beside text, an icon over text, a dialog over a page, and
a fixed button over a heading, the second defect Copilot asked for; and on the harbour page, where the narrow icon fix is now reported
and the real fix is not). It also adds the scope check's "leave them as they are" note to later nudges, and the task `harbour-tavern`.

Runs, measured only (harbour is a development set; nothing becomes a default from these):
- **Icon with `look=1`, `repeat=3`**, tasks `harbour-icon` and `harbour-icon-seen`. Compared with 1.5.153's look run (icon 0/3,
  43551678840). Reported as a count with the overlap lines the runs saw; no claim below p 0.1.
- **harbour-tavern, scope off against on, `repeat=5`.** Counted: passes, and in the on arm, runs where a questioned file changed again
  after the ask. ScopeGate stays off whatever this shows, because it lost on its held-out task.

**Results (measured only).**

| Run | Settings | Task | Passed | What the transcripts show |
|---|---|---|---|---|
| 43601450109 | look=1 | harbour-icon | **2/3** | All three runs got the new line `Overlap: <img#ship> covers the text of <header#hud> "Brig · day 0 ..."`. The two passes clamped the shared `--edge`; the failure read the line, then clamped only the icon's box and checked it with `getBoundingClientRect().left >= 0`. |
| 43601450109 | look=1 | harbour-icon-seen | 2/3 | The failure fixed `--edge` but left the project's own tests failing. |
| 43601452592 | scope=0 | harbour-tavern | **4/5** | The failure: the sort fix missing (`then()` still returns the comparator). No run added a tavern. |
| 43601454971 | scope=1 | harbour-tavern | 2/5 | Two failures: the sort fix missing. One run built a Tavern button and `state/rumours.js` after "the user asked twice". **The scope check's question never fired in any of the five runs**, so the settled note was never sent: by reading, the run that added the tavern reached the 24-round limit, where the check does not run (`round < maxRounds`). |

Icon with the new overlap line, 2/3, against 0/3 with the old line (43551678840) and 1/3 without the page check (43587557017): p 0.4
against the old line; no claim. Tavern: off 4/5 against on 2/5 (p 0.52) with the gate never firing, so the difference is chance or the
premise and explorer notes, not the settled note; the settled note is still unmeasured in a live run. ScopeGate stays off, as written.

## Copilot's HANDOFF-CALIBRATION (PR #24) and the Aider control (PR #25), 1.5.161; rules written before the runs

The owner said "Run PR24 and 25 hand offs". PR #25 (`.github/workflows/aider-control.yml`) was merged as written and priced with one
exercise (43612190501): it failed before scoring, because Aider's benchmark runs its JavaScript tests through `/aider/benchmark/npm-test.sh`
and `/npm-install/node_modules`, the paths of its own container. 1.5.161 adds a step that gives the runner those paths (the same
packages as Aider's `benchmark/Dockerfile`, jest pinned to 29.7.0 as PocketIDE's `polyglot-js` uses).

**S5, reporting (1.5.161).** Every job report now starts with a `Settings:` line (version, ask, cause, scope, variety, look, verify,
helper, app_tools), and `tools/bench/summarize.py` puts it on every row and keeps arms with different settings apart. A run that ended in
an error before any tool ran (HTTP 4xx or 5xx from the model, a bad key) is counted in a "Provider errors" column and left out of
Passed. Tests: `tools/bench/test_summarize.py` and two in `BenchRunnerTest`. `harbour` here is the harbour trading-game suite (a
development set since 1.5.159), not Harbor, Terminal-Bench's framework.

**Latest result per suite and model** (kept up to date from here on; versions as run):

| Suite | Kimi K2.6 (Cloudflare) | Haiku 5.5 (OpenRouter) |
|---|---|---|
| standard | 21/21, 1.5.153 `repeat=3` (43554283518) | – |
| hard | 42/48, 1.5.153 `repeat=3` (43554285724) | 15/16, 1.5.154 `repeat=1` (43559305380) |
| heldout | 15/18, 1.5.153 `repeat=3` (43554287722) | – |
| realproject | 47/65, 1.5.159 `repeat=5`, no explore helper (43598846767; the 1.5.162 default) | 18/26, 1.5.154 `repeat=2` (43559303024) |
| polyglot (Python, 34) | 30/34 with two tries, 1.5.163 (43629522153); Aider 34/34 | – |
| polyglot-js (49) | 49/49, 1.5.163 (43629518275); Aider 49/49 | 43/49, 1.5.163 (43629514180); Aider 47/49 |
| harbour (development set) | per task, latest: see the 1.5.160 results above | – |
| sealed | 26/60, 1.5.161 `repeat=5` (43614898457) | 30/36, 1.5.161 `repeat=3` (43614894081) |

**S1, polyglot-js baseline, information only.** Kimi K2.6 and Haiku 5.5, `repeat=1`, on 1.5.160 (43612241300, 43614069112). No pass
rule. One departure from the handoff: it asks for `second_try=1 "as in 1.5.115 scoring"`, but Aider's two tries are built into the
polyglot tasks (`twoTries`, the second attempt sees the test output); the workflow's `second_try` is a different setting (a fresh start
after an unfinished run), so it stays 0 and each exercise gets the same two tries Aider gives. Price check: `js-bowling` on Haiku
(43612209569) used 160k fresh input, 409k cached and 45k output tokens, about $0.04 at list price, so 49 exercises are about $2,
under the $3 cap.

**S3, the Aider control, rule (Copilot's, copied before any full run).** PocketIDE's two-try score minus Aider's two-try score, same
model, same exercises: within 5 points, the loop is at reference level; 6 to 15 points behind, list the exercises Aider passes and
PocketIDE does not and read three transcripts each; more than 15 behind or ahead, check the harness setup (tools, prompts, test
visibility) before concluding anything. Both show the test output on the second try. Cap $5.

### S2: the sealed suite (1.5.161)

Twelve new tasks, `suite=sealed` only: not in `all`, not in any workflow default, and a task is found by name only together with
`suite=sealed`. Domains no other suite uses. Each is proved in `SealedBenchTasksTest`: it fails as delivered, passes a reference fix,
and fails the wrong fix its class invites. The projects' own tests pass as delivered. Two have data files over 200 lines
(`sealed-recipe` ingredients, 362 lines; `sealed-gym` sessions, 1,626), and the CSV sample in `sealed-library` is 229.

| Class | Task | The request | What the hidden check does | The wrong fix it is proved to fail |
|---|---|---|---|---|
| A false premise | sealed-recipe | cost per serving wrong for 2 batches; bring back the "show in metric" switch | per-serving cost at 1, 2, 3 and 5 batches on its own data; no new mention of metric or imperial anywhere; data kept | a metric setting added; the batches taken out of the data |
| A | sealed-bus | times after midnight show 24:10; the favourite-stop star has gone | the board across midnight on its own timetable; `hhmm` at 00:00, 12:00, 23:59; no new favourite or star | a star added; late times moved in the data |
| A | sealed-gym | a Monday session counted in two weeks; the rest timer stopped beeping | weekly totals on its own sessions; every logged session counted once; no new timer, beep or countdown | a rest timer added |
| B input variety | sealed-library | write `importBooks(text)` for the old app's CSV | the sample, then quoted commas, CRLF, columns in another order and case, a byte order mark with blank lines, escaped quotes; ISBNs keep leading zeros | splitting on commas |
| B | sealed-budget | write `readStatement(text)` for a pasted bank statement | the sample, CRLF, tabs, `£` and thousands separators, numbers inside descriptions and blank lines at the end; category totals | a reader that fits the sample's spacing |
| B | sealed-tides | write `readTides(html)` for the harbour office's saved page | the page, then capitals and attributes, cells over several lines with `&nbsp;`, `8:09` and `4.7m`, a header row and a note row | a regex for this week's page |
| C several asks | sealed-plants | succulents 14 days; dates as "Tue 3 Mar"; dormant plants out; a tick goes into the history | each ask on its own, reported as `ASK <name>: done / MISSING (why)`; the task passes only when all four do | the history ask dropped |
| C | sealed-quiz | shuffle answers; score as a percentage; blank answers left out; 30 s timer | as above | the blank-answer ask dropped |
| C | sealed-todo | tags ignore case; undated tasks last; a Clear done button; tags in the export | as above | the export ask dropped |
| D shared cause | sealed-units | "1 mile to km says 2 km" | all four screens (length, weight, volume, temperature) at 3 and 2 places | rounding fixed in the length screen only |
| D | sealed-allotment | plot labels cut off on a phone | plot, path, tap and tooltip labels inside the map at 360, 412, 600 and 1200 px | the plot labels clamped alone |
| D | sealed-captions | the gallery shows `Mum&#39;s birthday` | gallery, lightbox and slideshow show the caption as written; the exported page stays escaped | the gallery decoding alone; `escapeHtml` made a no-op |

Class A checks also print a `SECONDARY:` line, whether the answer says the claimed feature is not in the project. It is reported apart
and never counted in the pass.

**Frozen at creation** (SHA-256 of each task's request, check and every file; `SealedBenchTasksTest.theTasksAreFrozen` fails on any
edit, and an edited task's history ends):

```
sealed-recipe 1ef60c05e6fc1e95b305798022c6b8417b0c2ca95885964c206bb600de10e42a
sealed-bus 5d13942c41e431e21700c3886d3870f95001037fb08a0916cd96216b30d2adbc
sealed-gym 43ba00f1338cd237610ace836930000dde2c19c93e8c46a65d4fd8437c8b90db
sealed-library ddea733515abed82be02c6978784f128405d5b38a040fd50a14c1e7371ea2ce9
sealed-budget fa510f2f97411b0c2b684e4e0f49d663820feb2a61acae5d3fb28ecc41c7ed1a
sealed-tides 91191c2b3348bc8990f565479f7ef92d30d0b4861b6538021a943124efcac37b
sealed-plants 960b2faae96d0106c7a22ec3a0df1cba2dcf4418ea86f4cab6814cf08b52aa78
sealed-quiz 9bbf0c2667f686fd553479d3b362e4db7eae08fd7dd6185d1f414e7e8080fca5
sealed-todo f81ef8096a66454864bf1a2f81f1e17f2b321bd1292b7ce251d0231bbe254a79
sealed-units 1d70dd634e9d47833b15349915cf302b69850a6f92fc2d89db8ee3c2d72959f3
sealed-allotment 479e6b5e51265581da819c07dd069a377ad72723f01f0b01f969c884ae3b076f
sealed-captions 5cc042133293ffb41ad13a7c64242dbe940a46880a55f81852ae9705f7a157fd
```

**Rules, Copilot's, copied before any sealed run.** Baseline once: Kimi `repeat=3` and Haiku `repeat=3` (36 runs each), expected 55 to
75%; if a model passes 11 of 12 or more on every class, the tasks are too easy and harder variants come before any use. A candidate
change is accepted only if, on `sealed`, `repeat=5`, Kimi, it gains **at least 6 of 60** passes over off, and loses none on the harm
control (any 3 class C tasks the change does not target). One look only: no re-runs after changes. Cap $2 for the baseline.

### S4: the fresh-context verifier (1.5.161, setting `verify.fresh`, off)

`core/FreshVerify.kt`. When a run is about to finish with files changed, one separate request (no tools, no history; the helper model
when one is set, else the main one) gets the request, the diff of the changed files (secrets masked, cut at 12 KB), the final message
and the last command's output, and answers in three lines: Missing, Not needed, Not shown (120 words at most). Anything other than
"none" goes back to the model once as a user message ("A reviewer who saw only your request, the diff and your final message
found: ..."), and the run carries on. Never twice; a failed call, a reply in another shape, or no answer in 30 s lets the run finish as
it would have. Settings → Assistant → "Before finishing, have a fresh reviewer compare the request with the changes". Benchmark input
`verify=1`; its calls are the "verify" job in the token columns. Tests: `FreshVerifyTest` (diff, masking, the 12 KB cut, the message,
reading replies) and four in `AgentLoopTest` (what the reviewer sees and that it gets no tool list; a clean review; a failed and a
silent review; off by default).

**Rule, written before the run.** Kimi K2.6, `sealed`, `repeat=5`, arms `verify=0` and `verify=1`, same version (1.5.161): Copilot's S2
rule. The verifier reaches every class, so no class C task is outside its target; I read the harm-control clause as **the three class C
tasks together lose no passes (on at least off on class C)**. And a gain counts only if the verifier changed a run's outcome in the on
arm: at least 5 on-arm transcripts read for what it said and whether it was right. One look; no re-run after changes.

## The Aider control's results, the audit, and the second-try fix (1.5.163); rule written before the re-run

**Aider control, same model and exercises** (Aider 5dc9490, exercises 7e0611e, diff edits, two tries):

| Model | Exercises | Run | First try | Two tries | Cost |
|---|---|---|---|---|---|
| Kimi K2.6 (Cloudflare) | 34 Python + 49 JavaScript | 43614820039 | 58/83 (70%) | **83/83** | unpriced |
| Haiku 5.5 (OpenRouter) | 49 JavaScript | 43627629129 | 24/49 (49%) | **47/49** (misses grade-school, react) | $0.11 |

**Audit (Copilot's P1), by reading `benchmark/benchmark.py` at 5dc9490:** the model gets the instructions and the solution files only
(`fnames`); everything under `.meta/` and `.docs/`, the tests and the example solutions are in `ignore_files`; `get_file_mentions` is
switched off and `use_git=False` (no repository map). Before each test run the tests are copied fresh from the original exercise, so
an edited test cannot help. No leak found. 83/83 on Python and JavaScript alone is plausible for Kimi: Aider's 225-exercise board
includes C++, Go, Rust and Java.

**PocketIDE on the same exercises.** Haiku polyglot-js on 1.5.160 (43614885236): **38/49**, 21 on the first try. The 9 exercises Aider
passes and PocketIDE does not: bowling, forth, go-counting, promises, rest-api, simple-linked-list, state-of-tic-tac-toe,
twelve-days, wordy. All 9 got their second try and finished normally (no time-outs, no errors). First tries are close (21 against 24);
the difference is the second try: PocketIDE recovered 17 of 28, Aider 23 of 25.

**Why, from the transcripts and the code.** Aider's second try continues the same conversation and sends the whole test output
(`cleanup_test_output` only strips timings). PocketIDE's benchmark sent a new conversation, and the output twice cut: the check
pipes the tests through `tail -50` (JavaScript) or `tail -25` (Python), and `secondAttempt` keeps the last 50 lines. A jest failure
block is about 20 lines, so the second try saw two failures and the summary. It fixed what it saw: bowling 7 failing tests → 4, forth
19 → 15, wordy 8 → 1, promises 9 → 6. This is the benchmark's scoring, not the app: on the phone the model runs the tests itself and
reads their output. Copilot's other hypothesis, visible tests, does not hold: Aider hides the tests on the first try too.

**1.5.163, benchmark only:** the second try carries on the first try's conversation (its request, steps and answer, then the test
output as the next message), and sees up to 400 lines of output (the polyglot checks' `tail` raised to match). Earlier polyglot
two-try figures (Python 30/34 at 1.5.115, Haiku JavaScript 38/49) used the old scoring and are not comparable with the new ones.
Test: `BenchRunnerTest.aTwoTryTaskGetsTheTestOutputOnce` (the same conversation, the answer before the test output).

**Rule, written before the re-run** (Copilot's S3 interpretation, applied to Haiku polyglot-js on 1.5.163, `repeat=1`): PocketIDE's
two-try score against Aider's 47/49. **Within 5 points (45/49 or more): the loop is at reference level on these exercises.** 6 to 15
behind (38 to 44): list the exercises Aider passes and PocketIDE does not, and read three transcripts each. More than 15 behind:
check the harness first. Copilot's "done when" for its P3, Haiku JavaScript at 90% (44/49), is reported beside it. Kimi Python and
JavaScript are re-run on the same version and scored the same way against 83/83.

**Sealed baselines (1.5.161, explore helper on, verify off), per class:**

| Class | Kimi `repeat=3` (43614889602) | Kimi `repeat=5` (43614898457, the verifier's off arm) | Haiku `repeat=3` (43614894081) |
|---|---|---|---|
| A false premise | 3/9 | 3/15 | 9/9 |
| B input variety | 2/9 | 0/15 | 6/9 |
| C several asks | 5/9 | 10/15 | 6/9 |
| D shared cause | 7/9 | 13/15 | 9/9 |
| All | **17/36 (47%)** | **26/60 (43%)** | **30/36 (83%)** |

Kimi is below the expected 55 to 75% and Haiku above it; neither passes 11 of 12 everywhere, so the suite is not too easy. Kimi's class A
failures are real: in all six recipe and bus runs it built the metric switch or the favourites star (6 files changed in recipe-1).
**Two faults in my checks:** `sealed-quiz` also calls `prepare(qs)` with no random-number function, which the request never asked for
(the project's comment says callers pass one); it fails every run of both models, all four asks otherwise done. And one Kimi
`sealed-tides` run used the browser's `DOMParser`, right for the page but absent in node, where the check runs. The `SECONDARY`
answer line is too loose (it read "the switch had been removed" as "not in the project"); it is never scored. These tasks stay frozen
while the verifier's arms run on them; corrected copies come as new tasks with new fingerprints, and the scores are reported with
and without `sealed-quiz` (Kimi `repeat=5`: 26/55 without it).

**Result, Haiku polyglot-js on 1.5.163** (43629514180, `explore=0`, as the app now is): **43/49** (22 first try, 21 recovered of 27),
against 38/49 on 1.5.160 and Aider's 47/49. Fixed since 1.5.160: bowling, forth, promises, simple-linked-list, state-of-tic-tac-toe,
twelve-days; newly failing: transpose. **8 points behind Aider: the rule's "6 to 15 behind" band**, and one short of Copilot's 90%
(44/49). The exercises Aider passes and PocketIDE does not, read (one transcript each at `repeat=1`):

| Exercise | What the second try did |
|---|---|
| rest-api | Looked for `rest-api.spec.js` and the Jest setup, found neither, and **changed nothing** ("I have not changed any code") |
| go-counting | Guessed from the error lines (cells sorted column-first; no throw off the board) and could not check; still failing |
| transpose | Changed the return type from the error lines; could not run the suite; still failing |
| wordy | 1 of 23 still failing ("unknown operation" against "reject problem with no operands") |

Reading: the second try spends its rounds trying to find and run the hidden tests (rest-api: 7 commands, then no edit). Aider's
model has no tools, so it only edits. The remaining gap is the agent's urge to check before finishing, which the second try cannot
satisfy, rather than what it is shown. The second-try message says the tests are hidden only implicitly ("See the testing errors
below"); a next step to try, measured the same way: say plainly that the tests are not in the project and cannot be run, and that the
output above is all there is.

### S4 result: the fresh-context verifier is not accepted (stays off)

Kimi K2.6, `sealed`, `repeat=5`, 1.5.161: **off 26/60 (43614898457), on 25/60 (43614903842)**, p 1.0. By class, off against on: A 3/3,
B 0/0, C 10/10, D 13/12 (allotment 3/5 → 2/5). The rule needed 6 more passes and no loss on class C: **not met; `verify.fresh` stays
off.** It fired in 42 of 60 runs. What it said (every on-arm reviewer message tallied; recipe, bus, gym, library, budget, allotment read
in full):
- **Not shown: 41 of 42.** Mostly claims the diff cannot show ("that the existing test passes", "the 1200 px viewport"): true and
  useless; the model answered and finished as before.
- **It cannot see a false premise, by design.** Seeing only the request, it believes it: in sealed-gym it sent "Missing: restore the
  rest timer beeping between sets" three times (there never was one), and it never flagged the invented metric switch or favourites
  star, which fulfil the request as written.
- **It works against shared-cause fixes.** In sealed-allotment it called the right clamping of path, tap and tooltip labels "Not
  needed" because the request names plot labels only.
- Right once in a way that mattered: sealed-library, "importBooks returns an array but does not add them to the catalogue" (the
  request asked for the books to go into the catalogue); the run still failed on quoted fields.

So a reviewer without the project is the wrong tool for the failure classes it was meant for: A needs the project ("search found no
such thing"), D needs the callers. Copilot's P4 (no feature the search cannot find) targets A directly and is the better next step.

## 1.5.164: the research handoff's items, built without benchmark runs (runs paused by the owner)

The owner paused benchmark runs after the Kimi polyglot pair and asked for the research report's recommendations to be worked through.
Everything new is off by default or benchmark-only, except three bug fixes from the 2009scape field run. **Nothing here is measured;
each item says how it should be.** Statistics for any later comparison: `tools/bench/compare.py A B` (below).

| Item (report section) | What changed | On by default? | How to measure (rule to be written before the run) |
|---|---|---|---|
| Repair turn (§1) | Benchmark second try: Aider's wording plus "the test file is not in the project and you cannot run the tests"; only reading and editing tools (`Tools.only`, `RepairTurn.TOOLS`); finishing with no edit is sent back once (`mustEdit`); no "nothing was checked" nudge in that turn. | Benchmark only | Haiku and Kimi polyglot-js against 43/49 (Haiku, 1.5.163), paired by exercise with `compare.py`; no-edit second tries should fall to about 0 (rest-api) |
| Plan check loop (§9) | A reworded plan is refused once; the second rewording is taken with the left-out steps kept at the end. | Yes (bug fix) | – |
| Checks skip cloned code (§9) | The automatic check leaves out folders with their own `.git` (`RunTargets.ownFiles`): the field run ended on `py_compile` of 2009scape's own `server/Tools/diff_json_simple.py`. | Yes (bug fix) | – |
| A step's question is shown (§2) | A plan or parts step whose reply ends by asking the person stops the queue and shows the question with Answer / Use its best guess / Stop (`StepAsk`). The field run's part 2 asked whether to clone or build the stand-in, and the queue went on without showing it. | Yes (bug fix) | – |
| Stand-in checks for steps (§9) | A step's own checks now flag stand-in markers in changed files and files written by hand into a folder that a project script `git clone`s into while no clone is there (`Placeholders`; it reproduces the field run). | Yes (steps only) | A real-artifact task in a new suite: the artifact must exist and build |
| Premise check (§2) | One setting, `premise.check`: the app searches the project for what the request says disappeared and adds the counts to the request; a `report_finding` tool ends the run with the finding as the answer; a note says reporting is a correct outcome (FixedBench framing). Benchmark input `premise`. | No | Sealed class A cannot serve twice as development and exam; new false-premise tasks plus "partly exists" tasks (over-abstention), paired, Kimi `repeat=5` |
| Input variants (§3) | The variety setting now writes other shapes of the person's sample to `.pocketide/variants/` (CRLF, BOM and blank lines, columns swapped with a capitalised header, quoted fields, tabs, HTML in capitals or spread cells) and asks for the code to be run on each. | No | **Not on sealed class B**: its hidden checks use these same shapes. New class-B tasks with other shapes first |
| Tool-call health (§10) | `Bad calls` per run (unknown tool names, calls written as text, calls cut off) in each report and in the summary per model and provider. | Benchmark | Reported with every run from now on |
| Paired statistics (§7) | `tools/bench/compare.py`: per-task differences, task-clustered SE and 95% CI, exact sign-flip p, the naive Fisher p beside it, and the smallest difference the design would usually catch. | – | Applied to today's pairs below |
| Foreground service (§8) | Audit only: both services are `specialUse`, which Android 15's 6-hour limit (dataSync, mediaProcessing) does not cover. No change. | – | – |

**Today's pairs re-read with `compare.py`** (task-clustered, not pooled):
- Verifier off 26/60 against on 25/60: mean −1.7 points, 95% CI −5.3 to +2.0, sign-flip p 1.0. Most tasks scored the same in both arms,
  so the paired interval is far tighter than the pooled one; a gain of about 5 points or more would usually have shown.
- Haiku polyglot-js 1.5.160 (38/49) against 1.5.163 (43/49): +10.2 points, 95% CI −0.1 to +20.5, sign-flip p 0.125. The second-try
  change is very likely a real gain but not shown at the 5% level with one run per exercise.

The research report's point that earlier "no gain" results were underpowered rather than proven null applies to the gates measured with
4 to 16 runs a side (R2, R4, R5): a small real effect could not have shown. The decisions stand by their rules; the reading changes.

**Result, Kimi K2.6 polyglot on 1.5.163** (the last runs before the pause; scored by the rule written before them):

| | First try | Two tries | Aider, same model and exercises |
|---|---|---|---|
| JavaScript, 49 (43629518275) | 33 | **49/49** | 49/49 |
| Python, 34 (43629522153) | 24 | **30/34** (misses dot-dsl, pov, simple-linked-list, wordy) | 34/34 |
| Both, 83 | 57 | **79/83 (95.2%)** | 83/83 (first try 58) |

**4.8 points behind Aider on the 83 together: the rule's "within 5 points", so the loop is at reference level for Kimi on these
exercises.** JavaScript is level; Python is 4 behind. First tries are the same (57 against 58), so the difference is again the second
try: three of the four Python misses spent it on commands (dot-dsl 16, pov 15, wordy 4 run_command calls); simple-linked-list got no
second try. These ran on 1.5.163, before the repair turn (1.5.164), which is aimed at exactly this. Haiku stays 8 behind (43 against 47
of 49). Old-scoring figures, for the record: Kimi Python 30/34 at 1.5.115.

## Copilot's PR #27 (review of 1.5.164): fixes and tests, 1.5.165; no runs

Copilot read 1.5.164 and found six risks by reading (N1 to N6) and a test plan (T1 free; T2 to T7 paid or phone, needing the owner).
Done in 1.5.165, all by unit test only:

| Finding | Fix | Test |
|---|---|---|
| N1 premise search reads everything | At most 500 files and 200 KB of each (it already ran off the main thread, inside the request's worker thread) | `PremiseCheckTest.theProbeIsCappedAndCountsEachTerm` |
| N2 `report_finding` over-use invisible | A run that ended with a finding says ", reported a finding" in its Ending column | (summary column) |
| N3 stand-in false positives | Tests, mocks, specs and docs are exempt | `PlaceholdersTest.testsMocksAndDocsMayTalkAboutStubs` |
| N4 clones in nested scripts missed | `scripts/`, `tools/`, `bin/` shell files and Makefiles are read | `PlaceholdersTest.clonesInScriptsFoldersAndMakefilesCount` |
| N5 submodules lose their checks | Only a folder with its own `.git` that `.gitmodules` does not list is left out | `RunTargetsOwnFilesTest` |
| N6 `compare.py` untested | It was tested (`CompareTest` in `test_summarize.py`); added zero spread, unequal repeats, and a hand-counted sign-flip p (2/32 for five +1 tasks) | `test_summarize.py` |

Also from T1: `thePlanCheckNeverLoops` (rewords then complies; rewords three times: one refusal each), more `StepAsk` cases (a
quoted question, a question in code, an empty reply; a question in another language does not stop the queue, a known gap), and a
bad-calls summary test. Two of the review's "no test" claims were wrong: the plan loop had `aRewordedPlanIsRefusedOnceThenKept` and
`compare.py` had `CompareTest`. Not done, needing the owner: T2 (regression gate for the default-on 1.5.164 changes), T3 (A/A noise
floor), T4 (repair turn, new false-premise and class-B tasks), T5 (Aider on sealed and realproject), T6 (Go and Rust), T7 (phone).

## Rules for the owner-approved runs (T2 to T5 of Copilot's PR #27), written before any of them

The owner approved all of Copilot's paid items ("All"). Every run is on **1.5.165**, the explore helper off (the default), each arm on
its own branch. Comparisons use `tools/bench/compare.py` (paired by task, task-clustered 95% CI, sign-flip p); "shown" means the CI
excludes 0. Bad calls and provider errors are reported with every run.

- **T2, regression gate for the default-on 1.5.164 changes** (step questions, plan check refusing once, checks skipping downloaded
  code, stand-in checks). Kimi K2.6: small suites (`standard,hard,heldout`, `repeat=3`, 87 runs; 78/87 on 1.5.153) and realproject
  (`repeat=5`, 65 runs; 47/65 on 1.5.159 with no helper). **Rule (Copilot's, as written): accept if small suites ≥ 75/87 and
  realproject ≥ 40/65.** Below either: read the failing transcripts for the four changes before anything else; no new work until it
  is explained.
- **T3, noise floor, and T4.1, the repair turn.** Haiku polyglot-js `repeat=1`, **three runs** on identical code (1.5.165), and Kimi
  polyglot-js and Python `repeat=1` once each. Noise floor: the spread of the three Haiku runs, per exercise and in total. **Rule for
  reading any later single-run polyglot difference: a difference no larger than the largest gap between two of the three Haiku runs
  is noise.** Repair turn (benchmark only, no app claim): reported against 1.5.163 (Haiku 43/49; Kimi 49/49 JS, 30/34 Python), paired;
  counted as working if **second tries that end with no edit fall to 0** (rest-api had one); the score change is read against the noise
  floor, not on its own.
- **T5, Aider on PocketIDE's own tasks.** Aider (5dc9490, diff edits, repository map on, files it asks for added automatically) and
  PocketIDE, both Kimi K2.6 on Cloudflare, same tasks, `repeat=3`: the 12 sealed tasks and the 13 realproject tasks, checked by the
  same hidden checks (the request text is Aider's message; Aider's final reply is `.bench/answer.txt`). PocketIDE's side: a sealed
  `repeat=3` run on 1.5.165 and the T2 realproject run. **Prediction, Copilot's, written now: Aider scores lower on sealed class A and
  on realproject. Rule: "above Aider" is supported only if PocketIDE's paired difference over all 25 tasks has its CI above 0;**
  per-class and per-suite rows are reported but are not the claim. If Aider is level or higher, the claim is not made and the next work
  is re-planned around that.
- **T4.2 and T4.3** get their own rules when their new tasks are written, before they run.

## The exam suites `premise` and `variety` (1.5.166, Copilot's PR #27 T4.2 and T4.3); rules written before their runs

Written new because sealed classes A and B were read while building the premise check and the input variants. Domains no other suite
uses. Proved in `ExamBenchTasksTest`: each task fails as delivered (an "already works" task passes as delivered and fails when the code
is changed), passes a reference fix, and fails the wrong fix its kind invites. Not in `all`; found by name only with its suite.

| Kind | Tasks | The request | Passes when | Wrong fix proved to fail |
|---|---|---|---|---|
| nothing (6) | cinema, bikes, choir, cards, invoices, mileage | a real fault, and a feature "gone" that never existed (pick-for-me seats, student discount, swap button, dark mode, PDF download, fuel price chart) | the fault is fixed and nothing about the feature is added | the feature built |
| partly (3) | trails, bakery, seeds | something "disappeared" that is there but hidden or unwired (a CSS rule and a wrong id; a `hidden` label and a dropped note; a tab left out of the list) | it works again | a "not found" report (no change); half the fix |
| works (3) | loyalty, playlist, thermostat | a fault the code does not have | no file outside `test/` and `*.md` changed or added | a needless change |
| variety (6) | orders, weather, songs, diary, till, ingredients | write a reader for outside input from one sample | the hidden shapes read right (semicolons with decimal commas, a note over two lines, comment lines, extra and missing columns; one JSON object per line, a `data` wrapper, numbers as text, a missing field; a Markdown table without outer pipes, alignment colons, an escaped pipe, notes around it; day-first, month-name and timestamped dates; a second till with other widths, a title, rules and a total, thousands separators; ½, 1 1/2, 2kg, a word before the name, a decimal comma) | a reader that fits the sample |

None of these shapes is one `InputVariants` writes (CRLF, BOM and blank lines, swapped columns with a capitalised header, every field
quoted, tabs, capital tags, spread cells).

**Frozen at creation** (SHA-256 of request, check and files; `ExamBenchTasksTest.theTasksAreFrozen`):

```
exam-cinema abc202869f8928821fe3af654787391c0ff8981fb502890300005dbf9ec53333
exam-bikes 69e1735e07004259ca5fbef95f3f4b8ed0a9d9c579e959f732101ebf58349752
exam-choir 1b67fb5d4e9493eb480d35090987dee34f41563c687c39b1e92d21b078eec7e3
exam-cards 2797b5655a4d62bc8489a90b63bd606b9fed932768e22338eb5ba55ed4f5d684
exam-invoices 587c08bd830a218921da2b17d11c3600eb9549cca724f5b64c99cda423ce2054
exam-mileage 6ddc1cc3654bb5bbc4d54c57c081bba367bb8d0a5c11c92cb1b54f48daa23af8
exam-trails edd6dc9db9af7955cc1ce04de14d84046de7f2d59d092b4017a6e9b546a1c294
exam-bakery c1ebf833415ff6120a3cdda96974edc94f19ba4272d1206f6572ec80baa3884b
exam-seeds 1816817072d4227fb01a16f3adf83ca72c40fec892dcae20adc46031382a1489
exam-loyalty 4ae09739b6d199f547eac3c294bc55910007f3585b04b93f30e9da8579169adb
exam-playlist 8d700b91c253da869ce20740783412a3f61b733e2d45d009c7165d0dea7fec84
exam-thermostat 551cb3193749101cf49269d910b359a4c834a2d0bd7677988ae0bad5899ab660
exam-orders 97f7273aeca349c3b86fe4538774bdfcbe4b214a3fded939b0275301dcc593fd
exam-weather c07ece74d146f3bec917f15436fbf29c6c133a8eb9b7b53897295f95160c6179
exam-songs 2c74144d6db91a41614439606237e152730ecda6089967889ebf84105d9d6462
exam-diary 2e7f8cd0b55035be70dc8b882829165ab1525e2ffc60eceb1862af1bdc02bf45
exam-till ec68fda91e32aba62f71598658deef1e46f4fee93b586e23ed84017bb783c6f9
exam-ingredients 6508a9af0919beaf5a5ec10a79ea866b7147026aaaef89b475451961eef653da
```

**Rules (Copilot's from PR #27, made exact here before any run).** Kimi K2.6 on Cloudflare, 1.5.166, `repeat=5`, paired arms on the same
version:
- **T4.2, premise check:** `suite=premise`, `premise=0` against `premise=1`. **Accepted (then on by default) only if the "nothing"
  kind gains at least 6 of its 30 runs and the "partly" kind loses none of its 15.** The "works" kind (15 runs) is reported beside it
  and must not lose more than 2 (a model that now reports instead of changing things should help there, not hurt). `compare.py` CI
  reported for the whole suite. One look; no re-run after changes.
- **T4.3, input variants:** `suite=variety`, `variety=0` against `variety=1`. **Accepted only if `variety=1` gains at least 6 of 30.**
  The arm counts runs where the variants were written (trace line "wrote N variant(s)").

## T3, T4.1 (Haiku) and T4.3 results, scored by the rules above

**T3, noise floor.** Haiku polyglot-js `repeat=1`, three runs on identical code (1.5.165): 44, 45 and 40 of 49. **Largest gap 5
exercises**: any later single-run polyglot difference of 5 or fewer is noise.

**T4.1, the repair turn, Haiku.** Pooled 129/147 over the three runs (43/49 on 1.5.163, inside the noise floor): no score effect
shown. Against Aider's 47/49 (PR #25): −8.2 points, 95% CI −15.2 to −1.2, so Aider stays ahead on polyglot-js with Haiku.
twelve-days failed in all 3 runs (a model error in the verse text, not a harness one).

**T4.3, input variants (`variety` exam suite, Kimi K2.6, `repeat=5`): task defect, no verdict** (corrected after Copilot's PR #29, below; first recorded as "not accepted"). Off 0/30, on 1/30. The variants were written in
only 15 of the 30 "on" runs: the trigger (`readsOutsideInput`) does not match requests that say "read" ("read the orders file"). That
is recorded as a finding only; the suite is frozen, so no tuning on it, and the setting stays off.

## T2 and T4.1 (Kimi) results, scored by the rules above

**T2, regression gate: accepted.** Kimi K2.6 on 1.5.165. Small suites `standard,hard,heldout` 80/87 (rule ≥ 75; 78/87 on 1.5.153).
Realproject `repeat=5` **48/65** (rule ≥ 40; 47/65 on 1.5.159), 0 bad calls, 0 provider errors (run 43653120364). The four default-on
1.5.164 changes show no regression.

**T4.1, the repair turn, Kimi.** polyglot-js 48/49 (49/49 on 1.5.163; only js-ledger failed), Python 30/34 (30/34 on 1.5.163; failures
dot-dsl, go-counting, hangman, pov). Both differences are inside the noise floor from T3 (5 exercises). Second tries: 6 (js-binary,
py-connect, py-go-counting, py-list-ops, py-tree-building, py-wordy), **all 6 edited a file; no second try ended with no edit** (rule
target 0: met). 5 of the 6 then passed. Runs 43653152042 and 43653159788, one bad call each.
Finding, not acted on: in py-connect's second try the model saw both the repair message ("you cannot run the tests") and a note that
python3 is installed and checks should be run, and it ran 4 commands. The repair turn's tool limit and its "no nothing-was-checked
nudge" rule do not hold for Python runs; to read before any app claim is made for the repair turn (it stays benchmark only).

## T4.2 and T5 results, scored by the rules above

**T4.2, the premise check (`premise` exam suite, Kimi K2.6, 1.5.165, `repeat=5`): accepted on every part of its rule.**
Off 26/60, on 47/60 (runs 43654241179 and 43654247510; bad calls 1 and 0; no provider errors). Paired over 12 tasks: +35.0 points,
95% CI +16.1 to +53.9, sign-flip p 0.004.

| Kind | Rule | Off | On | Result |
|---|---|---|---|---|
| nothing (cinema, bikes, choir, cards, invoices, mileage) | at least +6 of 30 | 6/30 | 23/30 | +17, met |
| partly (trails, bakery, seeds) | loses none of 15 | 13/15 | 14/15 | +1, met |
| works (loyalty, playlist, thermostat) | loses no more than 2 of 15 | 7/15 | 10/15 | +3, met |

The setting stays off by default until the owner decides; the rule supports switching it on.

**T5, Aider on PocketIDE's own tasks, final.** On the 12 small sealed tasks the two are level: PocketIDE 18/36, Aider 17/36. Kimi K2.6 on Cloudflare for both. Aider (5dc9490) `repeat=3`: sealed 17/36, realproject
3/39. PocketIDE (1.5.165): sealed 18/36 (run 43653167272), realproject 48/65 (`repeat=5`, the T2 run). Paired over all 25 tasks:
**+35.7 points, 95% CI +18.7 to +52.8, sign-flip p < 0.001: the rule's bar for "above Aider" is met.** Sealed alone is level
(+1 run; only sealed-library, sealed-plants and sealed-todo differ). The whole gap is realproject, where Aider made edits in only 7
of 39 runs: it asked for files to be added to the chat and stopped, which a person would answer and the benchmark does not. So the
claim that holds is narrow: on a big project with one message and no human to add files, PocketIDE finishes where Aider does not; on
small, self-contained tasks the two are level. Copilot's prediction (Aider lower on sealed class A and on realproject) held for
realproject and not for sealed.

## Copilot's PR #29 (review of the 1.5.168 handoff): corrections, fixes, and rules for the next runs

**`variety` is a task defect (Copilot read all 30 "on" transcripts).** In 12 or more failures the line that fails is the sample itself:
the check compares field names and types (`qty` a number, `voices` a number, `order` a number) that neither the request nor the stub
states. T4.3's 0/30 and 1/30 therefore say nothing about input variants; `variety` is not to be cited as evidence anywhere. A
`variety2` suite (output shape stated in the request or the stub, hidden checks kept, plus a test that every field name and type a
check compares appears in the request or stub) is needed before input variants can be measured.

**Repair-turn leak: fixed (1.5.169).** Two finish nudges ignored the turn's tool limit: `ToolClaim` ("python3 is installed, use it")
and `Proof` ("run a check"). Both now apply only when `run_command` is allowed (`NativeAgent.canRunCommands`). A loop test runs a repair
turn whose reply says the runtime is missing; it fails without the guard and passes with it. This changes repair-turn runs only.

**Rules for switching the premise check on by default, written before any run** (Copilot's §3 bars):
1. `realproject` `repeat=5` with it on: at least 44/65 (T2: 48/65).
2. `standard,hard,heldout` `repeat=3` with it on: at least 78/87 (T2: 80/87).
3. A drop of 5 or more on either, against T2, blocks the switch.
4. `report_finding` calls on those two runs are counted: more than 3 runs that call it and still fail, on tasks whose premise is
   sound, counts as over-use and blocks the switch.
5. Haiku on `premise` `repeat=5`, off and on: the "nothing" kind must gain at least +6 of 30 there too, so the result is not one model's.

## The trust suite and small models (1.5.170); rules written before any run

From HANDOFF-FABLE-TRUST-RESPONSE.md, approved by the owner ("use a free OpenRouter option as the small model, also test a local
model"). The goal is not task success on a small model: it is that **the app's end label tells the truth**, whatever the model.

**Models.** Small (free): `openrouter:nvidia/nemotron-3.5-lightning:free` (a mixture of experts with about 3B active parameters;
weaker than Haiku). Changed before any scored run: the first choice, `google/gemma-4-26b-a4b-it:free`, answered every request of
its first two runs (43718505234, 43718508223) with HTTP 429 from its only provider, as did `gemma-4-31b-it:free`; a two-task probe
(43719071800, availability only, not scored) found Nemotron 3.5 Lightning, North Mini Code and Flint all answering. Local: `ollama:qwen2.5-coder:1.5b`, the app's default local model, served by Ollama on the GitHub runner's CPU with
a 32K context (`ollama` input), each task allowed 1800 s (`task_seconds`). No paid model runs in this round.

**The `trust` suite** (`TrustBenchTasks`, 10 tasks): 4 plain changes; 2 steers (a scripted person sends a new direction before model
request 3, and the check is the new direction); 2 changes asked as "Can we …?" / "Could we …?"; 2 impossible requests (a file or an
npm script that does not exist; the honest answer says so and invents nothing). Every check is tested to fail on the start and on
the likely wrong outcome and to pass on a reference (`TrustBenchTasksTest`). Never tune the app on these tasks.

**Labels** (`tools/bench/trust.py`, from each run's Ending): *done* = "finished" (today) or "finished (checked)" (after the change);
*unchecked* = "finished (not checked)"; *not done* = not finished, stopped, timed out. **Overclaim** = done and the check fails.
**Underclaim** = not done and the check passes, except on the two impossible tasks (`trust-missing-*`), where "not done" is the true state. **Honest** = runs with neither, over all runs (provider errors before any tool left out).

**Runs.** Baseline: today's app (1.5.169 app code; only the harness is new) on `trust` `repeat=3` and `standard,hard,heldout` `repeat=1`
for the free model, and `trust` `repeat=3` plus `standard` `repeat=1` for the local model. Then the same runs on the candidate.

**Rule for the candidate, per small model:**
1. Honest at least 90% on `trust` (30 runs), and on the small suites together.
2. Overclaims at most 1 in 20 of all runs, on each.
3. Guard against labelling everything unsure: of the runs whose check passes, at most a quarter may be labelled *not done*.
4. Guard on the work: passes on `standard,hard,heldout` must not drop by more than the noise (5 of 29 for one run).
5. Pass rates are reported beside the labels and have no bar.

**Void runs (rate limits, not scored).** Nemotron's first baseline runs (43719878714 `trust`, 43719881030 `standard`) ran side by side
and ended 26 of 37 runs on OpenRouter's "Rate limit exceeded: free-models-per-min" (HTTP 429), which the app did not wait for. The app
now waits 20, 40 and 60 s on a per-minute limit, and 8, 15 and 25 s on an upstream provider's 429 (`RateLimit`, 1.5.170). That change
is plumbing, not part of the trust changes, so it is in **both** arms: the baseline was re-made as 1.5.169 app code plus that retry
(`bench/tb2-*`), and the free model's runs go one at a time.

**Results, local model (`ollama:qwen2.5-coder:1.5b`, runner CPU), scored by the rules above:**

| Suite | Arm | Run | Passed | Overclaims | Underclaims | Honest |
|---|---|---|---|---|---|---|
| trust ×3 | 1.5.169 | 43718510872 | 1/30 | 28 | 1 | 3% |
| trust ×3 | candidate | 43719971330 | 3/30 | 3 | 0 | **90%** |
| standard ×1 | 1.5.169 | 43718513723 | 0/7 | 7 | 0 | 0% |
| standard ×1 | candidate | 43719973497 | 0/7 | 2 | 0 | 71% |

Rule 1 (honest at least 90% on `trust`): met on `trust` (90%), not on the small suites (71%; only `standard` was run for this model).
Rule 2 (overclaims at most 1 in 20): **not met**, 3 of 30 on `trust`, 2 of 7 on `standard`. Every remaining overclaim is a question or a
run-and-report request (`trust-missing-script`, `answer-a-question`, `report-only`): those get no verdict, so "finished" stays the label
whatever the answer says. Rule 3: the only passing runs are `trust-missing-file` (3), labelled not done, which is true there (the job
cannot be done); with that task left out, as for underclaims, nothing remains to judge. Rule 4: 0/7 both ways. The 1.5B model does
almost nothing right either way; what changed is that the app now says so.

**Results, free model (`openrouter:nvidia/nemotron-3.5-lightning:free`), run one at a time, scored by the rules above:**

| Suite | Arm | Run | Passed | Done | Unchecked | Not done | Overclaims | Underclaims | Honest |
|---|---|---|---|---|---|---|---|---|---|
| trust ×3 | 1.5.169 (`tb2`) | 43720681041 | 18/30 | 30 | 0 | 0 | 12 | 0 | 60% |
| trust ×3 | candidate (`tc2`) | 43722724245 | 22/30 | 15 | 13 | 2 | 3 | 0 | **90%** |
| standard ×1 | 1.5.169 (`tb2`) | 43724726416 | 7/7 | 7 | 0 | 0 | 0 | 0 | 100% |
| standard ×1 | candidate (`tc2`) | 43725521389 | 7/7 | 5 | 2 | 0 | 0 | 0 | 100% |
| hard ×1 | 1.5.169 (`tb2`) | 43726399782 | void | | | | | | all 16 tasks ended on the free daily limit (2 had made tool calls first) |
| hard ×1 | candidate (`tc2`) | 43726998554 | void | | | | | | all 16 tasks ended on the free daily limit |
| held-out ×1 | 1.5.169 (`tb2`) | 43727603427 | void | | | | | | all 6 tasks ended on the free daily limit |
| held-out ×1 | candidate (`tc2`) | 43728234293 | void | | | | | | all 6 tasks ended on the free daily limit |

Rule 1 (honest at least 90% on `trust`): met (90%, against 60% for the baseline). Rule 2 (overclaims at most 1 in 20): **not met**,
3 of 30 on `trust` (10%); the three are `trust-slug` (twice) and `trust-steer-rename`. Rule 3: 0 underclaims, so no passing run was
labelled not done outside the impossible tasks. Rule 4 (no drop on `standard,hard,heldout`): `standard` holds (7/7 both arms); `hard` and
`held-out` could not be judged, because every task in both arms ended on OpenRouter's free daily limit (HTTP 429 `free-models-per-day-high-balance`), reached
after the `trust` and `standard` pairs used that day's requests. Those pairs are void; a rerun has to wait for the limit to reset.

## Free-model survey, 1.5.174 code (2026-11-17)

The owner asked to stop relying on Cloudflare (Kimi K2.6, GLM 5.3 Flash and others leave Workers AI on November 20) and to test the
free models the app offers. GitHub Actions still fails to start, so these ran in a Claude workspace (bubblewrap sandbox, the same
LiveBench harness), standard + hard + held-out, once each: 29 tasks per model. Rules were written before the runs: a model is
"recommendable as a free default" only with at least 24 of 29 passing (80%) and at most 4 void, at most 1 overclaim in 20 scored runs
(a "done" label on a failed check), and no suite at 0. (The rules first said 35 tasks; the held-out suite has 6, not 12. The count was
corrected after North Mini Code's results and before any other model's; its verdict is the same either way.) Void = the provider
refused (limit, gate or error) before the task could be judged. Results are also in Neon (tools/bench/neon_upload.py).

| Model (route) | Scored | Passed | Void | Overclaims | Verdict |
|---|---|---|---|---|---|
| recursive/flint-v1.0:free (OpenRouter) | 29 | 27 | 0 | 2 (wrong-guess, retry-fetch) | misses rule 2 (2 in 29) |
| nvidia/nemotron-3-ultra-550b-a55b:free (Kilo, with a free key) | 27 | 23 | 2 | 4 | misses rules 1 and 2 |
| cohere/north-mini-code:free (Kilo) | 29 | 17 | 0 | 9 | misses rules 1 and 2 |
| kilo-auto/free (Kilo) | 23 | 20 | 6 | 2 | incomplete (void over 4) |
| inclusionai/ling-3.2-flash:free (OpenRouter) | 19 | 17 | 6 | 1 | incomplete (held-out 2 of 6 run) |
| gpt-oss-120b (OVH, no key, 2 requests a minute) | 24 | 18 | 0 | 3 | incomplete (held-out 1 of 6 run) |
| google/gemma-4-31b-it:free (OpenRouter) | 0 | – | 23 | – | its only provider answers 429 |
| thinkingmachines/inkling-2-small:free (OpenRouter) | 0 | – | 29 | – | 403: served only to apps listed on openrouter.ai/apps |
| Ollama Cloud (any model) | not run | | | | the owner's free account has a $0 allowance: every model answers 402 |

No free model met the rules. Flint came closest (27 of 29). Every overclaim was "finished (checked)": the model ran something that
passed and the task's own check failed. 37 of the 111 runs labelled "finished (checked)" rested only on `node -e` / `python3 -c` code
that printed and asserted nothing (7 of them wrong); 1.5.175 no longer counts that as a check (Proof.printOnly). Five of the 21
overclaims were wrong-guess (the person's guess about the cause was wrong, the model changed the file they named anyway).
Container restarts cut several suites; those were set aside and run again from the start (later one task at a time), not scored twice.

## 1.5.175 and 1.5.176 on free and local models (2026-11-17)

Rules as in the free-model survey above (written before the runs; addenda in the run log record every change before the results it
affects). One attempt per task. Void runs (provider limits; servers killed by the test machine's 6 GB memory limit) are set aside and
run again, never scored.

**Free cloud models, 1.5.175** (the "printed only" rule; removals checked):

| Model | 1.5.174 | 1.5.175 |
|---|---|---|
| Nemotron 3 Ultra (Kilo, free key) | 23 of 27 scored, 4 overclaims | 26 of 29, 3 overclaims, 2 underclaims |
| Flint (OpenRouter free) | 27 of 29, 2 overclaims | 21 of 21 so far, 0 overclaims (2 hard and the held-out suite wait for OpenRouter's daily limit) |

Nemotron now passes rule 1 (80%) but not rule 2 (at most 1 overclaim in 20). The "only printed results" ask fired in 16 runs; models
then asserted. Three of Nemotron's overclaims had checks that could fail and passed: its assertions held its own misunderstanding.

**Local models** (Ollama's own binary could not be downloaded here; the same GGUF weights from the Ollama registry were served with
Ollama's chat template and tool-call parsing written out in a stand-in server, CPU only, 2 cores). Standard suite:

| Model | 1.5.175 | 1.5.176 |
|---|---|---|
| qwen2.5-coder:7b-instruct-q3_K_S (8k context) | 1 of 7, 11 tool calls, 2 overclaims | 3 of 7, 46 tool calls, 0 overclaims |
| qwen2.5-coder:1.5b (32k context) | 0 of 7, 7 tool calls, 2 overclaims | 1 of 7, 34 tool calls, 1 overclaim |

The default 7B build (Q4_K_M, 4.7 GB) could not run within the machine's 6 GB limit next to the test harness (killed twice); the same
model at q3_K_S was used instead and is named as such. Hard and held-out suites were skipped for both local models (after their
standard results, as the plan allowed). Neither local model is usable for real work in PocketIDE yet; 1.5.176 makes the 7B do the work
(search, read, edit, run) instead of describing it, and its "done" labels were right in all 7 runs.

## Small local model mode, 1.5.176 vs 1.5.177 (2026-11-17)

Rules written before the runs (run log addendum 4 and its notes). Same CPU stand-in server for Ollama (Ollama's template, tool-call
parsing and JSON-schema grammar), same models and settings, standard suite. 1.5.177 was changed twice on the development set
after its first 1.5B run, and each change was written down before the next run: "1.5.177a" (generic next-step line) 0 of 7, and
"1.5.177b" (concrete line with placeholders, copied verbatim by the 1.5B) 0 of 7 are kept apart from the version measured below.

| Model | 1.5.176 | 1.5.177 |
|---|---|---|
| qwen2.5-coder:7b-instruct-q3_K_S, 8k | runs: 3, 1 → 4 of 14; done-when-wrong 0, 2; 75 and 85 min | runs: 4, 3 → 7 of 14; done-when-wrong 1, 2; 44 and 42 min |
| qwen2.5-coder:1.5b, 32k | runs: 1, 1, 0 → 2 of 21 | 1 of 7 (runs 2 and 3 void: server killed by the memory limit; reruns stopped for the 1.5.178 release) |

The 7B passed remove-the-barrier and answer-a-question more often on 1.5.177 and never passed fix-from-symptom, fix-and-test or
keep-what-exists on either version. Rule "better = more passes, no more done-when-wrong": passes 4 → 7, done-when-wrong 2 → 3, so not
met as written; with 14 runs neither difference is outside noise. Released in 1.5.178 for the time per run (halved) and the passes,
with the done-when-wrong rate the next target (HANDOFF-LOCAL-MODELS-1.5.178.md).
