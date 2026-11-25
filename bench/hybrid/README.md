# Hybrid AI benchmark (Gemini Nano on the phone + cloud)

Eight short tasks that test what hybrid AI must get right: real edits from Gemini Nano, handoffs to the cloud with the reason
shown, private mode, long files and offline use. Each takes 2 to 5 minutes. Run them on a phone with Gemini Nano (Galaxy S24+).

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
