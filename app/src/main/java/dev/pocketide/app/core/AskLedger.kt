package dev.pocketide.app.core

/**
 * The ask checklist (1.5.138, Copilot's HANDOFF-RAISE-CEILING WP1). A request with several asks ("take the logs and call
 * lightFire; a tinderbox on raw shrimps does nothing") was often finished with one ask quietly left out, and nothing in the loop
 * compared the result with the asks one by one. Before a change request with two or more asks is called done, the model lists
 * each ask with its proof: the file and line where it is done, or the command that shows it and what it printed. An ask with no
 * proof goes back once, by name. The model splits the request itself (it knows "and" from "and"); the app only counts asks to
 * decide whether to ask, and reads the list it gets back.
 */
object AskLedger {
    const val HEAD = "ASKS"

    /** Words that start an ask, or show a sentence asks for something rather than describing it. */
    private val VERB = Regex("""^(?:please\s+)?(?:make|add|take|call|show|use|remove|delete|keep|fix|change|let|put|give|move|rename|set|create|write|update|ensure|stop|allow|return|save|load|hide|display|play|send|check|turn|replace|drop|clear|reset|limit|cap|log|print|sort|count|support|handle|treat|accept|reject|open|close|start|run|build|apply|store|mark|split|join|merge|convert|validate|don't|do\s+not|never|always|also|then)\b""", RegexOption.IGNORE_CASE)
    private val WANT = Regex("""\b(?:should|must|needs?\s+to|has\s+to|have\s+to|want(?:s|ed)?\b|would\s+like|please|can\s+you|could\s+you)\b""", RegexOption.IGNORE_CASE)
    private val AND_VERB = Regex("""\b(?:and|then|also)\s+(?:also\s+)?(make|add|take|call|show|use|remove|delete|keep|fix|change|put|give|move|rename|set|create|write|update|return|save|hide|display|send|turn|replace|clear|reset|log|print|sort|count|handle|open|close|store|mark)\b""", RegexOption.IGNORE_CASE)

    /** The request's sentences and list items. */
    fun parts(request: String): List<String> =
        request.split(Regex("""(?<=[.!?;])\s+|\n+|^\s*(?:[-*•]|\d+[.)])\s+""", RegexOption.MULTILINE))
            .map { it.trim().trimStart('-', '*', '•').trim() }.filter { it.split(Regex("\\s+")).size >= 2 }

    /** How many asks the request seems to hold: sentences that ask for something, plus "X and do Y" inside them. */
    fun count(request: String): Int = parts(request).sumOf { p ->
        val asks = VERB.containsMatchIn(p) || WANT.containsMatchIn(p)
        if (!asks) 0 else 1 + AND_VERB.findAll(p).count()
    }

    fun wanted(request: String): Boolean = count(request) >= 2

    /**
     * 1.5.139 (Copilot's choice, ANALYSIS-1.5.138-ASKS rec. 4): with a helper model set, the helper counts the asks once, because the
     * word counter above misses requests that describe a behaviour and then ask for it in other words. Without a helper, or when the
     * helper gives no number, the word counter decides. Since 1.5.142 the larger of the two counts decides: the helper can add the
     * checklist, never take it away (a missed ask costs a wrong result; an extra checklist one request).
     */
    const val COUNT_PROMPT = "You count the separate things a request asks to be done. A sentence that only describes the problem, or " +
        "says the same thing again in other words, is not a separate ask; each different change or behaviour asked for is one, including " +
        "each part joined by \"and\". Reply with the number only, for example: 3"

    fun countQuestion(request: String): String = "How many separate asks does this request have?\n\n\"" + request.take(3000) + "\""

    /**
     * The helper's number, or null when the reply has none (or an unlikely one). A reply that is only a number is that number; a
     * numbered list of the asks counts its items (1.5.142: in run 43511040146 a small helper model's answers were read as 1, its
     * list's first number, and the checklist was skipped); otherwise the last number in the reply.
     */
    fun parseCount(reply: String): Int? {
        val t = reply.trim().trim('*', '.', ' ')
        t.toIntOrNull()?.let { return it.takeIf { n -> n in 1..20 } }
        val items = reply.lines().count { Regex("""^\s*(?:\d{1,2}[.)]|[-*•])\s+\S""").containsMatchIn(it) }
        if (items >= 1) return items.takeIf { it in 1..20 }
        return Regex("""\b(\d{1,2})\b""").findAll(reply).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..20 }
    }

    /** Sent once before a multi-ask change request is called done. */
    fun ask(request: String): String =
        "Before you finish, check the work against the request one ask at a time. Split the request below into its separate asks " +
            "(each thing it asks for, including each part joined by \"and\"; leave out sentences that only describe the problem). " +
            "For each ask, give the proof: the file and line where it is done (path:line), or the command you ran and what it printed. " +
            "If an ask is not done yet, do it now (with the tools) before you write the list.\n" +
            "Then reply in exactly this format, and nothing after it:\n" +
            "$HEAD\n1. <the ask, in the request's words> | <proof>\n2. ...\n\n" +
            "Request: \"" + request.take(1500) + "\""

    class Line(val ask: String, val proof: String)

    private val LINE = Regex("""^\s*(\d+)[.)]\s*(.+?)\s*\|\s*(.*)$""")

    /** The list in a reply, or null when the reply has none. */
    fun parse(reply: String): List<Line>? {
        val lines = reply.lines()
        val at = lines.indexOfFirst { it.trim().trim('*', '#', ' ', ':').equals(HEAD, true) }
        if (at < 0) return null
        val out = lines.drop(at + 1).mapNotNull { LINE.find(it)?.let { m -> Line(m.groupValues[2].trim(), m.groupValues[3].trim()) } }
        return out.ifEmpty { null }
    }

    private val PATH_LINE = Regex("""[\w./\-]+\.\w{1,8}:\d+""")
    private val COMMAND = Regex("""`[^`]{2,}`|\b(?:ran|printed|prints|output|exit(?:ed)?\s+(?:code\s+)?0|passes|passed)\b""", RegexOption.IGNORE_CASE)
    /** Words that say an ask is not done, wherever they are in the proof. */
    private val NOT_DONE = Regex("""\b(?:not\s+(?:done|yet|implemented|handled|possible)|no\s+proof|could\s+not|couldn't|didn't|did\s+not|unverified)\b""", RegexOption.IGNORE_CASE)
    /**
     * Words that say so only when they are the whole proof ("missing", "n/a") or are stated of the ask ("still missing", "is skipped")
     * (1.5.140, Copilot's ANALYSIS-1.5.139-ASKS G2): they are often the ask's own subject, and 1.5.139 sent back a proven ask because
     * its proof quoted "Unknown option: --jsno".
     */
    private val WEAK = """(?:missing|skipped|todo|to\s+do|unknown|n/?a)"""
    private val NOT_DONE_ALONE = Regex("""^\W*$WEAK\W*$""", RegexOption.IGNORE_CASE)
    private val NOT_DONE_STATED = Regex("""\b(?:is|are|was|were|still|left)\s+(?:still\s+)?$WEAK\b""", RegexOption.IGNORE_CASE)

    /** A backticked span that is only a file name or path (1.5.139): it names a file and shows nothing was run. */
    private val BARE_PATH = Regex("""`[\w./\-]+\.\w{1,8}`""")

    /** Whether a line's proof shows where or how the ask was done. A path in backticks followed by :line still counts as path:line. */
    fun proven(line: Line): Boolean {
        val p = line.proof
        if (p.isBlank() || NOT_DONE.containsMatchIn(p) || NOT_DONE_ALONE.matches(p.trim()) || NOT_DONE_STATED.containsMatchIn(p)) return false
        val plain = BARE_PATH.replace(p) { it.value.trim('`') }
        return PATH_LINE.containsMatchIn(plain) || COMMAND.containsMatchIn(BARE_PATH.replace(p, ""))
    }

    /**
     * The person's answer once the checklist is done (1.5.139; 1.5.138 showed the bare list). Normally the summary the model wrote before
     * the checklist, or the reply's text before the list when there was no summary. [preferReply] puts the reply's own text first: when
     * the model wrote no list at all (its reply is an answer of its own), or when files changed after a send-back (it may say what was
     * fixed). After a send-back that changed nothing, the reply answers the app ("That ask is already implemented, here's the proof"),
     * not the person, so the summary stays (1.5.140, Copilot's ANALYSIS-1.5.139-ASKS G3).
     */
    fun answer(reply: String, summary: String, preferReply: Boolean): String {
        val lines = reply.lines()
        val at = lines.indexOfFirst { it.trim().trim('*', '#', ' ', ':').equals(HEAD, true) }
        val before = (if (at < 0) reply else lines.take(at).joinToString("\n")).trim()
        val first = if (preferReply) before else summary
        val second = if (preferReply) summary else before
        return first.ifBlank { second }.ifBlank { "Done." }
    }

    /** Sent once for the asks with no proof, by name. */
    fun again(unproven: List<Line>): String =
        "These asks have no proof yet:\n" + unproven.joinToString("\n") { "- " + it.ask + (if (it.proof.isBlank()) "" else " (you wrote: " + it.proof.take(160) + ")") } +
            "\n\nDo each of them now, or show where it is already done (path:line) or which command shows it. If one cannot be done, say why in plain words."
}
