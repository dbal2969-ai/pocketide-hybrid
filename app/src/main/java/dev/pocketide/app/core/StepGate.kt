package dev.pocketide.app.core

/**
 * The verified step queue: an order (one plan step) is only followed by the next one after it has been checked.
 * Two checks, in this order: facts the app can see for itself (files the order names, files that changed, pages that load their files),
 * then a read-only review by a model that sees only the order and must quote evidence for each point.
 */
object StepGate {
    const val FILE = ".pocketide/queue.md"
    const val MAX_TRIES = 2

    private val FILE_TOKEN = Regex("""(?<![\w/.-])((?:[\w.-]+/)*[\w-][\w.-]*\.(?:html|js|mjs|css|json|md|py|kt|java|ts|tsx|jsx|png|jpg|wav|mp3|txt|csv|svg))\b""")
    private val REMOVES = Regex("""\b(delete|remove|rename|move|drop|clean up)\b""", RegexOption.IGNORE_CASE)
    private val CHECK_ONLY = Regex("""^\W*(verify|check|review|confirm|look|test|inspect|audit|make sure|ensure)\b""", RegexOption.IGNORE_CASE)

    /** File names an order mentions (without placeholders like <id> or *). Orders that delete or rename files name none. */
    fun named(order: String): List<String> {
        if (REMOVES.containsMatchIn(order)) return emptyList()
        return FILE_TOKEN.findAll(order).map { it.groupValues[1] }.filter { !it.contains('<') && !it.contains('*') }.distinct().take(12).toList()
    }

    /** True for an order that asks only to look at something, which may change nothing. */
    fun checkOnly(order: String): Boolean = CHECK_ONLY.containsMatchIn(order.trim())

    /**
     * Problems the app can see without asking a model. [changed] is what this order changed; [files] is every project file.
     */
    fun appChecks(order: String, changed: List<String>, files: List<String>, read: (String) -> String?, size: (String) -> Long,
                  isRepo: (String) -> Boolean = { false }): List<String> {
        val out = ArrayList<String>()
        if (changed.isEmpty() && !checkOnly(order)) out.add("No file was changed, so the order was not carried out.")
        for (n in named(order)) {
            val hit = files.firstOrNull { it == n || it.endsWith("/$n") }
            if (hit == null) out.add("The order names $n, but there is no such file.")
            else if (size(hit) == 0L) out.add("$hit is empty.")
        }
        out.addAll(PageRefs.problems(files, read, size))
        // 1.5.164: stand-ins (the 2009scape field run's 15-file server written where setup.sh clones the real one)
        out.addAll(Placeholders.problems(changed, files, read, isRepo))
        return out.distinct().take(8)
    }

    /** The read-only review: the order, what changed and what the app found, answered in a fixed format with evidence. */
    fun reviewPrompt(order: String, number: Int, total: Int, changed: List<String>): String =
        "You are checking one finished order. Do not change any file. Read the files that matter.\n\n" +
            "Order $number of $total: $order\n\n" +
            "Files changed by this order: ${if (changed.isEmpty()) "none" else changed.take(20).joinToString(", ")}\n\n" +
            "For every separate thing the order asks for, find it in the files. Reply in exactly this format:\n" +
            "Evidence: <one line per thing the order asks for: the file and a short quote of the code or text that does it, or NOT FOUND>\n" +
            "Missing: <each thing that is NOT FOUND, partly done, or done differently from the order, or \"nothing\">\n" +
            "Verdict: PASS or FAIL\n" +
            "Pass only if every point has a quote you actually read. A claim without a quote counts as NOT FOUND. Do not say something works unless you saw the code for it."

    class Result(val pass: Boolean, val missing: String)

    /** Reads the reviewer's reply. Anything that is not a clear PASS with evidence and nothing missing counts as a failure. */
    fun verdict(reply: String): Result {
        val v = Regex("""(?im)^\W*Verdict\s*:\s*\W*(PASS|FAIL)""").find(reply)?.groupValues?.get(1)?.uppercase()
        val missing = Regex("""(?im)^\W*Missing\s*:\s*(.*)$""").find(reply)?.groupValues?.get(1)?.trim()?.trim('*', '"', '.', ' ').orEmpty()
        val none = missing.isEmpty() || missing.lowercase() in setOf("nothing", "none", "n/a", "-")
        val evidence = Regex("""(?is)Evidence\s*:(.*?)(?:^\W*Missing\s*:|\z)""", RegexOption.MULTILINE).find(reply)?.groupValues?.get(1).orEmpty()
        val proven = FILE_TOKEN.containsMatchIn(evidence) && !evidence.contains("NOT FOUND", true)
        if (v == null) return Result(false, "The reviewer did not give a clear PASS or FAIL.")
        if (v == "FAIL" || !none) return Result(false, if (none) "The reviewer failed the order without saying what is missing." else missing)
        if (!proven) return Result(false, "The reviewer passed the order without quoting the files it read.")
        return Result(true, "")
    }

    /** Sent back to the builder when an order did not pass. */
    fun fixPrompt(order: String, number: Int, problems: List<String>): String =
        "Order $number was checked and did not pass: $order\n\nFix exactly this, and nothing else:\n" + problems.joinToString("\n") { "- $it" } +
            "\n\nMake the changes now. End with one line that starts with \"Step done:\" saying what you changed."

    enum class State(val word: String) { QUEUED("queued"), ISSUED("issued"), VERIFYING("verifying"), VERIFIED("verified"), FAILED("failed"), SKIPPED("skipped") }

    /** The queue as a small text file: one line per order with its state. */
    fun encode(title: String, steps: List<String>, states: List<State>): String =
        "# Order queue: $title\nOne order is issued at a time; the next is released only after the last is verified.\n\n" +
            steps.indices.joinToString("\n") { "${it + 1}. [${states[it].word}] ${steps[it]}" } + "\n"

    fun decode(text: String): List<Pair<State, String>> =
        Regex("""(?m)^\d+\.\s*\[(\w+)]\s*(.+)$""").findAll(text).mapNotNull { m ->
            val s = State.values().firstOrNull { it.word == m.groupValues[1] } ?: return@mapNotNull null
            s to m.groupValues[2].trim()
        }.toList()
}
