# Review of the roadmap work through 1.5.125 (2026-11-11)

A review of what `ROADMAP-HANDOFF.md` says is done, against the code, the tests, the benchmark runs and GitHub.
No code was changed. Each finding is marked **Verified** (checked in the code, a run or the GitHub API) or **Concern** (likely,
not proven). Line numbers are for the GPL edition at 1.5.125.

Checked and fine: both `Build Android APK` runs for 1.5.125 passed (Test-Coder2.3 run 43020035168, Test-Coder2.2 run
43020052845). Every phase marked done has unit tests in the tree. The two editions differ only in files that differed before
(Linux runtime, terminal, tools, licences), plus the two items in findings 6 and 7.

## A. Blocks the milestone or the next release

**1. PocketIDE's own pull requests will get no check and no guard.** Verified.
`core/CloudAgent.kt:54` gives the agent `github.token`, and `core/CloudAgentScript.kt:244` opens the pull request with it.
GitHub starts no workflow for an event made with that token, so `pr-check.yml` and `pr-guard.yml` never run on a `/cloud`
pull request. Steps 3 and 4 of the roadmap's section 4 do not happen for the one executor they were built for, and with
required status checks the pull request would wait forever.
Fix now, no code: the owner closes and reopens the pull request (both workflows react to `reopened`). Write that into
section 4. Fix later: open the pull request with a fine-grained token kept as a repository secret. Both files are protected,
so that is an external executor's change.

**2. The guard has never passed or correctly failed on GitHub.** Verified.
Its only two runs (43005682759, 43005853431) failed on the unauthenticated fetch. The fix reached `main` with 1.5.125 and no
pull request has been opened since. Phase 2's exit test (a broken test goes red, an agent edit of `core/AgentGuard.kt` goes
red, a clean one goes green) was never run. The status table says "Done".
Fix: open the three small demonstration pull requests, one from a `pocketide/` branch. Then change the table.

**3. A renamed protected file passes the guard.** Verified by reading; not yet shown on GitHub.
`pr-guard.yml`, step "Protected files", reads only `.filename` from the pull request's file list. For a rename that is the
new name, so moving `core/AgentGuard.kt` to another name, with any edit, is not seen.
Fix: `--jq '.[] | .filename, (.previous_filename // empty)'`.

**4. The first release after `/cloud` is set up will stop.** Verified in `release.sh`.
Setup commits `pocketide-agent.yml` and the agent script to `main` on GitHub. `release.sh` refuses to run when `main` has
commits the machine has not seen (lines 8–12), and it would delete those files if it did run, because it copies the working
copy over the repo. Fix: after setup, pull both files into both working copies. Add this to the "Releases" part of
`CONTRIBUTING-AGENTS.md`.

**5. Branch protection may not be available.** Concern.
Both repos are private and `main` is unprotected on both (API: `protected=false`). On a free GitHub plan, protection is not
offered for private repos. If so, the roadmap's "turn on branch protection" cannot be done and the rules stay a convention.
The owner should check the plan first.

## B. Says more than the evidence shows

**6. A leftover patch file was released.** Verified. `BENCHMARKS.md.orig` is in the GPL working copy and in Test-Coder2.3.
Delete it.

**7. MIT has a test file GPL lacks.** Verified. `app/src/test/java/dev/pocketide/app/SyntaxCheckTest.kt` (4 tests) entered
Test-Coder2.2 with 1.5.124 and is not in GPL, though `core/SyntaxCheck.kt` is in both. Copy it to GPL and run it there. If
it fails, say why before deciding which edition is right.

**8. Status table: "Done" is used for work whose own pass rule is not met.** Verified against section 5.

| Phase | Table says | Evidence | Should say |
|---|---|---|---|
| 1a replay | Done | `AgentLoopTest.readingAnUnchangedFileInCirclesIsStopped` | Verified |
| 1b re-reads | Done, A/B kept | Replay test. In 12 live runs the stop never fired | Built. No live benefit shown, no harm shown |
| 1c, 1e | Done | Unit tests. See finding 9 | Built, unit-tested, not measured live |
| 2 check and guard | Done | Check ran green once. Guard: finding 2 | Check verified. Guard unverified |
| 3 JavaScript polyglot | Done | 49 exercises checked in the sandbox | Built. Pass rule (Kimi baseline) not met |
| 4 escalation | Done | 2 unit tests of the pure part. Trial never fired | Built. Pass rule not met. See finding 10 |
| 5 chat home | Done | Unit tests. Suite 12/12 on Kimi | Built. Exit asks for Haiku too, and the phone boxes |
| 6 `/bench` | Done | Fake-server tests only | Built. Never run against GitHub |
| 7 error reports | Done | Fake-server tests only | Built. Never run against Cloudflare |
| 8 next-edit | Done | 8 recorded pairs offline | Built. Pass rule (match rate, 2 seconds) not met |
| 9 debugger | Built | Node tests, `DEBUGGER.md` | Built. Never run in a WebView on a device |

Nothing in 1.5.117–1.5.125 has been run on a phone: `PHONE-TEST.md` has 95 open boxes and 2 ticked.
Fix: use three words in the table: built, measured, checked on a phone.

**9. The A/B evidence is thinner than the roadmap's rule.** Verified from the run artifacts.
- The reading given to the owner holds. In run 43005645160 all ten runs that did not hit the time limit ended "finished",
  no stall stop fired on either side, and the two lost tasks failed their checks as written (`game/inv.js` changed;
  sectioned steps read as empty).
- "main" in that run was 1.5.124, which already had the re-read stop. `BENCHMARKS.md:927-928` lists it among 1.5.125's
  changes that did not appear. Reword.
- The deciding run repeated only the two lost tasks. The other four were never repeated. One of them, rp-loot, took 887
  seconds on the candidate against 406 on main, 13 seconds short of the cut-off.
- Phase 1 asks for each change on its own, suites `hard` and `realproject`, two models, three repeats. What ran: all
  changes together, six tasks, one model.
- The run's summary says "at 3 runs per task" under a table of single runs. That is the summary script's fixed wording.

Rule 3 of `CONTRIBUTING-AGENTS.md` is met as written (an A/B exists and is recorded). The roadmap's stricter rule is not.
The result supports "no harm seen", not "no drop". Fix: one run of the six tasks at `repeat=3` on `main` against the
1.5.123 commit, and say in `BENCHMARKS.md` that the release went out on the smaller run. It costs about two hours of
Actions time, so the owner decides.

**10. Automatic escalation shipped unmeasured.** Verified. Phase 4 says to drop the automatic setting if the score does not
rise. It was never measured (`BENCHMARKS.md`, escalation trial: never fired, and the roadmap does not count it as evidence).
The setting is off by default (`AppSettings.kt:374-376`). Fix: mark the checkbox "not yet measured", or hide it until a
trial on tasks that end as stalls shows a gain.

**11. The roadmap's own text is out of date for a cold reader.** Verified.
- Line 1 "written at 1.5.123", lines 39–40 version numbers.
- Lines 46 and 241: test counts 777 and 763. They are 827 and 813. Line 241 tells an executor to stop when the counts
  differ, so a new executor stops at once.
- Line 48: the suite list lacks `polyglot-js`, `polyglot-all` and `chat`.
- Section 2 still lists gaps 1–4, 6, 8 and 9 as missing. Section 7's tasks 2 and 3 are done. Line 141 names the boxes for
  1.5.117–1.5.123.

**12. More leftover branches than reported.** Verified. Test-Coder2.3 has 29 branches besides `main`, among them
`candidate`, `candidate-1.5.124` and `candidate-1.5.125`. None is needed: the releases are on `main`. The owner can delete
them on GitHub. This session's proxy refuses deletion.

## C. Smaller points

**13. Escalation is once per request in the normal path.** Verified by reading `ChatPane.kt:3959-3990`. `target()` never
falls back to the main model (`Escalate.kt:17-18`, `AppSettings.pickedStrongSlot()` reads only `workflow.strong`). Empty
notes are handled (`Escalate.kt:26`).
Concern: `escalating` is cleared only when the carried-on run reaches `nativeDone`. If that run ends another way, the flag
stays set and the next request's stall gets no offer. No test covers the flag. An empty `why` would print "finished: ." but
`SecondTry.reason` returns null in that case, so it cannot happen today.
Fix: clear the flag when a new request starts, and test it.

**14. The guard's token.** Verified by reading. It is the job's read-only token, sent as a header and never echoed, and
the step does not trace commands. Low risk. Add `echo "::add-mask::$auth"` before the fetch to be safe.

**15. Who counts as an agent.** Verified. A pull request opened with the owner's token from a branch not named
`pocketide/*` or `copilot/*` is not an agent's, so the protected-file check is skipped. That is how this session's
candidate branches were treated. `CONTRIBUTING-AGENTS.md` rule 5 says the rule covers Claude too. Either name such
branches `agent/*` and add that to the guard, or say in rule 5 that an executor using the owner's token is trusted.

**16. The test-count check counts `@Test` text.** Verified. A test emptied of its assertions still counts. The owner's
reading of the diff is the only check on a weakened test. State that in rule 4.

**17. Phase 1d.** Verified. The status table (line 23) and section 7 say it is left for PocketIDE's `/cloud` run. `/cloud`
is not set up: no `pocketide-agent.yml` in either repo. Finding 1 applies to that run.

## D. What the roadmap should add

- **Owner-only steps in one list:** phone checks in `PHONE-TEST.md`; check the GitHub plan, then branch protection; `/cloud`
  setup on Test-Coder2.3 (workflow, `POCKETIDE_API_KEY`, `POCKETIDE_MODEL`); Actions write for the phone's token; delete
  the old branches.
- **`release.sh` and branch protection.** Recommendation: protection applies to everyone except the owner's account, and
  `release.sh` keeps pushing to `main` as the owner. The agents the protection is for (`/cloud`'s token, Copilot) cannot
  bypass it. Releases as pull requests would double the work of the two-edition copy for no added safety, since the owner
  already approves each release. The cost: any session holding the owner's token can also push, so the rule "ask before
  every release" stays a convention.
- **When the milestone is done:** PocketIDE takes section 7's task 1 through `/cloud`; the pull request's check and guard
  run (reopened by the owner until finding 1 is fixed); `/bench` posts an A/B comment; the owner merges; the run is written
  up in `BENCHMARKS.md`.
- **Spending:** the owner has put $5 on Haimaker. No run uses a paid key without the owner's yes for that run, and each
  paid run's cost is recorded beside its result.
