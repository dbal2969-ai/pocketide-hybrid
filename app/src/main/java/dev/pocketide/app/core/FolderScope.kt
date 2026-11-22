package dev.pocketide.app.core

/**
 * "Work only in this folder". The person names a folder in their request; from then on the app, not the model,
 * decides: writes outside the folder are refused (reads stay allowed so the rest of the project can be used as reference).
 */
object FolderScope {
    // "only working in the "X" folder", "work only inside X folder", "stay within the 'X' directory", "restrict everything to the X folder"
    private val NAME = """["“'‘]?([^"”'’\n]{1,80}?)["”'’]?"""
    private val IN = Regex(
        """(?:only|just|solely|exclusively|strictly)\s+(?:work(?:ing)?\s+|operate\s+|save\s+(?:files\s+)?|put\s+(?:files\s+)?|create\s+(?:files\s+)?)?(?:in|inside|within|under)\s+(?:the\s+|my\s+)?$NAME\s+(?:folder|directory|dir)\b""",
        RegexOption.IGNORE_CASE)
    private val STAY = Regex(
        """(?:stay|keep(?:\s+everything)?|restrict(?:\s+(?:everything|all\s+work|yourself))?|limit(?:\s+(?:everything|all\s+work|yourself))?)\s+(?:strictly\s+)?(?:in|inside|within|to)\s+(?:the\s+|my\s+)?$NAME\s+(?:folder|directory|dir)\b""",
        RegexOption.IGNORE_CASE)
    private val OUTSIDE = Regex(
        """(?:don'?t|do\s+not|never)\s+(?:touch|change|edit|modify|write\s+(?:to\s+)?)\s+(?:(?:any|every)(?:thing)?\s+)?(?:files?\s+)?outside\s+(?:of\s+)?(?:the\s+|my\s+)?$NAME\s+(?:folder|directory|dir)\b""",
        RegexOption.IGNORE_CASE)

    /** The folder the request restricts work to, matched to a real folder in [dirs] when there is one. Null when the request names none. */
    fun detect(text: String, dirs: List<String>): String? {
        val raw = listOf(IN, STAY, OUTSIDE).firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1) } ?: return null
        val name = raw.trim().trim('/').trim()
        if (name.isEmpty() || name.contains("..")) return null
        val clean = dirs.map { it.trim('/') }
        clean.firstOrNull { it.equals(name, ignoreCase = true) }?.let { return it }
        clean.firstOrNull { it.substringAfterLast('/').equals(name, ignoreCase = true) }?.let { return it }
        return name
    }

    private fun norm(p: String) = p.trim().removePrefix("./").trim('/')

    /** True when [path] is inside [scope] ("" means the whole project). */
    fun allows(scope: String, path: String): Boolean {
        val s = norm(scope)
        if (s.isEmpty()) return true
        val p = norm(path)
        return p.equals(s, ignoreCase = false) || p.startsWith("$s/")
    }

    private val WRITERS = setOf("rm", "mv", "cp", "touch", "mkdir", "rmdir", "tee", "truncate", "ln", "chmod", "install", "dd")

    /**
     * The path a shell command would write outside the folder, or null when it looks fine. This is a guard for the
     * common cases (redirects, rm/mv/cp/touch/mkdir/tee, sed -i, cd .. and absolute paths), not a sandbox.
     */
    fun commandViolation(scope: String, command: String): String? {
        val s = norm(scope)
        if (s.isEmpty()) return null
        var cwd = ""   // relative to the project root; "" = root
        for (segment in command.split(Regex("""&&|\|\||;|\n|\|"""))) {
            val toks = tokens(segment)
            if (toks.isEmpty()) continue
            fun resolve(t: String): String? {          // project-relative path, or null if it leaves the project
                if (t.startsWith("/") || t.startsWith("~")) return null
                val parts = ArrayList<String>()
                for (x in (if (cwd.isEmpty()) t else "$cwd/$t").split('/')) when (x) { "", "." -> {} ".." -> { if (parts.isEmpty()) return null; parts.removeAt(parts.size - 1) } else -> parts.add(x) }
                return parts.joinToString("/")
            }
            fun bad(t: String): String? {
                if (t.isEmpty() || t.startsWith("-") || t == "/dev/null" || t.startsWith("/dev/") || t.startsWith("/tmp/")) return null
                val r = resolve(t) ?: return t
                return if (allows(s, r)) null else t
            }
            val cmd = toks[0].substringAfterLast('/')
            if (cmd == "cd") {
                val target = toks.getOrNull(1) ?: ""
                if (target.isEmpty() || target == "~") return if (s.isEmpty()) null else "~"
                val r = resolve(target) ?: return target
                cwd = r
                continue
            }
            // redirects: > path, >> path, 2> path
            for ((i, t) in toks.withIndex()) {
                val m = Regex("""^\d*>>?(.*)$""").find(t) ?: continue
                val target = m.groupValues[1].ifEmpty { toks.getOrNull(i + 1) ?: "" }
                if (target.startsWith("&")) continue
                bad(target)?.let { return it }
            }
            val args = toks.drop(1).filterNot { it.contains('>') }
            val writes = cmd in WRITERS || (cmd == "sed" && toks.any { it == "-i" || it.startsWith("-i") }) || (cmd == "git" && args.firstOrNull() in setOf("checkout", "restore", "clean", "reset", "rm", "mv"))
            if (writes) for (a in args) { if (a.startsWith("-")) continue; if (cmd == "git" && a == args.first()) continue; bad(a)?.let { return it } }
        }
        return null
    }

    private fun tokens(s: String): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder(); var q = '\u0000'; var has = false
        for (c in s) {
            if (q != '\u0000') { if (c == q) q = '\u0000' else cur.append(c) }
            else if (c == '"' || c == '\'') { q = c; has = true }
            else if (c.isWhitespace()) { if (cur.isNotEmpty() || has) { out.add(cur.toString()); cur.setLength(0); has = false } }
            else cur.append(c)
        }
        if (cur.isNotEmpty() || has) out.add(cur.toString())
        return out
    }

    fun commandRefusal(scope: String, path: String): String =
        "Refused: this command would change \"$path\", which is outside the folder \"${norm(scope)}\". Run it with paths inside \"${norm(scope)}/\" (for example cd ${norm(scope)} first) or tell the person it needs a different folder."

    /** What the model is told when it tries to write outside the folder. */
    fun refusal(scope: String, path: String): String =
        "Refused: you may only create or change files inside the folder \"${norm(scope)}\", and \"${norm(path)}\" is outside it. " +
            "Use the path \"${norm(scope)}/${norm(path).substringAfterLast('/')}\" instead. Files outside the folder can be read but not changed."

    /** Added to the system prompt while a folder is set. */
    fun note(scope: String): String {
        val s = norm(scope)
        if (s.isEmpty()) return ""
        return "\n\nFOLDER RULE: The person restricted all work to the folder \"$s\". Every file you create or change must have a path that starts with \"$s/\". " +
            "Other folders in the project are reference only: read them if useful, never write to them. A write outside \"$s/\" will be refused."
    }

    /** A short line for the chip under the chat header. */
    fun label(scope: String): String = "Working only in: " + norm(scope)
}
