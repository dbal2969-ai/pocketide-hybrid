# Termux terminal libraries: licence confirmation

`terminal-emulator/` and `terminal-view/` are vendored from termux/termux-app v0.118.3
(commit 5b657c6adf4304e5198951ce815fe0205dcac29c).

termux-app's `LICENSE.md` says the repository is GPLv3 only, with an exception noting that these two libraries use code from
Jack Palevich's Terminal Emulator for Android, released under the Apache License 2.0. The library sources carry no licence headers
of their own, so the owner of PocketIDE asked the Termux maintainers directly.

**Reply (November 2026):** the Termux maintainers answered that these libraries are not GPL and may be used in a non-GPL app.

Link to the reply: (to be added by the owner)

On that basis the MIT edition of PocketIDE includes the two libraries from 1.5.128, under the Apache License 2.0 terms of the code
they come from, with credit to Termux and to Jack Palevich (Terminal Emulator for Android).
