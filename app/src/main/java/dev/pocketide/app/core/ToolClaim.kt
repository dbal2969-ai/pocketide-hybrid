package dev.pocketide.app.core

/**
 * "Node.js is not installed in this environment": a claim the app can test. Llama 3.3 70B (hard suite, 1.5.90 candidate) ran
 * `node:test`, read "command not found" as Node missing, and ended on that claim twice while node was on the PATH; gpt-oss-120b
 * at 1.5.88 reported "Node is not installed" without running anything. The app runs `command -v` for the tool once, and when it
 * is there the reply goes back with where it is.
 */
object ToolClaim {
    private val BINARY = mapOf(
        "node" to "node", "node.js" to "node", "nodejs" to "node", "npm" to "npm", "npx" to "npx", "python" to "python3", "python3" to "python3",
        "pip" to "pip3", "pip3" to "pip3", "git" to "git", "java" to "java", "deno" to "deno", "bun" to "bun", "cargo" to "cargo", "rustc" to "rustc",
        "gcc" to "gcc", "ruby" to "ruby", "php" to "php")

    private val CLAIM = Regex(
        """\b(node(?:\.?js)?|npm|npx|python3?|pip3?|git|java|deno|bun|cargo|rustc|gcc|ruby|php)\b(?:\s+(?:itself|and\s+\w+))?\s+""" +
            """(?:is\s+not|isn'?t|are\s+not|aren'?t|was\s+not|wasn'?t)\s+(?:\w+\s+)?(?:installed|available)""", RegexOption.IGNORE_CASE)

    /** The command the reply says is missing ("node" for "Node.js is not installed"), or null. */
    fun missing(reply: String): String? = CLAIM.find(reply)?.let { BINARY[it.groupValues[1].lowercase()] }

    /** The read-only check the app runs for [tool]. */
    fun probe(tool: String): String = "command -v $tool"

    /** Sent once when [probe] found [tool] at [where]. */
    fun ask(tool: String, where: String): String =
        "$tool is installed here (`command -v $tool` gives ${where.trim().lines().firstOrNull().orEmpty()}), so that is not what stops you. " +
            "Exit code 127 (\"command not found\") means the shell did not find the exact name that was typed, such as node:test; it does not mean " +
            "$tool is missing. Run the check with $tool itself, fix what fails, and then give your answer."
}
