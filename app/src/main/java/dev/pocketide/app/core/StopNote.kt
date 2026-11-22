package dev.pocketide.app.core

/**
 * What a stopped run did and found. It is saved with the run in the chat, so the next message ("Continue") carries on from here.
 * Before 1.5.73 a stopped run was not saved at all, and "Continue" started the job over from its first step.
 */
object StopNote {
    const val HEAD = "Stopped before the request was finished. Work so far, to carry on from:"

    /**
     * [reason]: why the run ended (the round limit, a loop, the finish budget, Stop); [lastPassed]: the last check that passed and
     * when, so "Continue" starts from evidence instead of from the plan's first open step (1.5.77).
     */
    fun text(said: List<String>, steps: List<String>, changed: List<String>, plan: List<Pair<String, String>>, reason: String = "", lastPassed: String = ""): String {
        val sb = StringBuilder(HEAD).append('\n')
        if (reason.isNotEmpty()) sb.append("Why it stopped: ").append(reason.trim().trimEnd('.')).append(".\n")
        sb.append(if (lastPassed.isEmpty()) "No check passed in this run.\n" else "Last check that passed: $lastPassed.\n")
        if (plan.isNotEmpty()) {
            sb.append("Plan:\n")
            plan.forEachIndexed { i, (t, st) -> sb.append("  ").append(i + 1).append(". [").append(st).append("] ").append(t.take(160)).append('\n') }
        }
        sb.append(if (changed.isEmpty()) "No files were changed.\n" else "Files changed: " + changed.take(30).joinToString(", ") + ".\n")
        if (steps.isNotEmpty()) sb.append("Last steps: ").append(steps.takeLast(25).joinToString("; ") { WorkLog.oneLine(it, 100) }).append(".\n")
        val found = said.map { it.trim() }.filter { it.isNotEmpty() }.takeLast(6)
        if (found.isNotEmpty()) {
            sb.append("What the assistant had found or said:\n")
            found.forEach { sb.append("- ").append(it.replace('\n', ' ').take(400)).append('\n') }
        }
        return sb.toString().trimEnd()
    }

    /**
     * What the model is told when a limit stops the run (1.5.125). With files changed it gives a checkpoint (Done / Left), as
     * before. With nothing changed, the usual reason is that it needs a choice the person must make: in the field run of
     * 2026-11-10 Kimi tried 18 dead public proxies, concluded it needed the owner's choice of approach, and ended "stopped"
     * without ever asking. So then it must end with that question, naming the options, or say plainly what blocks it.
     */
    fun wrapAsk(reason: String, changedNothing: Boolean): String =
        "You have reached a limit for this request ($reason). Do not call more tools. " +
            if (!changedNothing) "Reply now as a checkpoint: a line starting 'Done:' with the files you changed and what you checked, then a line starting 'Left:' with what still needs doing. Keep it short."
            else "No file was changed in this run. Reply now, briefly: what you found, then what stops you. If the person must choose something " +
                "(an approach, a service, a file, a setting), end with one question that names the options and what each would mean, so they can answer in one line. " +
                "Do not promise to do it later."
}
