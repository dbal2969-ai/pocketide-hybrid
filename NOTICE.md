# PocketIDE (Kotlin) license and notices

PocketIDE
Copyright 2026 Daniel Joseph Ball (dbal2969-ai)

PocketIDE is licensed under the Apache License, Version 2.0 (see `LICENSE`) from version 1.5.222. Versions up to 1.5.221 were
published under the MIT License, and those versions remain available under it. Where the history below says the author's own code
was licensed "under MIT", that code is the author's and is now licensed under Apache-2.0 with the rest of the app.

PocketIDE™ and the PocketIDE icon are trademarks of Daniel Joseph Ball. The Apache License covers the code, not the name or icon
(see `TRADEMARK.md`).

## Code ported from MobileCoder

The logic in `app/src/main/java/dev/pocketide/app/core/` is Kotlin written for PocketIDE and based on the author's own
MobileCoder (Test-Coder) Python code: `editblocks.py` (edit blocks), `context.py` (context ranking) and `prompts.py` (prompt wording).
The author wrote that code, so the author may license the ports under MIT. MobileCoder itself stays under its own terms (GPL-3.0-or-later).

Version 0.2 adds ports of the author's own MobileCoder code, again rewritten for PocketIDE and licensed under MIT:

- `BoundedProcessRunner.kt`: from MobileCoder's `BoundedProcessRunner.kt` (time and output limits, process-tree kill), rewritten without coroutines.
- `core/AgentActions.kt` and the investigation loop in `ChatPane.kt`: from MobileCoder's agent loop (`assistant.py`) and `SYSTEM_AGENT_LOOP` in `prompts.py`.
- `core/GitCore.kt`, `core/GitSync.kt`, `GitPanel.kt`: repository, branch and token checks and the pull-before-push rule from MobileCoder's
  `GitHubSourceSnapshot.kt`, `GitHubConnection.kt` and `GitHubRemoteGit.kt`. MobileCoder used JGit (EDL/BSD); PocketIDE talks to the GitHub REST API directly and includes no JGit code.
- The change list and diff view follow the idea of MobileCoder's `WorkspaceGitStatus.kt` / `WorkspaceGitDiff.kt`, written from scratch.

Version 0.3 adds, again written for PocketIDE under MIT:

- `ModelsScreen.kt`, `core/OllamaApi.kt`, `core/ModelCatalog.kt`: the one-tap model download flow follows the idea of MobileCoder's `ModelDownloadQueue.kt` / `ModelDownloads.kt` (progress, cancel, fit for the device), written from scratch against Ollama's HTTP API.
- `HelpScreen.kt`, `core/HelpContent.kt`: the searchable FAQ follows the idea of MobileCoder's `TroubleshootingFaq.kt` (keyword-matched cards), with new content.
- `Welcome.kt`, `FullScreen.kt`: new.

Not taken from MobileCoder: llama.cpp, PRoot and wireless-ADB components, its JGit and other library dependencies, the file adapted from
Code on the Go (GPLv3), and its bundled xterm, Lucide and IBM Plex files. Anything from those would carry its own license terms.

## The Linux environment, crash reports and language servers (1.5.128)

From 1.5.128 this edition has the same app code as the GPL edition. The parts that were in the GPL edition only were written by
the same author and are licensed under MIT here: `LinuxRuntime.kt`, `LinuxRunHost.kt`, `LinuxService.kt`, `LinuxServerHooks.kt`,
`LinuxScreen.kt`, `ToolsScreen.kt`, `core/ToolCatalog.kt`, `core/Rootfs.kt` (tar extraction rules adapted from the author's own
MobileCoder runtime), `core/Lsp.kt`, `LspCheck.kt`, `CrashReport.kt`, `LinuxAddon.kt`.

This edition contains **no PRoot and no talloc**. Linux runs through PRoot, which comes from the separate "PocketIDE Linux add-on"
(`dev.pocketide.linux`), a GPL app with no code that holds PRoot (GPL-2.0-or-later), its loader and talloc (LGPL-3.0-or-later).
It is built from `linux-addon/` in the GPL repository (Test-Coder2.3), downloaded only when the person taps Get the Linux add-on,
installed by Android like any app, and run as a separate program. The Alpine Linux system and its packages are downloaded from
Alpine when the person installs them, under each package's own licence; nothing of them is in this repository or the APK.

## Third-party code

- Termux terminal libraries (`terminal-emulator/`, `terminal-view/`), from termux/termux-app v0.118.3, commit
  5b657c6adf4304e5198951ce815fe0205dcac29c, compiled into the app (Java and the PTY helper `libtermux.so`) for the full terminal
  (1.5.128). They build on Jack Palevich's Terminal Emulator for Android, Apache License 2.0
  (text: `LICENSES/Apache-2.0.txt`). The Termux maintainers confirmed these libraries are not GPL and may be used in a
  non-GPL app: see `third_party/termux/PERMISSION.md` and their notice in `third_party/termux/LICENSE.md`. Changes: three
  `androidx.annotation` annotations removed, library manifests reduced to `<manifest />`, upstream unit tests not included, built
  for arm64-v8a, armeabi-v7a and x86_64.

- Robolectric (MIT, Copyright (c) 2010 Xtreme Labs and Pivotal Labs; Copyright (c) Google), test only: runs Android classes on the JVM for the agent harness. Not in the app.

- JSch 0.2.20 (mwiede/jsch, BSD-style; includes JZlib, BSD-style, and jBCrypt, ISC), bundled in the app since 1.5.112 for the remote machine over SSH. License texts: `third_party/jsch/`.

Apart from JSch, the app bundles no third-party libraries. It uses only the Android framework. Tests use JUnit 4 (EPL-1.0), which is not shipped in the APK.
Build tools (Gradle, Android Gradle Plugin, Kotlin) are used at build time only.
The shipped `app/debug.keystore` is a throwaway debug key so that updates install over each other. Do not publish an app signed with it.

## Studied, not copied

- OpenHands (MIT, Copyright (c) All Hands AI): the loop rules of its StuckDetector (same action and result, repeated errors, alternation) were read for the loop detector (1.5.78). No code was copied.
- Cline (Apache-2.0, Copyright (c) Cline Bot Inc.): its move of rarely used tool documentation out of the prompt and into an on-demand tool was read for the on-demand tool list (1.5.79). No code was copied.
- SWE-agent (MIT, Copyright (c) 2024 John Yang, Carlos E. Jimenez, Alexander Wettig, Shunyu Yao, Karthik Narasimhan, Ofir Press; arXiv 2405.15793): its agent-computer-interface study was read for the capped search with a "narrow it" reply, the window-sized file read and the syntax guard on edits (1.5.76). No code was copied.
- microsoft/vscode-copilot-chat (MIT, Copyright (c) Microsoft Corporation): its agent prompts and tool descriptions were read for working habits (read large ranges, batch independent calls, group edits, several edits in one call). The wording in PocketIDE is our own; the multi_edit tool follows the idea of its multi_replace_string_in_file tool.
- Game cache format (container, index, sprite and map layouts): written from the formats documented by the OpenRS2 and RuneLite communities; no code from either was copied.
- 3D model storage layout: studied in the RuneLite cache library (BSD-2-Clause, Copyright (c) 2016-2017 Adam); the Kotlin decoder is an independent implementation.
