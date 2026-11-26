package dev.pocketide.app

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.pocketide.app.core.AgentActions
import dev.pocketide.app.core.DocEdit
import dev.pocketide.app.core.DocText
import dev.pocketide.app.core.EditBlock
import dev.pocketide.app.core.EditBlocks
import dev.pocketide.app.core.FileEdit
import dev.pocketide.app.core.Prompts
import dev.pocketide.app.core.RepetitionGuard
import dev.pocketide.app.core.HistoryTrim
import dev.pocketide.app.core.Rounds
import dev.pocketide.app.core.StuckDetector
import dev.pocketide.app.core.TaskTracker
import dev.pocketide.app.core.ToolLoop
import dev.pocketide.app.core.ProviderKind
import dev.pocketide.app.core.Providers
import dev.pocketide.app.core.ToolCall
import dev.pocketide.app.core.ToolTurn
import dev.pocketide.app.core.Tools

/**
 * v0.4 W2: one request handled with native tool calling. The model calls tools (read, search, list, write, edit,
 * run, update_plan); results go back until it answers in plain text. With agent mode on, writes go straight to the
 * project and the page is checked afterwards (errors are sent back, up to 3 times). With it off, writes are staged
 * and shown as review cards when the model is done.
 */
class NativeAgent(
    private val ctx: Context,
    private val store: ProjectStore,
    private val settings: AppSettings,
    private val agent: Boolean,
    private val box: LinearLayout,
    private val body: TextView,
    write: (FileEdit) -> Boolean,
    private val exec: (String, (Int, String) -> Unit) -> Unit,
    private val askRun: (String, (Int, String) -> Unit, () -> Unit) -> View,
    private val check: ((List<String>) -> Unit) -> Unit,
    private val setCall: (ModelCall?) -> Unit,
    private val onPlan: (List<Pair<String, String>>) -> Unit,
    private val scrollDown: () -> Unit,
    private val docs: Boolean,
    private val docEdit: (String, String, Map<String, String>) -> String,
    private val verifyPlan: () -> dev.pocketide.app.core.Verify.Plan?,
    private val onDone: (Result) -> Unit,
    /** What the user asked for, so files nobody asked for (README, notes, chat logs) are not written. */
    private val request: String = "",
    /** Tells the live status line what the agent is doing, and how much of the reply has arrived. */
    private val phase: (String) -> Unit = {},
    private val progress: (Int) -> Unit = {},
    /** The folder the person limited the work to ("" = the whole project). Writes elsewhere are refused. */
    private val scope: () -> String = { "" },
    /** True during a review: the assistant may read but not change anything. */
    private val readOnly: () -> Boolean = { false },
    /** False when Linux is not running, so commands that need it are answered at once (1.5.129). */
    private val linuxRunning: () -> Boolean = { true },
    /** A message the person typed while the assistant works; taken once, before the next model call. */
    private val steerTake: () -> String? = { null },
    private val steerPending: () -> Boolean = { false },
    /** True once when the person's message cancelled the open model call on purpose (not Stop): the call is dropped and the message read now. */
    private val steerCut: () -> Boolean = { false },
    /** True after the person pressed Stop while a tool was running. */
    private val stopRequested: () -> Boolean = { false },
    /** A stronger model that gives advice; null when off. */
    private val advisor: dev.pocketide.app.core.Advisor.Plan? = null,
    /** The spending limit for the whole request (shared by every exchange it starts); null when off. */
    private val budget: () -> dev.pocketide.app.core.RunBudget? = { null },
    /**
     * A limit outside the app that ends the run without an answer (the benchmark's per-task cut-off); 0 when there is none, as on the
     * phone. The run wraps up at [HARD_LIMIT_WRAP_PERCENT]% of it, the same way it does at the round or time limit, so it ends with
     * what was found and the best guess instead of being cut off mid-search (Copilot's HANDOFF-RAISE-CEILING WP3, 1.5.145).
     */
    private val hardLimitMs: Long = 0L,
    /** Search by meaning for find_code (SemanticIndex, 1.5.141): the query and the project's files in, ranked hits out; null when off. */
    private val meaning: ((String, Map<String, String>) -> List<dev.pocketide.app.core.RankedSearch.Hit>)? = null,
    /** Loads the page hidden and reports what is on screen, with a screenshot when one could be taken. */
    private val look: ((String, String, String, (String, String?) -> Unit) -> Unit)? = null,
    /** Checks a file the assistant just wrote; the callback gets an error description or null. */
    private val syntax: ((String, String, (String?) -> Unit) -> Unit)? = null,
    /** Asks a language server for the errors in a file the assistant just wrote; the callback gets the note or null. */
    private val lsp: ((String, String, (String?) -> Unit) -> Unit)? = null,
    /** Names a script the assistant just wrote reads but never defines (assets/check/names.js); the callback gets its raw answer or null. */
    private val names: ((String, String, (String?) -> Unit) -> Unit)? = null,
    /** (cache folder, map squares): the game folder is built from the cache before the first model call. */
    private val preflight: Pair<String, String>? = null,
    /** An add-on's work before the first model call (for example a game folder built from a game cache); null for none. */
    private val prepare: dev.pocketide.app.core.AddOnPrep? = null,
    /** The model the explore tool's helper uses; null = the same model. */
    private val explorerSlot: () -> dev.pocketide.app.core.Workflow.Slot? = { null },
    /** True for the explorer helper itself: it may not start another one. */
    private val nested: Boolean = false,
    /** A repair turn (1.5.164): finishing without changing a file is sent back once ("make the fix"). */
    private val mustEdit: Boolean = false,
    /** A round limit for this run only (the explorer's); 0 = the setting. */
    private val roundCap: Int = 0,
    /** True where setup commands can run (the GPL edition's Linux); elsewhere the assistant is told they could not run. */
    private val setupRuns: Boolean = false,
    /** When set, this run is one of several parts built at the same time: it may write only these files (a trailing slash = a folder), and it runs no commands. */
    private val owned: List<String>? = null,
    /** Files an earlier run of this job changed (parts built at the same time): they count as changed and not yet checked, so the finish checks run on them. */
    private val checkAfter: List<String> = emptyList(),
    /** Files changed most often in recent turns (from the checkpoints), for the project map. */
    private val hotFiles: () -> List<String> = { emptyList() },
    /** Deletes a project file (apply_patch's Delete File and Move to); null where deleting is not offered. */
    private val remove: ((String) -> Boolean)? = null,
    /** Called before a tool writes a file straight to the project (game_assets), so Undo and a second try can put it back (1.5.167). */
    private val beforePut: ((String) -> Unit)? = null,
    /** The person's projects (ProjectsTool, 1.5.168): runs one call off the main thread; null where it is not offered. */
    private val projects: ((Map<String, Any?>) -> String)? = null,
    /** Called after each round whose tools changed files (with the round number), so the chat can save a checkpoint. */
    private val onRound: ((Int) -> Unit)? = null,
    /** The person's message is a question (Habits.isQuestion): answer it; reading is fine, changing is refused. */
    question: Boolean = false,
    /** The request only asks to run, check or report (Scope.reportOnly): commands and page looks run, files are not changed. */
    reportOnly: Boolean = false,
    /** The request is research (Intent.Kind.RESEARCH): the app does not push the assistant to change files while it looks. */
    private val research: Boolean = dev.pocketide.app.core.Requirements.asksForResearch(request),
    /** The model refused native tool calling: the tools are written as text blocks (TextTools) and their results come back as text. */
    private val textTools: Boolean = false,
    /** Called with the work log each time it grows, so the chat can save it while the run goes (1.5.96). */
    private val onLog: ((List<dev.pocketide.app.core.WorkLog.Item>) -> Unit)? = null,
    /**
     * Puts a held change to the saved tests to the person at once (path, the steps it removes or changes, allow, keep) and the run
     * waits for the answer (1.5.171). Null: the change is proposed when the run ends, as before (benchmarks, parallel parts).
     */
    private val askTest: ((String, List<String>, () -> Unit, () -> Unit) -> View)? = null,
) {
    /** Screenshots from look_at_preview, sent as an image message right after the tool results. */
    private val shots = ArrayList<String>()
    // 1.5.170: what kind of request this is can change mid-run: "yes, do it" after a question unlocks changes (Steer.unlocks)
    private var question = question
    private var reportOnly = reportOnly
    /** The person's messages sent during the run, oldest first (1.5.170): every later ask and check reads the job with them. */
    private val steers = ArrayList<String>()
    /** The job as it stands now: the request and every message since, newest first (Steer.job). */
    private val job: String get() = dev.pocketide.app.core.Steer.job(request, steers)
    class Result(
        val text: String,
        val staged: Map<String, String>,
        val changed: List<String>,
        val labels: List<String>,
        val ok: Boolean,
        val rejected: Boolean = false,
        val stopped: Boolean = false,
        val error: String? = null,
        val needsSettings: Boolean = false,
        /** Web pages the assistant read (address to title). */
        var sources: List<String> = emptyList(),
        /** A stopped run: what it did and found (StopNote), saved with it in the chat so "Continue" carries on. */
        var progress: String = "",
    ) {
        /** How the reply was made: thinking, words between steps and steps, in order (WorkLog, 1.5.95). */
        var log: List<dev.pocketide.app.core.WorkLog.Item> = emptyList()
        /** Play-test changes put to the person: path, new content, the steps it removes or changes (1.5.98). */
        var testProposals: List<Triple<String, String, List<String>>> = emptyList()
        /** For a second try (SecondTry, 1.5.112): why the run was asked to wrap up ("" when it was not), the last check after the last change when it failed, and whether the person pressed Stop. */
        var cutShort: String = ""
        var failedCheck: String = ""
        var personStopped: Boolean = false
        /** The app's verdict on a task run (Outcome, 1.5.170); null for a question, a report, research, an error or a stop. */
        var outcome: dev.pocketide.app.core.Outcome.Kind? = null
        /** The run fell back to the small tool set (SmallTools): a sign the model is weak for this job. */
        var narrowed: Boolean = false
    }

    /** Test changes the request did not ask for, put to the person after the run (Scope.proposalNote, 1.5.98). */
    private val testProposals = LinkedHashMap<String, Pair<String, List<String>>>()

    /** The play-test guard: a weakening change becomes a proposal the person can allow, and the model is told to carry on. */
    private fun testGate(path: String, prior: String?, content: String, request: String): String? {
        dev.pocketide.app.core.Scope.checkRefusal(path, prior, content, request)?.let { return it }
        dev.pocketide.app.core.Scope.testRefusal(path, prior, content, request) ?: return null
        val gone = dev.pocketide.app.core.Scope.gone(prior ?: "", content)
        testProposals[path.trimStart('/')] = content to gone
        return dev.pocketide.app.core.Scope.proposalNote(gone)
    }

    /**
     * A test change [testGate] just held, asked about now instead of after the run (1.5.171). Before, the card came when the run had
     * ended, so the model had carried on against the old test and "Allow" changed a file nobody ran again. The run waits on the card
     * (the time is the person's, as for Run/Skip); the answer is the tool's result, so after "Allow" the model can run the test at once.
     * False when there is no one to ask; the caller then sends the usual note and the change is proposed at the end.
     */
    private fun askTestNow(path: String, content: String, label: String, reply: (String, String) -> Unit): Boolean {
        val ask = askTest ?: return false
        if (HybridBench.running) return false   // 1.5.184: no one is watching the benchmark; the saved tests stay as they are
        val p = path.trimStart('/')
        val gone = testProposals[p]?.second ?: return false
        pausedAt = System.currentTimeMillis()
        fun resume() { if (pausedAt > 0) { pausedMs += System.currentTimeMillis() - pausedAt; pausedAt = 0 } }
        box.addView(ask(p, gone, {
            resume(); testProposals.remove(p)
            if (write(FileEdit(p, content))) { changed.add(p); writeMark = round; readFiles.remove(p); reply("The person allowed this test change: $p is written. Run the test again to see where it stands.", "$label (allowed)") }
            else reply("The person allowed the test change, but $p could not be written.", "$label (failed)")
        }, {
            resume(); testProposals.remove(p)
            reply("The person kept the saved tests as they are: $p was not changed. Do not change it another way; if the test is right and the app is wrong, fix the app.", "$label (kept)")
        }))
        scrollDown()
        return true
    }

    /** This run's work log (WorkLog): saved with the reply in the chat. */
    private val workLog = ArrayList<dev.pocketide.app.core.WorkLog.Item>()
    private fun logged() { onLog?.let { cb -> try { cb(workLog.toList()) } catch (e: Exception) { /* saving must not stop the run */ } } }

    private val sourceList = LinkedHashSet<String>()

    /** The app's own record of the checks that ran in this request (Proof), and whether a claim without one was sent back. */
    private val proofChecks = ArrayList<dev.pocketide.app.core.Proof.Check>()
    private var proofAsked = false


    private fun recordCheck(c: ToolCall, r: String, atRound: Int) {
        when (c.name) {
            "run_command" -> {
                val cmd = (c.args()["command"] as? String).orEmpty()
                val code = Regex("""^exit code (-?\d+)""").find(r)?.groupValues?.get(1)?.toIntOrNull() ?: return
                val exists = { p: String -> store.exists(p) || staged.containsKey(p) }
                if (dev.pocketide.app.core.Proof.isCheckCommand(cmd, exists)) proofChecks.add(dev.pocketide.app.core.Proof.command(cmd, code, atRound, exists))
            }
            "look_at_preview" -> {
                if (r.startsWith(dev.pocketide.app.core.LookOffer.NO_PAGE)) return
                val ok = when { r.contains("VERDICT: PASS") -> true; r.contains("VERDICT: FAIL") || r.contains("FAILED") -> false; else -> null }
                val what = if ((c.args()["test"] as? String).isNullOrBlank()) "looked at the page" else "play tests"
                proofChecks.add(dev.pocketide.app.core.Proof.Check(what, ok, atRound, true))
            }
        }
    }

    /** A check the app ran itself (page check, play tests, the project's tests, game check). */
    private fun appCheck(what: String, ok: Boolean?) { proofChecks.add(dev.pocketide.app.core.Proof.Check(what, ok, round, true)) }

    /** The final reply carries the app's record of what was checked after the last change. */
    private fun withProof(r: Result): Result {
        if (!agent || nested || r.error != null || r.rejected || r.stopped) return r
        val v = verdict(r)
        val line = if (r.changed.isEmpty()) "" else "\n\n" + dev.pocketide.app.core.Proof.footer(proofChecks, writeMark)
        if (v == null) return if (line.isEmpty()) r else Result(r.text.trimEnd() + line, r.staged, r.changed, r.labels, r.ok, r.rejected, r.stopped, r.error, r.needsSettings)
        trace("outcome: " + v.header)
        // the app's verdict comes first, the model's words under it (1.5.170, Outcome)
        return Result(v.header + "\n\n" + r.text.trim() + line, r.staged, r.changed, r.labels, r.ok && v.kind != dev.pocketide.app.core.Outcome.Kind.NOT_DONE,
            r.rejected, r.stopped, r.error, r.needsSettings).also { it.outcome = v.kind }
    }

    /**
     * The app's own verdict on a task run (Outcome, 1.5.170), from what it saw: files changed, checks that ran the program after the
     * last change, a cut-off, and anything its finish checks found still open. Null for a question, a run-and-report or research.
     */
    private fun verdict(r: Result): dev.pocketide.app.core.Outcome.Verdict? {
        // a question, a report, research, or a run that answered with a finding (PremiseCheck) has no task verdict
        if (question || reportOnly || researchRequest || readOnly() || finding != null || docsOnly()) return null
        val after = proofChecks.filter { it.runtime && it.round >= writeMark }
        val passed = after.lastOrNull { it.ok == true }?.what.orEmpty()
        val failed = dev.pocketide.app.core.Proof.lastFailed(proofChecks, writeMark)?.what.orEmpty()
        val cut = if (wrapAt >= 0) wrapWhy.ifBlank { stopReason } else ""
        val open = ArrayList<String>()
        if (!r.ok) open.add(stopReason.ifBlank { "the app's checks did not pass" })
        tracker.unfinished().takeIf { it.isNotEmpty() }?.let { open.add("${it.size} plan step${if (it.size == 1) "" else "s"} still open (" + it.first().take(60) + ")") }
        // 1.5.173: a step the model itself marked failed ("Re-check the files and look at the result in the preview") sat under "✓ Done and checked"
        tracker.failed().takeIf { it.isNotEmpty() }?.let { open.add("${it.size} plan step${if (it.size == 1) "" else "s"} marked failed (" + it.first().take(60) + ")") }
        refreshStubs()
        if (stubs.isNotEmpty()) open.add("unfinished files: " + stubs.keys.take(3).joinToString(", "))
        undefinedLeft.filterKeys { it in changed && store.read(it) != null }.takeIf { it.isNotEmpty() }?.let { open.add("names nothing defines in " + it.keys.take(3).joinToString(", ")) }
        dev.pocketide.app.core.RenameCheck.asked(steers.lastOrNull() ?: request)?.let { rn -> val (_, n) = dev.pocketide.app.core.RenameCheck.left(rn.old, paths() - dev.pocketide.app.core.Scope.namedChecks(request)) { current(it) }; if (n > 0) open.add("${rn.old} still appears in $n place${if (n == 1) "" else "s"}") }
        // 1.5.175: "remove the coupon feature" with the thing still mentioned (RemoveCheck)
        dev.pocketide.app.core.RemoveCheck.asked(steers.lastOrNull() ?: request)?.let { st -> val (_, n) = dev.pocketide.app.core.RemoveCheck.left(st, paths()) { current(it) }; if (n > 0) open.add("\"$st\" still mentioned in $n place${if (n == 1) "" else "s"}") }
        return dev.pocketide.app.core.Outcome.of(r.changed.size, passed, failed, cut, open, r.staged.size)
    }

    /** library_docs (LibDocs, 1.5.112): the README and type definitions of the version the project uses, cached for the session. Runs off the main thread. */
    private fun libraryDocs(lib: dev.pocketide.app.core.LibDocs.Lib, topic: String): String {
        fun get(url: String): String? = libDocCache.getOrPut(url) { try { WebClient.get(url).body } catch (e: Exception) { "" } }.takeIf { it.isNotEmpty() }
        return try {
            if (lib.kind == dev.pocketide.app.core.LibDocs.Kind.PYPI) {
                val json = get(dev.pocketide.app.core.LibDocs.pypiUrl(lib)) ?: return "Could not find ${lib.name}${lib.version?.let { " $it" } ?: ""} on PyPI."
                val (ver, text) = dev.pocketide.app.core.LibDocs.pypiText(json)
                dev.pocketide.app.core.LibDocs.answer(lib, ver, text, null, topic)
            } else {
                val pkg = get(dev.pocketide.app.core.LibDocs.packageJsonUrl(lib)) ?: return "Could not find ${lib.name}${lib.version?.let { " $it" } ?: ""} on npm."
                val ver = dev.pocketide.app.core.LibDocs.versionOf(pkg)
                val pinned = dev.pocketide.app.core.LibDocs.Lib(lib.name, ver ?: lib.version, lib.kind, lib.from)
                val readme = dev.pocketide.app.core.LibDocs.readmeUrls(pinned).firstNotNullOfOrNull { get(it) }
                val typesUrl = dev.pocketide.app.core.LibDocs.typesUrl(pinned, pkg)
                val types = typesUrl?.let { u -> get(u)?.let { main -> main + dev.pocketide.app.core.LibDocs.typeRefs(u, main).mapNotNull { get(it) }.joinToString("\n") } }
                dev.pocketide.app.core.LibDocs.answer(lib, ver, readme, types, topic)
            }
        } catch (e: Exception) { "Could not read the docs of ${lib.name}: ${e.message}" }
    }
    private val libDocCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun emit(r0: Result) {
        if (narrowed && Tools.only != null) { Tools.only = null }
        val r = withProof(r0); r.sources = sourceList.toList(); r.log = workLog.toList(); r.testProposals = testProposals.map { (p, v) -> Triple(p, v.first, v.second) }
        r.cutShort = if (wrapAt >= 0) wrapWhy.ifBlank { stopReason } else ""
        r.failedCheck = dev.pocketide.app.core.Proof.lastFailed(proofChecks, writeMark)?.what.orEmpty()
        r.personStopped = stopRequested()
        r.narrowed = narrowed
        if (r.stopped || (!r.ok && r.error == null && !r.rejected) || (wrapAt >= 0 && stopReason.isNotEmpty())) {
            val passed = proofChecks.lastOrNull { it.ok == true }?.let { it.what + " (round ${it.round})" }.orEmpty()
            r.progress = dev.pocketide.app.core.StopNote.text(said, labels, changed.toList(), lastPlan, stopReason, passed)
        }
        onDone(r)
    }

    /** Rounds that only ran checks after a check had already passed (Proof.checkedTooLong). */
    private var afterPassChecks = 0

    /** Why the model was asked to wrap up (wrapReason), for the stop message. */
    private var wrapWhy = ""

    /** Why the run is ending early, for StopNote; set where the decision is made. */
    private var stopReason = ""

    /** Re-prompts sent after the model said it was done: one budget for every finish check (Rounds.FINISH_BUDGET). */
    private var finishReprompts = 0
    private val finishBudget: Int get() = Rounds.finishBudget(settings.maxRounds)

    /**
     * Sends the model back once more at finish time, or ends the run as not finished when the budget is spent. [why] names the
     * check in a few words; [ask] is the message. Each message says which check of how many it is, so the model knows the run
     * will end.
     */
    /** counted = false (1.5.139, the ask checklist): the ask has its own one-shot slots and does not spend the shared finish budget. */
    private fun goBack(text: String, why: String, ask: String, images: List<String> = emptyList(), counted: Boolean = true) {
        if (!counted) {
            trace("ask check: $why")
            messages.add(Tools.assistantText(text.ifBlank { "Done." }))
            messages.add(Tools.userText(anthropic, "Ask check: $why. $ask"))
            request()
            return
        }
        if (finishReprompts >= finishBudget) {
            stopReason = "the finish budget of $finishBudget checks was spent; the last one was: $why"
            trace("not finished: the finish budget ($finishBudget) is spent; last check: $why")
            note("Not finished: the model was sent back $finishBudget times and the work still does not pass ($why).\n$NEXT_STEPS", Ui.BAD)
            emit(Result(text.trimEnd() + "\n\nNot finished: $why.", staged, changed.toList(), labels, false))
            return
        }
        finishReprompts++
        trace("finish check $finishReprompts of $finishBudget: $why")
        messages.add(Tools.assistantText(text.ifBlank { "Done." }))
        val head = "Finish check $finishReprompts of $finishBudget" + (if (finishReprompts == finishBudget) " (the last one: after it the run ends)" else "") + ": $why. "
        messages.add(if (images.isEmpty()) Tools.userText(anthropic, head + ask) else Tools.userText(anthropic, head + ask, images))
        request()
    }

    /** The model's words between tool calls, and the last plan it posted: kept for StopNote. */
    private val said = ArrayList<String>()
    private var lastPlan: List<Pair<String, String>> = emptyList()

    private val anthropic = Providers.byId(settings.provider).kind == ProviderKind.ANTHROPIC
    private val staged = LinkedHashMap<String, String>()
    private val changed = LinkedHashSet<String>()
    private val labels = ArrayList<String>()
    private val messages = ArrayList<Map<String, Any?>>()
    private var system = ""
    private var round = 0
    /**
     * For PLAN.md: boxes ticked by this write go back to open when code changed after the last passing check. Boxes that were
     * already ticked stay ticked. Null for any other file. Writing the plan is not a code change, so it never moves [writeMark].
     */
    private fun planGate(p: String, content: String): Pair<String, String>? {
        if (!dev.pocketide.app.core.PlanGate.isPlan(p)) return null
        if (writeMark <= checkMark || docsOnly() || (look == null && verifyPlan() == null)) return content to ""
        val (text, n) = dev.pocketide.app.core.PlanGate.reopenNew(current(p), content)
        return text to if (n == 0) "" else "\n$n newly ticked item${if (n == 1) "" else "s"} set back to open: no check has passed since the last code change. Run look_at_preview, and tick them once it says VERDICT: PASS."
    }

    /** Rounds in which files were last changed and last checked (run_command or look_at_preview). */
    private var writeMark = -1
    /** Fix attempts for the page check and Verify; follows the rounds setting (3 at the default). */
    private val maxFixes: Int get() = Rounds.fixAttempts(settings.maxRounds)
    private var checkMark = -1
    init { if (checkAfter.isNotEmpty()) { changed.addAll(checkAfter); writeMark = 0 } }
    private var verifyNudged = false
    private var printNudged = false
    /** The ask checklist (AskLedger): 0 not asked, 1 asked, 2 done with (1.5.138). */
    private var ledgerState = 0
    /** CauseGate (1.5.147): the first change was held back once for a named cause; changed data files were questioned once. */
    private var causeAsked = false
    private var dataAsked = false
    private var varietyAsked = false
    private var scopeAsked = false
    // 1.5.161 (FreshVerify): each changed file's text before this run's first write to it (null = new), for the fresh reviewer's diff
    private val originals = LinkedHashMap<String, String?>()
    private fun remember(p: String) { synchronized(originals) { if (!originals.containsKey(p)) originals[p] = try { if (store.exists(p)) store.read(p) else null } catch (e: Exception) { null } } }
    private val write: (FileEdit) -> Boolean = { e -> remember(e.path); write(e) }
    private var lastCommandOutput: String? = null
    private var freshAsked = false
    private var mustEditAsked = false
    /** Tool calls that could not run as sent (1.5.164, tool-call health per provider): unknown names, calls written as text, calls cut off. */
    var badCalls = 0; private set
    /** Whether the run ended with report_finding (Copilot N2, PR #27: counted in the benchmark so over-use can be seen). */
    val reported: Boolean get() = finding != null
    /** Set by report_finding: the run's answer, given as soon as the model's turn ends (PremiseCheck). */
    private var finding: String? = null
    /** 1.5.160: the files the scope check asked about; later nudges tell the model to leave them as they are (Copilot ANALYSIS-1.5.159). */
    private var scopeFiles: List<String> = emptyList()
    private fun scopeTail(): String = if (scopeFiles.isEmpty()) "" else "\n\n" + dev.pocketide.app.core.ScopeGate.settled(scopeFiles)
    /** The summary the model wrote before the checklist asked for its list (1.5.139): it stays the person's answer. */
    private var ledgerSummary = ""
    private var ledgerAnswered = false
    /** The round of the checklist's send-back (1.5.140): files changed after it decide whose text is the answer. */
    private var ledgerAgainRound = -1
    /** The helper model's count of the request's asks (1.5.139), asked once; null when not asked or not answered. */
    private var helperAsks: Int? = null
    private var asksCounted = false
    private var filesAsTextAsked = false
    private var toolTextAsked = false
    private var deferralAsked = false
    private var changeAsked = false
    /** Asks for something a reply announced and never gave ("Here is the change:"), and the round of the last one: asked again only after tools ran. */
    private var announcedAsks = 0
    private var announcedRound = -1
    private var readForModel = false
    private var ranSomething = false
    private var runAsked = false
    /** Every this many rounds the person's request is repeated at the end, where a long run cannot lose it. */
    private val REQUEST_REMINDER_EVERY = 10
    /** Share of [hardLimitMs] after which the run wraps up: the rounds after the ask, and one slow model reply, still fit. */
    private val HARD_LIMIT_WRAP_PERCENT = 60
    private fun requestReminder(r: String) = dev.pocketide.app.core.RequestReminder.text(r)

    /** The tool list for the text format, built once (the tools do not change during a run). */
    private val chatHome: Boolean by lazy { try { Projects.isChat(ctx) } catch (e: Exception) { false } }
    private val catalogue: String by lazy { if (small) Tools.smallCatalogue(docs, dev.pocketide.app.core.SmallLocal.forApp(request), chatHome) else Tools.textCatalogue(docs) }
    /** 1.5.177: a local model of 8B or fewer runs in small local model mode (core/SmallLocal): one JSON action per reply, six tools. */
    private val small: Boolean by lazy {
        val pid = settings.provider
        dev.pocketide.app.core.SmallLocal.applies(pid, settings.baseFor(pid), settings.model(pid).ifBlank { Providers.byId(pid).model })
    }
    /** Tools are described in the prompt and written in the reply (the OpenHands text format, or small local model mode's JSON). */
    private val textMode: Boolean get() = textTools || small

    /** The reply's text blocks as tool calls. lastRaw keeps the reply as the model wrote it, for the history. */
    private var lastRaw = ""
    private fun textTurn(t: ToolTurn): ToolTurn {
        lastRaw = t.text
        val p = if (small) dev.pocketide.app.core.SmallLocal.parse(t.text) { Tools.paramTypes(it) } else dev.pocketide.app.core.TextTools.parse(t.text) { Tools.paramTypes(it) }
        return ToolTurn(p.text, p.calls, t.stop, t.thinking, t.thinkSecs)
    }
    // 1.5.177: what small local model mode's next-step line is based on
    private var smallRead = false
    private var smallEdited = false
    private val smallReadPaths = LinkedHashSet<String>()
    private var smallTarget: String? = null
    private var smallLooked = false
    private var smallProposed = false
    private val smallAboutApp by lazy { dev.pocketide.app.core.AppHelp.aboutApp(request) }
    private val smallSetting by lazy { dev.pocketide.app.core.AppHelp.settingRequest(request)?.setting }
    private fun smallState(results: List<Pair<ToolCall, String>>): dev.pocketide.app.core.SmallLocal.State {
        for ((c, r) in results) {
            val path = (c.args()["path"] as? String)?.trim()?.removePrefix("./")
            if (c.name == "read_file" && !r.startsWith("Error")) { smallRead = true; if (path != null) { smallReadPaths.remove(path); smallReadPaths.add(path) } }
            if (c.name in setOf("edit_file", "write_file", "edit_lines") && !dev.pocketide.app.core.SmallTools.slipped(r) &&
                !r.startsWith("Error") && !r.startsWith("Not written")) smallEdited = true
            if (c.name == "app_help") smallLooked = true
            if (c.name in setOf("search", "list_files", "run_command"))
                dev.pocketide.app.core.SmallLocal.pathsIn(r).firstOrNull { it !in smallReadPaths && store.exists(it) }?.let { smallTarget = it }
        }
        if (smallTarget in smallReadPaths) smallTarget = null
        val after = proofChecks.filter { it.runtime && it.round >= writeMark }
        val last = after.lastOrNull()
        return dev.pocketide.app.core.SmallLocal.State(question || reportOnly, smallRead, smallEdited || changed.isNotEmpty(),
            smallEdited && last != null, last?.ok == false, smallTarget, smallReadPaths.lastOrNull(),
            smallReadPaths.lastOrNull()?.let { p -> store.read(p)?.let { dev.pocketide.app.core.Rewrite.lines(it) } } ?: 0,
            smallAboutApp, smallLooked, smallSetting, smallProposed, chatHome)
    }
    private var securityChecked = false
    private var reviewed = false
    /** Round of the last look_at_preview, and whether the automatic look before finishing has run. */
    private var lookMark = -1
    private var accepted = false
    /** The work summary is added once per request, the first time old messages are shortened. */
    private var summarized = false
    private var fixes = 0
    /** What the last fix attempt was asked to cure; the same problem again means the attempt changed nothing, so stop. */
    private var lastFixSig = ""
    /** The last plan the model showed; a plan repeated unchanged is not progress (some models re-post step one forever). */
    private var shownPlan = ""
    private var lastShown = ""
    /** The steps of the last accepted plan, used to protect the feature list from being dropped or reworded. */
    private var lastSteps: List<Pair<String, String>> = emptyList()
    private var planRefused = false
    /** The person sent a message into the job since the plan was last set (1.5.167): the plan may then change freely. */
    private var steeredSincePlan = false
    /** Steps the model marked done before any check ran after its last change; they become done when a check passes. */
    private val pendingDone = LinkedHashSet<String>()
    /** edit_lines calls already warned that they delete lines (path:start:end); the same call again goes ahead. */
    private val linesWarned = HashSet<String>()
    private val DOC_EXT = setOf("md", "markdown", "txt", "rst", "adoc")

    /**
     * Checks the files marked unfinished again, as they are now (1.5.184, benchmark run 5, task 05): edit_lines and run_command
     * change files without updating [stubs], so app.js stayed "unfinished" after the stand-in was gone and the test passed.
     */
    private fun refreshStubs() {
        for (p in stubs.keys.toList()) {
            val why = current(p)?.let { dev.pocketide.app.core.StubCheck.find(p, it) }
            if (why == null) stubs.remove(p) else stubs[p] = why
        }
    }

    /** Every file this run changed is a note or document (Markdown, text): no test or preview can check it. */
    private fun docsOnly(): Boolean = changed.isNotEmpty() && changed.all { it.substringAfterLast('.', "").lowercase() in DOC_EXT }

    /** A check passed after the last change: the steps waiting for one are done now. */
    private fun promoteChecked() {
        if (pendingDone.isEmpty() || checkMark < writeMark) return
        val steps = lastSteps.map { (t, st) -> if (t in pendingDone && st != "failed") t to "done" else t to st }
        val n = pendingDone.size
        pendingDone.clear()
        lastSteps = steps
        tracker.update(steps)
        onPlan(steps)
        note("Checked: $n step${if (n == 1) "" else "s"} marked done.", Ui.GOOD)
    }
    private var row: LinearLayout? = null
    private val tracker = TaskTracker(Rounds.reminders(settings.maxRounds))
    private val loop = ToolLoop().also { l -> if (small) l.limit = 2 }
    private val streak = dev.pocketide.app.core.ReadStreak()
    private val stuck = StuckDetector()
    /** Rarely used tools the model has asked for; the rest are listed in one use_tool entry (Tools.ON_DEMAND). Not used in the text format. */
    private val loadedTools = LinkedHashSet<String>()
    private fun toolSet(): Set<String>? = if (textMode) null else loadedTools.toSet()
    private val maxRounds: Int get() = (if (roundCap > 0) roundCap else Rounds.limit(settings.maxRounds)).let { if (question && !nested) minOf(it, Rounds.QUESTION_ROUNDS) else it }
    private val timeLimitMinutes = Rounds.timeLimitMinutes(settings.maxRounds)
    /** A request to find things out: the app does not push the assistant to change files while it researches. */
    private val researchRequest = research
    private val FILE_CHANGES = setOf("write_file", "delete_file", "edit_file", "edit_lines", "multi_edit", "apply_patch", "doc_edit")
    private val startedAt = System.currentTimeMillis()
    /** Time spent waiting for the person to answer a Run/Skip card; it is theirs, not the run's. */
    private var pausedMs = 0L
    private var pausedAt = 0L
    private fun askRunTimed(cmd: String, onRun: (Int, String) -> Unit, onSkip: () -> Unit): View {
        pausedAt = System.currentTimeMillis()
        fun resume() { if (pausedAt > 0) { pausedMs += System.currentTimeMillis() - pausedAt; pausedAt = 0 } }
        return askRun(cmd, { code, out -> resume(); onRun(code, out) }, { resume(); onSkip() })
    }
    private fun runningMs(): Long = System.currentTimeMillis() - startedAt - pausedMs - (if (pausedAt > 0) System.currentTimeMillis() - pausedAt else 0)
    /** Round at which the model was told to wrap up (limit, time or no progress); -1 until then. */
    private var wrapAt = -1
    private val mutating = setOf("game_assets", "write_file", "delete_file", "edit_file", "edit_lines", "multi_edit", "apply_patch", "run_command", "doc_edit", "remember")
    /** game_cache changes the project only when it exports or packs. */
    private fun isMutating(c: ToolCall) = c.name in mutating || dev.pocketide.app.core.AddOns.changes(c.name, c.args())
    private var remembered = 0
    private val webSession = HashSet<String>()   // sites allowed for this request
    private val readFiles = HashMap<String, Int>()   // path -> stamp of what the model was last shown

    fun run(system: String, history: List<Map<String, Any?>>) {
        val prep = prepare
        if (prep != null && !nested) { runPrepared(prep, system, history); return }
        val pf = preflight
        if (pf == null || nested) { start(system, history); return }
        // a build request that fits a starter kit: set the kit up first, so the model starts from something that runs and passes its tests
        if (pf.first == dev.pocketide.app.core.Design.KIT) {
            val kind = pf.second; val dir = dev.pocketide.app.core.Design.folderFor(kind)
            note("Setting up the $kind starter kit first (it runs and has play tests); the assistant then shapes it to your request.", Ui.MUTED)
            McpClient.background {
                val text = try { dev.pocketide.app.core.GameAssets.run(mapOf("action" to "scaffold", "kind" to kind, "out" to dir), cacheEnv { true }) } catch (e: Throwable) { "scaffold failed: " + (e.message ?: e.javaClass.simpleName) }
                McpClient.onMain {
                    val ok = store.exists("$dir/index.html")
                    if (ok) { writeMark = round; note("Starter kit ready in $dir/.", Ui.GOOD); start(system + dev.pocketide.app.core.Design.builtNote(kind, dir, text), history) }
                    else { note("Could not set up the starter kit; the assistant will start from scratch.", Ui.BAD); start(system, history) }
                }
            }
            return
        }
    }

    /** An add-on's work before the first model call (prepare), then the request. */
    private fun runPrepared(prep: dev.pocketide.app.core.AddOnPrep, system: String, history: List<Map<String, Any?>>) {
        note(prep.label, Ui.MUTED)
        var n = 0
        McpClient.background {
            val text = try { prep.run(cacheEnv { n++ < 200 }) } catch (e: Throwable) { "" }
            McpClient.onMain {
                if (text.isNotEmpty()) { note("Done: the project is ready for the assistant.", Ui.GOOD); start(system + text, history) }
                else { note("Could not prepare the project automatically; the assistant will try.", Ui.BAD); start(system, history) }
            }
        }
    }

    private fun cacheEnv(track: () -> Boolean) = dev.pocketide.app.core.AssetEnv(
        resolve = { p -> if (p.isEmpty()) store.resolve(".keep")?.parentFile else store.resolve(p) },
        put = { p, b -> try { beforePut?.invoke(p) } catch (e: Throwable) { }; val ok = store.writeBytes(p, b); if (ok && track()) synchronized(changed) { changed.add(p) }; ok },
        read = { p -> store.readBytes(p) },
        files = { d -> store.filesUnder(d) },
        asset = { n -> try { ctx.assets.open(if (n.startsWith("../")) n.removePrefix("../") else "gamekit/$n").use { it.readBytes() } } catch (e: Exception) { null } })

    private fun start(system: String, history: List<Map<String, Any?>>) {
        this.system = system
        messages.addAll(history)
        if (nested) { request(); return }
        val manifest = store.read(dev.pocketide.app.core.SetupManifest.FILE)
        if (!setupRuns) { this.system = system + dev.pocketide.app.core.SetupManifest.unavailableNote(manifest); request(); return }
        // which version of the setup file already ran is kept in the app, not in the project (so it is never committed)
        val donePrefs = ctx.getSharedPreferences("setup-done", Context.MODE_PRIVATE)
        val doneKey = store.root.absolutePath
        val steps = dev.pocketide.app.core.SetupManifest.pending(manifest, donePrefs.getString(doneKey, null))
        if (steps == null) { request(); return }
        runSetup(steps) { results ->
            donePrefs.edit().putString(doneKey, dev.pocketide.app.core.SetupManifest.hash(manifest!!)).apply()
            note(dev.pocketide.app.core.SetupManifest.summary(results), if (results.all { it.skipped || it.code == 0 }) Ui.GOOD else Ui.BAD)
            this.system = system + dev.pocketide.app.core.SetupManifest.contextNote(results)
            request()
        }
    }

    /** Runs the project's setup steps one after another; commands from a project file are confirmed like the assistant's own. */
    private fun runSetup(steps: List<dev.pocketide.app.core.SetupManifest.Step>, done: (List<dev.pocketide.app.core.SetupManifest.Result>) -> Unit) {
        val results = ArrayList<dev.pocketide.app.core.SetupManifest.Result>()
        note("Running the project's setup steps (${dev.pocketide.app.core.SetupManifest.FILE}) before starting.", Ui.MUTED)
        fun next(i: Int) {
            if (stopRequested() || i >= steps.size) { done(results); return }
            val st = steps[i]
            phase("Setup: " + st.name.take(40))
            val finished = { code: Int, out: String -> results.add(dev.pocketide.app.core.SetupManifest.Result(st, code, out)); next(i + 1) }
            when (decide(st.run)) {
                dev.pocketide.app.core.CommandRules.Decision.RUN -> exec(st.run, finished)
                dev.pocketide.app.core.CommandRules.Decision.DENY -> { results.add(dev.pocketide.app.core.SetupManifest.Result(st, -1, "", skipped = true)); next(i + 1) }
                else -> { box.addView(askRunTimed(st.run, finished) { results.add(dev.pocketide.app.core.SetupManifest.Result(st, -1, "", skipped = true)); next(i + 1) }); scrollDown() }
            }
        }
        next(0)
    }

    private fun request() {
        if (stopRequested()) { stopReason = "the person pressed Stop"; emit(Result("", staged, changed.toList(), labels, false, stopped = true)); return }
        budget()?.let { b -> b.over()?.let { why -> pauseForBudget(b, why); return } }
        steerTake()?.let { s ->
            trace("steer: read at round $round")
            steeredSincePlan = true
            steers.add(s)
            if ((question || reportOnly) && dev.pocketide.app.core.Steer.unlocks(s)) {
                trace("steer: the person's message asks for the change, so files may now be changed")
                question = false; reportOnly = false
            }
            stuck.changed()
            messages.add(Tools.userText(anthropic, dev.pocketide.app.core.Steer.message(s)))
            note("You: " + s.take(200), Ui.ACCENT)
        }
        // The first time old messages are about to be shortened, add a summary of the work so far (facts from the tool calls,
        // plus a short written summary from the second model when one is set), so the model keeps track of a long job.
        if (!summarized && !nested && HistoryTrim.needed(messages)) {
            summarized = true
            val facts = dev.pocketide.app.core.WorkSummary.facts(messages)
            val main = dev.pocketide.app.core.Workflow.Slot(settings.provider, settings.model(settings.provider).ifBlank { dev.pocketide.app.core.Providers.byId(settings.provider).model })
            val slot = (explorerSlot() ?: main).takeIf { Tools.providerSupports(it.provider) }
            if (slot == null) {
                if (facts.isNotEmpty()) messages.add(Tools.userText(anthropic, dev.pocketide.app.core.WorkSummary.note(facts, null)))
            } else {
                val transcript = dev.pocketide.app.core.WorkSummary.transcript(messages.dropLast(HistoryTrim.KEEP_LAST))
                summarize(slot, transcript) { written ->
                    messages.add(Tools.userText(anthropic, dev.pocketide.app.core.WorkSummary.note(facts, written)))
                    note("Long request: older messages were shortened and a summary of the work so far was added.", Ui.MUTED)
                    request()
                }
                return
            }
        }
        val guard = RepetitionGuard()
        var seen = 0
        var cur: ModelCall? = null
        var looped = false
        phase("Thinking" + meter() + contextMeter()); progress(0)
        // file contents shortened here are no longer in the conversation: a reread of them is real, not a repeat
        val fit = fitBudget()
        // after a too-long error the newest messages are shortened too: two big file reads alone overflowed a 24K window
        val keep = dev.pocketide.app.core.ContextFit.keepLast(fit).let { if (squeeze < 1.0) minOf(it, 2) else it }
        if (HistoryTrim.compact(messages, fit, keep) > 0) { readFiles.clear(); loop.changed() }
        dev.pocketide.app.core.StaleReads.clear(messages)
        val stage = settings.override
        val cheap = routeFor()
        if (cheap != null) { settings.override = cheap; phase("Reading with " + cheap.model.ifBlank { cheap.provider }) }
        else if (runSlot != null) settings.override = runSlot
        val localNow = dev.pocketide.app.core.LocalModels.isLocal(settings.provider, settings.baseFor(settings.provider))
        val wasCheap = cheap != null
        var signal = false
        var over = false
        cur = ModelGateway.current.startNative(settings, if (small) dev.pocketide.app.core.SmallLocal.system(catalogue) + (if (chatHome) "\n" + dev.pocketide.app.core.ChatHome.SMALL_NOTE else "") else if (textTools) system + catalogue else system, messages, !textMode, object : NativeListener {
            override fun onStatus(text: String) {
                phase(text); if (text.contains("characters of reasoning")) signal = true
                if (body.text.isEmpty() || body.text == "…" || body.text.startsWith("Thinking") || body.text.startsWith("Working") || body.text.startsWith("Waiting")) body.text = text
            }
            override fun onDelta(acc: String) {
                signal = true
                phase("Writing the reply" + meter()); progress(acc.length)
                body.text = acc.trimStart(); scrollDown()
                val chunk = acc.substring(minOf(seen, acc.length)); seen = acc.length
                if (!looped && guard.append(chunk)) { looped = true; cur?.cancel() }
            }
            override fun onTurn(turn0: ToolTurn) {
                val turn = if (textMode) textTurn(turn0) else turn0
                over = true
                fitRetries = 0
                setCall(null)
                val names = turn.calls.map { it.name }
                // the cheaper model only reads: anything else it wrote is thrown away and the main model does the round
                if (wasCheap && !dev.pocketide.app.core.Speed.keepCheapTurn(names)) { lastReadOnly = false; request(); return }
                lastReadOnly = dev.pocketide.app.core.Speed.readOnlyRound(names)
                handle(turn)
            }
            override fun onStopped(partial: String) {
                over = true
                setCall(null)
                if (!looped && steerCut()) {
                    // the person's message ended this call early: keep what it had written, then read the message
                    trace("steer: model call interrupted after ${partial.length} characters")
                    if (partial.isNotBlank()) messages.add(Tools.assistantText(partial.trim() + "\n\n(interrupted: the person sent a message)")) else messages.add(Tools.assistantText("Working on it."))
                    request(); return
                }
                if (restartCall != null && restartCall === cur) { restartCall = null; note("Trying again…", Ui.MUTED); request(); return }
                if (looped) { stopReason = "the model kept repeating the same lines"; note("Stopped: the model was repeating the same lines. What it wrote so far is kept.", Ui.BAD) }
                else if (stopReason.isEmpty()) stopReason = "the reply was cut off or the person pressed Stop"
                emit(Result(partial, staged, changed.toList(), labels, false, stopped = true))
            }
            override fun onError(message: String, needsSettings: Boolean, toolsRejected: Boolean) {
                over = true
                setCall(null)
                // the request did not fit the model's context window: learn the window, shorten older messages, send again
                if (!wasCheap && fitRetries < 2 && dev.pocketide.app.core.ContextFit.isTooLong(message)) {
                    fitRetries++
                    val prov = settings.provider
                    val model = settings.model(prov).ifBlank { dev.pocketide.app.core.Providers.byId(prov).model }
                    dev.pocketide.app.core.ContextFit.limitFrom(message)?.let { dev.pocketide.app.core.ModelFacts.learnContext(prov, model, it) }
                    squeeze *= 0.6
                    note("The conversation was too long for this model's context window; shortening older messages and trying again ($fitRetries of 2).", Ui.ACCENT)
                    request(); return
                }
                if (wasCheap) { cheap?.let { s -> dev.pocketide.app.core.Speed.markCheapFailed(s.provider, s.model.ifBlank { Providers.byId(s.provider).model }) }; routeOff = true; lastReadOnly = false; note("The cheaper model could not answer (" + message.take(120) + "); using the main model for the rest of this session.", Ui.MUTED); request(); return }
                emit(Result("", staged, changed.toList(), labels, false, rejected = toolsRejected && round == 0 && changed.isEmpty(),
                    error = message, needsSettings = needsSettings))
            }
        }, docs, toolSet())
        settings.override = stage
        setCall(cur)
        val mine = cur
        if (mine != null && !nested && settings.slowOffer && !localNow) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!over && !mine.cancelled && dev.pocketide.app.core.Speed.slow(dev.pocketide.app.core.Speed.SLOW_SECS.toLong(), signal, false)) offerSlow { slot ->
                if (!over && !mine.cancelled) { if (slot != null) runSlot = slot; restartCall = mine; mine.cancel() }
            }
        }, dev.pocketide.app.core.Speed.SLOW_SECS * 1000L)
    }

    private var advisorUsed = 0

    /** Web searches in a row that came back empty (the free search is rate-limited): after a few, the model is told to stop. */
    private var emptySearches = 0
    private val SEARCH_ELSE = "Searching again may not help right now. Read a page you know with web_fetch (for example " +
        "https://www.npmjs.com/package/<name> or https://github.com/<owner>/<repo>), or go on with what you know and say what you could not check."
    private val NO_SEARCH = "Web search is not answering right now (4 empty results in a row), so it is off for the rest of this request. " +
        "Use web_fetch on a page you know, or finish the work with what you know and say what you could not check."
    /** Retries after a too-long error, and how much smaller than the estimate the history is kept since. */
    private var fitRetries = 0
    private var squeeze = 1.0

    /** Characters the conversation may use for the current model: sized to its context window when that is known. */
    /** How full the model's context window is with this request (see ContextFit.usage). */
    private fun contextMeter(): String {
        val prov = settings.provider
        val model = settings.model(prov).ifBlank { dev.pocketide.app.core.Providers.byId(prov).model }
        val used = HistoryTrim.total(messages) + system.length + Tools.toolChars(docs, toolSet())
        return dev.pocketide.app.core.ContextFit.usage(used, dev.pocketide.app.core.ModelFacts.context(prov, model, settings.baseFor(prov)))
    }

    /** Characters one tool result may use: sized to the model's window (ContextFit.resultCap). */
    private fun resultCap(): Int {
        val prov = settings.provider
        val model = settings.model(prov).ifBlank { dev.pocketide.app.core.Providers.byId(prov).model }
        return dev.pocketide.app.core.ContextFit.resultCap(dev.pocketide.app.core.ModelFacts.context(prov, model, settings.baseFor(prov)))
    }

    private fun fitBudget(): Int {
        val prov = settings.provider
        val model = settings.model(prov).ifBlank { dev.pocketide.app.core.Providers.byId(prov).model }
        val ctx = dev.pocketide.app.core.ModelFacts.context(prov, model, settings.baseFor(prov))
        val b = dev.pocketide.app.core.ContextFit.messageBudget(ctx, system.length, Tools.toolChars(docs, toolSet()), dev.pocketide.app.core.OutputLimit.maxTokens(model))
        return (b * squeeze).toInt().coerceAtLeast(dev.pocketide.app.core.ContextFit.floor(ctx))
    }

    // phase routing and the slow-reply offer
    private var lastReadOnly = false
    private var routeOff = false
    private var routeNoted = false
    private var runSlot: dev.pocketide.app.core.Workflow.Slot? = null
    private var restartCall: ModelCall? = null

    /** The cheap model for this round, or null when the main model should answer it. */
    private fun routeFor(): dev.pocketide.app.core.Workflow.Slot? {
        if (nested || routeOff || !dev.pocketide.app.core.Speed.routeThisRound(settings.phaseRouting, round, lastReadOnly, wrapAt >= 0)) return null
        val slot = settings.routeSlot() ?: return null
        val p = Providers.byId(slot.provider)
        val model = slot.model.ifBlank { p.model }
        if (settings.key(slot.provider).isBlank() && !p.keyless) return null
        if (!dev.pocketide.app.core.Speed.canRoute(p.kind == ProviderKind.ANTHROPIC, anthropic, Tools.providerSupports(slot.provider), settings.toolsRejected(slot.provider, model))) return null
        if (slot.provider == settings.provider && model == settings.model(settings.provider).ifBlank { Providers.byId(settings.provider).model }) return null
        if (dev.pocketide.app.core.Speed.cheapFailed(slot.provider, model)) return null   // it failed earlier in this session
        if (!routeNoted) { routeNoted = true; note("Reading steps use $model; edits, commands and answers use the main model.", Ui.MUTED) }
        return slot
    }

    /** Another provider that has a key and speaks the same message format as this run, for a reply that is not coming. */
    private fun altSlot(): dev.pocketide.app.core.Workflow.Slot? {
        val usable = Providers.all.filter { it.id != "demo" && it.id != settings.provider && !it.keyless && settings.key(it.id).isNotBlank() &&
            Tools.providerSupports(it.id) && (it.kind == ProviderKind.ANTHROPIC) == anthropic && !settings.toolsRejected(it.id, settings.model(it.id).ifBlank { it.model }) }.map { it.id }
        val pid = dev.pocketide.app.core.Workflow.defaultDraftProvider(usable, settings.provider) ?: return null
        return dev.pocketide.app.core.Workflow.Slot(pid, settings.model(pid).ifBlank { Providers.byId(pid).model })
    }

    /** A reply has shown no sign of life for a minute: offer a retry, another provider or more waiting. */
    private fun offerSlow(restart: (dev.pocketide.app.core.Workflow.Slot?) -> Unit) {
        val alt = altSlot()
        note("No answer yet after ${dev.pocketide.app.core.Speed.SLOW_SECS} s. The provider may be busy.", Ui.MUTED)
        val row = Ui.horizontal(ctx)
        fun gap() = Ui.lp(Ui.WRAP, Ui.WRAP).also { it.rightMargin = Ui.dp(ctx, 8) }
        row.addView(Ui.button(ctx, "Retry now") { box.removeView(row); restart(null) }, gap())
        if (alt != null) row.addView(Ui.button(ctx, "Switch to " + alt.model.ifBlank { alt.provider }) { box.removeView(row); restart(alt) }, gap())
        row.addView(Ui.button(ctx, "Keep waiting") { box.removeView(row) }, gap())
        box.addView(row); scrollDown()
    }
    private var heldRound = -1
    private var heldAdvice = ""
    private var finalAsked = false

    /** Asks the advisor model; [then] gets its advice, or null when it is off, used up or unreachable. */
    private fun consult(purpose: dev.pocketide.app.core.Advisor.Purpose, then: (String?) -> Unit) {
        val plan = advisor
        if (plan == null || advisorUsed >= plan.maxCalls) { then(null); return }
        advisorUsed++
        val who = plan.slot.model.ifBlank { Providers.byId(plan.slot.provider).name }
        phase("Asking the advisor ($who)")
        note("Asking the advisor ($who) for advice…", Ui.ACCENT)
        val anth = Providers.byId(plan.slot.provider).kind == ProviderKind.ANTHROPIC
        val prompt = dev.pocketide.app.core.Advisor.prompt(job, dev.pocketide.app.core.Advisor.transcript(messages), purpose, plan.words)
        val saved = settings.override
        settings.override = plan.slot
        var cur: ModelCall? = null
        try {
            cur = ModelGateway.current.startNative(settings, dev.pocketide.app.core.Advisor.SYSTEM, listOf(Tools.userText(anth, prompt)), false, object : NativeListener {
                override fun onStatus(text: String) {}
                override fun onDelta(acc: String) {}
                override fun onTurn(turn: dev.pocketide.app.core.ToolTurn) { setCall(null); then(turn.text.trim().ifBlank { null }) }
                override fun onStopped(partial: String) { setCall(null); emit(Result("", staged, changed.toList(), labels, false, stopped = true)) }
                override fun onError(message: String, needsSettings: Boolean, toolsRejected: Boolean) {
                    setCall(null)
                    note("The advisor could not answer (" + message.take(160) + "). Carrying on without it.", Ui.MUTED)
                    then(null)
                }
            }, false)
        } finally { settings.override = saved }
        setCall(cur)
    }

    private var cuts = 0
    private var emptyReplies = 0
    private var rescued = false

    /**
     * A fix changed nothing: the same problem came back. Repeating the same plan only spends money, so ask the advisor
     * (when one is set) for a different approach and allow one more try; otherwise stop and say what the person can do.
     */
    private fun stuck(text: String, problem: String) {
        val plan = advisor
        if (!rescued && plan != null && advisorUsed < plan.maxCalls) {
            rescued = true
            note("The same problem came back after a fix. Asking the advisor for a different approach before one more try.", Ui.ACCENT)
            consult(dev.pocketide.app.core.Advisor.Purpose.HELP) { adv ->
                if (adv == null) { giveUp(text, problem); return@consult }
                chip("Advice", adv)
                goBack(text, "the same problem came back after a fix", "Your last fix did not change the result; the same problem came back:\n$problem\n" +
                    "Do not repeat that fix. A reviewer suggests:\n$adv\nTry a different approach, then check the result again" + (if (look != null) " (use look_at_preview for anything visual)." else "."))
            }
            return
        }
        giveUp(text, problem)
    }

    private fun giveUp(text: String, problem: String) {
        stopReason = "the same problem came back after a fix"
        note("Stopped: the same problem came back after a fix, so I stopped instead of spending more on it.\n$problem\n$NEXT_STEPS", Ui.BAD)
        emit(Result(text, staged, changed.toList(), labels, false))
    }

    private fun handle(first: ToolTurn) {
        if (first.thinking.isNotBlank()) {
            dev.pocketide.app.core.WorkLog.add(workLog, dev.pocketide.app.core.WorkLog.Kind.THINK, first.thinking, first.thinkSecs)
            keepThought(workLog.last()); logged()
            dev.pocketide.app.core.ToolTrace.write(dev.pocketide.app.core.ToolTrace.block("assistant thought", workLog.last().text, round))
        }
        // a reply that ran out of room leaves its last tool call unfinished: drop that call and ask for smaller pieces
        val broken = first.calls.filter { !dev.pocketide.app.core.OutputLimit.validArgs(it.arguments) }
        badCalls += broken.size
        val cut = dev.pocketide.app.core.OutputLimit.isCut(first.stop) || broken.isNotEmpty()
        var cutNote: String? = null
        var turn = if (broken.isEmpty()) first else first.copy(calls = first.calls.filter { it !in broken })
        if (cut) {
            cuts++
            // The reply length is NOT lowered: a lower limit only makes the next long reply get cut sooner. The model is asked to
            // send the lost work in smaller pieces instead.
            if (cuts > 6) {
                emit(Result(first.text, staged, changed.toList(), labels, false, error = "The model keeps running out of room in one reply. Try a model with a longer output limit, or ask for the work in smaller steps."))
                return
            }
            cutNote = dev.pocketide.app.core.OutputLimit.cutNote(broken.map { b -> b.name + dev.pocketide.app.core.OutputLimit.pathOf(b.arguments).let { if (it.isEmpty()) "" else " $it" } }, paths())
            note("The reply ran past the model's length limit; asking it to send the rest in smaller pieces ($cuts).", Ui.ACCENT)
        }
        if (turn.calls.isEmpty() && turn.text.isBlank()) {
            when {
                cutNote != null -> { messages.add(Tools.assistantText("Working on it.")); messages.add(Tools.userText(anthropic, cutNote)); request() }
                changed.isNotEmpty() -> { note("The model finished without a closing message.", Ui.MUTED); finish("Done: ${changed.size} file${if (changed.size == 1) "" else "s"} changed.") }
                emptyReplies < 2 -> { emptyReplies++; note("The model sent an empty reply" + (if (first.stop.isNotBlank()) " (stop reason: ${first.stop})" else "") + "; asking it to continue ($emptyReplies of 2).", Ui.ACCENT); messages.add(Tools.assistantText("Working on it.")); messages.add(Tools.userText(anthropic, dev.pocketide.app.core.OutputLimit.EMPTY_NOTE)); request() }
                else -> emit(Result("", staged, changed.toList(), labels, false, error = "The model returned an empty reply three times" + (if (first.stop.isNotBlank()) " (stop reason: ${first.stop})" else "") + ". Try another model or tap Retry."))
            }
            return
        }
        // A tool call written as text (read_file(path="a.js"), {"name": …, "parameters": …}): run the first one as if it had been
        // called. Llama 3.3 on Cloudflare did this in most replies and ignored the ask to call the tool (hard suite, 1.5.89).
        if (turn.calls.isEmpty() && !textMode && cutNote == null && wrapAt < 0 && round < maxRounds) {
            dev.pocketide.app.core.TextCalls.first(turn.text) { Tools.paramTypes(it) }?.let { hit ->
                textCallsRun++
                badCalls++
                if (textCallsRun == 1) note("The model wrote a tool call as text instead of calling it; running it as a call (${hit.call.name}).", Ui.ACCENT)
                dev.pocketide.app.core.ToolTrace.write(dev.pocketide.app.core.ToolTrace.event("tool call written as text; ran ${hit.call.name}"))
                // what the model wrote after the call guessed at results it had not seen, so it is not kept
                // 1.5.176: the call stays written in the reply as well. Ollama's qwen2.5 template shows an assistant message's text or
                // its tool calls, not both, so the model never saw what it had called and searched for "SAVE10" five times.
                turn = turn.copy(text = (turn.text.substring(0, hit.at).trim() + "\n" + dev.pocketide.app.core.MiniJson.write(mapOf("name" to hit.call.name, "arguments" to (dev.pocketide.app.core.MiniJson.obj(dev.pocketide.app.core.MiniJson.parse(hit.call.arguments)) ?: emptyMap<String, Any?>())))).trim(), calls = listOf(hit.call))
                cutNote = Tools.textCallRan(hit.call.name, textCallsRun)
            }
        }
        if (turn.text.isNotBlank()) body.text = turn.text.trimStart()
        if (turn.calls.isEmpty()) {
            if (cutNote != null) { messages.add(Tools.assistantText(turn.text)); messages.add(Tools.userText(anthropic, cutNote)); request(); return }
            // 1.5.184 (local benchmark run 1): the final answer never reached the chat log, so a right answer looked like none
            if (turn.text.isNotBlank()) dev.pocketide.app.core.ToolTrace.write(dev.pocketide.app.core.ToolTrace.block("assistant said", turn.text.trim(), round))
            finish(turn.text); return
        }
        if ((wrapAt >= 0 && round >= wrapAt + 2) || round >= maxRounds + 2) {
            // the model ignored the request to wrap up; stop here rather than loop
            val why = Rounds.stoppedNote(round, settings.maxRounds, roundCap, wrapWhy)
            stopReason = "the model kept calling tools after being asked to wrap up at round $wrapAt" + (if (wrapWhy.isEmpty()) "" else " ($wrapWhy)")
            note(why, Ui.MUTED)
            finish(turn.text.ifBlank { why })
            return
        }
        if (turn.text.isNotBlank()) { said.add(turn.text.trim()); dev.pocketide.app.core.WorkLog.add(workLog, dev.pocketide.app.core.WorkLog.Kind.SAY, turn.text); logged(); keepCommentary(turn.text.trim()); dev.pocketide.app.core.ToolTrace.write(dev.pocketide.app.core.ToolTrace.block("assistant said", turn.text.trim(), round)) }
        round++
        val plans = turn.calls.filter { it.name == "update_plan" }.map { Tools.planSteps(it).toString() }
        plans.lastOrNull()?.let { shownPlan = it }
        turn.calls.lastOrNull { it.name == "update_plan" }?.let { lastPlan = Tools.planSteps(it) }
        messages.add(if (textMode) Tools.assistantText(lastRaw) else Tools.assistantCalls(anthropic, turn.text, turn.calls))
        val results = ArrayList<Pair<ToolCall, String>>()
        fun next(i: Int) {
            if (i >= turn.calls.size) {
                if (turn.calls.any { isMutating(it) }) onRound?.invoke(round)
                if (textMode) messages.add(Tools.userText(anthropic, dev.pocketide.app.core.TextTools.results(results) + if (small) "\n\n" + dev.pocketide.app.core.SmallLocal.next(smallState(results)) else ""))
                else messages.addAll(Tools.results(anthropic, results))
                if (!question && !readOnly() && !researchRequest) stuck.endRound() else stuck.changed()
                // rounds that only run checks after a check already passed (Proof.checkedTooLong)
                afterPassChecks = when {
                    turn.calls.any { it.name in FILE_CHANGES } -> 0
                    turn.calls.isNotEmpty() && turn.calls.all { it.name == "run_command" || it.name == "look_at_preview" || it.name == "update_plan" } &&
                        dev.pocketide.app.core.Proof.passedBefore(proofChecks, writeMark, round) -> afterPassChecks + 1
                    else -> afterPassChecks
                }
                // 1.5.170 (SmallTools): two calls that could not run or edits that did not apply: the rest of the run gets a few plain tools
                slips += results.count { (_, r) -> dev.pocketide.app.core.SmallTools.slipped(r) }
                if (!narrowed && !nested && agent && Tools.only == null && dev.pocketide.app.core.SmallTools.due(slips + badCalls)) {
                    narrowed = true
                    val set = dev.pocketide.app.core.SmallTools.set(look != null, projects != null)
                    Tools.only = set
                    trace("small tool set: ${slips + badCalls} slip(s); offering only " + set.sorted().joinToString())
                    note("The model is struggling with the full tool set; giving it a few plain tools for the rest of this request.", Ui.MUTED)
                    messages.add(Tools.userText(anthropic, dev.pocketide.app.core.SmallTools.NOTE))
                }
                if (shots.isNotEmpty()) { messages.add(Tools.userText(anthropic, "Screenshot of the page from look_at_preview (what the player sees):", shots.toList())); shots.clear() }
                cutNote?.let { messages.add(Tools.userText(anthropic, it)) }
                if (agent && round > 0 && round % REQUEST_REMINDER_EVERY == 0 && request.isNotBlank())
                    messages.add(Tools.userText(anthropic, dev.pocketide.app.core.RequestReminder.text(request, steers)))
                if (agent && !nested && !question && !reportOnly && !lowNoted && !Rounds.isAutonomous(settings.maxRounds) && round >= Rounds.lowAt(maxRounds) && round < maxRounds) {
                    lowNoted = true
                    messages.add(Tools.userText(anthropic, Rounds.lowNote(maxRounds - round) + scopeTail()))
                }
                val reason = wrapReason()
                if (reason != null && wrapAt < 0) {
                    wrapAt = round
                    stopReason = reason
                    wrapWhy = reason
                    messages.add(Tools.userText(anthropic, dev.pocketide.app.core.StopNote.wrapAsk(reason, changed.isEmpty() && staged.isEmpty()) + scopeTail()))
                    chip("Stopped working", Rounds.wrapNote(reason, settings.maxRounds, roundCap))
                }
                request()
                return
            }
            if (steerPending()) {
                // the person wrote while this round ran: skip what is left (every call still gets a result) and read the message now
                trace("steer: skipped ${turn.calls.size - i} call(s) to read the person's message")
                results.addAll(dev.pocketide.app.core.Steer.skipRest(turn.calls, i))
                next(turn.calls.size)
                return
            }
            val c = turn.calls[i]
            toolCalls++
            if (c.name in FILE_CHANGES) editTries++
            // 1.5.147 (CauseGate): in a problem report, the first change waits until a cause is named, once
            if (c.name in FILE_CHANGES && agent && !nested && settings.causeFirst && !causeAsked && changed.isEmpty() &&
                dev.pocketide.app.core.Habits.reportsProblem(request) && !dev.pocketide.app.core.CauseGate.stated(saidSoFar() + "\n" + turn.text) { p -> store.exists(p) || paths().any { it.substringAfterLast('/') == p } }) {
                causeAsked = true
                trace("cause first: held back the first change until a cause is named")
                note("Asked the model to name the cause before its first change.", Ui.MUTED)
                results.add(c to dev.pocketide.app.core.CauseGate.ASK)
                next(i + 1)
                return
            }
            execute(c) { r ->
                results.add(c to Tools.cap(r, resultCap()))
                next(i + 1)
            }
        }
        next(0)
    }

    /** Why the model should stop calling tools now, or null. Autonomous mode adds a time limit and a no-progress check to the round ceiling. */
    private fun wrapReason(): String? = when {
        round >= maxRounds -> "$round rounds used"
        stuck.reason != null -> "the run was going in circles: ${stuck.reason}"
        agent && dev.pocketide.app.core.Proof.checkedTooLong(afterPassChecks, tracker.unfinished().size) -> "a check passed after the last change, and ${dev.pocketide.app.core.Proof.AFTER_PASS_ROUNDS} more rounds changed nothing"
        Rounds.timeLimitReached(runningMs(), settings.maxRounds) -> "$timeLimitMinutes minutes used"
        hardLimitMs > 0 && runningMs() >= hardLimitMs * HARD_LIMIT_WRAP_PERCENT / 100 ->
            "${runningMs() / 1000} seconds used of a ${hardLimitMs / 1000}-second limit"
        else -> null
    }

    private fun finish(text0: String) {
        if (steerPending()) { messages.add(Tools.assistantText(text0.ifBlank { "Working on it." })); request(); return }
        // A file printed as JSON text ({"path":…,"contents":…}) is junk: keep it out of the history and out of the chat
        val dump = changed.isEmpty() && staged.isEmpty() && Tools.jsonFileDump(text0)
        val text = if (dump && filesAsTextAsked) Tools.JSON_DUMP_SHOWN else text0
        if (dump && filesAsTextAsked) {
            val m = settings.model(settings.provider).ifBlank { Providers.byId(settings.provider).model }
            dev.pocketide.app.core.ModelFacts.markUnreliable(settings.provider, m, "printed files as text instead of using tools")
        }
        // A tool call written out as text did not run: ask once for the real call (seen with gpt-oss tokens and JSON tool_calls)
        if (!nested && !toolTextAsked && Tools.toolCallAsText(text0) && round < maxRounds && wrapAt < 0) {
            toolTextAsked = true
            badCalls++
            note("The model wrote a tool call as text, so it did not run; asking it to call the tool.", Ui.ACCENT)
            dev.pocketide.app.core.ToolTrace.write(dev.pocketide.app.core.ToolTrace.event("tool call written as text; asked for the real call"))
            goBack(text0, "a tool call was written as text", if (small) dev.pocketide.app.core.SmallLocal.ASK else if (textTools) dev.pocketide.app.core.TextTools.ASK else Tools.TOOL_TEXT_ASK)
            return
        }
        // File contents written as text are not saved in tool mode; ask once for the write tools instead
        if (!nested && !filesAsTextAsked && changed.isEmpty() && staged.isEmpty() && Tools.filesAsText(text0) && round < maxRounds && wrapAt < 0) {
            filesAsTextAsked = true
            note("The model wrote files as text instead of using the tools, so nothing was saved; asking it to use write_file.", Ui.ACCENT)
            goBack(if (dump) Tools.JSON_DUMP_SHOWN else text0, "files were printed as text instead of saved",
                "Nothing was saved: in this chat, files are only written through the write_file, edit_file and edit_lines tools, not as text in your reply. Call those tools now for every file you meant to write or change.")
            return
        }
        // 1.5.170 (CantDo): "that isn't possible" with nothing changed and few tools tried goes back once with the tools at hand
        val cant = if (!nested && !mustEdit && !question && !reportOnly && !cantAsked && changed.isEmpty() && staged.isEmpty() && toolCalls < 4 && round < maxRounds && wrapAt < 0) dev.pocketide.app.core.CantDo.claim(text0) else null
        if (cant != null && !stopRequested()) {
            cantAsked = true
            trace("the reply says it cannot (\"$cant\") after $toolCalls tool call(s); asking it to try the tools first")
            note("The model said it cannot do this; asking it to try its tools first.", Ui.ACCENT)
            val offered = (if (textMode) Tools.heldNames(docs) else Tools.anthropicTools(docs, toolSet()).mapNotNull { it["name"] as? String }).filter { it != "use_tool" } + Tools.heldNames(docs)
            goBack(text0, "the reply said it cannot", dev.pocketide.app.core.CantDo.ask(cant, offered.distinct().take(30)))
            return
        }
        // "To find the rate, I should look at this file." as the last words: ask once for the look and the full answer. "I will edit X"
        // with nothing changed has its own one-time ask: Llama 3.3 (hard suite) used the first ask to read a file, then stopped at "I will edit".
        val changeLeft = agent && !changeAsked && !question && !reportOnly && !readOnly() && changed.isEmpty() && staged.isEmpty() && dev.pocketide.app.core.Deferral.stopsBeforeChange(text0)
        if (!nested && !stopRequested() && round < maxRounds && wrapAt < 0 && (changeLeft || (!deferralAsked && dev.pocketide.app.core.Deferral.stopsShort(text0)))) {
            if (changeLeft) changeAsked = true else deferralAsked = true
            trace("the reply ended by naming a step it did not take; asking the model to take it")
            note("The model stopped before doing what it said it would; asking it to finish.", Ui.ACCENT)
            goBack(text0, if (changeLeft) "the reply named a change it did not make" else "the reply stopped before a needed step",
                if (changeLeft) dev.pocketide.app.core.Deferral.CHANGE_ASK else dev.pocketide.app.core.Deferral.ASK)
            return
        }
        // "Here is the change:" with nothing after it: ask for the change, at most twice and only after tools ran since the last ask
        val announced = if (agent && !question && !readOnly() && announcedAsks < 2 && round > announcedRound) dev.pocketide.app.core.Deferral.announced(text0) else null
        if (announced != null && !nested && !stopRequested() && round < maxRounds && wrapAt < 0) {
            announcedAsks++
            announcedRound = round
            trace("the reply ended by announcing something that did not follow; asking for it")
            note("The model announced a step and stopped; asking it to take the step.", Ui.ACCENT)
            goBack(text0, "the reply announced a step it did not take", dev.pocketide.app.core.Deferral.announcedAsk(announced))
            return
        }
        // Asked once already and the reply again stops at reading a file it names: read that file for the model, once
        if (!nested && deferralAsked && !readForModel && !stopRequested() && round < maxRounds && wrapAt < 0) {
            val all = paths().toHashSet()
            val p = dev.pocketide.app.core.Deferral.fileToRead(text0, all) { q -> current(q) != null }
            if (p != null) {
                readForModel = true
                trace("the reply stopped again at reading $p; reading it for the model")
                note("The model stopped again at reading $p: reading it for the model.", Ui.ACCENT)
                val shown = AgentActions.read(all, { q -> staged[q] ?: store.readForAi(q) }, p, cap = resultCap())
                goBack(text0, "the reply stopped at reading $p", dev.pocketide.app.core.Deferral.readFor(p, shown))
                return
            }
        }
        // "Run X and report": a report with nothing run is a guess ("Node is not installed", gpt-oss-120b at 1.5.88)
        if (reportOnly && !nested && !ranSomething && !runAsked && !stopRequested() && round < maxRounds && wrapAt < 0) {
            runAsked = true
            trace("run-and-report finished without running anything; asking it to run")
            note("Nothing was run yet; asking the model to run it before reporting.", Ui.ACCENT)
            goBack(text0, "a report-only request ended without running anything", dev.pocketide.app.core.Scope.RUN_FIRST)
            return
        }
        if (advisor?.mode == dev.pocketide.app.core.Advisor.AUTO && !finalAsked && changed.isNotEmpty() && wrapAt < 0) {
            finalAsked = true
            consult(dev.pocketide.app.core.Advisor.Purpose.FINISH) { adv ->
                if (adv != null && !dev.pocketide.app.core.Advisor.clear(adv)) {
                    chip("Advisor review", adv)
                    goBack(text, "the advisor's review", dev.pocketide.app.core.Advisor.finishMessage(adv))
                } else {
                    if (adv != null) note("The advisor found nothing to fix.", Ui.GOOD)
                    finishCore(text)
                }
            }
            return
        }
        finishCore(text)
    }

    private val stubs = LinkedHashMap<String, String>()
    private var stubNudged = false
    private var gameNudges = 0
    private var emptyAsked = false
    /** Tool calls made in this request (an answer to a task with none did nothing). */
    private var toolCalls = 0
    /** Calls to the file-changing tools, applied or not; asked once when none applied (noChangeAsked), and once on a failed last check. */
    private var editTries = 0
    /** Tool calls the model wrote as text that were run for it (TextCalls). */
    private var textCallsRun = 0
    private var lowNoted = false
    private var noChangeAsked = false
    private var failAsked = false
    private var renameAsked = false
    private var removeAsked = false
    private var classAsked = false
    private var toolClaimChecked = false
    private var cantAsked = false
    /** Calls in this run whose result says they could not run or did not apply (SmallTools), and whether the tool set was narrowed. */
    private var slips = 0
    private var narrowed = false
    /** Writes one line to the chat's full record, so a check's result can be read later. */
    /** Everything the model has written to the person so far in this run (its text between tool calls). */
    private fun saidSoFar(): String = messages.filter { it["role"] == "assistant" }.joinToString("\n") { m ->
        when (val c = m["content"]) { is String -> c; is List<*> -> c.joinToString("\n") { (it as? Map<*, *>)?.get("text")?.toString().orEmpty() }; else -> "" }
    }

    private fun trace(event: String) { dev.pocketide.app.core.ToolTrace.write(dev.pocketide.app.core.ToolTrace.event(event)) }

    /**
     * 1.5.185: the card for an app_settings proposal. Nothing changes until the person taps Apply; during a benchmark the card
     * declines itself, so a run never changes the phone's settings, and the decision is written to the chat's record.
     */
    private val RENAME = Regex("""(?i)\brename\b""")

    private fun settingsCard(c: dev.pocketide.app.core.AppSettingsTool.Check.Ok, now: String, why: String) {
        val tool = dev.pocketide.app.core.AppSettingsTool
        val card = Ui.vertical(ctx)
        card.background = Ui.rounded(Ui.SURFACE2, 10, ctx, Ui.ACCENT)
        card.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 10), Ui.dp(ctx, 12), Ui.dp(ctx, 10))
        card.addView(Ui.text(ctx, "Change a setting?", 14f, Ui.ACCENT))
        card.addView(Ui.text(ctx, c.item.label + "\n" + now + " → " + c.shown, 14f, Ui.TEXT))
        card.addView(Ui.text(ctx, c.item.where + (if (why.isNotBlank()) "\n" + why.take(300) else ""), 12f, Ui.MUTED))
        val status = Ui.text(ctx, "", 12f, Ui.MUTED)
        if (HybridBench.running) {
            status.text = "Declined automatically: a benchmark is running, so settings stay as they are."
            trace(tool.record(c, "declined automatically: benchmark"))
        } else {
            val row = Ui.horizontal(ctx)
            row.addView(Ui.button(ctx, "Apply", true) {
                val err = AppSettingsBridge.apply(ctx, settings, c.item.setting!!, c.value)
                row.visibility = View.GONE
                status.text = err?.let { "Not changed: $it." } ?: "Applied: ${c.item.label} is ${c.shown}."
                trace(tool.record(c, if (err == null) "applied by the person" else "failed: $err"))
            })
            row.addView(Ui.button(ctx, "No") { row.visibility = View.GONE; status.text = "Not changed."; trace(tool.record(c, "declined by the person")) },
                Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 8) })
            card.addView(row, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 8) })
        }
        card.addView(status)
        box.addView(card, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 8) })
        scrollDown()
    }
    private var gameCheckedAt = -1
    /** writeMark when the saved play tests last all passed, and how often failures were sent back. */
    private var playMark = -1
    private var playNudges = 0
    private val playRepeats = dev.pocketide.app.core.PlayTests.Repeats()

    /**
     * Plays the saved tests in tests/play.txt ([which]: "all" or part of a name), each on a fresh page, one after another.
     * Calls back with the combined report, a screenshot of the first failing test (or the last one) and whether all passed.
     */
    private fun playSaved(which: String, done: (String, String?, Boolean) -> Unit) {
        val lk = look ?: run { done("There is no page to test in this project.", null, false); return }
        val text = store.read(dev.pocketide.app.core.PlayTests.FILE) ?: run { done("No saved play tests: write ${dev.pocketide.app.core.PlayTests.FILE} first (a \"# feature\" line, then steps with expect or until).", null, false); return }
        val tests = dev.pocketide.app.core.PlayTests.pick(dev.pocketide.app.core.PlayTests.parse(text), which)
        if (tests.isEmpty()) { done("No saved play test matches '$which' in ${dev.pocketide.app.core.PlayTests.FILE}.", null, false); return }
        val results = ArrayList<Pair<String, String>>(); var shot: String? = null; var failShot: String? = null
        fun next(i: Int) {
            if (i >= tests.size || stopRequested()) {
                val all = results.size == tests.size && results.all { dev.pocketide.app.core.PlayTests.passed(it.second) }
                done(dev.pocketide.app.core.PlayTests.summary(results) + playRepeats.notes(results), failShot ?: shot, all); return
            }
            val t = tests[i]
            phase("Play test ${i + 1} of ${tests.size}: ${t.name}")
            lk(t.steps, t.page, "") { report, img ->
                results.add(t.name to report); shot = img ?: shot
                if (failShot == null && img != null && !dev.pocketide.app.core.PlayTests.passed(report)) failShot = img
                next(i + 1)
            }
        }
        next(0)
    }

    private fun finishCore(reply0: String) {
        // 1.5.164 (PremiseCheck): a report_finding is the answer; what the model wrote after it is added below
        val text = finding?.let { f -> reply0.trim().let { r -> if (r.isEmpty() || r == "Reported." || f.contains(r)) f else f + "\n\n" + r } } ?: reply0
        if (stopRequested()) { stopReason = "the person pressed Stop"; emit(Result(text, staged, changed.toList(), labels, false, stopped = true)); return }
        // 1.5.164, the repair turn: Aider's retry always edits; ours ended with no change when it could not find the tests (rest-api)
        if (agent && mustEdit && !nested && !mustEditAsked && changed.isEmpty() && round < maxRounds) {
            mustEditAsked = true
            trace("repair turn ended without an edit; asking for the fix")
            goBack(text.ifBlank { "No change yet." }, "a repair turn with no edit", dev.pocketide.app.core.RepairTurn.MUST_EDIT)
            return
        }
        // A request that asks for work, answered without a single tool call, did nothing: ask once for the work itself.
        // Not when the run is read-only (1.5.127): it cannot do the work, so asking for it only costs a round and invites refusals.
        if (agent && !readOnly() && !nested && !emptyAsked && !question && toolCalls == 0 && changed.isEmpty() && round < maxRounds && wrapAt < 0 &&
            dev.pocketide.app.core.Router.route(request).category != dev.pocketide.app.core.Router.Category.QUESTION) {
            emptyAsked = true
            trace("the model answered a task without using any tools; asking it to do the work")
            note("The model answered without doing anything: asking it to do the task.", Ui.ACCENT)
            goBack(text.ifBlank { "I will look into it." }, "no tool was used", "You have not used any tools in this request, so nothing was looked at, changed or checked. The request asks for work: \"" +
                job.take(400) + "\". Use the tools now: read what you need, make the change, and check it. If something stops you, say exactly what it is.")
            return
        }
        // Nothing changed and the reply ends the task: edits were tried and none applied (Llama 3.3 at 1.5.89: "renamed everywhere"
        // after 0 of 3 edits applied to files it guessed), or none was tried and the reply still ends at a step it only names, after the
        // one-time deferral ask (Llama 3.3, hard suite 1.5.90 candidate: "To fix it, I will first read the test file.")
        val stoppedShort = editTries == 0 && dev.pocketide.app.core.Deferral.stopsShort(text)
        if (agent && !nested && !noChangeAsked && !question && !reportOnly && !readOnly() && (editTries > 0 || stoppedShort) && changed.isEmpty() && staged.isEmpty() && round < maxRounds && wrapAt < 0) {
            noChangeAsked = true
            trace(if (stoppedShort) "nothing changed and the reply ends at a step not taken; asking the model to make the change" else "edits were tried but none applied; asking the model to make the change")
            note(if (stoppedShort) "Nothing has changed and the model stopped at a step it only named: asking it to make the change." else "None of the model's edits were applied, so nothing changed: asking it to make the change.", Ui.ACCENT)
            goBack(text.ifBlank { "Working on it." }, if (stoppedShort) "nothing was changed" else "no edit was applied",
                (if (stoppedShort) "No file has changed yet, and your reply ends at a step you have not taken. " else "No file has changed: every edit you tried in this request failed (see their results). ") +
                "The request still asks: \"" + job.take(400) + "\". Find the right files (list_files, search), read them, make the edits, and check them. " +
                "If no change is needed after all, say why.")
            return
        }
        // "Node.js is not installed": a claim the app can check itself, once, with a read-only command (ToolClaim)
        // not in a turn that cannot run commands (the repair turn, RepairTurn.TOOLS): there "use it" contradicts the turn (Copilot PR #29)
        if (agent && !nested && !toolClaimChecked && round < maxRounds && wrapAt < 0 && canRunCommands()) {
            val tool = dev.pocketide.app.core.ToolClaim.missing(text)
            val probe = tool?.let { dev.pocketide.app.core.ToolClaim.probe(it) }
            if (tool != null && probe != null && decide(probe) == dev.pocketide.app.core.CommandRules.Decision.RUN) {
                toolClaimChecked = true
                exec(probe) { code, out ->
                    if (code == 0 && out.isNotBlank() && !stopRequested()) {
                        trace("the reply says $tool is not installed, but `$probe` finds it; sending it back")
                        note("The model says $tool is not installed, but it is: asking it to use it.", Ui.ACCENT)
                        goBack(text, "$tool is installed", dev.pocketide.app.core.ToolClaim.ask(tool, out))
                    } else finishCore(text)
                }
                return
            }
        }
        // Proof before "done": a summary that says it works, with nothing run after the last change that shows it, goes back once.
        // (When the app will look at the page itself below, that look is the check.)
        if (agent && !nested && !proofAsked && changed.isNotEmpty() && !docsOnly() && round < maxRounds && wrapAt < 0 && canRunCommands() &&
            !(look != null && dev.pocketide.app.core.PageProbe.relevant(changed))) {
            val said = dev.pocketide.app.core.Proof.claims(text)
            if (said.isNotEmpty() && !dev.pocketide.app.core.Proof.runtimeSince(proofChecks, writeMark)) {
                proofAsked = true
                trace("summary claims \"" + said.joinToString("\", \"") + "\" with no check after the last change; asking for proof")
                note("The summary says \"${said.first()}\" but nothing that ran shows it: asking the model to check or say so.", Ui.ACCENT)
                goBack(text, "the summary claims more than any check showed", dev.pocketide.app.core.Proof.nudge(said, proofChecks, writeMark))
                return
            }
        }
        // The last check after the last change failed and the reply ends there: ask once to fix it, or to say why it fails
        if (agent && !nested && !failAsked && !question && !reportOnly && changed.isNotEmpty() && round < maxRounds && wrapAt < 0) {
            val failed = dev.pocketide.app.core.Proof.lastFailed(proofChecks, writeMark)
            if (failed != null) {
                failAsked = true
                trace("the last check after the last change failed (${failed.what}); asking the model to fix it")
                note("The last check failed (${failed.what}): asking the model to fix it before finishing.", Ui.ACCENT)
                goBack(text.ifBlank { "Fixing the failure." }, "the last check failed", dev.pocketide.app.core.Proof.failedAsk(failed))
                return
            }
        }
        // "Rename X to Y": the old name still in the project after the edits goes back once, with the places (RenameCheck)
        if (agent && !nested && !renameAsked && !question && !reportOnly && changed.isNotEmpty() && round < maxRounds && wrapAt < 0) {
            val r = dev.pocketide.app.core.RenameCheck.asked(steers.lastOrNull() ?: request)
            if (r != null) {
                // 1.5.184 (local run 3, l18): test.js must keep naming the old name ("…, not getScore"); it is the check, not code to rename
                val (lines, count) = dev.pocketide.app.core.RenameCheck.left(r.old, paths() - dev.pocketide.app.core.Scope.namedChecks(request)) { current(it) }
                if (count > 0) {
                    renameAsked = true
                    trace("the rename of ${r.old} left $count place(s); asking the model to finish it")
                    note("${r.old} still appears in $count place(s): asking the model to finish the rename.", Ui.ACCENT)
                    goBack(text.ifBlank { "Finishing the rename." }, "the old name is still used", dev.pocketide.app.core.RenameCheck.ask(r, lines, count))
                    return
                }
            }
        }
        // "Remove the coupon feature": the removed thing still mentioned after the edits goes back once, with the places (RemoveCheck, 1.5.175)
        if (agent && !nested && !removeAsked && !question && !reportOnly && changed.isNotEmpty() && round < maxRounds && wrapAt < 0) {
            val st = dev.pocketide.app.core.RemoveCheck.asked(steers.lastOrNull() ?: request)
            if (st != null) {
                val (lines, count) = dev.pocketide.app.core.RemoveCheck.left(st, paths()) { current(it) }
                if (count > 0) {
                    removeAsked = true
                    trace("the removal of $st left $count mention(s); asking the model to finish it")
                    note("\"$st\" is still mentioned in $count place(s): asking the model to finish the removal.", Ui.ACCENT)
                    goBack(text.ifBlank { "Finishing the removal." }, "the removed feature is still mentioned", dev.pocketide.app.core.RemoveCheck.ask(st, lines, count))
                    return
                }
            }
        }
        // A class the change adds or toggles that nothing styles: the button works but nothing on screen changes; asked once (ClassCheck)
        if (agent && !nested && !classAsked && !question && !reportOnly && changed.isNotEmpty() && round < maxRounds && wrapAt < 0) {
            val misses = dev.pocketide.app.core.ClassCheck.unstyled(paths(), changed) { current(it) }
            if (misses.isNotEmpty()) {
                classAsked = true
                val names = misses.joinToString(", ") { "." + it.cls }
                trace("no style rule for $names; asking the model to add one")
                note("Nothing styles $names: asking the model to add the styles.", Ui.ACCENT)
                goBack(text.ifBlank { "Adding the styles." }, "a class the change sets has no style rule", dev.pocketide.app.core.ClassCheck.ask(misses))
                return
            }
        }
        // Names a written script reads but nothing defines: the line throws when it runs, which no syntax check catches; asked once
        if (agent && !nested && !namesAsked && !question && !reportOnly && round < maxRounds && wrapAt < 0) {
            val left = undefinedLeft.filterKeys { it in changed && store.read(it) != null }
            if (left.isNotEmpty()) {
                namesAsked = true
                trace("undefined names left: " + left.values.flatten().joinToString { it.name })
                note("Names nothing defines are still used: asking the model to fix them.", Ui.ACCENT)
                goBack(text.ifBlank { "Fixing names nothing defines." }, "a script reads names nothing defines",
                    dev.pocketide.app.core.UndefinedNames.ask(left) { p, n -> dev.pocketide.app.core.UndefinedNames.definedIn(n, p, paths()) { current(it) } })
                return
            }
        }
        // The person said errors go unnoticed and the change still throws one away in an empty handler; asked once
        if (agent && !nested && !swallowAsked && !question && !reportOnly && round < maxRounds && wrapAt < 0 &&
            dev.pocketide.app.core.SwallowedErrors.complainsOfSilence(request)) {
            val left = swallowLeft.filterKeys { it in changed && store.read(it) != null }
            if (left.isNotEmpty()) {
                swallowAsked = true
                trace("empty error handlers left: " + left.keys.joinToString())
                note("An error is still thrown away: asking the model to let it through.", Ui.ACCENT)
                goBack(text.ifBlank { "Letting the error through." }, "an error handler throws the error away", dev.pocketide.app.core.SwallowedErrors.ask(left))
                return
            }
        }
        // Files written as stand-ins: ask once for the real code before the work is called done.
        refreshStubs()
        if (stubs.isNotEmpty() && !stubNudged && round < maxRounds && wrapAt < 0) {
            stubNudged = true
            note("Unfinished files (${stubs.keys.joinToString()}): asking the model to complete them.", Ui.ACCENT)
            goBack(text, "unfinished files: " + stubs.keys.take(3).joinToString(", "), dev.pocketide.app.core.StubCheck.request(LinkedHashMap(stubs)))
            return
        }
        // A game folder that was changed, and the page the preview opens, must pass the game check before the work is called done.
        // Problems that break the game go back to the model (up to twice); if they remain, the request ends as not finished.
        if (agent && !nested && gameCheckedAt != writeMark) {
            val all = paths()
            val dirs = dev.pocketide.app.core.GameCheck.dirsFor(all, changed) { store.exists(it) }
            val entry = dev.pocketide.app.core.GameCheck.entryPage(all)?.takeIf { e ->
                dev.pocketide.app.core.PageProbe.relevant(changed) && dirs.none { d -> d.isEmpty() || e.startsWith("$d/") } }
            if (dirs.isNotEmpty() || entry != null) {
                // with no game folder this checks only the files and links of the page the preview opens (a plain web page)
                val what = if (dirs.isEmpty()) "the app's link check of $entry" else "the app's game check"
                phase(if (dirs.isEmpty()) "Checking the page's files and links" else "Checking the game")
                McpClient.background {
                    val found = ArrayList<String>()
                    for (d in dirs) {
                        val files = store.filesUnder(d).map { it.removePrefix("$d/") }
                        val r = try { dev.pocketide.app.core.GameCheck.check(files, { f -> store.read(if (d.isEmpty()) f else "$d/$f") }, { f -> store.size(if (d.isEmpty()) f else "$d/$f") }) } catch (e: Throwable) { null }
                        if (r != null && r.contains("problem(s)")) found.add("In ${d.ifEmpty { "the project" }}: $r")
                    }
                    if (entry != null) {
                        val pp = try { dev.pocketide.app.core.GameCheck.pageProblems(entry, all, { store.read(it) }) } catch (e: Throwable) { emptyList() }
                        if (pp.isNotEmpty()) found.add("In $entry, the page the preview opens:\n" + pp.joinToString("\n") { "- $it" })
                    }
                    McpClient.onMain {
                        val blocking = found.flatMap { dev.pocketide.app.core.GameCheck.blocking(it) }
                        proofChecks.add(dev.pocketide.app.core.Proof.Check(what, blocking.isEmpty(), round, false))   // files and links, not the game running
                        if (blocking.isEmpty()) {
                            gameCheckedAt = writeMark
                            trace(if (found.isEmpty()) "game check passed" else "game check passed (only unused files left)")
                            if (found.isNotEmpty()) note("Game check: the game loads; some files are not used by anything.", Ui.MUTED)
                            finishCore(text); return@onMain
                        }
                        if (gameNudges < 2 && round < maxRounds && wrapAt < 0) {
                            gameNudges++
                            trace("game check found ${blocking.size} problem(s); asking the model to fix them ($gameNudges of 2)")
                            note("The game check found ${blocking.size} problem(s): asking the model to fix them before finishing.", Ui.ACCENT)
                            goBack(text.ifBlank { "Checking the game." }, "the game check found ${blocking.size} problem(s)", (if (dirs.isEmpty())
                                "Not done: $what found problems in files the page loads. Fix every one. Do not say the page works until they are fixed.\n\n" else
                                "The game is not done: the app ran the game check and it found problems. Fix every one, then run game_cache action=check again. " +
                                "If a change of yours broke a kit file and you cannot fix it, put it back with game_cache action=restore. Do not say the game works until the check passes.\n\n") + found.joinToString("\n\n"))
                            return@onMain
                        }
                        gameCheckedAt = writeMark
                        trace("not finished: the game check still finds ${blocking.size} problem(s)")
                        dev.pocketide.app.core.AppLog.add("check", "not finished: the game check still finds ${blocking.size} problem(s)")
                        note("Not finished: the game check still finds ${blocking.size} problem(s) after the fixes:\n" + blocking.take(8).joinToString("\n"), Ui.BAD)
                        emit(Result(text.ifBlank { "" } + "\n\nNot finished: $what still finds these problems:\n" + blocking.take(10).joinToString("\n"),
                            staged, changed.toList(), labels, false))
                    }
                }
                return
            }
        }
        val nudge = if (round < maxRounds && wrapAt < 0) tracker.reminder() else null
        if (nudge != null) {
            note("Not finished (${tracker.progress()}): asking the model to continue.", Ui.ACCENT)
            goBack(text.ifBlank { "Working on it." }, "the plan has ${tracker.unfinished().size} unfinished step(s)", nudge + scopeTail())
            return
        }
        // Saved play tests: when code changed since they last passed, play them all again; failures go back (up to twice).
        if (agent && !nested && look != null && changed.isNotEmpty() && playMark < writeMark && playNudges < 2 && round < maxRounds && wrapAt < 0 && store.exists(dev.pocketide.app.core.PlayTests.FILE)) {
            playSaved("all") { report, img, ok ->
                appCheck("play tests", ok)
                if (ok) {
                    playMark = writeMark; checkMark = round; promoteChecked()
                    note("Play tests: all passed.", Ui.GOOD)
                    finishCore(text)
                } else {
                    playNudges++
                    note("Play tests failed: asking the model to fix the game or app.", Ui.ACCENT)
                    val m = settings.model(settings.provider).ifBlank { Providers.byId(settings.provider).model }
                    val sees = img != null && dev.pocketide.app.core.Images.providerSees(settings.provider, m)
                    val ask = "Not done yet: the app played the saved tests and some failed. Fix what they show (change a test only if the request itself changed), then run look_at_preview test=all.\n\n$report"
                    goBack(text, "saved play tests failed", ask, if (sees) listOf(img!!) else emptyList())
                }
            }
            return
        }
        // Verification gate: files were changed but nothing ran after the last change. Ask once for a check before finishing.
        // (Only in agent mode: in review mode the changes are staged, not on disk, so commands and the page would test the old files.)
        if (agent && !nested && changed.isNotEmpty() && !docsOnly() && checkMark < writeMark && !verifyNudged && round < maxRounds && wrapAt < 0 &&
            Tools.only.let { it == null || "run_command" in it || "look_at_preview" in it }) {
            verifyNudged = true
            note("Not checked yet: asking the model to test its changes before finishing.", Ui.ACCENT)
            // 1.5.127: a web page is checked with look_at_preview first. In the emulator test Kimi spent 18 minutes on a snake game
            // trying python3 http.server, which, curl and grep (none of which can load the page) after the old wording put run_command first.
            val web = look != null && changed.any { dev.pocketide.app.core.VerifyHint.isWeb(it) }
            goBack(text.ifBlank { "Checking my changes." }, "nothing was checked after the last change", dev.pocketide.app.core.VerifyHint.ask(web, look != null))
            return
        }
        // 1.5.175: the checks after the last change only printed results (Proof.printOnly). Asked once to turn them into checks that can fail.
        if (agent && !nested && changed.isNotEmpty() && !printNudged && round < maxRounds && wrapAt < 0 &&
            dev.pocketide.app.core.Proof.onlyPrinted(proofChecks, writeMark)) {
            printNudged = true
            note("Only printed results: asking the model to check them with assertions.", Ui.ACCENT)
            goBack(text.ifBlank { "Checking my changes." }, "the checks after the last change only printed results", dev.pocketide.app.core.Proof.printedAsk(proofChecks, writeMark))
            return
        }
        // 1.5.138, the ask checklist (AskLedger): a change request with two or more asks is checked ask by ask before it is called
        // done; asks with no proof (a path:line or a command that shows it) go back once, by name.
        // 1.5.139: the checklist has its own two slots (the list, and one send-back) outside the shared finish budget, and the list is
        // never the person's answer: 1.5.138 finished with the bare "ASKS" list (Copilot, ANALYSIS-1.5.138-ASKS F1, F5).
        if (ledgerState == 2 && !ledgerAnswered) {
            // the reply after the send-back: its own text when files changed after it, else the summary written before the checklist
            ledgerAnswered = true
            finishCore(dev.pocketide.app.core.AskLedger.answer(text, ledgerSummary, preferReply = writeMark > ledgerAgainRound))
            return
        }
        if (agent && !nested && !question && !reportOnly && !readOnly() && changed.isNotEmpty() && round < maxRounds && wrapAt < 0 &&
            ledgerState < 2 && settings.askCheck) {
            if (ledgerState == 0) {
                // the helper model counts the asks once, when one is set; the word counter decides otherwise
                val helper = explorerSlot()?.takeIf { Tools.providerSupports(it.provider) }
                if (helper != null && !asksCounted) {
                    asksCounted = true
                    countAsks(helper) { n -> helperAsks = n; trace("the helper counted ${n ?: "no number"}; the word counter ${dev.pocketide.app.core.AskLedger.count(request)}"); finishCore(text) }
                    return
                }
                // 1.5.142: the larger count decides, so a small helper that undercounts cannot switch the checklist off
                val n = maxOf(helperAsks ?: 0, dev.pocketide.app.core.AskLedger.count(job))
                if (n >= 2) {
                    ledgerState = 1
                    ledgerSummary = text.trim()
                    trace("the request has $n asks" + (if ((helperAsks ?: 0) > dev.pocketide.app.core.AskLedger.count(request)) " (counted by the helper)" else "") + "; asking for the ask-by-ask check")
                    note("Checking the work against each ask in the request.", Ui.MUTED)
                    goBack(text.ifBlank { "Done." }, "the request has several asks", dev.pocketide.app.core.AskLedger.ask(job), counted = false)
                    return
                }
                ledgerState = 3   // one ask: no checklist
            }
        }
        if (ledgerState == 1) {
            val lines = dev.pocketide.app.core.AskLedger.parse(text)
            val open = lines.orEmpty().filter { !dev.pocketide.app.core.AskLedger.proven(it) }
            ledgerState = 2
            if (open.isNotEmpty() && round < maxRounds && wrapAt < 0) {
                trace("${open.size} of ${lines!!.size} asks have no proof; sending them back")
                note("${open.size} ask(s) have no proof yet: asking the model to do them or show where they are done.", Ui.ACCENT)
                ledgerAgainRound = round
                goBack(text, "${open.size} ask(s) have no proof", dev.pocketide.app.core.AskLedger.again(open), counted = false)
                return
            }
            ledgerAnswered = true
            if (lines != null && open.isEmpty()) note("Checked ${lines.size} asks: each one shows where it is done.", Ui.MUTED)
            else if (open.isNotEmpty()) note("${open.size} ask(s) still have no proof, and the run is out of rounds.", Ui.BAD)
            // no list at all: the reply is an answer of its own, so it is kept
            finishCore(dev.pocketide.app.core.AskLedger.answer(text, ledgerSummary, preferReply = lines == null))
            return
        }
        // 1.5.161 (FreshVerify, off until measured): one review in a fresh context, request + diff + final message only; its findings
        // go back once. A failed or slow call lets the run finish as it would have.
        if (agent && !nested && settings.verifyFresh && !freshAsked && changed.isNotEmpty() && round < maxRounds) {
            freshAsked = true
            freshReview(text) { f ->
                if (f == null || !f.any) { finishCore(text); return@freshReview }
                trace("fresh review: " + listOf("missing" to f.missing, "not needed" to f.notNeeded, "not shown" to f.notShown).filter { it.second.isNotEmpty() }.joinToString("; ") { it.first + ": " + it.second })
                note("A fresh reviewer found gaps: sending them back once.", Ui.ACCENT)
                goBack(text.ifBlank { "Done." }, "a fresh reviewer found gaps", dev.pocketide.app.core.FreshVerify.note(f))
            }
            return
        }
        // 1.5.156 (ScopeGate, off until measured): files neither asked for nor named as the cause are questioned once.
        // 1.5.157: also during the wrap-up (the "reply now" checkpoint), where most real-project runs end; R2 showed CauseGate's data
        // question, which waits for no wrap-up, skipped in exactly those runs. The hard round limit still ends the run.
        if (agent && !nested && settings.scopeCheck && !scopeAsked && changed.isNotEmpty() && round < maxRounds) {
            val extra = dev.pocketide.app.core.ScopeGate.unasked(changed, job, saidSoFar() + "\n" + text,
                dev.pocketide.app.core.Habits.reportsProblem(request)) { p -> store.exists(p) || paths().any { it.substringAfterLast('/') == p } }
            if (extra.isNotEmpty()) {
                scopeAsked = true; dataAsked = true; scopeFiles = extra
                trace("scope check: questioned ${extra.joinToString()}")
                note("Asked the model whether ${extra.joinToString()} needed changing.", Ui.MUTED)
                goBack(text.ifBlank { "Done." }, "files the request did not ask for", dev.pocketide.app.core.ScopeGate.ask(extra))
                return
            }
        }
        // 1.5.147 (CauseGate): a problem report that changed a data file it does not name is questioned once
        if (agent && !nested && settings.causeFirst && !dataAsked && changed.isNotEmpty() && round < maxRounds && wrapAt < 0 &&
            dev.pocketide.app.core.Habits.reportsProblem(request)) {
            val data = dev.pocketide.app.core.CauseGate.unnamedData(changed, request)
            if (data.isNotEmpty()) {
                dataAsked = true
                trace("cause first: questioned changed data files ${data.joinToString()}")
                note("Asked the model whether ${data.joinToString()} really needed changing.", Ui.MUTED)
                goBack(text.ifBlank { "Done." }, "data files changed for a problem report", dev.pocketide.app.core.CauseGate.dataAsk(data))
                return
            }
        }
        // 1.5.154 (InputVariety, off until measured): code that reads outside input is tried on other shapes before it is called done
        if (agent && !nested && settings.varietyProbe && !varietyAsked && round < maxRounds && wrapAt < 0 &&
            dev.pocketide.app.core.InputVariety.changedCode(changed) && dev.pocketide.app.core.InputVariety.readsOutsideInput(request)) {
            varietyAsked = true
            // 1.5.164: the app writes the other shapes itself, from the person's sample, when there is one (InputVariants)
            val written = try {
                dev.pocketide.app.core.InputVariants.samples(paths().toList()).mapNotNull { s ->
                    val t = store.read(s) ?: return@mapNotNull null
                    val vs = dev.pocketide.app.core.InputVariants.of(s, t)
                    vs.forEach { store.write(it.path, it.text) }
                    s to vs
                }
            } catch (e: Exception) { emptyList() }
            trace("input variety: " + if (written.isEmpty()) "asked for other shapes of input" else "wrote ${written.sumOf { it.second.size }} variant(s) of ${written.joinToString { it.first }}")
            note(if (written.isEmpty()) "Asked the model to try its code on other shapes of input." else "Wrote other shapes of ${written.joinToString { it.first }} for the model to try its code on.", Ui.MUTED)
            goBack(text.ifBlank { "Done." }, "code that reads outside input",
                if (written.isEmpty()) dev.pocketide.app.core.InputVariety.ASK else dev.pocketide.app.core.InputVariants.ask(written))
            return
        }
        // The person's finish checks (beforeFinish in .pocketide/hooks.json) must pass before the work is called done.
        if (agent && hooks.beforeFinish.isNotEmpty() && changed.isNotEmpty() && finishMark < writeMark) {
            if (finishTries < maxFixes && round < maxRounds && wrapAt < 0) {
                runFinishHooks { failures ->
                    if (failures.isEmpty()) { finishMark = writeMark; note("Finish checks passed.", Ui.GOOD); finishCore(text) }
                    else {
                        finishTries++
                        note("Finish checks failed (${failures.joinToString(", ") { it.first.take(40) }}): asking for a fix ($finishTries of $maxFixes).", Ui.ACCENT)
                        goBack(text, "the project's finish checks failed", dev.pocketide.app.core.Hooks.finishRequest(failures))
                    }
                }
                return
            }
            if (finishTries > 0) { finishMark = writeMark; note("Finish checks still failing after $finishTries fix attempts (change the limit in ${Rounds.SETTING}).", Ui.BAD) }
        }
        // Security check of the changed files (secrets, open database rules, SQL from request data); serious problems go back to the model once.
        if (!securityChecked && changed.isNotEmpty()) {
            securityChecked = true
            val found = dev.pocketide.app.core.SecurityScan.scan(changed.mapNotNull { p -> current(p)?.let { p to it } })
            if (found.isNotEmpty()) {
                note(dev.pocketide.app.core.SecurityScan.summary(found), Ui.BAD)
                if (found.any { it.level == dev.pocketide.app.core.SecurityScan.Level.HIGH } && round < maxRounds && wrapAt < 0) {
                    goBack(text, "the security scan found a serious problem", dev.pocketide.app.core.SecurityScan.request(found))
                    return
                }
            }
        }
        // Second opinion: a read-only reviewer on the second model checks the changes against the request, once.
        val reviewSlot = if (!nested && !reviewed && agent && changed.isNotEmpty() && round < maxRounds && wrapAt < 0) explorerSlot()?.takeIf { Tools.providerSupports(it.provider) } else null
        if (reviewSlot != null) {
            reviewed = true
            review(reviewSlot) { reply ->
                if (reply != null && dev.pocketide.app.core.ReviewPass.wantsFix(reply)) {
                    note("The reviewer found problems; asking for fixes.", Ui.ACCENT)
                    goBack(text, "the reviewer found problems", dev.pocketide.app.core.ReviewPass.request(reply))
                } else {
                    if (reply != null) note("Reviewer: no problems found.", Ui.GOOD)
                    finishCore(text)
                }
            }
            return
        }
        // Automatic look at the finished page: when page files changed and the model has not looked since, show it the page once.
        val lk = look
        if (lk != null && !nested && !accepted && agent && dev.pocketide.app.core.PageProbe.relevant(changed) && lookMark < writeMark && round < maxRounds && wrapAt < 0) {
            accepted = true
            phase("Looking at the finished page")
            lk("", "", "") look@{ report, img ->
                if (report.startsWith(dev.pocketide.app.core.LookOffer.NO_PAGE)) {
                    trace("page check skipped: the preview found no .html page")
                    note("No page to check: the preview found no .html file.", Ui.MUTED)
                    verifyStep(text)
                    return@look
                }
                val m = settings.model(settings.provider).ifBlank { Providers.byId(settings.provider).model }
                val sees = img != null && dev.pocketide.app.core.Images.providerSees(settings.provider, m)
                note("Looked at the page before finishing" + (if (sees) " (with a screenshot)." else "."), Ui.MUTED)
                appCheck("looked at the page", when { report.contains("VERDICT: PASS") -> true; report.contains("VERDICT: FAIL") -> false; else -> null })
                lookMark = round
                goBack(text, "a look at the finished page", dev.pocketide.app.core.ReviewPass.acceptance(request, report, sees), if (sees) listOf(img!!) else emptyList())
            }
            return
        }
        if (agent && dev.pocketide.app.core.PageProbe.relevant(changed) && look == null) {
            // no page to open: say so, instead of reporting a check that never ran as passed
            trace("page check skipped: the preview found no .html page")
            note("No page to check: the preview found no .html file.", Ui.MUTED)
            verifyStep(text)
            return
        }
        if (agent && dev.pocketide.app.core.PageProbe.relevant(changed)) {
            phase("Checking the page")
            check { errs0 ->
                val split = dev.pocketide.app.core.PageProblems.split(errs0, request)
                val errs = split.blocking
                val noteLine = dev.pocketide.app.core.PageProblems.noteLine(split.notes)
                if (noteLine.isNotEmpty()) { note(noteLine, Ui.MUTED); trace("page check: " + noteLine) }
                appCheck("the app's page check", errs.isEmpty())
                if (errs.isEmpty()) {
                    note("Checked the page: no errors.", Ui.GOOD)
                    trace("page check: no errors")
                    verifyStep(if (noteLine.isEmpty()) text else text.trimEnd() + "\n\n" + noteLine)
                } else if (fixes < maxFixes && errs.joinToString("|") != lastFixSig) {
                    lastFixSig = errs.joinToString("|")
                    fixes++
                    trace("page check found ${errs.size} error(s); asking for a fix ($fixes of $maxFixes): " + errs.first().take(160))
                    note("Found problems; asking for a fix ($fixes of $maxFixes):\n" + errs.joinToString("\n") { "- " + it.take(200) }, Ui.ACCENT)
                    goBack(text, "the page check found ${errs.size} error(s)", Prompts.fixRequest(emptyList(), errs))
                } else if (fixes < maxFixes) stuck(text, errs.joinToString("\n") { "- $it" })
                else {
                    trace("not finished: the page check still fails after $maxFixes fix attempts: " + errs.first().take(160))
                    note("Still failing after $maxFixes fix attempts (change it in ${Rounds.SETTING}):\n" + errs.joinToString("\n") { "- $it" } + "\n" + NEXT_STEPS, Ui.BAD)
                    emit(Result(text, staged, changed.toList(), labels, false))
                }
            }
        } else verifyStep(text)
    }

    /** After code changes: run the project's tests or checks and send failures back to the model (shares the 3 fix attempts). */
    private fun verifyStep(text: String) {
        val plan = if (agent && settings.verify && dev.pocketide.app.core.Verify.relevant(changed)) verifyPlan() else null
        if (plan == null) { emit(Result(text, staged, changed.toList(), labels, true)); return }
        val finished = { code: Int, out: String ->
            when (dev.pocketide.app.core.Verify.outcome(code, out)) {
                dev.pocketide.app.core.Verify.Outcome.PASS -> {
                    // 1.5.173: "syntax checks" only show the code parses; recorded as runtime they headed three field replies "✓ Done and
                    // checked: syntax checks", one of them saying 906 of 907 pictures were still missing
                    proofChecks.add(dev.pocketide.app.core.Proof.Check(plan.label, true, round, dev.pocketide.app.core.Verify.runsCode(plan.label)))
                    note("Verified: ${plan.label} passed.", Ui.GOOD)
                    checkMark = round
                    promoteChecked()
                    emit(Result(text, staged, changed.toList(), labels, true))
                }
                dev.pocketide.app.core.Verify.Outcome.MISSING_TOOL -> {
                    note("Could not run ${plan.label}: the tool is not installed here.", Ui.MUTED)
                    emit(Result(text, staged, changed.toList(), labels, true))
                }
                dev.pocketide.app.core.Verify.Outcome.FAIL -> {
                    proofChecks.add(dev.pocketide.app.core.Proof.Check(plan.label, false, round, dev.pocketide.app.core.Verify.runsCode(plan.label)))
                    dev.pocketide.app.core.AppLog.add("verify", "${plan.label} failed with code $code")
                    chip("${plan.label} (exit $code)", dev.pocketide.app.core.Verify.trim(out, 6000))
                    val sig = dev.pocketide.app.core.Verify.trim(out, 600)
                    if (fixes < maxFixes && sig != lastFixSig) {
                        lastFixSig = sig
                        fixes++
                        note("${plan.label} failed; asking for a fix ($fixes of $maxFixes).", Ui.ACCENT)
                        goBack(text, "${plan.label} failed", dev.pocketide.app.core.Verify.fixRequest(plan, code, out, fixes, maxFixes))
                    } else if (fixes < maxFixes) stuck(text, "${plan.label} fails the same way:\n" + dev.pocketide.app.core.Verify.trim(out, 1500))
                    else {
                        note("${plan.label} still failing after $maxFixes fix attempts (change it in ${Rounds.SETTING}).\n" + NEXT_STEPS, Ui.BAD)
                        emit(Result(text, staged, changed.toList(), labels, false))
                    }
                }
            }
        }
        note("Running ${plan.label} to check the changes…", Ui.MUTED)
        phase("Running ${plan.label}")
        if (settings.autorun) exec(plan.command, finished)
        else {
            box.addView(askRunTimed(plan.command, finished) {
                note("Skipped the check.", Ui.MUTED)
                emit(Result(text, staged, changed.toList(), labels, true))
            })
            scrollDown()
        }
    }

    /**
     * The model's words between tool calls used to be written over by the next round's text, so they vanished before they
     * could be read. Freeze them as their own block, and move the live text view below the chips so the next round starts fresh.
     */
    private fun keepCommentary(t: String) {
        val at = box.indexOfChild(body)
        val lp = body.layoutParams as? LinearLayout.LayoutParams ?: return
        if (at < 0) return
        val keep = TextView(ctx)
        keep.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, body.textSize)
        keep.setTextColor(body.currentTextColor)
        keep.typeface = body.typeface
        keep.setPadding(body.paddingLeft, body.paddingTop, body.paddingRight, body.paddingBottom)
        body.background?.constantState?.newDrawable()?.let { keep.background = it }
        fun show(open: Boolean) {
            keep.text = (if (open) "▾ " else "▸ ") + t
            keep.maxLines = if (open) Int.MAX_VALUE else 1
            keep.ellipsize = if (open) null else android.text.TextUtils.TruncateAt.END
            keep.tag = open
        }
        show(true)
        keep.setOnClickListener { show(keep.tag != true) }
        // only the newest thought stays open; older ones fold to one line you can tap to read again
        collapseLast?.invoke()
        collapseLast = { show(false) }
        box.addView(keep, at, LinearLayout.LayoutParams(lp))
        box.removeView(body)
        body.text = "…"
        box.addView(body, LinearLayout.LayoutParams(lp))
        row = null
    }
    private var collapseLast: (() -> Unit)? = null

    /**
     * The model's thinking for this round, as a folded "▸ Thought for 12s" line above the round's words and steps; tap to read it.
     * Saved in the work log with the reply (WorkLog).
     */
    private fun keepThought(item: dev.pocketide.app.core.WorkLog.Item) {
        val at = box.indexOfChild(body)
        if (at < 0) return
        box.addView(WorkLogView.thought(ctx, item), at, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        scrollDown()
    }
    private val NO_BEFORE = String(charArrayOf('\u0000'))

    private val NEXT_STEPS = "What you can do: tap Undo changes to go back to before this request, describe what you see on screen, or send it again with a stronger model."

    /** Adds the language server's errors for a written file, when that check is on, before the replies go out. */
    private fun lspThen(p: String, text: String, ok: String, next: (String) -> Unit) {
        val check = lsp ?: return next(ok)
        check(p, text) { note -> next(if (note.isNullOrBlank()) ok else ok + "\n" + note) }
    }

    /** Undefined names still in each written script (UndefinedNames), for the finish check; asked about once. */
    private val undefinedLeft = LinkedHashMap<String, List<dev.pocketide.app.core.UndefinedNames.Use>>()
    private var namesAsked = false

    /**
     * Adds a warning for names the written script reads but nothing defines (FOOD in `FOOD[sid]`), counting only names that were
     * not already undefined before this write, so old problems elsewhere in the file do not pull the work off course.
     */
    private fun namesThen(p: String, text: String, before: String?, ok: String, refuse: ((String) -> Unit)? = null, next: (String) -> Unit) {
        val n = names
        if (n == null || !dev.pocketide.app.core.UndefinedNames.checks(p)) return next(ok)
        n(p, text) { raw ->
            val now = dev.pocketide.app.core.UndefinedNames.parse(raw)
            if (now.isEmpty()) { undefinedLeft.remove(p); return@n next(ok) }
            val finish = { old: List<dev.pocketide.app.core.UndefinedNames.Use>? ->
                val fresh = try { dev.pocketide.app.core.UndefinedNames.fresh(p, text, now, old, paths()) { current(it) } } catch (e: Exception) { emptyList() }
                // a change that deletes a definition the file still uses is not kept (a small model replaced `let selected = [];`
                // with code that reads it, then spent every later round failing around the gap)
                val gone = if (refuse != null && before != null && before !== NO_BEFORE) dev.pocketide.app.core.UndefinedNames.removedDefinitions(fresh, before) else emptyList()
                // a name first reported on an earlier write stays reported until it is fixed
                val kept = undefinedLeft[p].orEmpty().map { it.name }.toSet()
                val left = now.filter { u -> u.name in kept || fresh.any { it.name == u.name } }
                if (gone.isNotEmpty() && before != null && write(FileEdit(p, before))) {
                    trace("change deleted definitions still used in $p: " + gone.joinToString { it.name })
                    note("Not saved: the change deleted $p's definition of ${gone.first().name}, which is still used; the earlier version was put back.", Ui.BAD)
                    refuse!!(dev.pocketide.app.core.UndefinedNames.removedRefusal(p, gone))
                }
                else if (left.isEmpty()) { undefinedLeft.remove(p); next(ok) }
                else {
                    undefinedLeft[p] = left
                    trace("undefined names in $p: " + left.joinToString { it.name })
                    note("$p reads " + left.take(3).joinToString { it.name } + ", which nothing defines: told the model.", Ui.ACCENT)
                    next(ok + "\n" + dev.pocketide.app.core.UndefinedNames.note(p, left) { dev.pocketide.app.core.UndefinedNames.definedIn(it, p, paths()) { f -> current(f) } })
                }
            }
            when {
                before == null -> finish(emptyList())               // a new file: everything in it is new
                before === NO_BEFORE -> finish(null)                 // the earlier text is not known
                else -> n(p, before) { r -> finish(dev.pocketide.app.core.UndefinedNames.parse(r)) }
            }
        }
    }

    /** Handlers that throw errors away, per written file: how many the file had before the first write, and the added ones' lines. */
    private val swallowBase = HashMap<String, Int>()
    private val swallowLeft = LinkedHashMap<String, List<Int>>()
    private var swallowAsked = false

    /** A warning when this write adds a handler that discards the error (SwallowedErrors), or "". */
    private fun swallowNote(p: String, text: String, before: String?): String {
        if (!dev.pocketide.app.core.SwallowedErrors.checks(p)) return ""
        val base = swallowBase.getOrPut(p) {
            if (before === NO_BEFORE) dev.pocketide.app.core.SwallowedErrors.lines(p, text).size   // unknown: nothing counts as added
            else before?.let { dev.pocketide.app.core.SwallowedErrors.lines(p, it).size } ?: 0
        }
        val all = dev.pocketide.app.core.SwallowedErrors.lines(p, text)
        if (all.size <= base) { swallowLeft.remove(p); return "" }
        val added = all.takeLast(all.size - base)
        swallowLeft[p] = added
        val silence = dev.pocketide.app.core.SwallowedErrors.complainsOfSilence(request)
        trace("error handler throws the error away in $p line ${added.joinToString()}")
        note("$p throws an error away (line ${added.first()}): told the model.", Ui.ACCENT)
        return "\n" + dev.pocketide.app.core.SwallowedErrors.note(p, added, silence)
    }

    /** Replies to a write, adding the syntax check's finding when there is one. */
    private fun afterWrite(p: String, text: String, ok0: String, label: String, before: String? = NO_BEFORE, reply: (String, String) -> Unit) {
        // saved play tests that cannot run (or can never fail) are pointed out at once
        val split = if (p == dev.pocketide.app.core.PlayTests.FILE && before !== NO_BEFORE && before != null) dev.pocketide.app.core.PlayTests.splits(before, text) else emptyList()
        val ok = (if (p == dev.pocketide.app.core.PlayTests.FILE) (dev.pocketide.app.core.PlayTests.problems(text) + split).let { pr ->
            if (pr.isEmpty()) "$ok0 ${dev.pocketide.app.core.PlayTests.parse(text).size} play test(s) saved; run them with look_at_preview test=all."
            else "$ok0\nProblems in the play tests (fix them):\n" + pr.joinToString("\n") { "- $it" } } else ok0).let { it + swallowNote(p, text, before) +
            dev.pocketide.app.core.Doubles.note(p, text, before.takeIf { b -> b !== NO_BEFORE }) }
        val s = syntax
        val refuse = { m: String -> reply(m, "$label (not saved: deleted a definition still in use)") }
        if (s == null) { namesThen(p, text, before, ok, refuse) { o -> lspThen(p, text, o) { runHooks(p, it, label, reply) } }; return }
        phase("Checking $p")
        s(p, text) { err ->
            if (err == null) { namesThen(p, text, before, ok, refuse) { o -> lspThen(p, text, o) { runHooks(p, it, label, reply) } }; return@s }
            val warn = { note("Syntax error in $p: $err", Ui.BAD); reply(ok + "\n" + dev.pocketide.app.core.SyntaxCheck.note(p, err), "$label (syntax error)") }
            // a broken script, page or data file is not kept when the text before it was fine (or the file is new): a cut-off
            // rewrite must never replace a working file. When the old text was already broken, the new one is kept as a step.
            if (before === NO_BEFORE || !dev.pocketide.app.core.SyntaxCheck.guarded(p)) { warn(); return@s }
            val undo = {
                val existed = before != null
                val back = if (before != null) write(FileEdit(p, before)) else store.delete(p).also { if (it) changed.remove(p) }
                if (!back) warn()
                else { note("Not saved: $p had a syntax error ($err); " + (if (existed) "the earlier version was put back." else "the new file was not kept."), Ui.BAD); reply(dev.pocketide.app.core.SyntaxCheck.refused(p, err, existed), "$label (not saved: syntax error)") }
            }
            if (before == null) undo() else s(p, before) { old -> if (old == null) undo() else warn() }
        }
    }

    private val hooks by lazy { dev.pocketide.app.core.Hooks.parse(store.read(dev.pocketide.app.core.Hooks.FILE)) }

    /** Null when [p] may be written, else the refusal for the model. */
    private fun guarded(p: String): String? = dev.pocketide.app.core.Hooks.protectedBy(hooks, p)?.let {
        "Refused: $p is $it. Do not try to change it another way; tell the person what you would change and why."
    }

    /** The project's afterWrite hooks for [p], run one after another; their results are added to the write's reply. */
    /** writeMark when the finish checks last passed, and how often failures were sent back. */
    private var finishMark = -1
    private var finishTries = 0

    /** Runs the beforeFinish commands in order; [done] gets the failures (command, exit code, output). A skipped or blocked command is not a failure. */
    private fun runFinishHooks(done: (List<Triple<String, Int, String>>) -> Unit) {
        val cmds = hooks.beforeFinish
        val failures = ArrayList<Triple<String, Int, String>>()
        fun next(i: Int) {
            if (i >= cmds.size || stopRequested()) { done(failures); return }
            val cmd = cmds[i]
            phase("Finish check: " + cmd.take(40))
            val finished = { code: Int, out: String -> if (code != 0) failures.add(Triple(cmd, code, out)); next(i + 1) }
            when (decide(cmd)) {
                dev.pocketide.app.core.CommandRules.Decision.RUN -> exec(cmd, finished)
                dev.pocketide.app.core.CommandRules.Decision.DENY -> { note("Finish check `$cmd` is blocked by your command rules.", Ui.MUTED); next(i + 1) }
                else -> { box.addView(askRunTimed(cmd, finished) { note("Skipped the finish check `$cmd`.", Ui.MUTED); next(i + 1) }); scrollDown() }
            }
        }
        next(0)
    }

    private fun runHooks(p: String, ok: String, label: String, reply: (String, String) -> Unit) {
        val cmds = dev.pocketide.app.core.Hooks.after(hooks, p)
        if (cmds.isEmpty()) { reply(ok, label); return }
        val sb = StringBuilder(ok)
        var failed = false
        fun next(i: Int) {
            if (i >= cmds.size) { reply(sb.toString(), if (failed) "$label (hook failed)" else label); return }
            val cmd = cmds[i]
            phase("Hook: " + cmd.take(40))
            val finished = { code: Int, out: String ->
                if (code != 0) failed = true
                sb.append("\nHook `").append(cmd).append("` exited with ").append(code).append(if (code == 0) "." else ":\n" + dev.pocketide.app.core.Verify.trim(out, 1500))
                next(i + 1)
            }
            when (decide(cmd)) {
                dev.pocketide.app.core.CommandRules.Decision.RUN -> exec(cmd, finished)
                dev.pocketide.app.core.CommandRules.Decision.DENY -> { sb.append("\nHook `").append(cmd).append("` is blocked by the person's command rules."); next(i + 1) }
                else -> { box.addView(askRunTimed(cmd, finished) { sb.append("\nHook `").append(cmd).append("` was skipped by the person."); next(i + 1) }); scrollDown() }
            }
        }
        next(0)
    }

    /**
     * The explore tool: a read-only helper with a fresh conversation (and the cheaper model when one is set) answers a
     * question about the project and hands back a short summary, so the files it read never enter this conversation.
     * Its steps fold into one row; its spending counts toward the request's limit.
     */
    private fun explore(question: String, done: (String, Boolean) -> Unit) {
        val slot = explorerSlot()?.takeIf { Tools.providerSupports(it.provider) }
        val who = slot?.let { it.model.ifBlank { Providers.byId(it.provider).name } }
        val holder = Ui.vertical(ctx)
        val head = Ui.text(ctx, "▸ Explorer" + (if (who != null) " ($who)" else "") + ": " + question.take(90), 12f, Ui.ACCENT)
        head.setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 6), Ui.dp(ctx, 4), Ui.dp(ctx, 2))
        val inner = Ui.vertical(ctx)
        inner.visibility = android.view.View.GONE
        inner.setPadding(Ui.dp(ctx, 12), 0, 0, 0)
        val innerBody = Ui.text(ctx, "…", 12f, Ui.MUTED)
        inner.addView(innerBody)
        head.setOnClickListener {
            val open = inner.visibility != android.view.View.VISIBLE
            inner.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
            head.text = (if (open) "▾" else "▸") + head.text.toString().drop(1)
        }
        holder.addView(head); holder.addView(inner)
        box.addView(holder, Ui.lp(Ui.MATCH, Ui.WRAP))
        row = null
        scrollDown()
        val saved = settings.override
        if (slot != null) settings.override = slot
        val childAnthropic = Providers.byId(settings.provider).kind == ProviderKind.ANTHROPIC
        phase("Explorer is reading")
        NativeAgent(ctx, store, settings, agent, inner, innerBody, write, exec, askRun, check, setCall, {}, scrollDown, docs, docEdit, verifyPlan,
            onDone = { r ->
                settings.override = saved
                when {
                    r.stopped -> done("", true)
                    r.error != null -> { head.text = head.text.toString() + "  (failed)"; done("The explorer could not finish (" + r.error.take(200) + "). Look yourself instead.", false) }
                    else -> done("Explorer's answer:\n" + r.text.trim().ifBlank { "(no answer)" }, false)
                }
            },
            request = request, phase = phase, progress = progress, scope = scope, readOnly = { true },
            stopRequested = stopRequested, advisor = null, budget = budget, meaning = meaning, look = look, nested = true, roundCap = Rounds.helperRounds(12, settings.maxRounds)
        ).run(dev.pocketide.app.core.Prompts.EXPLORER + (if (settings.scopeCheck) dev.pocketide.app.core.ScopeGate.EXPLORER_NOTE else ""),
            listOf(Tools.userText(childAnthropic, question)))
    }

    /** Asks [slot]'s model for a short summary of [transcript]; [done] gets it, or null when it could not finish. */
    private fun summarize(slot: dev.pocketide.app.core.Workflow.Slot, transcript: String, done: (String?) -> Unit) {
        val holder = Ui.vertical(ctx)
        val innerBody = Ui.text(ctx, "…", 12f, Ui.MUTED)
        holder.visibility = android.view.View.GONE
        holder.addView(innerBody)
        box.addView(holder, Ui.lp(Ui.MATCH, Ui.WRAP))
        val saved = settings.override
        settings.override = slot
        val childAnthropic = Providers.byId(settings.provider).kind == ProviderKind.ANTHROPIC
        phase("Summarising the work so far")
        NativeAgent(ctx, store, settings, agent, holder, innerBody, write, exec, askRun, check, setCall, {}, scrollDown, docs, docEdit, verifyPlan,
            onDone = { r -> settings.override = saved; done(if (r.stopped || r.error != null) null else r.text.take(2000)) },
            request = request, phase = phase, progress = progress, scope = scope, readOnly = { true },
            stopRequested = stopRequested, advisor = null, budget = budget, look = null, nested = true, roundCap = 2
        ).run(dev.pocketide.app.core.WorkSummary.PROMPT, listOf(Tools.userText(childAnthropic, "Summarise this work so far:\n\n" + transcript)))
    }

    /** Asks [slot]'s model how many separate asks the request has (1.5.139); [done] gets the number, or null. */
    private fun countAsks(slot: dev.pocketide.app.core.Workflow.Slot, done: (Int?) -> Unit) {
        val holder = Ui.vertical(ctx)
        val innerBody = Ui.text(ctx, "…", 12f, Ui.MUTED)
        holder.visibility = android.view.View.GONE
        holder.addView(innerBody)
        box.addView(holder, Ui.lp(Ui.MATCH, Ui.WRAP))
        val saved = settings.override
        settings.override = slot
        val childAnthropic = Providers.byId(settings.provider).kind == ProviderKind.ANTHROPIC
        phase("Counting the asks in the request")
        NativeAgent(ctx, store, settings, agent, holder, innerBody, write, exec, askRun, check, setCall, {}, scrollDown, docs, docEdit, verifyPlan,
            onDone = { r -> settings.override = saved; done(if (r.stopped || r.error != null) null else dev.pocketide.app.core.AskLedger.parseCount(r.text)) },
            request = request, phase = phase, progress = progress, scope = scope, readOnly = { true },
            stopRequested = stopRequested, advisor = null, budget = budget, look = null, nested = true, roundCap = 1
        ).run(dev.pocketide.app.core.AskLedger.COUNT_PROMPT, listOf(Tools.userText(childAnthropic, dev.pocketide.app.core.AskLedger.countQuestion(request))))
    }

    /**
     * The fresh-context review (FreshVerify): one request with no tools and no history, on the helper model when one is set, else the
     * main one. [done] runs once: with the finding, or null when the call failed, timed out (30 s) or answered in another shape.
     */
    private fun freshReview(finalText: String, done: (dev.pocketide.app.core.FreshVerify.Finding?) -> Unit) {
        val changes = changed.toList().map { p -> dev.pocketide.app.core.FreshVerify.Change(p, synchronized(originals) { if (originals.containsKey(p)) originals[p] else store.read(p) },
            try { if (store.exists(p)) store.read(p) else null } catch (e: Exception) { null }) }
        val q = dev.pocketide.app.core.FreshVerify.question(request, dev.pocketide.app.core.FreshVerify.diff(changes), finalText, lastCommandOutput)
        val slot = explorerSlot()
        val anth = Providers.byId(slot?.provider ?: settings.provider).kind == ProviderKind.ANTHROPIC
        var once = false
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        fun finish(f: dev.pocketide.app.core.FreshVerify.Finding?) { if (once) return; once = true; handler.removeCallbacksAndMessages(null); setCall(null); done(f) }
        phase("A fresh reviewer is checking the changes")
        val saved = settings.override
        if (slot != null) settings.override = slot
        var cur: ModelCall? = null
        // set before the call starts, so a call that never answers still ends here
        handler.postDelayed({ if (!once) { try { cur?.cancel() } catch (e: Exception) {}; note("The fresh reviewer took longer than 30 s. Finishing without it.", Ui.MUTED); finish(null) } }, dev.pocketide.app.core.FreshVerify.TIMEOUT_MS)
        try {
            cur = ModelGateway.current.startNative(settings, dev.pocketide.app.core.FreshVerify.PROMPT, listOf(Tools.userText(anth, q)), false, object : NativeListener {
                override fun onStatus(text: String) {}
                override fun onDelta(acc: String) {}
                override fun onTurn(turn: dev.pocketide.app.core.ToolTurn) { handler.post { finish(dev.pocketide.app.core.FreshVerify.parse(turn.text)) } }
                override fun onStopped(partial: String) { handler.post { finish(null) } }
                override fun onError(message: String, needsSettings: Boolean, toolsRejected: Boolean) {
                    handler.post { note("The fresh reviewer could not answer (" + message.take(120) + "). Finishing without it.", Ui.MUTED); finish(null) }
                }
            }, false)
        } finally { settings.override = saved }
        setCall(cur)
    }

    /** Runs the read-only reviewer on [slot]'s model; [done] gets its reply, or null when it could not finish. */
    private fun review(slot: dev.pocketide.app.core.Workflow.Slot, done: (String?) -> Unit) {
        val who = slot.model.ifBlank { Providers.byId(slot.provider).name }
        val holder = Ui.vertical(ctx)
        val head = Ui.text(ctx, "▸ Reviewer ($who) is checking the changes", 12f, Ui.ACCENT)
        head.setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 6), Ui.dp(ctx, 4), Ui.dp(ctx, 2))
        val inner = Ui.vertical(ctx)
        inner.visibility = android.view.View.GONE
        inner.setPadding(Ui.dp(ctx, 12), 0, 0, 0)
        val innerBody = Ui.text(ctx, "…", 12f, Ui.MUTED)
        inner.addView(innerBody)
        head.setOnClickListener {
            val open = inner.visibility != android.view.View.VISIBLE
            inner.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
            head.text = (if (open) "▾" else "▸") + head.text.toString().drop(1)
        }
        holder.addView(head); holder.addView(inner)
        box.addView(holder, Ui.lp(Ui.MATCH, Ui.WRAP))
        row = null
        scrollDown()
        val saved = settings.override
        settings.override = slot
        val childAnthropic = Providers.byId(settings.provider).kind == ProviderKind.ANTHROPIC
        phase("Reviewer is checking the changes")
        NativeAgent(ctx, store, settings, agent, inner, innerBody, write, exec, askRun, check, setCall, {}, scrollDown, docs, docEdit, verifyPlan,
            onDone = { r ->
                settings.override = saved
                if (r.stopped || r.error != null) { head.text = head.text.toString() + "  (could not finish)"; done(null) }
                else done(r.text)
            },
            request = request, phase = phase, progress = progress, scope = scope, readOnly = { true },
            stopRequested = stopRequested, advisor = null, budget = budget, meaning = meaning, look = look, nested = true, roundCap = Rounds.helperRounds(10, settings.maxRounds)
        ).run(dev.pocketide.app.core.ReviewPass.PROMPT, listOf(Tools.userText(childAnthropic, dev.pocketide.app.core.ReviewPass.question(request, changed, paths().firstOrNull { dev.pocketide.app.core.PlanGate.isPlan(it) }?.let { store.read(it) }, store.read(dev.pocketide.app.core.PlayTests.FILE)))))
    }

    /** The person's command rules, read once per request. */
    private val rules: dev.pocketide.app.core.CommandRules.Rules get() = CommandRulesStore.load(ctx)

    /** Run, ask or refuse a command, from the person's rules, the autorun setting and the command's risk. */
    private fun decide(cmd: String) = dev.pocketide.app.core.CommandRules.decide(cmd, rules, settings.autorun, dev.pocketide.app.core.CommandRules.insideRoot(store.root))

    /** A whole-number tool argument; models send 40, 40.0 or "40". */
    private fun intArg(x: Any?): Int? = (x as? Number)?.toInt() ?: (x as? String)?.trim()?.toIntOrNull()

    /** " · $0.42 of $1.00" for the live status line, or "" when no request limit is set. */
    private fun meter(): String = budget()?.takeIf { !it.isOff }?.meter()?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""

    /** The request reached its spending limit: stop calling the model and let the person decide. Nothing is lost either way. */
    private fun pauseForBudget(b: dev.pocketide.app.core.RunBudget, why: String) {
        phase("Paused at the spending limit")
        Notifier.assistant(ctx, "Paused: $why. Open PocketIDE to continue or stop.")
        val card = Ui.vertical(ctx)
        card.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 8), Ui.dp(ctx, 10), Ui.dp(ctx, 8))
        card.background = Ui.rounded(Ui.SURFACE2, 10, ctx)
        card.addView(Ui.text(ctx, "Paused: $why on this request." + (if (b.unpriced) " Some of the models used have no price set, so the real cost may be higher." else "") +
            "\nContinue to allow the same amount again, or stop and keep what is done." + (if (changed.isEmpty()) "" else " Changed so far: " + changed.joinToString(", ") + "."), 13f, Ui.TEXT))
        val buttons = Ui.horizontal(ctx)
        var decided = false
        val go = Ui.button(ctx, "Continue") {
            if (decided) return@button
            decided = true; card.visibility = android.view.View.GONE
            b.extend()
            note("Continuing. New limit: " + b.meter().substringAfter(" of ") + ".", Ui.MUTED)
            request()
        }
        val stop = Ui.button(ctx, "Stop here") {
            if (decided) return@button
            decided = true; card.visibility = android.view.View.GONE
            note("Stopped at the spending limit ($why). Change it in Settings → How long it may work → Dollars or Tokens per request.", Ui.MUTED)
            emit(Result("", staged, changed.toList(), labels, false, stopped = true))
        }
        buttons.addView(go)
        buttons.addView(stop, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 8) })
        card.addView(buttons, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
        box.addView(card, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
        row = null
        scrollDown()
    }

    private fun note(s: String, color: Int) {
        box.addView(Ui.text(ctx, s, 12f, color).also { it.setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4), 0) })
        scrollDown()
    }

    private fun chip(label: String, result: String) {
        var r = row
        if (r == null) {
            r = Ui.horizontal(ctx)
            val hs = HorizontalScrollView(ctx)
            hs.isHorizontalScrollBarEnabled = false
            hs.addView(r)
            box.addView(hs, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
            row = r
        }
        val c = Ui.chip(ctx, label) {
            val t = Ui.text(ctx, result, 11f, Ui.TEXT, true)
            t.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 8), Ui.dp(ctx, 16), Ui.dp(ctx, 8))
            t.setTextIsSelectable(true)
            val sv = ScrollView(ctx)
            sv.addView(t)
            AlertDialog.Builder(ctx).setTitle(label).setView(sv).setPositiveButton("Close", null).show()
        }
        r.addView(c, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.rightMargin = Ui.dp(ctx, 6) })
        labels.add(label)
        dev.pocketide.app.core.WorkLog.add(workLog, dev.pocketide.app.core.WorkLog.Kind.STEP, label); logged()
        scrollDown()
    }

    // ---- tools ----

    private fun clean(p: String) = p.trim().removePrefix("./").removePrefix("/")
    private fun current(p: String): String? = staged[p] ?: if (store.isBinary(p)) null else store.read(p)
    /** False in a turn limited to tools without run_command (the repair turn): finish nudges that ask for a run do not apply there. */
    private fun canRunCommands(): Boolean = Tools.only.let { it == null || "run_command" in it }
    private fun paths(): List<String> = (store.list() + staged.keys).distinct()
    /** The project's files, plus every file under [dir] when one is named: the capped list may leave that folder out (1.5.167). */
    private fun paths(dir: String): List<String> {
        val d = clean(dir).trimEnd('/')
        if (d.isEmpty() || d == ".") return paths()
        return (paths() + store.listUnder(d)).distinct()
    }
    private fun notOwned(p: String): String? = owned?.let { if (dev.pocketide.app.core.Parts.owns(it, p)) null else dev.pocketide.app.core.Parts.refusal(it, p) }
    private fun okPath(p: String) = p.isNotEmpty() && !p.contains("..") && store.resolve(p) != null

    /** True when [cmd] only prints project files the model was already shown whole and that have not changed since (1.5.124). */
    private fun reprints(cmd: String): Boolean {
        val ps = dev.pocketide.app.core.Rereads.printed(cmd)
        return ps.isNotEmpty() && ps.all { p -> val t = staged[p] ?: store.readForAi(p); t != null && readFiles[p] == t.hashCode() }
    }

    private fun execute(c: ToolCall, skipHold: Boolean = false, done0: (String) -> Unit) {
        // every tool call is written to the chat's record once it has an answer; a held call is written when it is finally run
        val t0 = System.currentTimeMillis(); val r0 = round
        val done: (String) -> Unit = if (skipHold) done0 else { r ->
            dev.pocketide.app.core.ToolTrace.write(dev.pocketide.app.core.ToolTrace.call(t0, r0, c.name, c.arguments, r, System.currentTimeMillis() - t0))
            recordCheck(c, r, round)
            done0(r)
        }
        val label = Tools.label(c)
        val a0 = c.args()
        phase("Step $round · " + dev.pocketide.app.core.WorkingStatus.forTool(c.name, c.args())); progress(0)
        fun reply(r: String, shown: String = label) {
            // 1.5.99: readFiles is not cleared here any more: each entry is the stamp of the text shown, so a file that changed (by an
            // edit or a command) no longer matches and is sent again; only the files that really changed lose their entry
            // 1.5.124: a command that only reads (cat, grep, ls) changes nothing, so it no longer clears the repeat check either
            val readOnlyCmd = c.name == "run_command" && dev.pocketide.app.core.Habits.readOnlyCommand((a0["command"] as? String).orEmpty())
            if (isMutating(c) && !readOnlyCmd) { loop.changed(); if (c.name != "run_command" && c.name != "remember") streak.changed() }
            streak.searched(c.name, r)
            // a file that really changed clears the detector; a failed edit and a command's result are evidence
            if (isMutating(c) && c.name != "run_command" && c.name != "remember" && !StuckDetector.isError(r)) stuck.changed()
            else stuck.record(c.name, c.arguments, r)
            // 1.5.124: reading again a file the model already has, in any range or by printing it with a command
            if ((c.name == "read_file" && (shown.endsWith("(unchanged)") || shown.endsWith("(repeated)"))) ||
                (readOnlyCmd && reprints((a0["command"] as? String).orEmpty()))) stuck.reread()
            chip(shown, r); done(r)
        }
        val a = c.args()
        // 1.5.164: a repair turn offers only reading and editing; a model that calls something else anyway is told plainly
        Tools.only?.let { o -> if (c.name !in o) { reply("Not available in this turn: only ${o.sorted().joinToString(", ")}. Make the fix from what you have been shown."); return } }
        if (c.name == "run_command" || c.name == "look_at_preview") ranSomething = true
        if (stopRequested()) { done("Skipped: the user pressed Stop."); return }
        // 1.5.127: the card said "Wrote index.html (read-only)" over a refusal; it now says what was refused.
        if (readOnly() && isMutating(c)) { reply(dev.pocketide.app.core.ReadOnlyRun.REFUSAL, dev.pocketide.app.core.ReadOnlyRun.label(c.name, (a["path"] ?: a["command"]) as? String)); return }
        if (reportOnly && !nested && (c.name in FILE_CHANGES || c.name == "game_assets")) {
            trace("report only: refused ${c.name} (the person asked to run or check and report)")
            reply(dev.pocketide.app.core.Scope.REPORT_REFUSAL, "$label (report only: not changed)"); return
        }
        if (question && !nested && isMutating(c) && c.name != "remember" &&
            !(c.name == "run_command" && dev.pocketide.app.core.Habits.readOnlyCommand((a["command"] as? String).orEmpty()))) {
            trace("question: refused ${c.name} (the person asked a question)")
            reply(dev.pocketide.app.core.Habits.QUESTION_REFUSAL, "$label (a question: not changed)"); return
        }
        if (!skipHold && advisor?.mode == dev.pocketide.app.core.Advisor.AUTO && isMutating(c) && !readOnly()) {
            if (heldRound == round) { reply(dev.pocketide.app.core.Advisor.heldAgain(), "$label (held)"); return }
            if (heldRound < 0 && advisorUsed < advisor.maxCalls) {
                heldRound = round
                consult(dev.pocketide.app.core.Advisor.Purpose.START) { adv ->
                    if (adv == null) execute(c, true, done)
                    else { heldAdvice = adv; reply(dev.pocketide.app.core.Advisor.heldMessage(adv), "Advisor advice before changes") }
                }
                return
            }
        }
        if (c.name != "update_plan" && c.name != "advisor" && loop.repeated(c.name, c.arguments)) {
            val msg = if (c.name == "read_file") "You already read this file and nothing has changed since. Use the copy you already have in this conversation." else ToolLoop.MESSAGE
            reply(msg, "$label (repeated)"); return
        }
        // searches for names that are not in the project, over and over
        if (streak.missBlocked(c.name)) { trace("search miss: refused ${c.name} after ${streak.misses} misses"); reply(dev.pocketide.app.core.ReadStreak.MISSED, "$label (search again)"); return }
        fun g(k: String) = (a[k] as? String).orEmpty()
        if (c.name == "use_tool") {
            val want = g("name").trim()
            val text = Tools.describe(want, docs)
            if (text == null) { reply("No tool called \"$want\" can be added. Available: " + Tools.heldNames(docs).joinToString(", ") + ".", "$label (unknown)"); return }
            loadedTools.add(want); trace("tool added on request: $want")
            reply(text, "Added tool $want"); return
        }
        // calling a held tool directly adds it too, so it works at once and stays in the list
        if (Tools.isOnDemand(c.name) && loadedTools.add(c.name)) trace("tool added by its first use: ${c.name}")
        // the app's own "shortened" note must never become file content or a command
        if (c.name in setOf("write_file", "edit_file", "edit_lines", "multi_edit", "apply_patch", "run_command") && dev.pocketide.app.core.HistoryTrim.isPlaceholder(c.arguments)) {
            reply(dev.pocketide.app.core.HistoryTrim.PLACEHOLDER_REFUSAL, "$label (placeholder text)"); return
        }
        if (owned != null && c.name in setOf("run_command", "game_assets", "doc_edit", "remember")) {
            reply("Not available while parts are built at the same time. Write only your own files; a check of the whole result runs after all parts are done.", "$label (not now)"); return
        }
        if (dev.pocketide.app.core.AddOns.handles(c.name)) {
            var n = 0
            val env = cacheEnv { n++ < 200 }
            McpClient.background {
                val r = try { dev.pocketide.app.core.AddOns.run(c.name, a, env) } catch (e: Throwable) { "${c.name} failed: " + (e.message ?: e.javaClass.simpleName) }
                McpClient.onMain { reply(r, label) }
            }
            return
        }
        when (c.name) {
            "read_file" -> {
                val p = clean(g("path"))
                if (dev.pocketide.app.core.GameTemplates.isEngineFile(p)) { reply("$p belongs to the game engine (a minified library). Do not read it; use the engine's documented functions from your own files.", "$label (engine)"); return }
                val text = staged[p] ?: store.readForAi(p)
                val stamp = text?.hashCode() ?: 0
                val from = intArg(a["start"])
                if (from != null) {
                    // 1.5.99: a piece of a file that fits in one reply is answered with the whole file (once per version). In the logs
                    // the model read a 900-line main.js 40 to 45 times a request in 150-line pieces, with a long think before each.
                    val whole = settings.readWhole && (text?.let { dev.pocketide.app.core.ReadPlan.whole(it, resultCap()) } ?: false)
                    if (text != null && whole && readFiles[p] == stamp) { reply(dev.pocketide.app.core.ReadPlan.seen(p, from, intArg(a["end"])), "$label (unchanged)"); return }
                    if (text != null && whole) {
                        val shown = AgentActions.read(paths() + p, { q -> staged[q] ?: store.readForAi(q) }, p, cap = resultCap())
                        if (!shown.contains(AgentActions.MORE)) { readFiles[p] = stamp; reply(dev.pocketide.app.core.ReadPlan.wholeNote(p, text, from, intArg(a["end"])) + shown); return }
                    }
                    reply(AgentActions.read(if (text != null) paths() + p else paths(), { q -> staged[q] ?: store.readForAi(q) }, p, from, intArg(a["end"]), resultCap())); return
                }
                if (text != null && readFiles[p] == stamp) reply(dev.pocketide.app.core.ReadPlan.seen(p, null, null), "$label (unchanged)")
                else {
                    val shown = AgentActions.read(if (text != null) paths() + p else paths(), { q -> staged[q] ?: store.readForAi(q) }, p, cap = resultCap())  // a real file is readable even if the capped list missed it
                    // only a whole read counts as "seen": a cut one may be read again for the rest
                    if (text != null && !shown.contains(AgentActions.MORE)) readFiles[p] = stamp
                    reply(shown)
                }
            }
            "repo_map" -> {
                val map = dev.pocketide.app.core.RepoMap.build(dev.pocketide.app.core.RepoMap.gather(paths().toList()) { p -> store.read(p) })
                val notes = dev.pocketide.app.core.RepoMap.notes(try { hotFiles() } catch (e: Exception) { emptyList() }, try { verifyPlan()?.command } catch (e: Exception) { null })
                reply((map.ifBlank { "No code files with definitions were found." } + notes).trim())
            }
            "list_files" -> {
                val dir = g("dir")
                val named = clean(dir).trimEnd('/').let { it.isNotEmpty() && it != "." }
                // a named folder is walked on its own, so the whole-project note (which folders the capped list left out) is not needed
                reply(AgentActions.list(paths(dir), { p -> staged[p]?.length?.toLong() ?: store.size(p) }, dir) +
                    (if (named) "" else store.listNote().let { if (it.isEmpty()) "" else "\n\n$it" }))
            }
            "project_audit" -> {
                // a big project takes a while to read: scan in the background so Stop and the screen keep working
                val files = paths().toList()
                McpClient.background { val r = dev.pocketide.app.core.ProjectAudit.run(files, { store.size(it) }, { store.readBytes(it) }); McpClient.onMain { reply(r, label) } }
            }
            "find_code" -> {
                val q = g("query").trim()
                if (q.isEmpty()) { reply("Say what the code does in a few words."); return }
                // reads many files: off the main thread, so the screen keeps working
                val list = paths().toList()
                McpClient.background {
                    val r = try {
                        val files = dev.pocketide.app.core.RepoMap.gather(list) { p -> staged[p] ?: store.read(p) }
                        val words = dev.pocketide.app.core.RankedSearch.search(q, files, if (meaning != null) 12 else 8)
                        // 1.5.141: with an embeddings model set, the keyword ranking is merged with the ranking by meaning; a failed call
                        // leaves the keyword one
                        val byMeaning = meaning?.let { m -> try { m(q, files) } catch (e: Throwable) { trace("search by meaning failed: ${e.message}"); null } }
                        dev.pocketide.app.core.RankedSearch.format(q, if (byMeaning.isNullOrEmpty()) words.take(8) else dev.pocketide.app.core.SemanticIndex.fuse(words, byMeaning))
                    } catch (e: Throwable) { "Could not search: ${e.message}" }
                    McpClient.onMain { reply(r) }
                }
            }
            "search" -> {
                val base = paths()
                val scoped = paths(g("path"))
                if (scoped.size > base.size + 200) {
                    // a folder the capped list left out (1.5.167): many files to read, so off the main thread
                    val pat = g("pattern"); val dir = g("path"); val cap = resultCap()
                    McpClient.background {
                        val r = try { AgentActions.grep(scoped, { current(it) }, pat, dir, cap) } catch (e: Throwable) { AgentActions.GrepResult("Could not search: ${e.message}", 0) }
                        McpClient.onMain { reply(r.text, "$label (${r.hits})") }
                    }
                    return
                }
                val r = AgentActions.grep(scoped, { current(it) }, g("pattern"), g("path"), resultCap())
                val words = dev.pocketide.app.core.RankedSearch.queryOf(g("pattern"))
                if (r.hits > 0 || words.isEmpty()) { reply(r.text, "$label (${r.hits})"); return }
                // 1.5.141: a search that finds nothing (90 of 397 searches in the realproject baseline, a guessed name) also gets the
                // places closest to it, by keywords and by meaning when an embeddings model is set, so the next step reads instead of guessing
                val list = paths().toList()
                McpClient.background {
                    val near = try {
                        val files = dev.pocketide.app.core.RepoMap.gather(list) { p -> staged[p] ?: store.read(p) }
                        val kw = dev.pocketide.app.core.RankedSearch.search(words, files, 6)
                        val byMeaning = meaning?.let { m -> try { m(words, files) } catch (e: Throwable) { null } }
                        dev.pocketide.app.core.RankedSearch.near(if (byMeaning.isNullOrEmpty()) kw else dev.pocketide.app.core.SemanticIndex.fuse(kw, byMeaning, 6))
                    } catch (e: Throwable) { "" }
                    McpClient.onMain { reply(r.text + near, "$label (0)") }
                }
            }
            "write_file" -> {
                val p = clean(g("path"))
                if (!okPath(p)) { reply("Refused: '$p' is not a valid project path."); return }
                if (!dev.pocketide.app.core.FolderScope.allows(scope(), p)) { reply(dev.pocketide.app.core.FolderScope.refusal(scope(), p), "$label (outside the folder)"); return }
                notOwned(p)?.let { reply(it, "$label (another part's file)"); return }
                dev.pocketide.app.core.Unrequested.refusal(request, p)?.let { reply(it, "$label (skipped)"); return }
                if (store.isBinary(p)) { reply("Refused: $p is a binary file type." + if (DocText.isOffice(p)) Prompts.OFFICE_EDIT_HINT else ""); return }
                guarded(p)?.let { reply(it, "$label (protected)"); return }
                val rawContent = (a["content"] as? String) ?: ""
                val escaped = dev.pocketide.app.core.OverEscaped.looks(rawContent, p)
                val content = if (escaped) dev.pocketide.app.core.OverEscaped.decode(rawContent) else rawContent
                val escNote = if (escaped) dev.pocketide.app.core.OverEscaped.NOTE else ""
                val prior = if (store.exists(p)) current(p) else null
                (dev.pocketide.app.core.Rewrite.refusal(p, prior, content, job, small) ?: dev.pocketide.app.core.Rewrite.planRefusal(p, prior, content, job))?.let { reply(it, "$label (refused)"); return }
                testGate(p, prior, content, job)?.let { if (askTestNow(p, content, label) { r, s -> reply(r, s) }) return; reply(it, "$label (refused)"); return }
                val lines = content.count { it == '\n' } + 1
                readFiles.remove(p)
                val stub = dev.pocketide.app.core.StubCheck.find(p, content)
                if (stub != null) { stubs[p] = stub; note("$p looks unfinished: $stub", Ui.BAD) } else stubs.remove(p)
                val stubNote = if (stub != null) "\n" + dev.pocketide.app.core.StubCheck.note(p, stub) else ""
                if (agent) {
                    val gate = planGate(p, content)
                    if (gate != null) { if (write(FileEdit(p, gate.first))) { changed.add(p); reply("Wrote $p (${gate.first.count { it == '\n' } + 1} lines).${gate.second}", label) } else reply("Could not write $p.") }
                    else if (write(FileEdit(p, content))) { changed.add(p); writeMark = round; afterWrite(p, content, "Wrote $p ($lines lines)." + escNote + stubNote + dev.pocketide.app.core.JsonCheck.note(p, content), label, prior) { r, l -> reply(r, l) } } else reply("Could not write $p.")
                } else { staged[p] = content; reply("Staged $p ($lines lines) for the user's review; treat it as written.") }
            }
            "delete_file" -> {
                // 1.5.105: with no way to delete, gpt-oss-120b "removed" a feature by emptying its files, which were left in the project
                val p = clean(g("path"))
                val why = when {
                    !okPath(p) -> "'$p' is not a valid project path."
                    !dev.pocketide.app.core.FolderScope.allows(scope(), p) -> dev.pocketide.app.core.FolderScope.refusal(scope(), p)
                    !store.exists(p) -> dev.pocketide.app.core.MissingFile.note(p, paths())
                    remove == null || !agent -> "Deleting files is not available here. Leave $p and tell the person to remove it."
                    p == dev.pocketide.app.core.PlayTests.FILE && !dev.pocketide.app.core.Scope.asksToChangeTests(request) -> "The saved play tests are not deleted unless the person asks."
                    dev.pocketide.app.core.DeleteRules.isTest(p) && !dev.pocketide.app.core.Scope.asksToChangeTests(request) ->
                        "Not deleted: $p is a test, and the person did not ask to change tests. Fix the code so the test passes, or say why the test is wrong."
                    else -> notOwned(p) ?: dev.pocketide.app.core.Unrequested.refusal(request, p) ?: guarded(p)
                }
                if (why != null) { reply(why, "$label (refused)"); return }
                val others = paths().filter { it != p }
                val users = dev.pocketide.app.core.DeleteRules.usedBy(p, others) { current(it) }
                val names = current(p)?.let { dev.pocketide.app.core.DeleteRules.exported(p, it) }.orEmpty()
                if (remove?.invoke(p) == true) {
                    changed.add(p); readFiles.remove(p); stubs.remove(p); undefinedLeft.remove(p); swallowLeft.remove(p); writeMark = round
                    val uses = dev.pocketide.app.core.DeleteRules.stillUse(names, others) { current(it) }
                    reply("Deleted $p." + dev.pocketide.app.core.DeleteRules.leftovers(users, uses), label)
                } else reply("Could not delete $p.", "$label (failed)")
            }
            "edit_file" -> {
                val p = clean(g("path"))
                if (!dev.pocketide.app.core.FolderScope.allows(scope(), p)) { reply(dev.pocketide.app.core.FolderScope.refusal(scope(), p), "$label (outside the folder)"); return }
                notOwned(p)?.let { reply(it, "$label (another part's file)"); return }
                dev.pocketide.app.core.Unrequested.refusal(request, p)?.let { reply(it, "$label (skipped)"); return }
                guarded(p)?.let { reply(it, "$label (protected)"); return }
                val cur = current(p)
                if (cur == null) { reply(if (store.exists(p) && DocText.isOffice(p)) "No text file named $p." + Prompts.OFFICE_EDIT_HINT else dev.pocketide.app.core.MissingFile.note(p, paths())); return }
                val rawReplace = (a["replace"] as? String) ?: ""
                val escaped = dev.pocketide.app.core.OverEscaped.looks(rawReplace, p)
                val rawSearch = g("search")
                // a search escaped the same way is decoded only when the decoded text is really in the file
                val search = dev.pocketide.app.core.OverEscaped.decode(rawSearch).takeIf { it != rawSearch && rawSearch !in cur && it in cur } ?: rawSearch
                val block = EditBlock(p, search, if (escaped) dev.pocketide.app.core.OverEscaped.decode(rawReplace) else rawReplace)
                val every = a["all"] == true || a["all"]?.toString()?.trim()?.lowercase() == "true"
                val res = (if (every) EditBlocks.applyAll(cur, block) else EditBlocks.apply(cur, block)).let { r ->
                    // 1.5.187: in a rename request, a bare old name → new name renames every use instead of being refused as ambiguous
                    if (r.text == null && !every && RENAME.containsMatchIn(request)) EditBlocks.renameAll(cur, block) ?: r else r }
                val escNote = if (escaped || search != rawSearch) dev.pocketide.app.core.OverEscaped.NOTE else ""
                val newText = res.text
                if (newText == null) { reply("Edit not applied to $p: ${res.error ?: "the search text was not found"}. Read the file again and copy the text exactly.", "$label (failed)"); return }
                testGate(p, cur, newText, job)?.let { if (askTestNow(p, newText, label) { r, s -> reply(r, s) }) return; reply(it, "$label (refused)"); return }
                readFiles.remove(p)
                if (agent) {
                    val gate = planGate(p, newText)
                    if (gate != null) { if (write(FileEdit(p, gate.first))) { changed.add(p); reply("Edited $p.${gate.second}", label) } else reply("Could not write $p.") }
                    else if (write(FileEdit(p, newText))) { changed.add(p); writeMark = round; afterWrite(p, newText, "Edited $p." + escNote + dev.pocketide.app.core.JsonCheck.note(p, newText), label, cur) { r, l -> reply(r, l) } } else reply("Could not write $p.")
                } else { staged[p] = newText; reply("Staged the edit to $p for the user's review; treat it as applied.") }
            }
            "multi_edit" -> {
                val items = dev.pocketide.app.core.MultiEdit.parse(a["edits"]).map { dev.pocketide.app.core.MultiEdit.Item(clean(it.path), it.search, it.replace) }
                if (items.isEmpty()) { reply("multi_edit needs an edits list; each item has path, search and replace."); return }
                val out = dev.pocketide.app.core.MultiEdit.run(items, { current(it) }) { p ->
                    if (!dev.pocketide.app.core.FolderScope.allows(scope(), p)) dev.pocketide.app.core.FolderScope.refusal(scope(), p)
                    else notOwned(p) ?: dev.pocketide.app.core.Unrequested.refusal(request, p) ?: guarded(p)
                }
                val summary = "Applied ${out.applied} of ${items.size} edits in ${out.texts.size} file${if (out.texts.size == 1) "" else "s"}." + out.notes.joinToString("") { "\n$it" }
                if (out.texts.isEmpty()) { reply(summary + "\nRead the files again and copy the text exactly.", "$label (failed)"); return }
                out.texts.entries.firstNotNullOfOrNull { (p, t) -> testGate(p, current(p), t, job) }?.let { reply(it + " Nothing else in this multi_edit was written: send the other edits again without ${dev.pocketide.app.core.PlayTests.FILE}.", "$label (refused)"); return }
                if (!agent) { staged.putAll(out.texts); reply(summary + "\nStaged for the user's review; treat the applied edits as done."); return }
                val paths = out.texts.keys.toList()
                val msgs = ArrayList<String>()
                fun step(i: Int) {
                    if (i >= paths.size) { reply(summary + msgs.joinToString(""), label); return }
                    val p = paths[i]
                    val gate = planGate(p, out.texts.getValue(p))
                    val t = gate?.first ?: out.texts.getValue(p)
                    readFiles.remove(p)
                    val prior = current(p)
                    if (!write(FileEdit(p, t))) { msgs.add("\nCould not write $p."); step(i + 1); return }
                    changed.add(p)
                    if (gate != null) { msgs.add(gate.second); step(i + 1); return }
                    writeMark = round
                    afterWrite(p, t, "", label, prior) { r, _ -> if (r.isNotBlank()) msgs.add("\n" + r.trim()); step(i + 1) }
                }
                step(0)
            }
            "apply_patch" -> {
                // 1.5.106: gpt-oss-120b sends the patch as "patch", not "input" (the name its training used); every such call failed
                // with "must start with *** Begin Patch" and it retried up to 18 times. Any argument holding the patch is accepted.
                val parsed = dev.pocketide.app.core.ApplyPatch.parse(dev.pocketide.app.core.ApplyPatch.argument(a))
                if (parsed.error != null) { reply("Patch not applied: ${parsed.error}", "$label (failed)"); return }
                fun refused(p: String): String? = when {
                    !okPath(p) -> "'$p' is not a valid project path"
                    !dev.pocketide.app.core.FolderScope.allows(scope(), p) -> dev.pocketide.app.core.FolderScope.refusal(scope(), p)
                    store.isBinary(p) -> "$p is a binary file type"
                    else -> notOwned(p) ?: dev.pocketide.app.core.Unrequested.refusal(request, p) ?: guarded(p)
                }
                // work out every file's new text first; nothing is written unless the whole patch applies
                val writes = LinkedHashMap<String, String>()
                val deletes = ArrayList<String>()
                val problems = ArrayList<String>()
                for (op in parsed.ops) {
                    val p = clean(op.path)
                    refused(p)?.let { problems.add("$p: $it"); continue }
                    when (op) {
                        is dev.pocketide.app.core.ApplyPatch.Add -> writes[p] = op.text
                        is dev.pocketide.app.core.ApplyPatch.Delete -> if (writes.remove(p) == null && current(p) == null) problems.add("$p: no such text file to delete") else deletes.add(p)
                        is dev.pocketide.app.core.ApplyPatch.Update -> {
                            val cur = writes[p] ?: current(p)
                            if (cur == null) { problems.add("$p: no such text file (use *** Add File to create it)"); continue }
                            val res = dev.pocketide.app.core.ApplyPatch.apply(cur, op.chunks)
                            if (res.text == null) { problems.add("$p: ${res.error}"); continue }
                            val to = op.moveTo?.let { clean(it) }
                            if (to != null && to != p) {
                                refused(to)?.let { problems.add("$to: $it"); continue }
                                writes.remove(p); writes[to] = res.text; deletes.add(p)
                            } else writes[p] = res.text
                        }
                    }
                }
                if (deletes.isNotEmpty() && (remove == null || !agent)) problems.add("deleting or renaming files is not available here (" + deletes.joinToString(", ") + "); leave those files, or write the new one and say which old one the user should remove")
                writes.entries.firstNotNullOfOrNull { (p, t) -> testGate(p, current(p), t, job) }?.let { problems.add(it) }
                if (deletes.any { it == dev.pocketide.app.core.PlayTests.FILE } && !dev.pocketide.app.core.Scope.asksToChangeTests(request)) problems.add("${dev.pocketide.app.core.PlayTests.FILE}: the saved play tests are not deleted unless the person asks")
                if (problems.isNotEmpty()) { reply("Patch not applied (nothing was changed):\n" + problems.joinToString("\n") { "- $it" } + "\nRead the files again and send a corrected patch" + (if (testProposals.isNotEmpty()) " without the play-test change" else "") + ".", "$label (failed)"); return }
                if (!agent) { staged.putAll(writes); reply("Staged ${writes.size} file${if (writes.size == 1) "" else "s"} for the user's review; treat the patch as applied."); return }
                val msgs = ArrayList<String>()
                val paths = writes.keys.toList()
                fun finishDeletes() {
                    for (d in deletes) if (remember(d).let { remove?.invoke(d) == true }) { changed.add(d); readFiles.remove(d); msgs.add("\nDeleted $d.") } else msgs.add("\nCould not delete $d.")
                    if (deletes.isNotEmpty()) writeMark = round
                    reply("Applied the patch: ${paths.size} file${if (paths.size == 1) "" else "s"} written" + (if (deletes.isEmpty()) "" else ", ${deletes.size} deleted") + "." + msgs.joinToString(""), label)
                }
                fun step(i: Int) {
                    if (i >= paths.size) { finishDeletes(); return }
                    val p = paths[i]
                    val gate = planGate(p, writes.getValue(p))
                    val t = gate?.first ?: writes.getValue(p)
                    readFiles.remove(p)
                    val prior = if (store.exists(p)) current(p) else null
                    val stub = dev.pocketide.app.core.StubCheck.find(p, t)
                    if (stub != null) { stubs[p] = stub; note("$p looks unfinished: $stub", Ui.BAD) } else stubs.remove(p)
                    if (!write(FileEdit(p, t))) { msgs.add("\nCould not write $p."); step(i + 1); return }
                    changed.add(p)
                    if (gate != null) { msgs.add(gate.second); step(i + 1); return }
                    writeMark = round
                    afterWrite(p, t, "", label, prior) { r, _ -> if (r.isNotBlank()) msgs.add("\n" + r.trim()); step(i + 1) }
                }
                step(0)
            }
            "remember" -> {
                val n = g("note").trim().replace(Regex("\\s+"), " ").take(300)
                if (n.isEmpty()) { reply("remember needs a note."); return }
                if (remembered >= 3) { reply("Three rules were saved in this request already; that is the limit.", "$label (skipped)"); return }
                val f = dev.pocketide.app.core.Memory.FILE
                if (write(FileEdit(f, dev.pocketide.app.core.Memory.remember(store.read(f), n)))) {
                    remembered++; changed.add(f); writeMark = round
                    note("Remembered for this project: $n", Ui.GOOD)
                    reply("Saved to $f.")
                } else reply("Could not write $f.")
            }
            "explore" -> {
                if (nested) { reply("The explorer cannot start another explorer. Answer from what you have read."); return }
                val q = g("question").trim()
                if (q.isEmpty()) { reply("explore needs a question."); return }
                explore(q) { text, stopped ->
                    if (stopped) emit(Result("", staged, changed.toList(), labels, false, stopped = true))
                    else reply(text, label)
                }
            }
            "history" -> {
                McpClient.background {
                    val r = try { dev.pocketide.app.core.VcsAssist.run(dev.pocketide.app.core.Vcs(store.root), g("action"), g("path"), g("commit")) }
                        catch (e: Throwable) { "history failed: " + (e.message ?: e.javaClass.simpleName) }
                    McpClient.onMain { reply(r, label) }
                }
            }
            dev.pocketide.app.core.ProjectsTool.NAME -> {
                val pr = projects
                if (pr == null) { reply("The projects tool is not available here."); return }
                McpClient.background {
                    val r = try { pr(a) } catch (e: Throwable) { "projects failed: " + (e.message ?: e.javaClass.simpleName) }
                    McpClient.onMain { reply(r, label) }
                }
            }
            "game_assets" -> {
                var n = 0
                val env = cacheEnv { n++ < 200 }
                McpClient.background {
                    val r = try { dev.pocketide.app.core.GameAssets.run(a, env) }
                        catch (e: Throwable) { "${c.name} failed: " + (e.message ?: e.javaClass.simpleName) }
                    McpClient.onMain { reply(r, label) }
                }
            }
            "look_at_images" -> {
                val lk = look
                if (lk == null) { reply("Looking at pictures needs agent mode with a page in the project."); return }
                val dir = clean(g("dir")).trim('/')
                val all = store.imagesIn(dir)
                if (all.isEmpty()) { reply("No pictures found in '${dir.ifEmpty { "the project" }}'. Use list_files to find the folder."); return }
                val page = ((a["page"] as? Number)?.toInt() ?: g("page").toIntOrNull() ?: 1).coerceIn(1, dev.pocketide.app.core.ContactSheet.pages(all.size))
                val names = dev.pocketide.app.core.ContactSheet.slice(all, page)
                val sheet = ".pocketide-sheet.html"
                if (!store.write(sheet, dev.pocketide.app.core.ContactSheet.html(dir, all, page))) { reply("Could not prepare the picture page."); return }
                phase("Looking at the pictures")
                lk("", sheet, "") { report, img ->
                    store.delete(sheet)
                    val m = settings.model(settings.provider).ifBlank { Providers.byId(settings.provider).model }
                    val sees = dev.pocketide.app.core.Images.providerSees(settings.provider, m)
                    if (img != null && sees) shots.add(img)
                    val head = "Pictures ${(page - 1) * dev.pocketide.app.core.ContactSheet.PER_PAGE + 1}-${(page - 1) * dev.pocketide.app.core.ContactSheet.PER_PAGE + names.size} of ${all.size} " +
                        "(page $page of ${dev.pocketide.app.core.ContactSheet.pages(all.size)}), five per row, left to right, in this order:\n" + names.joinToString(", ") { it.substringAfterLast('/') }
                    reply(head + when {
                        img != null && sees -> "\nThe screenshot follows the tool results; each picture has its file name under it."
                        img != null -> "\n(This model cannot see images, so only the names are given.)"
                        else -> ""
                    }, label)
                }
            }
            "look_at_preview" -> {
                val lk = look
                if (lk == null) { reply(dev.pocketide.app.core.LookOffer.NO_PAGE); return }
                // suggest mode: edits wait for the person's approval and are not on disk, so the page is the saved version
                val savedOnly = if (!agent && staged.isNotEmpty()) "\nNote: your suggested edits are not saved yet, so this shows the page as it is saved now, without them." else ""
                if (g("test").isNotBlank()) {
                    phase("Playing the saved tests")
                    playSaved(g("test")) { report, img, _ ->
                        lookMark = round
                        if (report.startsWith("PLAY TESTS: PASS")) { checkMark = round; promoteChecked() }
                        val m = settings.model(settings.provider).ifBlank { Providers.byId(settings.provider).model }
                        if (img != null && dev.pocketide.app.core.Images.providerSees(settings.provider, m)) shots.add(img)
                        reply(report + savedOnly)
                    }
                    return
                }
                phase("Looking at the page")
                lk(g("steps"), g("page"), g("trace")) { report, img ->
                    lookMark = round
                    // only a page that passes the verdict counts as a check for plan steps
                    if (report.contains("VERDICT: PASS")) { checkMark = round; promoteChecked() }
                    val m = settings.model(settings.provider).ifBlank { Providers.byId(settings.provider).model }
                    val sees = dev.pocketide.app.core.Images.providerSees(settings.provider, m)
                    if (img != null && sees) shots.add(img)
                    reply(report + savedOnly + when {
                        img != null && sees -> "\nThe screenshot follows the tool results. Compare it with what the job needs."
                        img != null -> "\n(This model cannot see images, so only the text report is given.)"
                        else -> ""
                    })
                }
            }
            "edit_lines" -> {
                val p = clean(g("path"))
                if (!dev.pocketide.app.core.FolderScope.allows(scope(), p)) { reply(dev.pocketide.app.core.FolderScope.refusal(scope(), p), "$label (outside the folder)"); return }
                notOwned(p)?.let { reply(it, "$label (another part's file)"); return }
                dev.pocketide.app.core.Unrequested.refusal(request, p)?.let { reply(it, "$label (skipped)"); return }
                guarded(p)?.let { reply(it, "$label (protected)"); return }
                val cur = current(p)
                if (cur == null) { reply(dev.pocketide.app.core.MissingFile.note(p, paths())); return }
                val st = intArg(a["start"]); val en = intArg(a["end"])
                if (st == null || en == null) { reply("edit_lines needs start and end line numbers.", "$label (failed)"); return }
                val res = EditBlocks.applyLines(cur, st, en, (a["text"] as? String) ?: "")
                // 1.5.184 (local benchmark l13): Gemini Nano sent start 3, end 5 to INSERT a case, and deleted three others
                dev.pocketide.app.core.EditBlocks.linesLost(cur, st, en, (a["text"] as? String) ?: "", request)?.let { lost ->
                    val key = "$p:$st:$en"
                    if (key !in linesWarned) { linesWarned.add(key); reply("Not applied: start $st and end $en replace lines $st-$en, and the new text " +
                        "does not keep ${lost.joinToString("; ") { "\"" + it.trim().take(50) + "\"" }}. To INSERT after line N without removing anything, use " +
                        "start = N+1 and end = N. If removing those lines is what you want, send the same edit again.", "$label (would delete lines)"); return }
                }
                val newText = res.text
                if (newText == null) { reply("Edit not applied to $p: ${res.error}.", "$label (failed)"); return }
                testGate(p, cur, newText, job)?.let { if (askTestNow(p, newText, label) { r, s -> reply(r, s) }) return; reply(it, "$label (refused)"); return }
                readFiles.remove(p)
                if (agent) {
                    val gate = planGate(p, newText)
                    if (gate != null) { if (write(FileEdit(p, gate.first))) { changed.add(p); reply("Edited lines $st-$en of $p.${gate.second}", label) } else reply("Could not write $p.") }
                    else if (write(FileEdit(p, newText))) { changed.add(p); writeMark = round; afterWrite(p, newText, "Edited lines $st-$en of $p. Line numbers after line $en have shifted; read again before another edit_lines.", label, cur) { r, l -> reply(r, l) } } else reply("Could not write $p.")
                } else { staged[p] = newText; reply("Staged the edit to $p for the user's review; treat it as applied.") }
            }
            "run_command" -> {
                val cmd = g("command").trim()
                if (cmd.isEmpty()) { reply("No command given."); return }
                dev.pocketide.app.core.ProjectGuard.installRefusal(cmd)?.let { reply(it, "$label (not allowed)"); return }
                dev.pocketide.app.core.ProjectGuard.outsideRefusal(cmd, store.root.absolutePath)?.let { reply(it, "$label (outside the project)"); return }
                dev.pocketide.app.core.ProjectGuard.archiveRefusal(cmd, request) { store.size(it) }?.let { reply(it, "$label (big archive)"); return }
                if (dev.pocketide.app.core.ProjectGuard.readsEngine(cmd)) { reply(dev.pocketide.app.core.ProjectGuard.ENGINE_REFUSAL, "$label (engine)"); return }
                dev.pocketide.app.core.FolderScope.commandViolation(scope(), cmd)?.let { reply(dev.pocketide.app.core.FolderScope.commandRefusal(scope(), it), "$label (outside the folder)"); return }
                if (!linuxRunning()) dev.pocketide.app.core.NoLinuxCommand.program(cmd)?.let { reply(dev.pocketide.app.core.NoLinuxCommand.reply(it, look != null), "$label (no $it here)"); return }
                val finished = { code: Int, out: String ->
                    // a command that tests or runs the project counts as a check; one that passed also confirms steps waiting for a check.
                    // 1.5.170: not `ls`, `cat` or `node --version` (Proof.isCheckCommand): those marked the work checked and ticked plan steps
                    val exists = { p: String -> store.exists(p) || staged.containsKey(p) }
                    if (dev.pocketide.app.core.Proof.isCheckCommand(cmd, exists)) {
                        checkMark = round
                        // 1.5.175: code that only prints does not tick plan steps: nothing in it can fail
                        if (code == 0 && !dev.pocketide.app.core.Proof.printOnly(cmd, exists)) promoteChecked()
                    }
                    lastCommandOutput = "$ $cmd (exit $code)\n" + out.takeLast(3000)
                    // a command (rm, mv, a script) can leave the page loading a file that is gone: say so at once
                    val gone = try { dev.pocketide.app.core.PageRefs.missing(paths().toList(), { p -> store.read(p) }, { p -> store.size(p) }) } catch (e: Exception) { emptyList() }
                    val r = AgentActions.runResult(cmd, code, out) + (if (gone.isEmpty()) "" else "\n\nAfter this command the page is broken: " + gone.joinToString(" ") + " Put it back or recreate it now, before anything else.")
                    reply(r, label + if (code == 0) "" else " (exit $code)")
                }
                if (!agent && staged.isNotEmpty()) {
                    // staged files are not on disk yet, so a command would not see them
                    reply("Not run: the files you changed are staged for review and not on disk yet, so the command would test the old versions. Tell the user to apply the changes and run: $cmd")
                } else when (decide(cmd)) {
                    dev.pocketide.app.core.CommandRules.Decision.RUN -> exec(cmd, finished)
                    dev.pocketide.app.core.CommandRules.Decision.DENY -> reply("Not run: the person's command rules block `" + (dev.pocketide.app.core.CommandRules.blockedBy(cmd, rules) ?: cmd) +
                        "`. Do not try the same thing another way; find a different approach or tell the person what you need.", "$label (blocked)")
                    else -> box.addView(askRunTimed(cmd, finished) { reply("The user declined to run this command.", "$label (skipped)") })
                }
                scrollDown()
            }
            "doc_outline" -> reply(docEdit(g("path"), "outline", emptyMap()), label)
            "doc_edit" -> {
                if (!dev.pocketide.app.core.FolderScope.allows(scope(), clean(g("path")))) { reply(dev.pocketide.app.core.FolderScope.refusal(scope(), clean(g("path"))), "$label (outside the folder)"); return }
                val args = LinkedHashMap<String, String>()
                for ((k, v) in a) if (k != "path" && k != "action" && v != null) args[k] = if (v is Double && v % 1.0 == 0.0) v.toInt().toString() else v.toString()
                val r = docEdit(g("path"), g("action"), args)
                val failed = r.startsWith("Not applied") || r.startsWith("No file") || r.startsWith("Could not")
                if (!failed && agent) { changed.add(clean(g("path"))); writeMark = round }
                reply(r, label + if (failed) " (failed)" else "")
            }
            "find_symbol" -> reply(dev.pocketide.app.core.Symbols.find(g("name"), paths(), { p -> current(p) }))
            "find_references" -> {
                val p = clean(g("path")); val name = g("name").trim()
                val t = current(p)
                val h = dev.pocketide.app.core.LspHook.find
                when {
                    name.isEmpty() -> reply("Give the name to look up.")
                    t == null -> reply("No text file named $p.")
                    h == null -> reply("The language server is not available here. Use find_symbol.")
                    else -> h(p, t, name, if (g("kind").trim().lowercase() == "definition") "definition" else "references") { r -> reply(r) }
                }
            }
            "file_outline" -> {
                val p = clean(g("path"))
                val t = current(p)
                if (t == null) reply("No text file named $p." + (if (store.exists(p)) " Use doc_outline for documents." else ""))
                else reply(dev.pocketide.app.core.Symbols.outline(p, t))
            }
            "update_plan" -> {
                // A step counts as done only after a check ran following the last file change (feature-list rule from the research).
                // The feature list is protected: steps may change status but not disappear or be reworded within one request.
                var raw = Tools.planSteps(c)
                val dropped = lastSteps.filter { old -> raw.none { it.first == old.first } }
                // 1.5.164: refused once only. In the 2009scape field run the model reworded the same steps four times and was refused each
                // time; now a second rewording is taken, with the steps it left out kept at the end, so nothing silently disappears.
                if (dropped.isNotEmpty() && steeredSincePlan) {
                    // 1.5.167: the person changed direction ("focus on the quests"), so the plan follows them; the old steps are not forced back in
                    trace("plan: replaced after the person's message; dropped ${dropped.size} step(s)")
                } else if (dropped.isNotEmpty() && planRefused) {
                    trace("plan: second rewording taken; kept ${dropped.size} left-out step(s) at the end")
                    raw = raw + dropped
                } else if (dropped.isNotEmpty()) {
                    planRefused = true
                    reply("Plan not changed. Keep every step: these were left out or reworded: " + dropped.take(6).joinToString("; ") { "\"${it.first}\"" } +
                        ". Post the full plan again with the same step texts; only statuses may change, and new steps may be added.")
                    return
                }
                // Only where a check is possible (a page to look at, or tests/checks to run); otherwise "done" is taken as given.
                // 1.5.184: a run that only wrote notes (DATES.md from research) has nothing a check could show, so "done" is taken as given
                val unverified = agent && writeMark > checkMark && !docsOnly() && (look != null || verifyPlan() != null)
                val steps = raw.map { (t, st) -> if (st == "done" && unverified) t to "doing" else t to st }
                for ((t, st) in raw) if (st == "done" && unverified) pendingDone.add(t) else pendingDone.remove(t)
                lastSteps = steps
                steeredSincePlan = false
                tracker.update(steps)
                onPlan(steps)
                if (unverified && raw.any { it.second == "done" }) note("A step stays open until it has been checked; it is marked done when a check passes.", Ui.MUTED)
                val next = steps.firstOrNull { it.second != "done" && it.second != "failed" }
                reply(if (next != null && steps.toString() == lastShown) "Plan shown to the user. It has not changed since the last update, so do the next step now instead of repeating the plan: \"${next.first}\"." else "Plan shown to the user.")
                lastShown = steps.toString()
            }
            "report_finding" -> {
                // 1.5.164 (PremiseCheck): ends the run with the finding as the answer; a correct outcome, not a failure
                val ev = g("evidence").trim()
                if (ev.isEmpty()) { reply("Give the evidence: what you searched for or ran, and what it showed."); return }
                trace("report_finding: ${g("kind")}")
                reply("Reported.")
                finding = dev.pocketide.app.core.PremiseCheck.answer(g("kind"), ev, g("question"))
            }
            "advisor" -> {
                if (advisor == null) { reply("No advisor is set up. Continue on your own."); return }
                if (advisorUsed >= advisor.maxCalls) { reply("The advisor limit for this request is reached. Continue on your own."); return }
                consult(dev.pocketide.app.core.Advisor.Purpose.HELP) { adv -> reply(adv?.let { "Advisor: $it" } ?: "The advisor could not be reached. Continue on your own.", "Asked the advisor") }
            }
            // 1.5.185: the assistant's knowledge of the app (AppHelp) and the person's settings (AppSettingsTool)
            "app_help" -> {
                val q = g("query").ifBlank { g("question") }.ifBlank { g("text") }.ifBlank { request.take(160) }
                reply(dev.pocketide.app.core.AppHelp.answer(q, if (small) 4 else 6, if (small) 1400 else 2400) { k ->
                    AppSettingsBridge.value(ctx, settings, k)?.let { dev.pocketide.app.core.AppSettingsTool.shown(k, it) } })
            }
            dev.pocketide.app.core.AppSettingsTool.NAME -> {
                val tool = dev.pocketide.app.core.AppSettingsTool
                val read = { k: String -> AppSettingsBridge.value(ctx, settings, k) }
                val key = g("key").ifBlank { g("name") }.ifBlank { g("setting") }
                if (g("action").trim().lowercase() != "propose" && g("value").isBlank()) reply(tool.list(key, read))
                else when (val chk = tool.check(key, g("value"))) {
                    is dev.pocketide.app.core.AppSettingsTool.Check.No -> { trace("settings proposal refused: ${key.take(60)}"); reply(chk.why) }
                    is dev.pocketide.app.core.AppSettingsTool.Check.Ok -> {
                        val now = read(chk.item.setting!!)
                        if (now == chk.value) reply("Already ${chk.shown}: nothing to change. Tell the person it is already set (${dev.pocketide.app.core.AppMap.path(chk.item)}).")
                        else { smallProposed = true; settingsCard(chk, tool.shown(chk.item.setting, now), g("why")); reply(tool.proposed(chk, tool.shown(chk.item.setting, now))) }
                    }
                }
            }
            "web_search" -> {
                // models often name the argument after another search tool (pattern, q)
                val q = g("query").ifBlank { g("q") }.ifBlank { g("pattern") }.ifBlank { g("search") }.trim()
                if (q.isEmpty()) { reply("Give something to search for: web_search needs query."); return }
                if (emptySearches >= 4) { reply(NO_SEARCH); return }
                McpClient.background {
                    val r = WebClient.search(q, settings.braveKey)
                    McpClient.onMain {
                        // a benchmark run searched ten more times after the free search stopped answering, and never wrote the file
                        if (r.startsWith(dev.pocketide.app.core.WebResearch.NO_RESULTS_HELP.take(30))) {
                            emptySearches++
                            reply(if (emptySearches >= 4) NO_SEARCH else if (emptySearches >= 2) "$r\n$SEARCH_ELSE" else r)
                        } else { emptySearches = 0; reply(r) }
                    }
                }
            }
            "sql_query" -> {
                val path = g("path").trim().removePrefix("./")
                if (!dev.pocketide.app.core.DbView.isDbFile(path) || !store.exists(path)) { reply("No SQLite file at $path. Database files in the project: " + store.list().filter { dev.pocketide.app.core.DbView.isDbFile(it) }.ifEmpty { listOf("none") }.joinToString(", ") + "."); return }
                val sqlText = g("sql")
                if (!dev.pocketide.app.core.DbView.readsOnly(sqlText)) { reply("sql_query only reads: give one SELECT, WITH, EXPLAIN or PRAGMA statement. To change data, change the code that writes it, or write a script and run it."); return }
                val file = java.io.File(store.root, path)
                McpClient.background {
                    val r = DbAccess.run(file, sqlText)
                    val text = r.fold({ t -> dev.pocketide.app.core.DbView.grid(t.columns, t.rows) + (if (t.rows.size >= dev.pocketide.app.core.DbView.PAGE) "\n(first ${dev.pocketide.app.core.DbView.PAGE} rows; add LIMIT and OFFSET for more)" else "") }, { "SQL error: ${it.message}" })
                    McpClient.onMain { reply(text) }
                }
            }
            "http_request" -> {
                val url = dev.pocketide.app.core.HttpCall.normalize(g("url"))
                if (url == null) { reply("Give a full address such as http://localhost:3000/api/items."); return }
                if (!dev.pocketide.app.core.HttpCall.isLocal(url)) { reply("http_request reaches servers on this phone only (localhost or 127.0.0.1). To read a public page, use web_fetch."); return }
                val method = g("method").trim().uppercase().ifEmpty { "GET" }
                if (method !in dev.pocketide.app.core.HttpCall.METHODS) { reply("Unknown method $method. Use one of: " + dev.pocketide.app.core.HttpCall.METHODS.joinToString(", ") + "."); return }
                val secrets = try { dev.pocketide.app.core.Secrets(java.io.File(Projects.metaDir(ctx, store.root.name), "secrets.json")).all() } catch (e: Exception) { emptyMap() }
                val req = dev.pocketide.app.core.HttpCall.Request(method, url, dev.pocketide.app.core.HttpCall.parseHeaders(g("headers")), g("body"))
                McpClient.background {
                    val resp = dev.pocketide.app.core.HttpCall.send(dev.pocketide.app.core.HttpCall.filled(req, secrets))
                    val text = if (resp.code == 0) dev.pocketide.app.core.HttpCall.failure(req, resp) else dev.pocketide.app.core.HttpCall.describe(resp, secrets, 8000)
                    McpClient.onMain { reply(text) }
                }
            }
            "library_docs" -> {
                val paths = store.list()
                val used = dev.pocketide.app.core.LibDocs.used(paths) { p -> try { store.read(p) } catch (e: Exception) { null } }
                val py = paths.any { it.endsWith(".py") } && paths.none { it.endsWith("package.json") || Regex("""\.(html?|m?js|tsx?|jsx)$""").containsMatchIn(it) }
                val lib = dev.pocketide.app.core.LibDocs.find(g("name"), used, py)
                if (!dev.pocketide.app.core.LibDocs.validName(lib.name, lib.kind)) { reply("\"${g("name").take(80)}\" is not a package name. Give the name as npm or PyPI knows it, such as three or flask."); return }
                val topic = g("topic")
                McpClient.background { val r = libraryDocs(lib, topic); McpClient.onMain { reply(r) } }
            }
            "web_fetch" -> {
                val url = dev.pocketide.app.core.WebResearch.normalizeUrl(g("url"))
                if (url == null) { reply("That is not a usable public web address. Give a full address such as https://example.com/page."); return }
                val host = dev.pocketide.app.core.WebResearch.hostOf(url).removePrefix("www.")
                fun go() { McpClient.background { val r = WebClient.fetch(url); McpClient.onMain { if (r.startsWith("Web page")) sourceList.add(url); reply(r) } } }
                if (host in settings.webAllowed || host in webSession) go()
                // 1.5.184: the hybrid benchmark runs with no one watching; it stalled on this card twice (task 06). Reads are allowed for this
                // run only (not saved to the person's always-allowed sites), and the transcript says so.
                else if (HybridBench.running) { webSession.add(host); trace("web read of $host allowed for the benchmark run"); note("Allowed reading $host for the benchmark run.", Ui.MUTED); go() }
                else {
                    val card = Ui.vertical(ctx)
                    card.background = Ui.rounded(Ui.SURFACE2, 10, ctx, Ui.LINE)
                    card.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 10), Ui.dp(ctx, 12), Ui.dp(ctx, 10))
                    card.addView(Ui.text(ctx, "Let the assistant read $host?", 14f, Ui.TEXT))
                    card.addView(Ui.text(ctx, url.take(160), 12f, Ui.MUTED, true))
                    val row = Ui.horizontal(ctx)
                    fun drop() { (card.parent as? android.view.ViewGroup)?.removeView(card) }
                    row.addView(Ui.button(ctx, "Allow", true) { drop(); webSession.add(host); go() })
                    row.addView(Ui.button(ctx, "Always") { drop(); settings.webAllowed = settings.webAllowed + host; go() })
                    row.addView(Ui.button(ctx, "Deny") { drop(); reply("The user declined to let you read that page.", "$label (skipped)") })
                    card.addView(row)
                    box.addView(card); scrollDown()
                }
            }
            else -> {
                val tool = Tools.extra.firstOrNull { it.qualified == c.name }
                val server = tool?.let { McpClient.server(settings, it) }
                if (tool == null || server == null) { badCalls++; reply("Unknown tool ${c.name}. Available: ${Tools.NAMES.joinToString(", ")}."); return }
                fun go() { McpClient.background { val r = McpClient.call(server, tool.name, a); McpClient.onMain { reply(r) } } }
                if (tool.qualified in settings.mcpAlways) go()
                else {
                    val card = Ui.vertical(ctx)
                    card.background = Ui.rounded(Ui.SURFACE2, 10, ctx, Ui.LINE)
                    card.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 10), Ui.dp(ctx, 12), Ui.dp(ctx, 10))
                    card.addView(Ui.text(ctx, "Allow ${tool.server}: ${tool.name}?", 14f, Ui.TEXT))
                    card.addView(Ui.text(ctx, "Outside server " + server.url.substringAfter("://").substringBefore('/') + "\n" + dev.pocketide.app.core.Mcp.summarize(a), 12f, Ui.MUTED, true))
                    val row = Ui.horizontal(ctx)
                    fun drop() { (card.parent as? android.view.ViewGroup)?.removeView(card) }
                    row.addView(Ui.button(ctx, "Allow once", true) { drop(); go() })
                    row.addView(Ui.button(ctx, "Always") { drop(); settings.mcpAlways = settings.mcpAlways + tool.qualified; go() })
                    row.addView(Ui.button(ctx, "Deny") { drop(); reply("The user declined to use this tool.", "$label (skipped)") })
                    card.addView(row)
                    box.addView(card); scrollDown()
                }
            }
        }
    }
}
