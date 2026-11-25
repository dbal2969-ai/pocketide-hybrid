# Hybrid AI benchmark (Gemini Nano on the phone + cloud)

Eight short tasks that test what hybrid AI must get right: real edits from Gemini Nano, handoffs to the cloud with the reason
shown, private mode, long files and offline use. Each takes 2 to 5 minutes. Run them on a phone with Gemini Nano (Galaxy S24+).

## Run it automatically (recommended)

Models → Hybrid AI → **Run the hybrid benchmark**. PocketIDE runs all eight tasks by itself with the real agent and Gemini
Nano, checks each one, and saves `pocketide-hybrid-benchmark-<date>.zip` to **Downloads/PocketIDE**: a report, and every task's
files, chat log and check output. Keep the phone plugged in with PocketIDE open; it takes about 30 to 60 minutes. It needs Linux
with Node (Terminal tab), and puts your settings and project back when it finishes. The steps below are for running a task by hand.

## Before you start

- Settings: AI provider **Hybrid AI (Gemini Nano + cloud)**, workflow **Single model**.
- Models → Hybrid AI: Gemini Nano says **Ready**, routing **On this phone first**, Private off (task 8 turns it on).
- The Linux add-on with Node is installed (the tests run `node test.js` in the terminal).

## For each task

1. New project, then ⋮ → **Project zip → Import zip into project**, and pick the task's zip from `bench/hybrid/zips/`.
2. Open the AI tab in **Agent** mode and send the prompt below, exactly.
3. Wait for it to finish. Do not help it.
4. Run the check (terminal or preview), then note the result in the table at the end.
5. Export the project with its chat logs (⋮ → **Project zip → Export project as zip**) and send the zip.

## Tasks

| # | Zip | Prompt | Check | What it tests |
| --- | --- | --- | --- | --- |
| 1 | `01-fix-add.zip` | Fix add in app.js so node test.js prints ok | `node test.js` prints `ok` | A whole small fix on the phone: read, edit, run the test |
| 2 | `02-explain.zip` | (Chat mode, not Agent) Explain what parseCSV in csv.js does and how it handles quoted commas | Correct answer; no file changed; reply says "Ran on this phone" | Plain help on the phone |
| 3 | `03-todo-feature.zip` | Add a "Clear completed" button that removes done items. Make the play test in tests/play.txt pass | Preview → play tests: all pass | A small UI feature across HTML and JS, checked by the app's play test |
| 4 | `04-rename.zip` | Rename calcTotal to orderTotal everywhere and keep node test.js passing | `node test.js` prints `ok`; search finds no `calcTotal` | Edits across three files |
| 5 | `05-keep-definition.zip` | Add setDay(day, id) and dayOf(day) to app.js so a recipe id can be stored per day. node test.js must print ok | `node test.js` prints `ok`; `let selected = [];` is still there | The meal-planner failure: an edit must not delete a definition still in use |
| 6 | `06-research-handoff.zip` | Research the three most popular JavaScript date libraries and write DATES.md comparing their size and licence | Nano's line "I'm passing this to the cloud model… needs the web"; DATES.md names three libraries | Handoff for work that needs the web |
| 7 | `07-long-file.zip` | total() in inventory.js returns NaN. Find why and fix it so node test.js prints ok | `node test.js` prints `ok` | A file too long for the phone: expect a handoff ("longer than my on-phone limit") |
| 8 | `08-private-validate.zip` | (Turn on **Private** first) Make signup reject empty or malformed emails so node test.js prints ok | `node test.js` prints `ok`; every step says "Ran on this phone" | Private mode: Nano alone, never the cloud |

Also once, with no project: turn on **airplane mode**, then in the editor select a few lines, ⋮ → **Quick assist → Explain**.
The reply should come back and say "Ran on this phone".

## Results

| # | Passed? | Steps on phone / in cloud | Handoff shown (and why) | Time | Notes |
| --- | --- | --- | --- | --- | --- |
| 1 | | | | | |
| 2 | | | | | |
| 3 | | | | | |
| 4 | | | | | |
| 5 | | | | | |
| 6 | | | | | |
| 7 | | | | | |
| 8 | | | | | |
| Offline | | | | | |

The chat log in each exported zip has every step, where it ran and every tool result, so the zips are what matters; the table
is a quick summary. The tasks' source files are in `bench/hybrid/tasks/`; `node test.js` fails in each until the task is done.

## Hard benchmark

Models → Hybrid AI → **Run the hard benchmark** runs ten harder tasks the same way (about 60 to 90 minutes) and saves
`pocketide-hybrid-hard-benchmark-<date>.zip`. Starting projects are in `hard/tasks/`, zips to run by hand in `hard/zips/`, and a
reference fix for each in `hard/solutions/`. `python3 check_hard.py hard` proves every command task fails as given and passes with
its reference fix; `make_hard.py hard` rebuilds them, then `to_kotlin.py hard ../../app/src/main/java/dev/pocketide/app/core/HybridBenchHardFiles.kt HybridBenchHardFiles`
updates the app's copy (a unit test fails if it is out of date).

| # | Prompt | Check | What makes it hard |
| --- | --- | --- | --- |
| h01 | Cart totals are wrong: coupons and bulk prices come out wrong. Find every cause and fix it so node test.js prints ok | `node test.js` | Two bugs in two files behind one symptom; the request names neither file |
| h02 | Implement parseDuration and formatDuration in duration.js exactly as SPEC.md describes, so node test.js prints ok | `node test.js` (20 cases) | Code from a written spec with edge cases (order, repeats, case, invalid input) |
| h03 | loadAll in loader.js returns too few results, in the wrong order, and throws when one id fails. Fix it so failed ids give { id, error: message } in their place. node test.js must print ok | `node test.js` | Async: `forEach(async …)`, ordering and per-item errors |
| h04 | Split utils.js: move slugify and titleCase to strings.js, and clamp, sum and avg to numbers.js. Keep unique in utils.js, and keep utils.js exporting everything so app.js and other callers still work. node test.js must print ok | `node test.js` | A refactor across new files that must not change behaviour or break a caller |
| h05 | node test.js fails. Find the cause and fix it so it prints ok | `node test.js`; the callers are not patched | The bug is in a nested helper (`src/lib/format/date.js`) the request does not name, with two callers |
| h06 | Add All, Active and Done filter buttons above the list that show only the matching items and keep working as items are added or ticked. Make the play test in tests/play.txt pass | Play test (14 steps) | UI state across HTML and JS, checked by the app's play test |
| h07 | inventory.js gives wrong results: totalValue is NaN and needsReorder misses items. Find both bugs and fix them so node test.js prints ok | `node test.js` | Two bugs far apart in a 34,000-character file (over twice the on-phone limit) |
| h08 | (Private) Implement checkPassword in password.js as its comment describes, with the problems in that order, so node test.js prints ok | `node test.js`; no step in the cloud | Gemini Nano alone with four rules, exact messages and order |
| h09 | Look up the SPDX licence identifiers of the npm packages express, lodash and react, and write licences.json mapping each package name to its identifier. node test.js must print ok | `node test.js`; a web handoff shown | The web, then a file a program checks |
| h10 | Invoices show prices like $12.5 instead of $12.50. Fix it without breaking receipts, so node test.js prints ok | `node test.js` | A shared helper with two callers that use it differently |
