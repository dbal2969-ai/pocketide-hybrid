package dev.pocketide.app.core

/**
 * Error handlers that throw the error away (1.5.104): `catch (e) { // ignore }`, `except: pass`, `.catch(() => {})`. In the hard
 * suite gpt-oss-120b, asked to fix an import where "a bad row is silently ignored", wrapped each insert in an empty catch: the rows
 * saved in order, its own run looked right, and the bad row was still silently ignored (in every run, with or without a prompt note
 * listing the symptom). An added handler that discards the error is pointed out when it is written, and when the person complained
 * about errors being ignored it is asked about once before the work is called done.
 */
object SwallowedErrors {
    private val CODE = Regex("""(?i)\.(?:js|mjs|cjs|jsx|ts|tsx|java|kt|kts|cs|dart|swift|php|py)$""")
    private val PY = Regex("""(?i)\.py$""")
    private val SKIP = Regex("""(?i)(^|/)(node_modules|dist|build|vendor|\.git)/|\.min\.""")
    private const val GAP = """(?:\s|//[^\n]*|/\*[\s\S]*?\*/)*"""
    // catch (e) { } with nothing but comments inside; `catch {` (no binding) too
    private val C_CATCH = Regex("""\bcatch\s*(?:\([^)]*\))?\s*\{$GAP\}""")
    // promise.catch(() => {}), .catch(e => null), .catch(function () {})
    private val P_CATCH = Regex("""\.catch\(\s*(?:async\s+)?(?:\w+|\([^)]*\))\s*=>\s*(?:\{$GAP\}|null|undefined|void 0)\s*\)|\.catch\(\s*function\s*\w*\s*\([^)]*\)\s*\{$GAP\}\s*\)""")
    // except ...: pass (comments and blank lines allowed before pass)
    private val PY_EXCEPT = Regex("""(?m)^[ \t]*except\b[^:\n]*:[ \t]*(?:#[^\n]*)?\n(?:[ \t]*(?:#[^\n]*)?\n)*[ \t]*(?:pass|\.\.\.)[ \t]*(?:#[^\n]*)?$|^[ \t]*except\b[^:\n]*:[ \t]*(?:pass|\.\.\.)[ \t]*$""")

    /** The person says errors go unnoticed: "silently ignored", "no error", "swallowed". */
    private val SILENT = Regex("""(?i)\b(?:silent(?:ly)?|swallow\w*|quietly|without (?:an? )?(?:error|warning|message)|no error|ignor\w+ (?:the )?(?:error|bad|invalid|failure)|(?:errors?|failures?|bad \w+|invalid \w+) (?:\w+ )?(?:is |are |get |gets )?(?:ignored|lost|hidden))\b""")

    fun checks(path: String): Boolean = CODE.containsMatchIn(path) && !SKIP.containsMatchIn(path)

    fun complainsOfSilence(request: String): Boolean = SILENT.containsMatchIn(request)

    /** Line numbers of every handler in [text] that discards the error. */
    fun lines(path: String, text: String): List<Int> {
        if (!checks(path)) return emptyList()
        val rx = if (PY.containsMatchIn(path)) listOf(PY_EXCEPT) else listOf(C_CATCH, P_CATCH)
        return rx.flatMap { r -> r.findAll(text).map { m -> text.substring(0, m.range.first).count { it == '\n' } + 1 + leading(m.value) } }.distinct().sorted()
    }

    // a Python match may start on the blank part of the line before `except`
    private fun leading(s: String): Int = s.takeWhile { it == '\n' }.length

    /**
     * Handlers this write added: the ones in [now] beyond those [before] had (null: a new file). Empty when the earlier text is
     * not known, so old code is never blamed on this write.
     */
    fun added(path: String, before: String?, now: String, known: Boolean = true): List<Int> {
        if (!known || !checks(path)) return emptyList()
        val had = before?.let { lines(path, it).size } ?: 0
        val all = lines(path, now)
        return if (all.size > had) all.takeLast(all.size - had) else emptyList()
    }

    /** Added to the write's reply. */
    fun note(path: String, lines: List<Int>, silence: Boolean): String =
        "Warning: $path now throws an error away (line ${lines.take(3).joinToString(", ")}): the handler is empty, so nothing ever learns the " +
            "work failed. " + (if (silence) "The person complained that errors go unnoticed, so this keeps that problem. " else "") +
            "Let the error reach the caller (do not catch it, or rethrow it), or handle it for real: report it, count it or return it, as the code's " +
            "comments and callers expect."

    /** Sent back once before the work is called done, when the person complained of ignored errors and the handler is still empty. */
    fun ask(left: Map<String, List<Int>>): String =
        "Before finishing: the person said errors are silently ignored, and these handlers still throw the error away: " +
            left.entries.joinToString("; ") { (p, l) -> "$p line ${l.joinToString(", ")}" } +
            ". Make the failure visible to the caller (let it throw, or rethrow after any cleanup), then check it with a bad input."
}
