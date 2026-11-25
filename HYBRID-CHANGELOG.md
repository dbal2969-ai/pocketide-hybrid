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
| 1124-2111 (run 5) | `e61aafe` | 8 of 8 | 25 of 57 | 01 done in 2 steps and 08 in 8 (were 7 and 19); 05 held on a Run/Skip card for `apk add` until the owner tapped Run, then ended "unfinished files: app.js" with the stand-in gone; 06 ended "stopped or failed" with no reason in the log | `(next)` |

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

### (next) · 2026-11-24 · Fixes from benchmark run 5 (8 of 8)
- **Evidence:** run 1124-2111 and the owner's screenshot of the held Run/Skip card (task 05).
- **Change:** during the benchmark, a command that always needs the person's OK (here `apk add`, which uses the network) is
  skipped instead of waiting, the model is told it was skipped, and the chat log notes it. Files marked unfinished are checked
  again as they are now before the finish check and the verdict (edits with `edit_lines` did not update the record, so app.js
  stayed "unfinished" after the stand-in was gone and `node test.js` passed). A run that only wrote notes is not asked to test
  them ("nothing was checked after the last change"). An error that ends a request is now written to the chat log, so a
  "stopped or failed" ending says why.
- **Files:** ChatPane.kt (`askRunCard`, `showError`), NativeAgent.kt (`refreshStubs`, verification gate).
- **Tests:** HybridAgentLoopTest (a stand-in removed with edit_lines; fails on the old code). 1,229 tests.

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
- **Version label:** the app still reports 1.5.183-hybrid; builds are told apart by commit, as in the table above.
