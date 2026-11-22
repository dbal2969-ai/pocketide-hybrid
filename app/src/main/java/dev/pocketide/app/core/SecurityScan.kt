package dev.pocketide.app.core

/**
 * A quick security check of the files the assistant changed, run when it finishes (Lovable scans every publish after
 * thousands of generated apps shipped with open database rules; Copilot's reviewer looks for secrets). Pattern checks
 * only: keys and passwords written into code, database rules open to everyone, SQL built from request data, and
 * eval of request data. Matched secrets are never repeated in full.
 */
object SecurityScan {
    enum class Level { HIGH, MEDIUM }

    data class Finding(val path: String, val line: Int, val level: Level, val what: String, val fix: String) {
        val text: String get() = "$path:$line: $what. $fix"
    }

    private class Rule(val regex: Regex, val level: Level, val what: String, val fix: String)

    private const val KEY_FIX = "Move it to an environment variable or a settings file that is not committed, and revoke the key if it was shared"

    private val RULES = listOf(
        Rule(Regex("""-----BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"""), Level.HIGH, "Private key written into the file", KEY_FIX),
        Rule(Regex("""\bAKIA[0-9A-Z]{16}\b"""), Level.HIGH, "AWS access key in code", KEY_FIX),
        Rule(Regex("""\b(ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{30,}\b|\bgithub_pat_[A-Za-z0-9_]{40,}\b"""), Level.HIGH, "GitHub token in code", KEY_FIX),
        Rule(Regex("""\bsk-ant-[A-Za-z0-9_-]{20,}"""), Level.HIGH, "Anthropic API key in code", KEY_FIX),
        Rule(Regex("""\bsk-(proj-)?[A-Za-z0-9_-]{32,}"""), Level.HIGH, "OpenAI-style API key in code", KEY_FIX),
        Rule(Regex("""\bAIza[0-9A-Za-z_-]{35}\b"""), Level.HIGH, "Google API key in code", KEY_FIX),
        Rule(Regex("""\b[sr]k_live_[0-9A-Za-z]{20,}"""), Level.HIGH, "Stripe live secret key in code", KEY_FIX),
        Rule(Regex("""\bxox[baprs]-[0-9A-Za-z-]{10,}"""), Level.HIGH, "Slack token in code", KEY_FIX),
        Rule(Regex("""\bcfut_[A-Za-z0-9]{30,}"""), Level.HIGH, "Cloudflare API token in code", KEY_FIX),
        Rule(Regex("""\bgsk_[A-Za-z0-9]{40,}"""), Level.HIGH, "Groq API key in code", KEY_FIX),
        Rule(Regex("""(?i)\b(password|passwd|pwd|secret|api[_-]?key|access[_-]?token)\b["']?\s*[:=]\s*["'][^"'\s]{4,}["']"""), Level.MEDIUM,
            "Password or secret written into the code", "Read it from an environment variable or a config file kept out of Git"),
        Rule(Regex(""""\.(read|write)"\s*:\s*true"""), Level.HIGH, "Firebase database rule open to everyone",
            "Require sign-in, for example \".read\": \"auth != null\", and limit each user to their own data"),
        Rule(Regex("""allow\s+(read|write|read,\s*write)\s*:\s*if\s+true"""), Level.HIGH, "Firestore or Storage rule open to everyone",
            "Use a condition such as request.auth != null and check the user owns the document"),
        Rule(Regex("""(?i)disable\s+row\s+level\s+security|using\s*\(\s*true\s*\)"""), Level.HIGH, "Database row security turned off or open to everyone",
            "Keep row level security on and write policies that check auth.uid()"),
        Rule(Regex("""(?i)\b(query|execute|exec|raw)\s*\(\s*[`"'][^`"']*\b(select|insert|update|delete)\b[^`"']*(\$\{\s*req\.|["'`]\s*\+\s*req\.)"""), Level.HIGH,
            "SQL built from request data (SQL injection)", "Use placeholders (? or \$1) and pass the values separately"),
        Rule(Regex("""\beval\s*\(\s*req\.|new\s+Function\s*\([^)]*req\."""), Level.HIGH, "Request data run as code", "Never run text from a request as code; parse it instead")
    )

    private val TEXT_EXT = setOf("js", "mjs", "cjs", "ts", "tsx", "jsx", "py", "rb", "php", "go", "java", "kt", "cs", "json", "yml", "yaml", "toml",
        "env", "sql", "html", "htm", "vue", "svelte", "rules", "sh", "ini", "cfg", "conf", "properties", "xml", "md")

    private fun scannable(path: String): Boolean {
        val name = path.substringAfterLast('/')
        if (path.contains("node_modules/") || name.endsWith(".min.js") || name == "package-lock.json") return false
        if (name.endsWith(".example") || name.endsWith(".sample") || name.contains(".example.")) return false
        return name.startsWith(".env") || name.substringAfterLast('.', "").lowercase() in TEXT_EXT
    }

    /** Findings in [files] (path to text), most serious first, at most [max]. */
    fun scan(files: List<Pair<String, String>>, max: Int = 20): List<Finding> {
        val out = ArrayList<Finding>()
        for ((path, text) in files) {
            if (!scannable(path) || text.length > 400_000) continue
            val lines = text.split("\n")
            for ((i, line) in lines.withIndex()) {
                if (line.length > 2000) continue
                for (r in RULES) {
                    if (!r.regex.containsMatchIn(line)) continue
                    // placeholders in docs and examples are not secrets
                    if (r.level == Level.MEDIUM && Regex("""(?i)(your[_-]|example|changeme|xxx|\*\*\*|<[^>]+>|process\.env|os\.environ|getenv)""").containsMatchIn(line)) continue
                    out.add(Finding(path, i + 1, r.level, r.what, r.fix))
                    break
                }
            }
        }
        return out.sortedBy { it.level }.take(max)
    }

    /** One note for the person. */
    fun summary(f: List<Finding>): String =
        "Security check: ${f.size} problem(s) in the changed files" + (if (f.any { it.level == Level.HIGH }) ", some serious" else "") + ":\n" +
            f.take(8).joinToString("\n") { "• " + it.path + ":" + it.line + " " + it.what }

    /** Told to the model once, when something serious was found. */
    fun request(f: List<Finding>): String =
        "A security check of the files you changed found these problems:\n" + f.take(10).joinToString("\n") { "- " + it.text } +
            "\nFix them now without changing what the app does. Do not print any secret in your reply. If one is a false alarm, say why."
}
