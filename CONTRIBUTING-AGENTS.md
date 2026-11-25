# Working on PocketIDE as an agent

For any AI coding agent changing this repository: PocketIDE's own `/cloud` agent, GitHub Copilot, Claude, or another.
The roadmap and the reasons behind these rules are in `ROADMAP-HANDOFF.md`. This file is the short version you must follow.

## The two editions

- **GPL** (`dbal2969-ai/Test-Coder2.3`) and **MIT** (`dbal2969-ai/Test-Coder2.2`). From 1.5.128 `app/src` is **identical** in
  both. They differ only in: `app/build.gradle.kts` (app ID; GPL packs PRoot from `runtime/`), `settings.gradle.kts`, the licence
  and notice files, `.github/workflows/build-apk.yml`, and the GPL-only `runtime/` and `linux-addon/` (PRoot, and the Linux add-on
  the MIT edition downloads). MIT must never get PRoot, talloc or other GPL or AGPL code into its repository or APK.
- Make a change in one edition and say in the pull request that it needs porting. The owner, or an agent asked to, ports it:
  copy the changed `app/src` files across (they are identical), apply the diff to the few files that differ, then build and test both.

## Build and test

```
./gradlew --no-daemon -q -Dorg.gradle.java.installations.paths=/usr/lib/jvm/java-17-openjdk-amd64 assembleDebug testDebugUnitTest --rerun
```

On GitHub every pull request does this (`pr-check.yml`). Run it yourself first when you can: the cloud agent's commands stop
after 2 minutes, which is too short for this build, so it relies on the pull request check.

## Rules

0. **Change record (#BuildHybridAI).** Every commit that changes `app/`, `bench/`, the build files or the workflows adds an entry
   to `HYBRID-CHANGELOG.md` in the same commit: why, what changed, files, tests and the evidence (benchmark run or report).
   CI (`changelog.yml`) fails a push or pull request that skips it.
0b. **Benchmark runs are analysed in full.** Every results zip goes through `bench/hybrid/analyze.py`: passes as well as failures,
   the finished files (syntax, lost names, the page in a browser, the play tests replayed) and every step of the chat log and
   nano.jsonl. The app's own verdict is never taken at its word. A pass with problems is treated like a failure.

1. Work on a branch and open a pull request. **Never push to `main`, never merge, never run `release.sh`.** The owner reads every
   pull request and merges it.
2. One behaviour per pull request. Keep it small. Change nothing the task did not ask for.
3. No behaviour change without a unit test or a benchmark task that covers it. A change to how the agent loop behaves
   (`NativeAgent.kt`, `ChatPane.kt`, `core/AgentGuard.kt`, prompts) also needs an A/B benchmark before it is merged:
   `.github/workflows/benchmark.yml` with `refs=main,<your branch>`. Small runs only: one model, the tasks the change is about,
   `repeat=1` to look. Record the result in `BENCHMARKS.md`, including "tried and not kept".
4. **Never delete or weaken a test or a benchmark check to make something pass.** If a test is wrong, say why in the pull request
   and let the owner decide. `pr-guard.yml` fails a pull request that lowers the number of tests. It only counts `@Test` lines, so
   a test emptied of its checks still counts: the owner's reading of the diff is the only check on that.
5. Protected files: an agent's pull request may not change `.github/`, `release.sh`, or `core/AgentGuard.kt`,
   `core/CommandRules.kt`, `core/DeleteRules.kt`, `core/ProjectGuard.kt`, `core/Sandbox.kt`, `core/CloudAgent.kt`,
   `core/CloudAgentScript.kt` (they hold the agent's own limits). `pr-guard.yml` fails it, renames included. Ask the owner instead.
   The guard knows an agent's pull request by its author (not the owner) or its branch (`pocketide/*`, `copilot/*`). An agent
   working with the owner's own token and another branch name is not caught: it must follow this rule unprompted.
6. Never read, print or commit secrets or keys.
7. Write like the code around you: comments in plain words that say why, with the version number of the change. Add a section
   to `README.md`, and a checkbox to `PHONE-TEST.md` for anything visible on the phone.
8. No Jagex game content is extracted or recreated.

## When a check fails

Read the output and fix the cause. One more attempt with what you learned. After a second failure, stop: write in the pull
request what you tried and what you think the cause is, mark it as a draft, and ask the owner.

Ask the owner at once when the task can be read two ways, needs a protected file, a secret, a GitHub setting, or more Actions
minutes than rule 3 allows.

## Releases (the owner)

After merging, the owner ports to the other edition if needed, builds and tests both, and runs `release.sh "<message>"` on their
machine. It copies both working copies over both repos and pushes to `main`; `build-apk.yml` then builds the APK, which the app's
self-fix screen can download and install with a tap. The owner's account may bypass branch protection; agents may not.

`release.sh` refuses to run when `main` on GitHub has commits the working copy lacks, because copying the working copy over the
repo would delete them. After anything is committed on GitHub directly (the app's `/cloud` setup, a merged pull request), bring
those files into the working copies first.

A pull request opened by the `/cloud` agent starts no checks (it uses the workflow's own token, and GitHub starts no workflow for
that token's events). Close and reopen it once to run `build` and `guard`.
