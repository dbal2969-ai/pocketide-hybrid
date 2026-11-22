package dev.pocketide.app.core

// Project hooks in .pocketide/hooks.json (Claude Code has PreToolUse / PostToolUse hooks; Codex keeps some paths read-only).
// Example file:
//   { "protect": ["engine/**", "*.png"],
//     "afterWrite": [ { "match": "*.js", "run": "node --check {file}" } ],
//     "beforeFinish": [ "npm test" ] }
// protect: paths the assistant may not write. .git/ and signing keys are always protected.
// afterWrite: a command run after the assistant writes a matching file; its result goes back to the assistant.
// beforeFinish: commands that must pass (exit 0) before the assistant may finish a request that changed files; a failure goes
// back to it to fix, like a Stop hook in Claude Code.
// {file} is replaced with the written path. A hooks file can come from a downloaded repository, so its commands are
// shown and confirmed like any other command unless the person lets commands run on their own.
object Hooks {
    const val FILE = ".pocketide/hooks.json"

    val ALWAYS_PROTECTED = listOf(".git/**", "*.keystore", "*.jks", "**/*.keystore", "**/*.jks")

    data class After(val match: String, val run: String)
    data class Config(val protect: List<String>, val afterWrite: List<After>, val error: String? = null, val beforeFinish: List<String> = emptyList())
    const val MAX_FINISH = 3

    fun parse(json: String?): Config {
        if (json.isNullOrBlank()) return Config(emptyList(), emptyList())
        val o = try { MiniJson.obj(MiniJson.parse(json)) } catch (e: Exception) { null }
            ?: return Config(emptyList(), emptyList(), "$FILE is not valid JSON, so its hooks are ignored")
        val protect = (o["protect"] as? List<*>).orEmpty().mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } }
        val after = (o["afterWrite"] as? List<*>).orEmpty().mapNotNull { x ->
            val m = x as? Map<*, *> ?: return@mapNotNull null
            val run = (m["run"] as? String)?.trim().orEmpty()
            if (run.isEmpty()) null else After((m["match"] as? String)?.trim()?.ifEmpty { null } ?: "**", run)
        }.take(5)
        val finish = (o["beforeFinish"] as? List<*>).orEmpty().mapNotNull { x ->
            ((x as? String) ?: ((x as? Map<*, *>)?.get("run") as? String))?.trim()?.takeIf { it.isNotEmpty() }
        }.take(MAX_FINISH)
        return Config(protect, after, beforeFinish = finish)
    }

    /** Glob match on project paths: ** spans folders, * stays inside one, ? is one character. A pattern without / also matches by file name. */
    fun matches(glob: String, path: String): Boolean {
        val g = glob.trim().removePrefix("./")
        val target = if (!g.contains('/')) path.substringAfterLast('/') else path
        val sb = StringBuilder("^")
        var i = 0
        while (i < g.length) {
            val c = g[i]
            when {
                c == '*' && i + 1 < g.length && g[i + 1] == '*' -> {
                    i++
                    if (i + 1 < g.length && g[i + 1] == '/') { i++; sb.append("(?:.*/)?") } else sb.append(".*")
                }
                c == '*' -> sb.append("[^/]*")
                c == '?' -> sb.append("[^/]")
                else -> sb.append(Regex.escape(c.toString()))
            }
            i++
        }
        return Regex(sb.append("$").toString()).matches(target)
    }

    /** Why [path] may not be written, or null when it may. */
    fun protectedBy(cfg: Config, path: String): String? {
        ALWAYS_PROTECTED.firstOrNull { matches(it, path) }?.let { return "always protected" }
        // the hooks file decides what is protected and which commands run, so only the person may change it
        if (path == FILE) return "the person's hooks file (only they change it)"
        return cfg.protect.firstOrNull { matches(it, path) }?.let { "protected by \"$it\" in $FILE" }
    }

    /** The commands to run after [path] was written, with {file} filled in. */
    fun after(cfg: Config, path: String): List<String> =
        cfg.afterWrite.filter { matches(it.match, path) }.map { it.run.replace("{file}", shellQuote(path)) }

    /** What the assistant is told when beforeFinish commands failed: (command, exit code, output) each. */
    fun finishRequest(failures: List<Triple<String, Int, String>>): String =
        "Not done yet: the project's finish checks ($FILE, beforeFinish) failed. Fix the code so they pass, then finish. Do not change the checks or $FILE.\n\n" +
            failures.joinToString("\n\n") { (cmd, code, out) -> "`$cmd` exited with $code:\n" + Verify.trim(out, 1500) }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
