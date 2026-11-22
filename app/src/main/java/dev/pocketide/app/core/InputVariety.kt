package dev.pocketide.app.core

/**
 * 1.5.154 (Copilot's parity handoff, R5): code that reads input from outside the project (a CSV export, a web page, a feed) is often
 * written for the one example the person gave and breaks on the next file. When the request is to parse, import or scrape such
 * input, the run is sent back once before it finishes to try its code on inputs in shapes it was not given. Off by default
 * (AppSettings.varietyProbe) until measured. The ask text is fixed: it is not to be tuned on results.
 */
object InputVariety {
    private val I = RegexOption.IGNORE_CASE

    private val VERB = Regex("""\b(?:pars(?:e|es|ed|ing|er)|import(?:s|ed|ing|er)?|scrap(?:e|es|ed|ing|er)|extract\w*|ingest\w*|read(?:s|ing)? in)\b""", I)
    private val INPUT = Regex("""\b(?:csv|tsv|json|xml|html|web ?pages?|pages?|feeds?|exports?|spreadsheets?|price lists?|recipes?|logs?|emails?|api responses?)\b""", I)

    /** True when the request asks for code that reads input from outside the project. */
    fun readsOutsideInput(request: String): Boolean {
        val r = request.take(4000)
        return (VERB.containsMatchIn(r) && INPUT.containsMatchIn(r)) || (TAKE.containsMatchIn(r) && SAMPLE.containsMatchIn(r))
    }

    // Copilot PR #29: "read my weather station's export; imports/readings-sample.json is one" did not fire (no "parse", no data noun),
    // so variants were written in only 15 of 30 runs. A reading verb plus a sample input file named under imports/, data/, samples/ or
    // fixtures/ now counts too. Fixed in the app; the frozen `variety` suite is not used to tune it.
    private val TAKE = Regex("""\b(?:read(?:s|ing)?|paste[sd]?|pasting|load(?:s|ed|ing)?|import\w*|pars\w*)\b""", I)
    private val SAMPLE = Regex("""\b(?:imports?|data|samples?|fixtures?|inputs?)/[\w./-]+\.(?:csv|tsv|json|jsonl|xml|html?|txt|md|log|ya?ml)\b""", I)

    private val CODE = setOf("js", "mjs", "cjs", "ts", "tsx", "jsx", "py", "kt", "java", "rb", "go", "rs", "php", "cs", "swift", "dart", "lua")

    /** True when a changed file is code (not data, docs or tests' fixtures alone). */
    fun changedCode(changed: Collection<String>): Boolean =
        changed.any { it.substringAfterLast('/').substringAfterLast('.', "").lowercase() in CODE }

    const val ASK = "Before you finish: this code reads input from outside the project, and so far it has only seen the example it " +
        "was given. Real inputs of this kind vary. Write two more inputs in shapes the example does not show (for files: quoted " +
        "fields, separators inside quotes, blank lines, Windows line endings, columns in another order or case, extra spaces; for " +
        "web pages: the text in one block, in a list, or split into sections), run your code on them, and fix what breaks. Keep " +
        "what the person asked for working. Then finish, and say which shapes you tried."
}
