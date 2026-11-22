package dev.pocketide.app.core

/** Rough checks used to warn when a small local model is asked for a big job. */
object ModelSize {
    private val SIZE = Regex("""(?<![A-Za-z0-9.])[eE]?(\d+(?:\.\d+)?)\s*[bB](?![A-Za-z])""")
    private val VERBS = Regex("""\b(create|build|make|write|implement|design|develop|generate|code|program)\b""", RegexOption.IGNORE_CASE)
    private val THINGS = Regex("""\b(app|application|game|server|engine|website|site|api|backend|frontend|project|program|bot|system|platform|clone|framework|compiler|emulator|mmo)\b""", RegexOption.IGNORE_CASE)

    /** Billions of parameters from a model name such as "qwen2.5-coder:1.5b" or "gemma3n:e2b", or null if not in the name. */
    fun billions(model: String): Double? =
        SIZE.findAll(model).mapNotNull { it.groupValues[1].toDoubleOrNull() }.lastOrNull()

    /** True for a model that names itself as under 7B parameters. Unknown sizes are not treated as small. */
    fun isSmall(model: String): Boolean = billions(model)?.let { it < 7.0 } ?: false

    /** True for models of 8B parameters or fewer, which often skip tool calls and mangle file edits. Unknown sizes are not treated as weak. */
    fun isWeakForFiles(model: String): Boolean = billions(model)?.let { it <= 8.0 } ?: false

    private val FILE_WORDS = Regex("""\.(docx|xlsx|pptx|odt|ods|odp|epub|csv|tsv|zip)\b|\b(document|spreadsheet|slide|slides|presentation|word file|excel|powerpoint|worksheet|heading|paragraph)\b""", RegexOption.IGNORE_CASE)
    private val EDIT_VERBS = Regex("""\b(add|change|edit|replace|insert|update|rewrite|fix|remove|delete|rename|append|sort|format|make|set|move|translate|summari[sz]e)\b""", RegexOption.IGNORE_CASE)

    /** A request to change a document or table file. */
    fun isFileEdit(text: String): Boolean = FILE_WORDS.containsMatchIn(text) && EDIT_VERBS.containsMatchIn(text)

    /** A request that asks for a whole program rather than a small change. */
    fun isBigRequest(text: String): Boolean {
        if (text.contains("from scratch", ignoreCase = true)) return true
        if (VERBS.containsMatchIn(text) && THINGS.containsMatchIn(text)) return true
        return text.length >= 400
    }
}
