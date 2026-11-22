# Pause-and-step debugger: spike and design (1.5.125, roadmap phase 9)

## The question

Android's WebView has no supported API for an app to pause a page's JavaScript (no DevTools protocol from inside the app;
`setWebContentsDebuggingEnabled` only lets a desktop Chrome attach over USB). The roadmap named two candidates: rewrite the copy of
the code the Preview loads so it can wait for a "continue", or debug on the remote machine with Node's inspector.

## Verdict: the rewrite approach is viable, using a blocking `@JavascriptInterface` call

A plain rewrite cannot make JavaScript wait (`await` would turn every function async and change what the page does). But a
method the app hands to the page with `addJavascriptInterface` is a synchronous call that the page waits on:

- Android documentation (Access native APIs with JavaScript bridge, `addJavascriptInterface` section): "**Synchronous**: The
  JavaScript execution environment blocks until the method in your Android code returns." and "The system calls methods on a
  background thread". https://developer.android.com/develop/ui/views/layout/webapps/native-api-access-jsbridge
- Chromium's design note for the WebView Java Bridge: "methods of Java objects are invoked on a private, background thread of
  WebView"; "Calls to interface methods are synchronous—JavaScript VM stops and waits for a result to be returned from the
  invoked method"; and "the browser UI thread must not be involved in the processing of requests from the renderer" (the bridge
  must keep working while the app's main thread is itself blocked, crbug.com/438255).
  https://chromium.googlesource.com/chromium/src/+/HEAD/android_webview/docs/java-bridge.md

So if the Kotlin method waits (on a lock, until the person taps Continue or Step), the page's JavaScript stops on that exact
statement with all its local state intact, and the app's own UI thread stays free to draw the panel and take taps. That is the
pause. The page cannot tell this apart from a slow call.

### Risks and how the design handles them

| Risk | Handling |
|---|---|
| ANR ("app not responding") | ANR watches the app's **UI thread**; the wait is on WebView's bridge thread. `StepDebug.Gate.pause` also refuses to wait if it is ever called on the UI thread (returns continue at once). |
| Page left frozen forever | The wait times out after 5 minutes (`PAUSE_TIMEOUT_MS`) and the page goes on; the running bar says so. Reload, hiding the Preview, closing the preview (`destroy`, `onDetachedFromWindow`) and Stop debugging all close the gate, which lets the page go at once with "stop" (its checks then switch themselves off). |
| Tearing down a WebView whose renderer waits in a synchronous call | The gate is always closed **before** `loadUrl`/`destroy`, so the bridge call has already returned. |
| Timers, animation and rendering freeze while paused | Expected: that is what pausing a debugger does (the renderer's main thread is the one waiting). `requestAnimationFrame` games resume where they were; time-based code sees one long frame. |
| One bridge thread per app | A paused page also holds any other `@JavascriptInterface` call in the app. PocketIDE uses no other bridge object today (checked: no `@JavascriptInterface` anywhere before this change). The look_at_preview and check browsers never get the object. |
| Renderer "unresponsive" handling | `WebViewRenderProcessClient.onRenderProcessUnresponsive` (API 29+) may be called while paused; by default WebView takes no action, and PocketIDE does not set such a client. **Unverified on a phone.** |
| Any frame can call the object | `addJavascriptInterface` exposes it to every frame. The object has one method, which can only pause the page that calls it; it is added only during a debugging run and removed at the end. |
| Taps that arrive late or twice | Each pause has a number; an answer for an older pause, or a second answer, is ignored (`Gate.answer`). |
| Page input while paused | Touches are queued by the browser and delivered after resuming, as in desktop Chrome. Not tested on a phone. |

A fallback with the same effect, if a WebView version ever misbehaves: `prompt()` is also synchronous for the page and is answered
from `WebChromeClient.onJsPrompt` with a `JsPromptResult` the app can confirm later, without any thread waiting. Not used now.

### The alternative (described only): Node's inspector on the remote machine

`core/RemoteDev.kt` runs code on a remote machine. Node's `--inspect` speaks the Chrome DevTools Protocol over a WebSocket
(Debugger.setBreakpointByUrl, Debugger.paused with real scopes, stepInto/stepOver/stepOut, evaluateOnCallFrame). It would give
true scopes and step in/over/out, but only for code that runs under Node (servers, tests), not the page shown in the Preview, and
it needs the remote machine, a tunnel for the inspector port, and a protocol client in the app. Worth doing later for backend
code; it does not answer "pause my page".

## Design as built

- `core/StepDebug.kt` (pure Kotlin, tested on the JVM):
  - `parse`: the same lines as log points (`file.js:LINE values`); every line is a breakpoint, the values are watched.
  - `analyze`: a small tokenizer (strings, templates with `${}`, comments, regex literals) and a bracket stack that tells blocks
    from object literals, class bodies, brackets and template parts. A line gets a check only where a statement certainly starts:
    inside a block, after `;` `{` `}`, or where JavaScript itself ends a statement (a line starting with a name after a value),
    never in front of a line that carries on the one above, a `case` label, an `if` body without braces, or `'use strict'`.
    It also collects the names declared before each line (let/const/var, simple destructuring, function and arrow parameters,
    catch names), innermost function first, at most 10.
  - `instrument` / `instrumentHtml`: in front of each such line of a file that has a breakpoint (inline scripts of a page are read
    one by one), `typeof __pkD==='object'&&__pkD.s&&__pkD.h("file",N,0,()=>{…})`; on breakpoint lines the `__pkD.s&&` is left out.
    When not stepping the check is one property read. The arrow function reads each watched value and each name in its own
    try/catch, so a name that is not defined there is simply skipped. Breakpoints move to the first statement line within 6
    lines, like log points. Line numbers never change. Files over 400 000 characters get only their breakpoint lines.
  - `RUNTIME`: put first in the page's head. `__pkD.h` serialises the values safely (depth 3, 20 items per level, 120 characters
    per string, 300 values per pause, `[circular]` for loops, `<tag#id>` for elements, Map/Set/Date) and calls
    `window.PocketDebug.pause(file, line, json)`. The answer `step` turns stepping on (the next checked line anywhere stops),
    `continue` turns it off, `stop` switches every check off for the rest of the page's life.
  - `Gate`: the wait itself, with numbered pauses, timeout, close and the UI-thread guard.
- `PreviewPane.kt`: Debug dialog → **Pause** starts a run on the visible preview (project files only), adds the bridge object,
  and reloads; `serve` rewrites files with breakpoints and adds the runtime to pages. The panel above the page shows the place,
  the code around it, the values, and Continue / Step / Stop debugging.

## How it was checked (StepDebugTest)

- Unit tests for where checks go (objects, classes, switch, templates, regex with braces, ASI) and which names are visible.
- Under node, with a stand-in `PocketDebug` that records each call and answers at once: a 98-line sample using classes with
  private fields and static blocks, getters, generators, async/await, labels, destructuring, template literals, regex, switch,
  do/while and ASI prints exactly the same before and after rewriting, both when never paused and when stepping through every
  statement; the order of lines for a Step sequence through a loop and a function call is exactly as expected; Continue stops
  only at breakpoints; Stop ends all pauses; a value that refers to itself arrives as `[circular]`.
- Every `.js` file in the app's assets (55: the game kit, its templates, acorn; phaser and three.js are over the size limit, so
  they are only checked to still parse unchanged) still parses with `node --check` after a check is put in front of every
  statement line.

## Not verified

A real phone: the pause itself (the bridge thread waiting), the panel, behaviour when the renderer is reported unresponsive, and
input while paused. These are the PHONE-TEST.md checkboxes under 1.5.125.

## Limits

- Step stops at the next checked line anywhere (only files with a breakpoint are checked); there is no step over / step out.
- Names are found by a light scan: `var` inside a nested block is seen only in that block; names after the first in
  `let a = 1, b = 2` are not listed (they can be watched by name).
- Reading values calls getters on objects shown.
- Code from a local server, workers and code made with `eval`/`new Function` is not checked.
