package dev.pocketide.app.core

/**
 * A second look in a fresh context (1.5.161, Copilot HANDOFF-CALIBRATION S4; setting "verify.fresh", off until measured).
 *
 * Four finish checks that speak to the model in its own conversation (cause-first, the ask checklist, ScopeGate, InputVariety) showed
 * no gain: a model that has argued itself into "the cause was the data" answers its own question the same way. This one sends a
 * separate request with no history and no tools: the person's request, the diff of what changed, the final message and the last
 * test output. The reviewer cannot be talked round, because it never hears the argument. When it finds a requested part the diff
 * does not cover, a changed file the request did not need, or a claim the diff and output do not support, its reply goes back to the
 * model once, as a user message, and the run carries on. Never twice; a failed or slow call (30 s) lets the run finish as it would have.
 */
object FreshVerify {
    /** The diff sent is cut here (characters); the request and the final message are cut at [TEXT_CAP] each. */
    const val DIFF_CAP = 12_000
    const val TEXT_CAP = 4_000
    const val TIMEOUT_MS = 30_000L
    const val WORDS = 120

    val PROMPT = "You check finished work against the request it was for. You see only the person's request, the diff of the changes, " +
        "the assistant's final message, and the last test output if there was one. You cannot run anything or ask anything.\n\n" +
        "Answer in exactly three lines, at most $WORDS words in all:\n" +
        "Missing: each part of the request the diff does not do, separated by \"; \", or none\n" +
        "Not needed: each changed file the request did not need, with a few words on what it adds, or none\n" +
        "Not shown: each claim in the final message that the diff or the test output does not support, or none\n\n" +
        "Count a part as done when the diff plainly does it; do not ask for more than the request asked. Tests added or changed for " +
        "the requested change are needed. When the request mentions something the project does not have, saying so in the final " +
        "message is the right answer, and adding it is not needed."

    /** One changed file: its text before the run (null = new) and now (null = deleted). */
    data class Change(val path: String, val before: String?, val after: String?)

    /** A unified-style diff of [changes], one line of context, secrets masked, cut at [DIFF_CAP]. */
    fun diff(changes: List<Change>, mask: (String) -> String = { SecretMask.mask(it) }): String {
        val sb = StringBuilder()
        for (c in changes) {
            if (c.before == c.after) continue
            sb.append("--- ").append(if (c.before == null) "/dev/null" else "a/" + c.path).append('\n')
            sb.append("+++ ").append(if (c.after == null) "/dev/null" else "b/" + c.path).append('\n')
            val ops = Diff.lines(c.before.orEmpty(), c.after.orEmpty())
            if (ops == null) { sb.append("(too large to compare: ").append(c.after?.lines()?.size ?: 0).append(" lines now)\n"); continue }
            for (r in Diff.compact(ops)) {
                if (r.tag == '~') sb.append("@@ ").append(r.skipped).append(" unchanged lines @@\n")
                else sb.append(r.tag).append(r.text).append('\n')
            }
            if (sb.length > DIFF_CAP) break
        }
        val text = mask(sb.toString())
        return if (text.length <= DIFF_CAP) text else text.take(DIFF_CAP) + "\n[the diff goes on; cut at ${DIFF_CAP / 1000} KB]\n"
    }

    /** The one message the reviewer gets. */
    fun question(request: String, diff: String, finalMessage: String, testOutput: String?, mask: (String) -> String = { SecretMask.mask(it) }): String =
        "Request:\n" + mask(request.trim()).take(TEXT_CAP) + "\n\nDiff:\n" + diff.ifBlank { "(no changes)" } +
            "\nFinal message:\n" + mask(finalMessage.trim()).take(TEXT_CAP).ifBlank { "(none)" } +
            (testOutput?.trim()?.takeIf { it.isNotEmpty() }?.let { "\n\nLast test output:\n" + mask(it).takeLast(TEXT_CAP) } ?: "")

    data class Finding(val missing: String, val notNeeded: String, val notShown: String) {
        val any: Boolean get() = listOf(missing, notNeeded, notShown).any { it.isNotEmpty() }
    }

    private val LINE = Regex("""^[\s*>#-]*\**\s*(missing|not needed|not shown)\s*\**\s*:\s*\**\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val NONE = Regex("""^(none|nothing|n/?a|no|-|—)?[.\s]*$""", RegexOption.IGNORE_CASE)

    /** The reviewer's three lines, each "" when it found nothing; null when the reply is not in that shape (then nothing is sent). */
    fun parse(reply: String?): Finding? {
        if (reply.isNullOrBlank()) return null
        val got = HashMap<String, String>()
        for (l in reply.lines()) {
            val m = LINE.find(l.trim()) ?: continue
            val v = m.groupValues[2].trim().trimEnd('*').trim()
            got.putIfAbsent(m.groupValues[1].lowercase(), if (NONE.matches(v)) "" else v)
        }
        if (got.isEmpty()) return null
        return Finding(got["missing"].orEmpty(), got["not needed"].orEmpty(), got["not shown"].orEmpty())
    }

    /** What goes back to the model when [f] found something. */
    fun note(f: Finding): String = "A reviewer who saw only your request, the diff and your final message found:\n" +
        (if (f.missing.isNotEmpty()) "Missing: ${f.missing}\n" else "") +
        (if (f.notNeeded.isNotEmpty()) "Not needed: ${f.notNeeded}\n" else "") +
        (if (f.notShown.isNotEmpty()) "Not shown: ${f.notShown}\n" else "") +
        "\nDo what is right in this: make a missing part, put back a change nobody asked for, or correct your message. If a point is " +
        "wrong, say why in one line and leave it. Then finish."
}
