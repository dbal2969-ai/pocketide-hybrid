# Emulator test of 1.5.126 (2026-11-11)

The first run of `.github/workflows/emulator.yml`: PocketIDE 1.5.126 (GPL) on an Android 16 emulator (Pixel 6 profile, x86_64,
running the app's arm64 code through Android's translation), driven step by step from `.github/emulator/driver.py` for about
85 minutes, with Kimi K2.6 on Cloudflare as the model. 46 batches of steps; screenshots and screen dumps were read after each.
**No crash in the whole session** (the crash buffer stayed empty).

This is not the phone check: speed, touch feel, the real keyboard, self-update and anything tied to the owner's accounts still need
the phone. It does check that screens open, buttons do what they say and the flows finish.

## What was checked

| Area (PHONE-TEST.md) | Result |
|---|---|
| First run: welcome dialog | Works. **Bug:** the checklist behind it ticked "Connect an AI" with no key (the keyless demo counted) — fixed in 1.5.127 |
| 1.5.125 Chat home: header, mode chip, a question | Works: "Chat (no project)", chip "Chat", correct answer about HTTP 418 |
| 1.5.125 Chat home: "create index.html" | Nothing was written (safe). **Bugs:** Agent mode switched itself on; the app nudged the model to "do the task"; the card said "Wrote index.html (read-only)"; the model told the person to switch to Agent; the empty Chat home showed project hints ("Add a dark mode toggle") — all fixed in 1.5.127 |
| 1.5.125 Build this as a project | Works: name asked, conversation copied, Plan mode, "Build what we discussed above." typed. **Bugs:** it landed on the Code tab, not the chat; Plan mode stayed on for every other project; the typed request followed into the next project — fixed in 1.5.127 |
| 1.5.125 Search all chats | Works: hits by project, opens the chat in its project. **Bug:** the search box did not take focus, so typing went nowhere until it was tapped — fixed in 1.5.127 |
| Agent edit in a project (heading text and colour) | Works: requirements list, Approve, both changes applied, Preview shows them. **Bugs:** the read-only look-around was nudged to "do the work" and its refused edit was labelled "Edited index.html, style.css (read-only)"; the requirements list showed raw `` ` `` marks; the keyboard stayed open over the Preview — fixed in 1.5.127 |
| Markdown in replies | **Missing:** `**bold**` and `` `code` `` showed with their marks — rendered in 1.5.127 (replies and plan lists) |
| 1.5.125 pause-and-step debugger | **Works on a real WebView:** paused at app.js:3 with the code, the watched value and the names there; Step moved to line 4; Continue ran on; Stop debugging ended it |
| 1.5.125 errors from the published site, nothing published | Works: "No error reports yet" with a plain explanation |
| 1.5.126 settings label | Works: "…Not yet measured: the button is the tested way" |
| 1.5.125 next-edit setting | Present and ticked (the emulator counts as Wi-Fi) |
| 1.5.125 next-edit live (`area(w)` → `area(w, h)`) | **No suggestion appeared in 45 seconds**, twice. The request is silent by design (no error, no log line), so the cause could not be seen: Kimi too slow, an answer the app rejected, or an error. Not fixed; needs a visible trace first |
| 1.5.117 replace on move | Works: "Replace main.js?" dialog; Replace moved the root file into game/ and the old one is gone |
| Full terminal (no Linux installed) | Works: Android shell in the project folder; `ls` lists the files |
| First project: Snake sample in an empty project | **Works but slow: about 22 minutes.** The game runs in the Preview (moves, swipes turn it, wall collision shows Game Over, Restart works). **Problems:** splitting an 8-point list into parts took over 3 minutes and then proposed 2 parts that write the same file (a second approval for nothing); the app's step labels ("Splitting the job into parts", "Checking the result…") showed as the person's blue bubbles (fixed in 1.5.127); Kimi asked to run `python3 -m http.server`, then `which node`, then `grep` to check a page, though Python and Node are not installed and the app has its own page checker (the "not checked yet" ask now leads with `look_at_preview` for web files, 1.5.127, pending its A/B); two read-only commands were labelled "This changes files in the project" (fixed in 1.5.127) |

Not reached this session: Export all (1.5.121), /bench from the phone (needs a linked repository and a token), publishing to
Cloudflare, the Linux environment install, "Carry on with a stronger model" (needs a stall and a second model).

## Still open after 1.5.127

1. **Next-edit on a live model:** add a trace line for each request (asked, answered, rejected and why) so the next emulator
   session can see why nothing shows.
2. **Build in parts for one-file requests:** skip the split when the request names one file or all requirements touch the same
   file. It cost 3+ minutes and a second approval for nothing.
3. **Commands for tools that are not installed:** the prompt says Python and Node are missing and Kimi still asked to run them.
   The app could answer such a command itself ("python3 is not installed here") instead of showing a Run card.
4. **Search snippets** include PocketIDE's own follow-up text ("The person approved…") — cosmetic.

## Session 2 (2026-11-12, GPL 1.5.128, Kimi K2.6 on Cloudflare)

The same Snake request in a new, empty project ("a snake game in one HTML file, arrow keys and swipe, score, restart").

| What | Result |
|---|---|
| Plan | One list of 5 requirements, no split into parts (Kimi kept it to one file without being told) |
| Building | index.html (7.7 KB) written about 3 minutes after Approve |
| Then | **Stuck for 50 minutes** on a Run card for `python3 -m http.server 8080 &` ("This uses the network"). The run waits for the person, which is right for a network command, but the command could never have worked (no Linux on the emulator) |
| After Skip | Finished within seconds: "What changed: 1 new index.html", then the requirement check listed all 5 as met with line numbers |
| Page check | **"No page to check: the preview found no .html file"**, with index.html there. Cause: the page tools were decided when the request started, when the project was still empty, so Kimi had no look_at_preview and tried a web server instead. **Fixed in 1.5.129** (`LookOffer`): offered for empty projects and page requests, page looked up when the check runs |
| Command without Linux | 1.5.129's `NoLinuxCommand` answers python3/node/curl at once when Linux is not running, so this Run card no longer appears; together with the fix above the answer now points at look_at_preview |
| Test rig | The live step timer keeps the screen "busy" for Android's screen reader, so the driver could not find the Skip button by its text; tapped by position instead |

Next-edit tracing (1.5.129) was not tested live here: the emulator ran the released 1.5.128.

## About the test rig

Three problems in the driver, all fixed for the next session: Android's screen reader cannot capture a screen that is animating
(a reply streaming in), and the driver then re-read the previous capture, so some early "the tap did nothing" readings were
stale (fixed: a busy screen is reported as busy); taps landed on dialogs still moving as the keyboard opened (fixed: a short
settle before each tap); clearing the app's data also withdrew its notification permission (fixed: granted again). Injected
spaces and letters did not always reach the code editor; tapping the on-screen keyboard did.

## Emulator test of 1.5.136 (2026-11-13)

Run 43342748168, Android 16 emulator, PocketIDE 1.5.136 (GPL), Kimi K2.6 on Cloudflare, 11 batches. **No crash** (crash buffer
empty in every batch).

| Check | Result |
|---|---|
| Settings → Editor and layout: "Screen reader: say what the assistant is doing" | Present, ticked by default; unticking and Save stores `a11y.work_announce=false` |
| Keys sealed (1.5.133) | `key.cloudflare` is stored as `enc1:…` in the settings file on a real Android 16 system |
| `cat $HOME/.bashrc` (1.5.135) | Card: "This looks like it reaches outside the project folder." Run / Skip |
| `cat </etc/passwd` (1.5.136) | Asked first, but the card said "This only reads." and offered "Always allow cat": **fixed in 1.5.137** |
| `grep -n html index.html` | Ran with no card; answer with the three matching lines |
| Saved chat masking (1.5.135) | Reply `DB_PASSWORD=hunter2hunter` shown on screen; the saved chat file holds `DB_PASSWORD=[hidden]` three times, the value never |
| Chat after force-stop and restart | Reopens with its whole history |
| Spoken names | ⚙ ⋮ ▶ ⋯ 🎤 ■ ↪ ⏱ all named; **📎 had no name: fixed in 1.5.137** |

Not checked here: TalkBack itself, the keystore upgrade over an older install, the preview crash restart, the Linux add-on on the
MIT edition. Those need the phone (PHONE-TEST.md).

## Emulator checks for Copilot's R8 (2026-11-14, GPL 1.5.154 and 1.5.155)

Run 43559376238, Android 16 emulator, Kimi K2.6 on Cloudflare, batches 401 to 408. **No crash** (crash buffer empty). Batch 401 was
lost to a "System UI isn't responding" dialog at boot, 402 and 404 to the wrong taps; 403, 405 to 408 are the results.

| Check | Result |
|---|---|
| Advanced editor: rotate to landscape and back | Stays open with the file; Fold all works in landscape (403) |
| Advanced editor: Home, then back to the app | Still open with the file (403) |
| Advanced editor: Back | Returns to the normal editor with the code (403) |
| Dark mode switch (`cmd uimode night yes`) with a file open, 1.5.154 | **The open file was closed and the home page shown** (403): the activity was recreated for a setting the app does not use. **Fixed in 1.5.155** (`uiMode` handled by the app) |
| The same on 1.5.155 | Normal editor and advanced editor stay open through night on and off (408); a dialog stays open too (407) |
| Dark mode, new screens | Home and chat look the same as before (the app's look is always dark); screenshots 403/40, 403/50 |
| A run with the screen off (Agent, "add input checks ... write tests ... fix anything that fails") | After the plan was approved, the screen was turned off for 3 minutes: the run wrote `app.test.js`, `app.js`, `index.html`, `test.html` and `style.css` while asleep and was on its last page check when woken (406) |

Seen on the way: the run above also changed `style.css` ("button size fix"), which the request did not ask for (the R4 pattern).
Still for the phone: typing with the soft keyboard in the advanced editor, TalkBack, search by meaning with a real provider (what leaves
the phone), Sweep, a real `/reviewbot` review.
