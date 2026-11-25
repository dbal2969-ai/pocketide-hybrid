# #BuildHybridAI change record

Every change made to PocketIDE Hybrid since the Firebase #BuildHybridAI 2026 hackathon started, with the reason for each one, the
files it touched, the tests that cover it and the benchmark runs that led to it. `HACKATHON.md` summarises the features for judges;
this file is the full record.

- **Baseline:** `2b8c266` (2026-11-22) is PocketIDE MIT edition 1.5.182 exactly as it was before the hackathon (private repository
  `test-coder2.2`, commit `1013cb7`). Everything after it was made during the hackathon. `git diff 2b8c266..main` shows all of it.
- **Rule:** every commit that changes the app, its tests, the benchmark or the build adds an entry here in the same commit
  (newest at the bottom of its day). CI checks this (`.github/workflows/changelog.yml`).
- Times are US Eastern. Commit messages carry the same detail in shorter form.

## How to add an entry

```
### <short hash> · <date time> · <one-line title>
- **Why:** what was wrong or missing, and where it was seen (a phone run, a benchmark task, a user report).
- **Change:** what now happens, in behaviour terms.
- **Files:** the files changed.
- **Tests:** the tests added or changed, and the test count after it.
- **Evidence:** the benchmark run or report it came from, if any.
```

Write the entry with the commit, then fill in the hash in the next commit (or amend before pushing).

---

## Benchmark history

The hybrid benchmark (`bench/hybrid/`, run on a Galaxy S24+ with Gemini Nano 4 Fast (E2B), on-phone limit 14,000 characters,
cloud model `gemini-3.1-flash-lite`). Runs from 1124-1947 on use the one-tap runner in the app.

| Run | Build | Passed | Steps on phone | What it found | Fixed in |
| --- | --- | --- | --- | --- | --- |
| mp123 (manual, meal planner) | 1.5.183 at `cfd47de` | n/a (a free-form test project) | most | Nano deleted `let selectedRecipeIds = []` and failed for six rounds; Single model mode kept switching to the strong model | `efbd51c` |
| 1124-1947 (run 1) | `0cc393d` | 6 of 8 | not recorded | 04 rename: same failing edit three times, no replace-all; 06 research: empty-search loop, DATES.md never written; an unwanted "Carry on" escalation to another provider in Single model mode | `ed08bdb` |
| 1124-2004 (run 2) | `ed08bdb` | 8 of 8 | 29 of 54 | 05 and 06 passed their checks but ended "stopped or failed": a leftover "Placeholder" comment; plan steps of a notes-only task held open for a check | `affd564` |
| 1124-2028 (run 3) | `ed08bdb` (old APK) | 6 of 8 | 24 of 47 | 06 stalled on the "Let the assistant read npmjs.com?" card; 07 Nano saw only 2,000 characters of a 22,000-character file; 08 stopped when the app left the screen | `feb7289` |
| 1124-2048 (run 4) | `feb7289` | 8 of 8, all ended done | 39 of 68 | 01 Nano un-did its passing fix; 08 Private mode repeated a refused edit five times | `e61aafe` |
| 1124-2111 (run 5) | `e61aafe` | 8 of 8 | 25 of 57 | 01 done in 2 steps and 08 in 8 (were 7 and 19); 05 held on a Run/Skip card for `apk add` until the owner tapped Run, then ended "unfinished files: app.js" with the stand-in gone; 06 ended "stopped or failed" with no reason in the log | `fd54990` |
| 1124-2131 (run 6) | `c59582a` | 7 of 8, no stalls, every task ended done | 33 of 55 | 03 done entirely on the phone (9 steps); 08 failed: Nano's email regex lost its backslashes (`/S+@S+.S+/`), then Nano edited test.js instead of the app; 06 still showed "Changed, not checked" for a notes file | `f8e624b` |
| 1124-2147 (run 7) | `a943442` | 8 of 8, no stalls, every task ended done | 23 of 50 | 03 went to the cloud because PocketIDE was in the background (reason shown); 07 Nano's first reply was not an action, so the cloud took it; 08 passed in Private mode (7 steps); 01 Nano again swapped its passing fix back despite the note | `12178cb` |
| hard 1124-2213 (hard run 1) | `a31830a` | 9 of 10 | 39 of 84 | every handoff gave its reason (failed edits, a reply that was not an action, the web); h08 Private mode passed in 3 steps; h07 ended before any step: one cloud call failed with the SDK's "Something unexpected happened."; the report's # column showed "h0" | `31b6415` |
| hard 1124-2237 (hard run 2) | `82ab9d0` | 9 of 10 | 31 of 82 | h07 failed: the cloud reply stopped at `MAX_TOKENS` because the phone's 1,024-token output setting applied to the cloud model too; Nano also named a tool in Chinese (讀取文件), handed off | `3cbcdd0` |
| hard 1124-2308 (hard run 3) | `eb8137a` (cloud tiers) | **2 of 10** (regression) | 32 of 40 | every cloud step failed: `Role 'function' is not supported. Please use a valid role: user, model.` The newer models refuse the role the app sent tool results under (gemini-3.1-flash-lite had accepted it), and that error matched the loose "is not supported" test for a missing model, so Balanced was skipped and Flash-Lite answered and failed too. Only h08 and h10 (all on the phone) passed; Auto did move up to Best once ("the check failed 2 times") | `a06ce7a` |
| hard 1124-2343 (hard run 4) | `b7a2354` | **10 of 10** | 49 of 102 | first perfect hard run. Auto moved to Gemini 4 Hydrogen once ("the check failed 2 times"), and Hydrogen answered, so the project can use it. Google reported "high demand" during the run: it ended h01's request after its work was done, and in h02 Balanced was marked unusable for the session, so Fast (`gemini-3.5-flash-lite`) answered 51 of the cloud steps | `3375f9a` |
| hard 1125-0008 (hard run 5) | `e547833` | **10 of 10**, all ended done | 39 of 92 | second perfect hard run. Gemini 4 Hydrogen answered 8 steps after two moves up ("the last 2 tool calls failed", "the check failed 4 times"). The note now quoted why Balanced dropped out: `You exceeded your current quota, please check your plan and billing details` (the free tier's request quota for gemini-3.8-flash); the word "billing" made it count as unusable for the session, so Fast answered 45 steps | `a6b5958` |
| games 1125-0233 (game run 1) | `717b4be` | **5 of 5**, all ended done | 19 of 56 | the play tests ran before the work was called done and sent gm03 and gm05 back once each; the page check asked for touch controls on the keyboard-only paddle game (gm02); Auto moved to Gemini 4 Hydrogen once ("the last 2 tool calls failed"). Balanced's free quota was still spent from the night's runs, so Fast answered 35 cloud steps and Hydrogen 2 | none needed |
| hard helping 1125-0743 | `7bc42e7` | **10 of 10**, all ended done | 26 on the phone (the report did not count the strong model's steps) | first "Hybrid helps my strong model" run, with `kilo/recursive/flint-v1.0:free` as the strong model: every handoff went to it and every task passed; h08 Private stayed on the phone (13 steps). Nano asked for a tool named `membaca_file` (h07) | `(next)` |

Reports and zips: run results are kept by the owner (Downloads/PocketIDE on the phone). Each zip has `report.md` and, per task,
the files after the run, `transcript.txt` and `check.txt`.

---

## 2026-11-22

### 2b8c266 · 2026-11-22 · Baseline
- PocketIDE MIT edition 1.5.182 as it was before #BuildHybridAI. No changes.

### b27edee · 2026-11-22 11:14 · Hybrid AI with Firebase AI Logic
- **Why:** the hackathon entry: Gemini Nano on the phone with cloud Gemini fallback through Firebase AI Logic.
- **Change:** `HybridAi` with three routing modes (PREFER_ON_DEVICE, PREFER_IN_CLOUD, ONLY_ON_DEVICE); long requests go to the
  cloud; replies labelled with where they ran; Gemini Nano status and download. Hybrid AI screen (Models → Hybrid AI) and editor
  Quick assist (explain, bugs, doc comment, commit message). App Check: debug provider in debug builds, Play Integrity in release.
  Firebase applied only when `app/google-services.json` exists; CI writes it from a secret. `HACKATHON.md` added.
- **Files:** 11 files, +431 (HybridAi.kt, HybridScreen.kt, ModelsScreen.kt, EditorPane.kt, AppCheckSetup.kt (debug and release),
  build.gradle.kts (root and app), build-apk.yml, .gitignore, HACKATHON.md).

### ac690ad · 2026-11-22 11:14 · HACKATHON.md: fix a sentence
- Wording only.

### b2e688a · 2026-11-22 12:55 · Fix the Firebase AI Logic call
- **Why:** first compile failed: `FirebaseAI.getInstance` takes the FirebaseApp first, then the backend.
- **Change:** pass `FirebaseApp.getInstance()` explicitly. **Files:** HybridAi.kt.

### 88101bf · 2026-11-22 15:04 · Show the raw Gemini Nano status when unavailable
- **Why:** the phone said "Nano unavailable" with no reason although AICore had the model.
- **Change:** the screen shows why (no on-device model object, or the SDK status). **Files:** HybridAi.kt, HybridScreen.kt.

### fe75aa2 · 2026-11-22 15:15 · Re-check Gemini Nano status for about 2 minutes (later reverted)
- **Why:** the screen flashed DOWNLOADABLE, then UNAVAILABLE. **Change:** poll the status so Download stays tappable.
- Reverted in `2a8603c` at the owner's request once the real cause was found.

### 20ba053 · 2026-11-22 15:24 · Ask for the preview on-device model (Gemini Nano 4 Fast)
- **Why:** the SDK defaults to the stable model, which the S24+ reports as unavailable; AICore lists the preview model.
- **Change:** `OnDeviceConfig(modelOption = PREVIEW_FAST)`. This fixed "Nano unavailable". **Files:** HybridAi.kt.

### 2a8603c · 2026-11-22 15:26 · Revert the status re-check
- Reverts `fe75aa2` (owner's request; the cause was the model option, fixed in `20ba053`).

### ec81b34 · 2026-11-22 15:40 · Hybrid AI in the model list
- **Why:** owner's request: use hybrid AI in regular chat and the agent, in a project or outside one.
- **Change:** new keyless provider "Hybrid AI (Gemini Nano + cloud)"; chat and agent requests go through `HybridAi.chat`.
- **Files:** core/Providers.kt, ModelClient.kt, HybridAi.kt, MainActivity.kt.

### 12703fd · 2026-11-22 15:48 · On-phone limit 14,000 characters; each reply says where it ran
- **Why:** owner's request (raise the limit; show local vs cloud on every reply). **Files:** HybridAi.kt, ModelClient.kt.

### 335bd74 · 2026-11-22 16:28 · Agent reads `language:file.ext` fences as file writes
- **Why:** in the owner's snake-game run, the hybrid model wrote ```` ```javascript:game.js ```` blocks; the parser ignored them,
  so the agent reported progress while no file changed.
- **Change:** such fences are file blocks (same extension check as the loose-file fallback).
- **Files:** core/ReplyParser.kt. **Tests:** LangPathFenceTest.

### ab50b39 · 2026-11-22 17:14 · Hybrid AI settings
- **Why:** owner's request: adjustable temperature, top-K, max output tokens and context limit instead of a fixed 14,000.
- **Change:** settings card on the Hybrid AI screen; values go to Nano and the cloud model, clamped to safe ranges, with a
  warning above 14,000 characters. **Files:** core/HybridSettings.kt, HybridAi.kt, HybridScreen.kt. **Tests:** HybridSettingsTest.

## 2026-11-24

### 40717d2 · 2026-11-24 13:26 · Provider settings open the Hybrid AI settings
- **Why:** owner's request (the same settings from provider settings). **Files:** MainActivity.kt.

### 951a015 · 2026-11-24 13:51 · Real tool calls on the phone and in the cloud
- **Why:** owner report: with hybrid the agent said it could not call tools, or claimed edits it never made, and hallucinated.
- **Change:** `HybridAgent`: Nano runs in small local model mode (one JSON action, core tools) and its action becomes a real tool
  call; a reply that is not one action, names an unknown tool, or claims file changes with no write goes to the cloud. The cloud
  path uses Firebase AI Logic function calling with every PocketIDE tool, keeping the SDK's call objects so Gemini 3 thought
  signatures go back unchanged. Setting for the Fast (E2B) or Full (E4B) on-phone model. Cloudflare's provider note now says
  usage past the free allowance is billed (the owner received an invoice).
- **Files:** HybridAgent.kt (new), HybridAi.kt, HybridScreen.kt, ModelClient.kt, core/HybridSettings.kt, core/Providers.kt,
  core/SmallLocal.kt, core/Tools.kt, app/build.gradle.kts (kotlinx-serialization-json). **Tests:** HybridAgentTest.

### fcf93b0 · 2026-11-24 14:20 · Gemini Nano says when it passes a request on, and to which model
- **Why:** owner report: asked to research meal planning on GitHub, Nano replied "I cannot directly perform research on GitHub"
  and the reply was labelled as run on the phone.
- **Change:** requests that need the web, are over the on-phone limit, or that Nano refuses are passed on in on-phone-first mode;
  Nano's handoff line ("⚡ Gemini Nano (on this phone): I'm passing this to … Why: …") shows at once and stays above the reply. The
  request goes to the strong model when one is set for workflows, else to the cloud model. Stop also stops the handed-off request.
- **Files:** core/HybridRoute.kt (new), HybridAgent.kt, HybridAi.kt, ModelClient.kt. **Tests:** HybridRouteTest.

### e1656db · 2026-11-24 14:28 · Remote Config, Authentication and Firestore
- **Why:** owner's request: more Firebase integration (Remote Config and Authentication).
- **Change:** Remote Config keys `hybrid_cloud_model`, `hybrid_on_device_max_chars`, `hybrid_default_mode`,
  `hybrid_cloud_enabled` (cloud kill switch). Anonymous Authentication per install. Firestore `users/{uid}/hybrid_runs`: where each
  request ran, its time and why Nano passed it on (never prompts or code), shown as "Your hybrid usage".
- **Files:** HybridCloud.kt (new), core/HybridStats.kt (new), HybridAi.kt, HybridScreen.kt, MainActivity.kt, ModelClient.kt,
  app/build.gradle.kts, HACKATHON.md (console steps). **Tests:** HybridStatsTest.

### 4c4a33b · 2026-11-24 14:57 · Headless agent tests; a handed-off request stays handed off
- **Why:** the owner could not test on the phone; headless tests of the real agent loop were needed.
- **Change:** `HybridAgentLoopTest` runs NativeAgent and ModelClient's hybrid path under Robolectric with scripted Nano and cloud
  replies. Found and fixed: after a handoff the rest of an agent request stays with the model it went to; cloud-first mode always
  uses the cloud model, not the strong one.
- **Files:** HybridAgent.kt, HybridCloud.kt (test switch `off`), ModelClient.kt. **Tests:** HybridAgentLoopTest (6 scenarios).

### cfd47de · 2026-11-24 17:35 · When the strong model fails, the cloud model answers
- **Why:** owner report: with only Hybrid AI selected, a handoff went to a gateway that answered "You need to sign in" (HTTP 401).
- **Change:** a failed strong-model handoff falls back to the cloud model; Nano says which model failed and why.
- **Files:** HybridAgent.kt (`fallback`), ModelClient.kt. **Tests:** HybridRouteTest.

### efbd51c · 2026-11-24 18:44 · Reliability from the meal-planner run (mp123)
- **Why:** the owner's mp123 run: Nano replaced `let selectedRecipeIds = []` and spent six rounds failing around the gap; Single
  model mode kept switching to the strong model.
- **Change:** an edit that deletes a definition the file still uses is refused and the file put back (was a warning). After two
  failed tool calls in a row, Nano passes the rest of the request to the cloud. Strong-model handoffs only in the Cascade and
  Critique workflows, never in Single model mode. `web_search` accepts `q`, `pattern` and `search` as the query.
- **Files:** NativeAgent.kt, core/UndefinedNames.kt, HybridAgent.kt, ModelClient.kt. **Tests:** HybridAgentLoopTest, HybridRouteTest.

### 46d9614 · 2026-11-24 19:09 · Judges' build: one App Check debug token for every phone
- **Change:** a debug build given `-PjudgeAppCheckToken` (or `JUDGE_APPCHECK_TOKEN`) carries
  `R.string.firebase_app_check_debug_token`, used on every install and ignored in release builds. The token is never in git.
- **Files:** app/build.gradle.kts, HybridAi.kt.

### 6c1ac94 · 2026-11-24 19:20 · Crashlytics, Performance Monitoring, Private switch, first-run intro
- **Change:** Crashlytics (applied with google-services). Performance Monitoring traces `hybrid_phone`, `hybrid_chat`,
  `hybrid_cloud`. A Private switch (Gemini Nano only) and a one-time "How hybrid AI works" dialog on the Hybrid AI screen.
- **Files:** build.gradle.kts (root and app), HybridCloud.kt, HybridAi.kt, HybridAgent.kt, HybridScreen.kt.
  **Tests:** HybridScreenTest (opens the screen headless).

### 9c5cb55 · 2026-11-24 19:20 · HACKATHON.md: try it, how it works (diagram), testing
- Documentation only.

### 6399eb0 · 2026-11-24 19:27 · Hybrid AI benchmark: eight phone tasks with checks
- **Why:** owner's request for proper benchmarks.
- **Change:** `bench/hybrid/`: starting projects as zips, prompts, pass checks and a results table; `make_tasks.py` rebuilds them.
  Each task's `node test.js` fails until the work is done (checked with reference fixes).
- **Files:** bench/hybrid/ (28 files).

### 0cc393d · 2026-11-24 19:44 · One-tap hybrid benchmark inside the app
- **Why:** owner's request ("build the runner inside the app"); the owner could not run tasks by hand.
- **Change:** Models → Hybrid AI → Run the hybrid benchmark runs the eight tasks unattended through the real chat screen in Agent
  mode, checks each, and saves a zip (report, files, chat logs, check output) to Downloads/PocketIDE; settings and project are
  put back; 12 minutes at most per task.
- **Files:** HybridBench.kt (new), core/HybridBenchTasks.kt (new), core/HybridBenchFiles.kt (generated by
  bench/hybrid/to_kotlin.py), ChatPane.kt (`onExchangeEnded`, `busy`, `stopNow`), MainActivity.kt, HybridScreen.kt.
  **Tests:** HybridBenchTasksTest.

### ed08bdb · 2026-11-24 20:02 · Fixes from benchmark run 1 (6 of 8)
- **Evidence:** run 1124-1947.
- **Change:** `edit_file` takes `all=true` to replace every match, and the "matches more than once" error says so. A step
  identical to one that already failed goes to the cloud. After two empty web searches in a row the model is told to read a known
  page or go on; after four, `web_search` is off for the request. Hybrid AI in Single model mode no longer offers to carry on with
  another provider after a failure.
- **Files:** NativeAgent.kt, core/EditBlocks.kt, core/Tools.kt, HybridAgent.kt, ChatPane.kt.
  **Tests:** HybridRouteTest, HybridAgentLoopTest (rename, repeated step).

### affd564 · 2026-11-24 20:24 · Fixes from benchmark run 2 (8 of 8, two false "not finished")
- **Evidence:** run 1124-2004, tasks 05 and 06.
- **Change:** a run that only wrote notes (Markdown, text) takes the model's "done" on its plan steps and is not asked for a test
  it cannot have. The stand-in check names the exact line and number, and says to delete a stale comment above working code.
  Also deleted a stray `NativeAgent.kt.orig` backup (2,702 lines) that had been committed by mistake.
- **Files:** NativeAgent.kt, core/StubCheck.kt; test harness `AgentHarness` gained a `verify` flag.
  **Tests:** HybridAgentLoopTest (notes-only plan, fails on the old code), StubCheckLineTest. 1,223 tests.

### feb7289 · 2026-11-24 20:44 · Fixes from benchmark run 3 (6 of 8)
- **Evidence:** run 1124-2028 and the owner's screenshots of the stalled task 06.
- **Change:** during the benchmark, website reads are allowed for that run only (not saved as always-allowed; noted in the chat
  log) and test-file changes are not asked about. The newest tool result gets most of the on-phone limit instead of 2,000
  characters; a result longer than that is handed to the cloud ("what I just read is longer than my on-phone limit"). "You already
  read this file" counts as a failed step. When Gemini Nano fails because PocketIDE is in the background (Android does not allow
  it), Private mode waits for the app to be on screen and tries again; other modes pass the step on and say why.
- **Files:** NativeAgent.kt, HybridAgent.kt. **Tests:** HybridAgentLoopTest (long file, repeated reads, mid-sized result). 1,226 tests.

### e61aafe · 2026-11-24 21:09 · Fixes from benchmark run 4 (8 of 8)
- **Evidence:** run 1124-2048, tasks 01 and 08.
- **Change:** when the newest result is a passing command after a change, Nano's prompt says the check passed and asks for the
  answer (Nano had edited its passing fix back out). In Private mode, a step that repeats a failed one is not run; Nano is asked
  once more, told what failed (it had sent the same refused edit five times).
- **Files:** HybridAgent.kt. **Tests:** HybridAgentLoopTest (passing-check note, Private-mode retry). 1,228 tests.

### fa1bd82 · 2026-11-24 21:20 · Change record
- **Why:** owner's request: keep detailed records of every change since the hackathon started.
- **Change:** this file; a CI check that a change to the app, tests, benchmark or build also updates it; the rule added to
  `CONTRIBUTING-AGENTS.md`; `HACKATHON.md` points here and its routing row is corrected (the on-phone limit is 14,000 characters
  by default and adjustable, not 4,000).
- **Files:** HYBRID-CHANGELOG.md, .github/workflows/changelog.yml, scripts/check-changelog.sh, CONTRIBUTING-AGENTS.md, HACKATHON.md.

### fd54990 · 2026-11-24 21:40 · Fixes from benchmark run 5 (8 of 8)
- **Evidence:** run 1124-2111 and the owner's screenshot of the held Run/Skip card (task 05).
- **Change:** during the benchmark, a command that always needs the person's OK (here `apk add`, which uses the network) is
  skipped instead of waiting, the model is told it was skipped, and the chat log notes it. Files marked unfinished are checked
  again as they are now before the finish check and the verdict (edits with `edit_lines` did not update the record, so app.js
  stayed "unfinished" after the stand-in was gone and `node test.js` passed). A run that only wrote notes is not asked to test
  them ("nothing was checked after the last change"). An error that ends a request is now written to the chat log, so a
  "stopped or failed" ending says why.
- **Files:** ChatPane.kt (`askRunCard`, `showError`), NativeAgent.kt (`refreshStubs`, verification gate).
- **Tests:** HybridAgentLoopTest (a stand-in removed with edit_lines; fails on the old code). 1,229 tests.

### f8e624b · 2026-11-24 22:05 · Fixes from benchmark run 6 (7 of 8)
- **Evidence:** run 1124-2131, task 08 (Private mode) and task 06.
- **Change:** the JSON reader keeps a backslash before a character JSON does not escape (`\S`, `\d`, `\.`): Gemini Nano writes
  regexes that way, and dropping it saved `/S+@S+.S+/`, which refused every good email. The test file a request names as its check
  ("so node test.js prints ok") cannot have existing lines changed or removed unless the request asks to change the tests (new lines
  are fine); Nano had edited test.js to pass. The benchmark always checks with the task's own test, put back if the model changed
  it, and says so in check.txt. A run that only wrote notes gets no "Changed, not checked" verdict.
- **Files:** core/MiniJson.kt, core/Scope.kt (`namedChecks`, `checkRefusal`), NativeAgent.kt (`testGate`, `verdict`), HybridBench.kt.
- **Tests:** NamedCheckTest (7), HybridAgentLoopTest (Nano cannot rewrite the named test). 1,237 tests.

### 12178cb · 2026-11-24 22:20 · Nano cannot undo a fix that just passed (run 7)
- **Evidence:** runs 1124-2048 and 1124-2147, task 01: after `node test.js` passed, Nano edited `return a + b;` back to
  `return a - b;`, failed, re-read and redid the fix (7 steps instead of 2). The prompt note from `e61aafe` did not stop it.
- **Change:** right after a passing check, an `edit_file` that reverses an earlier edit of the same file (search and replace
  swapped) is not run; the request ends as done and the chat says Nano tried to undo a change that had just passed.
- **Files:** HybridAgent.kt (`undoes`). **Tests:** HybridAgentLoopTest (fails on the old code). 1,238 tests.

### 93708e3 · 2026-11-24 22:45 · Hard hybrid benchmark (ten tasks)
- **Why:** owner's request for harder benchmark tests; the standard eight had passed 8 of 8 in three of the last four runs.
- **Change:** `bench/hybrid/hard`: ten tasks, each harder in a different way: two bugs in two files behind one symptom (h01), code
  from a written spec with 20 cases (h02), an async ordering and error bug (h03), a refactor into new files that must keep old
  callers working (h04), a bug in a nested helper found from the failing test alone (h05), UI filters checked by a 14-step play test
  (h06), two bugs far apart in a 34,000-character file (h07), four password rules in Private mode (h08), web research written to a
  file a program checks (h09), and a shared helper with two different callers (h10). Each command task has a reference fix, and
  `check_hard.py` proves it fails as given and passes with the fix (h06 was checked with Playwright). Models → Hybrid AI has a
  **Run the hard benchmark** button; the runner, report and zip name say which suite ran. `to_kotlin.py` takes the object name.
- **Files:** bench/hybrid/make_hard.py, check_hard.py, hard/ (tasks, zips, solutions), to_kotlin.py, README.md;
  core/HybridBenchTasks.kt (`HARD`, `report` total and title), core/HybridBenchHardFiles.kt (generated), HybridBench.kt (`hard`),
  HybridScreen.kt.
- **Tests:** HybridBenchHardTest (5: files and ids, app copy matches the repository, every task fails as given and passes with its
  reference fix under Node, judges, research routing). 1,243 tests.

### 31b6415 · 2026-11-24 23:00 · Fixes from hard benchmark run 1 (9 of 10)
- **Evidence:** hard run 1124-2213, task h07: `error: Something unexpected happened.` one second after the request started, no step
  run. That text is the Firebase AI SDK's UnknownException, which wraps the real cause.
- **Change:** a cloud call that fails in a way that may pass (anything but App Check, permissions, a disabled API, a bad request,
  billing or a cancel) is tried once more after 2.5 s. Error messages now add the cause under the SDK's wrapper, for example
  "(IOException: Connection reset by peer)". The report's task column shows the full number (h01 … h10, not h0).
- **Files:** HybridAgent.kt (`transient`, retry in `cloud`), HybridAi.kt (`explain`), core/HybridBenchTasks.kt.
- **Tests:** HybridAgentTest (2). 1,245 tests.

### 3cbcdd0 · 2026-11-24 23:30 · Cloud model tiers with Auto escalation (toward parity with Copilot-class agents)
- **Why:** owner's request: bring PocketIDE to parity with Copilot or another reputable AI IDE through the hybrid model selection.
  The cloud side used `gemini-3.1-flash-lite`, the smallest model, for every step, with the phone's 1,024-token output limit
  (hard run 2, h07, stopped at `MAX_TOKENS`). The Firebase AI Logic model list (checked 2026-11-24) has stronger models that need no
  billing on the Gemini Developer API: `gemini-3.8-flash` and `gemini-4-hydrogen` (stable since 2026-11-12), plus `gemini-3.5-flash-lite`.
- **Change:** three cloud tiers: Fast (`gemini-3.5-flash-lite`, low thinking), Balanced (`gemini-3.8-flash`, medium thinking), Best
  (`gemini-4-hydrogen`, high thinking). Models → Hybrid AI → Cloud model: Auto (default), Fast, Balanced or Best. **Auto** starts on
  Balanced and passes the rest of a request to Best when it proves hard: two failed tool calls in a row, a check failing twice, or the
  app's checks sending the work back twice. Like Gemini Nano's handoff, it says so once in the chat ("☁ Passing this to the
  strongest cloud model (gemini-4-hydrogen). Why: …") and the rest of the request stays there. Every reply's label names the model
  that answered. A model the project cannot use (not found, not available, billing) is skipped for the session and the next tier
  down answers, with a note; a refused thinking setting is dropped. Cloud replies get at least 8,192 output tokens (16,384 on Best),
  and a reply cut off at `MAX_TOKENS` is asked again with 32,768. Remote Config's `hybrid_cloud_model` is now empty by default; set in
  the console it pins one model for every step (the screen says so). The benchmark report names the cloud choice.
- **Files:** core/CloudTier.kt (new), HybridAgent.kt (`tierFor`, tiered `cloud` with fallback), HybridAi.kt (tiers, thinking,
  output limits, fallback in `ask`), HybridCloud.kt (`pinnedModel`), HybridScreen.kt (cloud model choice), ModelClient.kt
  (label from the model that answered), HybridBench.kt, HACKATHON.md.
- **Tests:** CloudTierTest (11), HybridAgentLoopTest (a hard request moves up to Best once, says why, and stays there). 1,257 tests.
- **Not yet measured on the phone:** whether this project can use `gemini-4-hydrogen` (if not, Auto falls back to Balanced and says
  so); the next standard and hard benchmark runs will show the effect.

### a06ce7a · 2026-11-24 23:40 · Fix the cloud tiers' regression (hard run 3, 2 of 10)
- **Evidence:** hard run 1124-2308: eight tasks ended on `Role 'function' is not supported. Please use a valid role: user, model.`
- **Change:** tool results go back to the cloud model as the user's turn (with their function-response parts), not under the old
  "function" role, and neighbouring turns of the same role are joined so turns alternate. Only errors about the model itself
  ("models/… is not found", NOT_FOUND, 404, billing) now count as a model the project cannot use; a role error is not retried.
- **Files:** HybridAgent.kt (`contents`, `transient`, `remember` for tests), core/CloudTier.kt (`unavailable`).
- **Tests:** HybridAgentTest (roles are only user and model, and alternate), CloudTierTest (a request error is not a missing model).
  1,259 tests.
- **Lesson:** the headless tests replace the cloud call, so they could not see what the new models accept. The phone benchmark is
  what caught it; a cloud model change must be followed by a benchmark run before it is called done.

### 3375f9a · 2026-11-25 00:15 · A busy cloud model hands one step down; it is not marked unusable (hard run 4, 10 of 10)
- **Evidence:** hard run 1124-2343: `error: This model is currently experiencing high demand…` and, in h02, "Balanced
  (gemini-3.8-flash) is not available to this project, so Fast answered" for the rest of the session (the error itself was not
  in the log).
- **Change:** an error that says the model is busy or rate-limited (high demand, overloaded, try again later, 503, 429,
  RESOURCE_EXHAUSTED, quota) hands that one step to the next tier down, with a note naming the error; the tier is tried again on the
  next step. Only "not found" and billing errors mark a tier unusable for the session, and that note now quotes the error too.
- **Files:** core/CloudTier.kt (`busy`), HybridAgent.kt, HACKATHON.md. **Tests:** CloudTierTest (a busy model is not unusable). 1,260 tests.

### a6b5958 · 2026-11-25 00:35 · A spent free quota cools a tier down for 10 minutes (hard run 5, 10 of 10)
- **Evidence:** hard run 1125-0008: "Balanced (gemini-3.8-flash) is not available to this project, so Fast answers from now on (You
  exceeded your current quota, please check your plan and billing details…)".
- **Change:** quota errors (exceeded your current quota, quota exceeded, RESOURCE_EXHAUSTED, 429, rate limit) are checked before
  the unusable-model test. The tier cools down for 10 minutes and the next tier down answers meanwhile, with a note saying so; then
  it is tried again. Only a model that is not found, or one that needs the Blaze plan, is dropped for the session.
- **Files:** core/CloudTier.kt (`quota`, `QUOTA_COOLDOWN_MS`), HybridAi.kt (`coolUntil`, `skipNow`), HybridAgent.kt.
- **Tests:** CloudTierTest (a spent quota cools down). 1,261 tests.
- **Note for the owner:** the Spark plan's free quota for gemini-3.8-flash ran out during a benchmark; a long run will use
  Fast while Balanced cools down. That is the free tier's limit, not a fault.

### 717b4be · 2026-11-25 01:10 · Game benchmark (five browser games, play-tested)
- **Why:** owner's request for a game creation benchmark. Games are where the owner's real runs went wrong (a Snake game whose
  snake moved but ignored the keys).
- **Change:** `bench/hybrid/games`: gm01 Snake from a spec (GAME.md) with 6 play tests; gm02 a paddle game whose keys go to a canvas
  that never has focus, plus a paddle that leaves the court; gm03 pause (P) and a best score in localStorage added to a working Snake
  without breaking its 6 tests; gm04 tic-tac-toe with a computer opponent with exact rules; gm05 a Restart that stacks game loops.
  Each has a reference fix. Fairness is proved with the app's own play-test script (`PageSteps.script`, dumped by
  `GamePlayScriptDump`) in Chromium (`check_games.js`): before the fix 0/6, 1/3, 6/8, 0/5 and 2/3 tests pass; after it, all of them.
  Models → Hybrid AI → **Run the game benchmark**; a task passes when the app's checks pass (the play tests run there), the game
  code changed, and the play tests are as given.
- **Files:** bench/hybrid/make_games.py, check_games.js, games/ (tasks, zips, solutions), README.md; core/HybridBenchTasks.kt
  (`GAMES`), core/HybridBenchGameFiles.kt (generated), HybridBench.kt (`Suite`), HybridScreen.kt.
- **Tests:** HybridBenchGamesTest (3), GamePlayScriptDump (every play test parses; dumps the scripts when asked). 1,266 tests.

### 64de562 · 2026-11-25 08:00 · Benchmark "Hybrid helps my strong model" (Cascade), never Cloudflare
- **Why:** owner's request: test the hybrid models as the weaker models that help a stronger model's API, and not Cloudflare (it
  billed the owner $450 past its free allowance).
- **Change:** the benchmark card has a **Hybrid helps my strong model** switch for all three suites. The run then uses the Cascade
  workflow with Hybrid AI as the draft model and the strong model picked in Settings → Workflow (else the strongest other provider
  with a key) as the strong one: Gemini Nano does the cheap steps on the phone, its handoffs go to the strong model's API instead
  of Gemini's cloud, and the strong model reviews and finishes the work. It refuses to start when the strong model is missing, is
  Hybrid AI or the demo, has no key, or is Cloudflare. The person's workflow, draft and strong choices are put back afterwards.
  The report title, the zip name (`…-helping-…`) and the report header name the strong model.
- **Files:** HybridBench.kt (`helperStrong`, `helperProblem`, `helping`), core/HybridBenchTasks.kt (`strongProblem`),
  AppSettings.kt (`savedSlot`, `restoreSlot`), HybridScreen.kt.
- **Tests:** HybridBenchGamesTest (the strong model must be another provider's API with a key, never Cloudflare). 1,267 tests.

### (next) · 2026-11-25 · A more reliable Gemini Nano: insert by line, no repeated lines, offline stays on the phone
- **Evidence:** the owner's touch-controls test on the Snake game (2026-11-25): with the cloud it worked; on the phone alone Nano
  failed twice: it searched for `{ ... }` (not in the file), then for `canvas` (many matches), then its replacement repeated the
  next line and declared `ctx` twice (caught and put back), and after two failures it handed off with no connection, so the request
  ended on `android_getaddrinfo failed: EAI_NODATA`. Benchmark runs also had Nano name tools `membaca_file` and `讀取文件`.
- **Change:** Nano's instructions say to add new code with `edit_lines` inserted after a line number (start = N+1, end = N), copying
  nothing, and to search with whole lines, never "..."; the tool list keeps edit_lines' insert sentence (it was cut off). A search with
  "..." gets a reply that says why it cannot match and what to do instead. When a replacement starts with the search text and then
  repeats declaration lines that already follow it in the file (const, let, var, function, class, import, def), the repeats are left
  out. With no internet connection Nano carries on as in Private mode instead of handing off, and its replies say "No internet
  connection, so this stayed on the phone." A tool Nano names in other words (another language, camelCase) runs as the PocketIDE
  tool its arguments fit. In "Hybrid helps my strong model" runs the report counts the strong model's steps.
- **Files:** core/SmallLocal.kt, core/Tools.kt (`smallCatalogue`), core/EditBlocks.kt (`withoutRepeats`, the "..." reply),
  HybridAgent.kt (`online`, `phoneStep`, `toolFromArgs`), core/HybridBenchTasks.kt (`steps`, `Counts.other`, report).
- **Tests:** NanoEditsTest (5), HybridAgentLoopTest (offline Nano keeps going; a tool named in another language runs),
  HybridBenchGamesTest (the strong model's steps are counted). 1,275 tests.

---

## Outside the code

Changes and decisions that are not in a commit, kept here so the record is complete.

- **Firebase project** `pocketide-c4cae` (Spark plan). Owner to do in the console: register the judges' App Check debug token,
  turn on Anonymous sign-in, create Firestore with the rules in `HACKATHON.md`, and add the Remote Config keys if desired.
- **Credentials:** `google-services.json` is never committed (CI secret `GOOGLE_SERVICES_JSON`); the judges' App Check token is
  given at build time and never committed. A service-account key shared during the hackathon was not committed or kept; the
  owner should delete it in the Firebase console (Project settings → Service accounts).
- **Release:** the judges' APK is to be published by the owner as GitHub release `v1.5.183-hybrid-judges` (this session cannot
  create releases), rebuilt from the latest commit with `-PjudgeAppCheckToken`.
- **Free quota and judging:** the Gemini Developer API free tier's request limits are per Firebase project, so judges share
  them with every benchmark run. The night of 2026-11-24 used up `gemini-3.8-flash`'s quota (runs from hard run 5 on used Fast
  instead). The app keeps working on Fast, but heavy runs should stop well before the judging period (2026-12-10 to 12-16), and
  more quota can be asked for through the hackathon's quota form (sprint 3 closes 2026-11-25, 10 AM PT).
- **Version label:** the app still reports 1.5.183-hybrid; builds are told apart by commit, as in the table above.
