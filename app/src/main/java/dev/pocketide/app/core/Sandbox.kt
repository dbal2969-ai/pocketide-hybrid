package dev.pocketide.app.core

/**
 * What a command is about to do, so the app can take a snapshot first and ask before the risky ones
 * even when Autorun is on. Pattern-based: it errs towards asking.
 */
object Sandbox {
    enum class Risk { SAFE, WRITES, NETWORK, DESTRUCTIVE, OUTSIDE }

    private val DESTRUCTIVE = listOf(
        Regex("""\brm\s+(-[a-zA-Z]*\s+)*(-[a-zA-Z]*r[a-zA-Z]*f|-[a-zA-Z]*f[a-zA-Z]*r)\b"""),
        Regex("""\brm\s+(-\S+\s+)*[/*~]"""),
        Regex("""\b(mkfs|fdisk|dd)\b"""),
        Regex("""\bgit\s+(push|reset\s+--hard|clean\s+-[a-z]*f|checkout\s+--\s|rebase)\b"""),
        Regex("""\b(shutdown|reboot|halt|poweroff)\b"""),
        Regex("""\bchmod\s+(-R\s+)?[0-7]*777\b"""),
        Regex(""">\s*/dev/(sd|block)"""),
        Regex("""\b(truncate|shred)\b"""),
        Regex("""\bfind\b[^|;]*-delete\b"""),
        Regex("""\bxargs\b[^|;]*\brm\b""")
    )

    private val NETWORK = listOf(
        Regex("""\b(curl|wget|nc|netcat|ssh|scp|rsync|ftp|telnet)\b"""),
        Regex("""\b(apk|apt|apt-get|pip|pip3|npm|pnpm|yarn|gem|cargo|go)\s+(add|install|get|update|upgrade|fetch|ci|publish)\b"""),
        Regex("""\bgit\s+(clone|fetch|pull|push|remote\s+add|ls-remote)\b"""),
        Regex("""\bollama\s+(pull|run|push)\b"""),
        Regex("""\bpython3?\s+-m\s+(pip|http\.server)\b""")
    )

    private val WRITES = listOf(
        Regex("""(^|[|;&]\s*)(rm|mv|cp|mkdir|rmdir|touch|sed\s+-i|tee|ln)\b"""),
        Regex("""[^|>]>>?\s*\S"""),
        Regex("""\b(make|gradlew?|gradle|cc|gcc|g\+\+|javac|tsc|cargo\s+build|go\s+build|npm\s+run)\b"""),
        // unpacking archives writes files too
        Regex("""\b(unzip|gunzip|bunzip2|unxz|patch)\b|\btar\s+-?[a-zA-Z]*x|\b7z\s+[xe]\b""")
    )

    private val OUTSIDE = listOf(
        // 1.5.137: also after = : ( and an opening quote ("cat \"/etc/passwd\"", --file=/etc/hosts); a URL's "//" is not a path
        // (the letter must follow the first slash). After < and > see REDIRECT_OUTSIDE.
        Regex("""(^|[\s=:('"])(/(?!work|tmp|dev/null)[a-zA-Z]|~/|[$]\{?HOME)"""),
        Regex("""\.\./\.\./"""),
        Regex("""\bsudo\b""")
    )

    private val REDIRECT_OUTSIDE = Regex("""[<>]\s*(/(?!work|tmp|dev/null)[a-zA-Z]|~/|[$]\{?HOME)""")

    private val QUOTED = Regex(""""[^"]*"|'[^']*'""")
    private val NO_FILE = Regex("""[0-9&]?>>?\s*/dev/null\b|[0-9]?>&[0-9-]""")

    /** The worst thing this command line looks like it would do. */
    fun risk(command: String): Risk {
        val c = command.trim()
        if (c.isEmpty()) return Risk.SAFE
        if (DESTRUCTIVE.any { it.containsMatchIn(c) }) return Risk.DESTRUCTIVE
        if (OUTSIDE.any { it.containsMatchIn(c) }) return Risk.OUTSIDE
        // 1.5.137: a redirection to or from a path outside ("cat </etc/passwd" was shown as "This only reads." with "Always allow
        // cat", emulator test of 1.5.136). Only outside quotes: grep '</body>' is text.
        if (REDIRECT_OUTSIDE.containsMatchIn(c.replace(QUOTED, "''"))) return Risk.OUTSIDE
        if (NETWORK.any { it.containsMatchIn(c) }) return Risk.NETWORK
        // 1.5.127: "which node 2>/dev/null" was shown as "This changes files in the project" (emulator test). Discarding output
        // (> /dev/null) and joining streams (2>&1) write no file.
        val w = c.replace(QUOTED, "''").replace(NO_FILE, " ")   // and a > inside quotes ("<script>") is text, not a redirect
        if (WRITES.any { it.containsMatchIn(w) }) return Risk.WRITES
        return Risk.SAFE
    }

    /** True when the person should be asked even though Autorun is on. */
    fun needsConfirmation(command: String): Boolean = when (risk(command)) {
        Risk.DESTRUCTIVE, Risk.OUTSIDE, Risk.NETWORK -> true
        else -> false
    }

    /** What to tell them, in plain words. */
    fun explain(command: String): String = when (risk(command)) {
        Risk.DESTRUCTIVE -> "This can delete or overwrite work, or change your Git history."
        Risk.OUTSIDE -> "This looks like it reaches outside the project folder."
        Risk.NETWORK -> "This uses the network: it may download or upload something."
        Risk.WRITES -> "This changes files in the project."
        Risk.SAFE -> "This only reads."
    }

    /** Worth saving a checkpoint first. */
    fun shouldSnapshot(command: String): Boolean = risk(command) != Risk.SAFE

    /** How long to allow, in seconds: installs and builds need longer than a quick read. */
    fun timeoutSeconds(command: String, linux: Boolean): Long {
        val base = if (linux) 600L else 120L
        val c = command.lowercase()
        val slow = Regex("""\b(apk|apt|apt-get|pip|pip3|npm|pnpm|yarn|cargo|go|gradle|gradlew|make|ollama)\b""").containsMatchIn(c)
        return if (slow) maxOf(base, 1800L) else base
    }
}
