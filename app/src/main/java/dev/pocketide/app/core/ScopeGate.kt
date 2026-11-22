package dev.pocketide.app.core

/**
 * 1.5.156 (Copilot's parity handoff, R4; off by default until measured: AppSettings.scopeCheck). The transcripts show runs that fix
 * the cause and then change more than was asked: rp-bury added a "Big bones" item the project never had, to make the request true;
 * harbour crew-3 also edited the top bar because the explore helper said "also update ui/hud.js". Prompt reminders did not stop
 * either, so this is a check in the loop, with a prompt note beside it:
 *  - [PREMISE_NOTE]: something the request names that the project does not have is reported, not created;
 *  - [unasked] and [ask]: before finishing, files the request does not name are questioned once: data files always (1.5.158), and
 *    in problem reports also code files the model has not named as the cause; tests exempt;
 *  - [EXPLORER_NOTE]: the explore helper reports where things are, without advice on what else to change.
 * The texts are fixed: they are not to be tuned on results.
 */
object ScopeGate {
    const val PREMISE_NOTE = "\n\nIf the request says the project has something you cannot find in it (an item, a screen, a setting, a name " +
        "that search does not find), do not add it to make the request true. Say in your reply that the project has no such thing, and do " +
        "the rest of the request."

    const val EXPLORER_NOTE = " Report where things are and what they do. Do not suggest changes, or other files to update: the assistant " +
        "you are helping decides what to change."

    private val TEST = Regex("""(^|/)(tests?|spec|__tests__)/|[._-](test|spec)\.[a-z]+$|^test_[^/]*$|/test_[^/]*$""", RegexOption.IGNORE_CASE)

    /** A test file: changing or adding one is never questioned. */
    fun isTest(path: String): Boolean = TEST.containsMatchIn(path)

    /** The files the model has named as the cause in [said]: on a line with a cause word ("the bug is", "because" ...). */
    fun causeFiles(said: String, exists: (String) -> Boolean): Set<String> = said.lines()
        .filter { CauseGate.CAUSE_WORDS.containsMatchIn(it) }
        .flatMap { line -> CauseGate.FILE.findAll(line).map { it.value.substringBefore(':').trimStart('.', '/') }.filter(exists).toList() }
        .toSet()

    /**
     * The changed files to question: not tests, not named by [request], not named by the model as the cause. For a problem report every
     * such file; for other requests only data files (a feature legitimately touches files the request does not name).
     */
    fun unasked(changed: Collection<String>, request: String, said: String, problem: Boolean, exists: (String) -> Boolean): List<String> {
        val causes = causeFiles(said, exists)
        fun isCause(p: String) = causes.any { c -> c == p || p.endsWith("/$c") || c.substringAfterLast('/') == p.substringAfterLast('/') }
        return changed.filter { p ->
            // 1.5.158: a data file is questioned even when the model calls it the cause; in harbour-pearls the runs that invented pearls
            // wrote "the cause: data/goods.json had no pearls entry", which exempted the file they changed
            !isTest(p) && !CauseGate.named(p, request) && (CauseGate.isData(p) || (problem && !isCause(p)))
        }
    }

    /** Sent once before finishing when [files] were changed. */
    /**
     * 1.5.160 (Copilot ANALYSIS-1.5.159): appended to the later nudges (few rounds left, the wrap-up, the unfinished plan) once the
     * files were questioned. In the harbour runs a model put the invented entries back as asked, then read "make the changes you have
     * already found now" as leave to add them again.
     */
    fun settled(files: List<String>): String =
        "You were already asked about ${files.joinToString(", ")} and answered. Leave them as they are now; this note is not a reason to " +
            "change them again."

    fun ask(files: List<String>): String =
        "You changed ${files.joinToString(", ")}, which the request does not name and you have not named as the cause. For each one: if the " +
            "request needs it, say why in one line and keep it. If it does not (a tidy-up, a guess, an extra fix nobody asked for, or something " +
            "the project did not have, added to make the request true), put it back exactly as it was. Then finish."
}
