

## 1.5.161: one phone session for parity (Copilot HANDOFF-CALIBRATION S6), in this order

What the emulator already showed (EMULATOR-TEST.md, run 43559376238): the advanced editor survives rotation, Home and Back; dark mode
no longer closes the open screen; a run carries on with the screen off for 3 minutes. What only a phone can show:

- [ ] **1. Advanced editor with the soft keyboard.** Open a .js file, ⋮ → Advanced editor. Type about 200 characters with the phone's
  keyboard (a whole function), then Fold all, Undo three times, Redo twice, rotate, press Home and come back, press Back. Expected:
  every character lands where the cursor is; folding and undo work; the text survives each step and Back shows it in the normal editor.
- [ ] **2. TalkBack with a run going.** Turn TalkBack on, start "add a footer with the year". Swipe through the screen while it runs.
  Expected: the file tree and chat can be read; the step timer is not read out more than about once every few seconds.
- [ ] **3. Search by meaning sends nothing secret.** In a project holding a `.env` (`API_KEY=test-secret-123`) and
  `keystore.properties` (`storePassword=test-secret-456`), set Settings → Assistant → Search by meaning to a real provider and ask
  "where is the score saved?". Then open that provider's request log (Cloudflare: AI → Logs; OpenRouter: Activity). Expected: neither
  file's text and neither secret appears in any request.
- [ ] **4. Sweep with a local model.** Ollama on the phone with `hf.co/sweepai/sweep-next-edit-1.5B`. Add a parameter to a function and
  pause; note the seconds until the "Next edit" bar appears, for five suggestions. Note the battery level before and after 10 minutes.
- [ ] **5. `/reviewbot` on a scratch pull request** in a scratch repository (costs cents on your key): push a change with a deliberate
  bug and open a pull request. Expected: a "PocketIDE review" with a comment on that line.
- [ ] **6. Dark mode on every new screen**, including a page check result with the new overlap line ("covers the text of ...") and the
  new setting "Before finishing, have a fresh reviewer ..." in Settings → Assistant. Expected: everything readable, nothing white-on-white.
- [ ] **7. A 20-minute run with the screen off.** Start a long request ("write tests for every function in app.js and fix what fails"),
  approve the plan, turn the screen off for 20 minutes. Expected: the run is finished or still going when you wake the phone, and the
  notification says which.

Tell me the results in one message (a tick, or what you saw instead); a screenshot for anything that looks wrong.

## 1.5.144: the advanced editor
- [ ] Open a .js file, ⋮ → "Advanced editor (folding, multiple cursors)": the file opens with line numbers and colours. Select a
  name, tap "Next match" twice, type: all three change. "Fold all" folds the functions. Done: the normal editor shows the change.
- [ ] With TalkBack: the buttons are read as "Find and replace", "Add a cursor at the next match…", and the text can be read.

## 1.5.143: pull request reviews and Sweep
- [ ] In a project linked to GitHub with /cloud's settings: `/reviewbot`, push, open a pull request with a deliberate bug; a
  "PocketIDE review" appears with a comment on that line. A draft pull request gets none.
- [ ] Ollama on the phone with `hf.co/sweepai/sweep-next-edit-1.5B` as the suggestion model: add a parameter to a function, pause;
  a "Next edit" bar offers the matching change at a call. ⋮ → App log → next-edit lines say what happened.

## 1.5.141: Cursor parity
- [ ] A project with `.cursor/rules/style.mdc` (`alwaysApply: true`): send a request; a grey "Following … .cursor/rules/style.mdc"
  line appears under it.
- [ ] Settings → Assistant → "Search by meaning": enter `cloudflare:@cf/baai/bge-base-en-v1.5` (with a Cloudflare key and account
  set), ask "where is the score saved?" in a project; the assistant's find_code step answers, and the project's first search takes
  a little longer while it is indexed.

## 1.5.139: the ask checklist
- [ ] Ask for two things at once ("Make the title red and add a footer with the year"): the last message is the assistant's own
  summary of what it changed, with a grey "Checked 2 asks" note above it, and no "ASKS" list.
- [ ] Settings → Assistant: untick "When a request asks for several things, check each one before finishing"; the same request ends
  without the "Checking the work against each ask" note.

## 1.5.135: masking, spoken updates, commands
- [ ] Settings → Editor and layout: untick "Screen reader: say what the assistant is doing"; with TalkBack on, a run is silent
  apart from what you touch. Tick it again: "Reading", "Running" are announced.
- [ ] Ask the assistant to run `cat $HOME/.bashrc`: a card asks first. `grep -n 'TODO' app.js` runs without one.
- [ ] Ask it to run `echo DB_PASSWORD=hunter2hunter`; close and reopen the chat: the saved reply shows `DB_PASSWORD=[hidden]`.
- [ ] Old chats still open after the update.

## 1.5.133: TalkBack, keys, preview crashes
- [ ] Update over the old version: the assistant still works without entering the API key again (keys are sealed on start).
- [ ] TalkBack on: the ⋮ and ⚙ buttons, ▶, the chat's ⋯ chip, an editor tab's ✕ and the terminal's ↺ are read by name.
- [ ] TalkBack on, during a run: the timer line is not read every second; phase changes ("Running a command") are announced.
- [ ] TalkBack on: you can reach Send, Stop, Model and Chats by swiping, and double-tap works on each.
- [ ] Buttons and chips are easy to hit with a thumb (none feel smaller than a fingertip).
- [ ] Preview a page that allocates memory without end: the app stays open and says the preview crashed and was restarted.

## 1.5.130: Linux add-on without a token
- [ ] MIT edition with no GitHub token set: ⋮ → Tools & Linux → Get the Linux add-on downloads and offers to install it.

## 1.5.129: images, one-file builds, commands without Linux
- [ ] With Kimi (or another model that cannot see images) and a Gemini key saved: attach a screenshot and ask about it. The warning offers "Describe with Gemini"; tap it: a grey note says the image was described, and the answer refers to what is in the picture.
- [ ] Without Linux, ask for "a snake game in one HTML file": one list to approve, no "Splitting the job into parts", and no Run card for python3, node or curl.
- [ ] In a new, empty project ask for "a snake game in one HTML file": before finishing, a grey note says it looked at the page (no "No page to check").
- [ ] Edit a file, pause, then ⋮ → App log: "next-edit" lines say what happened.

## 1.5.128: model suggestions
- [ ] With Kimi on Cloudflare and an OpenRouter key saved, ask for something to build: no suggestion to switch to an unbenchmarked free model.
- [ ] When a suggestion appears, tap Don't suggest it: it disappears, and it does not come back after restarting the app.
- [ ] MIT edition: Menu → Full terminal opens a black terminal in the project folder; `ls` lists the files; the extra keys (Esc, Tab, Ctrl, arrows) work.
- [ ] MIT edition: ⋮ → Tools & Linux shows "Get the Linux add-on". Tap it: it downloads, Android asks to install "PocketIDE Linux add-on", install it. Back in Tools the button reads Install Linux; tap it; Alpine installs.
- [ ] MIT edition, after that: in the Terminal tab `python3 --version` fails, then install Python from Tools and it works; Full terminal opens into Alpine.
- [ ] GPL edition: Tools & Linux still installs and runs Linux as before (it uses its own PRoot, not the add-on).

**Emulator results (2026-11-11, 1.5.126):** `EMULATOR-TEST.md` lists what an Android emulator could check. Passed there: the
Chat home question, Build this, Search all chats, the pause-and-step debugger, replace on move, the site-errors empty state, the
1.5.126 label. Not working there: next-edit suggestions with Kimi. The boxes below stay for the phone.

## 1.5.127: fixes from the emulator test
- [ ] Ask the assistant something that gets a formatted answer ("list three CSS units with an example each"): bold, `code` and lists show formatted, with no stray * or ` marks.
- [ ] Home → 💬 Just chat → "create index.html": no "Agent mode on" note; the reply explains it can't change files here and offers Build this; no card saying "Wrote".
- [ ] In the Chat home, tap Code: Home opens instead of an empty ".chat" project.
- [ ] Build this as a project → Build: the AI tab opens with the request typed. Switch to another project: its mode is what it was before, and its box is empty.
- [ ] A big build: "Splitting the job into parts" and "Checking the result…" show as grey notes, not blue bubbles.

## 1.5.126: carry on with a stronger model, again
- [ ] Settings → Workflow: pick a strong model that cannot call tools. A stalled task offers "Carry on with <model>"; tap it and let it finish. Send another task that stalls: the button appears again.
- [ ] Settings → Assistant: the "carry on with the strong model at once" box ends with "Not yet measured: the button is the tested way".

## 1.5.125: chat with no project, carry on with a stronger model, questions at the end of a stall
- [ ] Home → 💬 Just chat: the header says "Chat (no project)" and the mode chip says "Chat". Ask "what does HTTP 418 mean?": it answers; ask it to "create index.html": it refuses to change files and mentions Build this.
- [ ] Mode chip → Build this as a project → name it: a new project opens with the conversation and "Build what we discussed above." typed in the box, in Plan mode. The project list never shows the Chat home itself.
- [ ] Home → 🔍 Search all chats, type a word from an old chat in another project: it is listed with its project; tapping opens it there.
- [ ] Settings → Workflow: pick a strong model different from the main one. Send a task that stops unfinished (a low Tool rounds setting): a "Carry on with <model>" button appears; tapping continues on the same files. With Settings → Assistant → "carry on with the strong model at once" ticked, it continues by itself, once.
- [ ] Type a new request into the box while the assistant works on a task: the reply does it in the same run instead of saying it will be done later.

## 1.5.125: /bench from the phone
- [ ] With PocketIDE's own repository linked in Git (token with Actions: Read and write), type `/bench rp-loot on <a pushed branch>`: the chat says "2 task runs: 1 task × 2 branches…", then a status line follows the run, and at the end a note shows the passes for main and the branch with an "Open the run" button. When the branch has an open pull request, the same table appears there as a comment "Benchmark from PocketIDE".
- [ ] `/bench a,b,c,d,e,f,g,h,i` is refused (at most 8), and `/bench suite hard` or `/bench rp-loot x3` asks in a dialog before anything is sent; Cancel says "Not sent."
- [ ] With a token that lacks Actions write, `/bench rp-loot` says the token needs "Actions: Read and write".

## 1.5.125: next-edit suggestions
- [ ] On Wi-Fi with a provider set (Settings → Editor and layout: "Next-edit suggestions" ticked by default), open a JS file with `function area(w) {…}` and a call `area(3)`. Add a parameter (`area(w, h)`) and pause: within a few seconds the bar above the keys says "Next edit, line N: … → …" with Go, Apply and Dismiss. Go jumps to the line; Apply changes only that call; ↶ puts it back. Typing again before the bar shows makes no bar appear for the old text.
- [ ] On mobile data with the box never touched, no next-edit bar appears (the rename bar still does). Ticking the box turns it on there too.

## 1.5.125: errors from published apps
- [ ] Add `setTimeout(() => { undefinedThing() }, 1000)` to a web project's script, `/deploy cloudflare`, open the site in the phone's browser, then `/deploy errors`: the list shows "Uncaught error: undefinedThing is not defined (app.js:…)", newest first. "Fix this" sends it to the assistant. The project's own index.html has no new script tag. With the usual token the list says the errors are kept in the Worker's memory only.

## 1.5.125: pause-and-step debugger
- [ ] Open a project with a script (e.g. a game), Preview → Debug, write `main.js:<a line inside a function that runs on a tap or every frame> <a variable>`, tap Pause. The preview reloads with a "Debugging: running" bar; when that line runs the page freezes and the panel shows "Paused at main.js:N", the code with ▶ on that line, the value and the names there.
- [ ] While paused, the rest of the app still responds (switch to Code and back is allowed but ends debugging: the page carries on).
- [ ] Step moves to the next statement line (the ▶ moves); Continue runs until the breakpoint is reached again; Stop debugging hides the panel and the page keeps running from where it was.
- [ ] Tap Reload while paused: the page reloads normally with no panel and no freeze.
- [ ] A breakpoint on a line in the middle of an object or a call (e.g. `main.js:<line inside { a: 1, … }>`) says "(at line M)" in the running bar and stops at the next statement instead.

## 1.5.124: re-reading in circles
- [ ] Ask the assistant (Agent mode) to change something in a big file, and watch for a run that keeps reading the same file: it now stops with "the run was going in circles: 6 reads of files it already had…", and Continue carries on from there. Normal runs read each file once and are not stopped.

## 1.5.123: deploy with a backend
- [ ] Publish a project that has index.html and a worker.js answering /api/hello with /deploy cloudflare: opening <address>/api/hello shows the backend's answer, not the page.

## 1.5.122: parts check and window
- [ ] Build a plain web page in parts (several js files loaded with script tags that set `window.something = …`): the "Checking all N files the parts wrote, together" step no longer says those files "should export" names they do share.

## 1.5.121: Export all with command output
- [ ] Run a command in a chat (e.g. ask the assistant to run a test), then open the chat's menu → Export all. The file has, under that chat, "Assistant record: the commands it ran, with their output", with the command's output in a Show section.
- [ ] A chat that never ran a command has no record section.

## 1.5.120: parts and follow-ups
- [ ] In a chat where a big build already finished, send a short new feature request: it goes straight to the agent (or its own plan), with no "First a look around" note about the old project.
- [ ] Send a big build request: the note "First a look around (read-only)…" appears before the list. While the list waits for approval, type a short note ("use blue"): the new list includes it.
- [ ] With several keys saved and no team in Settings → Parts, a long build in parts says "with 3 copies of <your model> on <provider>".
- [ ] Pick a team of two models in Settings → Parts: the message names only the models that build the parts.

## 1.5.119: benchmark task fix
- [ ] Nothing new to try on the phone: only a benchmark task changed.

## 1.5.118: benchmark task fix
- [ ] Nothing new to try on the phone: only a benchmark task changed.

## 1.5.117: replace on move
- [ ] Add a file named main.js to the project root, then long press it → Move to folder… → game: a dialog asks "Replace main.js?". Cancel leaves both files; Replace puts the new one in game/ and the root copy is gone.
- [ ] With game/main.js open in the editor, do the same: after Replace the editor shows the new text, in one tab.

## 1.5.116: new benchmark tasks
- [ ] Nothing new to try on the phone: this release only adds benchmark tasks. The app should behave as in 1.5.115.

## 1.5.115: big projects
- [ ] In a big project (your game), ask the AI "where is the long-press menu made?": it finds the file in a few seconds and does not stall.

## 1.5.114: cut-off answers are asked for again
- [ ] Nothing to see unless a connection breaks mid-answer: the status then says "The answer was cut off: asking again…" and the reply arrives.

## 1.5.113: light theme, side-by-side preview, rename suggestion
- [ ] Settings → Editor and layout → Light theme: the app restarts in light colours; chat, editor, dialogs readable. Turn it off again.
- [ ] Turn the phone sideways, open Preview: code on the left, page on the right; change the code, tap Reload, the page shows it.
- [ ] In a JS file, rename a variable on its declaration line; after a moment a bar offers "Rename x → y in N more places"; Apply, then ↶ undoes.

## 1.5.112: new tools
- [ ] /deploy cloudflare: enter a token and account id; the reply says "Live at https://…workers.dev"; Open the site works.
- [ ] ⋮ → Remote machine: Copy key, add it to a server, Connect shows the server's system; Run "ls" lists the project after Sync.
- [ ] Preview → Debug: a log point like index.js:10 x; Run shows the values or "never ran".
- [ ] ⋮ → API tester: GET https://httpbin.org/get shows HTTP 200 and indented JSON.
- [ ] ⋮ → Database viewer: with a .db file in the project, its tables and rows show; an UPDATE asks first.
- [ ] Attach a picture, "make a page like this": the look_at_preview report has "Compared with the design picture".
- [ ] /nightly at 3am: say hello in a file → files appear in .github; /nightly off removes them.

## 1.5.111: retries bare gateway errors
- [ ] Nothing to see unless a provider has a hiccup: an "error code: 502" now shows the app waiting and trying again instead
      of stopping the request.

## 1.5.110: searching works again after a read
- [ ] Ask about a name that is not in the project. After a few empty searches the assistant reads a file; its next search for
      a name from that file runs (no "Refused: the last 3 searches" right after a read).

## 1.5.109: retries Cloudflare internal errors
- [ ] With a Cloudflare model, run a few tasks. If Cloudflare has a hiccup, the chat shows it waiting and trying again instead
      of stopping with "Internal server error".

## 1.5.108: the page check long-presses
- [ ] In a game whose inventory opens a menu on a long press, ask for a small change: the check finishes a little later than before
      (about a second) and no menu is left open in your preview.

## 1.5.107: no app change you can see
- [ ] The bottom of the ⋮ menu says "About: PocketIDE 1.5.107". (A patch that ends with "*** End Patch" twice is now applied.)

## 1.5.106: patch tool, page check taps game controls
- [ ] With a gpt-oss model (Cloudflare @cf/openai/gpt-oss-120b), ask for a small change in a test project. If it uses
      apply_patch, the change is applied (no "must start with *** Begin Patch" errors).
- [ ] In a game whose inventory item tap throws an error, ask for any small change: the check reports "After tapping a
      control: …" with the error, and the assistant is asked to fix it.

## 1.5.105: deleting files, retries
- [ ] In a test project with app.js and an unused old.js, ask: "Delete old.js, nothing uses it." The chat shows delete_file,
      old.js is gone, and Undo changes brings it back.
- [ ] Ask: "Delete test.js." The assistant is told it may not delete a test unless asked to change tests, and says so.

## 1.5.104: errors thrown away, tests that catch bugs
- [ ] In a test project, ask: "Saving a bad row is silently ignored. Make save() in app.js fail on bad input." If the assistant
      writes an empty catch, the chat shows "app.js throws an error away (line N): told the model.", and it lets the error through.
- [ ] Ask: "Write tests for add() in app.js." The assistant's tests loop over many inputs, and its answer says which wrong
      changes it tried to prove they catch bugs.

## 1.5.103: names nothing defines
- [ ] In a test project, ask: "Add a function cook(id) to app.js that returns FOOD[id]" (with no FOOD anywhere). The chat shows
      "app.js reads FOOD, which nothing defines: told the model.", and the assistant defines FOOD or asks what it should be.
- [ ] In a game project that loads three.js with an import, ask for a small change in main.js: no warning about THREE or
      document appears.

## 1.5.102: no app change
- [ ] The bottom of the ⋮ menu says "About: PocketIDE 1.5.102". Nothing else changed on the phone.

## 1.5.101: no app change
- [ ] The bottom of the ⋮ menu says "About: PocketIDE 1.5.101". Nothing else changed on the phone.

## 1.5.100: no app change
- [ ] The bottom of the ⋮ menu says "About: PocketIDE 1.5.100". Nothing else changed on the phone.

## 1.5.99: faster rounds, thinking setting, carrying on
- [ ] Ask the Game developer something about game/main.js. In the work log, the first read says "The whole of game/main.js (N lines) is below…", and later reads of the same file say "has not changed since the whole file was shown".
- [ ] Settings → How long it may work → Thinking before each step: choose Low, run a task with Kimi K2.6. Each "Thought for…" line should be shorter than before. Set it back to "Model's own" if answers get worse.
- [ ] Start a long task, switch apps until Android closes PocketIDE (or the run stops). Open the chat: "Android closed PocketIDE while this was running, so it carries on from here in a few seconds", and it does.

## 1.5.98: honest checks in the background, test changes put to you
- [ ] Ask the Game developer for a visual check ("look at the game and tell me where the orbs are"), then switch to another app until it finishes. The answer gives real positions; the work log's "Looked at the page" steps say "Screen: 384x832" (or your phone's size), not 0x0.
- [ ] Ask for a change that breaks a saved play test without mentioning tests. Under the reply: "The assistant wants to change N saved test steps…" with **Allow the change** / **Keep the tests**. Allow: tests/play.txt changes. Keep: it stays as it was.
- [ ] In a game whose item pictures are missing, ask "fix the missing inventory pictures": the assistant exports them with game_cache instead of asking you to.

## 1.5.97: keep working in the background
- [ ] Start any task: the chat shows (once) "Tip: Android may close PocketIDE while you are in another app…" with **Let it keep working in the background**. Tap it: Android asks "Let app always run in background?"; choose Allow. On Samsung, also do Settings → Battery → Background usage limits → Never sleeping apps → add PocketIDE.
- [ ] Start a long task and switch to another app for a few minutes. Come back: the run is still going (or, if Android closed it anyway, the request says "Why: …" under "This request did not finish…").

## 1.5.96: a run cut off by the app closing
- [ ] Start a long task with Kimi K2.6. After a few "Thought for…" lines, swipe the app away from recent apps (or let Android close it). Open the app and the chat again: the request shows "This request did not finish…", "▸ So far: …" (tap: the thoughts and steps are there), and **Continue from here** / **Start over**.
- [ ] Tap **Continue from here**: the new run starts from what was found (its first reply does not re-read everything it had already read).

## 1.5.95: the work log
- [ ] With a thinking model (Kimi K2.6), send any task. While it works, the chat shows "▸ Thought for Ns: …" lines; tap one and the full thinking opens; tap again and it folds.
- [ ] When it finishes, leave the chat and open it again (Chats). Under the reply: "▸ How it worked: Thought … · … steps". Tap it: thoughts, notes and steps in order; tap a step for the full command.
- [ ] Chat ⋯ → Export or summarize → Markdown: the file has a "How it worked" section with the thinking quoted.

## 1.5.94: re-prompt limit and model advice
- [ ] Settings → pick a provider → load its models: the list starts with "For coding, pick a large model that supports tool calls…".
- [ ] Help → API keys mentions Kimi K2.6, GPT-OSS 120B and Llama 3.3 70B, and the Groq entry says GPT-OSS 120B.
- [ ] Agent mode with a weak model (e.g. Llama 3.3 70B): ask for a small fix. If it keeps stopping early, it is sent back at most 3 times, then ends with "Not finished:" and the reason.

## 1.5.93: bug reports that start with "When"
- [ ] Agent mode, any project: type "When I open the page the title is missing. It should say Hello." The assistant changes a file (it does not just explain and ask).
- [ ] Type "When does the page load the script?" It answers without changing anything.

## 1.5.92: no app change
- [ ] The bottom of the ⋮ menu says "About: PocketIDE 1.5.92". Nothing else changed on the phone.

## 1.5.91: no app change
- [ ] The bottom of the ⋮ menu says "About: PocketIDE 1.5.91". Ask any model "what does README.md say?" and check it still answers. Nothing else changed on the phone; the release adds benchmark tasks.

## 1.5.90: calls written as text, searching one file, the person's guess, unstyled classes
- [ ] Settings → Cloudflare, model `@cf/meta/llama-3.3-70b-instruct-fp8-fast`: ask "what does src/cart.js export?". If the model writes the call as text, a note in the chat says "running it as a call" and the answer quotes the file.
- [ ] Ask "search for TODO in index.html": the result lists the lines in that file (before, a search given one file found nothing).
- [ ] In a project where the price is wrong in `cart.js`, say "I think the format function is broken": the reply checks it, says the cause was elsewhere, and fixes `cart.js`.
- [ ] In a project that is not a web app, ask for a small fix: the reply does not mention an APK.
- [ ] Settings → Rounds per request → Custom number 20, then give a long task: the last rounds go to finishing and checking the changes already found, not to a new approach.
- [ ] In a project with `getUser` used in several files, ask "rename getUser to findUserById everywhere": no file except a CHANGELOG still has `getUser` when the reply ends (a missed place is sent back once with its line).
- [ ] In a Node project, ask "run the tests and tell me what fails": the reply never says Node or npm is not installed (if a model claims it, the app checks with `command -v` and sends it back).
- [ ] In a plain web project (no game folder) whose `index.html` loads a missing `app.js`, ask for a small change: the check is named "the app's link check of index.html" and nothing mentions game_cache.
- [ ] In a Node project with `src/money.js`, ask "write tests for src/money.js with node:test": the test file imports `test` from `node:test`, the tests run, and none was replaced by `console.log`.
- [ ] In a plain page with `index.html`, `app.js` and `style.css`, ask "add a dark mode button that toggles a 'dark' class on the body": tapping it in the preview turns the page dark (`style.css` has a `.dark` rule; if the model left it out, it was asked once to add it).

## 1.5.89: Cloudflare models and finishing answers
- [ ] Settings → Cloudflare, model `@cf/openai/gpt-oss-120b`: ask for a small change. It answers and edits (before, every request failed with "Bad input").
- [ ] Same model: ask "explain this project in detail". The reply is not cut off after a few lines.
- [ ] Turn on airplane mode mid-request with Cloudflare: the error names "Cloudflare Workers AI" and does not show the account id or the full address.
- [ ] Ask the assistant to edit `cart.js` in a project where it is `src/cart.js`: the tool result names `src/cart.js` and the edit lands there.
- [ ] Ask a question whose answer is in a file ("what is the tax rate?"): the reply gives the value, not "I should look at this file".

## 1.5.88: RuneScape add-on
- [ ] Settings → Add-ons shows "RuneScape game caches", ticked.
- [ ] In a project with a game cache, ask for a game set in Lumbridge: the game folder is built from the cache first, as before.
- [ ] Untick the add-on and ask again: no cache tools and no cache notes; the assistant builds without them.
- [ ] In a project without a cache, with the Game developer profile, ask for a small platformer: it starts from a starter kit (game_assets) without first asking for the tool.

