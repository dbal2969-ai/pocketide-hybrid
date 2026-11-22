package dev.pocketide.app.core

/**
 * A command that needs a program Android's own shell does not have, asked for while Linux is not running (1.5.129). The prompt
 * already says "Python, Node and git are not installed", yet in the emulator test Kimi asked to run `python3 -m http.server`,
 * then `which node`, then `grep` to check a page, and each time the person had to answer a Run card for a command that could not
 * help. Such a command is now answered at once, without a card, with what to do instead.
 */
object NoLinuxCommand {
    /** Programs that come only with the Linux environment (Tools & Linux); Android's shell has none of them. */
    private val LINUX_ONLY = setOf(
        "python", "python3", "pip", "pip3", "node", "npm", "npx", "yarn", "pnpm", "deno", "bun", "git", "gcc", "g++", "cc", "c++",
        "make", "cmake", "go", "rustc", "cargo", "java", "javac", "kotlinc", "ruby", "gem", "php", "perl", "curl", "ollama", "sqlite3",
        "pytest", "jest", "tsc", "eslint", "prettier",
    )
    private val PREFIX = setOf("sudo", "env", "time", "nohup", "exec")

    /** The first Linux-only program the command starts, or null. Looks at each part of `a && b || c ; d` and through `env VAR=x`. */
    fun program(command: String): String? {
        for (part in command.split(Regex("""&&|\|\||;|\|"""))) {
            val words = part.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
            var i = 0
            while (i < words.size && (words[i] in PREFIX || (words[i].contains('=') && !words[i].startsWith("-")))) i++
            if (i >= words.size) continue
            if (words[i] == "command" && words.getOrNull(i + 1) != "-v") i++   // `command npm test` runs npm
            if (i >= words.size) continue
            val prog = words[i].substringAfterLast('/')
            // `which node` / `command -v python3` only ask whether it exists: answered too, since the answer is known
            val asked = if (prog == "which" || (prog == "command" && words.getOrNull(i + 1) == "-v")) words.drop(i + 1).firstOrNull { !it.startsWith("-") }?.substringAfterLast('/') else null
            val p = asked ?: prog
            if (p in LINUX_ONLY) return p
        }
        return null
    }

    fun reply(program: String, canLookAtPage: Boolean): String =
        "Not run: $program is not installed here. This phone has no Linux environment running, only Android's own shell " +
            "(ls, cat, grep, sed and the like). Do not ask for $program, other interpreters or package managers again in this request. " +
            (if (canLookAtPage) "To check a web page, call look_at_preview: it loads the page, runs its scripts and reports errors. " else "") +
            "If the work cannot be checked here, say exactly what you could not check. The person can set up Linux from ⋮ → Tools & Linux (the MIT edition first offers the Linux add-on there)."
}
