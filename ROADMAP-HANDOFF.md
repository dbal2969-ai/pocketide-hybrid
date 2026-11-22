# PocketIDE roadmap handoff (written at 1.5.123, 2026-11-10; status updated at 1.5.126, 2026-11-11)

> **Status at 1.5.134 (2026-11-12): this file is a history, not the current plan.** Since 1.5.126: the edition merge (1.5.128,
> identical `app/src`, the public Linux add-on), the /cloud workflow is live on Test-Coder2.3 (1.5.131), and two independent
> reviews by GitHub Copilot (`ANALYSIS-1.5.131.md`, `ANALYSIS-1.5.133.md`, PRs #9 and #10) drove 1.5.132-1.5.134. The current
> picture is the top of `README.md` and the newest `ANALYSIS-*.md`. Both editions now have the same test count; check it in the
> README's newest section, not in the numbers below.

For an AI coding agent picking this up cold: Haiku, Opus, GitHub Copilot, or PocketIDE itself.
Read the status below and sections 1 and 6 before touching anything. Sections 2–7 are the plan as written at 1.5.123; the status
table says what has happened since, and `REVIEW-1.5.125.md` is an independent review of that work. File and line references in
sections 2–7 are to the **GPL edition at 1.5.123**
(`app/src/main/java/dev/pocketide/app/…`, shortened below to the file name). Lines drift: search for the quoted name if a line is off.

## The goal

1. **Parity with Claude and GitHub Copilot as a mobile AI coding app.**
2. **Chat like Claude or Copilot**: free conversation and Q&A, history that survives closing the app, a clear switch between
   "just talk" and "build", and no file changes unless the owner asks or approves.
3. **Milestone: PocketIDE as its own executor.** It takes a task from this roadmap, changes its own code in a branch, runs its own
   tests and benchmarks, and reports the result. **A human approves every merge and release.** PocketIDE never releases its own changes.

## Status at 1.5.126 (2026-11-11)

Three words, kept apart on purpose (review finding 8): **built** (code and unit tests in the tree), **measured** (the phase's own
pass rule met in a live benchmark), **phone** (its `PHONE-TEST.md` boxes ticked). Nothing from 1.5.117 on is checked on a phone yet.

| Phase | Built | Measured | Where, and what is left |
|---|---|---|---|
| 0 Real-phone check | n/a | n/a | **Owner.** `PHONE-TEST.md`, boxes for 1.5.117–1.5.126. Most screens can now be checked first on an Android emulator: `.github/workflows/emulator.yml` (steps in `.github/emulator/driver.py`). First run: `EMULATOR-TEST.md` (1.5.126: no crash; 17 problems found, fixed in 1.5.127) |
| 1a Replay of the field-run loop | yes (1.5.124) | yes: the replay is the pass rule | `AgentLoopTest.readingAnUnchangedFileInCirclesIsStopped` |
| 1b Re-reads counted by file | yes (1.5.124) | partly: no drop in two one-run A/Bs; the stop never fired live | `core/AgentGuard.kt` `StuckDetector.reread`, `Rereads`. Phase 1's full A/B (two models, `hard` and `realproject`, three repeats) not run |
| 1c End a stall with the question | yes (1.5.125) | no: never seen live | `core/StopNote.kt` `wrapAsk` |
| 1d Same play-test failure 3 times | no | no | **Left for PocketIDE itself** (section 7, task 1); needs `/cloud` on Test-Coder2.3 |
| 1e Steer wording | yes (1.5.125) | no | `core/Steer.kt` `message` |
| 2 Pull request check and guard | yes (1.5.124; guard fixed 1.5.125, renames 1.5.126) | yes: guard check, below | `pr-check.yml`, `pr-guard.yml`. Guard check passed (below). Protection: **not in effect** on either repo when checked (2026-11-11) |
| 3 JavaScript polyglot | yes (1.5.125): `polyglot-js` (49), `polyglot-all` (83) | no: Kimi baseline not run | `bench/PolyglotTasks.kt` |
| 4 Escalate on a stall | yes (1.5.125; once-per-request fix 1.5.126) | no: the one trial never fired | `core/Escalate.kt`, `ChatPane.offerEscalation`. The automatic setting is labelled "not yet measured" |
| 5 Chat with no project | yes (1.5.125) | partly: suite `chat` 12/12 on Kimi; Haiku not run | `core/ChatHome.kt`, `ChatSearchScreen.kt`, `bench/ChatBenchTasks.kt` |
| 6 `/bench` from the phone | yes (1.5.125) | no: fake-server tests only, never run against GitHub | `core/BenchRun.kt` |
| 7 Error reports from published apps | yes (1.5.125) | no: fake-server tests only, never run against Cloudflare | `core/CfErrors.kt`, `SiteErrorsScreen.kt` |
| 8 Next-edit beyond renames | yes (1.5.125) | no: 8 recorded pairs offline; match rate and latency not measured | `core/NextEdit.kt` |
| 9 Pause-and-step debugger | yes (1.5.125) | no: node tests only, never in a WebView on a device | `core/StepDebug.kt`, `DEBUGGER.md` |

**Editions (1.5.128):** `app/src` is identical in GPL and MIT. MIT gets PRoot from the separate GPL "Linux add-on"
(`linux-addon/`, artifact `PocketIDE-Linux-addon` of the GPL APK build), used only when signed with PocketIDE's key. Running Linux
through the add-on is confirmed only up to "PRoot starts from another app" (emulator); a real phone must confirm the rest.

**Owner-only steps, in order:** (1) the phone checks; (2) branch protection on Test-Coder2.2's `main`, as on Test-Coder2.3
(`build` and `guard` can be added as required checks after the first pull request there runs them); (3) `/cloud` setup on
Test-Coder2.3: the app's `/cloud` setup, the `POCKETIDE_API_KEY` secret and the `POCKETIDE_MODEL` variable, then copy the two files
it commits (`.github/workflows/pocketide-agent.yml` and the agent script) into the GPL working copy before the next `release.sh`
(it refuses to run when `main` has commits the machine lacks, and would otherwise delete them); (4) Actions write permission for
the phone's GitHub token, for `/bench`; (5) delete the old branches on GitHub (`candidate*`, `guard-test-*` and the experiment
branches): the agent's proxy cannot delete branches.

**Decided: `release.sh` and branch protection.** Protection applies to everyone except the owner's account ("Do not allow
bypassing" is off), and `release.sh` keeps pushing to `main` as the owner. The agents protection is for (`/cloud`'s token,
Copilot) cannot bypass it. The cost: any session holding the owner's token can also push, so "the owner approves every release"
stays a rule, not a lock.

**Known gap: PocketIDE's own pull requests start no checks** (review finding 1). The `/cloud` agent opens its pull request with
the workflow's own token, and GitHub starts no workflow for events made with that token, so `build` and `guard` never run and a
protected `main` shows them as "Expected". Until the agent opens pull requests with a separate token kept as a secret (a change to
protected files, so an external executor's), the owner **closes and reopens** each such pull request once to start both checks.

**Guard check** (phase 2's exit test), run on Test-Coder2.3 after the 1.5.126 release, draft pull requests #4–#8, all closed
unmerged. Result: **passed, every case as expected.**

| Pull request | Change | `build` | `guard` |
|---|---|---|---|
| #4 `guard-test-clean` | one line of documentation | green | green |
| #5 `guard-test-failing` | a test that fails on purpose | **red** (833 tests, 1 failed) | green (833 ≥ 832) |
| #6 `guard-test-fewer` | `SteerTest.kt` removed | (cancelled) | **red**: 827 < 832 tests |
| #7 `pocketide/guard-test-edit` | a comment added to `core/AgentGuard.kt` | (cancelled) | **red**: protected file |
| #8 `pocketide/guard-test-rename` | `core/AgentGuard.kt` renamed | (cancelled) | **red**: both names listed (the 1.5.126 fix) |

The `build` runs of #6–#8 were cancelled once the guard had answered, to save Actions minutes.
**Branch protection was not in effect when these ran:** GitHub's API reported `main` on Test-Coder2.3 as not protected, with no
ruleset, so the checks reported but nothing yet blocks a merge. The owner should check the rule saved (Settings → Branches).

**Milestone done when:** PocketIDE takes section 7's task 1 through `/cloud`; its pull request's `build` and `guard` run (reopened
by the owner, above); `/bench` posts an A/B comment; the owner merges; the run is written up in `BENCHMARKS.md`.

**Spending:** the owner funded Haimaker with $5. No run uses a paid key without the owner's yes for that run; each paid run's
cost is recorded beside its result.

## 1. Project facts and rules

- Two editions, kept in sync: **GPL** (repo `dbal2969-ai/Test-Coder2.3`, version `1.5.123-gpl`) and **MIT** (repo `Test-Coder2.2`,
  `1.5.123-mit`). MIT has no Linux runtime, terminal, language server or crash report (files absent there: `LinuxRuntime.kt`,
  `LinuxService.kt`, `TerminalActivity.kt`, `ToolsScreen.kt`, `LspCheck.kt`, `CrashReport.kt`, `core/Lsp.kt`, `core/Rootfs.kt`).
  Everything else is the same code. Make a change in GPL, port it to MIT (copy the file when the two are identical before your change,
  otherwise apply the diff), then build and test both.
- Build and test (each edition):
  `./gradlew --no-daemon -q -Dorg.gradle.java.installations.paths=/usr/lib/jvm/java-17-openjdk-amd64 assembleDebug testDebugUnitTest --rerun`
  Counts at 1.5.126 were GPL 832 and MIT 814; from 1.5.128 both editions have the same count (see the README's newest section). A lower count after your change means a test was lost.
- Live benchmark: `.github/workflows/benchmark.yml`, started by hand (`workflow_dispatch`). Inputs that matter: `models`
  (`provider:model;…`), `suite` (`all, standard, hard, heldout, realproject, polyglot, polyglot-js, polyglot-all, chat`), `tasks`,
  `repeat` (1–3), `escalate` (`provider:model`), and
  **`refs`** (`main,candidate`: runs both and compares, which is the A/B). Results: the `summary` artifact and `bench-*` artifacts.
  Tasks live in `app/src/test/java/dev/pocketide/app/bench/`. Each task file has a fairness test (`…TasksTest.kt`): a new task
  must pass with a correct edit and fail without it.
- Budget: small Actions runs only. One model, named tasks, `repeat=1` to look; `repeat=3` only for the A/B that decides a release.
- `BENCHMARKS.md` holds every result and the failure classes. Add yours there. `PHONE-TEST.md` gets one checkbox per
  phone-visible change. `README.md` gets a section per release.
- Release: only the owner's machine runs `release.sh "<message>"`, and only after both builds and both test suites pass. It copies the
  two working copies over the two repos and pushes to `main`. **An executor never runs it and never pushes to `main`.**
- Keep changes small. One behaviour per change. No behaviour change without a test or a benchmark task that covers it.
- A behaviour change to the agent loop ships only after an A/B (`refs=main,candidate`) shows no drop. "Tried and not kept" is a
  normal outcome: record it in `BENCHMARKS.md` and remove the code.

## 2. Current state, gap by gap

The notes the owner started from were partly out of date. Verified against the code:

| # | Gap as noted | What the code has | What is really missing |
|---|---|---|---|
| 1 | No escalation to a stronger model | **Partly built.** Cascade and Critique workflows (`core/Workflow.kt:10-81`; `ChatPane.kt:2364-2383` hands the job to the strong slot when the review finds gaps). Advisor model consulted when the same problem returns (`NativeAgent.kt:635-690`). `core/Router.kt` picks cheap or strong per request. OpenRouter fallbacks (`AppSettings.kt:321`), `core/CreditSwitch.kt`, `core/SecondTry.kt` (off by default: it lowered scores). | **Escalation on a stall.** A run stopped as stuck, at its round limit or out of finish budget only tells the person to "send it again with a stronger model" (`NativeAgent.kt:1341`, `NEXT_STEPS`). Nothing offers or does it. |
| 2 | No pause-and-step debugger | Log points only (`core/Trace.kt`, Preview → Debug, `look_at_preview trace=`). | Pause, step, inspect. Confirmed missing. |
| 3 | Next-edit only for renames | `core/NextEdit.kt`, `EditorPane.kt:194-230`, no model call. Inline completion exists separately (`core/Autocomplete.kt`). | Model-backed next-edit for general changes. Confirmed missing. |
| 4 | No error reports from published apps | Nothing in `core/CfDeploy.kt`, `CfDeployRun.kt` or `PagesDeploy.kt`. The app's own crashes are caught (`CrashReport.kt`, GPL only). | Confirmed missing. |
| 5 | Real-phone testing not done | `PHONE-TEST.md`: 78 unchecked boxes, 2 checked. | Confirmed. The 1.5.123 deploy routing and the meal-planner update are untested on a phone. |
| 6 | JavaScript polyglot not covered | Suite `polyglot` is Python only (`bench/PolyglotTasks.kt`: `PREFIX = "py-"`, reads `python/exercises/practice`). | Confirmed. |
| 7 | Agent loops run to the round limit | **Guards exist**: `ToolLoop` (second identical `read_file` refused), `ReadStreak` (3 empty searches), `StuckDetector` (same call and result 3 times, 3 errors in a row, A-B-A-B, 12 stale rounds), all in `core/AgentGuard.kt:83-207`. | They did not fire in the field run (`BENCHMARKS.md:817-860`). Likely cause, **not yet proven**: every `run_command` counts as a change and clears `ToolLoop` (`NativeAgent.kt:1674`), so `cat -n file \| sed` between reads reset it; reads of different line ranges have different arguments; page looks return different text each time. Settle it with a replay test (phase 1). Also missing: a run that ends with no changes and an open choice ends "stopped", not with the question. |
| 8 | Chat always builds; no talk mode | **Wrong.** Ask mode never changes anything (`ChatPane.kt:439-449`; `core/AppModes.kt` `CHANGES` refused). "Agent, suggest only" shows each change to apply. `core/Intent.kt` treats a question as read-only even in Agent mode, with a tap to override. Chats persist per project (`ChatPane.kt:1798`, `meta/<project>/chats/*.json`), stopped runs carry on (`core/StopNote.kt`), `/model` switches model, pictures and files attach, voice input (`MainActivity.kt:120`), export, hand-off to the Claude app (`core/ClaudeLink.kt`). | **Chat is tied to a project** (`Projects.metaDir` is keyed by the open project). There is no place to just talk with no project, no list or search of chats across projects, and a question is capped at 12 tool rounds (`Rounds.QUESTION_ROUNDS`). Unknown: how well the Ask prompt handles non-coding questions. Check: phase 4's chat tasks. |
| 9 | Cannot update itself | **Pieces exist.** `/cloud <task>`: an issue starts a GitHub Actions agent that pushes a branch and opens a pull request (`core/CloudAgent.kt`, `CloudAgentScript.kt`; permissions `contents: write`, `pull-requests: write`). `/nightly` schedules it. `core/CopilotHandoff.kt` assigns an issue to Copilot. `core/CiFailure.kt` reads a failed build. `SelfFixScreen.kt` writes a bug report about the app itself and downloads the newest good APK from Actions; installing needs the owner's tap. `build-apk.yml` runs the tests and builds on every push to `main`. | (a) Nothing runs tests on a **pull request** (`build-apk.yml` triggers on `push: main` only). (b) The app cannot start `benchmark.yml` (no `dispatches` call anywhere) or read its summary. (c) The cloud agent's commands have a 2-minute limit (`CloudAgentScript.kt:126`), too short for a Gradle build, so it cannot test its own change. (d) Nothing stops an agent's branch from editing workflows or safety code. (e) The two-edition port and `release.sh` live on the owner's machine, not in the repos. (f) Whether `main` is protected is **unknown**: check the repo settings. |

## 3. Chat design: "like Claude or Copilot" on a phone

| Part | Exists? | Work |
|---|---|---|
| Free Q&A that changes nothing | Yes, inside a project (Ask mode, question intent) | Make it reachable with no project |
| "Just talk" vs "build" switch | Yes: the mode chip (Ask / Plan / Agent / Agent, suggest only) | Keep. In the new Chat home the chip starts on Ask |
| Ask before a change | Yes: Agent, suggest only; plan approval | None |
| History after closing the app | Yes, per project | One chat list across projects and the Chat home, with search |
| Model choice | Yes: `/model`, model picker | None |
| Attach files and pictures | Yes | In the Chat home an attached file is read-only context |
| Continue a stopped run | Yes (`StopNote`) | None |
| A chat with no project | **No** | New: a "Chat" home |
| Turn a conversation into a build | **No** | New: "Build this" makes or opens a project and carries the conversation into it |

Design for the Chat home: a reserved project key (for example `.chat`, hidden from the project list) so `ChatPane`, `ChatLog` and
storage are reused unchanged. In it: Ask mode fixed on, no file or command tools offered, web research offered when the setting is
on, attachments as context. "Build this" creates a project, copies the chat into it, and switches the chip to Plan. This is a
small change to where chats are stored and what the first screen shows, not a new chat engine.

## 4. Self-update design: PocketIDE as executor

**The path, end to end**

1. On the phone, the open project is PocketIDE's own repo. The owner picks a task from section 7 and sends it with `/cloud`.
2. The Actions agent works on a branch `pocketide/<issue>` and opens a pull request. It never touches `main`.
3. A new pull-request workflow builds the app and runs every unit test on that branch (the agent's own 2-minute commands cannot).
4. A new guard job fails the pull request if it touches a protected path (below).
5. The owner (or PocketIDE with `/bench`) starts `benchmark.yml` with `refs=main,<branch>` and the tasks the change is about.
6. The result is posted as a comment on the pull request: tests passed or failed, the A/B table, the cost.
7. **The owner reads the diff and the comment, then merges or closes.** This is the approval step. Nothing else merges.
8. The owner ports to the other edition and runs `release.sh` (or hands the port to an executor as its own pull request).
9. `build-apk.yml` builds the merged commit; the owner installs it from the app's self-fix screen with a tap.

**PocketIDE may, on its own:** create a branch and a pull request; change app source, tests, benchmark tasks and docs in it; start
a benchmark run within the budget in section 1; comment results.

**PocketIDE must not, ever, on its own:** merge; push to `main`; run or edit `release.sh`; edit anything under `.github/workflows/`;
change the files that hold its own limits (`core/AgentGuard.kt`, `core/CommandRules.kt`, `core/DeleteRules.kt`,
`core/ProjectGuard.kt`, `core/Sandbox.kt`, `core/CloudAgent.kt`, `core/CloudAgentScript.kt`); weaken a benchmark check or delete a
test to make a run pass; raise its own budget; read or print secrets. A task that needs one of these is done by an external
executor in a pull request the owner reviews line by line.

**Rejection rule:** close the pull request when any unit test fails, the test count drops, the guard job fails, the A/B total is
lower than `main` by more than one task over three repeats, or the diff contains something the task did not ask for.

**When its benchmark fails:** one more attempt on the same branch, using the failing output. After the second failure it stops,
comments what it tried and what it thinks the cause is, and leaves the pull request open as a draft. It asks the owner instead of
trying a third time, and it asks at once when the task is unclear, needs a protected path, or needs a secret or setting.

**Needs from the owner (an agent cannot do these):** turn on branch protection for `main` on both repos (pull request required,
one approval, status checks required); confirm the repos have the `pocketide-agent.yml` workflow, the `POCKETIDE_API_KEY` secret
and the `POCKETIDE_MODEL` variable; give the phone's GitHub token the Actions write permission for `/bench`. **Decision needed:**
`release.sh` pushes straight to `main`, which branch protection will refuse. Either the owner's account is allowed to bypass, or
releases become pull requests too. Until this is decided, steps 3–7 still work, as a convention without enforcement.

**Which phases PocketIDE can run itself:** after phase 2 exists, phases 1b, 3, 6 and the task files of 5. Phase 2 itself, and
anything touching protected paths (parts of 1 and 4), need an external executor first.

## 5. Phases

Order: prove what shipped, fix the failure we measured, add the rails that make every later change safer, then features by size.

### Phase 0: real-phone check (owner, about 30 minutes, no code)
- **Why first:** two releases and the meal planner are unverified on a device; later phases build on them.
- **Do:** the `PHONE-TEST.md` boxes for 1.5.117 onwards; add `meal-planner-update.zip` to the Web page project, paste a recipe,
  Save, `/deploy cloudflare`, import a link from the published address, Add to Home Screen.
- **Exit:** each box ticked or written up as a bug with what was on screen. Bugs go ahead of phase 1.
- **Executor:** the owner. Nobody else has the phone.

### Phase 1: stalls end usefully (medium)
- **Why here:** it is the failure in the newest field run, and the proposals are already written (`BENCHMARKS.md:846-850`).
- **1a. Prove why the guards missed.** In `bench/AgentLoopTest.kt` (scripted model, no network) replay the field pattern: read a
  file, `run_command cat -n f | sed -n 1,40p`, read the same file with another range, look at the page, repeat 15 times, no edit.
  Expect today: no stop. That test is the pass/fail rule for 1b.
- **1b. Count repeats that the current guards miss** (`core/AgentGuard.kt`, called from `NativeAgent.kt:1674-1678`): reads of an
  unchanged file by path whatever the range; a read-only command (`cat`, `sed -n`, `head`, `grep`) must not clear `ToolLoop`;
  page looks with the same failing step. One stop with a reason, no nudges (the 1.5.78 rule).
- **1c. End with the question.** When a run ends with no file changed and its last text names a choice, the final message is the
  question with the options, not "stopped" (`NativeAgent.kt` finish path near 214-246; wording in `core/StopNote.kt`).
- **1d. A play-test step that fails the same way 3 times** gets one note: "check the step itself" (`core/PlayTests.kt`).
- **1e. Steer wording:** a message typed during a run joins this run's job (`core/Steer.kt`).
- **Tests:** 1a's replay; one unit test per rule beside the existing StuckDetector tests (`StuckDetectorTest`); `Eval` rows where routing changes.
- **Benchmark:** A/B each of 1b–1e separately, `refs=main,candidate`, suites `hard` and `realproject`, Kimi K2.6 and gpt-oss-120b,
  `repeat=3` for the deciding run. **Pass:** candidate total ≥ main total − 1, and the stalled-run count does not rise.
- **Exit:** the replay stops with a reason; no A/B drop; anything that dropped is removed and recorded.
- **Risk:** a guard that stops honest work (a model legitimately re-reading). If the A/B drops, loosen the count once, else drop it.
- **Executor:** Opus. `NativeAgent.kt` is 2,235 lines and the loop is easy to break. 1e (wording only) suits Haiku.
- 1b edits `core/AgentGuard.kt`, a protected path: external executor only.

### Phase 2: rails for every executor (small)
- **Why here:** cheap, and it makes phases 3–8 safe to hand to any agent, including PocketIDE.
- **Changes:** `.github/workflows/pr-check.yml` (on `pull_request`: `testDebugUnitTest assembleDebug`, same steps as
  `build-apk.yml:60-63`, plus a step that fails when the test count is below the count on `main`); a `guard` job that fails when
  the diff touches the protected paths of section 4 and the pull request's author is the agent; `CONTRIBUTING-AGENTS.md` with
  section 1 and section 6 of this file; the port and release steps written into the repo as a document (the scripts stay on the
  owner's machine).
- **Exit:** a test pull request with a broken test goes red; one editing `core/AgentGuard.kt` from the agent goes red; a clean
  one goes green. Owner turns on branch protection.
- **Risk:** Actions minutes. The check is one build, about the cost of `build-apk.yml`.
- **Executor:** Copilot or Opus (workflow files; never PocketIDE).

### Phase 3: JavaScript polyglot exercises (small)
- **Why here:** measurement before features; most of the owner's projects are JavaScript.
- **Changes:** `bench/PolyglotTasks.kt` reads `javascript/exercises/practice` too (prefix `js-`, tests run with the repo's own
  jest config); `PolyglotTasksTest.kt`; the workflow's polyglot step installs node dependencies once. The workflow edit is an
  external executor's; the Kotlin is PocketIDE-sized.
- **Pass:** fairness test green; one run of Kimi K2.6 recorded in `BENCHMARKS.md` as the baseline.
- **Uncertain:** install time for jest on the runner. Check with one run before choosing how many exercises to include.
- **Executor:** Haiku or PocketIDE for the Kotlin.

### Phase 4: escalate on a stall (medium)
- **Depends on:** phase 1 (a stall must be detected and named first).
- **Changes:** when a run ends stopped (stuck, round limit, finish budget) and a strong slot different from the working model has
  a key, the stop note carries a button "Carry on with <strong model>": it sends the `StopNote` text and the request to the strong
  slot (reuse `Workflow.escalatePrompt`, `ChatPane.kt:2377-2381`). A setting makes it automatic, off by default, one escalation
  per request, shown in the cost line. Add an `escalate` input to `benchmark.yml` next to `advisor`.
- **Pass:** on tasks the cheap model fails 3 of 3, the pass rate with escalation rises; total cost is reported beside it.
  Second try lowered scores, so do not assume: if the score does not rise, keep the button and drop the automatic setting.
- **Executor:** Opus.

### Phase 5: chat with no project (medium)
- **Changes:** the Chat home of section 3 (`Projects.kt`, `ProjectsScreen.kt`, `ChatPane.kt` storage at 1798-1880, `MainActivity.kt`
  first screen); chat list across projects with search; "Build this"; raise or remove the 12-round question cap only if the tasks
  below show answers being cut.
- **Tests and benchmark:** storage unit tests; a new suite `chat` of about ten plain questions (some about code, some not), each
  passing when the answer contains the expected fact, no file changed, and no change tool called.
- **Exit:** suite passes on Kimi K2.6 and Haiku; `PHONE-TEST.md` boxes ticked by the owner.
- **Risk:** the reserved project leaking into project lists, sync or deploy. Test each list.
- **Executor:** Opus for the design, Haiku or PocketIDE for the suite.

### Phase 6: `/bench` from the phone (small to medium)
- **Depends on:** phase 2. This completes the self-update milestone.
- **Changes:** `core/Slash.kt` adds `/bench [tasks] [branch]`; a new pure `core/BenchRun.kt` builds the dispatch body
  (`POST /repos/{repo}/actions/workflows/benchmark.yml/dispatches`) with the budget of section 1 as hard caps, follows the run
  (reuse `SelfFix.parseRuns`, `CloudFollow`), reads the `summary` artifact, and posts it as a pull-request comment.
- **Tests:** `BenchRunTest` against a fake GitHub server, as `CfDeployTest` does for Cloudflare.
- **Exit (the milestone demo):** PocketIDE on the phone takes one section-7 task through steps 1–7 of section 4, and the owner
  merges it. Write the run up in `BENCHMARKS.md`.
- **Executor:** Opus or Copilot. Then PocketIDE runs the demo.

### Phase 7: error reports from published apps (medium)
- **Changes:** `/deploy cloudflare` adds a small script to published pages that sends uncaught errors to a route the Worker
  answers; the app shows them and offers "fix this" (`core/CfDeploy.kt`, `CfDeployRun.kt`, a new screen).
- **Uncertain:** where reports are kept. A Worker has no storage without a KV or D1 binding, and the token may lack that
  permission. Check: create a KV namespace with the owner's token before designing further. Fallback: keep the last errors in
  the Worker's memory and say plainly that they are lost when it restarts.
- **Executor:** Opus.

### Phase 8: next-edit beyond renames (medium)
- **Changes:** after an edit, ask the completion model for the next likely edit in the file (`core/NextEdit.kt`, prompt in the
  style of `core/Autocomplete.kt`, bar in `EditorPane.kt:194-230`). Off on metered connections by default.
- **Benchmark:** extend `EditBenchmarkTest` with recorded edit pairs; pass when the suggested edit matches at a set rate and
  latency stays under two seconds on the reference model.
- **Executor:** Opus or Copilot.

### Phase 9: pause-and-step debugger (large, needs a spike first)
- **Uncertain:** Android's WebView gives an app no supported way to pause page JavaScript. Two candidates: rewrite the loaded
  copy so each statement awaits a "continue" (the way `core/Trace.kt` already rewrites for log points), or debug on the remote
  machine with Node's inspector (`core/RemoteDev.kt`). Spike both for one day each and write up which is possible before
  planning further. Do not start building before that write-up.
- **Executor:** Opus.

## 6. Notes for whoever executes

- Start by running both builds and both test suites untouched. If they are not green at the counts in section 1, stop and report.
- Read the file you are changing in full, and the test beside it. Follow the style there: plain-word comments that say why,
  with the version number.
- GPL first, then port to MIT, then build and test both. A change that cannot exist in MIT (Linux runtime) is GPL only: say so
  in the README section.
- When a check fails: read the output, fix the cause, run again. Never delete or weaken a test or a benchmark check to pass. If
  the test is wrong, say why in the pull request and let the owner decide.
- When a benchmark result is worse: report it as it is, remove the change, record it under "tried and not kept".
- Stop and ask the owner when: the task needs a protected path, a secret, a setting on GitHub, or money beyond the budget; two
  attempts failed; the request can be read two ways; or you are about to change something this document did not ask for.
- Never run `release.sh`, never push to `main`, never merge.
- The owner's rules for content: no Jagex game content is extracted or recreated; the owner's game gets engine and gameplay
  fixes only.

## 7. The first three tasks

Status at 1.5.126: tasks 2 and 3 are done (1.5.124). Task 1 is still open and is the milestone task.

**Task 1 (PocketIDE itself, via `/cloud`; today's pieces are enough for a draft pull request).**
"In `core/PlayTests.kt` and where `look_at_preview` reports a play test (`NativeAgent.kt`, search `PlayTests`), add: when the same
step of the same saved test fails with the same message three times in one request, add one line to the tool result: `This step
has failed the same way 3 times. Check the step itself: a step that types text into a number field, or expects something the
page never does, can never pass.` Say it once per test per request. Add a unit test in `PlayTestsTest` for 2 failures (no note)
and 3 (note). Change no other behaviour."
Until phase 2 exists, an external executor or the owner's machine must build and test the branch before it is merged.

**Task 2 (Copilot or Opus).** Phase 2's `pr-check.yml` and guard job, with the three demonstration pull requests listed under its
exit criteria. Ask the owner to turn on branch protection and to decide how `release.sh` passes it.

**Task 3 (Opus).** Phase 1a: the replay test in `bench/AgentLoopTest.kt` that reproduces the field run's read-and-look loop and
asserts the run is stopped with a reason. It is expected to fail at 1.5.123; commit it marked as the target for 1b, and report
which of the three suspected causes in gap 7 the replay confirms.

Owner, in parallel: phase 0 on the phone.
