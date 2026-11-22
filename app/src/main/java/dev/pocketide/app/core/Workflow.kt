package dev.pocketide.app.core

/**
 * Multi-model workflows for big builds, after GitHub's HydraFusion:
 *  - single: the chosen model does everything (today's behaviour);
 *  - cascade: a fast draft model builds, the app's checks and a strong model's read-only review are the quality gate,
 *    and only if they find gaps does the strong model finish the job;
 *  - critique: a draft model builds, a different model reviews it with no ability to change anything, and the drafter revises once.
 */
object Workflow {
    const val SINGLE = "single"
    const val CASCADE = "cascade"
    const val CRITIQUE = "critique"
    val ALL = listOf(SINGLE, CASCADE, CRITIQUE)

    fun parse(s: String): String = if (s in ALL) s else SINGLE

    fun title(w: String): String = when (w) { CASCADE -> "Cascade"; CRITIQUE -> "Critique"; else -> "Single model" }

    fun explain(w: String): String = when (w) {
        CASCADE -> "A fast model builds. The app's checks and a strong model's read-only review then decide whether the work is good enough; only if they find gaps does the strong model finish the job. Cheapest when the draft is already good."
        CRITIQUE -> "A fast model builds. A different model reviews it (read-only, it cannot change files) and the builder revises once. Catches mistakes a single model misses."
        else -> "The model you picked does everything."
    }

    /** One model choice. */
    data class Slot(val provider: String, val model: String) {
        fun encode() = "$provider\t$model"
        fun same(o: Slot?) = o != null && o.provider == provider && o.model == model
    }

    fun decode(s: String): Slot? {
        val p = s.split('\t')
        return if (p.size == 2 && p[0].isNotBlank()) Slot(p[0], p[1]) else null
    }

    private val CHEAP_FIRST = listOf("cerebras", "groq", "gemini", "openrouter", "mistral", "openai")

    /** The provider to draft with when none was picked: the cheapest-looking one that has a key and is not the strong one. */
    fun defaultDraftProvider(usable: List<String>, strong: String): String? =
        CHEAP_FIRST.firstOrNull { it in usable && it != strong } ?: usable.firstOrNull { it != strong }

    /** True when the workflow can actually use two different models. */
    fun distinct(draft: Slot?, strong: Slot?): Boolean = draft != null && strong != null && !draft.same(strong)

    fun describe(w: String, draft: Slot?, strong: Slot?): String =
        if (w == SINGLE) title(w) else title(w) + ": draft " + (draft?.let { "${it.provider}/${it.model.ifBlank { "default" }}" } ?: "not set") + ", strong " + (strong?.let { "${it.provider}/${it.model.ifBlank { "default" }}" } ?: "not set")

    // ---- prompts ----

    /** The strong model finishes a draft that the gate rejected. */
    fun escalatePrompt(task: String, reqs: List<String>, scope: String, review: String, found: List<String>): String =
        task + (if (scope.isBlank()) "" else "\n\nWork only inside the folder \"${scope.trim('/')}\".") +
            "\n\nA faster model built a first draft of this and the review found it incomplete." +
            (if (reqs.isEmpty()) "" else "\nApproved requirements:\n" + Requirements.list(reqs)) +
            (if (found.isEmpty()) "" else "\n\nProblems the app found in the files:\n" + found.joinToString("\n") { "- $it" }) +
            "\n\nReview findings:\n" + review.trim().take(3000) +
            "\n\nRead the files first and keep what already works. Fix every gap and problem above, then finish all the requirements. Keep each file under about ${OutputLimit.FILE_LINES} lines."

    /** The critic: reads only. */
    fun critiquePrompt(task: String, reqs: List<String>, scope: String, found: List<String>): String =
        "You are a reviewer. Another model just built this. Read the project files that matter and list real defects. Do not change any file.\n\n" +
            "Request: $task\n\n" + (if (reqs.isEmpty()) "" else "Approved requirements:\n" + Requirements.list(reqs) + "\n\n") +
            (if (scope.isBlank()) "" else "Everything belongs inside \"${scope.trim('/')}\".\n\n") +
            (if (found.isEmpty()) "" else "The app already found:\n" + found.joinToString("\n") { "- $it" } + "\n\n") +
            "Reply with a numbered list of at most 10 concrete problems, most important first, each naming the file and what to change (missing features, bugs, broken links between files, anything swapped for something else). " +
            "If you find nothing wrong, reply exactly: No defects."

    fun noDefects(reply: String): Boolean {
        val t = reply.trim().trim('.', '*', ' ').lowercase()
        return t.isEmpty() || t == "no defects" || (t.startsWith("no defects") && t.length < 60)
    }

    /** The builder revises once using the critic's list. */
    fun revisePrompt(task: String, critique: String, scope: String): String =
        "A reviewer from another model checked your work on this request:\n$task\n\n" + (if (scope.isBlank()) "" else "Work only inside the folder \"${scope.trim('/')}\".\n\n") +
            "Their findings:\n${critique.trim().take(3000)}\n\nRevise once: read the files, fix the real problems on the list, and change nothing else. If a finding is wrong, skip it. Keep each file under about ${OutputLimit.FILE_LINES} lines."

    /** True when the gate should hand the work to the strong model. */
    fun shouldEscalate(review: String, found: List<String>): Boolean = found.isNotEmpty() || Requirements.hasGaps(review)
}
