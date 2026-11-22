package dev.pocketide.app.core

/**
 * Set by the GPL edition: asks a language server for the errors in a file the assistant just wrote. The callback gets the note
 * for the assistant, or null. Null in the MIT edition, where nothing is checked.
 */
object LspHook {
    @Volatile var check: ((String, String, (String?) -> Unit) -> Unit)? = null

    /** Set by the GPL edition: (path, text, name, "definition" or "references", done) asks the language server where a name lives. */
    @Volatile var find: ((String, String, String, String, (String) -> Unit) -> Unit)? = null

    /** True when the language server can be asked now (GPL edition, switched on in Tools, Linux running). */
    @Volatile var enabled: () -> Boolean = { false }
}
