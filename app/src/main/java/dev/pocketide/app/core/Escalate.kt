package dev.pocketide.app.core

/**
 * Escalation on a stall (1.5.125, phase 4 of ROADMAP-HANDOFF.md): when a request ends unfinished (the run went in circles, hit its
 * round or time limit, its last check failed, or its answer says the job is not done: [SecondTry.reason]), the job can carry on
 * with the stronger model the person picked in Settings → Workflow, keeping the files as they are. Before, the stop note only said
 * "send it again with a stronger model", and nothing offered or did it.
 *
 * Unlike a second try (which undoes the attempt and starts over with the same model), the strong model continues from where the
 * first one stopped, with its notes. It is a button by default; Settings can make it automatic, once per request.
 */
object Escalate {
    /**
     * The model to carry on with, or null. Only a strong model the person chose ([picked]: Settings → Workflow's strong slot) counts,
     * never the fallback to the main model, and only when it differs from the model that just worked and has a key.
     */
    fun target(working: Workflow.Slot?, picked: Workflow.Slot?, hasKey: (String) -> Boolean): Workflow.Slot? =
        picked?.takeIf { it.provider.isNotBlank() && !it.same(working) && hasKey(it.provider) }

    /** What the strong model is told: the request, why the first model stopped, and its notes (the StopNote text when there is one). */
    fun prompt(request: String, why: String, firstModel: String, notes: String): String = request.trim() + note(why, firstModel, notes)

    /** The part after the request; the benchmark adds it to the task's request as the phone adds it to the person's. */
    fun note(why: String, firstModel: String, notes: String): String =
            "\n\nAnother model ($firstModel) worked on this request and stopped before it was finished: ${why.trim().trimEnd('.')}." +
            (if (notes.isBlank()) "" else "\nIts notes:\n" + Trim.middle(notes.trim(), 3000)) +
            "\n\nThe files are as it left them. Read the files that matter first and keep what already works. Do not repeat what it " +
            "tried that failed. Finish the request, check it, and say what you changed and how you checked it."

    /**
     * Once per request (1.5.126). [start] when the strong model takes over; [ended] when a run ends, true for the carried-on run
     * itself (which is never escalated again); [newRequest] when the person sends a new request. Before 1.5.126 the flag was cleared
     * only when the carried-on run ended through the tool-calling path, so a strong model without tool calls left it set and no
     * later stalled request was offered "Carry on" until the app restarted (REVIEW-1.5.125.md, finding 13).
     */
    class Once {
        var carrying = false
            private set
        fun start() { carrying = true }
        fun ended(): Boolean { val was = carrying; carrying = false; return was }
        fun newRequest() { carrying = false }
    }

    fun button(model: String) = "Carry on with $model"
    fun startNote(model: String, why: String) = "Carrying on with $model: the first attempt stopped unfinished (${why.trim().trimEnd('.')})."
}
