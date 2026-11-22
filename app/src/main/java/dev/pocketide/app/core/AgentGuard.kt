package dev.pocketide.app.core

/*
 * Agent safety and progress helpers (GPL edition).
 * The repetition check and the "unfinished plan" reminder follow ideas from MobileCoder
 * (GenerationRepetitionGuard, LocalAgentWorkflow), Copyright (c) 2026 dbal2969-ai, GPL-3.0-or-later.
 * Rewritten for PocketIDE; see NOTICE.
 */

/** Notices when generated text starts repeating the same block of lines, so a stuck model can be stopped. */
class RepetitionGuard {
    private val lines = ArrayDeque<String>()
    private val line = StringBuilder()
    private var skipping = false
    var detected = false
        private set

    /** Adds streamed text; true once a block of lines has repeated three times in a row. */
    fun append(chunk: String): Boolean {
        if (detected) return true
        for (ch in chunk) {
            if (ch == '\n') {
                if (!skipping) {
                    val done = line.toString().trimEnd()
                    if (done.isNotBlank()) lines.addLast(done)
                    while (lines.size > 36 || lines.sumOf { it.length } > 16_384) lines.removeFirst()
                    detected = repeatedSuffix()
                } else lines.clear()
                line.clear(); skipping = false
                if (detected) return true
            } else if (!skipping) {
                if (line.length < 4096) line.append(ch) else { line.clear(); skipping = true }
            }
        }
        return false
    }

    private fun repeatedSuffix(): Boolean {
        val tail = lines.toList()
        for (size in 1..minOf(12, tail.size / 3)) {
            val start = tail.size - size
            if (tail.subList(start, tail.size).sumOf { it.length } < 80) continue
            if ((0 until size).all { o -> tail[start + o] == tail[start - size + o] && tail[start + o] == tail[start - size * 2 + o] }) return true
        }
        return false
    }
}

/** Tracks the checklist the model keeps with update_plan, and nudges it when it stops with steps left. */
class TaskTracker(private val maxReminders: Int = MAX_REMINDERS) {
    var steps: List<Pair<String, String>> = emptyList()
        private set
    private var reminders = 0

    fun update(s: List<Pair<String, String>>) { if (s.isNotEmpty()) steps = s }

    val hasPlan: Boolean get() = steps.isNotEmpty()

    /** Steps that are not done and not failed. */
    fun unfinished(): List<String> = steps.filter { it.second.lowercase() in OPEN }.map { it.first }

    /** Steps the model marked failed: not nudged again, but the work is not done. */
    fun failed(): List<String> = steps.filter { it.second.lowercase() == "failed" }.map { it.first }

    val done: Int get() = steps.count { it.second.lowercase() == "done" }

    fun progress(): String = if (steps.isEmpty()) "" else "$done of ${steps.size} steps done"

    /** A message asking the model to carry on, or null. At most [MAX_REMINDERS] per request, and only when a plan has open steps. */
    fun reminder(): String? {
        val open = unfinished()
        if (open.isEmpty() || reminders >= maxReminders) return null
        reminders++
        return "Your checklist still has ${open.size} unfinished step(s). The next one is: \"${open.first()}\"." +
            (if (open.size > 1) " Still to do after it: " + open.drop(1).take(5).joinToString("; ") { "\"$it\"" } + "." else "") +
            " Carry on with it now using your tools. If a step is no longer needed or cannot be done, call update_plan to mark it done or failed and say why. " +
            "Only give your final answer when every step is done or failed."
    }

    companion object {
        const val MAX_REMINDERS = 2
        private val OPEN = setOf("todo", "doing", "")
    }
}

/** Notices a model calling the same tool with the same arguments over and over with nothing changing in between. */
class ToolLoop {
    private val recent = ArrayDeque<String>()
    /** Identical calls allowed before the refusal; small local model mode sets 2 (a 1.5B repeated one failed search five times). */
    var limit = 3

    /** True when this exact call is the third identical one since the last change to the project (the second, for a file read). */
    fun repeated(name: String, args: String): Boolean {
        val key = "$name\u0000$args"
        recent.addLast(key)
        while (recent.size > 8) recent.removeFirst()
        return recent.count { it == key } >= if (name == "read_file") 2 else limit
    }

    /** Call after anything that changes files, runs a command or edits a document: results may differ now. */
    fun changed() { recent.clear() }

    companion object {
        const val MESSAGE = "You have made this exact call before with the same result. Do not repeat it. Try a different approach, or stop and tell the user what is blocking you."
    }
}

/**
 * Refuses searches after [MISS_AT] misses in a row: a model guessing names that are not in the project (a run made 19 such
 * misses) must read an entry file or an outline first. Loops of every other kind are caught by [StuckDetector].
 */
class ReadStreak {
    /** Searches in a row that found nothing. */
    var misses = 0; private set

    /**
     * Records a tool result. A search that finds something resets the count, and so does a read or an outline: the refusal tells
     * the model to read a file and then search for a name it saw there (1.5.110; before, every later search stayed refused until a
     * file changed, so a model that read as told was refused again, as in 11 of 14 benchmark runs that hit the refusal).
     */
    fun searched(name: String, result: String) {
        if (name in READS) { if (!StuckDetector.isError(result)) misses = 0; return }
        if (name !in SEARCHES || result.trimStart().startsWith("Refused:")) return
        if (result.trimStart().let { it.startsWith("No matches") || it.startsWith("Nothing searched") }) misses++ else misses = 0
    }

    /** True when another search is refused: [MISS_AT] misses in a row, so the model must read an entry file or an outline first. */
    fun missBlocked(name: String): Boolean = name in SEARCHES && misses >= MISS_AT

    /** A file was written or edited. */
    fun changed() { misses = 0 }

    companion object {
        const val MISS_AT = 3
        private val SEARCHES = setOf("search", "find_code")
        private val READS = setOf("read_file", "file_outline", "repo_map")
        const val MISSED = "Refused: the last $MISS_AT searches found no matches, so the names you are guessing are not in this project. " +
            "Stop searching for names. Open the entry file (index.html or main.js) or file_outline of the file that should hold the behaviour, " +
            "read the part that matters, and search only for a name you saw there."
    }
}

/**
 * Detects a run that is going in circles by looking at what was called and what came back, not at how often a tool is used
 * (rules after OpenHands' StuckDetector, MIT). The run is stopped once with a reason; there are no nudges.
 *  - the same call with the same result [SAME_RESULT] times;
 *  - [ERRORS_IN_ROW] error results in a row;
 *  - an A-B-A-B-A-B alternation of calls over [ALTERNATION] calls, whatever came back;
 *  - [STALE_ROUNDS] rounds in a row with no file change in which every result had been seen before.
 * A changed file clears all of it. Running a command does not: a test that keeps failing the same way is a loop too.
 */
class StuckDetector(private val staleRounds: Int = STALE_ROUNDS) {
    private data class Call(val key: String, val result: Int)
    private val calls = ArrayList<Call>()
    private val seen = HashSet<Int>()
    private var errorsInRow = 0
    private var stale = 0
    private var roundFresh = false
    private var roundCalls = 0

    /** Why the run is stuck, or null. Set by [record], [reread] and [endRound]; stays set until a change. */
    var reason: String? = null; private set

    /** Reads of files the model already had, unchanged, since the last change (1.5.124). */
    var rereads = 0; private set

    /**
     * The model read again a file it already had and that has not changed: a read_file the app answered "unchanged", or a command
     * that only prints such a file (`cat -n f | sed -n 1,40p`, see [Rereads]). In the field run of 2026-11-10 Kimi did this 24 times
     * with no edit; no other rule saw it, because each read asked for a different range and so every call and result looked new.
     */
    fun reread() {
        rereads++
        if (reason == null && rereads >= REREADS) reason = "$REREADS reads of files it already had, with no file changed in between"
    }

    /** One finished tool call. */
    fun record(name: String, args: String, result: String) {
        val r = result.hashCode()
        val key = "$name\u0000$args"
        calls.add(Call(key, r))
        if (calls.size > ALTERNATION * 2) calls.removeAt(0)
        roundCalls++
        if (seen.add(r)) roundFresh = true
        errorsInRow = if (isError(result)) errorsInRow + 1 else 0
        if (reason != null) return
        val label = name.replace('_', ' ')
        reason = when {
            calls.count { it == calls.last() } >= SAME_RESULT -> "the same $label call gave the same result $SAME_RESULT times"
            errorsInRow >= ERRORS_IN_ROW -> "$ERRORS_IN_ROW calls in a row failed (the last: $label)"
            alternating() -> "the model alternated between the same two calls for $ALTERNATION calls"
            else -> null
        }
    }

    /** The end of a round of tool calls. A round whose calls changed a file never gets here with stale results: [changed] cleared them. */
    fun endRound() {
        stale = if (roundCalls > 0 && !roundFresh) stale + 1 else 0
        roundFresh = false; roundCalls = 0
        if (reason == null && stale >= staleRounds) reason = "$staleRounds rounds in a row with no file changed and nothing new found"
    }

    /** A file was changed: what was seen before may differ now. */
    fun changed() { calls.clear(); seen.clear(); errorsInRow = 0; stale = 0; roundFresh = false; roundCalls = 0; rereads = 0; reason = null }

    private fun alternating(): Boolean {
        if (calls.size < ALTERNATION) return false
        val w = calls.takeLast(ALTERNATION)
        // the calls only: a command's output may carry a time or a count that differs on every repeat
        return w[0].key != w[1].key && (2 until w.size).all { w[it].key == w[it - 2].key }
    }

    companion object {
        const val SAME_RESULT = 3
        const val ERRORS_IN_ROW = 3
        const val ALTERNATION = 6
        const val STALE_ROUNDS = 12
        const val REREADS = 6
        private val ERROR_STARTS = listOf("refused", "error", "edit not applied", "failed", "could not", "unknown tool", "skipped")
        fun isError(result: String): Boolean {
            val t = result.trimStart().lowercase()
            return ERROR_STARTS.any { t.startsWith(it) }
        }
    }
}

/** Commands that only print files (1.5.124), for [StuckDetector.reread]. */
object Rereads {
    private val PRINTERS = setOf("cat", "sed", "head", "tail", "nl")
    private val NUMBERS = Regex("""^\d+(,\d+)?p?$""")

    /**
     * The files [cmd] only prints, or an empty list when it does anything else: `cat -n a.js`, `sed -n 1,40p a.js`,
     * `cat -n a.js | sed -n 1,40p | head -20`. Every part must be a printer; only the first may name files.
     */
    fun printed(cmd: String): List<String> {
        if (Regex("""[;&<>`$()]""").containsMatchIn(cmd)) return emptyList()
        val segs = cmd.split('|').map { it.trim() }
        if (segs.any { it.isEmpty() }) return emptyList()
        val files = ArrayList<String>()
        for ((i, seg) in segs.withIndex()) {
            val w = seg.split(Regex("""\s+"""))
            if (w[0] !in PRINTERS) return emptyList()
            val args = w.drop(1).map { it.trim('\'', '"') }.filter { it.isNotEmpty() && !it.startsWith("-") && !NUMBERS.matches(it) }
            if (i == 0) files += args.map { it.removePrefix("./") } else if (args.isNotEmpty()) return emptyList()
        }
        return files
    }
}

/** How many rounds of tool calls one request may use: a fixed number, or autonomous (no round limit, with safety stops). */
object Rounds {
    val CHOICES = listOf(12, 24, 48)
    const val DEFAULT = 12
    /** Stored value meaning "keep going until the job is done". */
    const val AUTONOMOUS = 0
    const val MIN = 6
    const val MAX = 500
    /** Autonomous mode still has an absolute ceiling, a time limit, and the loop detector, so a stuck model cannot run for ever. */
    const val AUTO_CEILING = 400
    const val AUTO_MINUTES = 90
    const val MINUTES_PER_RUN_ROUND_NUMERATOR = 2
    const val MINUTES_PER_RUN_ROUND_DENOMINATOR = 3
    const val MIN_RUN_MINUTES = 10
    const val MAX_RUN_MINUTES = 60
    const val AUTO_REMINDERS = 6
    /** A question is answered from a few read-only rounds; past this it is wrapped up (1.5.79). */
    const val QUESTION_ROUNDS = 12
    /** Rounds for one part of a parts run in autonomous mode: a part is one step of a job, so it gets a budget, not the whole ceiling. */
    const val AUTO_PART_ROUNDS = 100
    /** The most rounds a part gets under a fixed setting; always below [AUTO_PART_ROUNDS], so Autonomous never gives a part less. */
    const val PART_MAX = 80

    fun isAutonomous(n: Int): Boolean = n == AUTONOMOUS
    fun clamp(n: Int): Int = if (n == AUTONOMOUS) AUTONOMOUS else n.coerceIn(MIN, MAX)
    /** The number of rounds that actually applies. */
    fun limit(n: Int): Int = if (isAutonomous(n)) AUTO_CEILING else clamp(n)
    fun timeLimitMinutes(n: Int): Int = if (isAutonomous(n)) AUTO_MINUTES else
        (clamp(n) * MINUTES_PER_RUN_ROUND_NUMERATOR / MINUTES_PER_RUN_ROUND_DENOMINATOR).coerceIn(MIN_RUN_MINUTES, MAX_RUN_MINUTES)
    fun timeLimitReached(elapsedMillis: Long, n: Int): Boolean = elapsedMillis >= timeLimitMinutes(n) * 60_000L
    /** A model's refusal of tool calls is trusted for a week; then tools are tried again. A mark with no date (before 1.5.70) has expired. */
    const val REJECTION_DAYS = 7
    fun rejectionFresh(at: Long, now: Long): Boolean = at > 0 && now - at < REJECTION_DAYS * 86_400_000L

    fun reminders(n: Int): Int = if (isAutonomous(n)) AUTO_REMINDERS else TaskTracker.MAX_REMINDERS

    /**
     * Times the model may be sent back after it said it was done, for every finish check together (proof, unfinished files, the
     * game check, the plan, play tests, the verification nudge, finish hooks, the security scan, the reviewer, the page look,
     * the page check and the project's tests). Aider stops after 3 reflections; nobody in the field runs ten. Before 1.5.77 each
     * check had its own counter and a request could be sent back more than twenty times (a run was sent the same page error ten
     * times, in two requests). The same in every mode, Autonomous included.
     */
    const val FINISH_BUDGET = 3
    fun finishBudget(n: Int): Int = FINISH_BUDGET

    // ---- limits for runs that are not the main tool-calling loop; each follows the same setting ----
    // At the default setting these give the numbers the app used before (6 look rounds, 20 rounds in all, 3 fixes, parts 30).

    /** Text format: rounds the model may spend only looking around (read, list, search) before it must write. */
    fun lookRounds(n: Int): Int = if (isAutonomous(n)) 60 else (clamp(n) / 2).coerceIn(AgentActions.MAX_ROUNDS, 50)
    /** Text format: rounds of actions in all, counting ones that changed things. */
    fun totalRounds(n: Int): Int = if (isAutonomous(n)) 200 else (lookRounds(n) * 10 / 3).coerceIn(AgentActions.MAX_TOTAL_ROUNDS, 160)
    /** Text format: times the model is asked to fix problems the checks found. */
    fun fixAttempts(n: Int): Int = if (isAutonomous(n)) 12 else (clamp(n) / 4).coerceIn(3, 10)
    /** Rounds for one part of a parts run. */
    fun partRounds(n: Int): Int = if (isAutonomous(n)) AUTO_PART_ROUNDS else (clamp(n) * 5 / 2).coerceIn(30, PART_MAX)
    /** Rounds for a read-only helper (explorer, reviewer) whose cap at the default setting is [base]. */
    fun helperRounds(base: Int, n: Int): Int = if (isAutonomous(n)) base * 3 else (base * clamp(n) / DEFAULT).coerceIn(base, base * 2)

    /** Says which limit stopped a run and where to change it, for the stop message. */
    fun stopHint(n: Int): String = if (isAutonomous(n)) "Autonomous mode's safety limits ($AUTO_CEILING rounds, $AUTO_MINUTES minutes, and the loop detector)" else "the ${timeLimitMinutes(n)}-minute, ${clamp(n)}-round limit"
    /** The limit a run is under: its own [cap] when it has one (a part, the explorer, the reviewer), else the setting. */
    fun limitName(n: Int, cap: Int): String = if (cap > 0) "this run's $cap-round limit, which follows Tool rounds per request" else stopHint(n)
    /**
     * True when [reason] is a round or time limit, which the person can raise in Settings. Other reasons (the loop detector, the
     * checks having passed) are not limits, and pointing at the setting for them sent people to the wrong place (1.5.85).
     */
    fun isLimit(reason: String): Boolean = reason.isEmpty() || reason.endsWith("rounds used") || reason.endsWith("minutes used")
    /** Shown when a run is asked to wrap up. */
    fun wrapNote(reason: String, n: Int, cap: Int): String =
        if (!isLimit(reason)) "Asked the model to wrap up: $reason."
        else "Asked the model to wrap up: $reason. Limit: ${limitName(n, cap)}. To give it more room, change $SETTING" +
            (if (isAutonomous(n)) "." else " (Autonomous removes the round limit).")
    /** Shown when a run is stopped because the model kept calling tools after being asked to wrap up ([reason]: why it was asked). */
    fun stoppedNote(round: Int, n: Int, cap: Int, reason: String = ""): String =
        if (!isLimit(reason)) "Stopped at round $round: the model kept calling tools after being asked to wrap up ($reason)."
        else "Stopped at round $round: the model kept calling tools after being asked to wrap up. Limit: ${limitName(n, cap)}. Change it in $SETTING."
    /** The round at which a run under a [limit]-round ceiling is told how many rounds are left: about four fifths of the way, at least 3 before the end. */
    fun lowAt(limit: Int): Int = limit - maxOf(3, limit / 5)
    /**
     * Said once at [lowAt]. gpt-oss-120b in the hard suite (1.5.89) found the wrong levels, then spent its last 18 of 40 rounds on awk scripts
     * that failed and fixed one level of six: it was never told the rounds were running out.
     */
    fun lowNote(left: Int): String = "$left tool rounds are left for this request. Use them to finish: make the changes you have already found now " +
        "(edit_file can change several places, one call each), run one check, then reply. Do not start a new approach that may not work."
    const val SETTING = "Settings → How long it may work → Tool rounds per request"
    /** A whole number from [MIN] to [MAX], or null. */
    fun parseCustom(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in MIN..MAX }

    fun label(n: Int): String = when {
        isAutonomous(n) -> "Autonomous (no round limit)"
        n == DEFAULT -> "$n rounds (default)"
        else -> "$n rounds"
    }
}

/** The person's request in their own words, repeated near the end of a long run. */
object RequestReminder {
    fun text(request: String): String =
        "Reminder of what the person asked, in their words: \"" + request.trim().take(800) + "\". Keep to it: do what it asks, use the resources it names, and nothing it did not ask for."

    /** 1.5.170: after the person steered, the reminder is the job as it stands, newest message first (Steer.job). */
    fun text(request: String, steers: List<String>): String = if (steers.isEmpty()) text(request) else
        "Reminder of the job as the person has set it, newest message first:\n" + Steer.job(request, steers) +
            "\nDo what the newest messages ask. Where they change or drop part of the first request, follow them, not the first request."
}
