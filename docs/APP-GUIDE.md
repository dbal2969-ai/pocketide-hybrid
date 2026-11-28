# PocketIDE: the full app guide

Everything PocketIDE does, feature by feature. For the #BuildHybridAI entry, start at the [README](../README.md).

> **#BuildHybridAI 2026 entry:** this repository is PocketIDE with hybrid AI (Gemini Nano on the phone, a cloud Gemini model
> through Firebase AI Logic when needed). See **[HACKATHON.md](../HACKATHON.md)** for what was built, how it works, and how to try it.

A phone-only AI code editor, written in Kotlin with no third-party libraries.

- Code tab: editor with line numbers, syntax colours, auto-indent, undo/redo, find and replace, symbol keys. Images show as images.
- Preview tab: runs your HTML, CSS and JS in a WebView, served from the project folder.
- Terminal tab: Android's own shell, in the project folder.
- AI tab: chat with your own API key (OpenRouter, Groq, Cerebras, Mistral, Gemini, OpenAI, Anthropic), a local llama.cpp server, Ollama, or any custom OpenAI-style server. Changes arrive as reviewable cards. Agent mode applies them, loads the page, and asks the model to fix errors (up to 3 tries).
- Investigation: before answering, the assistant can read files, search, list folders and run commands (`ACTION: read|grep|list|run`), up to 6 rounds per request. Each step shows as a chip you can tap to see what it saw. Commands only run without asking if "Run commands without asking" is on.
- Git button: pull from and push to a GitHub branch, see what changed since the last sync with a line diff, and revert single files. Uses the GitHub API, so no git install is needed.
- Terminal commands have a time limit and an output limit, and Stop kills the command and everything it started.
- Settings: "Test connection" lists the models a provider or local server offers, and you can tap one to use it.
- Top bar: ☰ (file tree), the project name (project picker), Git, Settings and ⋮ (Projects, Search in project, Models, Help & FAQ, zip export/import, welcome screen).
- Models (⋮ > Models): see whether Ollama is running, download phone-friendly models with one tap (with a progress bar and cancel), see which ones suit this phone's memory, switch the AI tab to a model, and delete models. In the MIT edition Ollama runs in Termux; the screen shows the commands with Copy buttons.
- Help & FAQ (⋮ > Help & FAQ): step-by-step guides for free API keys (Groq, Gemini, Cerebras, OpenRouter, Mistral) with links and a "Paste key in Settings" button, plus local models, GitHub tokens, agent mode and troubleshooting. Searchable.
- First run: a welcome screen to choose a free cloud key, a local model, or the demo.
- Settings: "How do I get a free key?" and Paste under the key field. Chat errors get a "Get help" button that opens the matching answer (wrong key, rate limit, model not found, server unreachable).

## Projects and the editor (0.4)

- Projects: several projects side by side, each its own folder (`files/projects/<name>`), with its own chats, GitHub link and open tabs (kept in `files/meta/<name>`, never inside the project). Tap the project name for the picker: New (templates: Web page, Empty, Python, Node.js, Go, Java), Clone from GitHub, Rename, Duplicate, Delete. The single project from earlier versions becomes "My project" on first start, keeping its GitHub link.
- File tree: ☰ opens a drawer with the project's folders. Tap a file to open it; long-press for New file/folder here, Rename, Move to folder, Duplicate, Delete.
- Tabs: open files show as tabs above the editor (✕ closes, ● marks unsaved changes); they are remembered per project.
- Search in project: 🔍 in the editor (or ⋮ → Search in project) searches every text file up to 1 MB, with case, whole-word and regex options; tap a result to jump to the line. Replace all asks first and shows how many matches in which files.
- Editor: matching-bracket highlight, Go to line, bigger/smaller text (or pinch), word wrap on/off, light or dark editor theme (⋯ menu or Settings).
- AI autocomplete: tap **AI ⇥** in the key row to ask your provider for a suggestion at the cursor. It appears in grey; **Tab** or Accept keeps it, typing or Dismiss drops it. "AI suggestions while typing" in Settings asks automatically after a short pause (off by default, because every suggestion is a request to your provider).

## GitHub sync

1. On github.com, open Settings > Developer settings > Personal access tokens > Fine-grained tokens > Generate new token.
2. Repository access: "Only select repositories", pick your repository. Permissions > Repository permissions > Contents: Read and write. Generate and copy the token.
3. In PocketIDE open the project, tap Git, enter `owner/repository` (or the repository URL), the branch (usually `main`) and the token, and Save.
4. Pull first. On the first pull choose "Replace project" to get an exact copy of the branch, or "Keep my files" to merge it into what you have.
5. Edit, then Git > Changes to review, and Git > Push. If someone pushed in the meantime, PocketIDE asks you to pull first; files changed in both places are listed so nothing is overwritten silently.

An empty repository can be started with Push. Files over 20 MB are skipped, and symlinks and submodules are left alone.
The sync state lives in the app's private storage, separate from the project folder.

## Local models

Pick "Local llama.cpp server" (default address `http://127.0.0.1:8080/v1`) or "Ollama (local)" (`http://127.0.0.1:11434/v1`) in Settings.
The server runs outside PocketIDE, for example in Termux (`llama-server -m model.gguf --port 8080`, or `ollama serve`) or on a computer on your network.
PocketIDE does not bundle llama.cpp or Ollama.

For Ollama, ⋮ > Models does the rest: it checks the server, downloads models from the curated list (or any name from ollama.com/library) and switches the AI tab to one.
Its "Runs well / Slow / Too big" labels are a rough rule: a model needs about its download size plus 30% and 0.5 GB of memory; up to 35% of the phone's memory runs well, up to 65% runs slowly, and anything over 3 GB is at best slow on a phone CPU.

## Build

Push this folder to a GitHub repo. The workflow in `.github/workflows/build-apk.yml` runs the unit tests and builds the APK;
download `PocketIDE-debug-apk` from the run's Artifacts. On a phone, some unzip apps hide folders that start with a dot.
If `.github` is missing after unzipping, create `.github/workflows/build-apk.yml` on github.com instead.

Locally: `./gradlew testDebugUnitTest assembleDebug` (JDK 17, Android SDK 36).

## Layout

`core/` has all logic that does not need Android (edit blocks, reply parsing, context ranking, diff, streaming decoder, prompts, highlighter, GitHub sync, investigation actions) and is covered by unit tests.
The rest is the Android layer: views are built in code, so there are no layout files.

Licenses: see `LICENSE` and `NOTICE.md`.

## 0.3.1

- Replies from models that ignore the edit format (a file name followed by a code fence or a `=====` divider) are now turned into files. An existing file is only replaced when the reply looks like the whole file.
- Small local models (under 7B) get a one-time hint to use a free cloud model when asked to build a whole program.
- Local models: thinking is shown as progress instead of a blank wait, `<think>` sections are hidden, Qwen3 is asked to answer directly (`/no_think`), only 8,000 characters of project context are sent, and the app waits up to 10 minutes for the first word.

## 0.4.2 — rate limits wait and retry
When a provider answers "rate limit, try again in 15s" (Groq's free tier does this often), PocketIDE shows a countdown and retries by itself, up to 3 times. Longer limits (daily limits, billing) still show as an error.

## 0.4.1 — any file in and out

- **Add files from your phone** (⋮ → Add files from phone, or + Add files in ☰; long-press a folder to add into it). Files are copied into the project, up to 64 MB each, without loading them into memory.
- **Save a copy to your phone** from the ☰ list (long-press a file, or a folder for a zip), from the editor's ⋯ menu, and from the ⬇ buttons ("Files ready") that appear after the AI or a command creates or changes a document, build or archive.
- **Viewers in the Code tab.** PDFs are drawn page by page (Android's PdfRenderer); Word, Excel, PowerPoint, OpenDocument, EPUB and zip/jar/apk files show their text read-only. Compiled files such as `.pyc` and any file with a zero byte in its first 4 KB are treated as binary, so they no longer open as garbage text.
- **The AI can read them.** `read` gives the text of .docx, .xlsx (cells with their addresses and formulas), .pptx (with speaker notes), .odt/.ods/.odp, .epub, .pdf (text only, no OCR) and the file list of archives. The readers are pure Kotlin on `java.util.zip` and are unit tested.
- **Attach any file in chat** (📎 → File from phone). Pictures are attached as images; other files ask each time: Add to project (the AI can read and change it) or This message only (read-only).
- Word, Excel, PowerPoint and PDF files are read-only in this edition; the GPL edition can edit .docx, .xlsx and .pptx.
- The thinking timer starts when you send and shows seconds, for example "Thinking… 12s" or "Waiting for the model… 45s", even before the server has answered.
- Fixes: a reply that names an existing file and gives a full code block of the same language now updates that file; plans written as bullets are accepted; plan proposals are no longer saved into the chat history (small models copied them and planned every answer).

## 0.4.0 — the AI tab works like Claude Code

- **Saved chats per project.** Chats → list, open, rename, delete, New chat. The model sees the current chat's history (newest messages, within a size budget).
- **Project memory.** If `POCKETIDE.md` exists in the project, the assistant reads it before every request (up to 4,000 characters). ⋯ → Project memory creates and opens it; `/remember <rule>` adds a line.
- **Plan → approve → build.** Big requests (and `/plan <task>`) first get a numbered plan you can Approve, Edit, Skip or Cancel. Each step then runs as its own turn with a live checklist. With Agent on the steps run back to back; with it off you review each step's changes and tap Continue.
- **Checkpoints.** The files each turn or step changes are saved first. ⋯ → Checkpoints restores the project to before any of them (and saves the current state first, so a restore can be undone). Files changed by commands are not tracked.
- **Native tool calling** on Groq, Cerebras, Gemini, OpenRouter, OpenAI, Mistral and Anthropic: the model reads, searches, lists, writes and edits files, runs commands (asking first unless "run without asking" is on) and shows a plan, each as a chip you can tap. Agent off stages the writes as review cards. A model that refuses tools switches to the text format automatically. Settings → "Native tool calling" turns it off; local models and the demo always use the text format.
- **Images.** 📎 attaches a picture from the gallery or a screenshot of the Preview tab (resized to 1280 px). Gemini, OpenAI, Anthropic and OpenRouter vision models can see them; other providers get a warning first.
- **Slash commands.** Type `/` for suggestions: `/plan`, `/fix` (sends the last errors), `/explain`, `/test`, `/commit` (writes a commit message, then opens Git), `/remember`, `/clear`, `/model`.

## 0.4: Run and Problems

- ▶ in the top bar runs the project: web pages open in Preview and `.sh` scripts run in the Terminal tab. Long-press it for targets and custom commands.
  Python, Node, Go and other languages need the Linux environment of the GPL edition (or Termux).
- Errors in command output are collected in a Problems panel in the Terminal tab: tap one to jump to the line, or "Ask AI to fix".
- The Preview tab can also show a local server address (for example one running in Termux), and offers "Open in Preview" when a command prints one.

## 0.5.0 — edit documents without the AI, better model handling
- Edit Word, Excel, PowerPoint, OpenDocument, EPUB, zip and CSV files directly: open the file and tap ✎ (add heading, replace text, set cell, add slide, and more). Works in both editions, with no AI and no Python.
- The AI can use the same actions (`doc_outline`, `doc_edit`), so small models can edit documents too. With Agent off, changes show a review card with a text diff first, with Apply, Reject and Undo.
- Asking for an APK explains, step by step, how to push the project to GitHub and add the build workflow.
- Model chip in the chat header: switch provider or model, pick from the provider's real model list (searchable), and save profiles such as "Cheap fast" or "Smart slow".
- "Model not found" errors offer a Pick a model button with the closest names.
- Models of 8B or less get a warning before file edits, with suggestions.
- Groq's free limit (8,000 tokens a minute) is handled by sending smaller requests, learned from the first limit error.
- ⋮ → Diagnostics: device info, project file test, editor self-test, provider and model check, last crash. Copy the report.

## 0.6.0 — remote servers, sync, error log
- Remote server (GPU or PC): a provider for a bigger model on your own computer (Ollama over Tailscale) or a rented GPU. ⋮ → Remote server setup has the steps; Test connection explains failures in plain words.
- Sync (Supabase): ⋮ → Sync chats & backups. Uses your own free Supabase project. Syncs chats and model profiles (newest copy wins) and backs up or restores a whole project. API keys are never synced.
- Error log: recent model errors, failed edits and failed commands appear in ⋮ → Diagnostics (keys are hidden).
- Each AI reply that changes files now has a "↶ Undo these changes" chip under it.
- Help text for document editing updated.

## 0.7.0 — verify loop, usage and cost, more free providers
- With Agent on, after the assistant changes code the app runs the project's tests (npm test, pytest or unittest, go test, cargo test, make test) or else syntax checks, and sends failures back to the assistant to fix, up to 3 attempts. Settings can turn this off.
- Usage & cost (⋮ menu): tokens in, cached and out per reply, per day and per model. Enter prices per million tokens to see costs; free and local models show tokens only. A line under each reply shows what it used.
- New provider presets: NVIDIA NIM, Hugging Face, Ollama Cloud. Pick a model with Test connection, since catalogs change.
- Mistral's help text now says it may ask for a $10 deposit.

## 0.7.1 — chat export and summaries, more free providers
- Chat ⋯ → Export or summarize this chat: save the full log as Markdown (API keys hidden), save it as JSON to restore later, make an instant summary from the record, or have the model write one (shows the token estimate first). "New chat from this summary" opens a fresh chat carrying the summary forward, which avoids resending a long history. Files go in the project's `chat-logs/` folder and are not sent to the model as context.
- Assistant replies now keep the commands they ran in the chat record.
- New provider presets: SambaNova, Cloudflare Workers AI (replace ACCOUNT_ID in the address), Kilo Code gateway, LLM7.io and OVHcloud.

## 0.8.0 — cheaper requests: caching and smart routing
- Prompt caching: the instructions now stay identical between requests and the project files travel with your message, so providers can reuse the start of the request at a lower price. Anthropic requests mark the reusable part; OpenAI-style providers match it automatically. Settings can turn it off.
- Smart routing (Model chip → Smart routing): pick a cheap profile and a strong one. Questions and small edits go to the cheap model; builds, multi-file changes, document edits and bug reports with details go to the strong one. A line under the reply says which was used, with a one-tap retry on the other. Off by default.
- Usage & cost now shows cached tokens apart, so the saving is visible.

## 0.8.1 — code intelligence and self-checks
- The assistant has two new tools: `find_symbol` (where a function, class, type, CSS rule or HTML id is defined and used, across the project) and `file_outline` (the definitions in one file with line numbers, without reading the whole file). This is how it can change something safely without reading everything.
- ⋮ → Find a definition: a searchable list of every definition in the project; tap to jump to it.
- ⋮ → Diagnostics now also runs the assistant decision checks: a table of example requests with the routing, verify command and small-model warning each should produce.

## 0.8.2 — smaller requests
- Command output sent back to the assistant is cleaned up first: terminal colour codes removed, runs of the same line collapsed ("… same line ×40"), progress bars dropped, and long output cut around the parts that look like problems.
- If the assistant reads a file it has already read and nothing has changed, it is told so instead of being sent the text again.
- A long message with the same block pasted twice is trimmed before sending, and the chat says how much was removed.

## 0.9.0 — starters, installs, long chats, applied-edit review
- ⋮ → New starter project: a web page, a Python script with a test, a Python web server, a Node project with a test, or a C program with a Makefile. Each one works with the Run button and the assistant's verify step.
- When a command fails because something is not installed, the Terminal offers a button that installs it (an Alpine package, a one-tap tool, or a pip package for a missing Python module).
- Long chats now send a short digest of the older turns instead of every message, which keeps cost down without losing the thread.
- With Agent on, a document edit now also shows a before-and-after diff card with "Undo this change".

## 0.9.1 — picture and PDF actions, preview console
- Open a picture or a PDF and tap ✎: resize, crop, rotate, flip, convert or make a thumbnail; for PDFs, see the page count, keep only some pages, rotate pages, or save pages as PNG. Results are written as new files next to the original, so nothing is overwritten. PDF page edits redraw the pages as pictures, and the app says so before you apply them.
- Preview tab: a Console button shows everything the page printed, including errors, with "Ask the assistant" to send it to the chat. A Size button previews the page at phone, tablet or desktop width.

## 0.9.2 — safer commands, fixing the app itself
- Before the assistant runs anything that writes, the app saves a checkpoint, so one tap puts the files back.
- The run card now says in plain words what a command will do. Commands that delete, push to Git, reach outside the project or use the network are always asked about, even with Autorun on. Installs and builds get a longer time limit.
- ⋮ → Fix the app itself: send the last crash and the recent problems to the assistant (API keys removed), or fetch the APK that GitHub built from the current source and install it. Nothing installs without you tapping Install, and the build can also just be saved to Downloads.

## 1.5.178 — small local model mode; Ollama Cloud works from the phone again

- **Ollama Cloud on the phone:** every chat request was answered with an HTML "403 Forbidden" page. Ollama Cloud's edge refuses
  Android's default User-Agent ("Dalvik/2.1.0 …"); Test connection passed because it already sent "PocketIDE". Every request now
  says "PocketIDE/<version> (Android)", and a web page sent instead of an answer is shown as one line of text, not its tags.
- **Small local model mode** (local models of 8B or fewer: Ollama, llama.cpp or another local server). One action per reply as one
  JSON object `{"reason", "tool", "tool_args"}`, asked of the server as a JSON schema; the reason comes first, and the key names sort
  in that order (Ollama is reported to write schema keys alphabetically). Seven tools plus "answer", which needs its text. A
  1.6k-token prompt instead of 3.6k. After every result the app names the next step and the file ("Next: read the file: tool
  "read_file" with tool_args {"path": "src/cart.js"}"), with real values only (a 1.5B copied placeholders as values). Short files
  (up to 150 lines) are rewritten whole, longer ones edited by line number. A repeated identical call is refused after 2 (not 3).
  Qwen's sampling profile (temperature 0.7, top-p 0.8), from MobileCoder.
- **Local model lineup:** Qwen3.5 4B (8 GB phones) and Granite 4.2 8B (12 GB and up) are recommended; Qwen3.5 0.8B, 2B and 9B and
  Granite 4.2 3B are listed; Qwen2.5 Coder stays as an older choice. The Models screen warns when Ollama is too old for a model
  (Qwen3.5 needs 0.17.4; the built-in Alpine Ollama is 0.17.7).
- **Ollama starts with an 8k context** (its own default on a phone is 4k, too short for the assistant), a q8_0 KV cache and a
  30-minute keep-alive: the Termux command, the built-in Linux service and the help page.

Measured (standard suite, 7 tasks, CPU stand-in for Ollama, same server and settings; 1.5.177 = this mode before the key rename, the
edit format by size and the repeat guard, which are not yet measured):

| Model | 1.5.176 | 1.5.177 |
|---|---|---|
| qwen2.5-coder:7b-instruct-q3_K_S | 4 of 14 (2 runs), 2 labelled done when wrong, ~80 min a run | 7 of 14 (2 runs), 3 labelled done when wrong, ~43 min a run |
| qwen2.5-coder:1.5b | 2 of 21 (3 runs) | 1 of 7 (1 run; two runs void: the server ran out of memory) |

Fourteen runs cannot separate 4 from 7 with confidence, and the done-when-wrong count did not fall; the research behind this mode
(HANDOFF-LOCAL-MODELS-1.5.178.md in the GPL edition) says the next gains are app-run checks for the labels and a 30-task suite.

## 1.5.176 — small local models: their tool calls are read and kept

Measured on local Ollama models (qwen2.5-coder 1.5B, and 7B at q3_K_S, the largest build that fits the 6 GB test machine), standard
suite, same server and settings before and after:

| Model | 1.5.175 | 1.5.176 |
|---|---|---|
| qwen2.5-coder:7b-instruct-q3_K_S | 1 of 7, 11 tool calls, 2 labelled done when wrong | 3 of 7, 46 tool calls, 0 labelled done when wrong |
| qwen2.5-coder:1.5b | 0 of 7, 7 tool calls, 2 labelled done when wrong | 1 of 7, 34 tool calls, 1 labelled done when wrong |

- A tool call written as text with its name under "function" or "tool" (`{"function": "read_file", "arguments": {…}}`, as the 1.5B
  writes it) is now run like one under "name" (TextCalls).
- A call written as text and run by the app now also stays written in the reply. Ollama's template for qwen2.5 shows a message's text
  or its tool calls, never both, so the model never saw what it had called: the 7B searched for "SAVE10" five times in a row.
- The "call tools through the tool interface" ask is sent once. A model that can only write calls as text answered it every round by
  apologising and starting over; from the second call it is told to go on, one call per reply.

## 1.5.175 — a check that only prints is not "checked"; removals are checked; free-provider texts updated

- "Remove the coupon feature", "we dropped coupons": before the work is called done, the project is searched for the removed thing
  (RemoveCheck). Places that still mention it go back to the model once, and any left at the end keep the verdict open, so the reply
  no longer ends "✓ Done and checked" while code still uses it. Found in a free-model run (Cohere North Mini Code on Kilo): the
  tests passed, the answer said "Nothing left references coupons", and src/cart.js still did.
- Ollama Cloud: the provider note and help now say it needs usage credits or a subscription (a free account can have no included
  usage; every request then answers 402). The old text said limits reset every few hours.
- Requests to OpenRouter now name the app (HTTP-Referer and X-Title), as OpenRouter asks of apps; some free models are only served
  to coding apps it can identify.
- The parts-team example no longer names a Cloudflare model that Cloudflare retires on November 20.
- A check that only prints is no longer "checked". `node -e` / `python3 -c` code that prints results and asserts nothing cannot fail on
  a wrong result; in the free-model survey 37 of 111 runs labelled "finished (checked)" rested only on such code, and 7 of them were
  wrong (Nemotron 3 Ultra printed every rule of a parser but never tried "250 1h"). Such a run is now recorded "(printed only)" with no
  verdict, does not tick plan steps, and the model is asked once to turn what it expects into assertions, error cases included. With
  nothing else passing, the reply ends "changed, not checked". Labels of multi-line commands now show `node -e "…` instead of `node -e "`.
- Model suggestions: Recursive Flint (free on OpenRouter) is listed as measured, 27 of 29 survey tasks.
- Benchmarks: the default model is now openrouter:recursive/flint-v1.0:free (Kimi K2.6 leaves Cloudflare on November 20), and results
  can go to a Neon Postgres database (tools/bench/neon_upload.py; in Actions when a NEON_DATABASE_URL secret is set).
- tools/bench/neon_upload.py: uploads LiveBench reports to a Neon Postgres database (NEON_DATABASE_URL) over HTTPS, one row per
  task run with its end label and whether the label overclaimed.

## 1.5.174 — game kit: every floor drawn on its own

- `game_cache export kind=scene` now bakes floors 1, 2 and 3 apart (baked squares version 3; sections 2-4). Version 2 put all
  upper floors together, so a game could not draw the floor the player stood on without the roof above it: a player who climbed
  the stairs saw the roof, not the room. The kit's 3D view reads both versions; it draws every floor up to the player's, and the
  floors above only while the player is out in the open. Bakes are written at the strongest gzip level (about 17% smaller).
- Games made before this keep working; export the scene again to get floors drawn apart.

## 1.5.173 — fixes from a field chat: "syntax checks" are not "checked", failed plan steps, tests cut by a # line

- The app's fallback check ("syntax checks") only shows the files parse. It counted as a check that ran the code, so replies
  were headed "✓ Done and checked: syntax checks", one of them saying 906 of 907 NPC pictures were still missing. It now leaves
  the reply at "⚠ Changed, not checked" unless something that runs the code passed.
- A plan step the assistant marked failed now makes the reply "✗ Not finished" instead of sitting under "✓ Done and checked".
- A line starting with # in tests/play.txt names a new test. When a change cuts an existing test in two that way (a # line meant
  as a comment), the assistant is told at once which test lost its steps, instead of the old test quietly passing on fewer checks.

## 1.5.172 — checks written over several lines count, and a test step that can never pass is pointed out

- A check written over several lines (`node -e "` with the code on the next lines, or a here-document fed to node or python) is
  now recognised as a check when its code loads the project. Before, such a run was labelled "⚠ Changed, not checked" although
  a real check had passed. Text inside quotes is no longer read as a command, so `echo "run npm test"` does not count.
- When the same step of a saved play test fails with the same message three times in one request, the assistant is told once
  to check the step itself, since a step that can never pass sent it back to change working code (roadmap phase 1d).
- Chat search no longer shows the text the app added after your message ("The person approved this list…").

## 1.5.171 — Approval for saved-test changes appears when it is asked

- When the assistant wants to change a saved test (`tests/play.txt`), the Allow / Keep card now appears straight away,
  and the run waits for your answer. Before, the card appeared only after the assistant had stopped, so Allow came too late.
- Allow writes the change and tells the assistant at once, so it can run the test again. Keep leaves the test as it is and
  tells the assistant to fix the app instead.
- With no one to ask (benchmark runs), the change is still proposed at the end of the run, as before.

## 1.5.170 — Telling the truth about the result, with any model

- Every task reply now starts with the app's own verdict, worked out from what it saw rather than from the model's words:
  "✓ Done and checked" (a check that runs the code passed after the last change), "⚠ Changed, not checked", or "✗ Not finished"
  / "✗ Nothing was changed". A small model that says "done" when nothing changed, or when its last check failed, can no longer
  make the reply look finished.
- Commands that only look (`ls`, `cat`, `grep`, `echo`, `--version`) no longer count as checks or tick plan steps; a check has to
  run tests or run the project's own code.
- Messages you send while it works now steer every later reminder and check, newest first; the old request no longer pulls it
  back. "Yes, do it" after a question lets it make the change. "Can we …?" and "Could we …?" are taken as requests, like
  "Can you …?" ("How can we …?" and "Can we analyze …?" stay questions).
- A model whose edits keep failing is given a small set of plain tools for the rest of the request, and a second try keeps its files
  instead of starting over.
- "That isn't possible" with nothing tried goes back once with the tools it has (the projects tool included); the projects tool is
  offered when a message uses part of a project's name ("my scape project").
- Free-tier rate limits: a "per minute" limit that gives no time now waits 20, 40, then 60 seconds, and a gateway's "provider
  returned error" is retried like a busy server; a time the answer states ("try again in 14.8s") is still what it waits. A daily
  limit is shown as an error, not waited for.
- Measured on two models weaker than Haiku (BENCHMARKS.md, trust suite, 30 runs each): replies labelled done when the work had failed
  went from 12 to 3 (Nemotron 3.5 Lightning, free) and from 28 to 3 (qwen2.5-coder 1.5B, local). That is still above the bar of 1 in
  20, and the hard and held-out suites could not be measured yet (the free tier's daily limit).
- Benchmark: a `trust` suite (a scripted person who changes direction, "Can we" requests, impossible requests) scored on whether the
  end label was true; local models through Ollama on the runner (`ollama` input) and a per-task time limit.

## 1.5.169 — Copilot's PR #29 review

- The second try in the benchmark (the repair turn) is no longer told to run checks it cannot run: the "python3 is installed, use
  it" and "run a check" nudges apply only when commands are allowed.
- Asking the assistant to work on a project whose name matches two of yours ("empty project") now makes it ask which one, instead of
  failing; tests confirm no file path can leave a project.
- Input variants recognise "read my station's export; imports/readings-sample.json is one" (a reading verb and a sample file).

## 1.5.168 — the chat with no project works on your projects

- Asked to "take files from one project and make a new branch on my Empty-project-10 with them", the chat with no project said that was
  not possible. It now works on your projects the way the Claude or Copilot app would: name a project in your own words ("my empty
  project 10") and it finds it, reads and searches it, copies files and folders from one project to another, makes a branch and
  switches to it, and commits. In a project's chat the same tool reads your other projects, and in Agent mode copies from them.
- Files it replaces in another project are saved as a checkpoint of that project first, so that project's Undo puts them back.

## 1.5.167 — follows you when you change direction, finds imported projects, keeps its work

From a field run where the assistant was asked to bring quests over from an imported 2009scape server and ended with nothing changed.

- Listing or searching a folder now shows what is really in it. In a big project the file list is shortened, and asking for one folder
  used to answer "No files under …" for a folder with 2,759 source files, so the assistant decided the imported server's source could
  not be read. A named folder is now walked on its own, a big folder is shown as its sub-folders with file counts, and the note about
  the shortened list counts every left-out file under some folder instead of naming only the twelve biggest.
- When a request takes things from a project kept inside yours ("from the 2009scape-project I imported, add the quests"), the
  assistant is told which folder that is, how big it is and where its source code is, and to read those files before writing instead
  of writing a simplified version from memory. You no longer have to give the path.
- A message you send while it works now decides the job: "focus on the quests" narrows the work, and its plan may change to match
  instead of being forced to keep the old steps.
- The automatic second try no longer throws work away. It does not start at all when you have been sending messages into the run; a
  first attempt that worked on files for five minutes or more keeps its files and carries on from them; and when a short attempt is
  undone, a button brings its changes back. Saved progress is put back with the files, so it never says "done" for work that was undone.
- Pictures drawn by the game tools are part of Undo and the second try like every other change.
- The requirements list for a big job comes from your request: points copied from a plan or notes file already in the project
  (PLAN.md, TODO.md) are taken off the list and shown, so you can add any back.

## 1.5.166 — benchmark only: two new exam suites

- No change to the app. Two new benchmark suites, kept apart from the others: `premise` (12 requests about things said to have
  disappeared or broken: some never existed, some are there but hidden, some already work) and `variety` (6 readers for outside input,
  checked on shapes the sample does not show). They are how the two experimental settings will be judged.

## 1.5.165 — fixes from Copilot's review of 1.5.164

- The automatic check still checks a git submodule you list in .gitmodules; only folders downloaded with their own git history are left
  out.
- The experimental "disappeared" search reads at most 500 files and 200 KB of each, so a big project cannot stall the start of a request.
- The stand-in check no longer flags tests, mocks and docs, and it also reads clone commands in scripts/ and Makefiles.

## 1.5.164 — questions from a step are shown, no more plan-check loops, and two new experimental checks

- When a step of a multi-part job ends by asking you something, the job now stops and shows the question, with **Answer**, **Use its
  best guess** and **Stop**. Before, the next step started and the question was never seen.
- A step that writes a stand-in by hand into a folder that one of the project's scripts downloads with git (or leaves "placeholder" or
  "not implemented" code) is now caught by the step's own check.
- The plan list no longer refuses the assistant's plan over and over: after one refusal, a reworded plan is taken and the steps it left
  out are kept.
- The automatic check after changes leaves out code the project downloaded (a folder with its own git history).
- Two new experimental settings in Settings → Assistant, off by default and not yet measured: "When a request says something
  disappeared, search for it first, and report it if it was never there", and the input check now writes other shapes of your sample
  file (Windows line endings, quoted fields, columns swapped and more) for the assistant to try its code on.

## 1.5.163 — benchmark only: the second try is scored as Aider scores it

- No change to the app. In the polyglot benchmark, a failed exercise's second try now carries on the same conversation and sees the
  whole test output, as Aider's does; before it started afresh with the last 50 lines.

## 1.5.162 — the explore helper is off by default

- The explore helper (a second model call that reads the project for the assistant) is now off unless you turn it on in Settings →
  Assistant → "Explore helper". In a planned test, 65 real-project tasks a side on Kimi K2.6, the assistant passed 47 without it
  against 43 with it, and used well under half the tokens.

## 1.5.161 — a fresh second look before finishing (experimental), and new benchmark tools

- New setting, off by default: Settings → Assistant → "Before finishing, have a fresh reviewer compare the request with the changes".
  When the assistant is about to finish, one extra model call sees only your request, the changes and the assistant's last message,
  with no tools and none of the conversation, and lists anything asked for that is missing, any change nobody asked for, and any
  claim the changes don't back up. What it finds goes back to the assistant once. If the call fails or takes over 30 seconds, the
  request finishes as it would have.
- Benchmark: a sealed suite of twelve new tasks (`suite=sealed`), kept apart from the others and run only on a final candidate; each
  report says which version and settings it ran with; runs the provider refused no longer count as failures; the Aider control
  workflow (Aider itself on the same exercises and model) now has the test paths it needs.

## 1.5.160 — the page check names text that something covers

- **Look at the page (phone and benchmark):** an overlap is reported only when one element covers another's text, and the line names
  that text, e.g. `Overlap: <img#ship> covers the text of <header#hud> "Brig"`. An icon set on a bar beside its text is no longer
  reported, and a dialog over a page names the page text under it. At most five such lines.
- The experimental "Keep to what was asked" setting: after it asks about a file, the later reminders (few rounds left, the wrap-up, an
  unfinished plan) tell the assistant to leave that file as it is now. In the benchmark, a run put invented entries back as asked and
  then added them again after "make the changes you have already found now". Still off by default.
- Benchmark: a new task, `harbour-tavern`, a real bug plus a screen the game never had.

## 1.5.159 — benchmark only: the measured results of the two experimental settings

- No change to the app. "Keep to what was asked" and the input-variety check stay off by default: on the held-out tasks one lost and
  the other showed no gain (BENCHMARKS.md has the runs). A new benchmark task, `harbour-icon-seen`, repeats the icon bug with the
  squashed top bar named in the request.

## 1.5.158 — the scope check questions changed data files every time

- In the experimental "Keep to what was asked" setting, a data file the request does not name is now always questioned, even when
  the assistant says that file was the cause. In the benchmark, the runs that invented an item wrote "the cause: the data had no
  entry for it", which excused the very change they made. Off by default, as before.

## 1.5.157 — the scope check also asks during the wrap-up

- The experimental "Keep to what was asked" setting (1.5.156) now also asks about extra edits when a run is wrapping up, which is
  where most long runs end. Off by default, as before. The benchmark showed the older data-file question skipped in exactly those runs.

## 1.5.156 — keep to what was asked (experimental)

- **A new setting, off by default until measured** (Settings → Assistant, "Keep to what was asked"). When the request says the
  project has something it does not (an item, a screen, a name search can't find), the assistant is told to say so rather than add
  it. Before finishing, it is asked once about files the request did not name and it did not name as the cause (for problem
  reports; for other requests, data files only; tests are never questioned): say why each is needed, or put it back. Its explore
  helper reports where things are without suggesting other files to change.
- Benchmark: a `scope_check` input, and a seventh harbour task, `harbour-pearls` (a real bug plus a claim the data does not support).

## 1.5.155 — switching dark mode keeps your place

- Switching the phone to dark or light mode (by hand, or automatically at sunset) closed whatever was open and showed the home
  page. PocketIDE's look doesn't change with that setting, so the screen now stays as it was. Found on the Android 16 emulator,
  which also confirmed that the advanced editor keeps its file through rotation, leaving the app and coming back, and Back, and that
  a run keeps working with the screen off.

## 1.5.154 — try importers on other inputs (experimental), page check on large canvases

- **Code that reads outside input can be tried on other shapes before it is called done** (Settings → Assistant, off by default
  until measured). When the request is to parse, import or scrape CSV files, web pages, feeds or exports, the assistant is asked
  once, before finishing, to run its code on two inputs in shapes the example did not show (quoted fields, blank lines, Windows line
  endings, columns in another order; for pages, the text in one block, a list or sections) and fix what breaks.
- **The page check reads large canvases in steps**: at most about 1.5 million pixels, every n-th on a larger canvas, so a phone's
  full-screen canvas no longer falls back to judging by picture size. WebGL canvases are read too (tested in Chrome).
- Benchmark: a `variety` input, and a sixth harbour task, `harbour-import` (one clean CSV example; the hidden check feeds the shapes
  other spreadsheet exports have, and an importer written for the example alone fails it).

## 1.5.153 — the page check sees small drawings

- **A canvas with a few small marks is no longer called blank.** The page check judged a large canvas by the size of its picture,
  so a map with a few dots or a dark scene with one sprite was reported as "the page draws nothing", and the assistant went looking
  for a game that does not start. The check now reads the canvas's pixels (from a copy, so it never takes the page's own drawing
  context), and the size is only used when the pixels can't be read. Found by the harbour suite's page-check run.

## 1.5.152 — the harbour suite hardened

- Benchmark only. The first `harbour` run passed 14 of 15, too easy to show whether cause-first helps. The project is now 49 files:
  the causes sit a step further from the symptom (a type table, a sea-events module, a comparator helper), comments no longer
  describe the intended behaviour beside each bug, and most tasks have a second wrong-place fix to avoid (a shared converter, the
  cart, the stylesheet, the shared centring helper). Every check is proved as before.

## 1.5.151 — Copilot's ANALYSIS-1.5.150 P1 and P2, and the harbour project

- **Secret files stay off the search by meaning** (P1). More names are left out (Android signing properties, cloud service-account
  and Firebase admin keys, Terraform variables, Apple `.p8` keys, git and Kubernetes credentials, password databases, VPN profiles,
  WordPress and Ansible secrets, `.env` files), and a config-format file that has a plain `password`, `secret` or `token` line is
  skipped whole. Passwords in code are masked, not skipped.
- **The page check always stops in time** (P2). The browser's output is read on its own thread, so a hung page is stopped at its
  time limit (30 s plus the steps) instead of holding the benchmark task to its 900 s limit.
- **Benchmark: the harbour suite** (`suite=harbour`). A trading-game project in layers (ui, state, loader, util, data),
  independent of the realproject fixture. Five problem reports, each with a wrong-place fix that hides the symptom. The hidden checks
  run the code with their own data and keep the decoy files as they were. Each task is proved to fail as delivered, pass the real
  fix, and fail the decoy.
- The phone's page viewport is unchanged: the WebView ignores the viewport meta tag by default, so `isMobile` stays false (P3).

## 1.5.150 — fixes from Copilot's review of 1.5.146–1.5.148 (ANALYSIS-1.5.148)

- **Notifications mask keys** (M1). The assistant's, the build's and the background-work notifications show on the lock screen; any
  key or token in their text (an error that quotes one) is now masked, and keys of the `ak-` form are recognised everywhere masking
  applies.
- **Cause first is harder to satisfy with filler** (M5): the cause the assistant names must point at a file the project has, and
  `.txt` files no longer count as data files.
- **Benchmark runs are titled with their settings** (M3): suite, tasks, repeats, models, and the ask, cause, look, embed and helper
  settings, so runs that differ in one setting can be told apart in the list.

## 1.5.149 — benchmark page check fix

- Benchmark only: on GitHub's runner, Chrome's own request for `favicon.ico` showed as a console error, so every page check's verdict
  was FAIL. A phone's WebView makes no such request, so it is ignored. Checked on the runner (run 43536416219): look_at_preview ran
  three times in a web task, which passed.

## 1.5.148 — the benchmark can look at the page

- **Benchmark only:** a `look` input lets the model use look_at_preview during a benchmark run, as it can on the phone for web pages.
  Headless Chrome on the runner plays the steps and builds the same report the app does (layout problems, console errors, game
  state, a screenshot), from the app's own page-check code. Pages are laid out 360 pixels wide like a phone's, files come from the
  task's folder only, and requests to other addresses are answered empty. Off by default, so earlier results stay comparable.

## 1.5.147 — cause before change for problem reports (experimental, off by default)

- **Settings → Assistant → "Problem reports: name the cause before the first change, and question changed data files"**, off until
  measured. The largest group of failures on real projects was a fix in the wrong place: the model changed a data file or a shared
  helper so the symptom went away while the cause stayed. With this on, in a problem report the first change waits once until the
  assistant names where the wrong behaviour starts (file and line), and a changed data file the report does not mention (a
  `.json`, `.csv`, `.yaml`…) is questioned once before finishing: put it back and fix the code that reads it, unless an entry was
  really wrong.
- **More problem reports are recognised** (they get the app's "find the cause first" note): "should …, but … still …", a quoted
  wrong output ("says \"1 items\" when…"), and reports that end "Fix it."
- **Benchmark:** a `cause` suite of four problem reports in new domains, each with an easy wrong-place fix (rates data, a menu's
  action list, a shared plural helper, a scores table) that its hidden check rejects; the `cause_first` input turns the check on.

## 1.5.146 — fixes from a review of 1.5.138–1.5.145

- **Search by meaning no longer sends secrets.** It sends your project's text to the embeddings provider you set (in chunks, to
  build the index, and each search's words); before, that could include `.env` files, keys and certificates. Now secret files
  (`.env*`, `*.pem`, `*.key`, keystores, `.ssh/`, credentials and secrets files, `local.properties`, `.npmrc` and the like) are
  never sent, keys and tokens in other files are masked first, and the setting says that text leaves the phone. It stays off unless
  you fill it in (Copilot's ANALYSIS-1.5.145 N1). In a very large project (over about 4,000 chunks, roughly 80,000 lines)
  files past the limit were marked as indexed and never tried again; now a file is indexed whole or not at all, and tried again
  when there is room.
- **The advanced editor closes cleanly if its WebView crashes**, instead of taking the app down; your text up to the last pause
  in typing is already saved (N4).
- **Pull request reviews no longer repeat themselves:** after a new push, a finding the reviewer already posted on that file is not
  posted again.
- **Sweep's next-edit model through Ollama** is now asked with Ollama's raw prompt, so no chat template wraps Sweep's own format.
- **The ask checklist is off for everyone:** Save stored the old default (on) for anyone who saved Settings while it was on; the
  setting moved to a new key, so it starts off and stays as you set it from now on.
- **Benchmark:** in runs with a helper model, the reviewer's conversation was taken for the run's own, so the transcript lost its
  tool steps and the read and search columns were wrong (pass and fail were not affected). The emulator driver now stops on a
  stop file pushed over an old one.

## 1.5.145 — checklist off by default, editor and time-limit fixes

- **The ask checklist is now off by default** (Settings → Assistant, tick box kept). On Kimi K2.6 and on Claude Haiku 5.5 it passed
  no more tasks than without it (Haiku: 21/21 both ways), and in 38 runs it never led to an edit, while costing about a fifth more
  requests. Copilot's review set this rule in advance.
- **Advanced editor:** on Android 15 and later its buttons sat under the status bar, where taps did not reach them (seen on the
  Android 16 emulator); the bars and the keyboard are now kept clear, and Done comes first in the row.
- **The "Following …" note** now shows at the top of the reply instead of under the answer.
- **Benchmark:** a run wraps up at 60% of the 900-second cut-off and ends with what it found, instead of "timed out" mid-search
  (the app already does this at its own limits on a phone). The `ask_check` input now defaults to off, as in the app.

## 1.5.144 — the advanced editor (Cursor parity)

- **Editor menu → "Advanced editor (folding, multiple cursors)"** opens the file full screen in CodeMirror 6: multiple cursors
  ("Next match" adds a cursor at the next copy of the selection, so one edit changes them all), folding ("Fold all", or the arrows
  beside the line numbers), bracket matching, search and replace, comment toggling, and highlighting for JavaScript, TypeScript,
  HTML, CSS, Python, JSON and Markdown. It saves as you type and when you tap Done; the normal editor then shows the changes.
  Inline AI suggestions and next-edit stay in the normal editor. It works offline (the page loads nothing from the internet) and
  adds about 240 KB to the app. CodeMirror and its parts are MIT (`assets/codemirror/LICENSES.txt`; built from `tools/codemirror`).
  Measured in a browser slowed six times to phone speed: a 5,000-line file opens in about a quarter of a second, and a key press
  shows in about 40 ms.

## 1.5.143 — pull request reviews and a local next-edit model (Cursor parity)

- **/reviewbot: every pull request gets a review**, like Cursor's BugBot. It writes a GitHub workflow and a small script into the
  project; once pushed, each pull request that is not a draft gets comments on the lines where the model sees a likely bug (not
  style), and nothing when it finds none. It uses the same repository settings as /cloud. It reads only the pull request's diff,
  runs nothing from the pull request, takes its script from the base branch, and its token can only read code and write review
  comments. Pull requests from forks, drafts, and those labelled `no-review` are skipped. `/reviewbot off` removes it.
- **Next-edit suggestions from Sweep's open model (experimental).** sweepai/sweep-next-edit-1.5B (Apache-2.0, about 1.5 GB) runs on
  a phone in Ollama or llama.cpp. Set it as the suggestion model (for example `hf.co/sweepai/sweep-next-edit-1.5B`) and the editor
  asks it in its own format, then shows its edit after the same checks as any suggestion. Not yet tried on a phone; the app log
  (next-edit) says what it did each time.

## 1.5.142 — the draft model's ask count can only add the checklist

- With a draft model set, it counts the asks in a request (1.5.139). In the first run with one (a small Llama model), its answers
  were read as "1" — the first number of its numbered list — and the checklist was skipped on requests with four asks. Now a
  numbered list is counted by its items, and the larger of the draft model's count and the app's own count decides, so a draft
  model that undercounts cannot switch the checklist off.
- Benchmark: each run's transcript lists its searches by meaning, and says when one failed and fell back to keywords.

## 1.5.141 — Cursor parity, first steps (Copilot's HANDOFF-CURSOR-PARITY)

- **Search by meaning (optional).** Settings → Assistant → "Search by meaning": enter an embeddings model as provider:model, for
  example `cloudflare:@cf/baai/bge-base-en-v1.5`, `openai:text-embedding-3-small`, or a local Ollama model. The project is indexed
  in chunks (only changed files again, at most 4,000 chunks, saved per project), and the assistant's find_code tool merges the
  ranking by meaning with the keyword ranking, so a question in plain words finds code that shares none of its words. It uses that
  provider's key and address; with nothing set, search works as before.
- **A search that finds nothing names the closest places.** About a quarter of the assistant's text searches on a real project
  found nothing (a guessed name). Now the reply also lists the three places closest to it (by keywords, and by meaning when an
  embeddings model is set), so the next step reads instead of guessing another name.
- **Cursor rules, fully.** Rules in `.cursor/rules` and `.cursorrules` were already read and followed (always, or by their file
  patterns). Now rules with only a description ("Agent Requested", and Windsurf's model-decision rules) are listed for the assistant
  to read when one fits, and `@name` in your message brings in that rule (`@react` for `.cursor/rules/react.mdc`).
- **See which instructions a request follows.** A grey line under your message names them: "Following POCKETIDE.md, AGENTS.md
  and .cursor/rules/style.mdc."
- Benchmark: an `embed` input to measure search by meaning; a new `asks` task (ask-plan) whose trap is a per-month figure for a
  yearly price.

## 1.5.140 — two ask-checklist fixes (Copilot's ANALYSIS-1.5.139-ASKS, decision A)

- **No false send-backs over the ask's own words.** The checklist treated words like "unknown", "missing", "todo", "n/a" and
  "skipped" anywhere in a proof as "not done", so a proof quoting `Unknown option: --jsno` was sent back although it was done.
  These words now count only when they are the whole proof, or are said of the ask ("still missing", "is skipped").
- **After a send-back that changed nothing, the answer is the summary.** In 1.5.139 the last message could be the assistant's reply
  to the app ("That ask is already implemented. Here's the proof: …"). The reply is used only when files changed after the
  send-back, since it then says what was fixed.

## 1.5.139 — the ask checklist fixed and measured (Copilot's ANALYSIS-1.5.138-ASKS)

- **Fixed: in 1.5.138 the checklist replaced the assistant's answer.** After a request with several asks, the last message was the
  bare "ASKS" list ("1. … | src/cart.js:9-11") instead of the assistant's own summary of what it changed. The summary is the answer
  again; the checklist shows as a short grey note ("Checked 3 asks: each one shows where it is done"). When the checklist sends an
  ask back, the answer is what the assistant says after fixing it.
- **The checklist no longer uses up the finish checks.** It has its own two turns (the list, and one send-back), so the project's
  finish checks, the "test your change" ask and the others keep all of theirs.
- **A file name in backticks no longer counts as proof.** The proof has to be a file and line (`src/cart.js:9`) or a command and
  what it printed.
- **With a draft model set** (⋮ → Multi-model workflow and advisor → Draft model), the helper counts the asks in the request once, when the
  work is about to finish; without one, the word counter decides as before. The word counter missed requests that describe a
  behaviour and ask for it in other words, and fired on one ask said twice.
- **Benchmark:** the `helper` input now reaches the explorer, the history summary, the reviewer and the ask count (1.5.138 passed it
  only to phase routing). The `asks` suite is rebuilt: six tasks, each with an ask that is easy to leave out (a second file needing
  the same change, a help text, asks inside a description, a file or rule to leave alone, an exact place or message); the two tasks
  that passed every run either way are gone, and every hidden check matches an ask the request states.

## 1.5.138 — token counts in the benchmark, and the ask checklist (Copilot's HANDOFF-RAISE-CEILING, WP7 and WP1)

- **Ask checklist.** When a change request asks for several things ("take 10% off and show the saving; don't let two coupons stack"),
  the assistant now checks the work ask by ask before it finishes: each ask with its proof (the file and line, or the command that shows
  it). An ask with no proof goes back to it once, by name. Requests with one ask, questions and report-only requests are not affected.
  Settings → Assistant → "When a request asks for several things, check each one before finishing" turns it off.
- **Test connection** no longer calls a model "may have been retired" when the provider took the request for it (Z.ai lists only part
  of its models; `glm-4.6v-flash` is free and served).
- **Benchmark:** every run now records the tokens each model request used (the main model's own, cached, output, and every helper
  job's), with seconds per request in each transcript; the summary has totals per suite and for the whole run. New workflow inputs:
  `helper` (a cheaper model for the explorer and summaries), `route` (reading rounds on it too), `ask_check` (0 = without the
  checklist, to compare) and `max_tokens` (a cap per job). A new `asks` suite (three multi-ask tasks) is for tuning the checklist, so
  the real-project tasks stay an untouched score.

## 1.5.137 — from the emulator test of 1.5.136

- **The command card no longer calls `cat </etc/passwd` "This only reads."** It said so and offered "Always allow cat", after which
  such commands would have run without asking. Paths after `<` and `>` (outside quotes), `=`, `:`, `(` and an opening quote now count
  as outside the project, so the card says so and an allow rule never skips the question. `grep '</body>' index.html` still only reads.
- **The 📎 attach button has a spoken name** ("Attach a file or picture") and a full-size touch target.
- **Emulator tests:** the driver skips batches left on `emu/cmd` by an earlier session, checks for `stop` between batches, and types
  in short pieces with a pause (long messages lost their middle).

## 1.5.136 — Copilot's review of 1.5.135 (ANALYSIS-1.5.135.md, PR #12: "Ready for benchmarks")

- **Input redirection asks first:** `cat </etc/passwd` read any file without a card. A `<` outside quotes now counts like `>`
  (`grep -n '<div' index.html` still runs).
- **Chats are saved on a background thread.** Masking keys in a saved chat ran on the main thread at every save; a 2 MB chat took
  about a second on a computer, longer on a phone. The chat is taken as it is, then masked and written in order on one thread;
  a burst of saves writes once, and the chat list, search and sync wait for pending saves.

## 1.5.135 — Copilot's review of 1.5.134 (ANALYSIS-1.5.134.md, PR #11)

- **Fewer commands without a card, again.** A command whose words the shell would change before it runs now asks: a variable
  anywhere but inside single quotes (`cat $HOME/.bashrc`, `cat "$PWD/../x"`), and a glob or brace outside quotes (`cat .e[n]v`,
  `cat *`, `cat .{e,}nv`). The secret-file check also looks at the words as the shell sees them (`cat .e''nv`). Quoted patterns
  still run without a card (`grep -n 'a*b' src`, `find . -name '*.kt'`).
- **`/cloud`: no name lookups for the model's commands.** The machine's own name server would look up any name for them, and a
  name can carry code out. The workflow stops that resolver, sends this job's own lookups to the real name servers, and blocks
  port 53 for the commands' user. Before the agent starts it checks both ways (the job can look up names, the commands cannot
  look up names or connect out) and stops if either fails. Firewall rules tested here; the resolver change runs only on GitHub.
- **`/cloud`: git for the model.** Its commands cannot see the moved git folder, so `git status` failed. The agent now has a `git`
  tool (status, diff, log, show, with a short list of flags and no external programs), and the model is told.
- **`/cloud`:** an unfinished run opens a normal pull request after any 422 on the draft, whatever GitHub's wording.
- **Guard:** every pull request is checked for protected files, whatever its author or branch name.
- **More secrets hidden:** GitLab, npm, PyPI, Slack app, DigitalOcean, Shopify, SendGrid, Stripe webhook, Supabase, Netlify and
  Google OAuth tokens, passwords in addresses (`https://me:…@host`) and query strings (`?api_key=…`), Azure `AccountKey=`, and named
  values from 12 characters. Saved chats, the app log and crash reports are masked too; saved chats stay valid JSON.
- **TalkBack:** Settings → Editor and layout → "Screen reader: say what the assistant is doing" turns the spoken updates off.

## 1.5.134 — Copilot's review of 1.5.133 (ANALYSIS-1.5.133.md, PR #10)

- **`/cloud`: the real git folder is out of reach.** The model's user could rename `.git` in the worktree and leave a forged one
  whose settings (a proxy, no TLS checks) the agent's git would read, putting the push token at risk. The workflow now moves
  `.git` to a folder only the agent can enter and the agent's git uses it explicitly; a `.git` the model makes is ignored.
  Tested here: the forged folder's proxy and hook were not used, and the model's user could not list the real one.
- **`/cloud`: no internet for the model's commands**, so they cannot send the private code anywhere: the workflow adds firewall
  rules for that user (tested here: a direct connection was refused with the rule, made without it). The model is told. Set the
  repository variable `POCKETIDE_CMD_NETWORK` to `1` to allow it (for package downloads).
- **`/cloud`: an unfinished run** that cannot open a draft pull request (some plans have no drafts in private repositories) opens
  a normal one, still titled "Unfinished" and not closing the issue.
- **Stop, again.** A force-stopped run that calls back later can no longer write files, run commands, take over the Stop button, or
  end a newer run's busy state; its end is noted in its own reply. A command whose snapshot was being taken when Stop was pressed
  is not started.
- **Fewer commands without a card.** Quotes are now read as the shell reads them (`cat "../x"`, `cat '/etc/hosts'`), absolute and
  home paths ask, a lone `&` counts as two commands, and flags that write, run programs or follow links out ask (`tree -o`/`-R`,
  `file -C`/`-m`, `sort --compress-program`/`-T`, `tail -f`, `rg -L`/`--hostname-bin`, `find -L`, `grep -R`, `ls -L`,
  `git --no-index`/`--textconv`, `<(…)`, `${…}`). A path through a symlink that leaves the project asks too.
- **More secrets sealed:** project secrets (`secrets.json`), MCP server settings, and the GitHub token wherever it is written
  (new projects, clones). **More hidden in records and exports:** Groq, Stripe, Hugging Face, AWS, JWT and PEM keys, and
  `NAME_TOKEN=…` style values; chat exports (they land in the project, which may be pushed) and shares are masked too.
- **Guard:** also protects `Vault.kt`, `KeystoreCipher.kt`, `SecretMask.kt`, `SelfFix.kt`, `RootfsSwap.kt`, `RendererCrash.kt`.
- **Smaller:** reinstalling Linux while a terminal or server runs in it is refused (nothing is changed); TalkBack announces only a
  change of kind ("Reading" to "Running"), at most every 5 s; chips like "⬇ Save a copy" are read as "Save a copy";
  ROADMAP-HANDOFF.md says it is a history and points to the current picture.

## 1.5.133 — more fixes from the analysis: keys sealed, preview crashes, accessibility

- **Keys and tokens are sealed at rest** (analysis S6). API keys, the Brave key, the Cloudflare deploy token, remote passwords,
  the Supabase refresh token and each project's GitHub token are encrypted with an AES-256-GCM key kept in the Android Keystore
  (`core/Vault.kt`, `KeystoreCipher.kt`). Plain values from older versions are sealed when the app starts and read the same. If a
  sealed value cannot be opened (the keystore key was lost), it counts as missing and the app asks for it again; it never crashes.
- **Records hide secrets.** The assistant's record of commands and tool calls (and "Save the full record") replaces the app's own
  keys and well-known token shapes (`ghp_`, `github_pat_`, `sk-…`, `Bearer …`, `Authorization: basic …`) with `[hidden]`
  (`core/SecretMask.kt`).
- **A crashing page no longer takes the app down** (C2). When a page crashes the browser's renderer (often a canvas game out of
  memory), the preview gets a new browser and loads the page again, and says so; if it crashes again within a minute it is not
  reloaded. The hidden page checks report the crash to the assistant instead of killing the app and the run.
- **Accessibility** (U1-U3). Controls labelled only with a symbol (✕, ⋯, ↺, ▶, ⚙, ⋮) have spoken names; buttons and chips are at
  least 48 dp; the working timer updates once a second, is hidden from screen readers, and phase changes are announced instead,
  so TalkBack and Android's UI tests see the screen settle.
- **Reinstalling Linux keeps the old one until the new one is in place** (C4), and free space is checked first.
- **The snapshot before a command is taken off the screen's thread** (C3), so large projects no longer freeze the app before a
  command.
- **Self-fix only takes an artifact named `…debug-apk`** (E6); tests now tie the names in `build-apk.yml` to what self-fix and the
  add-on download look for, and test the add-on signature check (T3).

## 1.5.132 — fixes from Copilot's analysis (ANALYSIS-1.5.131.md)

- **Stop means stopped.** Pressing Stop a second time cleared the flag the assistant reads, so a command that was still running
  could let the run carry on after the screen said "Stopped" (analysis C1). Each run now keeps the number it started with, and a
  forced stop changes it, so the old run stops at its next step whatever happens to the flag.
- **Fewer commands skip the card.** Commands that only read run without a card, but some reading commands have flags that write,
  delete or run programs: `git branch -D main`, `sort -o`, `uniq in out`, `find -fprintf`/`-fls`/`-okdir`, `rg --pre`,
  `git log --output`, and paths into other projects (`../other`). Those now ask like any other command (analysis S5;
  `ReadOnlyBypassTest`).
- **`/cloud` keeps its keys away from the model's commands** (GPL repository's workflow, and what the app's /cloud setup writes).
  The model's commands run as a separate user, `pocketide-cmd`, which cannot read the agent's environment (where the API key and
  GitHub token are) or write `.git`; git runs without hooks or fsmonitor, the push token goes through the environment instead of
  the command line, and only to github.com. Tested here with a canary secret: the old way the command read it, the new way it
  could not, and writes into `.git` were refused (analysis S2, S3). A run that stops on its time or round limit now opens a
  **draft** pull request titled "Unfinished" that does not close the issue (S9). The workflow runs on `ubuntu-24.04`.
- **The guard protects more.** Agent pull requests may no longer change `LinuxAddon.kt` (the add-on signature check),
  `Habits.kt`, `ReadOnlyRun.kt`, `NoLinuxCommand.kt`, `app/build.gradle.kts`, `settings.gradle.kts`, `linux-addon/` or the
  signing key; branches named `agent/*` and `analysis/*` count as agents' (S8).
- The no-Linux answer says the MIT edition first offers the Linux add-on (E4). `VcsTest`'s timing check is a smoke check now
  (30 s, not 3 s), the likely cause of its occasional failures (T2).
- Still open from the analysis, needing the owner: the signing key (S1, a committed debug key signs the app and the add-on) and
  debug builds (S4); and to do: WebView renderer crashes (C2), accessibility (U1-U3), stored keys (S6), NOTICE rows (L1, L2).

## 1.5.131 — the MIT edition's name

- The MIT edition was named "PocketIDE GPL" from 1.5.128 to 1.5.130: the shared app code carried the GPL edition's name, so
  Android's installer showed "PocketIDE GPL" when updating the MIT app. Each edition's build now sets its own name
  (`resValue` `app_name`), and `EditionNameTest` checks it. The two editions always had different app IDs
  (`dev.pocketide.app` and `dev.pocketide.gpl`), so neither ever replaced the other.

## 1.5.130 — the Linux add-on without a GitHub token

- **Get the Linux add-on** (MIT edition) downloads the newest release from the public repository
  [dbal2969-ai/pocketide-linux-addon](https://github.com/dbal2969-ai/pocketide-linux-addon), so no GitHub token is needed.
  With a token, the builds of the GPL repository are still tried when the public download fails. The add-on is used only
  when it is signed with PocketIDE's key, as before.
- Every GPL build also packs the add-on's source (the exact PRoot and talloc sources and the recipe) as
  `PocketIDE-Linux-addon-source`; it goes into each public release next to the APK.

## 1.5.129 — open items from the emulator test

- **Pictures for models that cannot see them.** When the model in use cannot see images and you have a key for one that can
  (Gemini first, then OpenRouter, OpenAI, Anthropic), the warning offers **Describe with Gemini**: that model describes the
  picture once (all visible text, layout, anything broken) and the description goes to your model as text. Tool calls already
  had a bridge like this for models without them (the text tool format).
- **No splitting into parts when everything goes into one file.** "A snake game in one HTML file" was split into 2 parts that
  both write index.html, which cost 3+ minutes and a second approval for nothing. A request that says "one file" or names a
  single file is built in one go.
- **Commands that need Linux, without Linux.** Asked to run python3, node, git, curl and the like while Linux is not running,
  the assistant is answered at once ("python3 is not installed here… check a page with look_at_preview") instead of showing you
  a Run card for a command that cannot work.
- **The assistant can look at a page it is about to write.** In a new project there is no page when the request starts, so
  look_at_preview was not offered: the emulator saw Kimi write index.html, then ask to run `python3 -m http.server` to see it
  and wait on that Run card for 50 minutes, and the app reported "No page to check" though index.html was there. The page
  check is now offered for empty projects and page requests, and the page is found when the check runs.
- **Next-edit suggestions explain themselves:** every decision (not asked and why, asked which model, answered after how long,
  rejected and why, failed) goes to the app log, so a suggestion that never appears can be traced.

## 1.5.128 — model suggestions that learn

- **Suggestions count evidence, not only descriptions.** A model was suggested from what its provider says about it (free, "agentic",
  tool use), so OpenRouter's thinkingmachines/inkling:free kept being suggested over Kimi K2.6, the model PocketIDE has measured
  working, and kept failing. Now a suggestion also counts:
  - the models PocketIDE's own benchmarks measured (Kimi K2.6, Claude Haiku 5.5, GLM 5.3 Flash, DeepSeek V4.1 Flash, GPT-5 mini),
    on any provider that offers them;
  - how each model's requests ended on your phone (finished, or unfinished or failed).
  A model nothing proves is never suggested over one that is proven. A model that failed twice for you and never finished is not
  suggested again. The note says what is known ("on this phone: 0 finished, 2 unfinished or failed").
- **At most once a week**, not after every restart, and a **Don't suggest it** button ends it for good.
- A model without native tool calls loses fewer points: the text tool format still works for it, if less reliably.
- **The MIT edition catches up with GPL.** Both editions now have the same app code. MIT gains:
  - the full terminal (Termux's terminal libraries, which the Termux maintainers confirmed are not GPL);
  - the Linux environment: ⋮ → Tools & Linux → **Get the Linux add-on**, then Install Linux. The add-on is a small separate GPL app
    holding only PRoot (built from `linux-addon/` in the GPL repository), so the MIT app itself still contains no GPL code. It is
    used only when it is signed with PocketIDE's own key;
  - with Linux: the Tools screen (Python, Node, git and more in one tap), background servers and Ollama, language-server error
    checks after each edit, the 2009scape server template, and a terminal that opens into Linux;
  - crash reports: after an unexpected close, the next start shows why.
  Downloading the add-on reads the GPL repository's builds with your GitHub token, so for now it works only with access to that
  repository. (From 1.5.130 it comes from a public release and needs no token.)

## 1.5.127 — fixes from the first emulator test

The first run of the new emulator test (`EMULATOR-TEST.md`) drove 1.5.126 through its main flows for 85 minutes with no crash,
and found these, all fixed here:

- **Markdown in replies:** the assistant's `**bold**`, `` `code` ``, code blocks, headings, lists and links are shown formatted
  (they showed with their marks), in replies and in plan and requirements lists (`core/ChatMarkdown.kt`, tested).
- **Chat home stays a chat:** it no longer switches Agent mode on for a build request, and a read-only run is no longer nudged to
  "do the work". A refused change is labelled "Not done (read-only): write index.html", not "Wrote index.html (read-only)", and the
  model is told to answer in the reply. The empty Chat home suggests questions, not project changes.
- **Build this** opens the new project on the AI tab (it landed on Code), and its Plan mode stays with that project; a typed
  request no longer follows the person into another project.
- **Code, Preview and Terminal in the Chat home** open Home to pick a project (they showed the hidden `.chat` folder as a project).
- **The app's own steps** ("Splitting the job into parts", "Checking the result against your request") show as notes, not as the
  person's messages.
- **Commands:** `which node 2>/dev/null` and `grep "<script>" index.html` are no longer labelled "This changes files in the project".
- **Checking a web page:** when the assistant changed web files and has not checked them, it is asked to use the app's page checker
  (look_at_preview) first, not shell commands; Kimi spent 18 minutes on python3, which and grep for a snake game before.
- The first-run checklist no longer ticks "Connect an AI" on the demo; Search all chats puts the cursor in its box; the keyboard
  closes when you switch tabs.
- Emulator test rig: busy screens are reported as busy (they were read stale), taps wait for moving dialogs, and the notification
  permission is granted again after clearing the app's data.

## 1.5.126 — fixes from the review of 1.5.125

- **"Carry on with <model>" offered again after a carried-on run.** The flag that stops a second escalation of the same request
  was cleared only when the carried-on run ended through the tool-calling path. With a strong model that does not call tools it
  stayed set, and no later stalled request got the button until the app restarted. A new request now clears it
  (`core/Escalate.kt` `Once`, tested).
- Settings → Assistant: the automatic "carry on with the strong model" box now says it is not yet measured. The button is the
  tested way.
- Pull request guard: a renamed protected file (for example `core/AgentGuard.kt` moved elsewhere) is now caught, and the fetch
  token is masked in the log.
- GPL edition: `SyntaxCheckTest` (4 tests) restored. It was lost in an old batch commit; the MIT edition still had it. A leftover
  `BENCHMARKS.md.orig` is gone.
- `ROADMAP-HANDOFF.md`: the status table now separates built, measured and checked on a phone; current test counts; the owner's
  steps; the `release.sh` and branch-protection decision. `REVIEW-1.5.125.md` is the review these fixes come from.

## 1.5.125 — chat with no project, carry on with a stronger model, /bench, error reports from published apps, next-edit suggestions

- **`/bench` from the phone** (roadmap phase 6, the last step of PocketIDE updating itself): `/bench rp-loot,rp-bury on pocketide/issue-12`
  starts `benchmark.yml` on the project's GitHub repository with those tasks, comparing the default branch with yours (an A/B), one
  run each, with your current model when the benchmark has a key for its provider (`with groq:<model>` picks another). It shows the
  number of task runs first, follows the run, reads its `summary` artifact and shows the passes per branch in the chat, and posts
  the summary on the branch's open pull request as a comment marked as PocketIDE's. The budget is a hard cap: named tasks only, at
  most 8, one model, one run of each; a whole set (`suite hard`) or `x2`/`x3` asks in a dialog first. A token without Actions: Read
  and write gets a message naming that permission; a failed run shows the failing job and the end of its log.
- **Next-edit suggestions beyond renames.** After you edit and pause (1.2 s), when the rename case does not apply, the editor
  asks the suggestion model (the provider and the "faster model for suggestions" that inline completion uses) for the one edit
  most likely to follow elsewhere in the same file: a call that needs the parameter you just added, a use of a constant whose unit
  you changed, an object that needs the field you added. The reply must be one exact SEARCH/REPLACE block; it is shown in the
  next-edit bar (Go, Apply, Dismiss; ↶ undoes) only when its old text is in the file exactly once, outside the lines you just
  edited, and is at most 8 lines. Anything else shows nothing. One request at a time; typing cancels it. Settings → Editor and layout:
  "Next-edit suggestions", on by default except on a metered connection (mobile data). The offline benchmark checks the
  plumbing; the live match rate and latency are not measured yet (BENCHMARKS.md).
- **A stalled run ends with its question.** When a limit stops a run that changed nothing, the model is asked for what it found,
  what stops it, and, when you must choose something (an approach, a service, a file), one question naming the options. In the field
  run Kimi decided it needed the owner's choice of proxy and ended "stopped" without asking.
- **A message you send during a run joins the job.** It used to say "if it changes the job, change your plan", and Kimi answered the
  owner's two messages with "I'll note it for the next iteration". Now it is told to do what the message asks in this run.
- **Carry on with a stronger model** (roadmap phase 4). When a task ends unfinished (going in circles, a round or time limit, a failed
  last check, or an answer that says it is not done) and you picked a strong model in Settings → Workflow that differs from the one
  that worked and has a key, a "Carry on with <model>" button appears under the reply. It continues on the files as they are, with
  the first model's notes, unlike Second try (which undoes and starts again with the same model). Settings → Assistant can make it
  automatic, once per request. The benchmark has the same switch (`escalate=provider:model`).
- **Chat with no project** (roadmap phase 5): Home → "💬 Just chat" (or Projects → Just chat) opens a chat that belongs to no
  project, like the Claude or Copilot app: ask anything, it answers, explains, writes code in the reply and looks things up, and
  changes nothing. History, models, pictures and Continue work as in a project. "Build this as a project" (the mode chip, or ⋮)
  makes a project named after the chat, copies the conversation into it and opens it in Plan mode with "Build what we discussed
  above." typed for you. "🔍 Search all chats" finds a conversation in any project and opens it there. A new benchmark suite `chat`
  (12 plain questions, read-only) checks the answers.
- **Errors from published apps** (`/deploy cloudflare`): the uploaded copy of each page loads a small reporter
  (`/__pocketide/report.js`) that sends uncaught errors, failed promises, `console.error` and files that did not load to the
  site's own Worker, capped at 10 per page load and 30 stored per minute; a backend's own errors are recorded too. The project's
  files are not changed: the tag is added to the uploaded bytes only, and a backend is wrapped by a generated entry module that
  imports `worker.js` unchanged. Read them with `/deploy errors`, the deploy result's "Errors from the site", or ⋮ → Run and
  check; "Fix this" sends one to the assistant (keys hidden). Only the phone can read them: a random key kept with the project's
  secrets. Where they are kept: Workers KV when the token has **Workers KV Storage: Edit** (Cloudflare refuses to make a KV
  namespace without it); otherwise the last 50 in the Worker's memory, which the app says are lost when Cloudflare restarts the
  Worker.
- **Pause-and-step debugger in the Preview** (roadmap phase 9, spike in `DEBUGGER.md`). Preview → Debug → write the lines to stop at
  (`game/main.js:248 sid, inv.slots`, the same lines as log points) → **Pause**. The preview reloads; when the page reaches a line
  it stops there, and a panel shows the file and line, the code around it, the watched values and the names declared so far in
  that function, with Continue, Step (stop at the next statement) and Stop debugging. The page is held by a call into the app
  that WebView makes on its own background thread, so the app stays usable while the page waits. A pause lets go by itself
  after 5 minutes, and when the preview is reloaded, hidden or closed. Only the copy the preview loads is changed (a check in
  front of each statement line of the files with breakpoints, and of inline scripts in a page); your files are not. Works on the
  project's files, not on a local server. Needs porting to MIT (no GPL-only code).
- **Benchmark: Aider's JavaScript polyglot exercises** (roadmap phase 3): suite `polyglot-js` runs the 49 JavaScript exercises
  (`js-` names), `polyglot-all` runs them with the 34 Python ones, and `polyglot` is still Python only, so earlier scores compare.
  As in Aider's harness the model gets the instructions and the stub; the hidden spec runs afterwards with jest through the
  exercise's own package.json and babel config, skipped tests (`xtest`) included, with one more attempt on the failing output.
  The workflow installs jest once per job, outside the project, and the task sandbox reads it without the network. A fairness test
  checks that each exercise's reference solution passes and its stub fails. No model run yet: the Kimi K2.6 baseline is to come.

## 1.5.124 — a run that re-reads files it already has is stopped; checks on every pull request

- **Re-reading in circles is stopped.** In the owner's field run (Kimi K2.6, 2026-11-10) the assistant read a file it already had
  15 times in different ranges and printed it 9 more times with `cat -n | sed`, with no edit, until the owner pressed Stop. No loop
  guard fired: each read asked for different lines, so every call and every result looked new. Now a read the app answers
  "unchanged", or a command that only prints a file the model already has (`cat`, `sed -n`, `head`, `tail`, `nl`), counts as a
  re-read; six with no file changed in between stop the run as going in circles, with the reason saved for Continue. A command
  that only reads (cat, grep, ls) also no longer resets the repeated-call check. A replay of the field run is now a test.
- **Checks on every pull request** (`pr-check.yml`): the build and every unit test. **A guard** (`pr-guard.yml`, run from main so a
  pull request cannot switch it off) fails a pull request that lowers the number of tests, or an agent's pull request (PocketIDE's
  `/cloud`, Copilot, anyone but the owner) that changes the workflows or the files holding the agent's own limits.
- **`CONTRIBUTING-AGENTS.md`**: the rules for any agent working on this repository. **`ROADMAP-HANDOFF.md`**: the roadmap to
  parity with Claude and Copilot, written so Haiku, Opus, Copilot or PocketIDE itself can pick up a phase.
## 1.5.123 — /deploy cloudflare sends /api/ calls to the app's backend first

- A project published with /deploy cloudflare that has a backend (worker.js) and an index.html used Cloudflare's
  single-page mode, which can answer an unknown address such as /api/fetch with index.html before the backend sees it.
  With a backend, /api/* now always goes to the backend first (Cloudflare's run_worker_first). Found while giving the
  owner's meal planner its own recipe-page fetcher.

## 1.5.122 — the parts check sees functions a page shares through window; the Web page project as a benchmark

- The check after building in parts compares each file with the parts' agreement. It counted only `export …`, so a plain
  script that shares functions with `window.getData = getData` (or `Object.assign(window, …)`, `globalThis.x =`, CommonJS
  `module.exports = { … }`) was reported as "should export getData, addRow, deleteRecipe, but it does not". In the owner's
  Web page project that false finding became the agent's plan for a 35-minute, 83-step run. It now counts those too; a name
  that is really missing is still reported.
- BENCHMARKS.md records the Web page project as a field run: what Kimi did in each chat, why the goal failed, and the class
  and action for each failure.
- New real-project task rp-recipe-steps: the owner's unanswered request (recipes need instructions, and an import should fill
  them in), in a made-up meal planner with a saved recipe page; the check also reads pages the model never sees.

## 1.5.121 — Export all includes each chat's assistant record

- Files → Export all chats (the "Export all" button) now puts each chat's assistant record under the chat: every command it
  ran, with the output the command printed, keys hidden. Before, the export had the messages, thinking and step lines, but not
  command output. A record over 200,000 characters keeps its start and end.

## 1.5.120 — a new request after a finished build is a new request; parts go to the models you chose

- A short message (under 160 characters) used to be joined to the last big request in the chat ("The person then added: …")
  for as long as the chat lasted. So a new feature asked for after a build was finished (an owner's "Add the ability to import
  url links for recipes") was treated as part of the original project: a requirements step, a read-only look around, and a web
  search about the original app. The big request is now forgotten once its build starts; notes typed while reviewing its
  requirements or plan still join it.
- Building parts at the same time no longer uses every provider with a saved key. With no team chosen in Settings → Parts, the
  parts go to three copies of the main model; with the copies setting off, they are built one after the other. (With 13 keys
  saved, one person's parts could go to paid accounts and weak free tiers they never chose.)
- The "I will build these parts…" message names the models that will build them, not the whole team.
- The read-only look-around before a plan or a requirements list now says so in the chat: nothing changes until you approve.

## 1.5.119 — the hit-splat task's page draws its overlay every frame

- Rerun 42816153262: Kimi again found the hidden canvas, then looked for where the splats are drawn each frame; nothing in the
  task's project called the drawing code, unlike the owner's game. The page now runs a frame loop that draws the overlay. The
  bug and the check are unchanged. No change to the app.

## 1.5.118 — the hit-splat benchmark task gets the combat code it talks about

- rp-hitsplats quoted the chat line "You hit the chicken for 1." but its project had no combat code, so Kimi (run 42810235944)
  found the hidden canvas, then spent its time looking for that code and ran out. The task's project now has the combat and the
  drawing of splats and health bars, as the owner's game does; the bug and the check are unchanged. Kimi's first run of the six
  owner tasks: 5 of 6. No change to the app.

## 1.5.117 — moving a file onto one that is already there asks to replace it

- Files → Move to folder… (or Rename) onto a name already taken used to fail with "Could not move: the name is taken or not
  valid". It now asks "Replace main.js?" and, on Replace, puts the moved file in place of the old one (files only: a folder
  that is in the way is still refused, with a message that says so). If the replaced file is open in the editor, it shows the
  new text, in one tab.

## 1.5.116 — six benchmark tasks from the owner's own game requests

- The real-project benchmark gains six tasks rebuilt from requests the owner typed into the app about their game, each in the
  suite's made-up tile game (no game files or assets): "food is not defined" on items that are not food (rp-not-defined),
  bones that no longer bury (rp-bury), hit splats and health bars hidden in the 3D view (rp-hitsplats), monsters that drop
  nothing while coins skip the ground (rp-loot), a tapped door walked around instead of opened (rp-door-tap), and the
  tinderbox used on raw fish instead of logs (rp-tinderbox). Each check is proven to fail as given, pass a real fix and reject
  a shortcut (RealProjectBenchTasksTest). No change to the app itself.

## 1.5.115 — big projects: the AI sees all the code, and its search is six times faster

- The project map and the AI's ranked search read files in name order within a 4 MB budget. In a game with 1,759 map files under
  game/data, that budget went on map data: the map showed 8 of 33 code files (not main.js), and the search read 190 MB each time
  (10.6 s on a laptop; slower on a phone). Code is now read first and data files over 64 KB are skipped: the map lists every code
  file, main.js first, and the search takes 1.7 s on the same project (9,754 files, 890 MB).
- After a cut-off reply, a project with no web page is no longer told to split its work into engine.js and ui.js "that the page
  loads"; Python projects hear about modules (found by Copilot reading the polyglot transcripts).
- Benchmark: the polyglot exercises use Aider's two tries (a failed attempt gets the test output once), and the report gives both
  rates. Kimi K2.6 on Cloudflare (free): 22/34 on the first try, 30/34 with the second. NVIDIA and Ollama Cloud keys can be used;
  their strong models are not in the free tiers we have (Ollama's run out after a few tasks; NVIDIA's Kimi K2.6 is not served).

## 1.5.114 — an answer cut off part way is asked for again

- When the connection broke while a model's answer was arriving ("Premature EOF", "Connection reset"), the request failed. The
  partial answer has not been used at that point, so the app now sends the request once more and the answer starts over
  ("The answer was cut off: asking again…"). This ended 3 of GLM 5.3 Flash's 34 polyglot runs through Haimaker.
- Benchmark: GLM 5.3 Flash (Haimaker) on the 34 polyglot exercises: 13/34, slow (4 h 20 min in all; 7 runs hit the time limit,
  3 ended on the cut-off connection). Kimi K2.6 stays the best value: 22–26/34, free.

## 1.5.113 — light theme, code next to the preview, rename suggestions, a real-world benchmark

- **Light theme** for the whole app (Settings → Editor and layout; the screen restarts). Dialogs and the editor follow it.
- **Code next to the preview** on wide screens (a tablet, or a phone turned sideways): the Preview tab shows the code beside the
  page, and Reload saves the code first. Code and AI side by side was already there.
- **Next-edit suggestion for renames**: rename a name on one line (playerHp → playerHealth) and a bar offers the same change at the
  other places in the file that use it. Text inside quotes and comments is left alone; no model call; ↶ undoes it.
- **Benchmark: Aider's polyglot exercises** (suite `polyglot`, 34 Python exercises from Exercism, MIT, cloned at a pinned commit by
  the workflow). The model gets the instructions and the stub, not the tests, and no second try with test output, so scores
  compare with Aider's first-try rate. First results: Kimi K2.6 24/34, then 26/34 and 22/34 on two later runs; gpt-oss-120b
  4–7/34 (it often searched the one-file project for tests until it was stopped).
- **Copilot's benchmark guide** (COPILOT-BENCHMARK-GUIDE.md): Copilot watches the runs and writes what they mean in
  BENCHMARK-NOTES.md on the `bench-notes` branch; Claude changes the app and the tasks.
- Tried and not kept: telling the model, after a search, that it had already read every file of a small project. It stopped gpt-oss
  going in circles (4 runs to 0) but its score barely moved (6 → 7 of 34) and Kimi's fell (26 → 22).

## 1.5.112 — nine new tools: deploy, remote machine, debugger, API tester, database viewer, library docs, design match, night shift, second try

- **Deploy to Cloudflare** (`/deploy cloudflare`): the whole project goes online at <name>.<you>.workers.dev. Its files are served as
  static assets; `worker.js` (or `_worker.js`, `src/worker.js`) with `export default { fetch }` runs as the backend, with the project's
  secrets as `env.NAME`; then the address is checked. Needs a Cloudflare API token with Workers Scripts: Edit and the account id.
  Tested live: a page, a backend route reading a secret, then the test site removed. `/deploy` alone is GitHub Pages as before.
- **Remote machine** (⋮ → Run and check): any server reachable by SSH. The phone makes its own key (copy it to the server's
  authorized_keys), syncs only changed files, runs commands in the project folder with a login shell, and brings back files a
  command changed. "Run the assistant's commands there" sends the AI's commands too. Works without SFTP. Uses JSch (BSD, notices in
  `third_party/jsch/`). Tested against a real OpenSSH server.
- **Debugger: log points** (Preview → Debug, and look_at_preview `trace`): `game/main.js:248 sid, inv.slots` records those values each
  time the line is about to run, in the copy of the file the hidden browser loads (your files are not changed), plus errors with their
  stack. A point on a line that continues a statement moves to the next line where one starts.
- **API tester** (⋮ → Run and check): method, address, headers, body; status, headers and indented JSON back. `{{NAME}}` fills in a
  project secret, and secret values are masked in the answer. Recent requests, Copy as curl. The AI's `http_request` reaches servers
  on the phone only.
- **Database viewer** (⋮ → Run and check): the project's SQLite files (tables, pages of rows, any SQL; a change asks first and keeps a
  copy) and a Supabase project's tables (secrets SUPABASE_URL and SUPABASE_KEY). The AI's `sql_query` only reads.
- **Library docs**: the AI reads the README and type definitions of the exact version the project uses (package.json, lock file, CDN
  script tags, requirements.txt) with `library_docs`, and gets only the sections about its question.
- **Screenshot to code**: attach a picture and say "make this"; the page check then compares the page with the picture (a similarity
  score and the areas that differ most, with their colours), so models that cannot see images also get the feedback.
- **Night shift** (`/nightly at 3am: run the tests and fix what broke`, `/nightly off`): GitHub runs the saved task on a schedule with
  the /cloud agent, opens an issue for the record and a pull request when it changed something.
- **Second try** (Settings → Assistant, off by default): when a request ends unfinished (cut off, last check failed, or the answer says
  it is not done), undo it and run it once more from the start. Benchmark: gpt-oss-120b 24 → 19 and GLM 4.7 Flash 14 → 13 of 32 with it
  on; 4 of 12 second tries passed, so it stays off.
- Benchmark: three harder real-project tasks (four asks in one message, a fishing feature, a bug seen only when the page runs).
  Strong cheap models: Kimi K2.6, GLM 5.3 Flash, DeepSeek V4.1 Flash, Haiku 5.5 5-6 of 6; GPT-5 mini 5.

## 1.5.111 — retries bare gateway errors

- A gateway answering only "error code: 502" (or 504, 529) was shown as a failed request: the app retried a 502 only when its
  text said "bad gateway" or similar. These codes never come from the request, so the app now waits and tries again whatever the
  text says (8, 15, then 25 seconds). One DeepSeek benchmark run through Haimaker ended this way.
- Benchmark: the real-project game's page now loads and runs its save and inventory code. Before, nothing loaded them, so
  Haiku 5.5 found the right fix for the save and menu tasks, saw that the file never ran, and undid it (1 of 3). With the
  page fixed: Haiku 6/6, GLM 5.3 Flash 6/6, Kimi K2.6 and GPT-5 mini 5/6. Haimaker models can be benchmarked (BENCHMARKS.md).
- Tried and not kept: a note telling the model that independent reads can share one reply, after four one-call replies.
  gpt-oss-120b went from 23 to 21 of 32 on the hard suite and GLM 4.7 Flash from 18 to 14.

## 1.5.110 — reading a file lifts the search block

- After three searches find nothing, the assistant is told to read a file and search only for a name it saw there. Reading
  never lifted the block, so every later search was refused until a file changed (11 of 14 benchmark runs that hit the block).
  A read or an outline now lifts it. Hard suite (2 runs each, old against new): Kimi K2.6 29 → 31 of 32, gpt-oss-120b
  21 → 23 of 32; on the tasks where the block showed up (GLM 4.7 Flash and gpt-oss, 3 runs each) 9 of 24 both ways.

## 1.5.109 — tries again on Cloudflare internal errors

- Cloudflare sometimes answers "Internal server error" (HTTP 500) or "Unknown internal error" (HTTP 503) for a moment. The
  app showed these as a failed request; it now waits and tries again (8, 15, then 25 seconds), as it does when a server is busy.
- Benchmark: GLM 4.7 Flash on the hard suite (2 runs each) went from 6 of 32 (half the runs ended on these errors) to 14 of
  32 with no run ending on one. Cohere north-mini-code (Kilo, no key) passed 4 of 6 hard tasks in a first trial.

## 1.5.108 — the page check also long-presses

- Games often open an item's menu on a long press, not a tap, so the page check never ran that code. It now holds a few filled
  controls (an inventory slot with an item in it) for 650 ms before its taps, and reads the page about 1.7 seconds later. In a
  real game the item menu now opens during the check, and the inventory errors are still caught.
- Tried and not kept: a note on the first edit to the file a person's guess blamed ("I think money() is broken"). It fired in
  every run, and gpt-oss-120b kept rewriting the correct function anyway (1 of 3 both with and without it).
- Held-out suite on 1.5.107 (2 runs each): Kimi K2.6 11 of 12, gpt-oss-120b 10 of 12; both missed the new retry-fetch task
  at least once.

## 1.5.107 — a patch that ends twice is still applied

- gpt-oss-120b sometimes ends a patch with "*** End Patch" twice; the patch was refused ("Unexpected line in the patch") and
  it retried the same patch up to six times. An extra Begin or End line inside a patch is now ignored.
- Benchmark after 1.5.106's fix (hard suite, 2 runs each): gpt-oss-120b with the phone's tools passed 23 of 32, against 24 of 32
  without them and 19 of 32 with the patch tool broken. With Kimi K2.6 as its advisor (before the fix) it passed 21 of 32 in
  twice the time, so the advisor stays an option, not a default.

## 1.5.106 — the patch tool works for gpt-oss; the page check taps game controls

- **apply_patch failed every time for gpt-oss.** The app offers GPT-family models a patch tool (it can also delete and rename
  files). gpt-oss-120b sends the patch under the name "patch" instead of "input", so every call was refused with "The patch
  must start with *** Begin Patch", and it retried up to 18 times a request. With the tool offered as on the phone, gpt-oss
  passed 19 of 32 hard benchmark runs against 24 of 32 without it. The patch is now read from whichever argument holds it.
- **The page check taps what a finger would.** It used to tap only real buttons, so a game whose inventory slots, tabs and
  menus listen for taps through addEventListener was never touched (0 controls tapped in a real game). The check now notes
  every element given a tap listener before the page's scripts run, and taps a few from each kind (slots holding an item
  first), sending the events a finger sends. In a real game it found two errors that the old check missed: "FOOD is not
  defined" and "equipmentData.find is not a function", both thrown when an inventory item is tapped.
- **Benchmark:** it now offers the tools the phone offers (explore, and apply_patch for GPT models), so it measures the app as
  shipped, and it can add an advisor model (input `advisor`).

## 1.5.105 — the assistant can delete files; brief outages are retried

- **New tool: delete_file.** Asked to "remove the coupon feature completely", gpt-oss-120b said "There's no delete tool" and
  emptied the files instead, leaving them in the project (held-out suite, 0 of 2). The assistant can now delete a project file;
  Undo brings it back like any other change. A test file is not deleted unless the person asked to change tests. After a
  delete, the assistant is told which files still load the deleted file or use what it defined (couponDiscount in routes.js),
  so a removed feature leaves nothing behind. Benchmark (3 runs each): gpt-oss-120b finished the removal 1 of 3 times against
  0 of 3; Kimi K2.6 and Nemotron Ultra stayed at 3 of 3 on every task tried.
- **A server that is briefly down is retried.** "Upstream provider is temporarily unavailable" (503), "Overloaded" (529) and
  bad-gateway pages ended a request at once; the app now waits and tries again, as it already did for a busy server.
- **Benchmark:** LLM7 (key from POCKETIDE_BENCH_KEY_LLM7) and Kilo Code's free models (no key) can be benchmarked. The held-out
  remove-feature task moved to the hard suite, since it shaped the delete tool, and a new held-out task, retry-fetch, took its place.

## 1.5.104 — errors thrown away, and tests that catch bugs

- **New check after every code edit: an error handler that throws the error away.** Asked to fix an import where "a bad row is
  silently ignored", gpt-oss-120b wrapped each save in `catch (e) { /* ignore */ }` in every benchmark run: the rows saved in
  order and its own check looked right, but the bad row was still silently ignored. Now, when an edit adds an empty `catch`,
  `.catch(() => {})` or `except: pass`, the assistant is told at once that nothing will ever learn the work failed. When the
  person complained that errors go unnoticed and the handler is still empty at the end, it is sent back once to let the error
  through. In the benchmark (hard suite, 3 runs each) that task went from 0 of 3 to 2 of 3 for gpt-oss-120b; the check did not
  fire in any other task, for either model.
- **Asking for tests now asks for tests that would catch a bug.** Kimi K2.6 wrote eleven passing tests, each one example, and
  a planted bug (rounding instead of flooring) got through. When a request asks for tests, the assistant is now told to check
  each promise the code's comments make over a loop of many inputs and edges, and to prove the tests by breaking a copy of the
  code outside the project. Test-writing tasks: 8 of 9 passed with the note against 7 of 9 without, for both models.

## 1.5.103 — catch names that nothing defines

- **New check after every script edit: names nothing defines.** In a real project a run wrote `FOOD[sid]` with `FOOD` defined
  nowhere: the syntax check and `node --check` passed, the game check passed, and every inventory tap showed "Food is not
  defined". Another line called `ensureNpcPic()`, which also does not exist, so fishing spots were never drawn. Now, right after
  the assistant edits a .js file, the hidden browser walks the script and lists the names it reads that are not declared,
  imported or built into the browser or Node. The assistant gets a warning naming each one and its line (and the file that
  declares it, when it only needs importing). If any are still there when it wants to finish, it is sent back once to fix them.
  Only names the edit added count, names another script on the page may define are left alone, and pages that load outside
  libraries (three.js, p5 from a link) are checked more carefully so their globals are not reported.
- The benchmark runs the same check with Node, so its results include it.

## 1.5.102 — compare a change on GitHub before it ships

No change on the phone.

- **Benchmark workflow: compare branches.** Set *refs* to `main,<branch>` and both run the same models and tasks; a final
  summary job puts them side by side (passes, median requests, seconds and reads per task). Fixes are now checked this way
  before they reach main.
- The OpenRouter secret is accepted as `POCKETIDE_BENCH_KEY_OPENROUTER` or `POCKETIDE_BENCH_KEY_OPEN_ROUTER`.
- **Benchmark tasks:** rp-equip-slot moved to the hard suite (its transcripts shaped a fix in the next release), and a new
  real-project task, rp-save-position, took its place: the player is back at the start after closing the game.

## 1.5.101 — benchmarks on GitHub Actions

No change on the phone.

- **Run the benchmark from GitHub, no Codespace needed:** Actions → *Benchmark (live models)* → *Run workflow*. Pick the models,
  the task set, the runs per task, the thinking effort and whole-file reads. Each model and task set runs as its own job, side by
  side. The scores show on the run's page, and the reports and transcripts download for 30 days. It needs the repository secrets
  `POCKETIDE_BENCH_KEY_CLOUDFLARE` and `POCKETIDE_BENCH_BASE_CLOUDFLARE`. The job stops before calling any model if they are
  missing or if the task sandbox can't start.
- **Copilot's benchmark-harness hardening** (1.5.100 bench commit, now in both editions): keys are kept in memory and redacted
  from saved output, and task commands run in a sandbox with a clean environment and a time limit.
- **Thinking-effort results** (Copilot, 90 trials each, 30 tasks × 3): Kimi K2.6 81 low / 86 medium / 84 own; GPT-OSS 120B 66 low /
  75 medium / 72 own. Low cost quality and medium was not clearly faster, so the setting's default stays "Model's own".

## 1.5.100 — the whole-file reads can be measured

No change on the phone. `POCKETIDE_BENCH_WHOLE_READS=0` serves file pieces as asked, as before 1.5.99, so the benchmark can
compare the whole-file reads on the same code. Copilot pointed out that the 1.5.98 commit can't run the new real-project tasks.
`COPILOT-NEXT-1.5.99.md` is corrected to match.

## 1.5.99 — faster rounds, a thinking setting, runs that carry on, and a real-project benchmark

- **Files are read whole, once.** Asking for a piece of a file that fits in one reply now returns the whole file, and asking
  again for an unchanged file gets a short reminder instead of the same text. In the logs the model read a 900-line main.js 40
  to 45 times per request in 150-line pieces, thinking for about half a minute before each piece. The record of what it has seen
  now survives edits to other files and commands; only a file that really changed is sent again.
- **Settings → How long it may work → Thinking before each step.** Kimi K2.6, GPT-OSS and other thinking models spend most of
  each run thinking (85–90% of the time in the logs). Low or Medium makes each step faster. The default leaves the model as it
  is until the benchmark shows what a lower setting costs. A provider that does not take the setting is asked again without it,
  so it can never stop a run.
- **A run Android closed carries on by itself.** When Android, low memory or battery saving closed PocketIDE mid-run, opening
  the chat now continues from the saved work log after a few seconds (tap Start over to begin again instead). This only
  happens when Android closed the app, never after you swiped it away or after a crash, at most twice per request, and within
  6 hours.
- **Benchmark: a real-project suite** (`realproject`): three failures from the game logs, rebuilt in a generated project of the
  same size: a ~1,100-line main.js and 120 other modules. "Bank" doing nothing on a booth, the inventory menu hidden under the side
  panel, and equipment slots shifted in the data. It measures time and reads as well as passes. `POCKETIDE_BENCH_EFFORT` measures
  the thinking setting. Instructions for Copilot are in `COPILOT-NEXT-1.5.99.md`.

## 1.5.98 — checks that tell the truth while you are in another app, and a say over test changes

- **Build fix:** the first 1.5.98 build failed downloading Robolectric's 300 MB test jar (Maven Central answered 429, too many
  requests). Builds now fetch from Google's mirror of Maven Central first and keep Gradle's downloads between builds.

From a review of two days of game-developer runs (their work logs):

- **The preview is measured at phone size even when PocketIDE is in the background.** Android lays nothing out for an app
  that is not on screen, so the hidden preview was 0×0 for most checks (68 of 80 reports in one chat). Every element then looked
  "outside the screen" and the game "drew nothing", and the model believed it: it ended one request saying the orbs "still
  render off-screen" when they were on screen. The preview is now sized by hand. A look that still comes back 0×0 says
  "NOT MEASURED" and tells the model not to change anything because of it.
- **The HUD over the game is not a layout problem.** A canvas that fills most of the screen no longer counts as overlapping
  the minimap, orbs and buttons on top of it.
- **A missing picture is a visible gap.** The app told the model that missing files were "not a failure". For the tinderbox
  and essence pictures, the model then told you to make them, though its own cache tool exports them. Missing pictures are
  now listed as shown blank, with the export call to make them (`game_cache action=export kind=icons ids=…`). Data files and map
  textures for squares that were never exported stay notes, as before.
- **Test changes are put to you.** When a request changes what a saved play test checks (keeping the player's position
  breaks a test that expected the start position), the change used to be refused and the model kept trying other ways to
  write it. Now the model is told the change has been put to you and to carry on. The reply shows the test steps it would change,
  with **Allow the change** and **Keep the tests**.
- **Game developer: test through the game, not the screen.** The model could not find a door on a 3D screen to tap, so it
  never tested its fixes. The role now asks for test helpers on `window.__game` (`teleport`, `give`, `messages`, `find`, and
  `options` / `choose` for menus), to add them if missing, and to prove each fix with them. It also asks the model to trace one
  concrete case through the code before changing anything. New games from the kit come with `teleport`, `give`, `messages`
  and `find`.

## 1.5.97 — why a run was cut off, and the setting that stops it

- **The assistant can keep working while you use another app.** Runs already keep a notification going, but with battery
  optimisation on, Android (and Samsung's "sleeping apps" most of all) can still close PocketIDE when you switch away, which
  cut the run off. At the first run, the chat now offers **Let it keep working in the background**, which opens Android's own
  question to exempt PocketIDE from battery optimisation. On Samsung phones it also says where "Never sleeping apps" is.
- **A cut-off run says why.** Android records why it closed an app; PocketIDE only reported crashes. A request that did not
  finish now shows the reason: low on memory, battery saver, closed from recent apps, and so on. While battery optimisation
  is still on, it offers the same button again.

## 1.5.96 — a run the app was closed during keeps its work log, and can carry on

- **Nothing is lost when Android closes the app mid-run.** The work log used to be saved only when the reply was, so if the app
  was closed while the assistant worked, its thinking and steps were gone and the chat showed only "Retry unanswered request". The
  log of the run in progress is now saved every few seconds. On reopening, the request shows "This request did not finish…", the
  folded "▸ So far: Thought … · … steps", and two buttons:
  - **Continue from here** sends the request again with what the run had done: files changed, last steps, what it had found,
    and its last thought, so the model carries on instead of starting from scratch.
  - **Start over** sends the request as it was.

## 1.5.95 — the work log: the model's thinking and steps are kept with each reply

- **Thinking is shown and kept.** Models that think before answering (Kimi K2.6, GPT-OSS, DeepSeek, Claude, Qwen's `<think>`)
  stream that thinking; the app used to count it ("1,234 characters of reasoning") and throw it away. Each round's thinking now
  appears in the chat as a folded line, "▸ Thought for 12s: …", that you tap to read in full.
- **Saved with the chat, as Copilot and Claude do.** Every reply keeps its work log: each thought, the model's words between
  steps ("Now I understand the problem…") and each step, in order. Reopen the chat and it is under the reply as one folded line,
  "▸ How it worked: Thought 6 times (2m 10s) · 4 notes · 31 steps"; tap it to go through the run. The log is not sent back to
  the model, so it costs no tokens. Long thinking is trimmed to its start and end (12,000 characters a thought).
- **Chat ⋯ → Export as Markdown** includes the log in a folded section, and benchmark transcripts include the model's thinking.
- **Steps read on one line**: "Ran python3 -c: import json; with open('game/data/maps…" instead of a command broken over many
  lines, in the saved summary and in "Last steps" of a stopped run. Tap a step in the log for the full command.

## 1.5.94 — at most three "you're not done yet" re-prompts, and model advice from the benchmark

- **The assistant is sent back after "done" at most 3 times.** The app has checks that send the model back when it stops early
  (it wrote a tool call as text, printed a file instead of saving it, said "I will change…" and stopped). Seven of them used to
  run outside the limit of 3, so a weak model could be sent back 11 or more times. Now they all share the limit; after 3 the run
  ends with "Not finished:" and the reason. Validated by Copilot on 243 benchmark runs (27 tasks, 3 runs each, 3 models): no model
  lost more than the run-to-run noise, and no task went from always passing to always failing.
- **Model advice** in Help → API keys and in every model list: for coding, pick a large model with tool calls; Kimi K2.6 and
  GPT-OSS 120B finished most tasks in PocketIDE's tests, Llama 3.3 70B often failed to edit files. Groq's key guide now names
  GPT-OSS 120B rather than Llama 3.3.

## 1.5.93 — a bug report that starts with "When…" gets fixed, not answered

- **Requests like "When I tap save the app crashes" are work again.** The app took any message starting with "when" as a
  question, so the assistant was told not to change files and only explained the problem. The benchmark caught it: every model
  failed the settings-merge task 9 times out of 9 because the app refused their edits. Now a message starting with when, where,
  if, once or whenever is a question only if it has a "?" or asks something next ("when does…", "where is…").
- Benchmark: settings-merge moved to the hard suite (it shaped this fix), and a new held-out task, unique-address, took its
  place. The csv-export check accepts the UTF-8 marker Excel uses. Copilot's three-run baseline (Kimi K2.6 67/78,
  GPT-OSS 120B 62/78, Llama 3.3 70B 27/78) is in BENCHMARKS.md.

## 1.5.92 — the bench script runs the held-out tasks

No change to the app. `tools/bench/run-bench.sh` accepted `-s heldout` in 1.5.91 but took its task list from the standard suite, so
`-s heldout` ran the 7 standard tasks and `-s all` ran 20 tasks instead of 26. It now reads `HeldOutBenchTasks.kt` for both.

## 1.5.91 — a held-out benchmark; the MIT edition catches up

No change to how the app behaves on the phone. This release is about measuring it honestly.

- **Held-out suite** (`POCKETIDE_BENCH_SUITE=heldout`, `-s heldout`): six new everyday jobs with hidden checks (deep-merge settings,
  paging off-by-one, a new command-line flag, dates across time zones, removing a feature cleanly, a CSV export). They are written
  separately from the tuning work and must never be used to tune the app: they show whether a hard-suite gain carries over to jobs
  the rules were not written for. `HeldOutBenchTasksTest` proves each check fails at the start, passes a real fix and rejects a shortcut.
- **add-feature check fixed**: `module.exports = celsiusToFahrenheit` is a correct answer and now passes; before, only
  `module.exports = { celsiusToFahrenheit }` did.
- **MIT edition**: now has everything from 1.5.89 and 1.5.90 (Cloudflare fixes, calls written as text, the hard suite and the
  bench script `tools/bench/run-bench.sh`).

## 1.5.90 — a harder benchmark; calls written as text run; the work is not left half done

Measured with the new hard suite (`BENCHMARKS.md`): 13 everyday jobs with hidden checks, three Cloudflare Workers AI models at once.

| Model | 1.5.89 | 1.5.90 |
|---|---|---|
| Kimi K2.6 | 12/13 | 12/13; big-file-many-fixes, the one it missed, then passed 2 of 2 with the audit note below |
| gpt-oss-120b | 10/13 | 10/13 |
| Llama 3.3 70B fast | 1/13 | 2/13 and 3/13 (two runs) |

On the standard suite (7 tasks, one run each) 1.5.90 scores Kimi 6/7, gpt-oss 7/7 and Llama 4/7. Llama's fix-code-not-tests
went from 0 of 4 to 2 of 2 with the announced-change ask below. With the class check, keep-what-exists went from 1 of 4 to
3 of 3 for gpt-oss and from 3 of 4 to 3 of 3 for Kimi.

- **The hard suite and a restart-safe runner.** `HardBenchTasks.kt` adds 13 tasks such as renaming across a project, fixing
  code without touching its tests, finding one cause in a 45-file project and closing a path-traversal leak. Their checks are
  written only after the run. `tools/bench/run-bench.sh` runs several models side by side and copies their reports out of
  `/tmp` every 20 seconds, so a Codespace restart loses nothing; `resume` re-runs only the tasks that have no result.
- **A tool call written as text is run.** Llama 3.3 on Cloudflare often writes `read_file(path="app.js")` or a JSON call in its
  reply instead of calling the tool, and asking it to call the tool rarely helped. The first complete call to a known tool is
  now run as if it had been called (`core/TextCalls.kt`), and a note in the chat says "running it as a call".
- **Code whose line breaks were escaped twice is written as code.** An edit holding `\n` as two characters broke a file
  that Llama could not then repair. Only a `\n` outside quotes at a code line break is changed (`core/OverEscaped.kt`).
- **"Rename X to Y" is checked.** Before the work is called done, the project is searched for the old name, and places that
  still use it go back to the model once (`core/RenameCheck.kt`). Changelog and history files, where the old name belongs,
  are left alone.
- **A class nothing styles is caught.** Asked for "a dark mode button that toggles a 'dark' class", gpt-oss and Kimi wired the
  button and the toggle, wrote no `.dark` rule, and said the page goes dark. When a changed script or page adds or toggles a
  class that no stylesheet, `<style>` block or script mentions, the model is asked once to add the styles or say why none are
  needed (`core/ClassCheck.kt`). Classes a script looks up are taken as markers, and the check is off when a CSS framework or
  a stylesheet from the web may supply them.
- **The work does not end on a failed check.** When the last test or run after the last change failed, the model is asked once
  to fix the cause, or to say why it fails on purpose.
- **"I will fix it" with nothing changed is sent back.** A reply that ends by naming a change it has not made is asked once
  to make it. When edits were tried and none applied, or nothing was tried and the reply still stops at a step, it is asked
  once more. A reply that stops at reading a file it named a second time gets the file read for it; a bare name such as
  `paginate.test.js` finds `test/paginate.test.js`. A reply whose last line announces something that never follows ("Here
  is the change:") is asked for it, at most twice and only after tools ran in between (Llama found the second bug, wrote
  the fixed line, and ended there).
- **"Node.js is not installed" is checked by the app.** When a reply says a tool is missing, the app runs `command -v` once,
  and if the tool is there the model is told and sent back (`core/ToolClaim.kt`). A "command" such as `node:test` (exit 127)
  now comes with a note that it is a module name and that `node --test` runs the tests, and "ReferenceError: test is not
  defined" with a note to import `test` from `node:test` (Llama had replaced its tests with `console.log`).
- **Searching one file works.** `search_files` given a file used to search nothing, and gpt-oss took twenty "No matches" as
  proof. It now searches that file, and a path that does not exist says "Nothing searched".
- **The person's guess at the cause is checked, not followed.** Told "I think money() is broken", Kimi and gpt-oss rewrote a
  correct function. When a problem report guesses a cause, the model is told to check it first, keep code that does what its
  name and comment say, and fix the code that breaks its own contract. Every symptom the person lists is checked against the
  change.
- **Checking many items against a table uses a script.** A request to check every item against a table, spec or list now
  asks for a throwaway `node -e` or `python3 -c` comparison instead of reading by eye. Kimi missed one formula of 300 that way.
- **The model is told when its rounds run low.** About four fifths of the way through the rounds, it is told how many are
  left and to finish the changes it has found instead of starting a new approach (gpt-oss spent its last 18 of 40 rounds on
  scripts that failed).
- **Long chats fit small windows.** The reply limit sent to Cloudflare is lowered when the prompt nearly fills a known context
  window ("you requested 6000 output tokens" ended a Llama run), and after a too-long error the newest messages are shortened
  as well.
- **An edit to a file that is not there says more.** "The project has no .py files" when the guess is in the wrong language,
  and "find the file that holds the code first" instead of a suggestion to create it.
- Smaller: a function body left as "// for now, just return 0" counts as unfinished; an APK is mentioned only when one was
  asked for; a plain web page's file check is named "the app's link check" with no game talk; look_at_preview with no page
  to open is no longer listed as "looked at the page" in the app's record of checks; benchmark transcripts include
  what the model wrote between calls and what the app asked it.

## 1.5.89 — Cloudflare models work, answers finish, a stalled reply is sent again

Measured with the agent benchmark (`BENCHMARKS.md`): three Cloudflare Workers AI models at once on one key, 7 tasks × 3 runs.

| Model | 1.5.88 (1 run each) | 1.5.89 (3 runs each) |
|---|---|---|
| Kimi K2.6 | 6/7 | 21/21 |
| gpt-oss-120b | 0/7 | 20/21 |
| Llama 3.3 70B fast | — | 9–12/21 over three benchmarks (answer-a-question 0/3 → 3/3) |

- **gpt-oss-120b on Cloudflare works.** Every request failed with "Bad input": an assistant message that only calls tools was sent
  with `content: null`, which Cloudflare rejects; it is now sent as `""`.
- **Replies on Cloudflare are not cut off.** Without `max_tokens`, Cloudflare stops gpt-oss and Llama after 256 tokens. When the
  model's context size is not known, the app now sends a cap for Cloudflare (`OutputLimit.whenUnknown`).
- **A reply that stalls before it starts is sent again once.** gpt-oss-120b sometimes accepts a request and then sends nothing for
  two minutes (4 of 21 runs failed this way). When nothing of the answer has arrived, the request is sent once more, with "No
  answer yet: asking again…" in the status line. A reply that has started is never repeated.
- **Errors never show the Cloudflare account id.** A timeout or lost connection used to quote the full server address, which holds
  the account id; it now names the provider ("Cloudflare Workers AI did not answer in time…"), or only the host for your own server.
- **An answer that stops at "I should look at this file" is sent back once.** When the last words of a reply name a step the model
  has not taken ("To find the rate, we can read this file.", or the call written out as text), the app asks it once to take the
  step and give the full answer (`core/Deferral.kt`). Llama 3.3: answer-a-question 0/3 → 3/3.
- **"Run X and report" is run before it is reported.** gpt-oss answered "Node is not installed" without trying. A run-and-report
  request that finishes with nothing run is asked once to run it (`Scope.RUN_FIRST`).
- **An edit to a file that does not exist names the closest files** ("No text file named cart.js. Existing files that may be the
  one you mean: src/cart.js, …"), so the next try lands in the right file (`core/MissingFile.kt`).
- **Only known reading commands run while answering a question.** The sandbox calls any command it does not recognise safe
  (`node server.js`), so `Habits.readOnlyCommand` now also requires the command to be on `CommandRules`' reader list.
- A command that prints only a number ("4.5") keeps it: the progress-bar filter dropped such lines from the result the model read
  (`Trim.dropProgress`).
- The benchmark writes each run's full transcript next to its report, and `BENCHMARKS.md` describes running several Cloudflare
  models side by side.

## 1.5.88 — RuneScape caches become an add-on; the game tools are for every game

PocketIDE is a general coding assistant; the RuneScape game-cache tools were built into its core. They are now an add-on.

- **The RuneScape add-on** (`app/.../addon/osrs`): reading and writing Old School and 2009-era caches, building a game from their
  maps, models and animations (`game_cache`), the rules a cache does not hold from 2009scape server data (`game_data`), It applies only to a project that holds a cache or server data, and can be switched off in
  **Settings → Add-ons**. Nothing changes for existing cache projects.
- **The core no longer refers to add-on code.** It asks the registered add-ons through one interface (`core/AddOn.kt`: tools,
  which calls change files, notes, work before the first round, run steps, templates). A test fails if core code refers to an
  add-on directly. More add-ons can be added the same way.
- **The generic game tools stay in the core for every game**, and the Game developer agent now gets them up front: for the Game
  developer profile, a game request or a project with a game, `game_assets` (starter kits including an open-world 3D kit,
  building blocks for pathfinding, inventory, bank, skills, saving, side-panel UI, sound, model animation and a 3D tile-world
  view, sprites from text, atlases, sound effects, 3D models, imported OBJ/glTF, tile and Tiled maps) is offered from the start
  instead of on request. The profile tells it to start from a kit and reuse the blocks instead of writing an engine.
- The RuneScape-specific art advice moved out of the Game developer profile into the add-on, where it is given only for a project
  that holds a cache. The shared JSON and file helpers the game tools used from the cache code moved to the core
  (`core/JsonText.kt`, `core/AssetEnv.kt`).

## 1.5.87 — one decision for what a request is, shown under it

- **One decision, in one order.** Whether a message is a question (reads and answers, changes nothing), run-and-report (runs and
  checks, changes no files), research (looks things up, is not pushed to edit) or a task is decided once when it is sent
  (`core/Intent.kt`): a question first, then run-and-report, then research. Before, three separate phrase checks were each
  called where needed, with their own order, and a misread request ran with different tools without anyone seeing it.
- **The decision is shown under your message** in Agent mode when it is not a task ("Taken as run-and-report: it runs and
  checks, and changes no files."), with **Run it as a task instead ›**: a tap sends the same message again as a task.
- Follow-up requests the app sends itself (plan steps, fixes) are decided on their own text, not given the kind of your message.

## 1.5.86 — a benchmark with real models

PocketIDE's aim is Copilot- or Claude-level help with the free API keys from the FAQ. This release adds the measurement for it.

- **A live benchmark** (`LiveBench`, see BENCHMARKS.md): the real agent loop runs seven tasks against any list of providers and
  models, each checked by a shell command: fix a bug from a symptom, add a feature in a new file, fix and write a passing test,
  add to a page without breaking what is there, remove a walking barrier without unblocking blocked tiles, answer a question
  without changing anything, and run a command and report without changing anything. It reports pass or fail, model requests,
  tool calls, failed edits, empty searches, refusals and time per run, and a summary per model.
- Keys come only from environment variables (`POCKETIDE_BENCH_KEY_<PROVIDER>`); nothing is written to the project. Without them
  the live test skips, so the normal tests never call a provider.
- The benchmark checks itself: a correct scripted solution passes, a wrong one fails, a question is scored on its answer and on
  every file being unchanged, and the barrier task fails a fix that also unblocks blocked tiles.

## 1.5.85 — the agent loop runs in tests, and the first two bugs it found

Until now the agent loop (NativeAgent) had no tests: it is tied to Android's screen classes, so only the small helpers around it
were tested and every reliability fix was checked on a phone, one run at a time.

- **An agent harness** (`app/src/test/.../bench`): the real, unchanged loop runs on a computer through Robolectric (MIT), with a
  scripted model that plays each round's reply. Seven scenarios run with the normal tests: a fix made and checked, regex search,
  a report-only request that changes nothing, a protected play test, Ask mode refusing a write, an unproven "it works" labelled
  untested, and a repeated failing command stopped by the loop detector. `ModelGateway` is the one seam in the app: the loop asks it
  for each model call, the app uses the network, the tests a script. A live runner for real providers follows.
- **Fixed: a run stopped by the loop detector blamed the round limit.** The message said "Limit: the 16-minute, 24-round limit,
  change it in Settings" for a run that stopped after 5 rounds for going in circles. Stop messages now name the real reason, and
  point to the setting only when a round or time limit was the reason.
- **Fixed: such a run was saved as a normal finish**, without the "Why it stopped" note, so "Continue" had nothing to start from.
- The request-dependent part of the instructions moved to `core/AgentPrompt.kt`, so the harness sends what the app sends.

## 1.5.84 — the page look in every mode, and no made-up app features

Found on the phone: "Run look_at_preview with test=all" in Ask mode and in Agent: suggest mode. Neither mode offered the page
tool, and both times the model invented a way to run it ("press the Run button, which opens look_at_preview", "use the command
from the preview interface"). Neither exists.

- **look_at_preview works in Ask and Agent: suggest mode** whenever the project has a page. Looking changes nothing. In suggest
  mode, while edits wait as cards, the result says the page is the saved version without them.
- **A request that names a tool the mode cannot use gets the app's answer first**, in plain words with what to do: for example
  "write_file can't run here: it changes files or runs commands, and this mode only reads. Tap the mode chip (it says "Ask")
  and choose Agent". The same goes for a project with no page, or a model called without tools. The model is told the same
  and told not to describe other ways.
- **The model is told what PocketIDE's modes are** (Ask, Plan, Agent: suggest, Agent), that the person cannot run its tools and no
  button does, and to say it does not know rather than describe app features it has not been told about.

## 1.5.83 — the rest of the benchmark findings

The open items from the 1.5.81 emulator benchmarks.

- **Splitting a job into parts has a 3-minute deadline.** The plan request streams, so its idle timeout never fired while a slow
  model kept writing (one run spent six minutes before touching a file). Past the deadline the parts are taken from what was
  written so far, or fall back to the default grouping, and the build starts.
- **Read-only commands run without a Run/Skip card:** ls, cat, head, tail, wc, grep, rg, find (without -exec or -delete),
  diff, tree and the like, and git status, log, diff and show. Not when the command writes to a file (`>`), runs a
  sub-command, or names a file that may hold keys (.env, tokens, credentials, local.properties). Deny rules still apply.
  Before, "cat tests/play.txt" stopped a run until someone tapped.
- **Checking stops when the work is checked.** After a check that runs the program has passed, four more rounds that only run
  checks (no file changed, no open plan steps) end the run with a request for the final report. One run kept auditing the same
  numbers with node, then python, then the shell for 13 minutes; the commands differed, so the loop detector did not see it.
- **The parts card says what it really does:** "at the same time with 3 copies of kimi-k2.6 on Cloudflare" when every lane is
  one model, instead of "on 3 providers". Three calls at once to one account can hit its rate limits; the card now shows that.

## 1.5.82 — "run and report" changes nothing, and tests are not weakened to pass

Found in the 1.5.81 emulator benchmarks: asked only to run the saved play tests, the assistant edited app.js to add a test hook
and rewrote tests/play.txt so that "reload keeps the entries" no longer reloaded. The tests passed and no longer tested reload.

- **A request that only asks to run, check, test, verify, review or report changes no files.** "Run look_at_preview with
  test=all", "Check whether the reload test passes", "Review game/main.js" run commands and look at the page as usual, but any
  file edit is refused and the assistant reports what passed and failed, describing a fix and asking before making it. A
  request with a change in it ("Run the tests and fix anything that fails") is a task as before.
- **Saved play tests are not weakened.** Removing or changing an existing step of tests/play.txt is refused, in every mode and
  through every write tool, unless the request asks for the tests to change. Adding tests and steps is always allowed. A
  failing test is a finding about the app: the assistant fixes the app, or says why it thinks the test is wrong and asks.
- The MIT edition now has 1.5.78 to 1.5.81 too (the loop detector, on-demand tools, steering, and the benchmark fixes).

## 1.5.81 — fixes from emulator benchmarks

Found by running five tasks on an Android 16 emulator with Kimi K2.6 and checking the results with a separate harness
(see COPILOT-CHANGELOG-SINCE-1.5.78.md and EMULATOR-TESTS.md in the GPL repository).

- **Context ranks files by content, not only by name.** A file enters the context when its symbols or text match the request,
  not only when its path does ("retry backoff jitter" now finds `src/net/client.js`). Ten new offline fixtures check it.
- **Failed parts fall back to sequential.** When parts built at the same time fail, Agent mode rebuilds them one after another
  instead of pausing for Retry, and the row shows the error.
- **An unanswered request survives a restart.** A saved chat whose last message has no reply shows "Retry unanswered request",
  which resends it without duplicating the history (requests with pictures say the pictures must be attached again).
- **Standard runs have a time limit:** two thirds of a minute per round, between 10 and 60 minutes (10 at the default 12
  rounds). Autonomous keeps 90 minutes. The clock pauses while a Run/Skip card waits for you.
- **Play tests accept `tap "Label"` and `run JS …`**, the forms models write naturally.

## 1.5.80 — a message typed while it works is read at once

Before, a message typed during a run waited for the whole model reply and every tool call in the round, which could be minutes.

- **A reply that is streaming is ended when you send.** The assistant keeps what it had written, then reads your message and
  carries on or changes course. (Stop is unchanged and does not read the message.)
- **Calls left in the round are skipped.** After the call that is running finishes, the rest of the round is not run (each gets a
  "not run" result) and your message is read next. A slow command still has to finish first.
- **The bubble says what happened:** "sent, waiting to be read", then "read by the assistant after 12s".
- **The message is saved in the chat** (after the request it interrupted), so it survives a reload and shows in exports.
  A message also clears the loop detector.
- The trace records each pickup ("steer: read at round N", "steer: skipped N call(s)", "steer: model call interrupted").
- Parts, plan and other non-tool-calling runs still queue the message, as before.

## 1.5.79 — a shorter tool list, and observations are answered, not run

- **Eleven rarely used tools load on demand** (game_cache, game_assets, game_data, doc_outline, doc_edit, history, project_audit,
  web_search, web_fetch, look_at_images, advisor). Each round now sends one `use_tool` entry that names them in a line each,
  instead of their full definitions. Calling `use_tool` with a name, or calling one of them directly, adds it for the rest of the
  run; a direct call still works at once. The text format, which lists tools in the prompt, is unchanged. Design after Cline's
  on-demand tool docs (Apache-2.0); the code is PocketIDE's own.
- **A report worded as a statement is a question.** "Found a critical indexing bug! …", "I noticed the map is cut off", "Looks
  like it loads twice" are answered, not run as tasks (one ran for 27 minutes and changed nothing). A message with an order in
  it ("I noticed it is blank, make it red", "found a bug, please fix") is still a task.
- **A question gets at most 12 rounds**, read-only, then is wrapped up.

## 1.5.78 — one loop detector instead of nudges

Rules after OpenHands' StuckDetector (MIT); the code is PocketIDE's own. See SOURCES.md and NOTICE.md.

- **A run that goes in circles is stopped once, with a reason.** The detector looks at what was called and what came back, not at
  how often a tool is used: the same call with the same result 3 times; 3 failed calls in a row; the same two calls alternating
  for 6 calls; 12 rounds in a row with no file changed and nothing new found. A changed file clears it; a command does not,
  so a test that keeps failing the same way counts. The model is asked for a Done:/Left: checkpoint, and the reason is saved in
  the note that "Continue" starts from.
- **Removed:** the "edit now" nudge (it fired 30 times in one request without effect), the read streak's nudge and block, the
  40-commands-with-no-change stop and Autonomous mode's 10-quiet-rounds stall check. The refusal after 3 searches that find nothing stays.
- The request reminder every few rounds stays.

## 1.5.77 — one finish budget, and stopped runs say why

- **Every finish check shares one budget of 3.** After the model says it is done, the app's checks (proof of the claims, unfinished
  files, the game check, the plan, play tests, the "nothing was checked" nudge, finish hooks, the security scan, the reviewer, the
  look at the page, the page check, the project's tests, the advisor) may send it back three times in all. Each message says
  "Finish check 2 of 3" so the model knows the run will end; after the third the run ends as "Not finished:" with the one
  unmet check named. Aider stops after 3 reflections; before, each check had its own counter and a request could be sent
  back more than twenty times (one run was sent the same page error ten times, twice). The budget is the same in Autonomous mode.
- **A stopped or unfinished run records why, and the last check that passed.** The saved note now starts with "Why it stopped:
  the finish budget of 3 checks was spent; the last one was: …" (or the round limit, a loop, Stop) and "Last check that passed:
  looked at the page (round 14)". "Continue" starts from that evidence instead of from the plan's first open step. Runs that
  end "Not finished" are saved with the note too, not only runs that were stopped.
- The syntax guard that puts a working file back when an edit breaks it (both editions) was already in place; the review
  that led to this release had listed it as missing.

## 1.5.76 — search is grep, reads fit the model, missing data files are not failures

Three tool-layer faults found in a review of the 31 Oct and 1 Nov runs (one request made 23 searches, 19 of them "No matches";
another spent two million tokens on a page check). Designs after SWE-agent's agent-computer-interface study (MIT) and the
grep conventions every coding agent uses; the code is PocketIDE's own.

- **`search` takes a regular expression**, like grep and ripgrep: `flags\(`, `me\.path`, `ensure\(.*,.*\)` and `a|b` now match.
  Plain text still works, and a pattern that is not a valid expression is searched as text. Before, the search was plain text
  and every escaped pattern a model wrote returned "No matches", which the model took as proof the name did not exist.
- **Searches are capped at 60 hits** with a count per file and a request to narrow the pattern. A miss now says the text is not
  in the project and points to file_outline or repo_map, instead of inviting another guess.
- **A file read fits the model's context window.** The cap is 15% of the window (about 118,000 characters on a 262K model,
  14,000 on a 32K model; 24,000 when the window is unknown) instead of a fixed 6,000, so a 50 KB file is read in one call.
  A read that was cut can be read again for the rest. An explicit `end` line is honoured. Before, a long file cost seven reads
  and the first pages were shortened out of the history before the last arrived, so edits were made from memory.
- **The history budget follows the window too**: half the window, at most 360,000 characters, instead of a fixed 90,000.
- **A missing data or media file no longer fails the page check.** A 404 for a `.json`, picture, sound or font the page asks
  for is a note in the summary ("3 requested files do not exist"), not a fix request; a missing script, style sheet, page or
  wasm module, or a file the request names, still blocks. look_at_preview's verdict follows the same rule. A streamed-asset
  game could never pass before; one run was sent the same 404 ten times, twice.

## 1.5.75 — the assistant stops searching for names that do not exist

- After three searches in a row find no matches, further searches are refused and the assistant is sent to the entry file or an outline of the file that should hold the behaviour (a run made 19 such misses).
- A miss streak is cleared by a change to a file, and a refusal does not reset it.

## 1.5.74 — whole-file rewrites are guarded

- write_file replaces a whole file, so it is refused for an existing file over 150 lines, for an entry file (index.html,
  main.js, style.css) of 20 lines or more, and for any write that would cut a file by half. Change those with edit_file or
  edit_lines. A whole rewrite still goes ahead when you ask for one (say "rewrite" or "from scratch").
- A PLAN.md rewrite that drops steps is refused. Add steps or tick them; do not replace the plan with a narrower one.
- The page verdict says FAIL when every canvas on the page is one flat colour: the page draws nothing.
- The assistant is told to use edit tools for files that already exist (write_file is for new files and asked-for rewrites).

## 1.5.73 — stopped runs carry on

- A stopped run is saved in the chat with what it did and found: its plan, the files it changed, its last steps and what
  the assistant had said. "Continue" now carries on from there. Before, a stopped run was not saved, and "Continue" started
  the job over from its first step.
- A request that runs 40 commands without changing a file is wrapped up with a checkpoint (Done: / Left:), instead of
  reading on until it is stopped. Past 40 commands every command was already refused, but one run kept going for 30 minutes.

## 1.5.72 — every tool works for models without native tool calling

- A model that refuses native tool calling (it is marked for a week) keeps all of the tools. Before, it had only four actions
  (read, grep, list, run). The tools are listed in the prompt, the model writes each call as a `<function=name>` block with one
  `<parameter=name>` block per value, and the results come back as text under "EXECUTION RESULT of [name]:".
- Values are typed by the tool's schema (numbers, true/false, JSON lists), and blocks inside a code fence are shown, not run.
  Replies may hold up to eight calls; more are dropped.
- Planning with a refused model still uses the old text format. Models that accept tools are unchanged.
- The block format is OpenHands' (MIT); the parser and the tool list are our own code.

## 1.5.71 — a context meter and a written summary in long runs

- **Context meter.** While the assistant works, the working line shows how full the model's context window is (for example
  "Thinking · context 42% of 262K"), as Cline and OpenCode do, so a slow or forgetful long run is easier to understand.
- **A written summary in every long run.** When older messages are first shortened, the work so far is summarized in writing
  by the second model, or now by the main model when no second model is set (Codex and Gemini CLI do the same). The summary
  keeps your rules, limits and the sites, files and resources you named, not only what was done.

## 1.5.70 — the assistant looks first and follows your direction

Ideas from open-source coding agents (Cline, OpenHands, Gemini CLI, Aider), written for PocketIDE:

- **The requirements list is based on what the assistant saw.** With a tool-calling model it first looks, read-only: the project
  files, reference pictures, the links you gave, and the web when you name resources. Each point ends with its source
  ([file: …], [picture: …], [link: …], [request] or [assumed]). A link it cannot read (a search results page) is named, with a
  request for a screenshot, instead of a guess. Before, the list was written from memory with every tool switched off.
- **It uses what you name.** Code under a licence that allows it (MIT, BSD, Apache-2.0) may be reused, with its notice kept;
  a site or repository you name is used, or the assistant says why it could not.
- **Tool calls written as text are caught** (JSON `tool_calls`, gpt-oss tokens, `<function=…>`): the app asks once for the real
  call instead of showing the text as the answer.
- **A model that refused tools once is tried with tools again after a week.** Older marks, some set by a misread error before
  1.5.67, expire now, so those models get game_cache, web search and the page preview back.
- **Problems on the page are reproduced first** (look at the page, check the element) before reading code, and the fix is
  checked the same way.
- **Your request is repeated near the end every 10 rounds** of a long run, in your own words, so it is not lost.
- The "edit now" nudge comes after 8 rounds without a change (was 4), and says to check the cause directly instead of reading more.
- "Dig deeper", "find resources/code/examples" and "on the internet" count as research, which is not pushed to edit files.

## 1.5.69 — every message says which version and model it came from

- Each message you send and each reply is saved with a short tag: the app version and build number, then the provider
  and model (for example `v1.5.69-gpl (118) · cloudflare/@cf/moonshotai/kimi-k2.6`). Chat exports show it above each
  message, and the assistant record shows it on the "request started" and "request ended" lines, so a problem in a log
  can be traced to the version (and the fixes) that produced it. Chats saved before this version have no tag.

## 1.5.68 — Modal is no longer a model host

- Removed: "Your own model on Modal" (the Gemma 4, Mistral Small and gpt-oss presets, the deploy, the Stay awake switch and the
  wake-up wait before a message). Modal is not used to host language models in PocketIDE.
- Other providers and the remote-server setup are unchanged; the rented-GPU examples now name RunPod and Vast.ai.

## 1.5.67 — requests fit small context windows

- A model with a small context window (a 32K self-hosted server, say) failed with "Input length exceeds model's maximum context
  length": the history budget was fixed, so a big project's history, system prompt and tool list did not fit. When the window is
  known, the history and the reply length are now sized to it, and the newest messages kept whole are fewer.
- When a request is still too long, the app learns the window from the error, shortens older messages and sends again (up to
  twice), instead of stopping.
- A too-long error no longer switches the model to the text format ("This model doesn't accept tool calls"). That only happens
  when the provider really refuses tools.

## 1.5.66 — times on chat messages

- Each message in the AI chat now shows when it was sent (your message) or written (the assistant's reply): the time today, or
  the date and time for older ones. Messages saved before this version show no time, because their time was never recorded.
- Modal: a **Stay awake** switch in ⋮ → AI → Your own model on Modal. Off (the default): the server sleeps after 10 idle minutes
  (was 5). On: one GPU stays on all the time, about $109 a day on an H200, which is more than the $30 Starter credit. Turning it
  on or off redeploys the server (about 2 minutes, same key and model).

## 1.5.65 — replies are no longer shortened after a cut-off

- A reply that ran past a model's length limit used to halve that model's reply length, and the lower limit was kept; every
  later cut halved it again (down to 4,096 tokens), so long replies got cut sooner and sooner. A cut-off no longer lowers
  anything: the model gets its full reply length and is asked to send the lost part in smaller pieces. Limits lowered by
  earlier versions are dropped, so affected models are back to full length.
- Only a provider's own per-minute output limit (Groq) still caps a reply.
- Chat exports (Save the full chat log, Send to the Claude app, all chats) keep every message whole; they were cut at 8,000
  characters (600 for automatic follow-ups).

## 1.5.64 — chats keep the message you sent

- Fixed: a request was only saved to its chat after the assistant replied. If the app was closed, restarted or went back to the
  home screen while a request ran, the new chat was not on disk, so the app reopened the previous chat. Edits made by the
  request were kept (they are saved as they happen), but the chat that asked for them was not. Now the message is saved to its
  chat as soon as it is sent. If the reply never arrives, the chat still shows your message, so you can send it again.

## 1.5.63 — no more reading in circles

- Fixed: on big projects the assistant could reread the same files for many rounds ("I now have the complete bug list. Let me
  read…") without ever editing. Older file contents are shortened to fit the conversation, but the app still treated those files
  as already read and refused rereads ("use the copy you already have"), so the model went around in circles. Shortened files
  can now be read again.
- New guard: after 16 reads in a row with no change, the assistant is told to start fixing what it already found, file by file;
  after 28, whole-file reads and project scans are paused until it changes something (reading a few lines and searching still
  work). Web research does not count, and questions and read-only reviews are not affected.

## 1.5.62 — waiting for a starting Modal model

- Sending again while your Modal model is waking no longer sends early (that gave "no upstreams available (HTTP 503)"); the
  message waits and goes out when the model is ready.
- When the Modal model answers "no upstreams available" or a 502/503/504 because it is still starting, the chat waits for it
  (counting the seconds) and sends again by itself, instead of showing an error.
- Fixed: Gemma 4 on Modal would not start ("model type gemma4 ... Transformers does not recognize this architecture"): the
  server now installs Transformers 5.6.2, which knows Gemma 4.
- The server key no longer appears in Modal's logs (vLLM reads it from the server's settings instead of its command line), and
  the server refuses to start without one. Every deploy now makes a new key, so an older one stops working.
- New model choice: **Mistral Small 4 119B** (Mistral's newest open model; chat, reasoning and Devstral coding in one, with tool
  calling), 4-bit on one B200 with a 64K context, about $6.25 an hour while running.
- The first start may take up to 30 minutes (large downloads); the app waits up to 35.
- To apply: ⋮ → AI → Your own model on Modal → Redeploy / change the model… → pick a model → Deploy.

## 1.5.61 — Modal menu fix

- Fixed: ⋮ → AI → Your own model on Modal showed only the details and a Close button once a server was set up; its actions
  (wake, use as main model or advisor, redeploy, stop) were hidden. They now show under the details. "Change the model or
  redeploy…" is now called "Redeploy / change the model…".

## 1.5.60 — Modal server fix

- Fixed: the Modal server crash-looped on start ("Containers in llm.Server are repeatedly failing to start"). Modal loads the
  script a second time inside the server, where the deploy-time key variable does not exist, and the script stopped itself.
  It now reads the key the server already has. Deploy again from ⋮ → AI → Your own model on Modal to replace the broken one.

## 1.5.59 — your own strong model on a Modal GPU

- ⋮ → AI → **Your own model on Modal (GPU)**: run a strong open model (Gemma 4 26B with tool calling, or gpt-oss 20B) on a Modal
  GPU as your own server. This edition has no Linux environment, so it gives the server script (from Modal's own vLLM examples) and three commands to deploy it from a computer, then connects the address you paste.
- It becomes the **main model**, the **advisor** (plans and reviews while a cheaper model edits), or both. Only this phone can
  use it: the server needs a key the phone keeps, and the key and token are never written into a project.
- It starts when you send a message and stops after 5 idle minutes, so idle time costs nothing (about $4.50 an hour while
  running on an H200; Modal's Starter plan includes $30 a month). When it has been asleep, the chat wakes it first and says so.

## 1.5.58 — insights, wiki, secrets and the rest of GitHub (part 4 of 4)

- **Project page tabs**: Code, Issues, Pull requests, Board, History, Releases, **Wiki**, **Insights**, Builds and **Settings**.
- **Insights**: a contribution graph (yours and the assistant's commits), code frequency per week, the most-changed files (red when
  the assistant keeps rewriting the same file), a branch graph, and an activity feed of commits, pull requests, issues and
  releases. **Activity** on the home page shows commits across all projects.
- **Wiki**: Markdown pages in wiki/ (so they have history), listed and read on the page; New page creates one.
- **Settings tab**: branch rules, history settings, fork and sync, **secrets** (API keys given to commands and servers as
  environment variables, masked in all output, never in files or history; the assistant only learns their names), the
  **setup file** (.pocketide/setup.json: packages and steps a project needs),
  **dependency updates** (newer versions and known vulnerabilities from npm, PyPI and osv.dev, handed to the assistant as an issue
  on its own branch), **check the whole project** (secrets, broken JSON, conflict markers, unfinished code, filed as issues),
  **history on GitHub** (send the phone's commits to the linked repository, or bring its history here), archive and pin.
- **Search all projects** from the home page; a hit opens its project at that line.
- **Pinned** projects come first and **archived** ones last; an archived project is read-only.
- **Snippets**: editor ⋯ → Snippets saves selected code and inserts it in any project, like Gists.
- **File history**: "Step through versions" slides through every version of a file and can restore one.
- Jobs built in parts commit each part separately, so the history shows which part wrote what.

## 1.5.57 — issues, the board and templates (part 3 of 4)

- **Issues** (project page → Issues): your to-do list for the project, with labels (bug, feature, idea, question, docs) and
  comments. **Assign to the assistant**: it gets a branch of its own (issue-12-…), works on the issue there, and its committed
  work opens a pull request that closes the issue when you merge it.
- **Board** (project page → Board): issues in To do, In progress and Done. They move by themselves as the assistant starts work
  and pull requests merge; Actions → Move on the board moves one by hand.
- **#12 links**: "#12" in a README, an issue or a comment opens that issue or pull request.
- **Save as a template**: a project's menu (Projects screen) can save it as a template, offered under New project with its
  files (not its history).
- **Markdown**: task lists (- [ ] and - [x]) in a README tick off with a tap and save to the file; ```mermaid blocks get a
  "View diagram" button that draws them.
- The assistant already helps with history from part 2: it writes commit messages and pull request summaries, explains commits,
  and reviews pull requests with suggestions you can apply.

## 1.5.56 — forks, pull requests, checks and releases (part 2 of 4)

- **Pull requests** (project page → Pull requests): from a branch, or from a fork, into another branch. Each one shows its
  commits, files changed with their diffs, checks, and a conversation. Approve, request changes or comment; ask the assistant
  to review it (it answers with a verdict, findings, and line suggestions you apply with one tap); let it summarize the changes;
  mark it draft or ready; merge it (keeping its commits) or squash it into one commit. "Merge automatically when ready" merges
  it by itself once nothing blocks it. "Fixes #3" in a pull request closes issue 3 when it merges.
- **Forks**: Fork makes your own copy of the project, history included, that remembers where it came from. Sync fork brings in
  the original's new commits, and the original can open pull requests from the fork's branches.
- **Checks** run after every commit: valid JSON, no secrets, no leftover conflict markers, and the project's tests when it has
  them. Pull requests and their merge box show the result.
- **Branch rules**: protect a branch so it only changes through pull requests (optionally with passing checks and an approving
  review). The assistant then works on a branch of its own (assistant/…) and opens a pull request instead of changing the
  protected branch, and your own commits there offer a new branch plus a pull request. The rules can also send the assistant
  to its own branch always, and delete branches after their pull request merges.
- **Releases** (project page → Releases): name a version with a tag, write notes (or have them written from the commits since
  the last release), and attach files: the source as a zip is added for you, and any project file (an APK, a build) can be
  attached and saved to the phone.

## 1.5.55 — version history for your projects, like GitHub (part 1 of 4)

Projects can now keep their own history on the phone, in real git format (a hidden .git folder that git itself reads), with no
GitHub account and nothing sent anywhere. Project page → History → **Start version history**.

- **Commits**: the Commit button lists changed files with their line-by-line changes; pick files, write a message or let the
  assistant write it (✨). Files that look like they hold a secret (API keys) are flagged before they enter the history.
- **History**: every commit with who made it (you or the assistant), its tags, and its changes file by file. From a commit:
  undo it (revert), copy it onto the current branch (cherry-pick), start a branch there, tag it, put one file or the whole
  project back to that version, or ask the assistant to explain it.
- **Branches**: the ⑂ button on the project page switches, creates, renames, deletes and merges branches. Uncommitted changes
  can come along, be stashed, or be thrown away. Files changed on both sides are merged line by line; real clashes open a
  conflict screen: keep yours, keep theirs, ask the assistant, or edit it yourself.
- **Stashes** (set changes aside), **tags**, **file history** and **who changed each line** (blame) — from the file's long-press
  menu or the editor's ⋯ menu.
- **Line links**: editor ⋯ → "Ask the assistant about these lines" or "Link these lines in the chat" (game/main.js:12-20). A
  link in any message shows the assistant those lines.
- **Large files** (20 MB and up, or marked in .gitattributes) are stored once outside the history, in Git LFS's format.
  Build output (node_modules, build, dist…) is left out through a .gitignore.
- **The assistant**: its finished work is committed automatically as "PocketIDE assistant" (History settings to turn off), and
  its new history tool reads status, commits, diffs, blame and file history, so it can find when something broke.

## 1.5.54 — an assistant that works more like a colleague

- **Questions get answers.** A message that asks something ("What is that?", "What is it doing?") is answered: the assistant may
  read files and run read-only commands, but changes to files and commands that change things are refused until you ask for them.
- **Proof before "done".** The app keeps its own record of the checks that ran (commands, the page, play tests, the project's
  tests). A summary that says "it works" or "tests pass" with nothing run after the last change goes back once, and every finished
  job ends with "Checks run by the app's record: …" (or "none … untested"), so you see what was really checked.
- **Knows its limits.** When an answer needs facts it is not sure of, the assistant says so and asks whether to research it with the
  web tools (or researches right away when you asked it to, or in Autonomous mode); when a job needs a tool it lacks, it asks
  whether to install it.
- **Better habits for each kind of request.** Problem reports: reproduce, find the cause, say it in one sentence, then fix it.
  Big builds ("a game like OSRS", "a clone of …"): look for existing open-source code or data first and offer it. "Everything" or
  "identical": say what is realistic first, and never call a partial result complete.
- **Talks like a colleague.** Replies lead with the answer in plain words, then why, then at most one next step.
- **A stronger model for thinking.** The advisor (⋮ → AI → Multi-model workflow and advisor) now uses your strongest other provider
  with a key (Anthropic, OpenAI, Gemini, haimaker, xAI, DeepSeek) when none is picked, and holds the assistant to the same habits.
  If you have such a key while a free model does the work, the app suggests it once.

## 1.5.53 — enough memory for the 2009scape world

- The 2009scape server ran out of memory while loading the map (1.5 GB). start.sh now gives it 45% of the phone's memory,
  between 2 and 5 GB (2009scape's own tests use 4 GB), and stops cleanly with advice if that is still not enough.
- Running a 2009scape step updates the project's setup.sh, build.sh and start.sh to the current version, unless you rewrote them.

## 1.5.52 — ▶ runs the next step

- In a project whose ▶ steps go in order (2009scape server: download, build, start), ▶ now always runs the next step instead of
  repeating the last one run.

## 1.5.51 — run steps for game servers

- ▶ commands can now have their own time limit, and a background server that is not a web page can open an app when it is ready
  (used by the GPL edition's 2009scape server projects, which need its Linux environment).

## 1.5.50 — no wrong NPCs from an old spawn list

- Real NPC spawns (game_cache action=enemies) now check each NPC's name as well as its number. A spawn list made from an older version of the game can use a number that now belongs to a different NPC (a Lumbridge Castle cutscene gypsy became Tempoross), so those are left out and listed in the result.

## 1.5.49 — game data keeps your items

- Adding real NPC spawns (game_cache action=enemies) no longer replaces a game's whole items.json with the few dropped items. It now adds or updates those items and keeps the rest, as its message always said.

## 1.5.48 — stays on what you asked

- **Older plans ask before they run.** A plan, parts plan or requirements list made before your latest messages asks "Run this older plan?" when you tap Approve or Continue, instead of quietly running work you asked for hours ago.
- **The assistant follows your latest message.** It works on PLAN.md items only when you ask it to continue the plan.
- **A picture with no words** is sent as "look at this image: what does it show, and what should I do?", instead of an empty message the assistant answered by resuming old work. When a message with an image fails, the app says the model may not read images and offers "Retry without the image".
- **search finds alternatives:** `ladder|stair|trapdoor` finds any of them (before, it looked for the whole text and found nothing), and it can search one folder.
- **NPC positions:** the assistant is told that the game cache holds no spawn data and that no map object is an NPC (a type-9 object is a diagonal wall). `game_cache action=enemies spawns=<file> area=x1,z1,x2,z2` now reads an Old School spawn list such as NPCList_OSRS.json and keeps only the NPCs standing in that area.

## 1.5.47 — research is not cut short

- A request to find things out (research, a deep dive, a review, "see what we can use") is no longer pushed to "make the change now" after a few rounds of reading. That nudge is only for requests that ask for a change. Before, a deep dive into the OpenRS2 archive was steered into editing game files and running play tests instead of reporting what it found.

## 1.5.46 — the preview finds its page in big projects

- The preview finds its page on disk (index.html at the top, else the nearest page) instead of in the file list, which leaves files out of projects with more than 2,000 files. In a big project the Preview tab showed "No .html file in this project", the assistant lost look_at_preview, and the page check reported no errors without checking anything.
- When there really is no page, the page check now says it was skipped instead of reporting a pass.
- In a big project the file list keeps the files nearest the top first, so the code (game/main.js) is listed before thousands of exported data files.
- A cheaper model that fails to answer (for example a thinking model that rejects the conversation) is not tried again until the app restarts, instead of once per request.

## 1.5.45 — search a big project without running out of memory

- Search and Replace in the project read one file at a time instead of holding every file's text at once, which crashed the app on large projects. Replace finds its matches off the main screen, so the screen keeps working.
- Files at the top of the project always appear in the file list, even in a project with thousands of files (a zip you just added there no longer goes missing).

## 1.5.44 — ideas from Liza and its recommended tools

- **Parts agree before they build (Liza's architecture step).** A plan for parts built at the same time now lists, in a form the app can check, which files load which, the names each file exports, the element ids each page has and the data files read. Before anything is built, the app checks that every file named belongs to a part or already exists, and sends the plan back once if not.
- **The result is checked against that agreement** after the parts finish: pages that do not load what they should, modules missing an agreed export, pages missing agreed ids and missing data files go to the whole-project check as problems to fix first.
- **The final review decides (binding review with done criteria).** After parts, the review judges every requirement on its own (R1: met / not met) and says whether the parts fit. If anything fails, one fix runs and the review checks again; if it still fails, the result is reported as not done instead of finishing quietly.
- **The parts run is kept in the project** in `.pocketide/parts-state.json`: request, requirements, parts and their files, the agreement, what the checks found and the review's verdict.
- **find_code (from Semble's idea):** finds code by what it does, in plain words, ranked by keyword relevance with a boost for code that defines a matching name. No model to download.
- **Shorter command output (from RTK's idea):** test runs keep failures and the summary (passing tests are counted, long stack traces cut), git status drops its hints, git log becomes one line per commit, npm and pip installs keep the result and errors.
- **The project map (from stacklit's idea)** also lists the files changed most often lately and the project's test command.
- **More MCP presets:** DeepWiki (questions about public GitHub repositories, free), Perplexity and Ref (need their own keys). A preset can put its key in the address.

## 1.5.43 — the game must load before a run is done

- The game check now catches what broke the world streaming build: page elements looked up through a helper such as `byId('p-bar')`, a class looked up in the wrong file (`getCtor('ActorManager', './content.js')`), data paths outside `data/`, and missing scripts in the page the preview opens.
- A run can no longer end as done while the game check still finds problems that break the game. After two fix requests it ends as not finished and lists them. Files that nothing loads are reported but do not block.
- A request that asks for work, answered without any tool calls, is sent back once to do the work.
- Parts of a parts run get at most 80 rounds on a fixed setting and 100 in Autonomous, so Autonomous never gives a part less.
- The chat's full record shows what the game check and the page check found.
- 1.5.42: no more out-of-memory crash when a reply finishes or a message is sent on a large project.

## 1.5.41 — longer runs follow your setting, checks after parallel parts, OpenRouter sign-in, rules from other agents, apply_patch, finish checks and round checkpoints

- Tool rounds per request now sets every limit: how long the text format may look around and fix, the rounds each part of a parts run gets, and the explorer and reviewer. At the default (12) nothing changes; at 24, 48 or Autonomous they all get more room. Autonomous time and stall limits also cover parts.
- Parts built at the same time now get one checking pass over the whole project afterwards: tests, the game check, play tests, the reviewer and the page look run on what the parts wrote, and problems between parts get fixed.
- The text format (models without tool calling) now runs the game check, the project's tests and the saved play tests after it writes files, and asks for fixes when they fail.
- Stop messages say which limit was reached and where to change it.
- Export without large files lists the files it left out, on screen and in LEFT-OUT-OF-THIS-ZIP.txt inside the zip.
- OpenRouter: "Sign in with OpenRouter" makes a key for PocketIDE without copying one (or paste a code if the browser cannot return). "Check spend" shows the key's spend today and this month, its limit and the free-model requests left. Optional fallback models and a provider choice (cheapest, fastest, no data collection). Requests with tools only go to providers that support tools. Out of credit (402) says what to do.
- Rules from other coding agents are read too: .clinerules, .cline/rules, .kilo/rules, .roo/rules, .cursor/rules (with their globs), .windsurfrules and .windsurf/rules. Fixed: Copilot's .github/instructions files were never found, because the project list skips hidden folders.
- Skills in the open Agent Skills format (a folder with SKILL.md) work, from .pocketide/skills and from .agents/skills, .claude/skills and .github/skills. Skills that match the request are sent in full; the rest are listed by name so the assistant can open one when it fits.
- GPT and Codex models get apply_patch, the patch format they are trained on: one call adds, changes, renames or deletes several files, and nothing is written unless the whole patch applies. Deleted files come back with Undo or a checkpoint.
- Finish checks: list commands under "beforeFinish" in .pocketide/hooks.json (for example "npm test"). They must pass before the assistant may finish a request that changed files; failures go back to it to fix. They follow your command rules and auto-run setting.
- Long runs save a checkpoint after every round that changed files (up to 25 per request), so ⋯ → Checkpoints can rewind to the middle of a run. Restoring the first round still undoes the whole request.
- Plan mode researches first, read only: the assistant looks at the project and the web without changing a file, then writes the plan. Models that cannot call tools get the plan request directly.
- Git → "Send a task to Copilot" makes an issue from your task and assigns it to GitHub Copilot, which opens a draft pull request. "Copilot's pull requests" lists them. If GitHub does not accept the assignment (it needs a Copilot plan with the coding agent on for the repository), the issue is still created and the app says so.
- Export all chats: "Chats in this project" → Export all writes every saved chat of the project into one Markdown file in chat-logs.
- Diagnostics keeps its record across restarts: each request adds a line with its model, how it ended, time, tokens and files changed, next to the problems already logged. Keys stay masked.
- Chat menu → Send this chat to the Claude app shares the chat into the Claude app (or the share sheet when it is not installed). Open the Claude app opens it, or claude.ai.
- Full record: each chat keeps every command and tool call the assistant made, with its arguments, output, round and time, and the start and end of each request, in order. Export it from ⋯ → Export or summarize.
- The assistant is told to make the change after four rounds of reading with nothing changed (agent mode), and a second identical file read is refused with a pointer to the copy it already has. Replies and these prompts are in the full record.
- A project map for the assistant: the new repo_map tool lists the project's main files and the names they define, the files other files use most first, so it can find where code lives before reading files.
- Better names for the project map: when the Linux environment is present, definitions are read with tree-sitter parsers (JavaScript, TypeScript, Python, Java, Kotlin) instead of patterns, so the map finds functions and classes more reliably. Falls back to the pattern reader if the native library is missing.
- Skills that break the open Agent Skills format still load, and the Diagnostics log says what is wrong. A JSON file that does not parse is flagged to the assistant the moment it is written, so a broken data file is fixed before anything else.
- Diagnostics now says why the app's previous run ended, including when Android closed it for memory or the system stopped it, and notes when the app left the chat screen during a request.

## 1.5.40 — key links, parallel parts with Custom server, more providers, and a reply-length fix

- Every provider that takes a key now shows a "Get a key" link in its settings, tappable.
- Custom server uses tool calling too (game_cache, game_assets, parallel parts). A server that refuses tools falls back to the text format, as before.
- New providers: DeepInfra, Kimi (Moonshot AI), Z.ai (GLM) and Nebius Token Factory. haimaker, DeepSeek, xAI, Together and Fireworks use tool calling; NVIDIA NIM, Hugging Face and SambaNova now do too.
- Parallel parts only use providers that are set up with an address or a key; an empty Custom server is never picked.
- Fixed: a reply cut off for length no longer drops the reply length to 1,024 tokens. The floor is now 4,096, and a limit stored too low is raised back to it.

## 1.5.39 — app kits for bookings, chats, recipes and AI assistants, and sign-in with an online database

- New app kits, each with play tests: a booking calendar (pick a day and service, book a free time, cancel), chat rooms (rooms, your name, delete your own messages), a recipe box (ingredients and steps, a servings scaler that rescales amounts including fractions, tick-off ingredients), and an AI assistant chat that reaches the model through an endpoint on your own server, so no API key is ever in the app; demo mode without one.
- Sign-in and online storage for any app kit: add a cloud block to app.json (Supabase URL and anon key), paste cloud-setup.sql once in Supabase, and the app shows a Sign in button. Each person's records are private; chat rooms are shared with everyone signed in. Changes made offline are sent when the app is online again, and records made on the device before signing in join the account.
- The tracker, notes and booking kits use the online store when a cloud block is set; without one they stay on the device as before.
- Describing an app in plain words now picks the matching kit for a booking calendar, a recipe box, a group chat or an AI assistant app.

## 1.5.38 — games in 3D with animations, and 2009scape as a rule book

- Games built from a cache are now 3D: real ground heights, every building and object as its real model, roofs that hide when you walk in, and a camera you turn with a drag and zoom with a pinch. Options switches back to the flat map.
- The player and enemies are animated models from the cache: standing, walking, running, punching, kicking, blocking, dying, and chopping with the axe in hand.
- Old School games use the game's own interface pictures: side panel tabs, skill icons, orbs, hit splats and the compass.
- 2009scape's own cache can be read (its maps, models, items, NPCs and objects) as well as the Old School cache.
- New game_data tool: reads 2009scape's server data for what a cache does not hold, such as where NPCs stand, their real stats, drop tables, shops and item bonuses. Enemies are placed at their real spawn points with real drops.
- game_data can now list and export the 2009scape server's data tables (276 of them: recipes, levels, experience, tools, quest requirements) as JSON, read straight from its source code.
- The game kit's parts (3D world, animation player, pathfinding, inventory, bank, skills, sounds, saving, side panel) can be copied into any project with game_assets action=blocks, for other games.
- Broken script writes are refused and put back, kit files can be restored, and the assistant cannot call a game finished while its check fails.
- Big requests can be split into parts by default, and each part can be given its own model from a list you make (or the assistant picks from it).
- The project overview no longer freezes in large projects. Claude models through OpenAI-style gateways now use prompt caching.

## 1.5.37 — games are built for you, then refined

- The app now builds the whole game folder from the cache (map, buildings, side panel, chat, saving, sounds, combat, woodcutting) before the assistant starts, when you ask for a game in a known town.
- "Continue", "fix it" and other follow-ups keep the game instructions once a game exists in the project.
- Cache and picture tools no longer freeze the screen while they work.
- The game kit gained skills and levels, woodcutting, a bank, sounds, saving and a working side panel.

## 1.5.36 — uploads show in the project overview and search
- Files added with Add files now appear in the project overview straight away (before, the overview kept its old list until something else refreshed it).
- Large projects say how many files the overview leaves out, instead of hiding them silently. Search covers every text file in the project, not only the first 2,000.
- Search says how many binary or large files it skipped, since it can only search text files up to 1 MB.

## 1.5.35 — layout report for look_at_preview (for models that cannot see images)
- look_at_preview now reports layout facts the page itself measures, as exact text: controls or text that overlap (with the size of the overlap), a control hidden under another element (and what covers it), text cut off by its box (how much), and tap targets smaller than 44 px. A model that cannot see images can act on these directly. A page with none says so.
- The report runs inside the same hidden preview as before, so there is no extra browser step.

## 1.5.34 — page check finds broken imports, unused scripts and missing ids; game fixes
- The page check now finds a page that cannot start: an import of a name the file does not export, and a script that looks up an element id no page has (with the close name it should use). Before, a game with a broken import looked fine to the check and showed a black screen.
- The game check lists every script that nothing loads, and the assistant is told to import or delete such files, run project_audit, and never report done while the page shows an error.
- Game kit: hit chance and maximum hit now follow the game's formulas (without gear bonuses), and the inventory is saved per game folder, so two games on one page do not share it.

## 1.5.33 — buildings, minimap and side panel, enemies, inventory, game check
- Game cache: new `place_objects` draws every building and object of a town (or map squares) as a picture, with where it stands, whether it is under a roof and which way it faces. The game kit draws them on the map and hides roofs while the player stands under one. `scaffold` now does this for the whole town, and also writes coins (items and icon) and 20 starter enemies.
- Game cache: new `enemies` writes enemies.json and a picture per enemy for a game: different fighting NPCs picked at random by combat level and seed. Hitpoints and highest hit are estimates from the combat level (the cache does not hold them), and the file says so.
- Game cache: new `check` looks over a generated game folder and lists missing scripts and data files, invalid JSON and objects with no picture, with how to fix each.
- Game kit: minimap, a side panel (status orbs, tab buttons, inventory of 28 slots), a chat box with tabs, a roof-aware map, and a starter rules.js (combat level 100, random enemies that chase and fight, drops, death and respawn). The inventory has stacking, tap to select, long press for Use, Drop and Examine, drag to swap, and is kept in the browser. The tab buttons are for looks only; Examine shows the item's name because the cache holds no examine text.
- The built-in game recipe is now seven ordered steps (world, buildings, player and gear, stats, enemies, rules, check) and says how to reuse them for a similar game. It still appears only when the project holds a cache and the request is about a game that names the cache, RuneScape or a known town.

## 1.5.32 — verified step queue, "make me a RuneScape game" from the cache, run guards
- Verified steps (Settings → Assistant, on by default): in a plan, the next step is not given to the model until the last one is checked. The app first checks what it can see itself (files the step names exist and are not empty, something actually changed, pages load their files), then a read-only review must quote the files for every point (PASS only with quotes and nothing missing). A step that fails goes back to the builder with the exact findings, twice; then the plan stops with Check again, Retry, Accept anyway, Skip or Stop. The queue and each step's state (queued, issued, verifying, verified, failed, skipped) are kept in .pocketide/queue.md. Fast mode skips the review and keeps the app's own checks.
- Game cache: a vague request such as "a game with the mechanics of RuneScape in Falador, combat level 100, white knight gear, 20 different hostile enemies" now gets a built-in recipe, but only when the project holds a cache and the request is about a RuneScape-style game. New: game_cache action=place (10 towns), find kind=npcs attackable=true (a repeatable random pick of different fighting NPCs by combat level), export kind=player worn=<item ids> (gear replaces the body parts it covers); items now show their worn models and slots.
- Run guards: a command that copies the text of a shortened earlier message is refused, commands outside the project folder are refused, and after a command the assistant is told when the page now misses a file it loads.

## 1.5.31 — Ask / Plan / Agent, Steer button, rules reminder, ground and icons in the game kit
- The Agent chip in the chat is now a mode menu: Ask (answers and reads, never changes anything), Plan (a plan to approve first, then it builds step by step), Agent (builds directly) and Agent, suggest only (each change is shown for you to apply). Picking one stops the automatic choice until Settings turns it back on.
- While the assistant works, the send button turns into "Steer" once you have typed something, so a tap sends your message into the job instead of stopping it. With an empty box it is still Stop, and the Stop chip above stays.
- The "Remember" rules in POCKETIDE.md are repeated at the end of every step message (and the whole file is now part of the prompt for parts built at the same time), because small models weigh the last message most.
- Game kit: the setup also exports shaded ground for the squares around the start, and the map shows it (squares without a picture keep the flat colours). New `icons.js` with `iconFor(itemId)` for exported item icons. The "Loading map..." text now clears. Nothing is added to a project unless the assistant runs the game kit setup on a project that has a cache.

## 1.5.30 — parts at the same time, speed options, connection changes, world data for games
- Build in parts: when the parts share no files, they are built at the same time, each by its own provider (every provider with a saved key and the same message format as your main one; a model on the phone is never used). The grouping now lists the files each part owns and a short agreement (file names, load order, shared function names, ids). A part can write only its own files and runs no commands; a free provider takes the next part. If a part fails on another provider it is tried once on the main one. When all parts are done, the usual check that they fit together runs. Parts that would write the same file, an edited list, or only one provider with a key are built one after the other as before. Stop stops all of them. Settings → Assistant → "Build in parts: build parts that share no files at the same time".
- A change from mobile data to Wi-Fi in the middle of a reply no longer shows a raw error: the reply stops, says the connection changed, and Retry works. Build in parts: Continue and Retry now work after a failed part.
- Cloudflare "Capacity temporarily exceeded" (429) waits 8, 15 and 25 seconds and retries, and shows a short message instead of the raw reply.
- Speed options (Settings → Assistant): Fast mode (no second-opinion model calls), Phase routing (reading steps use the cheaper model), an offer to retry or switch provider when a reply shows no sign of life for a minute, and an offer to start fresh with a summary when a chat has grown long.
- Game cache tools: map exports carry the height of all four floors, the underlays, overlays, shapes and flags; new exports "ground" (shaded ground), "icons" (item icons at their in-game angle) and "player" (the body from the identity kits). Animations and music are not decoded.

## 1.5.29 — reports go to their own chat
- "Fix the app itself" now sends the report to a separate "PocketIDE reports" project (made the first time) and a new chat there, instead of the chat you have open in your own project.

## 1.5.28 — model search across providers, fixes from a review
- New in the model switcher: "Search all providers". Type part of a model name (separators optional, so "kimik2" finds kimi-k2) or tap kinds (Tools, Images, Free, Coding, Reasoning, Small & fast, Works here); it searches every provider that has a key, whatever the provider. The per-provider list has the same search and chips.
- Model suggestions no longer offer a model the provider has refused tool calls for on this account. A model OpenRouter keeps for apps on its list (HTTP 403, "only available on agentic harnesses") is now marked unavailable, so it leaves suggestions and the "Works here" filter.
- PLAN.md gate: only boxes ticked since the last saved version go back to open; boxes that were already ticked after a passing check stay ticked. The gate now also covers edit_lines and multi_edit.
- The 3D starter game ships a player model, so a fresh 3D game loads with no missing file and can reach VERDICT: PASS.
- Cylinders, cones and spheres in model_build now take size like boxes (they used to ignore it and come out at the default size).
- Importing a zip into an empty project puts it at the root again (a whole project); into a project with files, it goes into a folder named after the zip.
- The project check (`project_audit`) runs in the background, so a big project no longer holds up the screen.
- Stop answers at once: "Stopping… waiting for the model to answer." If the model has not answered after 5 seconds, or you press Stop again, the request is dropped and the chat goes back to idle.

## 1.5.27 — imported zips get their own folder
- "Import zip into project" now unpacks into a folder named after the zip (rsmod-main.zip becomes rsmod-main/), not the project root. A single top folder inside the zip is still removed, so the contents sit directly in that folder. The toast names the folder.

## 1.5.26 — checks the assistant cannot talk its way past
- Every preview check now ends with one line: `VERDICT: PASS` (the page loaded with no console errors or missing files) or `VERDICT: FAIL`, with the reason. Only a PASS counts as a check for plan steps.
- A `PLAN.md` checklist is gated like the plan tool: any tick written after the last code change, with no passing check since, goes back to open, and the assistant is told why.
- New `project_audit` tool: finds identical files stored twice (and the space they waste), folders repeated inside themselves (such as `cache/cache`), and pictures, sounds and scripts that no code or data file names. It only reads.
- The game-developer prompt now says what to do with a vague request: decide the scope, write PLAN.md with one box per feature, scaffold a template, and tick a box only after a PASS.
- Not changed: searching inside zips without unpacking them (zips are unpacked on import, and search covers the files that result).

## 1.5.25 — characters fill their pictures
- `game_assets model_render trim=true` cuts the empty border, so a goblin holding a weapon is drawn as big as its body allows instead of tiny in the middle of a square picture.

## 1.5.24 — keeps working with the screen off
- While the assistant works, the app now holds the processor awake (a partial wake lock, released when the job ends or stops), so a long job carries on after the screen turns off. The "assistant is working" notification stays up with a Stop button. If the phone still stops it, set the app's battery use to Unrestricted in Android settings.

## 1.5.23 — pictures of trees, characters and items from their models
- A game cache has no pictures of trees, NPCs, items or buildings; the "sprites" in it are interface art numbered separately from object, NPC and item ids. An assistant that exported sprite 1276 for "Tree" got an arrow button. The tool descriptions now say so.
- `game_assets model_render` draws any exported model (or several parts together, or a folder) as a flat picture from a chosen angle (yaw, pitch, size). Tested on a real cache's tree, oak, bank booth and goblin.

## 1.5.22 — fixes from a phone run
- "Finish creating the game", "continue", "next ..." and similar are now treated as work to do, not as questions (the assistant had switched itself off for them).
- A steering note disappears once the assistant has read the message.
- Exporting or saving a zip runs in the background, so the screen no longer goes black on a big project. New: "Export without large files (over 20 MB)", so a project holding a game cache makes a small zip.

## 1.5.21 — one model suggestion per task
- The "this model looks better suited" note now appears once per kind of task, not again every time the provider falls back to another model.

## 1.5.20 — universal game toolkit
- New tool `game_assets`, always on in agent mode, for any game (2D or 3D, simple or large), not only RuneScape-style ones.
- `scaffold kind=topdown|platformer|walk3d|blank` writes a runnable starter game plus a shared engine in `core/`: fixed-step loop, keyboard/joystick/button/tap input, tile maps, grid collision, camera, saving, sound, tweens, state machine, UI (text, bars, buttons, dialogue), entities. Each template was run in a browser: no errors, and it responds to input.
- Make art and sound from nothing: `sprite` (text rows and a palette to PNG), `atlas` (pack PNGs, Phaser format), `sound` (presets, melodies, or tones to WAV), `model_build` (3D models from boxes, cylinders, cones, spheres, planes), `level` (ASCII map to tile map JSON).
- Bring files in and out: `model_import` (glTF, GLB, OBJ; one colour per face, no textures), `model_export` (OBJ and MTL for Blender), `tiled` (Tiled JSON maps).
- Not done: skeletal animation, textures, music tracks, multiplayer or server code.

## 1.5.19 — 3D models and model viewer
- `game_cache export kind=models` reads 3D models (56,011 of the 56,025 in a real cache decode; the rest are empty placeholders). `ids` takes model numbers or `item:4151`, `npc:3`, `object:1276` to get a definition's model with its colours (NPCs are joined from their parts, textured faces get the texture's average colour). Item, NPC and object definitions now carry their model numbers, colour swaps and scales.
- `game_cache scaffold kind=viewer` writes a three.js page (model.js, viewer.html, the bundled three.js) that shows the exported models spinning.
- Not done: animations and skeletons, textures as real images, old-format models, music, interfaces.

## 1.5.18 — map objects, collision, game kit
- `game_cache` now also: `keys` (downloads the OpenRS2 square keys), `export` of map objects with a walking-collision grid (trees, walls, buildings; tested on 2,250 squares of a real cache), `export kind=floors` (ground colours), and `scaffold` (writes a runnable tap-to-walk map game: pathfinder, renderer, world loader, with the data it needs).
- Game kit files in the app (world.js, pathfinder.js, render.js, main.js): shortest path in 8 directions with walls and diagonal rules, doors that can be opened, map drawn with the real ground colours.
- Packing keeps map objects (stored unencrypted in the new cache).
- Not done: 3D models, animations, music, interfaces.

## 1.5.17 — game cache reader and writer
- New tool `game_cache` (appears when the project holds a `main_file_cache.dat2`): `info`, `find` (items, NPCs, objects by name), `export` (JSON for definitions, PNG for sprites, collision grids for map squares) and `pack` (build a new cache from those files, so items, sprites and maps can be created from scratch).
- Pure Kotlin, no libraries: own bzip2 decoder, cache container and index reader/writer, sprite and PNG codecs. Tested on a real revision-228 cache: 6,390 sprites, 2,365 map squares and 100,000+ definitions read without errors; sprites round-trip exactly.
- Not covered: 3D models, animations, music, interfaces, and map objects (encrypted). A packed cache is a new file in the same format, not a patch for Jagex's client.

## 1.5.16 — picture sheets, JSON file dumps recovered, page argument for look_at_preview
- New tool `look_at_images(dir, page)`: shows 48 pictures per page with their file names under them, so numbered sprites (1442_222.png) can be told apart by looking instead of guessing. Walks the folder itself, so large sprite folders are not cut off.
- `look_at_preview` accepts an optional `page`.
- A model that prints a file as a JSON dump instead of calling write_file is now recovered, and models that do this are marked as unreliable for chat.
- Prompt hints for numbered image names.

## 1.5.15 — branches and history, tab/space edits, stand-ins in the text format, edit benchmark

- **Branches and History in the GitHub menu**: list the repository's branches, make a new branch from the current one, switch to a branch (then Pull to load it), and read the latest 25 commits. Done through GitHub's API like the rest of sync, so no extra library.
- **Edits written with tabs now land in space-indented files** (and the other way round): the replacement is converted to the file's indentation instead of mixing the two.
- **Stand-in files in the text format feed the fix loop**: a file written as an empty body or "TODO: implement" counts as a problem, so the model is asked to write the real code before the work is called done.
- **Edit benchmark**: a new offline test (EditBenchmarkTest) holds the shapes of model output that failed in real chats; a miss blocks a release. The tab/space case came from it.
- Tests: GPL 168, MIT 165.

## 1.5.14 — several edits in one call, habits studied from Copilot Chat

- **New tool `multi_edit`**: the assistant can make up to 12 exact edits across one or more files in a single call, in order, each seeing the result of the earlier ones. One failing edit does not stop the rest, and the reply says which failed and why. Fewer rounds means less time and money.
- **Working habits in the prompt**: read big ranges, make independent calls together, do not assume, do not ask permission for tools or name them to you, include unchanged lines around an edit, and do not change files with shell commands. These come from reading Microsoft's open-source Copilot Chat extension (MIT; its LICENSE file was checked); the wording is ours and NOTICE.md credits it.
- Tests: GPL 164, MIT 161.

## 1.5.13 — ranked project map

- **Files the project depends on come first**: when the assistant's context is built, files are ordered by how many other files use their functions and classes (a PageRank over references, like the repo map in Aider), starting from the open file and the files your request names. It breaks ties between files that match your request equally, and orders the symbol list that stands in for files that did not fit. Large files (over 200 KB) are not scanned and at most 400 files are ranked.
- Tests: GPL 161, MIT 158.

## 1.5.12 — unzip a zip file in the project

- **Tap a .zip in the file list to unpack it**: a dialog offers "Unzip" (into a folder named like the zip, next to it) or "Open as file". Long-press a zip also shows "Unzip here". Entries with unsafe paths are skipped, the size and file-count limits apply, and it runs in the background.
- Tests: GPL 158, MIT 155.

## 1.5.11 — files and chats are written safely

- **No half-written files**: project files and saved chats are now written to a side file and swapped in only when complete, so a crash, a forced stop or a full disk keeps the old content instead of leaving a truncated file.
- **Stand-in warning in the text format too**: when a model without tool calling writes a file that is empty or holds "TODO: implement" style stand-ins, the reply now says so.
- Edit matching was checked and already handles trailing spaces, indentation, blank lines and copied line numbers, so no change there.
- Tests: GPL 157, MIT 154.

## 1.5.10 — steadier prompt start for provider caching

- **The fixed part of the prompt now comes first** (tool rules, environment, outside tool servers) and the parts that change with the project (project notes, open file, folder scope, skills) come last. Providers that reuse the start of a prompt (OpenAI-style automatic caching, Anthropic caching) can now reuse more of it, which should lower the "little comes from the cache" warnings for Kilo, Groq and OVH. The saving has not been measured yet.

## 1.5.9 — unfinished files, big archives, empty replies

- **Stand-in files are caught**: when the assistant writes a file that is empty or contains "TODO: implement", "...rest of the code", "not implemented" and the like, it is told right in the write result, and before it can finish it is asked once to replace each stand-in with working code (so it stops saying "Part done" over placeholders).
- **Big archives stay closed**: unpacking an archive over 50 MB (a 130 MB cache zip, say) is refused unless the request names it, in both the tool-calling and text paths.
- **Models that only return empty replies** are marked in the model list and skipped by recommendations, like models the provider refuses.
- Tests: GPL 156, MIT 153. Not yet tried on a phone.

## 1.5.8 — Agent mode chosen per request, one-tap MCP servers, credit fallback

- **Agent mode is chosen for each request** (Settings: "Choose agent mode for each request"). A question answers without changing files; a document edit stays a card to Apply or Reject; building or fixing something applies changes and runs checks as it goes. A request that says not to change anything stays in review. Autonomous rounds are never chosen automatically. Tapping the Agent chip sets the mode yourself and turns automatic choice off; the mode you had is put back when the request ends.
- **Out of credit, try the next provider**: when a provider says the account has no credit or quota left, the request goes to the next provider that has a key, and that provider is skipped for an hour. Rate limits still wait and retry on the same provider. Turn it off in Settings.
- **Quick add for MCP servers**: Context7 (current library docs) and GitHub (your repositories and issues, asks for a token) are one tap away in Outside tool servers. Test on add shows whether the address answers.
- **Assistant fixes from real chat logs**: a bold or italic action line (**ACTION: run ls -la**) runs the right command; long command output keeps its start and end, so a big unzip reports its exit code; a command that already failed the same way is not run again; screenshots from earlier turns are dropped from the history, so text is not pushed out; a model the provider does not offer (404), or that needs sign-in, is marked in the model list, and a model's own context limit is kept; provider-published prices are used for cost; dependency folders are skipped in project listings; jsdom is refused like other browser libraries.
- Checked in this build: all unit tests pass (GPL 151, MIT 148). Not yet tried on a phone.

## 1.5.7 — Test connection checks chat for every provider

- **Test connection now sends one tiny chat request** with your model for every provider, which is the real test. Before, a model list that is public (OpenRouter, Ollama Cloud, NVIDIA, Hugging Face, SambaNova and others answer without a key) could say "Connected" with a wrong key, and a missing list (Cloudflare) could fail while chat worked.
- **Clear messages** for a refused key (with where to get the right one for that provider), a device public key pasted by mistake (ssh-…), a model the provider no longer knows, an account without credit, a wrong address, and free-tier rate limits (which mean the key works).
- **Retired models are caught**: when your model is no longer in the provider's list, Test connection says so and suggests one.
- **New OpenRouter default**: qwen/qwen3-coder:free is no longer offered on OpenRouter; the default is now poolside/laguna-s-2.1:free (a free coding-agent model with tool calling).
- Checked on 2026-10-23: every provider's chat address answered as expected (no wrong paths), the free no-key providers (Kilo, LLM7, OVH) answered real chat requests, and every other default model is still listed.

## 1.5.6 — Model recommendations from what providers say

- **Provider facts are kept**: when a model list is loaded, PocketIDE keeps what the provider says about each model: its description, context window, reply limit, tool calling, image input, reasoning and price (OpenRouter, Mistral, Gemini, Groq, Cloudflare and Anthropic lists each report some of these). They are saved between runs.
- **Recommendations in the chat**: when you send a task and a clearly better model for that kind of task is available from a provider you have a key for, the chat says so once, quotes what that provider says about the model, and offers a "Use …" button. Questions get no advice; building apps favours tool calling, coding models, long context and long replies; tasks with pictures need a model that reads images.
- **In the model list**: "Recommended for building apps" shows the top three at the top, and a long press on any model shows what its provider says about it.

## 1.5.5 — Models grouped by what they do

- The model list (Test connection, the model chip) is grouped by what each model is designed for: Coding and agents, Reasoning, Sees images, General chat, Small and fast, and Not for chat (hidden unless you tap Show all or search).
- Where the provider says so (OpenRouter does), models are tagged "tools" (tool calling, which gets the live plan and the checks after every write), "images" and "free". In each group, models with tool calling come first. Type "tools" in the search box to see only those.
- Mixture-of-experts names such as "29b-a4b" count by their active size, so they land in Small and fast.

## 1.5.4 — Big projects and the text format

- **Export keeps every file**: exporting a project as a zip stopped at 5,000 files without saying so, so big projects lost files (in one case everything except the first 4,999 sprites). There is no limit now.
- **The assistant sees the files that matter in big projects**: when a project has more than 2,000 files, code and text files are listed first and bulky files (sprites, caches) are left out by folder, with a note saying how many each folder has. Before, thousands of sprites could hide index.html.
- **The text format no longer stops right before building**: models without tool calling look around for at most 6 rounds. Rounds where a command changed something (an unzip that worked) no longer count toward that, and when the limit is reached, or the model only announces "Let me create the files", it is asked once to write them now instead of stopping.
- **You are told when a model uses the text format** and what that means (fewer checks, limited rounds), once per session.
- Unpacking archives (unzip, tar x, 7z x) now counts as changing files, so a checkpoint is saved first.

## 1.5.3 — Bigger uploads

- **Files from the phone**: up to 512 MB each (was 64 MB), for "Add files" and for chat attachments added to the project.
- **Zip import**: up to 1 GB once unpacked and 100,000 files (was 64 MB). The zip is unpacked to storage as it is read, in the background, so big zips no longer hold everything in memory or freeze the app.
- **Chat attachments over 25 MB**: they are too big to put into a message, so PocketIDE offers to add them to the project instead (copied without loading them into memory); the AI is told the file is there.
- Zip entries that try to leave the project folder (`../`) are skipped.

## 1.5.2 — Fixes from a code review

- **Missing numbers in generated code**: some servers (seen with Cloudflare Workers AI) send a token that is only digits as a JSON number. Those tokens were dropped, which left code like `const port =;` and `'px Arial'`. They are kept now.
- **Tool calling for Cloudflare Workers AI and Ollama Cloud**: these now use the tools (with checks after writes, cut-off handling and the plan) instead of the older text format. A model that refuses tools is switched back to the text format automatically. If a model writes files as text in tool mode, nothing half-done is saved and it is asked to use the tools.
- **Plan steps no longer stay open forever**: a step marked done before any check now becomes done when a check passes, and in projects where nothing can be run, done is taken as given. Before, such steps stayed "in progress" and the saved progress told the next request to continue a finished job.
- **Checks count only when they ran**: a command that was refused, declined or blocked no longer counts as checking the work. The "check before finishing" step is skipped in review mode, where changes are not on disk yet.
- **"Always allow" applies at once**, even later in the same request.
- **Background notification**: a very short request could stop the service before Android allowed it, which can crash on some phones. Fixed.
- **Hooks file is the person's**: the assistant can no longer change `.pocketide/hooks.json`.
- **Setup steps**: which version already ran is remembered in the app, so no marker file is added to the project.
- **/cloud follow-up** finds its GitHub run more reliably (new agent files name the run after the issue), and **/deploy** waits longer for a new site to come up.

## 1.5.1 — /cloud results come back to the phone

- After /cloud sends a job to GitHub, the chat follows it: a line shows whether the agent has started or is working, and when it ends you get the pull request link, the agent's note when it made no changes, or the failure with the end of its log and an "Ask the assistant to fix it" button. A notification tells you when it ends while PocketIDE is in the background. It follows for about an hour; the job keeps running on GitHub either way.

## 1.5.0 — Command rules, a last look at the page, rewind, work summaries

- **Command rules**: an "Always allow" button on the command card, and /rules to list or change them (/rules allow npm test, /rules deny git push, /rules remove …, /rules clear). A command made of several parts (a && b, a | b) runs without asking only when every part is allowed; a deny rule on any part blocks it. Commands that can delete work or reach outside the project are still always asked about. Rules live in the app, not in the project, so a downloaded repository cannot allow itself anything.
- **A last look at the page**: when the assistant changed a web page and has not looked at it since, it is shown the page once before finishing (with a screenshot when the model can see images) and checks it against your request.
- **Rewind the chat too**: ⋯ → Checkpoints now offers "Files and chat", which puts the files back and removes the conversation from that turn on, or "Files only".
- **Summaries for long requests**: the first time a long request's older messages are shortened, the assistant gets a summary of the work so far (files changed, commands run, and a short written summary from the second model when one is set).

## 1.4.1 — Publish with /deploy

- **/deploy** publishes a static web project from your GitHub repository with GitHub Pages: it turns Pages on (from the top folder or docs/), waits for GitHub's build, then checks that the live page really answers. You get the link, or the reason it failed and an "Ask the assistant to fix it" button. It publishes what is on GitHub, so push first. On a free account Pages needs a public repository.

## 1.4.0 — Closing the gap with Copilot and Emergent

- **Keeps working in the background**: while the assistant works, an ongoing notification shows what it is doing, with a Stop button. Android no longer freezes a long request when you switch apps.
- **More instruction files**: besides POCKETIDE.md and AGENTS.md, the assistant now reads CLAUDE.md, GEMINI.md and Copilot's `.github/instructions/*.instructions.md` (applied by their `applyTo` patterns to the open file or folder). Folder-level CLAUDE.md files count too.
- **Security check when the assistant finishes**: changed files are checked for API keys and passwords in code, database rules open to everyone, SQL built from request data, and request data run as code. Serious problems go back to the assistant once to fix; the person sees the list, never the secret itself.
- **Second opinion**: when a second (cheaper) model is set, a read-only reviewer on that model checks the changes against your request before the assistant finishes. Real problems go back to the assistant once.
- **Setup steps** (`.pocketide/setup.json`): this edition cannot run them, so the assistant is told they did not run and can suggest /cloud.

## 1.3.17 — Replies that get cut off, and following instructions

- A reply cut off for length now lowers the reply length for that provider and model (halved each time, down to 512 tokens), so the next requests fit. The app says the new length in the progress line. A run stops only after six cut-offs.
- The assistant is told to do the steps the person names, in order, and not to add files or features they did not ask for; a step it cannot do is named, and the rest continue.
- Fixed a broken sentence in the tools prompt that mixed two instructions together.

## 1.3.16 — Current step with context

- The assistant sees the current step in full, the steps already finished and the steps still to do (by title), so it keeps the thread without the whole plan and does not redo finished steps.

## 1.3.15 — One step at a time

- The assistant now sees only its current step, the number of steps done, and the next open step in its reminders, instead of the whole plan. The full plan is still saved in the project.

## 1.3.14 — Protected feature list

- Within one request, the assistant's plan cannot drop or reword steps. It can change their status and add new steps. If it tries, the plan is not changed and the assistant is told which steps to keep.

## 1.3.13 — Steps count as done only after a check

- A plan step marked done stays open until a check (run_command or look_at_preview) runs after the last file change. The assistant is told why.

## 1.3.12 — Checks before finishing

- Before the assistant gives its final answer after changing files, it is asked once to run the tests or the program (run_command) or look at the page (look_at_preview). If nothing can be run, it must say what it could not check.

## 1.3.11 — No more repeated plans

- A plan that is posted again unchanged no longer counts as progress. After a few such rounds the assistant is asked to wrap up, instead of repeating the first step forever.
- When the plan repeats, the assistant is told to do the next step now, and the step is named.

## 1.3.10 — Cloudflare model list, clearer Ollama Cloud errors

- **Cloudflare models**: after a successful connection test, the model list loads from your account, and the model picker shows the text-generation models.
- **Ollama Cloud 403**: the error now says the key was refused and how to replace it.
- Requests send a PocketIDE user agent instead of the default Java one.

## 1.3.9 — Cloudflare connection test

- **Test connection** for Cloudflare sends one short chat request instead of asking for the model list, which Cloudflare's chat address does not serve. The result says "Connected" or gives the status code and the likely fix.

## 1.3.8 — Simpler Cloudflare setup

- **Cloudflare Workers AI**: Settings now has one "Cloudflare account ID" field. Paste the 32-character ID and the address is built for you; the ACCOUNT_ID placeholder is gone.

## 1.3.7 — Agent procedures (/sop)

- **/sop**: runs a step-by-step procedure on your task. Built in: debug, feature, refactor, game-qa (play-tests a browser game with the preview), review. `/sop` alone lists them.
- Add your own procedure as `.pocketide/sops/<name>.sop.md`. A project file with the same name replaces the built-in one.

## 1.3.6 — Helper agent, project rules, hooks, build-failure hand-off

- **Fix a failed build**: tap a failed build in the Builds tab and choose "Ask the assistant to fix it". It reads the end of the GitHub log and starts a request.
- **explore**: the assistant can send a read-only helper to search the project and report back, so its own conversation stays short.
- **remember**: the assistant can save up to three lasting facts per request to POCKETIDE.md. Folder-level AGENTS.md / POCKETIDE.md files now apply to files in that folder.
- **Hooks** (`.pocketide/hooks.json`): `protect` lists paths the assistant may not write (.git and signing keys are always protected); `afterWrite` runs a command such as `node --check {file}` after matching writes and gives the result to the assistant. Commands are confirmed unless you let commands run on their own.

## 1.3.5 — Checks after every write, scripted play-testing, saved progress

- **Syntax check after every write.** When the assistant writes or edits a JavaScript, JSON, CSS or HTML file (including inline scripts), the file is parsed straight away and any error, with line and column, goes back to the assistant in the same step. JavaScript uses the bundled Acorn parser (MIT, works offline in both editions).
- **Play-testing.** `look_at_preview` can play input before it looks: taps, held touches, key presses, typed text and pauses (for example `tap 180 320; wait 500; key ArrowRight 400`). It reports the game state before and after, so the assistant can check that the game really responds.
- **Saved progress for long jobs.** The plan of a job is saved in the project (`.pocketide/progress.json`) each time it changes. The next request picks up at the first unfinished step, even after closing the app or switching models, and an item is only marked done after it was checked. Type `/progress` to see it or `/progress clear` to drop it.
- **Undo one part of an edit.** In the changes card, open a file's diff to undo single parts of the edit and keep the rest.
- **Notifications.** When PocketIDE is in the background you get a notification when the assistant finishes, stops, pauses at the spending limit, or waits for your OK to run a command. Android 13 and later asks for permission once.
- **Usage.** Usage lines show how much input came from the cache and how much was saved to it, the usage screen flags models where caching is barely used, and Claude models are priced with Anthropic's list prices when you have not set a price.
- Slash commands now start with a fresh spending limit, like typed requests.

## 1.3.4 — Spending limit, the assistant can see the page, sturdier edits

- **Limit per request.** Settings → How long it may work: a dollar and a token limit for one request (default $1.00 and 3M tokens; 0 = off). Fixes, reviews and the advisor all count. At the limit the assistant pauses and asks: Continue (allows the same amount again) or Stop here (keeps everything done so far). The live status line shows the running cost. Claude models use Anthropic's list prices when you have not entered a price, so the dollar limit works out of the box.
- **The assistant can look at the page.** New `look_at_preview` tool: the page loads in a hidden phone-sized browser and the assistant gets a screenshot (models that can see images), elements placed off the screen, each canvas, console errors, and the game state. Game canvases (Phaser, Three.js) are captured too. New 2D and 3D game projects define `window.render_game_to_text()` so the check can read the game state.
- **Fewer wasted fixes.** When a fix leaves the same problem, the assistant stops instead of trying the same thing again; with an advisor set, it first asks the advisor for a different approach and gets one more try. The page check no longer calls a working canvas game "empty".
- **Sturdier edits.** Edits now also match when the model copied line numbers, spaced things differently, or added or dropped blank lines. When an edit still fails, the assistant is shown the closest lines with their numbers. New `edit_lines` tool replaces lines by number.
- **Long files.** `read_file` takes start and end lines, so the assistant can read past the first part of a long file instead of guessing the rest.
- **Readable progress.** What the assistant says between steps stays on screen as tappable ▸ rows instead of being replaced. Camera zoom and screen-fixed HUDs are explained to the model for Phaser games.

## 1.3.3 — Stop fixes and a complete file list

- **Files no longer vanish from the assistant's view.** The file list used to stop after 2,000 files, so a folder like `node_modules` could hide real project files, and the assistant then called them missing and rewrote them. Installed-package folders are now left out of the list, a real file can always be read, and the assistant is told what was left out.
- **Stop is reliable.** Two bugs could leave the app stuck "busy" so every message queued and nothing sent. Stop now works between steps, and pressing it twice forces the chat back to idle.
- **Guards.** Installing or running a browser (puppeteer, playwright, chromium) and reading inside the game `engine/` folder with shell commands are refused.
- **Header band.** The chat header no longer leaves a big empty band when the chips are long; they scroll sideways.

## 1.3.2 — Lower cost and sources

- **One-hour cache (Anthropic).** Settings → Cost and context: keep the saved start of requests for an hour instead of five minutes. Costs a little more to save, pays off when you pause between steps.
- **Real model limits (Anthropic).** The app reads each model's true reply limit and image support from the model list instead of guessing from its name (refreshed when you pick a model, or on the first request).
- **Less repeated text.** File contents the assistant read and then changed are dropped from the conversation, so every later round is smaller (all providers).
- **Sources.** After an answer that used web pages, the pages it read are listed as tappable links.

## 1.3.1 — Steer, queue and game engines

- **Steer and queue while it works.** A bar above the message box shows **■ Stop**, **↪ Steer** and **⏱ Queue** whenever the assistant is busy. Type a message and tap *Steer* to have it read the message before its next step (nothing is lost), or *Queue* to run it when the job finishes. Queued messages show as chips you can remove; they wait after a stop or an error until you tap *Send queued*. Models without tool calling are stopped and re-asked instead.
- **Advisor for every model** (⋮ → Multi-model workflow and advisor). Pick a stronger model from any provider as an advisor for the model doing the work, in the spirit of Anthropic's advisor tool. *Ask when needed* adds an `advisor` tool the assistant can call; *Automatic checkpoints* also has the app consult it before the first change and before the assistant says it is done (if it finds problems, the assistant fixes them). A cap per request limits cost, and the advisor's usage is counted under its own model. Needs a tool-calling model for the work.
- **Game engines.** New project templates *2D game (Phaser)* and *3D game (Three.js)* with the engine bundled (MIT, works offline), plus a *Game developer* agent profile and a *Graphics pass* profile.
- **Fixes.** Custom rounds no longer freezes Settings; the Cascade model picker no longer stacks dialogs; providers with an output-tokens-per-minute limit (Groq) get an automatic reply cap.

## 1.3.0 — Web research and multi-model workflows

- **Web research.** The assistant can search the web and read pages (`web_search`, `web_fetch`). DuckDuckGo works without a key; add a Brave key in Settings for better results. New sites ask Allow once / Always / Deny, private-network addresses are blocked, and page text is labelled untrusted.
- **Multi-model workflows** (⋮ → Multi-model workflow). Pick a draft model (fast, cheap) and a strong model:
  - *Single* (default): one model does everything.
  - *Cascade*: the draft model builds, the strong model reviews read-only, and only steps in to finish the job if it finds gaps.
  - *Critique*: the strong model reviews read-only, then the draft model revises once.
  - Reviews cannot change files or run commands. If both slots hold the same model, builds run as Single.
- **Agent profiles** (⋮ → Agent profile). Presets modelled on the roles Claude Code uses: Explorer, Planner and Reviewer are read-only (enforced, and they need a model with tool support); Debugger, Test writer, Graphics pass and Careful builder add focused rules (Graphics pass restyles without touching logic and draws everything in code with a fixed palette). Every role must check before it claims. The active profile shows on the Agent chip.

## 1.2.1 — Anthropic workspace keys and cleaner key pasting
- **Anthropic keys that are not tied to a workspace.** Anthropic answers these with "this API key is not scoped to a workspace… must include the anthropic-workspace-id header". Settings now has an "Anthropic workspace ID" box (shown when Anthropic is the provider); the ID is sent with every request and with Test connection. A key created inside one workspace does not need it.
- **Test connection shows the server's reason** (for example the Anthropic message above) instead of just the number.
- **Pasted keys are cleaned.** Spaces, line breaks, quotes and labels such as "Bearer", "x-api-key:" or "export ANTHROPIC_API_KEY=" copied along with a key are removed.
- **Long replies no longer get thrown away.** The reply limit for Claude models is now 16,000 tokens (was 8,192). If a reply is still cut off, the unfinished tool call is dropped and the assistant is told to write smaller pieces (files under about 250 lines, split across several files). A big file in a "part" is split the same way.
- **Empty replies.** An empty reply after the assistant already changed files counts as finished; otherwise it is asked to continue twice before an error shows, with the provider's stop reason. A step that fails now says why.
- **Broken pages are caught at the end.** The final check lists files a page loads that do not exist (for example `game.js`), empty scripts or stylesheets, and buttons that call functions nothing defines.

## 1.2.0 — the assistant keeps to your folder and shows what it changed
- **Folder fence.** Say "only work in the X folder" and a bar under Assistant shows "Working only in: X". From then on the app refuses any write outside that folder (the assistant is told why and retries inside it), and the rule is added to the requirements list and the final check. Tap the bar to remove the limit or pick another folder. Reading other folders is still allowed.
- **A better requirements list.** It is built from the whole conversation, so a short follow-up like "I want a fully built game" keeps the game you named earlier. Research requests ("find resources…") and bare follow-ups ("continue") no longer get a list. The final check also reports anything you named that was swapped for something else.
- **What changed card.** After the assistant edits files you get a card listing each file as new, edited or deleted, with line counts, a Diff button, and Undo per file or Undo all.
- **Calmer failures.** A dropped connection is retried twice automatically (with a countdown) before an error appears; a timeout now says the provider did not answer in time instead of blaming your internet; old Terminal problems disappear when the file that caused them is rewritten.
- **History and Builds tabs.** The repository page now has Code, History and Builds. History lists the assistant's "go back" snapshots (with the files each touched and a Go back button) and, when the project is linked to GitHub, its recent commits. Builds lists the repository's GitHub Actions runs (including background agent runs) with passed, failed or running, and opens one on GitHub when tapped.
- **Build in parts.** When the approved requirements list has six or more points, the assistant first groups them into two to four parts (for example rules, screen, polish). You approve or edit the grouping, each part is then built in its own focused pass, and a final check also looks for places where the parts do not fit together. Setting: "Long lists: build in parts". "Build in one go" skips it.
- **Tests first for bug fixes.** For a request to fix something in a project that has real tests, the assistant is asked to add a failing test, show it failing, then fix the code until it passes.
- **/review.** Type `/review` (optionally `/review security`) and the assistant reads the changed files and replies with a summary, risks and possible bugs with file and line, missing tests and a next step, without changing anything.
- **Project skills.** Put short markdown notes in `.pocketide/skills/` (for example `add-a-page.md`, with an optional header giving a name, description and keywords). The assistant reads only the ones that match each request. `/skills` lists them.
- **"Read N files" line.** Under a reply, a small line shows which files the assistant read, so you can see what it based its answer on.
- **Outside tool servers (MCP).** ⋮ Menu → AI → "Outside tool servers (MCP)" lets you add a remote server (name, https address, optional token) and test it. Its tools then appear to the assistant as `mcp__name__tool`. Before each one runs you get an Allow once / Always / Deny card showing the server and the arguments; what a server returns is capped and handed back as data, never as instructions. Plain http is only accepted for localhost and your own network.
- **Budgets per provider.** Usage & cost → Prices & budget → "Set a budget for a provider": a dollar limit for the last 30 days, a token limit per day, or both (for example `25 200k`). Free providers usually run out of tokens, paid ones of money, so each provider has its own. A line under the reply warns at 80% and 100%.
- **Shell commands stay in the folder too.** With "Working only in: X" set, commands that would write outside X (redirects, rm, mv, cp, touch, mkdir, sed -i, `cd ..`, absolute paths) are refused with the reason.
- **Model advice.** A one-time note when the chosen model tends to ignore tools (for example Gemma) or is too small for a big request.
- **First-run card on Home.** A short checklist (connect an AI, tap a sample, try it) with Snake, To-do and Personal page samples; it disappears after the first answer.
- **Build notifications.** While the Builds tab is open and a build is running, it refreshes every 30 seconds and tells you (notification and message) when a build finishes.
- **Background agent v2.** Send a numbered or bulleted list to `/cloud` and each item becomes its own issue, running at the same time with its own pull request (up to 5). Issues are tagged review, tests, fix or feature and the agent is told how to approach each kind.
- **Faster suggestions.** Settings → Editor and layout: an optional smaller model used only for the inline suggestions while typing.
- **Side-by-side on wide screens.** On a tablet or a phone turned sideways with room (720dp wide or more) the code and the assistant sit next to each other.
- **Voice input.** A 🎤 button next to the message box uses the phone's own speech recognizer.
- **/share.** Makes a secret GitHub gist with a read-only copy of the project's text files and gives you the link (needs a token with the gist permission).
- **Release benchmark.** `BENCHMARKS.md` lists ten requests to run on a phone before each release; `BenchmarkTest` checks the offline half on every build. It already caught "don't touch anything outside the X folder" not being recognized.
- **Take a suggestion one word at a time.** The grey suggestion bar has a "Word" button next to Accept.
- **MIT edition caught up.** It now has the same plan reminders (the assistant is asked to continue when its checklist is unfinished), repeated-call guard and repeated-text guard as the GPL edition.
- **Phone test list.** `PHONE-TEST.md` is a checklist for trying everything in this version on a phone.

## 1.1.2 — one repository page, a Home page and tidier menus
- Tidier navigation. The app now opens on a Home page (your repositories, the one you were last in on top with Open code and Ask AI) instead of the code screen; tap the project name any time to come back to it. The version number is shown at the bottom of the Home page and at the bottom of the ⋮ menu. The top bar is just the project name, ▶ Run, ⚙ Settings and ⋮ Menu. The ⋮ menu is grouped into Project, Run and check, AI and App, and Settings has section headings (AI provider, Assistant, How long it may work, Cost and context, Editor and layout).
- The GitHub-style repository page is now the default and the only file browser: long-press a file or folder to rename, move, duplicate or delete it, use "＋ New" for a new file, folder or files from your phone, and the ☰ button takes you to it. Caches and scratch folders (`__pycache__`, `node_modules`, `tmp`, `dist`, `build`, `venv`, `target`) are hidden with a "Show" link. The classic editor and file drawer are still available by turning the setting off.

## 1.1.1 — the page check no longer hijacks unrelated requests
- Big jobs now start with a requirements list: the assistant writes exactly what it will build, point by point, and you approve or edit the list before anything is built (setting: "Big requests: list the requirements first"). When the work is done it is checked against your request and the list, and anything left out or built differently is named ("Left out" / "Changed").
- The page check (and the fix round it can start) now runs only when HTML, JavaScript or CSS files were changed. Creating a note or `AGENTS.md` can no longer send the assistant off to "fix" an old page in the project.
- "Found problems" now lists what was found.
- A canvas that stays one flat color is no longer reported when the page has buttons (a game may only draw after Start).
- The "wants to run" card shrinks to a "Ran" / "Skipped" line once you answer it.
- Replies no longer start with empty lines.
- Rounds per request (Settings): besides 12, 24 and 48 you can type your own number (6 to 500) or choose **Autonomous**, which has no round limit and keeps going until the job is done. It still stops after 90 minutes, after 10 rounds in a row that change nothing, or at an absolute ceiling of 400 rounds, and Stop always works. In autonomous mode the assistant is told to plan, test as it goes and not ask whether to continue (GPL edition: it also keeps reminding itself of unfinished checklist steps). Long runs keep the conversation small by shortening old tool output, and the status line shows "Step N". Long runs use a lot of your provider's quota.
- Projects no longer share anything by accident. The preview serves each project from its own address, so one project's saved data (localStorage, IndexedDB, cookies) is invisible to the others; games and apps that saved scores or settings start fresh once after this update.

## 1.1.0 — background agent, page check, repository page
- Background agent: type `/cloud <task>` and GitHub does the work while your phone is off. PocketIDE makes an issue with the `pocketide` label; a workflow runs a model on GitHub's machines, pushes a branch `pocketide/issue-N` and opens a pull request. It never pushes to your main branch, never edits `.github`, runs its commands without your keys, and only acts on issues from you or your collaborators. The first `/cloud` shows a six-step setup (add files, push, secret, two variables, one GitHub setting, token access). Needs an OpenAI-style provider.
- Page check: after the assistant changes a page, the check now also notices a page that loads but shows nothing, "NaN"/"undefined" on screen, a canvas that never gets drawn, and errors raised by tapping a few buttons. It skips buttons named Delete, Clear, Reset, Send, Buy and similar, and never posts to a server. Switch off in Settings.
- Project instructions: the assistant also reads `AGENTS.md` and `.github/copilot-instructions.md` (as well as POCKETIDE.md), within a size limit, and they cannot override the safety rules.
- While the assistant works you now see what it is doing and for how long ("Reading app.js...  12s", "Writing the reply...  1.2k characters") instead of "…".
- When a request fails there are Retry, Try another model and Try another provider buttons, so a network blip no longer means retyping.
- New layout (Settings, off by default): the Code tab opens on a GitHub-style repository page with the project's name, quick actions, a path you can tap, the files in this folder (folders first, with how long ago they changed) and the README shown underneath. Tap a file to edit it; tap Code again or Back to return. The project list becomes "Your repositories" with a description and last-updated time.

## 1.0.3 — fewer needless files
- The assistant no longer writes README, notes, changelog or chat-log files you did not ask for. "Save this chat" opens the Export or summarize menu instead of asking the model.

## 1.0.2 — made for a phone with no keyboard
- The assistant is now told it is running on a phone with only a touchscreen, so anything it builds with controls uses on-screen buttons, taps or swipes, and web pages get a mobile viewport tag.
- The page check now reports a page that can only be steered with keyboard keys, and the assistant is asked to add touch controls.
- A missing favicon.ico is no longer reported as an error, which used to trigger a needless fix round.
- Asking "are you still working?" when nothing is running is answered by the app itself, without a request to the model.
- When you asked for an APK earlier and the assistant has built the web app, it now points you to asking for the APK.

## 1.0.1 — key guides for every provider
- Settings: the help button beside the key field now opens a step-by-step guide to getting a key for every provider that needs one (12 in all: Groq, Gemini, Cerebras, OpenRouter, Mistral, NVIDIA NIM, Hugging Face, SambaNova, Ollama Cloud, Cloudflare Workers AI, OpenAI, Anthropic). OpenAI and Anthropic are paid; the rest have a free tier.

## 1.0.0 — emulator testing
- Ask for an APK in the chat and, for a Gradle Android project, there is now a "Try it on an emulator" chip: it writes a workflow that runs the project's instrumented tests on an Android emulator on GitHub's machines and keeps a screenshot, the logcat and the test reports. A phone cannot run an emulator, so this is how a build gets tried on a real Android system. It runs only when you start it from the Actions tab.
