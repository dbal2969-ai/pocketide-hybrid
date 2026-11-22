package dev.pocketide.app.core

/**
 * The working habits that make an assistant good to talk to, written as rules the app can apply to any model: answer a question
 * as a question, say what you do not know and offer to find out, look for existing work before rewriting it, be honest about
 * scope, reproduce a problem before fixing it, and lead with the answer. Pure logic, so the rules can be tested.
 */
object Habits {
    private val I = RegexOption.IGNORE_CASE

    private val ASK_START = Regex("""^\s*(what|what's|whats|why|how|when|where|which|who|whose|is|are|was|were|does|do|did|can|could|should|would|will|has|have|any idea|explain|tell me)\b""", I)
    /** "can you add …", "would you fix …": a request worded as a question (explaining is still a question). */
    /**
     * "When a user saves only the colour, the font disappears": a condition that starts a bug report, not a question (1.5.93; every
     * model failed the held-out settings-merge task because the app refused edits to this request). "When does it run" asks.
     */
    private val CONDITION_START = Regex("""^\s*(when|whenever|where|wherever|if|once)\s+(?!(do|does|did|is|are|was|were|will|would|can|could|should|shall|has|have|had|am)\b)\w""", I)
    // 1.5.170: "Can we sort the leaderboard?", "Could we make the chat work on projects?" ask for a change the way "can you" does
    // (the owner writes this way, and every edit was refused for the whole run); "can we explain…" stays a question.
    private val POLITE_ORDER =Regex("""(?<!\b(?:how|why|what|where|when|which)\s{1,3})\b(can|could|would|will|shall)\s+(you|u|we)\s+(also\s+|please\s+|just\s+)*(?!explain|tell|describe|clarify|say|summari[sz]e|remind|talk|discuss|know|see|analy[sz]e|think|figure|understand|compare|consider|review|plan)\w+""", I)
    private val IMPERATIVE = Regex("""^\s*(please\s+)?(create|build|make|write|implement|add|fix|change|update|edit|remove|delete|rename|refactor|rewrite|install|run|push|apply|restore|move|copy|replace|convert|port|set\s+up|generate|deploy|continue|keep|go\s+ahead|start|stop|try|use|put|turn|wire|hook|finish|do\s+it)\b""", I)

    /**
     * A finding or an observation worded as a statement: "Found a critical indexing bug! …", "I noticed the map is cut off",
     * "Looks like it loads twice". It reports; it does not order anything (1.5.79: one such message ran 27 minutes as a task).
     */
    private val OBSERVATION = Regex("""^\s*(i\s+(noticed|found|saw|spotted|am\s+(curious|wondering)|was\s+wondering)|(found|noticed|spotted)\b|looks\s+like|it\s+looks\s+like|seems|it\s+seems|apparently|fyi|just\s+(found|noticed)|curious|wondering)\b""", I)
    /** An order inside a sentence that starts as an observation: "I noticed it is blank, make it red", "found a bug and fix it". */
    private val ORDER_INSIDE = Regex("""(?:[,;:]|\band\b|\bthen\b|\bso\b|\bplease\b)\s*(?:please\s+|now\s+|you\s+)?(?:create|build|make|write|implement|add|fix|change|update|edit|remove|delete|rename|refactor|rewrite|install|run|push|apply|restore|move|copy|replace|convert|port|generate|deploy|continue|keep|start|try|use|let'?s|we\s+(?:need|should|must|have)\s+to|need\s+to|should|must)\b""", I)

    /**
     * True when the message asks something rather than orders a change: "What is that", "What is it doing?",
     * "Command to delete the backup?". A request worded as a question ("Can you add dark mode?") or a message with an order
     * in it ("Why is it blank? Fix it.") is not one.
     */
    fun isQuestion(text: String): Boolean {
        val t = text.trim().replace(Regex("""https?://\S+"""), "link")
        if (t.isEmpty()) return false
        val sentences = t.split(Regex("""(?<=[.!?])\s+|\n+""")).map { it.trim() }.filter { it.isNotEmpty() }
        if (sentences.any { IMPERATIVE.containsMatchIn(it) }) return false
        if (POLITE_ORDER.containsMatchIn(t)) return false
        if (t.endsWith("?")) return true
        if (ASK_START.containsMatchIn(t) && !('?' !in t && CONDITION_START.containsMatchIn(t))) return true
        return OBSERVATION.containsMatchIn(t) && !ORDER_INSIDE.containsMatchIn(t) && !Regex("""\b(please|fix it|you\s+(need|should|must))\b""", I).containsMatchIn(t)
    }

    /**
     * A command that only reads (ls, cat, grep, git status …), so it may run while answering a question. Sandbox.risk alone
     * calls anything it does not recognise SAFE ("node server.js"), so the command must also be on CommandRules' reader list.
     */
    fun readOnlyCommand(cmd: String): Boolean = Sandbox.risk(cmd) == Sandbox.Risk.SAFE && CommandRules.readsOnly(cmd)

    /** Told to the model when it tries to change something while answering a question. */
    const val QUESTION_REFUSAL = "Not done: the person asked a question, so answer it; do not change files or run commands that change anything. " +
        "You may read files and run read-only commands to find the answer. If a change would help, describe it in your answer and ask whether to make it."

    /** The note that tells the model this message is a question. */
    const val QUESTION_NOTE = "\n\nThis message is a question. Answer it directly, in plain words, first. Read files or run read-only commands if you need to " +
        "check something, but change nothing. If a change would help, say what you would change and ask whether to make it."

    // ---- the request's kind, for the notes below ----

    private val LIKE = Regex("""\b(clone|copy|replica|recreate|remake|just like|like\s+(?:osrs|runescape|\w+scape|minecraft|pokemon|zelda|mario|tetris|doom|wordpress|discord|slack|notion|spotify|twitter|reddit|instagram)|identical|same as|1:1|one[- ]to[- ]one|emulat\w+|port of)\b""", I)
    private val EVERYTHING = Regex("""\b(identical|exactly (?:like|the same)|everything|every (?:feature|detail|item|quest|skill)|all (?:the )?(?:features|content|quests|skills|items)|perfect(?:ly)?|100\s?%|fully|complete (?:game|clone|copy))\b""", I)
    private val PROBLEM = Regex("""(\bdoesn'?t\b|\bdoes not\b|\bdidn'?t\b|\bwon'?t\b|\bcan'?t\b|\bcannot\b|\bisn'?t\b|\bnot (?:working|loading|showing|saving|moving|starting|opening)\b|\bbroken\b|\bbug\b|\bcrash\w*|\berror\b|\bfails?\b|\bfailing\b|\bfreez\w+|\bstuck\b|\bblank\b|\bwrong\b|\bjust (?:re-?\w+|keeps?)\b|\bkeeps? (?:\w+ing)\b|\bstopped\b|\bno longer\b|\b(?:is|are|was|were|gets?|got|goes|go) (?:(?:often|sometimes|always|still|just|silently|randomly) )?(?:missing|ignored|lost|dropped|skipped)\b|\bout of order\b|\bin (?:a )?random order\b|\bshould\b[^.!?]{0,100}\bbut\b[^.!?]{0,80}\bstill\b|\bstill (?:pays|charges|shows|says|appears|happens|gives|returns|counts|prints)\b|\b(?:says|shows|reads|displays|prints)\s+["\u201c'][^"\u201d']{1,60}["\u201d']\s+(?:when|for|instead|after|on)\b|\bfix (?:it|this|that)\b)""", I)

    /** A big build that may already exist as open-source code or data. */
    fun bigBuild(text: String): Boolean = LIKE.containsMatchIn(text) || (Router.route(text).category == Router.Category.BUILD && text.length > 80)

    fun asksForEverything(text: String): Boolean = EVERYTHING.containsMatchIn(text)

    /** A report that something misbehaves. */
    fun reportsProblem(text: String): Boolean = !isQuestion(text) && PROBLEM.containsMatchIn(text) || Router.route(text).category == Router.Category.DEBUG

    private val TESTS = Regex("""\b(?:write|add|create|make)\b[^.]{0,40}?\btests?\b|\btests? (?:for|that|to check)\b|\bunit tests?\b|\bhas no tests\b""", I)

    /** The request asks for tests to be written (not only run). */
    fun asksForTests(text: String): Boolean = !isQuestion(text) && TESTS.containsMatchIn(text) && !Scope.reportOnly(text)

    // Kimi K2.6 (hard suite, 1.5.103): eleven passing tests of split(), every one a single example whose remainder was small,
    // so split() rounding its base share instead of flooring it went unnoticed; the doc's own promise ("add up to the amount")
    // checked over a loop of inputs would have caught it
    const val TESTS_NOTE = "\n\nThe tests must catch bugs, not only pass. For each promise the code's comments or docs make (adds up to the total, " +
        "differs by at most one, rounds half up, throws on bad input), write a check of that promise over a loop of many inputs, " +
        "including the edges (zero, one, the largest remainder, exact halves), not just one example. Then prove it: in a copy outside the project " +
        "(e.g. under /tmp), make one small wrong change at a time to the code under test (an off-by-one, round instead of floor, a swapped order, " +
        "a removed check) and run your tests against the copy; at least one must fail each time. Never change the project's own code to do this. " +
        "If a wrong change passes, add the test that catches it. Say in your answer which wrong changes you tried."

    const val REUSE_NOTE = "\n\nBefore building something this large, check whether it already exists: an open-source project, library, engine or data set " +
        "that does it (search the web and GitHub if you can). If one fits, tell the person what it is, its licence and how it would be used, and offer to use it " +
        "instead of rewriting it. Rewriting what already exists is slower and worse."

    const val SCOPE_NOTE = "\n\nThe request asks for everything or an exact copy. Say plainly, before you start, what can and cannot be achieved here " +
        "(with these tools, on a phone, in one request), then do the part that can, and end by naming what is still missing. Never call a partial result complete."

    const val REPRODUCE_NOTE = "\n\nThis is a problem report. First reproduce it (run it, look at the page, read the log or the code path), find the cause, " +
        "then fix that cause. Every symptom the person lists is something to make go away (an error that is silently ignored should be reported, not kept quiet): " +
        "before you reply, check each one against your change. " +
        "Start your reply with the cause in one plain sentence. If you cannot reproduce it, say so and say what you checked."

    // "I think money() is broken", "probably the parser": the person's idea of the cause, which may be wrong
    private val GUESS = Regex("""\b(?:i (?:think|guess|suspect|bet|believe|assume)|probably|maybe|might be|must be|seems? to be)\b""", I)

    /** The problem report names a suspected cause. */
    fun guessesCause(text: String): Boolean = GUESS.containsMatchIn(text)

    // Kimi K2.6 and gpt-oss-120b in the hard suite (1.5.89): told "I think money() is broken", both rewrote a correct money()
    // to take cents, when cart.total() broke its own "in dollars" contract after prices moved to cents
    const val GUESS_NOTE = "\n\nThe person suggests a cause. Treat it as a lead, not a fact: check it first. If that code does what its name and comment say, " +
        "the cause is elsewhere: follow the values to the code that breaks its own documented contract and fix that, keep code that is correct as it is, " +
        "and say plainly that the cause was somewhere else."

    // "Find every level that doesn't match the table": many items checked against a reference
    private val AUDIT_EVERY = Regex("""\b(?:every|all|each)\b""", I)
    private val AUDIT_COMPARE = Regex("""\b(?:match(?:es|ing)?|mismatch\w*|differ\w*|against|compared?|consistent)\b""", I)
    private val AUDIT_SOURCE = Regex("""\b(?:table|spec|specification|docs?|documentation|readme|reference|schema|list|csv|sheet|\w+\.(?:md|csv|json|txt|ya?ml))\b""", I)

    /** The request asks for many items to be checked against a reference (a table, spec, doc or data file). */
    fun audits(text: String): Boolean =
        AUDIT_EVERY.containsMatchIn(text) && AUDIT_COMPARE.containsMatchIn(text) && AUDIT_SOURCE.containsMatchIn(text)

    // Kimi K2.6 (hard suite, 1.5.90 candidate) compared 300 formulas with a table by eye, fixed two, missed a third and ran nothing to check
    const val AUDIT_NOTE = "\n\nThis asks to check many items against a reference. Do not compare them by eye: long files are easy to misread and " +
        "reads may be cut off. Run a short throwaway script (node -e or python3 -c, not a new file in the project) that reads both and prints every " +
        "mismatch, fix everything it lists, then run it again to show none are left."

    /** Notes for this message, by what it asks. */
    fun notes(text: String, question: Boolean = isQuestion(text)): String {
        if (question) return QUESTION_NOTE
        val sb = StringBuilder()
        if (reportsProblem(text)) sb.append(REPRODUCE_NOTE)
        if (asksForTests(text)) sb.append(TESTS_NOTE)
        if (reportsProblem(text) && guessesCause(text)) sb.append(GUESS_NOTE)
        if (bigBuild(text)) sb.append(REUSE_NOTE)
        if (asksForEverything(text)) sb.append(SCOPE_NOTE)
        if (audits(text)) sb.append(AUDIT_NOTE)
        return sb.toString()
    }

    // ---- how to talk, and knowing one's limits (in every system prompt) ----

    const val VOICE = "Talk like a capable colleague. Lead with the answer or the outcome in plain words, then why, briefly. " +
        "Mention files and commands only when they help the person. End with at most one next step. If the message is only a question, " +
        "answer it and change nothing."

    /** What to do when the model lacks the knowledge or the tool for the job. [web]: whether web_search is available. */
    fun limits(web: Boolean, autonomous: Boolean): String =
        " Know your limits. If the answer depends on facts you are not sure of (current versions, a library's interface, a game's or product's data, " +
            "anything recent or specific to this project you have not read), do not guess: " +
            when {
                autonomous && web -> "look it up with web_search and web_fetch, and say what you found and where."
                web -> "say in one sentence what you do not know and ask whether to research it with the web tools, unless the person already asked you to research or look it up, then do it."
                else -> "say what you do not know and suggest how to find out: turning on web research in Settings, or the person attaching the documentation or data."
            } +
            " If the job needs a tool or program you do not have, say which one and ask whether to install it (Tools) or which other way to go. " +
            "Read the project before you describe it."
}
