package dev.pocketide.app.core

/**
 * A second try (1.5.112): when a request's first attempt ends without finishing the job, the app undoes that attempt's changes
 * and runs the request once more with a fresh conversation. Codex offers several attempts at a task and keeps the one that works;
 * the benchmarks showed strong cheap models failing about one run in ten at random, and most of those runs pass on another try.
 *
 * Only the app's own signs of a failed attempt count, never the benchmark's hidden checks: the run was cut off (round limit,
 * going in circles), the last check it ran after its last change failed, or its answer says the job is not done.
 */
object SecondTry {
    /** "Not fixed yet", "I haven't changed anything", "I couldn't find where…": the answer says the job is not done. */
    private val GAVE_UP = Regex(
        """(?i)\b(?:(?:is|was|are|were) (?:still )?not (?:yet )?(?:fixed|solved|done|working)|not fixed|(?:have not|haven'?t) (?:yet )?(?:fixed|changed anything|made (?:any|the) change|been able|found (?:the|where|what))|""" +
            """(?:could not|couldn'?t|was unable to|am unable to|unable to) (?:find|fix|work out|tell|reproduce|locate|confirm)|(?:cannot|can'?t) (?:yet )?(?:say|tell) what)""")
    private const val READ = 1500

    /**
     * Why the attempt looks failed, or null when it looks done (or a retry would not help: the person stopped it, a provider
     * error, a question or report that changes nothing).
     */
    fun reason(work: Boolean, personStopped: Boolean, error: String?, cutShort: String, failedCheck: String, answer: String): String? {
        if (!work || personStopped || error != null) return null
        if (cutShort.isNotBlank()) return "it was stopped before it finished ($cutShort)"
        if (failedCheck.isNotBlank()) return "the last check it ran after its last change failed ($failedCheck)"
        if (GAVE_UP.containsMatchIn(answer.take(READ))) return "its answer says the job is not done"
        return null
    }

    /** What a second try does with the first attempt's files (1.5.167). */
    enum class Mode { NONE, FRESH, KEEP }

    /** A first attempt that changed files for this long (seconds) is carried on, never undone. */
    const val KEEP_AFTER_S = 300L

    /**
     * Whether and how to try again (1.5.167). A person who steered the run is there and directing it: no automatic retry, so a
     * fresh start cannot drop what they said (an Empty-project run had its quest work undone while the person was sending
     * corrections). An attempt that worked on files for minutes keeps its files and carries on from them. Only a short attempt is
     * undone and started again, as before.
     */
    fun mode(steered: Boolean, filesChanged: Int, seconds: Long, weak: Boolean = false): Mode = when {
        steered -> Mode.NONE
        filesChanged > 0 && seconds >= KEEP_AFTER_S -> Mode.KEEP
        // 1.5.170: a model that needed the small tool set (SmallTools) is unlikely to do better from nothing; it carries on from its files
        filesChanged > 0 && weak -> Mode.KEEP
        else -> Mode.FRESH
    }

    /** Added to the request for the second attempt: what happened, and the first attempt's last words, marked as possibly wrong. */
    fun note(reason: String, firstAnswer: String, kept: List<String>? = null): String {
        val last = firstAnswer.trim().replace(Regex("\\s+"), " ").take(1200)
        val files = if (kept == null) "Its changes were undone, so every file is as it was before it. "
            else "Its changes were kept: " + kept.take(12).joinToString(", ") + (if (kept.size > 12) " and ${kept.size - 12} more" else "") +
                ". Read them, keep what is right, and finish the job from there; do not start over. "
        return "\n\n(A first attempt at this request did not succeed: $reason. $files" +
            (if (last.isEmpty()) "" else "Its last words, which may point to the cause or may be wrong: \"$last\". ") +
            "Look at the code yourself, make the change, and check it.)"
    }

    /** Shown in the chat when the second attempt starts. */
    fun startNote(reason: String, kept: Boolean = false): String =
        if (kept) "The first attempt did not finish the job: $reason. Its changes are kept; carrying on from them."
        else "The first attempt did not finish the job: $reason. Its changes were undone (the button above brings them back); trying once more from the start."
}
