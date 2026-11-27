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
| hard helping 1125-0743 | `7bc42e7` | **10 of 10**, all ended done | 26 on the phone (the report did not count the strong model's steps) | first "Hybrid helps my strong model" run, with `kilo/recursive/flint-v1.0:free` as the strong model: every handoff went to it and every task passed; h08 Private stayed on the phone (13 steps). Nano asked for a tool named `membaca_file` (h07) | `316fd34` |
| local 1125-0955 (local run 1) | `e379bb8` | **12 of 20** (Gemini Nano alone) | 115 of 115 | Find and explain 0 of 4, but Nano's answers were right ("rules.js"; "it passed"): "Change nothing" made the app take each as a task and end it "Nothing was changed", and final answers were not in the chat log. Nano's own failures: a search for a block's first line or a line's start with a whole new block or line as the replacement (l08 syntax error, l11 doubled keys); edit_lines 3–5 used to insert, deleting three cases (l13); a module without module.exports (l17); it tried to edit test.js (refused). 32 refused calls, 4 repeated reads, 0 unusable replies. l02's one Nano call took 548 s (the app was off screen) | `1ababce` |
| local 1125-1040 (local run 2) | `1ababce` | **19 of 20** | 116 of 116 | every skill but one at 100%: Find and explain 4/4 (was 0/4), Add new code 6/6, New file 2/2, Two files 3/3; refused calls 32 → 12. Only l08 failed: told "label gave Unknown Name (30)", Nano added fallbacks twice and never read test.js to learn the property is `name` | `33dd884` |
| local 1125-1111 (local run 3) | `33dd884` | app 18 of 20; **analysis 16 clean** | 116 of 116 | l08 now passes (Nano read the test). The full analysis found two passes that were wrong: l05 `return 5;` (the visible test's answer, hard-coded) and l13 `Default:` (a label, not the default case). Fails: l12 replaced the line it meant to insert after (ArrowRight lost, tap added twice); l19 left the old module.exports after the new one. l18 was right but ended "getScore still appears" because the test names the old name | `fb9312f` |
| scratch 1125-1218 (from-scratch run 1) | `3a8cd77` | app 4 of 10; **analysis 3 clean, 1 with problems** | 26 of 125 | Four requests died on quota or busy errors with Best never tried; Nano in the Chat home took leftover files for "an isometric RPG"; after Build this Nano only answered (s10); the tip calculator became a Node CLI (s09); the to-do kit was kept with made-up records (s05) | `509f511`, `ce51e65`, `517496e` |
| app 1125-1338 (in-app questions run 1) | `103b58d` | app 3 of 14 | 12 of 19 | Nano was never offered app_help (the project context's file names hid the question), answered from what it thought it knew, and its refusals showed as JSON; the cloud passed app03 and app07 | `517496e` |
| scratch 1125-1527 (from-scratch run 2) | `ea46a32` | app 1 of 10; analysis 1 clean | 6 of 90 | 8 tasks ended when every cloud tier hit its free per-minute limit; 17 of 20 Nano replies were whole pages cut off at the 1,024-token output limit | `5ac38af` |
| app 1125-1555 (in-app questions run 2) | `ea46a32` | **app 10 of 14; analysis 10 clean** | 14 of 16 | app11 matched "OpenAI API Key" to the wrong tick box and Nano claimed a proposal; app05 Nano missed the top match; app04 not taken as about the app; app08 wording not accepted | `5ac38af` |
| scratch 1125-1619 (from-scratch run 3) | `5ac38af` (labelled 1.5.183) | **0 of 10** | 67 of 70 | the day's free cloud quota was used up for every tier: nine requests ended even after waiting; with the cloud gone, Nano alone could not finish any app (pages that load a script.js never written, a calculator showing 0) | `4cb790a` |
| standard 1125-1718 (cloud out of quota) | `4cb790a` | **5 of 8 on Nano Full (E4B) alone**; analysis 5 clean | 100 of 101 | with the cloud out, Nano carried on: 03 failed (`items` used before it was declared), 06 wrote DATES.md from memory and said so, 07 a NaN total; the cloud-out note was repeated under all 97 steps | `5d47c35` |
| reliable 1125-1815 (reliability run 1, full, 3 runs) | `5d47c35` | **116 of 141 runs on Nano Full (E4B) alone**; dev pass@1 91% (pass^3 16 of 20), held-out 75%, Node 87%, Python 0 of 9; analysis 110 clean, 6 with problems, 25 failed | 996 of 996 (median 3.6 s) | 141 edits refused as "NOT SAVED: syntax error": with `all: true` and a function's first line as the search, the replace-all skipped the brace repair; r20 bare-name renames refused as ambiguous; r03 run 2 edited a file on a "do not fix anything" task; l13 run 3 passed its visible test but broke behaviour (dev tasks had no hidden checks on the phone); the Python statements were too short ("port bpo-39057"); 10 rich tasks' hidden tests fail on the phone with the reference fix; 5 analyzer false alarms on report-only tasks | `c2a5662` |
| reliable 1125-2053 (reliability run 2, full, 3 runs, Python included) | `c2a5662` (1.5.187) | **114 of 171 on Nano Full alone**; dev pass@1 88% (pass^3 14 of 20), held-out 54%, Node 86% (114 of 132), Python 0 of 39; analysis 113 clean, 1 with problems, 57 failed | 1,529 of 1,529 | PocketIDE left the screen at 00:33: the last 10 rich runs (and rich_3043 run 2) each waited 10 minutes for AICore's "Background usage is blocked" with no step taken; 16 Nano replies thrown away as "not one JSON action" were the right action in broken JSON (unescaped quotes, `r"..."` values, YAML), and in Private mode each one ended the request (rich_3006 and rich_3067 never edited); every Python edit that broke the tests was a SyntaxError or IndentationError saved as written; Node: l05 and l06 hard-coded the visible test's value (caught by the new dev hidden checks), r14 `'grape',,`, r18 wrong JSON shape, r07 never fixed in 41 steps, r23 edited the wrong helper | `15c2dbd` |
| reliable-python-hybrid 1126-2032 (Python, Nano + cloud, helper on, quick) | `7cd3c58` (1.5.203) | **12 of 13** (requests 3 of 3, rich 9 of 10); analysis 12 clean, 1 fail. The fail (rich_4077) is Nano's fix, the same as the reference fix, which passes all 274 hidden tests here: a test that fails on this phone even with the reference fix slipped past the soundness check. **Gemini Nano wrote 10 of the 13 fixes**: 6 alone through the fix path (requests_6644, requests_7315, rich_2943, rich_3006, rich_3043, rich_4077) and 4 from the cloud's plan (requests_7427, rich_3454, rich_3518, rich_3894); the cloud agent fixed rich_3067, rich_3718 and rich_3882 after the fix path failed | 98 Nano calls in the fix path (1,515 s) and 13 cloud calls (223 s). The report said "0 of 105 steps on this phone": the step counts left out the fix path, and after it every task was handed to the cloud as "this needs the web" for links in the pasted issue text. Cloud steps ran on gemini-3.5-flash-lite ×104 (Balanced was out of quota). Max output 4,048 tokens | `25c4859` |
| reliable-python 1126-1923 (Python only, helper on; stopped by the owner at task 5, partial 4 of 13) | `27f3523` (1.5.201) | **0 of 4** (requests 0 of 3, rich_3894 failed) | 96 of 96 on the phone | First run of the Python fix path. It ran on all 4 tasks (45-113 s each) and picked the block the reference fix changes in all 4 (should_bypass_proxies, request_url twice, _get_signature), but none of Nano's 24 rewrites passed. Each was a real attempt buried in copying slips: docstrings lower-cased, `orgetattr`, `except**ValueError`, `Dir(obj)`, stray non-Latin characters, several SyntaxErrors. The Python note and replace_function now reached all 98 loop prompts (the 1.5.201 fix works), but Nano never chose replace_function. requests_7427 timed out at 12 min | `fa42953` |
| reliable-python 1126-1710 (Python only, helper on, AICore 8K) | `024c4e4` (1.5.199) | **1 of 13 on Nano Full alone** (requests_7315; requests 1 of 3, rich 0 of 10); analysis 1 clean, 12 failed | 309 of 309, 0 unusable replies | Private mode, on-phone limit 22,000, max output 8,000 tokens. 78 refused steps: the main loss was the app, not Nano. After a refused edit, StaleReads blanked the read before it as "changed afterwards" (nothing had changed) or the prompt cut it to a seventh, and the reread was then refused as already shown; Nano said "I cannot see the content of rich/repr.py" (rich_3006, 3043, 3454, 3067). rich_3894 sent `'__qualname__'` for the file's `"__qualname__"` 4 times. rich_3043 and requests_6644 searched the code for text from the test. The app's own check ran test_brokenpipeerror, which fails on this phone with the reference fix (rich_3882 spent its finish budget on it). requests_7427 wrote `if …: return False` on one line outside its function (SyntaxError) | `f29ee37` |
| reliable 1126-1352 (quick, Python helper on) | `a9688e6` (1.5.194) | **41 of 57 on Nano Full alone**; JavaScript 41 of 44 (93%), Python 0 of 13; analysis 39 clean, 2 with problems (l08 edited test.js, restored before the check; l19 page error "module is not defined"), 16 failed | 751 of 751, 0 unusable replies | first run with the Python helper: IndentationError refusals fell from about 2 per Python run to under 1 (10 in 13 runs); the failing-test pointer never fired (0 of 33 failing pytest runs: the assertions are in the test, no source frame); a bare outline made Nano give up (rich_3882: "could you please specify which part of the file") or ask for the file 42 times (rich_2943) | `21ee11e` |
| reliable 1126-0816 (reliability run 3, full, 3 runs, Python included) | `eea1ea0` (1.5.191) | **117 of 171 on Nano Full alone**; dev pass@1 88% (pass^3 15 of 20), held-out 57%, Node 87% (116 of 132), Python 1 of 39 (rich_4077 run 2, the first Python pass); analysis 114 clean, 3 with problems, 54 failed | 1,780 of 1,780 (median 3.8 s, p90 6.0 s), 5 unusable replies (16 in run 2) | 18 of 38 Python fails ended rereading the same file until the steps ran out: an edit missed by a quote (`'` for `"`), the closest-text hint was empty, and the reread was refused ("use the copy you already have") though Nano's short prompt no longer held it; 30 of 54 fails had a not-found edit; the Python syntax check refused 104 broken saves in 14 Python runs and put each file back; l18 run 2 and r14 run 3 edited test.js (the app restored it before checking, so both passes stand); l06 hard-coded 'Hello, Ana!' (caught by the hidden check); r23 edited helper4 again | `0cc011b` |
| scratch 1126-0628 (from scratch, hybrid) | `15c2dbd` (1.5.189) | **9 of 10**; analysis 6 clean, 3 with problems, 1 failed | 93 of 180 | every cloud step ran on Gemini 3.5 Flash-Lite (Balanced was out of its daily quota); s05 passed with an index.html that loads no script (the to-do list does nothing) and s09 with a script looking up #tipResult on a page whose element is id="result" (the app's page checks saw neither); s06 does not keep the list after a reload; s03 failed honestly (a broken inline onclick, finish budget spent); s01 was run again once because PocketIDE left the screen (the 1.5.189 pause worked) | `eea1ea0` |
| reliable-cloud 1126-0731 (cloud only, quick; partial 13 of 57) | `565065e` (1.5.190) | **13 of 13 in the cloud** (dev l01–l13), analysis 13 clean | 0 of 77 | all 77 steps on Gemini 3.5 Flash-Lite; the run stopped at task 14 when every cloud model was out of free quota until 3:00 AM Eastern and saved the partial zip (the 1.5.190 quota stop worked); l01 waited 3 minutes for Balanced's quota answer before Flash-Lite took it | `eea1ea0` |

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

### 316fd34 · 2026-11-25 09:30 · A more reliable Gemini Nano: insert by line, no repeated lines, offline stays on the phone
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

### 4b87ed0 · 2026-11-25 10:15 · Local model benchmark: twenty tiny tasks for Gemini Nano alone, with every exchange recorded
- **Why:** owner's request: benchmarks solely for the local model, to tune it. Gemini Nano's weights cannot be changed by apps
  (AICore manages the model; no fine-tuning or adapters are documented), so what is tuned is how PocketIDE drives it.
- **Change:** `bench/hybrid/local`: twenty tasks in Private mode, five skills: find and explain without changing anything (4), fix
  one line (5), add new code (6, among them a key control and a touch control, the owner's failing case), create a file (2), and two
  files or a request that quotes a line slightly wrong (3). Questions are judged on the last answer and on nothing changing; code
  tasks on `node test.js`; any cloud step fails a task. The report adds a score per skill and counts of Nano's refused tool calls,
  repeated reads, unusable replies and the app's repairs, and its header records the settings used. Each task saves `nano.jsonl`,
  one line per Nano exchange (prompt, reply, tool or why unusable, time, previous step's result). Models → Hybrid AI → **Run the
  local model benchmark**. `check_hard.py` proves the 16 code tasks fail as given and pass with their reference fixes.
- **Files:** bench/hybrid/make_local.py, local/ (tasks, zips, solutions), check_hard.py, README.md; core/HybridBenchTasks.kt
  (`LOCAL`, `Task.skill`, `Nano`, `nano`, `lastAnswer`, report), core/HybridBenchLocalFiles.kt (generated), HybridAgent.kt
  (`recorder`), HybridBench.kt (`Suite.LOCAL`, nano.jsonl, settings in the header), HybridScreen.kt.
- **Tests:** HybridBenchLocalTest (5), HybridAgentLoopTest (every Nano exchange is recorded). 1,281 tests.
- **Next:** after the first run, a tuning matrix (Fast or Full model, temperature, instruction variants with worked examples) run
  overnight, and the winning settings shipped through Remote Config.

### 1ababce · 2026-11-25 11:20 · Fixes from local run 1 (12 of 20): questions that say "change nothing", widened edits, edit_lines that deletes
- **Evidence:** local run 1125-0955 and its nano.jsonl records.
- **Change:** a request that says "change nothing" (or "don't change anything", "make no changes"; not "…nothing else") is a
  question, or run-and-report when it asks to run something, unless one of its sentences starts with an order to change something;
  a change word inside a question ("Which file defines add?") no longer makes it a task. The final answer is written to the chat log.
  When a search stops at the start of a line, or at a block's first line, and the replacement starts with it and covers the rest of
  the line or a whole block ending in its closing brace, the match is widened to cover what the replacement replaces (an insert at
  the top of a block is not widened). An edit_lines call that would delete two or more lines its new text does not keep is refused
  once, with how to insert (start = N+1, end = N); sent again it goes ahead, and requests that ask to remove or replace are not
  checked. Nano's instructions say to end a Node file with module.exports. The test harness decides the request kind as the app does.
- **Files:** core/Intent.kt (`NO_CHANGE`), core/Scope.kt (`asksForChange`), NativeAgent.kt (final answer logged, edit_lines check),
  core/EditBlocks.kt (`covered`, `closing`, `linesLost`), core/SmallLocal.kt, test AgentHarness.kt.
- **Tests:** LocalRunFixesTest (6), HybridAgentLoopTest (a change-nothing question answered on the phone succeeds). 1,288 tests.

### 33dd884 · 2026-11-25 11:35 · Nano reads a failing test before guessing (local run 2, 19 of 20)
- **Evidence:** local run 1125-1040, l08: two guessed fixes, no read of test.js.
- **Change:** Nano's instructions: "If a test fails and its message does not say why, read the test file to see what it expects,
  then fix the code. Never change the test."
- **Files:** core/SmallLocal.kt. **Tests:** LocalRunFixesTest (the instruction is there). 1,289 tests.

### 029c438 · 2026-11-25 12:30 · Full analysis of every benchmark run (analyze.py)
- **Why:** owner's instruction: analyse fails and passes, everything the model does, and the finished work, not the test summary
  (the owner had seen a syntax error in a preview after a run graded all passed). A hand comparison of local run 2's passes found
  l19 had "passed" with `<script src="app.js">` deleted and `</body>` written twice: its test only looked for the Reset button.
- **Change:** `bench/hybrid/analyze.py` (with `browser_check.js`): per task, files changed against the starting project and the
  reference fix; names the work lost that the reference keeps (comments and CSS selectors only noted); syntax of every changed JS,
  JSON and HTML file and HTML structure; the task's own test rerun with the original test; index.html opened in Chromium (console
  and page errors, failed loads) and every play test replayed with the app's own play-test code; the chat log and nano.jsonl read
  for tools, refusals, test edits, handoffs, errors and endings. Verdicts: clean pass, pass with problems, fail. `GamePlayScriptDump`
  dumps the play scripts of every suite. The rule is in CONTRIBUTING-AGENTS.md and the README.
- **Re-analysis of every earlier run:** apart from the failures already known, two passes had problems: local run 2's l19 (the page
  no longer loads app.js) and hard run 4's h01 (the request ended on Google's "high demand" error after the work was done). No other
  pass had a syntax error, a browser error, a failing replayed play test or lost code. Two flags in standard runs 1 and 6 (task 03
  "changed the play test") were the app's save dropping the file's last newline, now ignored.
- **Files:** bench/hybrid/analyze.py, browser_check.js, README.md; test GamePlayScriptDump.kt; CONTRIBUTING-AGENTS.md.

### fb9312f · 2026-11-25 12:50 · Hidden checks, insert_lines for Nano, warnings for doubled exports and capitalised keywords (local run 3)
- **Evidence:** local run 1125-1111 analysed in full: two of the app's 18 passes were wrong (l05 hard-coded the answer; l13 wrote
  `Default:`), and l12, l19 and l18 as in the benchmark history.
- **Change:** the local suite has **hidden checks** (`bench/hybrid/local/hidden/`): inputs the model never sees, run by analyze.py
  on every pass; check_hard.py proves the reference fixes pass them. Nano gets an `insert_lines` tool (after = N, nothing removed),
  mapped to edit_lines, and its instructions say to use it to add code and never to return a test's expected value. After a JS
  write the result warns when the file now sets module.exports more than once, declares a function twice, or has a capitalised
  `Default:`/`Case:`/`Return:` that JavaScript reads as a label. The rename check skips the test the request names as its check.
- **Files:** bench/hybrid/local/hidden/ (16), analyze.py, check_hard.py, make_local.py; core/Doubles.kt (new), core/SmallLocal.kt,
  core/Tools.kt, HybridAgent.kt (insert_lines), NativeAgent.kt (rename check, warnings).
- **Tests:** LocalRun3Test (5). 1,294 tests. Re-analysis with the hidden checks: local run 3 is 16 clean passes, not 18.

### 3a8cd77 · 2026-11-25 13:20 · From-scratch benchmark: empty project and the chat with no project
- **Why:** owner's question: had PocketIDE been tested making a project from scratch, in an empty project or from the chat outside
  a project? It had not: every task so far started from prepared files.
- **Change:** a from-scratch suite of ten tasks: a phone snake game, a calculator, a to-do list, tic-tac-toe against the computer and
  a tip calculator, each in an empty project (Agent mode) and from the chat with no project. For the chat the runner talks first,
  then uses Build this as a project (ChatPane.buildAs, the same path as the button) and sends the build request; during the benchmark
  the plan is approved as the person would, or the request runs without one when no plan comes back. `scratch/checks.js` plays each
  app in Chromium at phone width (errors, 390 px fit, and what the prompt asked for, found by visible text rather than the model's
  names); analyze.py runs it on every task. `scratch/references/` holds an ordinary app for each prompt that passes every check;
  a snake with keys only on the canvas fails exactly the key check, and the owner's own snake was flagged for being 412 px wide.
- **Files:** bench/hybrid/scratch/ (checks.js, references/), analyze.py, README.md; core/HybridBenchTasks.kt (`SCRATCH`, `Entry`),
  HybridBench.kt (`fromChat`, `Suite.SCRATCH`), ChatPane.kt (`buildAs`, plan approval during the benchmark), MainActivity.kt
  (`benchChatHome`, `benchBuildFromChat`), HybridScreen.kt.
- **Tests:** HybridBenchScratchTest (2).

### 103b58d · 2026-11-25 14:30 · PocketIDE knows itself: app_help, app_settings and the in-app questions benchmark
- **Why:** the owner asked whether the assistant knows it is inside PocketIDE and can answer in-app troubleshooting questions,
  change settings with explicit permission, or walk a person through the exact steps, without hallucinating. It could not: the
  prompt held one paragraph about the mode chip and told the model to say "I don't know"; Help & FAQ was never given to any model.
- **Change:**
  - `scripts/gen_app_map.py` reads every setting, menu item and button (with its exact label and the path to it) from the
    screens' source into `core/AppMapData.kt` (138 items). CI and a unit test fail when the map is out of date or names a label
    the source no longer has.
  - New tool `app_help`, in every mode: it searches the map, Help & FAQ, slash commands, workflows, advisor modes and cloud models.
    Words are weighted by how rare they are, with everyday words mapped to the app's own words (dark → light theme, spent →
    usage & cost, voice → 🎤). It returns paths, labels and current values, and names the words that match nothing in the app
    ("Not found anywhere in PocketIDE: "iphone", "ipa""), so a missing feature gets "PocketIDE does not have that" instead of
    invented steps.
  - New tool `app_settings`: `get` lists the settings with their values. `propose` shows an Apply / No card in the chat and
    changes nothing until the person taps Apply; the tool result says so. It only covers tick boxes and choices (including
    Private, the Full model, the cloud model and the chat mode). It never touches API keys, tokens, account details or text boxes,
    and never "Run commands from the assistant without asking". During a benchmark every card declines itself, and each
    decision is written to the chat record.
  - The prompt tells the model to use the tools for questions about the app and to quote paths exactly. Gemini Nano gets the two
    tools and a next-step hint only when the request is about the app, so coding tasks keep their room for results.
  - A request such as "Turn on word wrap in the editor" is now taken as a question, so no project file changes.
  - A new benchmark suite, In-app questions (14 tasks: how-to, troubleshooting, features that do not exist, settings changes, a
    key it must refuse). The runner compares the settings before and after every task. analyze.py checks every quoted label
    and menu path against the app's source.
- **Files:** scripts/gen_app_map.py; core/AppMap.kt, AppMapData.kt (generated), AppHelp.kt, AppSettingsTool.kt, Tools.kt,
  SmallLocal.kt, AppModes.kt, Intent.kt, HybridBenchTasks.kt (`APP`, `Facts.settingsChanged`); AppSettingsBridge.kt,
  NativeAgent.kt (tool handlers, `settingsCard`), HybridAgent.kt, HybridBench.kt (`Suite.APP`, settings snapshot),
  HybridScreen.kt; bench/hybrid/app/project/, analyze.py, README.md; .github/workflows/changelog.yml.
- **Tests:** AppSelfKnowledgeTest (9). AgentTest's tool counts are updated for the two new tools. 1,305 tests, 0 failures.

### 509f511 · 2026-11-25 12:55 · Chat home: Gemini Nano knows there is no project, and leftover files are not sent as one
- **Why:** owner's screenshot from the from-scratch benchmark (to-do list from the chat, before 103b58d). Nano replied "The
  current project is for a local isometric RPG… I will start by creating a new file", tried to write todo.js, and was refused
  ("Not done (read-only)"). There were two causes:
  - Small-model mode replaces the system prompt, so the Chat home note ("no project open… tap Build this") only reached cloud
    models. Nano got "You are a coding assistant working inside the person's project" and the write tools.
  - Every file in the Chat home's hidden folder was sent as the project's context. The folder held files that are not a project,
    and Nano took them for one.
- **Change:**
  - In the Chat home, small models (Nano and local servers) get a note that this chat has no project and nothing can be written,
    so they answer in the reply and point to Build this as a project. Their tool list has no write, edit or run tools, and the
    next-step hint says to answer.
  - Only the Chat home files that the conversation names (an attachment, a file asked about) are sent as context.
- **Files:** core/ChatHome.kt (`LABEL_NOTE`, `SMALL_NOTE`, `SMALL_WRITES`, `named`), core/Tools.kt (`smallCatalogue(chatHome)`),
  core/SmallLocal.kt (`State.chatHome`), HybridAgent.kt (`head`), NativeAgent.kt, ChatPane.kt (Chat home context).
- **Tests:** ChatHomeNanoTest (2), HybridAgentLoopTest.inTheChatHomeNanoIsToldThereIsNoProject. 1,308 tests, 0 failures.
- **Evidence:** owner's screenshot, from-scratch run (s06 to-do list from the chat), 2026-11-25 12:42.

### ce51e65 · 2026-11-25 13:05 · Chat home: see, move or delete the files left in it
- **Why:** the owner says an early build from the chat (before or at the start of the hackathon) wrote a game into the chat with
  no project. They undid most of it because it stopped PocketIDE from switching tabs properly, and stray files may still be
  there. That fits the to-do run where Nano took the chat for "a local isometric RPG".
- **Change:** in the Chat home, the chat's ⋯ menu shows "Files left in this chat (N)…" whenever the hidden folder holds files.
  It lists them, and offers **Move to a new project** ("Files from the chat"; nothing is lost and the chats stay) or **Delete
  them**, which asks for a second confirmation. Nothing happens unless the person chooses.
- **Files:** ChatPane.kt (`moreMenu`, `chatLeftovers`).
- **Tests:** none new (UI only); 1,308 tests, 0 failures.

### 517496e · 2026-11-25 14:10 · Fixes from in-app run 1 (3 of 14) and from-scratch run 1 (4 of 10)
- **Why:** both runs were analysed in full with analyze.py, passes as well as fails.
  - **In-app run 1125-1338:**
    - Nano never got the app tools. `SmallLocal.forApp` tested the whole message, project context included, and the context's
      file names (app.js, index.html) made every request look like a project request.
    - Without the tools, Nano answered from what it thought it knew ("I do not have access to PocketIDE's documentation").
    - In Private mode, two of those refusals were shown as raw JSON.
    - The cloud model answered "blank Preview" from app.js instead of Help.
    - app11 passed with wrong advice ("set the key in your configuration files"), because the check only looked for the word
      "settings".
  - **From-scratch run 1125-1218:**
    - s01, s03, s02 and s08 died on quota or "high demand" errors: Balanced was cooling down, Fast failed too, and Best was never
      tried.
    - s10: after Build this as a project, Nano answered "I have provided the function" and nothing was built.
    - s09: the tip calculator became a Node readline program.
    - s05: the to-do list was finished exactly as the starter kit came, with made-up records and fields. Nano never heard about
      the kit, because the note is in the system prompt its own prompt replaces.
    - The analyzer opened pages from file://, where pages that load ES modules do not run, and looked for scripts at the project
      top level.
- **Change:**
  - **The app looks itself up first.** For a question about PocketIDE, or a request about an API key or token, PocketIDE calls
    app_help itself before any model. For a request to change one of its settings ("Turn on word wrap", "Switch the cloud model
    to Best"), it shows the proposal card first. The step is labelled "⚡ PocketIDE did this step itself (no model)" and is not
    counted as a Nano step. The app tools and the app note now read the person's own words, not the project context.
  - **Recognising questions about the app:** a word that only makes sense about the app (on this phone, the cloud, built-in, API
    key…) counts even next to "my code".
  - **Private mode:** a refusal written as an answer is shown as text, not JSON.
  - **Cloud fallback:** when a cloud tier is out of quota, busy or unavailable, the next tier down answers; when none is left
    below, one above does. A busy model with nothing else to try is asked again once after 8 s.
  - **Nano answering instead of doing:** a request to do something that Nano only answers, before any tool call, is passed on.
    In Private mode, Nano is asked once more to take a first step. This does not apply in the chat with no project.
  - **Web page by default:** an app, game or tool asked for with no language named is a web page (index.html), in the main
    prompt and in Nano's.
  - **Starter kits:** for app kits, the note says to remove the template's sample records and keep only the fields the request
    needs, and it now reaches Nano too.
  - **Analyzer:** `scratch/checks.js` serves the page over http like the Preview does, and analyze.py finds the page in a
    subfolder and resolves scripts relative to it. All five reference apps still pass every check.
  - **Stricter checks:** app01 and app11 now need a real place in PocketIDE (⚙ / Settings › / AI provider).
- **Files:** HybridAgent.kt (`appFirst`, `requestOf`, `answersATaskUndone`, `kitNote`, tier fallback), HybridScreen.kt (label),
  ModelClient.kt, core/AppHelp.kt (`settingValue`, app signals), core/CloudTier.kt (`fallback`), core/Design.kt (`KIT_MARK`,
  `KIT_SAMPLES`), core/Prompts.kt, core/SmallLocal.kt, core/HybridBenchTasks.kt; bench/hybrid/analyze.py, scratch/checks.js.
- **Tests:** ScratchRun1Test (3), HybridAgentLoopTest (+4: theAppLooksItselfUpBeforeNanoAnswers, appFirstOnlyForTheAppAndOnlyFirst,
  aBuildRequestNanoOnlyAnswersGoesToTheCloud, nanoHearsAboutTheStarterKit), AppSelfKnowledgeTest (+1). 1,316 tests, 0 failures.
- **Evidence:** pocketide-hybrid-app-benchmark-1125-1338 and pocketide-hybrid-scratch-benchmark-1125-1218, with their analyses.

### ea46a32 · 2026-11-25 14:30 · Firebase agent skills: audit and fixes; a switch for model tips
- **Why:** the owner asked to add Firebase's agent skills (encouraged by the hackathon) to see how they help and to say so in the
  submission. They also asked for a toggle for suggestions, which "can get to be annoying" (chat model tips, chosen in the
  question that followed).
- **Change:**
  - Firebase agent skills installed for the coding agent (`npx skills add firebase/agent-skills`), pinned in `skills-lock.json`.
    The app was audited against firebase-ai-logic-basics, firebase-remote-config-basics, firebase-security-rules-auditor and
    firebase-auth-basics; the full report is in docs/FIREBASE-AGENT-SKILLS.md.
  - Cloud calls use App Check limited-use tokens (replay protection). The Remote Config key `hybrid_limited_use_app_check_tokens`
    (default true) can turn this off without a new build.
  - Remote Config real-time updates: console changes reach running apps at once.
  - `firestore.rules` (new file, with `firebase.json`): owner-only and create-only as before, now limited to the app's fields, with
    typed values, capped string lengths and the server's timestamp. 14 emulator tests in `scripts/firestore-rules-test`.
    HACKATHON.md points to the file.
  - New setting ⚙ Settings › Assistant › "Show model tips in the chat (suggestions to try another model)". Every model tip
    (recommended model, small-model warnings, the advisor tip) has a "Hide model tips" chip that turns them all off.
  - In-app benchmark app09 now asks to turn word wrap *off*, since it is on by default.
- **Files:** HybridAi.kt (`ai()`), HybridCloud.kt (`KEY_LIMITED_TOKENS`, real-time listener), AppSettings.kt (`modelTips`),
  ChatPane.kt (tip gates, `hideTipsChip`, `tipNote`), MainActivity.kt (setting), core/AppMapData.kt (regenerated),
  core/HybridBenchTasks.kt; firestore.rules, firebase.json, scripts/firestore-rules-test/, skills-lock.json, .gitignore,
  docs/FIREBASE-AGENT-SKILLS.md, HACKATHON.md, bench/hybrid/README.md.
- **Tests:** 1,316 unit tests, 0 failures; Firestore rules 14 of 14 in the emulator (firebase-tools 15.37.0).
- **Evidence:** the skills' checklists (docs/FIREBASE-AGENT-SKILLS.md).

### 5ac38af · 2026-11-25 16:40 · Fixes from in-app run 2 (10 of 14) and from-scratch run 2 (1 of 10)
- **Why:** both runs were analysed in full with analyze.py.
  - **From-scratch run 1125-1527:**
    - Eight tasks ended on "You exceeded your current quota". Every cloud tier hit its free per-minute limit at once, so the new
      fallback had nowhere left to go.
    - 17 of Nano's 20 replies were unusable. Told by 14:10's change to "write_file index.html (with its script) first", Nano wrote
      whole pages in one reply and stopped at the 1,024-token output limit, in the middle of the file.
    - One task ended on "MALFORMED_FUNCTION_CALL" from the cloud model.
  - **In-app run 1125-1555:**
    - app11: "OpenAI API Key" was matched to the tick box that names OpenAI among providers. The tool answered "give true or false"
      instead of refusing, and Nano then claimed it had proposed the key.
    - app05: app_help listed Private first, with five weak matches after it, and Nano said nothing matched.
    - app04: "how much I've spent on AI" was not recognised as a question about the app.
    - app08: the check did not accept "does not currently have".
    - app03: the cloud model offered to propose a setting that only the person may change.
    - The analyzer flagged bold list headings and "on → off" as invented labels.
- **Change:**
  - **Every tier out of quota:** the step waits for the limit to refill and asks the first tier again, at most twice. The wait is
    the time the error names, else 30 s, between 5 and 60 s.
  - **MALFORMED_FUNCTION_CALL:** the step is asked again once, then the next tier answers.
  - **Cut-off replies:** `SmallLocal.cutOff` spots a reply whose JSON never closes. Nano is asked once for a file of at most 40
    lines and told to add the rest with insert_lines. Nano's prompt now says replies stop after about 60 lines, so it should build
    in small files.
  - **API keys:** a key that looks like a secret is refused unless it names a tick box exactly. The refusal starts "NOT proposed,
    and nothing changed" and gives the place to paste the key.
  - **app_help results:** matches weaker than half the best one are left out, the list is introduced as "best match first", and a
    setting only the person may change is marked so.
  - **Other fixes:** spending words now count as questions about the app; the check accepts "does not currently have"; the
    analyzer skips bold headings and treats → as a value change, not a menu path.
- **Files:** HybridAgent.kt (cut-off retry, quota wait, malformed call), core/CloudTier.kt (`retryAfterMs`), core/SmallLocal.kt
  (`cutOff`, `CUT_OFF_NOTE`, prompt), core/AppSettingsTool.kt, core/AppHelp.kt, core/HybridBenchTasks.kt; bench/hybrid/analyze.py.
- **Tests:** ScratchRun1Test (+1), AppSelfKnowledgeTest (+1). 1,318 tests, 0 failures.
- **Evidence:** pocketide-hybrid-scratch-benchmark-1125-1527, pocketide-hybrid-app-benchmark-1125-1555, and their analyses.

### 4cb790a · 2026-11-25 17:10 · When every cloud tier is out of free quota, Gemini Nano carries on; version label 1.5.185
- **Why:** from-scratch run 1125-1619 (on 5ac38af, still labelled 1.5.183) passed 0 of 10. Nine tasks ended on "You exceeded your
  current quota" even after the new waits. The errors lasted from 16:21 to 16:48, so these were daily limits, which a minute's wait
  does not refill. Every request whose step had been passed to the cloud died, though Nano could still work. The reports also kept
  saying 1.5.183, so runs could not be matched to builds.
- **Change:**
  - When every tier is still out after the waits, or the error names a daily limit, the cloud counts as out:
    - until midnight Pacific for a daily limit;
    - for 15 minutes otherwise.
  - Meanwhile Gemini Nano carries on alone, as it does offline, with the note "The cloud models are out of their free quota for
    now, so Gemini Nano carries on alone on the phone." The step that failed is given back to Nano instead of ending the request.
  - Cloud-first mode has no Nano to fall back to and still reports the error.
  - The version is now 1.5.185-hybrid (code 233).
- **Files:** HybridAi.kt (`cloudOutUntil`, `cloudOut`), HybridAgent.kt (`CloudOut`, `CLOUD_OUT_NOTE`, offline rule), ModelClient.kt
  (Nano takes the step back), core/CloudTier.kt (`daily`, `outUntil`), app/build.gradle.kts.
- **Tests:** HybridAgentLoopTest.withTheCloudOutOfQuotaNanoCarriesOn, ScratchRun1Test.run3_aDailyLimitWaitsUntilMidnightPacific.
  1,320 tests, 0 failures.
- **Evidence:** pocketide-hybrid-scratch-benchmark-1125-1619 and its analysis.

### 5d47c35 · 2026-11-25 18:40 · On-device reliability benchmark (Gemini Nano alone), with a real-code Python track from the Kaggle tasks; 1.5.186
- **Why:** the owner asked for a comprehensive benchmark of the on-device model, "as reliable as we can make it", reusing the
  benchmarks written for the Kaggle Gemma 4 competition. Today also showed that the shared free cloud quota runs out, so a benchmark
  that needs no cloud can be run as often as needed.
  - The Kaggle tasks are repo-scale Python issues graded with pytest in Docker, and Gemini Nano is the same model family. In the
    Kaggle measurements, E2B (Nano Fast) solved 5 of 114 sound tasks and E4B (Nano Full) 16.
  - The owner chose to reuse Kaggle's method and also build a real-code Python track.
  - Standard run 1125-1718, made with the cloud out of quota: 5 of 8 on Nano Full alone. The note "the cloud models are out of their
    free quota" appeared under all 97 steps.
- **Change:**
  - **New suite:** `ReliableBench`, "On-device reliability benchmark (Gemini Nano)", always in Private mode. It has three tracks:
    - **dev:** the local suite's 20 tasks, which the app has been tuned on.
    - **held-out:** 24 new Node tasks (`make_reliable.py`) across find and explain, single-line fixes, new code, new files,
      multi-file changes and robustness (a stale quote, a 45-function file, report only).
    - **Python:** 13 real single-file fixes from Textualize/rich and psf/requests, taken from the competition's public task list
      and given "located" (`make_python.py`).
  - **Sound tasks:** every task's check fails as given and passes with the reference fix (`check_hard.py`, `make_python.py`). The
    phone also tries each reference fix before the model starts, and leaves out a task its own package versions break.
  - **Hidden checks:** the model never sees them. Node tasks get `hidden.js`; Python tasks run other test files. Their results go to
    `Facts.hiddenExit`.
  - **Runs:** Quick runs each task once. Full runs each task 3 times, round by round. The report adds pass@1 and pass^3 by split,
    track and skill, the tasks that passed only sometimes, damage caught by the hidden checks, and runs that ended "done" but
    failed.
  - **Python in Linux:** Python, pytest and the packages are installed once with apk. If that fails, the track is left out and the
    report says why.
  - **analyze.py** reruns the Node and Python checks (`ANALYZE_PYTHON`), takes each run's verdict from its own check.txt, and
    labels the runs.
  - **Other:** the cloud-out note is said once per request; `Task.judge` is now the last parameter (with `startFiles`, `hidden`,
    `hiddenCommand`, `solution`, `split` and `track` before it); the version is 1.5.186-hybrid.
- **Files:** core/ReliableBench.kt, core/HybridBenchTasks.kt, HybridBench.kt (`Suite.RELIABLE`, `reliableTasks`, `pythonReady`,
  `soundHere`, `hidden`), HybridScreen.kt, HybridAgent.kt; app/src/main/assets/bench/reliable/ (node.json, python.json,
  python/*.zip); bench/hybrid/make_reliable.py, make_python.py, reliable/, python/, analyze.py, README.md; app/build.gradle.kts.
- **Tests:**
  - ReliableBenchTest (4): the task data is whole, the judges, the Python tasks, and the repeats and summary arithmetic.
  - check_hard.py: 20 of 20 held-out fix tasks are fair.
  - make_python.py: 13 of 16 Kaggle tasks are sound; 3 were left out (two depend on the pygments version, one on a test helper).
  - analyze.py was checked on a synthetic run.
  - 1,324 unit tests, 0 failures.
- **Evidence:** Kaggle task_features.csv and small_model_summary.csv (E2B 5 of 114, E4B 16 of 114); pocketide-hybrid-benchmark-1125-1718.


### c2a5662 · 2026-11-25 20:50 · Fixes from reliability run 1 (116 of 141, Nano alone); 1.5.187

- **Why:** the first full reliability run (pocketide-hybrid-benchmark-reliable-1125-1815, 44 tasks × 3 runs on Gemini Nano Full
  (E4B), Private mode, build 1.5.186). Every transcript, check.txt, file set and report line was read; the findings are in the
  benchmark history row above.
- **Replace-all keeps the edit repairs** (core/EditBlocks.kt `applyAll`): the main failure. Nano searched for a function's first
  line with `all: true` and gave the whole new function as the replacement. `apply` repairs that case (`covered()` widens the
  match to the old block) but `applyAll` did a plain replace, so the old body stayed after the new one, the file failed its syntax
  check and the edit was refused (141 refusals). Now one match goes through `apply` with all its repairs, and each of several
  matches is widened the same way.
- **Renames of a bare name** (NativeAgent edit_file, `EditBlocks.renameAll`): when the request says "rename" and the search and
  replacement are each one identifier, an ambiguous match renames every whole-word use (`MAX_HP` → `MAX_HEALTH`, not
  `MAX_HP_BONUS`). Without a rename request an ambiguous search is still refused, as before (r20).
- **"Do not fix anything", "fix nothing", "only report"** now count as no-change requests (core/Intent.kt), so the edit is refused
  and Nano answers (r03 run 2).
- **Dev hidden checks on the phone** (ReliableBench `devTasks(hidden)`, assets/bench/reliable/dev_hidden.json written by
  make_reliable.py): the 20 dev tasks now run a hidden check as well as test.js, so a fix that passes the visible test but
  breaks behaviour fails (l13 run 3 replaced the default case with a label).
- **Python track:** the prompt now includes the failing test's code (make_python.py extracts new or changed test functions with
  ast), because statements like "port bpo-39057" told Nano nothing. Hidden tests that fail on the phone even with the reference
  fix are deselected (HybridBench `soundHere`), so the 10 rich tasks left out in run 1 can be scored; `ReliableBench.reason()`
  gives each failure's reason in the report, and the report counts every result.
- **analyze.py:** report-only tasks no longer run test.js (5 false "with problems" verdicts in run 1).
- **Files:** core/EditBlocks.kt, NativeAgent.kt, core/Intent.kt, core/ReliableBench.kt, HybridBench.kt,
  app/src/main/assets/bench/reliable/ (python.json, dev_hidden.json); bench/hybrid/make_python.py, make_reliable.py,
  python/python_tasks.json, analyze.py; app/build.gradle.kts (1.5.187-hybrid, code 235).
- **Tests:** ReliableRun1Test (new: replace-all with a first-line search, renames, no-change wording, dev hidden checks);
  HybridAgentLoopTest `nanoRenamesABareNameWithoutAllInARenameRequest`, and `repeatingAFailedStepHandsTheRestToTheCloud` now
  repeats a search that is not a bare name. 1,328 unit tests, 0 failures.
- **Evidence:** pocketide-hybrid-benchmark-reliable-1125-1815.


### 9811c6a · 2026-11-25 22:45 · Interrupted benchmark runs keep their finished tasks, and resume; 1.5.188

- **Why:** the owner asked that an interrupted run not be a loss: the tasks that finished should still give a zip, and the task
  that was running should be rotated when the run carries on. A full reliability run is 141 tasks over an evening, and until now a
  run cut off at task 120 (PocketIDE closed, Android stopping it, the phone restarting) saved nothing and left the benchmark's
  settings (Hybrid AI, Single model, Agent mode, a bench project) in place.
- **Progress is saved after every task** (core/BenchResume.kt, HybridBench `Run.save`): run.properties in the run's results folder
  (the task order, the finished ids, the task running now, the person's settings and project from before the run, and the
  reliability suite's left-out tasks and deselected tests), and result.properties beside each task's check.txt.
- **Cut off (PocketIDE closed or killed):** when PocketIDE next starts (MainActivity → `HybridBench.recover`), a run still marked as
  going on is treated as interrupted. The settings and project are put back, report.md says how far it got, the finished tasks
  are zipped as `pocketide-<suite>-benchmark-<stamp>-partial-<done>of<total>.zip` in Downloads/PocketIDE, and a dialog offers
  Resume.
- **Stopped by the person:** while a run goes on, any benchmark button reads "tap to stop" and offers "Stop and save": the running
  request is stopped, and the same partial zip is made.
- **Resume:** the same benchmark button offers "Resume the interrupted run?" (or "Start a new run", which drops it). Resuming keeps
  the run's stamp, folder, results, Python checks and settings snapshot, and runs the rest. The task that was running when it was
  interrupted goes to the back of the queue (rotated), since it may be what stopped the run; a task the run was interrupted during
  twice is recorded as failed ("interrupted during this task 2 times") so a run cannot get stuck on it. The final zip has every
  task, as a run that was never interrupted.
- **Files:** core/BenchResume.kt (new), HybridBench.kt (`resume`, `stop`, `recover`, `paused`, `abandon`, `tasksFor`,
  `savedResults`, `reportText`, `savePartial`; the report, zip and Downloads helpers moved out of `Run` to be shared), HybridScreen.kt
  (stop and resume dialogs), MainActivity.kt; app/build.gradle.kts (1.5.188-hybrid, code 236).
- **Tests:** BenchResumeTest (4): the progress file round-trips (tabs, newlines, commas in pytest ids), an interrupted task moves to
  the back, a task interrupted twice is given up, an interruption between tasks charges no task, saved results and the partial zip's
  name. 1,332 unit tests, 0 failures. Not yet tried on the phone.


### 15c2dbd · 2026-11-26 05:20 · Fixes from reliability run 2 (114 of 171): Nano's broken JSON is read, Python syntax is checked on save, the benchmark pauses off screen; 1.5.189

- **Why:** reliability run 2 (pocketide-hybrid-reliable-benchmark-1125-2053, build 1.5.187, 57 tasks × 3 runs, Python
  included). Every run's report row, check.txt, transcript and nano.jsonl was read and analyze.py reran every check; the findings
  are in the benchmark history row above.
- **Nano's actions in broken JSON are read** (core/LenientJson.kt, SmallLocal `obj`): when strict parsing fails, the reply is
  repaired (a double quote inside a string is escaped unless JSON structure follows it, an `r"..."` value loses its `r`, escapes
  JSON lacks such as `\w` are doubled, line breaks in strings are escaped), each `{` that opens a key is tried when code with
  braces comes first, and an action written as YAML (`reason:`, `tool:`, `tool_args:` with indented keys or `|` blocks) is read.
  All 16 of the run's thrown-away replies are now read (test data: app/src/test/resources/nano/unusable-1125-2053.jsonl).
- **Private mode asks again** (HybridAgent): a reply that still cannot be read no longer ends the request; Nano is asked once more
  (SmallLocal.ASK plus `QUOTES_NOTE`, how to write a quote and a line break inside a JSON string).
- **Python syntax is checked when a file is saved** (core/SyntaxCheck `pyCommand`/`pyResult`, SyntaxChecker `shell`,
  TerminalPane `quietExec`, ChatPane): the text is parsed with Python's ast module in PocketIDE's Linux (sent as base64, up to
  90,000 characters), and `.py` joins the guarded files, so a write that breaks a working Python file is not kept and Nano is told
  the line and error, as for JavaScript. Without Linux or Python the check is skipped.
- **The benchmark pauses when PocketIDE leaves the screen** (HybridBench `onScreen`, `leftScreen`, `onceOnScreen`; MainActivity
  onStart/onStop): Gemini Nano only runs for an app on screen. After 15 s off screen the task is stopped, nothing is scored, a toast
  says the run is paused, and the same task runs again in a fresh project once PocketIDE is back (also when AICore's "Background
  usage is blocked" shows up in a task's log), up to 3 times per task. The report lists the tasks that were run again.
- **Found, not changed (Nano's own mistakes):** l05 and l06 returned the visible test's expected value (`return 5;`,
  `'Hello, Ana!'`); the dev hidden checks now catch this. r14 left `'grape',,` (valid JavaScript, so no syntax error); r18 wrote
  the enemies as one string; r07 never changed `level + 1` in 41 steps.
- **Files:** core/LenientJson.kt (new), core/SmallLocal.kt, HybridAgent.kt, core/SyntaxCheck.kt, SyntaxChecker.kt, TerminalPane.kt,
  ChatPane.kt, HybridBench.kt, MainActivity.kt; app/build.gradle.kts (1.5.189-hybrid, code 237).
- **Tests:** LenientJsonTest (5): all 16 real replies, unescaped quotes, raw strings and bad escapes, YAML, valid JSON unchanged;
  HybridAgentLoopTest `privateModeAsksAgainAfterAReplyThatIsNoAction`. 1,338 unit tests, 0 failures. The Python check and the off-screen
  pause need the phone.
- **Evidence:** pocketide-hybrid-benchmark-reliable-1125-2053.


### 565065e · 2026-11-26 06:45 · Cloud-only and side-by-side reliability benchmarks; "Stop and save" no longer crashes; 1.5.190

- **Why:** the owner asked for a cloud-only benchmark and then for the full benchmark with both cloud and local; and reported a
  crash on "Stop and save" (1.5.189, Galaxy S24+, Android 17): `WindowManager$BadTokenException: Unable to add window -- token …
  is not valid; is your activity running?` at `HybridBench$Run.stop`. The run had kept the PocketIDE screen it started on; Android
  had rebuilt the screen since, and the "stopped" dialog was shown on the old one. The partial zip was already saved.
- **The crash:** HybridBench now tracks the screen that is showing (`live`, set in MainActivity onStart, cleared in onDestroy).
  Every benchmark dialog goes through `alert()`, which uses that screen and falls back to a toast instead of crashing; a run
  (`Run.act`) follows the rebuilt screen, and Stop stops the request on both the old and the new screen.
- **Cloud only** (Suite `RELIABLE_CLOUD`, `ReliableBench.forCloud`): the reliability tasks and checks with every step on Gemini in
  the cloud (Cloud first, so Nano never runs, and the cloud-out fallback to Nano does not apply). The checks are the tasks' own,
  except that a step on this phone fails the run (`ON_PHONE`) instead of a step in the cloud. Buttons "Cloud only: quick" and
  "Cloud only: full (3 runs)"; the dialog estimates the requests (6 to 10 per run).
- **Nano + cloud side by side** (Suite `RELIABLE_BOTH`, `ReliableBench.both`, `sideBySide`): each run of each task on Gemini
  Nano (Private mode), then right after it in the cloud (id `…#2@cloud`), so both see the same phone, hour and packages. The
  report has each engine's reliability section, a table of pass@1 by split, track and skill for both, the tasks only one engine
  passed every run, and the median time per run. Buttons "Nano + cloud: quick" and "Nano + cloud: full (3 runs)".
- **Out of quota:** when every cloud model is out of free quota during a cloud run, the run stops without scoring that task (it is
  not charged as an interruption), the finished tasks are zipped, and Resume carries on once the quota resets.
- **analyze.py** knows both new report titles and reads `@cloud` runs as their task.
- **Files:** HybridBench.kt, HybridScreen.kt, MainActivity.kt, core/ReliableBench.kt, bench/hybrid/analyze.py,
  app/build.gradle.kts (1.5.190-hybrid, code 238).
- **Tests:** ReliableBenchTest `cloudOnlyChecks` and `sideBySide`. 1,340 unit tests, 0 failures.


### eea1ea0 · 2026-11-26 08:10 · Page wiring checks; the report names the cloud models that really ran; 1.5.191

- **Why:** the from-scratch run 1126-0628 (1.5.189) and the first cloud-only run 1126-0731 (1.5.190, stopped by the quota after 13 of
  57). Both zips were read in full and analyze.py reran every check; the findings are in the benchmark history rows above.
- **Pages wired up wrong are found** (core/PageRefs.kt `wiring`, part of `problems`, so the model is told to fix them after its
  work, and the app's ✓ outcome needs them fixed): a page with no script at all while a script sits beside it that nothing loads
  (s05), and a script a page runs looking up an id (getElementById, querySelector('#…')) that no page or script gives (s09). Ids
  a script creates (el.id = …, setAttribute, id="…" in a string) count.
- **The from-scratch check uses it too** (HybridBenchTasks `built`): a page that is wired up wrong fails the task, even if the app's
  checks passed.
- **The report names the cloud models that answered** (HybridBench `reportText`): "cloud steps actually ran on: gemini-3.5-flash-lite
  ×77", read from the transcripts, beside the setting (both runs said Auto (Balanced) while Balanced was out of its daily quota).
- **Files:** core/PageRefs.kt, core/HybridBenchTasks.kt, HybridBench.kt; app/build.gradle.kts (1.5.191-hybrid, code 239).
- **Tests:** PageWiringTest (3): the run's own s05 and s09 pages (app/src/test/resources/pages/), fixed versions and script-made
  ids pass, and no false alarm on any benchmark project or starter. 1,343 unit tests, 0 failures.
- **Evidence:** pocketide-hybrid-scratch-benchmark-1126-0628, pocketide-hybrid-reliable-cloud-benchmark-1126-0731-partial-13of57.


### f2326ea · 2026-11-26 08:30 · The total benchmark: every suite in one run; 1.5.192

- **Why:** the owner asked for a total benchmark.
- **What it does** (HybridBench `startTotal`, `Total`, `totalReport`; core/BenchTotal.kt): one button, "Total benchmark (every suite)",
  opens a dialog to pick the suites (Standard, Hard, Games, Local model, From scratch, In-app questions, all ticked), the reliability
  suite (Nano and the cloud side by side, the default; Nano only; Cloud only; or left out), quick or full reliability, and the Python
  track. The suites run one after another, each exactly as on its own and into its own folder inside the total's folder, with its own
  report.md. The zip (`pocketide-hybrid-total-benchmark-<stamp>.zip`) has a report.md at the top with a line per suite (passed, pass
  rate, steps on the phone and in the cloud, folder) and all suites together.
- **Stop, Resume and a closed PocketIDE work across the whole run:** total.properties keeps which suite it is on, and the suite that
  was running keeps its own progress. Stop and save (or the cloud quota stopping a cloud reliability run) zips everything so far as
  `…-partial-<suites done>of<suites>.zip`; the total button then offers Resume, which carries on with that suite where it stopped. If
  PocketIDE was closed mid-run, the next start puts the settings back, zips everything so far and offers Resume.
- **analyze.py** reads a total zip (it has total.properties) suite folder by suite folder, and writes one analysis with a section per
  suite.
- **APK size:** debug builds are now packaged from scratch (the old APK is deleted before each build), so sizes compare between
  versions. 1.5.190's APK was 1.49 MB bigger than 1.5.191's only because the incremental packager had left two unused gaps where old
  copies of classes8.dex and classes6.dex had been; the files inside were the same.
- **Files:** core/BenchTotal.kt (new), HybridBench.kt (`Run` takes the suite's folder and its total; `savePartial` can skip the
  zip), HybridScreen.kt, bench/hybrid/analyze.py; app/build.gradle.kts (1.5.192-hybrid, code 240).
- **Tests:** BenchTotalTest (2): the progress file round-trips and says when it can be resumed; the summary table with a stopped and
  a not-yet-run suite. All unit tests pass; analyze.py was tried on a total zip made of the runs 1126-0628 and 1126-0731. The total
  run itself needs the phone.


### 957266a · 2026-11-26 11:40 · Judges' release workflow; PocketSWE prompt tried in Nano's prompt and taken back

- **Why:** the owner asked to finish the open hackathon work; the judges' build (HACKATHON.md, "Try it" step 1) had no release yet,
  and it needs an App Check debug token that must stay out of git and out of chats.
- **Judges' release** (.github/workflows/judges-release.yml, run by hand with a tag such as judges-1.5.192): checks the
  GOOGLE_SERVICES_JSON and JUDGE_APPCHECK_TOKEN repository secrets, runs the unit tests, builds the APK packaged from scratch with
  the token (app/build.gradle.kts reads JUDGE_APPCHECK_TOKEN), and publishes it on the Releases page with install notes.
- **Tried and taken back (not committed):** the working method of the Kaggle entry PocketSWE (0.06 with E4B: the most specific name
  first, read the test before the fix, short searches, no repeated failed edit, check the change against the request) written into
  Gemini Nano's system prompt (core/SmallLocal.kt). It made the prompt about 700 characters longer, and HybridAgentLoopTest
  `aMidSizedResultIsShownToNanoWhole` failed: an 8,292-character file no longer fitted Nano's 14,000-character prompt whole. Its
  benefit was unmeasured (PocketSWE ran the full model on a GPU, in another harness), so the change was reverted.


### 0cc011b · 2026-11-26 13:10 · Gemini Nano is shown the text it needs when it rereads a file; 1.5.193

- **Why:** reliability run 3 (pocketide-hybrid-reliable-benchmark-1126-0816, 1.5.191, 57 tasks × 3 runs, Python included). Every
  report row, check.txt, transcript and nano.jsonl was read and analyze.py reran every check; the findings are in the benchmark row.
- **The closest-text hint tolerates other quotes and spacing** (core/EditBlocks.kt `closest`, `similarLine`, `similarity`): when a
  search is not found, the most specific line of it is matched ignoring quote style (' " `) and spacing, then by similarity (Dice on
  character pairs, at least 0.6). In rich_3067 Nano wrote r'(?P<url>…' for the file's r"(?P<url>…", and the hint came back empty.
- **Nano's rereads get text, not a refusal** (NativeAgent `smallReread`, `smallMissed`, `nanoLike`): when Nano has no cloud to hand
  to (small local model mode, or Hybrid AI in Private mode or with the cloud out of quota), a reread of an unchanged file shows the
  numbered lines its last missed edit was aimed at (`EditBlocks.excerpt`, 12 lines either side), or else the file again, up to twice
  a file. With the cloud available, rereads stay refused so HybridAgent still hands the rest to the cloud after two.
- **Wording:** a refused broken write now says the file "still works" instead of "the game keeps working".
- **Seen, not changed:** the Python syntax check worked (104 refusals, every file put back); a def line replaced by the whole function
  (the Python form of the edit the JavaScript repair fixes) was only 4 of 89 syntax refusals, so no repair yet.
- **Files:** core/EditBlocks.kt, NativeAgent.kt, core/SyntaxCheck.kt; app/build.gradle.kts (1.5.193-hybrid, code 241).
- **Tests:** ReliableRun3Test (3), with the run's own rich/highlighter.py and Nano's search
  (app/src/test/resources/nano/rich_3067/); HybridAgentLoopTest `nanosRereadAfterAMissedEditShowsTheLines`. 1,349 unit tests, 0
  failures.
- **Evidence:** pocketide-hybrid-reliable-benchmark-1126-0816.


### a9688e6 · 2026-11-26 13:50 · The Python helper for Gemini Nano (optional); 1.5.194

- **Why:** the owner asked for a way to make Gemini Nano competent with Python, as an optional toggle. PocketSWE (the Kaggle entry,
  full E4B on a GPU) resolved about 6% of hidden tasks on Kaggle and 16 of 114 practice tasks; Nano in PocketIDE passed 1 of 87
  Python runs, with the file named for it. Run 1126-0816 showed why: 80 of 104 refused Python saves were IndentationErrors, Nano
  read files 402 times and ran the tests 58 times, and the files to fix run to 1,153 lines while Nano's prompt holds about 350.
- **The setting:** Settings › Assistant › "Python helper for Gemini Nano" (`AppSettings.pythonHelper`, off by default). It applies to
  Nano's steps (small local model mode, or the Hybrid AI provider) in a project with Python files or a request about Python
  (core/PyHelper.kt `applies`).
- **What it does** (core/PyHelper.kt; hooked in NativeAgent and HybridAgent):
  - a short Python note in Nano's prompt (`NOTE`, about 330 characters): indentation is code, use edit_lines with read line
    numbers, fix the source line the test points to;
  - edits re-indented to the lines they replace (`reindentEdit` for edit_file, `reindentLines` for edit_lines and inserts after a
    block opener). On the run's 76 refused Python edits whose search was in the file, 27 now parse (27 of the 53 IndentationErrors),
    and none got worse; the rest are wrong edits (a def line replaced by a body), still refused by the syntax check;
  - a whole read of a Python file over 300 lines answered with its classes and functions and their line numbers (`outline`);
  - after a failing pytest run, the deepest frame in a source file and the lines of its function (`whereFailed`).
- **Benchmark:** the reliability dialog has "Python helper for Gemini Nano on", so a run with it and one without compare directly;
  the run sets the setting, stores it in its progress (BenchResume `pyHelper`), names it in the report, and puts the person's own
  setting back afterwards.
- **Files:** core/PyHelper.kt (new), NativeAgent.kt, HybridAgent.kt, AppSettings.kt, MainActivity.kt, HybridBench.kt,
  HybridScreen.kt, core/BenchResume.kt, core/AppMapData.kt (regenerated: 140 items); app/build.gradle.kts (1.5.194-hybrid, code 242).
- **Tests:** PyHelperTest (3), including the run's own refused edits (app/src/test/resources/nano/py_edits/) parsed with Python's
  ast module. 1,352 unit tests, 0 failures. Its effect on Nano needs a reliability run with it on.


### 21ee11e · 2026-11-26 16:20 · The Python helper shows code, not just a list, and follows the failing test's calls; 1.5.195

- **Why:** the first run with the Python helper on (pocketide-hybrid-reliable-benchmark-1126-1352, quick, 1.5.194). Every row,
  check and transcript was read and analyze.py reran every check; the findings are in the benchmark row above.
- **Outlines come with the function the request is about** (core/PyHelper.kt `relevantDef`, `callees`, `outline`): the def whose name
  the request (with the failing test's code) mentions most, a function before a whole class, numbered, plus the functions in that
  file it calls with their lines ("The bug may be in one of them"). On the 12 Python tasks with a changed function, the shown function
  or one it calls is the one the reference fix changes in 6 (2 by name alone); the others never name it.
- **A second whole read of a long Python file shows code** (NativeAgent `pyOutlined`): that function with 60 lines either side, or the
  first 150 lines, instead of the outline again.
- **The failing-test pointer follows the test's calls** (`calledDef`): when pytest's trace has no source frame (the assertion is in
  the test), the functions the failing test calls are looked up in the project's source files and the last one called is shown.
- **Files:** core/PyHelper.kt, NativeAgent.kt; app/build.gradle.kts (1.5.195-hybrid, code 243).
- **Tests:** PyHelperTest `theRelevantFunctionIsTheOneTheFixChanges` (the 13 real tasks; which functions each reference fix changes is
  in app/src/test/resources/nano/py_edits/relevant.json). 1,353 unit tests, 0 failures.
- **Evidence:** pocketide-hybrid-reliable-benchmark-1126-1352.


### 5a145a1 · 2026-11-26 16:25 · Python-only reliability runs; 1.5.196

- **Why:** the owner asked to run just the Python tests, to measure the Python helper quickly.
- **What:** a "Python only: quick" and "Python only: full (3 runs)" pair of buttons in the benchmark section (Suite `RELIABLE_PY`,
  "Python reliability benchmark (Gemini Nano)"): the 13 real Python bug fixes from rich and requests, on Gemini Nano alone in Private
  mode, with the same soundness check, hidden tests, report, resume and zip as the full reliability suite. Its dialog has the
  "Python helper for Gemini Nano on" box, so a run with the helper and one without compare directly. analyze.py reads its report as
  a reliability run (its title ends "(Gemini Nano)").
- **Files:** HybridBench.kt, HybridScreen.kt; app/build.gradle.kts (1.5.196-hybrid, code 244). 1,353 unit tests, 0 failures.


### 236dfb2 · 2026-11-26 16:40 · Gemini Nano tuned from Remote Config; 1.5.197

- **Why:** the owner asked to use Remote Config more: Nano cannot be fine-tuned (Firebase AI Logic does not support tuned models,
  and AICore takes no app-supplied LoRA), so the way to teach it is its instructions, and Remote Config ships those to every phone
  at once without an app update.
- **New keys** (HybridCloud `KEY_NANO_NOTE`, `KEY_PY_NOTE`, `KEY_PY_EXAMPLES`, `KEY_PY_DEFAULT`, `KEY_PY_LINES`; copied into the code
  by `applyTuning()` after each fetch and each real-time update):
  - `hybrid_nano_note`: extra instructions added to Nano's prompt for every request (`SmallLocal.remoteLine`, up to 1,500 characters;
    in small local model mode and in HybridAgent's prompt for Nano);
  - `hybrid_python_note` and `hybrid_python_examples`: the Python helper's note and worked examples after it (`PyHelper.note()`);
  - `hybrid_python_helper_default`: whether the helper is on for people who have not set it (`AppSettings.pythonHelper` reads it
    until the person chooses; saving Settings without changing the box keeps following it, and a benchmark run puts it back unset);
  - `hybrid_python_outline_lines`: the length at which a Python file is outlined (`PyHelper.long()`, 50 to 5,000).
  Every default is the app's built-in value, so nothing changes until a value is published in the console.
- **remoteconfig.template.json** lists all ten keys with their defaults and descriptions; firebase.json points to it, so
  `firebase deploy --only remoteconfig` publishes it.
- **Files:** HybridCloud.kt, AppSettings.kt, MainActivity.kt, HybridBench.kt, NativeAgent.kt, HybridAgent.kt, core/PyHelper.kt,
  core/SmallLocal.kt, remoteconfig.template.json (new), firebase.json; app/build.gradle.kts (1.5.197-hybrid, code 245).
- **Tests:** RemoteTuningTest (3): the defaults are the built-in values, remote values change what Nano sees (and out-of-range ones
  are ignored), and the template lists exactly the app's keys with its defaults. 1,356 unit tests, 0 failures.


### 92a611b · 2026-11-26 16:45 · Everything tunable in Gemini Nano and hybrid routing comes from Remote Config; 1.5.198

- **Why:** the owner asked to use Remote Config for everything it can tune.
- **core/Tuning.kt** (new) holds every tunable value with the app's built-in default; HybridCloud's `applyTuning()` fills it after
  each fetch and each real-time update; a value out of range or not a number is ignored. Fourteen new keys:
  - defaults for people who have not saved their own: `hybrid_nano_temperature` (0.2), `hybrid_nano_top_k` (40),
    `hybrid_nano_max_output_tokens` (1024), `hybrid_nano_full_model` (false), `hybrid_cloud_tier_default` (auto) (HybridAi `settings`,
    `cloudChoice`);
  - thresholds: `hybrid_handoff_after_failures` (2, HybridAgent), `hybrid_quota_waits` (2), `hybrid_busy_wait_ms` (8000),
    `hybrid_cloud_out_minutes` (15, CloudTier `outUntil`), `hybrid_nano_reread_reshows` (2, NativeAgent `smallReread`);
  - switches for the newer repairs, all on: `hybrid_repair_broken_json` (SmallLocal: LenientJson and YAML), `hybrid_nano_ask_again`
    (HybridAgent, Private mode), `hybrid_python_syntax_check` (SyntaxCheck), `hybrid_page_wiring_check` (PageRefs).
- **remoteconfig.template.json** now lists all 24 keys; HACKATHON.md's setup step and Firebase table say what they tune.
- **Removed:** the constants these replace (HybridAgent `FAIL_LIMIT`, `BUSY_WAIT_MS`; NativeAgent `SMALL_RESHOWS`).
- **Files:** core/Tuning.kt (new), HybridCloud.kt, HybridAi.kt, HybridAgent.kt, NativeAgent.kt, core/CloudTier.kt, core/SmallLocal.kt,
  core/SyntaxCheck.kt, core/PageRefs.kt, remoteconfig.template.json, HACKATHON.md; app/build.gradle.kts (1.5.198-hybrid, code 246).
- **Tests:** RemoteTuningTest `tuningValues` (each value changes behaviour; out-of-range values fall back) and the template check now
  covers all 24 keys and their defaults. 1,357 unit tests, 0 failures.


### d871291 · 2026-11-26 17:05 · Larger on-phone limit for phones where AICore allows 8,000 tokens; 1.5.199

- **Why:** the owner's phone lets AICore's Gemma 4 E2B take up to 8,000 tokens instead of the default 4,000. The on-phone limit
  (Models › Hybrid AI) already goes to 30,000 characters, but the setting's warning only spoke of the 4,000-token default, and a
  long read in Private mode was cut wherever Nano's prompt ran out rather than at a clean section.
- **Change:**
  - With no cloud to hand to (Private mode, or the cloud out of quota), one tool result is sized to 55% of the on-phone limit
    (`ContextFit.nanoResultCap`, at least 8,000 characters), so at about 24,000 characters a 13,000-character file is shown whole;
    a longer one arrives as a section with a "read more from line N" note. With the cloud available nothing changes, so a file too
    long for the phone still goes to the cloud (`aFileTooLongForThePhoneIsPassedOnWithTheReason`).
  - Older tool results in Nano's prompt keep a seventh of the limit instead of a fixed 2,000 characters (2,000 at the default
    14,000; about 3,400 at 24,000).
  - The Python outline appears for files longer than the larger of its Remote Config threshold and the result size / 40 lines.
  - The on-phone limit warning names 24,000 characters (`HybridSettings.NANO_8K_CHARS`) as safe once AICore is raised to 8,000.
  - The limit is set per phone, not by Remote Config: phones left at AICore's default would overflow Nano's prompt.
- **Files:** NativeAgent.kt, HybridAgent.kt, core/ContextFit.kt, core/HybridSettings.kt; app/build.gradle.kts (1.5.199-hybrid, code 247).
- **Tests:** RemoteTuningTest `nanoReadsGrowWithTheOnPhoneLimit`. 1,358 unit tests, 0 failures.


### f29ee37 · 2026-11-26 18:35 · Nano keeps seeing the file it read while its edits are refused; 1.5.200

- **Why:** Python-only run 1126-1710 (1.5.199, helper on, on-phone limit 22,000): 1 of 13. In 4 of the 12 fails Nano never saw the
  file after its first refused edit, so it said "I cannot see the content of rich/repr.py" and stopped.
  - StaleReads counted an edit that was not applied as a change, so the read before it became "[Earlier contents … left out: the
    file was changed afterwards]".
  - Otherwise Nano's prompt cut that read to a seventh of the limit.
  - A reread was then refused ("has not changed since the whole file was shown to you above"), which was not true of the prompt.
- **Change:**
  - StaleReads ignores change calls whose result says nothing changed (`notApplied`: edit not applied, Error, Not written).
  - `HybridAgent.currentRead` finds the newest read with every step after it changing nothing. `phonePrompt` gives it the room the
    last (short) result leaves, so Nano edits from the whole file instead of a fragment.
  - EditBlocks matches whole lines that differ only in `'` and `"` (rich_3894: `getattr(obj, '__qualname__', name)` for the file's
    `"__qualname__"`, sent 4 times). Backticks still have to match.
  - An edit whose search text is in another project file and not the one named says where it is. If that file is a test, the
    message says the test shows what the code must do (rich_3043 searched `_export_format.py` for the test's expected HTML;
    requests_6644 searched `adapters.py` for the test's URL).
  - Benchmark: the app's own check after Nano's edits leaves out the tests the scoring leaves out (`Verify.deselect`, set per task;
    only `path::name` ids). rich_3882 had spent its finish budget on test_brokenpipeerror, which fails on this phone even with the
    reference fix.
- **Files:** core/StaleReads.kt, HybridAgent.kt, core/EditBlocks.kt, NativeAgent.kt, core/Verify.kt, HybridBench.kt;
  app/build.gradle.kts (1.5.200-hybrid, code 248).
- **Tests:** NanoSeesItsReadTest (new, 4 tests): the refused edit leaves the read in place; the whole read stays in Nano's prompt;
  quote-only differences match but backticks don't; the check's deselect list, with unsafe ids dropped. 1,362 unit tests, 0 failures.
- **Evidence:** pocketide-hybrid-reliable-python-benchmark-1126-1710.


### 268eaa4 · 2026-11-26 19:15 · Python fix path: the app finds the block, Nano rewrites it whole, the test picks; 1.5.201

- **Why:** the owner asked for the techniques small models use on real bugs. In Python run 1126-1710, 78 of Nano's 309 steps were
  refused, most of them search text that did not match the file. Two published ideas fit an on-phone model:
  - Agentless (Xia et al. 2024): the app, not the model, finds where to fix (files, then a skeleton view, then the function), and
    then samples several patches and lets tests pick one.
  - Aider and SWE-Edit: weaker models do better rewriting a whole block than writing search/replace edits.
- **Change (core/PyFix.kt, NativeAgent `pythonFix`, HybridAgent `pythonFixFirst`):**
  - The path starts once per request, before Nano's first step, when the Python helper is on, Remote Config's `hybrid_python_fix`
    is on, the phone may run the step, and the request names a .py file and a test command in backticks.
  - It runs the test, lists the file's functions, classes and constants (methods and class attributes as `Class.name`), asks Nano
    which one the fix belongs in, and adds the app's guesses: the block the request names most, the one a traceback points into, the
    ones the test calls, and the one sharing the most words with the test. A class over 60 lines is narrowed to that member.
  - For up to three blocks it asks for the whole fixed block: `hybrid_python_fix_samples` times (4) for the first, half as many for
    the others. The first try uses the person's temperature, the rest `hybrid_python_fix_temperature` (0.7), each with the last
    failure. Each answer is put in place at the block's indentation and the test runs; the first that passes is kept and recorded as
    a passed check. If none passes, the file is put back and Nano carries on with its tools.
  - `replace_function` (path, name, code): a new tool for Nano when the Python helper is on. It replaces a block by name, with no
    search text, through the usual write checks.
  - Nano's calls on the path go to nano.jsonl with `tool: python_fix`.
- **Bug fixed:** with the hybrid provider the Python helper's note never reached Nano's prompt. ModelClient sends the system prompt
  apart from the messages, so HybridAgent never saw the note; none of run 1126-1710's 309 prompts had it.
  NativeAgent now sets `HybridAgent.pythonHelp`. The helper's other parts (re-indenting, outlines, where-it-failed) were in
  NativeAgent and did run.
- **Remote Config:** 3 new keys (`hybrid_python_fix`, `hybrid_python_fix_samples`, `hybrid_python_fix_temperature`); 27 in all.
- **Files:** core/PyFix.kt (new), NativeAgent.kt, HybridAgent.kt, HybridAi.kt (`onDeviceSample`), core/Tuning.kt, core/StaleReads.kt,
  remoteconfig.template.json, HACKATHON.md; app/build.gradle.kts (1.5.201-hybrid, code 249).
- **Tests:**
  - PyFixTest: on all 13 real Python tasks each reference fix, given as Nano would answer, goes in block by block and its visible test
    passes (13 of 13). The app's guesses include the block the fix changes in 11 of 13. Also: blocks found whole (decorators,
    multi-line defs, class constants); Nano's prose and indentation handled; the request's file and test.
  - PyFixLoopTest (real agent loop, scripted Nano, real pytest): the second rewrite passes and is kept; with none passing the file is
    put back and Nano fixes it with replace_function; off when Remote Config turns it off.
  - 1,368 unit tests, 0 failures.
- **Evidence:** pocketide-hybrid-reliable-python-benchmark-1126-1710.


### fa42953 · 2026-11-26 20:10 · Python fix path tests only Nano's real changes; 1.5.202

- **Why:** Python run 1126-1923 (1.5.201, stopped at task 5). The fix path picked the right block in all 4 tasks, but 0 of Nano's 24
  rewrites passed. Retyping 30-100 lines, Nano (E4B) changed docstrings and comments and slipped on code (`orgetattr`,
  `except**ValueError`, `Dir(obj)`), so a real attempt failed on a slip next to it.
- **Change (core/PyFix `variants`, `hunks`, `replaced`; NativeAgent `pythonFix`):**
  - Each rewrite is compared with the original line by line. A stretch that changes only comments, docstrings, blank lines, case,
    spacing or a trailing comment is put back. Strings stay code in a constant (rich_3043's HTML template).
  - The app then tests the rewrite with all its real changes, then each real change alone (at most 5 tests a rewrite). Equal-size
    stretches are split per line, so a slip next to a real change is put back on its own.
  - The message now counts rewrites and tests.
- **Evidence (replay):** this run's 24 real rewrites, tried as the app now tries them against the real tests here: 0 pass as sent,
  and requests_7315 passes from one of them (1 of the 4 tasks, 40 tests). The rest are wrong fixes, not slips: Nano changes
  `request.path_url` to `request.url`, or catches the wrong error. Nano's reasoning is the limit; the app now only stops losing
  the attempts it does get right.
- **Files:** core/PyFix.kt, NativeAgent.kt; test data nano/pyfix/run-1126-1923.jsonl (the 24 rewrites); app/build.gradle.kts
  (1.5.202-hybrid, code 250).
- **Tests:** PyFixTest `nanosRealRewritesFromRun1923` (the replay; as sent none pass) and `copyingSlipsArePutBack`;
  PyFixLoopTest updated. 1,370 unit tests, 0 failures.
- **Evidence:** pocketide-hybrid-reliable-python-benchmark-1126-1923-partial-4of13.


### 26695f9 · 2026-11-26 20:25 · Python fix path asks the cloud when Nano's rewrites fail; benchmark dialogs fixed; 1.5.203

- **Why:** the owner asked for 80-100% on the Python tasks. Run 1126-1923 showed that Nano's reasoning is the limit (the app found
  the right function every time; Nano's changes were wrong), and no tool on the phone closes that.
  - The owner also reported that the total benchmark had no Start button.
- **Change: the fix path's cloud steps** (NativeAgent `pythonFix`; core/PyFix `CLOUD_SYSTEM`, `sketchPrompt`, `sketchNote`):
  - They run only when Nano's own rewrites fail and the cloud is allowed (not Private mode, online, quota left).
  - Cascade (`hybrid_python_fix_cascade`): one cloud call says in at most 8 lines what to change in the block the app picked
    first, with how Nano's last try failed. Nano then writes the block from that plan, twice, so most of the output stays on the
    phone.
  - Then (`hybrid_python_fix_cloud`): the cloud writes the fixed block itself, twice at most, the second time with the new failure.
  - Every answer goes through the same slip check, variants and test. The success message says who wrote the fix. The cloud calls
    go to nano.jsonl as `python_fix_cloud` with the model.
- **Change: benchmark:**
  - New suite and buttons "Python, Nano + cloud: quick / full" (`hybrid-reliable-python-hybrid`): the 13 Python tasks in the
    on-phone-first mode, with the cloud allowed (`ReliableBench.withCloud`; a cloud step no longer fails the task). Every other
    Python run was Private mode, where the cloud steps can never run. analyze.py knows its title.
  - The Python-only dialogs have the Python helper ticked by default.
  - The total benchmark has a Python helper option, ticked (`BenchTotal.State.pyHelper`); total runs had the helper off before.
  - Benchmark dialogs put their text and options in one scrolling view (`benchSheet`). With a long message set apart and the
    options under it, the phone had no room for the buttons.
- **Remote Config:** 2 new keys; 29 in all.
- **Files:** NativeAgent.kt, core/PyFix.kt, core/Tuning.kt, HybridBench.kt, HybridScreen.kt, core/ReliableBench.kt, core/BenchTotal.kt,
  remoteconfig.template.json, bench/hybrid/analyze.py, HACKATHON.md; app/build.gradle.kts (1.5.203-hybrid, code 251).
- **Tests:** PyFixLoopTest `nanoWritesTheCloudsPlan` (Nano's try fails; the cloud's plan is shown with the failure; Nano's rewrite
  from it passes), `theCloudWritesTheBlockLast`, `privateModeNeverAsksTheCloud`; the Remote Config template check covers the new
  keys. 1,373 unit tests, 0 failures.


### 25c4859 · 2026-11-26 22:10 · Thinking shown for hybrid; links in pasted issues stay on the phone; the report counts the fix path; 1.5.204

- **Why:** the owner asked why hybrid mode does not show the model's thought process the way API models do.
  - The cloud tiers that think were never asked for thought summaries (`includeThoughts`).
  - HybridAgent's turns left the thinking field empty. Nano has no thinking stage, but its "reason" was shown as reply text.
  - In Python run 1126-2032 (12 of 13), every task was handed to the cloud as "this needs the web" for links in the pasted issue
    text, and the report counted none of the fix path's 98 Nano calls ("0 of 105 steps on this phone").
- **Change:**
  - Thinking: tiers that think ask for thought summaries (Remote Config `hybrid_show_thoughts`, on).
    `HybridAgent.turn` puts thought parts in the turn's thinking, shown in the work log like an API model's thinking, and keeps
    them out of the reply. Nano's reason with a tool call goes there too, as "Gemini Nano on this phone: …". An answer stays the
    reply.
  - Web routing (`HybridRoute.needsCloud`): the web words are read in the person's own words only (the request without the app's
    project context, first paragraph, at most 400 characters). A link counts when the request asks to open, read, fetch,
    summarise, download or clone it, or when the request is short.
  - Report: `HybridBenchTasks.fixBy` reads who wrote the fix path's passing rewrite. The report has a line "Python fix path: ran
    on N tasks · fixed by Gemini Nano alone · from the cloud's plan · by the cloud model · no rewrite passed".
- **Remote Config:** 1 new key; 30 in all.
- **Files:** HybridAi.kt, HybridAgent.kt, core/HybridRoute.kt, core/HybridBenchTasks.kt, core/Tuning.kt, remoteconfig.template.json;
  app/build.gradle.kts (1.5.204-hybrid, code 252).
- **Tests:**
  - HybridRouteTest `linksInAPastedIssueStayOnThePhone`, plus a link to open still going to the cloud.
  - HybridAgentTest `nanosReasonIsItsThinking` (and with the key off, in the reply as before) and `theReportCountsTheFixPath`.
  - 1,376 unit tests, 0 failures.
- **Evidence:** pocketide-hybrid-reliable-python-hybrid-benchmark-1126-2032.

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
- **Firebase console to do (from the agent-skills audit):** paste `firestore.rules` into Firestore → Rules, and turn on App Check
  enforcement for Cloud Firestore.
- **Version label:** the app still reports 1.5.183-hybrid; builds are told apart by commit, as in the table above.
