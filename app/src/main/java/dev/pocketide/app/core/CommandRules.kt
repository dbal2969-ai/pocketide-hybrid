package dev.pocketide.app.core

/**
 * The person's own allow and deny rules for commands from the assistant (VS Code's terminal auto-approve rules; Claude
 * Code's deny rules). A rule is a command prefix such as "npm test" or "git push". Rules live in the app's settings, not
 * in the project, so a downloaded repository cannot allow commands for itself.
 *
 * A compound command (a && b, a; b, a | b) runs without asking only if every part is allowed, as in VS Code. A deny rule
 * that matches any part blocks the whole command. Allow rules never skip the question for commands that can delete work
 * or reach outside the project (Sandbox.Risk.DESTRUCTIVE / OUTSIDE).
 */
object CommandRules {
    data class Rules(val allow: List<String>, val deny: List<String>)

    enum class Decision { RUN, ASK, DENY }

    val EMPTY = Rules(emptyList(), emptyList())

    fun encode(r: Rules): String = MiniJson.write(linkedMapOf("allow" to r.allow, "deny" to r.deny))

    fun decode(json: String?): Rules {
        if (json.isNullOrBlank()) return EMPTY
        val o = try { MiniJson.obj(MiniJson.parse(json)) } catch (e: Exception) { null } ?: return EMPTY
        fun list(k: String) = (o[k] as? List<*>).orEmpty().mapNotNull { (it as? String)?.let(::normalize)?.takeIf { s -> s.isNotEmpty() } }.distinct()
        return Rules(list("allow"), list("deny"))
    }

    fun normalize(prefix: String): String = prefix.trim().replace(Regex("\\s+"), " ")

    /** The parts of a compound command, split on &&, ||, ;, | and new lines outside quotes. */
    fun parts(command: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quote: Char? = null
        var i = 0
        val c = command
        fun flush() { val t = sb.toString().trim(); if (t.isNotEmpty()) out.add(t); sb.setLength(0) }
        while (i < c.length) {
            val ch = c[i]
            when {
                quote != null -> { if (ch == quote) quote = null; sb.append(ch) }
                ch == '\'' || ch == '"' -> { quote = ch; sb.append(ch) }
                ch == '\n' || ch == ';' -> flush()
                (ch == '&' || ch == '|') && i + 1 < c.length && c[i + 1] == ch -> { flush(); i++ }
                ch == '|' -> flush()
                // a lone & runs the left side in the background and goes on: two commands (1.5.134); not the & of 2>&1 or &>
                ch == '&' && (i == 0 || c[i - 1] != '>') && (i + 1 >= c.length || c[i + 1] != '>') -> flush()
                else -> sb.append(ch)
            }
            i++
        }
        flush()
        return out
    }

    /** The part starts with [prefix] as whole words ("npm test" matches "npm test --watch", not "npm tester"). */
    fun matches(prefix: String, part: String): Boolean {
        val p = normalize(prefix); val s = normalize(part)
        return p.isNotEmpty() && (s == p || s.startsWith("$p "))
    }

    /** Files that may hold keys or passwords: a read-only command that names one still asks. */
    private val SECRET = Regex("""(secret|\.env\b|passw|token|credential|api[_-]?key|private[_-]?key|id_rsa|\.pem\b|\.keystore\b|local\.properties)""", RegexOption.IGNORE_CASE)

    /** Commands that only read files or the project's state. */
    private val READERS = setOf("ls", "cat", "head", "tail", "wc", "grep", "egrep", "fgrep", "rg", "file", "stat", "pwd", "tree", "du", "nl", "sort", "uniq", "cut", "diff", "cmp", "md5sum", "sha256sum", "basename", "dirname", "realpath", "which", "echo", "find")
    private val GIT_READS = setOf("status", "log", "diff", "show", "branch", "ls-files", "blame")
    /** git branch only lists with these (no -d, -D, -m, -M, -c, -f, or a new branch name). */
    private val GIT_BRANCH_LIST = setOf("-a", "--all", "-r", "--remotes", "-v", "-vv", "--verbose", "--list", "--show-current", "--no-color", "--color")
    /** find actions that run, delete or write files. */
    private val FIND_ACTS = setOf("-exec", "-execdir", "-ok", "-okdir", "-delete", "-fprint", "-fprint0", "-fprintf", "-fls")

    /** The words of one part, with quotes and backslashes removed as the shell would ("cat '../x'" reads ../x). */
    fun words(part: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var quote: Char? = null; var any = false; var i = 0
        while (i < part.length) {
            val ch = part[i]
            when {
                quote != null -> if (ch == quote) quote = null else if (ch == '\\' && quote == '"' && i + 1 < part.length) { sb.append(part[i + 1]); i++ } else sb.append(ch)
                ch == '\'' || ch == '"' -> { quote = ch; any = true }
                ch == '\\' && i + 1 < part.length -> { sb.append(part[i + 1]); i++; any = true }
                ch.isWhitespace() -> { if (sb.isNotEmpty() || any) out.add(sb.toString()); sb.setLength(0); any = false }
                else -> sb.append(ch)
            }
            i++
        }
        if (sb.isNotEmpty() || any) out.add(sb.toString())
        return out
    }

    /**
     * The shell would change the words before the command sees them: a variable ($HOME, $PWD, $1) anywhere but inside single
     * quotes, or a glob, brace or input redirection (* ? [ { <) outside quotes. "cat $HOME/.bashrc" and "cat .e[n]v" looked like plain reads inside
     * the project but were not (Copilot, ANALYSIS-1.5.134, 1.5.135). Such a command still runs; it only asks first.
     */
    fun expands(command: String): Boolean {
        var quote: Char? = null; var i = 0
        while (i < command.length) {
            val ch = command[i]
            when {
                quote == '\'' -> if (ch == '\'') quote = null
                ch == '$' -> return true
                quote == '"' -> if (ch == '"') quote = null else if (ch == '\\') i++
                ch == '\'' || ch == '"' -> quote = ch
                ch == '\\' -> i++
                ch == '*' || ch == '?' || ch == '[' || ch == '{' -> return true
                // 1.5.136: input redirection reads any file ("cat </etc/passwd"), like > writes one (Copilot, ANALYSIS-1.5.135)
                ch == '<' -> return true
            }
            i++
        }
        return false
    }

    /** A path that leaves the project: absolute, home, or with "..". */
    private fun leaves(v: String): Boolean =
        v.startsWith("/") || v.startsWith("~") || v == ".." || v.startsWith("../") || v.contains("/../") || v.endsWith("/..")

    /** Short flags ("-nro") that contain any of [letters]. */
    private fun shortHas(flags: List<String>, letters: String) = flags.any { it.startsWith("-") && !it.startsWith("--") && it.drop(1).any { c -> c in letters } }
    private fun longHas(flags: List<String>, vararg names: String) = flags.any { f -> names.any { n -> f == n || f.startsWith("$n=") } }

    /** True when [path] (relative to the project) stays inside [root] after symlinks are followed. */
    fun insideRoot(root: java.io.File): (String) -> Boolean = { path ->
        try {
            val base = root.canonicalFile
            val f = java.io.File(base, path).canonicalFile
            f == base || f.path.startsWith(base.path + java.io.File.separator)
        } catch (e: Exception) { false }
    }

    /**
     * True when every part of [command] only reads, inside the project: a known reading command with none of its flags that write,
     * delete, run programs or follow links out, no redirection or substitution, no path that leaves the project (quoted or not,
     * or through a symlink: [inside]), and no file that may hold keys or passwords.
     * 1.5.132 and 1.5.134: written from the bypasses Copilot found (ANALYSIS-1.5.131 S5, ANALYSIS-1.5.133).
     */
    fun readsOnly(command: String, inside: (String) -> Boolean = { true }): Boolean {
        if (SECRET.containsMatchIn(command) || Regex("""(^|[^0-9&])>|\$\(|`|<\(|\$\{""").containsMatchIn(command)) return false
        if (expands(command)) return false
        val ps = parts(command)
        return ps.isNotEmpty() && ps.all { part ->
            val w = words(part)
            if (w.isEmpty()) return@all false
            // the secret check again on the words as the shell sees them ("cat .e''nv" reads .env), 1.5.135
            if (w.any { SECRET.containsMatchIn(it) }) return@all false
            val args = w.drop(1)
            val flags = args.filter { it.startsWith("-") }
            for (a in args) {
                val v = if (a.startsWith("-") && a.contains('=')) a.substringAfter('=') else a
                if (leaves(v)) return@all false
                if (!a.startsWith("-") && v.isNotEmpty() && !inside(v)) return@all false
            }
            when (w[0]) {
                "git" -> args.isNotEmpty() && args[0] in GIT_READS &&
                    !longHas(flags, "--output", "--ext-diff", "--textconv", "--exec", "--exec-path", "--no-index") &&
                    (args[0] != "branch" || args.drop(1).all { it in GIT_BRANCH_LIST })
                "find" -> args.none { it in FIND_ACTS || it == "-L" || it == "-follow" }
                "sort" -> !shortHas(flags, "oT") && !longHas(flags, "--output", "--compress-program", "--temporary-directory")
                "uniq" -> args.count { !it.startsWith("-") } <= 1
                "rg" -> !shortHas(flags, "zL") && !longHas(flags, "--pre", "--pre-glob", "--search-zip", "--hostname-bin", "--follow")
                "grep", "egrep", "fgrep" -> !shortHas(flags, "R") && !longHas(flags, "--dereference-recursive")
                "file" -> !shortHas(flags, "Cm") && !longHas(flags, "--compile", "--magic-file")
                "tree" -> !shortHas(flags, "oRl") && !longHas(flags, "--output", "--fromfile")
                "tail" -> !shortHas(flags, "fF") && !longHas(flags, "--follow", "--retry")
                "du", "ls" -> !shortHas(flags, "L") && !longHas(flags, "--dereference")
                else -> w[0] in READERS
            }
        }
    }

    fun decide(command: String, rules: Rules, autorun: Boolean, inside: (String) -> Boolean = { true }): Decision {
        val ps = parts(command)
        if (ps.isEmpty()) return Decision.ASK
        if (ps.any { part -> rules.deny.any { matches(it, part) } }) return Decision.DENY
        val risk = Sandbox.risk(command)
        // a command that only reads (ls, cat, grep, head, wc, git status …) runs without a card: the read tools could show the
        // same thing, and a card for "cat tests/play.txt" only stopped the run until someone tapped (1.5.83). Deny rules still apply.
        if (risk == Sandbox.Risk.SAFE && readsOnly(command, inside)) return Decision.RUN
        val hardRisk = risk == Sandbox.Risk.DESTRUCTIVE || risk == Sandbox.Risk.OUTSIDE
        if (!hardRisk && ps.all { part -> rules.allow.any { matches(it, part) } }) return Decision.RUN
        return if (autorun && !Sandbox.needsConfirmation(command)) Decision.RUN else Decision.ASK
    }

    /** The deny rule that blocks [command], for the message to the assistant. */
    fun blockedBy(command: String, rules: Rules): String? =
        parts(command).firstNotNullOfOrNull { part -> rules.deny.firstOrNull { matches(it, part) } }

    /** The rule offered by "Always allow": the first one or two words of the first part ("npm test", "node --check", "ls"). */
    fun suggest(command: String): String? {
        val first = parts(command).firstOrNull() ?: return null
        val words = first.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val two = words.size > 1 && !words[1].contains('/') && !words[1].contains('.') && words[1].length <= 20
        return if (two) words[0] + " " + words[1] else words[0]
    }

    fun add(r: Rules, prefix: String, allow: Boolean): Rules {
        val p = normalize(prefix)
        if (p.isEmpty()) return r
        return if (allow) Rules((r.allow + p).distinct(), r.deny - p) else Rules(r.allow - p, (r.deny + p).distinct())
    }

    fun remove(r: Rules, prefix: String): Rules { val p = normalize(prefix); return Rules(r.allow - p, r.deny - p) }

    fun listing(r: Rules): String =
        if (r.allow.isEmpty() && r.deny.isEmpty()) "No command rules yet. Tap \"Always allow\" on a command, or type /rules allow npm test, /rules deny git push."
        else "Command rules:\n" + r.allow.joinToString("") { "✓ allow: $it\n" } + r.deny.joinToString("") { "✕ deny: $it\n" } +
            "Change them with /rules allow …, /rules deny …, /rules remove …, /rules clear."
}
