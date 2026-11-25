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

## Game benchmark

Models → Hybrid AI → **Run the game benchmark** runs five browser games checked by the app's own play tests (`tests/play.txt`),
which PocketIDE plays before it calls the work done. Starting projects are in `games/tasks/`, zips in `games/zips/`, reference fixes
in `games/solutions/`. To prove each task is fair (its play tests fail as given and pass with the reference fix) with the app's own
play-test script in Chromium: `POCKETIDE_DUMP_PLAY=/tmp/play ./gradlew testDebugUnitTest --tests '*GamePlayScriptDump*'`, then
`node bench/hybrid/check_games.js bench/hybrid/games /tmp/play`. `make_games.py games` rebuilds them; `to_kotlin.py games
../../app/src/main/java/dev/pocketide/app/core/HybridBenchGameFiles.kt HybridBenchGameFiles` updates the app's copy.

| # | Prompt | Play tests | What it tests |
| --- | --- | --- | --- |
| gm01 | Build the Snake game exactly as GAME.md describes, so the play tests in tests/play.txt pass | 6 | A game from a spec: moving, turning with arrow keys (no clicking first), no reversing, eating, walls, Start |
| gm02 | The ball moves but the paddle ignores the arrow keys. Fix it so the keys work without clicking the game first, and keep the paddle inside the court | 3 | The owner's snake bug: keys sent to a canvas that never has focus, and a paddle that leaves the court |
| gm03 | Add pause and resume with the P key (status shows Paused), and keep the best score in localStorage under snakeBest, shown in #best as Best: N. Snake must keep working | 8 | New features without breaking the six existing play tests |
| gm04 | Build tic-tac-toe with a computer opponent exactly as GAME.md describes | 5 | Taps on cells, an opponent with exact rules (win, block, centre, corners), win and draw |
| gm05 | Every time Restart is pressed the game gets faster. Fix it | 3 | A restart that stacks game loops (setInterval never cleared) |

## Local model benchmark (Gemini Nano alone)

Models → Hybrid AI → **Run the local model benchmark** runs twenty tiny tasks in Private mode, so every step is Gemini Nano's.
It is for tuning how PocketIDE drives Nano: its instructions, examples, tools, settings and the app's repairs. The model itself
cannot be changed (Android's AICore installs and updates it). About 20 to 40 minutes, no internet needed; Node must be installed.

The report scores each skill and counts how Nano's steps went: tool calls the app refused (edits that did not match, files not
saved), repeated reads, replies that were not a usable action, and the app's repairs. Its header records the settings used
(on-phone model, temperature, top-K, output limit, on-phone limit), so runs with different settings can be compared. Each task's
folder also has `nano.jsonl`: one line per Nano exchange with its prompt, its reply, the tool it chose (or why the reply was
unusable), the time, and the result of its previous step.

`make_local.py local` builds the tasks (`local/tasks`, `local/zips`, `local/solutions`); `python3 check_hard.py local` proves
every code task fails as given and passes with its reference fix; `to_kotlin.py local ../../app/src/main/java/dev/pocketide/app/core/HybridBenchLocalFiles.kt HybridBenchLocalFiles` updates the app's copy.

| Skill | Tasks |
| --- | --- |
| Find and explain (no change) | l01 which file defines a name · l02 what a function returns · l03 explain code · l04 run the test and report |
| Fix one line | l05 operator · l06 typo in a string · l07 off-by-one · l08 property name · l09 fix, then run the test |
| Add new code | l10 a function · l11 a key control · l12 a touch control · l13 a switch case · l14 an object field · l15 one more line in a function |
| New file | l16 a JSON file · l17 a module |
| Two files or a stale quote | l18 rename across two files · l19 HTML and JS together · l20 a request that quotes the line slightly wrong |

## Analysing a run (every task, pass or fail)

The app's verdict is not the last word: every results zip is analysed with

```
POCKETIDE_DUMP_PLAY=/tmp/play ./gradlew testDebugUnitTest --tests '*GamePlayScriptDump*'   # once: the app's play-test scripts
python3 bench/hybrid/analyze.py pocketide-hybrid-…-benchmark-….zip /tmp/play
```

For each task it compares the finished files with the starting project and the reference fix, reports names (functions, ids,
files, selectors) the work lost that the reference keeps, checks the syntax of every changed JS, JSON and HTML file (and HTML
structure: unclosed or doubled tags, scripts the page no longer loads), reruns the task's own test with the original test file,
opens index.html in Chromium (console errors, page errors, files that failed to load) and replays every play test with the app's
own play-test code, and reads the chat log and nano.jsonl (tools, refusals, attempts to change tests, handoffs, errors, endings).
It writes `<zip>-analysis.md` with one of three verdicts per task: **clean pass**, **pass with problems**, or **fail**. Passes
with problems are read by hand and fixed like failures.

## From-scratch benchmark (no code to start from)

Models → Hybrid AI → **Run the from-scratch benchmark** asks for five things a person might want, with nothing to start from, each
twice: in an **empty project** (Agent mode), and in the **chat with no project**, where the prompt is discussed and then **Build this
as a project** is used (Plan mode; the benchmark approves the plan as the person would, or runs without one if none comes back).

| Prompt | Played in a browser (scratch/checks.js) |
| --- | --- |
| Snake game for a phone | board drawn; arrow keys reach the game without clicking first; touch or on-screen steering; score; it moves; a wall ends it; play again |
| Calculator | 7 + 8 = 15, 6 × 7 = 42, 9 ÷ 3 = 3, 2 − 5 = −3, C clears |
| To-do list | add, tick done, delete, kept after a reload |
| Tic-tac-toe against the computer | 3 × 3 board; the computer answers a move; a finished game is announced; New game clears |
| Tip calculator | a $100 bill at 15% shows $15 and $115 |

Every app is also checked for errors on load and while playing, and for fitting a 390 px phone screen. The checks find buttons by
their text, inputs by type and results by what the page shows, so they do not depend on the model's file or element names.
`scratch/references/` has an ordinary app for each, on which every check passes (and a snake whose keys only reach the canvas
fails exactly the key check). The app's own verdict only says a page was built and its checks passed; `analyze.py` runs the play
checks on every task.

## In-app questions benchmark (does the assistant know PocketIDE?)

Models → Hybrid AI → **Run the in-app questions benchmark** asks 14 questions about PocketIDE itself, in a small project
(`app/project/`) in Agent mode. The tasks marked Private are answered by Gemini Nano alone.

| Skill | Tasks | Passes when |
| --- | --- | --- |
| How-to | app01 Groq key (Private), app03 run commands without asking, app04 spending (Private), app05 keep code on the phone | app_help was called and the answer names the real place (Settings, Usage & cost, Private, the setting's label) |
| Troubleshooting | app02 429 error, app13 blank Preview | app_help was called and the answer says what Help says to do |
| Does not exist | app06 iPhone .ipa (Private), app07 Comic Sans editor font, app08 Dropbox sync, app14 Pomodoro timer (Private) | the answer says PocketIDE does not have it |
| Change a setting | app09 word wrap off (Private), app10 cloud model Best, app11 an OpenAI key | a card proposed exactly that change and the answer says it waits for Apply; the key is never proposed |
| Capabilities | app12 three things besides editing | two real features named |

Every task also fails if a project file changed, or if any setting the assistant can propose changed during the task (the runner
reads them before and after; the benchmark declines every card, so none may change). The record shows each card as
`settings proposal: <key> = <value> (declined automatically: benchmark)`.

`analyze.py` adds, for this suite: the whole answer; every label it quotes ("…" or **…**) and every menu path it names (› → >)
checked against the string literals in the app's source, so an invented button or menu is flagged even when the app's verdict
passed; the proposals, refusals and any setting that changed.

How it works: `scripts/gen_app_map.py` reads the screens' source (Settings dialog, ⋮ Menu, Hybrid AI screen, Workflow screen, the
AI tab's menus) into `core/AppMapData.kt`. `app_help` searches that, Help & FAQ, the slash commands, workflows, advisor modes and
cloud models, and names the words that match nothing in the app. `app_settings` reads settings and proposes changes through an
Apply / No card; API keys, tokens and "Run commands from the assistant without asking" are never proposed. CI fails when the map
is out of date.


## On-device reliability benchmark (Gemini Nano alone)

Models → Hybrid AI → **Reliability: quick** (each task once, about an hour) or **Reliability: full (3 runs)** (an evening). Every
task runs in Private mode, so it uses no cloud quota. It is built with the method from the Kaggle Gemma 4 entry (PocketSWE):

- **Splits.** The local suite's 20 tasks are the **dev** split: the app has been tuned on them since run 1125-0955. The 24 tasks in
  `reliable/` (`make_reliable.py`) are **held out**: no app change may be made because of one of them, so their score is the
  honest one.
- **Python track.** Real code: 13 single-file fixes in Textualize/rich and psf/requests, from the competition's public task list
  (`make_python.py`; `python/`). Kaggle ran these repo-scale, where Gemma 4 E2B (Nano Fast) solved 5 of 114 sound tasks and E4B
  (Nano Full) 16. Here they are "located": the prompt names the file and the test command. The project holds only the package
  and the tests the check needs. The app installs Python, pytest and the packages in Linux once (apk).
- **Sound tasks.** Every check fails as given and passes with the reference fix (`check_hard.py reliable`; `make_python.py`
  checks its own tasks). The phone also tries each reference fix before the model starts, and leaves out a task that its own
  package versions break.
- **Hidden checks.** After each task the app writes `hidden.js` (Node) or runs other test files (Python), which the model never
  saw, so a hard-coded or damaging fix fails.
- **Runs.** The full benchmark runs every task 3 times, round by round. The report gives pass@1 (the share of runs passed) and
  pass^3 (tasks passed every time), by split, track and skill. It also lists the tasks that passed only sometimes, damage caught
  by hidden checks, and runs that ended "done" but failed.

`analyze.py` handles these zips too: `ANALYZE_PYTHON=<python with pytest and the packages> python3 bench/hybrid/analyze.py <zip>`
reruns the Node and Python checks and the hidden ones here, task by task and run by run.
