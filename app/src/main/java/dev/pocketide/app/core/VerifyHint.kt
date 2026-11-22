package dev.pocketide.app.core

/**
 * What the model is asked when it changed files and has not checked them (1.5.127). Before, the ask put run_command first and
 * look_at_preview last for every change; for a web page on a phone without Python or Node that sent Kimi through python3
 * http.server, which and grep for 18 minutes (emulator test). A page is checked with look_at_preview first now.
 */
object VerifyHint {
    private val WEB = Regex("""(?i)\.(html?|css|m?js|jsx|tsx?|svg)$""")

    fun isWeb(path: String): Boolean = WEB.containsMatchIn(path.trim())

    fun ask(web: Boolean, canLook: Boolean): String =
        "You changed files in this request but have not checked them since the last change. Before you give your final answer, check the work: " +
            (if (web && canLook) "this is a web page, so call look_at_preview now: it loads the page in the app's browser, runs its scripts and " +
                "reports errors and what is on screen. Use run_command only for tests or programs that exist here"
            else "run the tests or the program with run_command" + (if (canLook) ", or use look_at_preview for a page" else "")) +
            ", then fix anything that fails. If nothing can be run here, say exactly what you could not check."
}
