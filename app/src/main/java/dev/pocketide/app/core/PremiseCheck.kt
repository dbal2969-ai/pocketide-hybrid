package dev.pocketide.app.core

/**
 * Premise check (1.5.164, setting "premise.check", off until measured). From the research handoff (§2): FixedBench (Gloaguen et al.,
 * 2026) found coding agents make unwanted changes in 35 to 65% of already-fixed tasks, and the one framing that helped made not acting a
 * *successful* outcome; R2E-Gym found checks grounded in execution, not in a second reading of the request, are the ones that work. On
 * the sealed suite Kimi built the metric switch, the favourites star and the rest timer the person said had "disappeared" (0/10).
 *
 * Three parts: (1) before the first step, the app itself searches the project for what the request says has gone, and tells the model
 * what it found as a fact; (2) a report_finding tool that ends the run as a correct answer; (3) a note that says reporting is success.
 */
object PremiseCheck {
    const val MAX_FILES = 500
    const val MAX_BYTES = 200_000

    /** Words that say something used to be there. */
    private val GONE = Regex("""\b(disappeared|vanished|has gone|is gone|are gone|went missing|is missing|are missing|no longer|not there any ?more|used to|stopped (?:working|showing|appearing)|bring (?:it |them )?back|put (?:it |them )?back|restore)\b""", RegexOption.IGNORE_CASE)
    private val QUOTED = Regex("""["'‘“]([^"'’”]{2,40})["'’”]""")
    private val THING = Regex("""\b(?:the|my|a|our)\s+((?:[A-Za-z][\w-]*\s+){0,3}?)(button|switch|toggle|star|tab|menu|screen|page|setting|option|link|icon|timer|panel|field|column|list|feature|mode|filter|shortcut)\b""", RegexOption.IGNORE_CASE)
    private val KINDS = setOf("button", "switch", "toggle", "tab", "menu", "screen", "page", "setting", "option", "link", "icon", "panel", "field", "column", "list", "feature", "mode", "filter", "shortcut")
    private val STOP = setOf("the", "a", "my", "our", "for", "of", "in", "on", "that", "which", "i", "had", "was", "it", "this", "top", "bar", "new", "old", "little")

    /** The names the request says have gone, as search terms (a quoted label, or the words before "button", "switch" ...). */
    fun terms(request: String): List<String> {
        if (!GONE.containsMatchIn(request)) return emptyList()
        val out = LinkedHashSet<String>()
        for (m in QUOTED.findAll(request)) out.add(m.groupValues[1].trim())
        for (m in THING.findAll(request)) {
            val words = (m.groupValues[1] + " " + m.groupValues[2]).trim().split(Regex("""\s+""")).filter { it.lowercase() !in STOP }
            val head = words.dropLast(1)
            // the whole name ("rest timer"), its naming words ("rest"), and the thing itself unless it is a bare "button" or "switch"
            if (words.size > 1) out.add(words.joinToString(" "))
            for (w in head) if (w.length >= 4) out.add(w)
            words.lastOrNull()?.takeIf { it.lowercase() !in KINDS }?.let { out.add(it) }
        }
        return out.filter { it.length >= 3 }.take(6)
    }

    /**
     * Searches [files] for each term (case ignored) and returns a fact block when at least one term is found nowhere; null when the
     * request claims nothing has gone, or everything it names is in the project.
     */
    fun probe(request: String, files: List<String>, read: (String) -> String?): String? {
        val ts = terms(request)
        if (ts.isEmpty()) return null
        // Copilot N1 (PR #27): at most MAX_FILES files and MAX_BYTES of each, so a big project or one huge file cannot stall the start
        val texts = files.filter { !RunTargets.ignored(it) }.take(MAX_FILES).mapNotNull { p -> read(p)?.let { p to it.take(MAX_BYTES).lowercase() } }
        val counts = ts.associateWith { t -> texts.count { (_, s) -> t.lowercase() in s } }
        if (counts.values.none { it == 0 }) return null
        return "\n\n[The app searched the project before you started (${texts.size} files). " +
            counts.entries.joinToString("; ") { (t, n) -> "\"$t\": " + (if (n == 0) "found in no file" else "in $n file" + (if (n == 1) "" else "s")) } +
            ". What the request says has gone may never have been in this project.]"
    }

    const val NOTE = "\n\nIf the request says something has disappeared, broken or used to exist, check that first: search for it. If the " +
        "project has no trace of it, do not build it to make the request true. Call report_finding with what you searched for and found, " +
        "and do any other part of the request that is real. Reporting that something is not there is a correct and complete answer, not " +
        "a failure. If part of it exists, fix that part and report the rest."

    const val TOOL_DESC = "End the work with a finding instead of a change, when what the request describes is not in the project, already " +
        "works, or needs the person to decide. This is a correct outcome. Give the evidence: what you searched for or ran, and what it showed."

    /** The final answer report_finding gives the person. */
    fun answer(kind: String, evidence: String, question: String?): String {
        val head = when (kind.trim().lowercase()) {
            "already_works" -> "This already works as asked."
            "needs_decision" -> "This needs your decision before I change anything."
            "blocked" -> "I could not do this."
            else -> "I could not find this in the project."
        }
        return head + "\n\n" + evidence.trim() + (question?.trim()?.takeIf { it.isNotEmpty() }?.let { "\n\n$it" } ?: "")
    }
}
