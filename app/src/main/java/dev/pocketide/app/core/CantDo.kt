package dev.pocketide.app.core

/**
 * "That isn't possible" with a tool for it at hand (1.5.170, HANDOFF-FABLE-TRUST-RESPONSE change 5). The chat with no project told
 * the owner it could not take files from one project into another, when the app could; a weak model says it cannot do something far
 * more often than a strong one, and the person believes it. A reply that says it cannot is sent back once with the tools it has, so it
 * tries them first or says exactly what is missing. Pure logic.
 */
object CantDo {
    private val CLAIM = Regex(
        """\b(?:(?:is|it's|that's|isn't|is not)\s+(?:not\s+)?(?:possible|supported)|not\s+possible|impossible|""" +
            """i\s+(?:can(?:no|')?t|am\s+(?:not\s+able|unable))\s+(?:to\s+)?(?:access|reach|open|read|see|modify|change|edit|create|copy|move|run|do\s+(?:that|this|it)|work\s+(?:on|with)|make)|""" +
            """i\s+(?:don't|do\s+not)\s+have\s+(?:access|the\s+ability|a\s+(?:tool|way)|any\s+(?:tool|way))|""" +
            """(?:outside|beyond)\s+(?:of\s+)?(?:my|this|the)\s+(?:project|scope|abilities|capabilities)|no\s+way\s+(?:for\s+me\s+)?to)\b""",
        RegexOption.IGNORE_CASE)

    /** The words in [text] that say the job cannot be done, or null. */
    fun claim(text: String): String? = CLAIM.find(text)?.value

    /** Sent once: what was said, and the tools that might do it. */
    fun ask(said: String, tools: List<String>): String =
        "You said \"$said\". Before saying something cannot be done, try it with the tools you have: " + tools.joinToString(", ") + "." +
            (if ("projects" in tools) " The projects tool lists, reads, searches and copies between the person's other projects by name, and makes branches and commits in them." else "") +
            " If it still cannot be done, say exactly what is missing (a file, a permission, a tool) and what the person can do instead."
}
