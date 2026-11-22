package dev.pocketide.app.core

/** A message the person typed while the assistant works: when it is read and what happens to the calls left in the round (1.5.80). */
object Steer {
    /** Every call from [from] on gets a "not run" result, so each call still has a paired result in both API formats. */
    fun skipRest(calls: List<ToolCall>, from: Int): List<Pair<ToolCall, String>> =
        calls.drop(from.coerceAtLeast(0)).map { it to Tools.SKIPPED_FOR_STEER }

    /** "read by the assistant after 12s": what the sent message's bubble says once it has been read. */
    fun readLabel(waitedMs: Long): String = "read by the assistant" + (if (waitedMs >= 1000) " after ${waitedMs / 1000}s" else "")

    /**
     * The job as it stands (1.5.170): the person's messages since the request, newest first, then the request. Every ask the app
     * sends later quotes this, not the request alone: before, a reminder every ten rounds quoted the first request and said "nothing
     * it did not ask for", which pulled the model back to a job the person had just changed.
     */
    fun job(request: String, steers: List<String>): String =
        if (steers.isEmpty()) request
        else steers.reversed().mapIndexed { i, s -> (if (i == 0) "Newest message: \"" else "Earlier message: \"") + s.trim().take(400) + "\"" }
            .joinToString("\n") + "\nThe first request (the newest messages above override it where they differ): \"" + request.trim().take(500) + "\""

    private val AGREE = Regex("""^\s*(yes|yeah|yep|sure|ok|okay|please|go ahead|do it|do that|make it|make the change|apply|go for it|sounds good|that works)\b""", RegexOption.IGNORE_CASE)
    private val CHANGE = Regex("""\b(fix|change|update|edit|add|remove|delete|rename|replace|implement|make|write|create|build|apply|set|move|sort|trim|do it|go ahead)\b""", RegexOption.IGNORE_CASE)

    /** A message during a question or report run that asks for the change after all ("yes, do it", "go ahead and fix it"). */
    fun unlocks(text: String): Boolean = !Habits.isQuestion(text) && (AGREE.containsMatchIn(text) || CHANGE.containsMatchIn(text))

    /**
     * How a message typed during a run is given to the model (1.5.125). It is part of this job: in the field run of 2026-11-10 the
     * owner's two messages ("recipes need instructions", "test with this URL") were answered "I'll note it for the next
     * iteration" and never done. Before, the wording only said to change the plan "if it changes the job".
     */
    fun message(text: String): String =
        "The person sent a message while you were working: \"" + text + "\"\n" +
            "It belongs to this job and it comes first: their newest words decide what the job is now. If it narrows or redirects the work " +
            "(\"focus on X\", \"only X\", \"stop doing Y\"), change your plan to match: drop or set aside the steps it rules out, and do not " +
            "argue for the old plan. If it asks for something more (a feature, a fix, a test, a link to use), add it to your plan and do it in " +
            "this run, not later. If it reports problems while also telling you what to focus on, do the focus first and list the other " +
            "problems in your answer. If it is a question, answer it briefly. Then continue."
}
