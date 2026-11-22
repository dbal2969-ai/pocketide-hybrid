package dev.pocketide.app.core

import java.io.File

/**
 * Project secrets, like GitHub's repository secrets: API keys and passwords kept in the app's own storage (never in the project's
 * files or history), given to commands and servers as environment variables, and masked out of every output the assistant or
 * the person sees. The assistant is told their names only.
 */
class Secrets(private val file: File) {
    private val NAME = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")

    fun all(): Map<String, String> {
        if (!file.isFile) return emptyMap()
        val o = try { MiniJson.obj(MiniJson.parse(file.readText())) } catch (e: Exception) { null } ?: return emptyMap()
        return o.mapNotNull { (k, v) -> (v as? String)?.let { k to Vault.open(it) } }.filter { it.second.isNotEmpty() }.toMap(java.util.TreeMap())
    }

    fun set(name: String, value: String) {
        require(NAME.matches(name)) { "A secret's name uses letters, numbers and _ and does not start with a number (like API_KEY)." }
        val m = LinkedHashMap(all()); m[name] = value; save(m)
    }

    fun remove(name: String) { val m = LinkedHashMap(all()); m.remove(name); save(m) }

    /** Seals values still stored as plain text (from before 1.5.134). */
    fun sealPlain() {
        if (!file.isFile || Vault.cipher == null) return
        val o = try { MiniJson.obj(MiniJson.parse(file.readText())) } catch (e: Exception) { null } ?: return
        if (o.values.any { it is String && it.isNotEmpty() && !Vault.isSealed(it) }) save(all())
    }

    /** Values are sealed with the Android Keystore (Vault, 1.5.134); plain ones from before are sealed on the next save. */
    private fun save(m: Map<String, String>) { file.parentFile?.mkdirs(); file.writeText(MiniJson.write(m.mapValues { Vault.seal(it.value) })) }

    companion object {
        /** [text] with every secret value (4 characters or longer) replaced by its name. */
        fun mask(text: String, secrets: Map<String, String>): String {
            var t = text
            for ((k, v) in secrets.entries.sortedByDescending { it.value.length }) if (v.length >= 4) t = t.replace(v, "***$k***")
            return t
        }

        /** What the assistant is told: the names, so it can use them as environment variables, never the values. */
        fun note(names: Collection<String>): String = if (names.isEmpty()) "" else
            "\n\nThis project has secrets set as environment variables for commands and servers: ${names.sorted().joinToString()}. " +
                "Use them by name (process.env.NAME, os.environ[\"NAME\"], \$NAME). Never write their values into files; you cannot see them."
    }
}

/**
 * A project's setup file, like a dev container for Codespaces: .pocketide/setup.json lists the Linux packages and the steps a
 * project needs ({"packages": ["python3", "nodejs"], "run": ["npm install"]}). Opening the project (or a fork) offers to run it once.
 */
object ProjectSetup {
    const val FILE = ".pocketide/setup.json"

    class Setup(val packages: List<String>, val run: List<String>, val note: String)

    fun parse(text: String?): Setup? {
        val o = try { MiniJson.obj(MiniJson.parse(text ?: return null)) } catch (e: Exception) { null } ?: return null
        fun strs(k: String) = MiniJson.list(o[k]).orEmpty().mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } }
        val pk = strs("packages").filter { Regex("^[A-Za-z0-9.+_-]{1,80}$").matches(it) }
        return Setup(pk, strs("run"), (o["note"] as? String).orEmpty()).takeIf { it.packages.isNotEmpty() || it.run.isNotEmpty() }
    }

    /** One shell command that installs the packages with whichever package manager is there, then runs the steps in order. */
    fun command(s: Setup): String {
        val parts = ArrayList<String>()
        if (s.packages.isNotEmpty()) {
            val p = s.packages.joinToString(" ")
            parts.add("if command -v apk >/dev/null 2>&1; then apk add --no-cache $p; elif command -v apt-get >/dev/null 2>&1; then apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq $p; fi")
        }
        parts.addAll(s.run)
        return "set -e; " + parts.joinToString(" && ")
    }

    /** A stamp of the file, so a project is only offered its setup again when the file changed. */
    fun stamp(text: String): String = VcsStore.sha1(text.toByteArray()).take(12)

    /** A starting setup file from the tools the project's run targets need. */
    fun draft(tools: Set<String>): String {
        val map = mapOf("python" to "python3", "node" to "nodejs npm", "go" to "go", "rust" to "rust cargo", "cc" to "build-base", "java" to "openjdk17-jdk")
        val pk = tools.mapNotNull { map[it] }.flatMap { it.split(' ') }.distinct()
        return MiniJson.write(linkedMapOf("note" to "Installed when the project is opened (Alpine package names; Ubuntu uses its own).", "packages" to pk, "run" to emptyList<String>())) + "\n"
    }
}

/** Saved pieces of code to reuse in any project, like GitHub Gists. */
class Snippets(private val file: File) {
    class Snippet(val title: String, val lang: String, val code: String, val time: Long)

    fun all(): List<Snippet> {
        if (!file.isFile) return emptyList()
        return try { MiniJson.list(MiniJson.parse(file.readText())).orEmpty().mapNotNull { MiniJson.obj(it) }.map {
            Snippet(it["title"] as? String ?: "", it["lang"] as? String ?: "", it["code"] as? String ?: "", (it["time"] as? Number)?.toLong() ?: 0) } } catch (e: Exception) { emptyList() }
    }

    fun add(title: String, lang: String, code: String, now: Long = System.currentTimeMillis()) = save(all().filter { it.title != title } + Snippet(title.ifBlank { code.lineSequence().first().take(40) }, lang, code, now))

    fun remove(title: String) = save(all().filter { it.title != title })

    private fun save(l: List<Snippet>) { file.parentFile?.mkdirs(); file.writeText(MiniJson.write(l.map { linkedMapOf("title" to it.title, "lang" to it.lang, "code" to it.code, "time" to it.time) })) }

    companion object {
        fun langOf(path: String): String = path.substringAfterLast('.', "").lowercase()
    }
}

/** Searching every project at once. Pure over folders. */
object CrossSearch {
    class Hit(val project: String, val path: String, val line: Int, val text: String)

    private val SKIP = setOf(".git", "node_modules", "build", "dist", "target", "__pycache__", "venv", ".venv", ".gradle", "cache")
    private val TEXT = setOf("js", "mjs", "ts", "tsx", "jsx", "html", "htm", "css", "json", "md", "txt", "py", "kt", "kts", "java", "go", "rs", "c", "h", "cpp", "sh", "yml", "yaml", "toml", "xml", "svg", "sql", "rb", "php", "lua", "gradle", "csv")

    fun search(projects: List<File>, query: String, limit: Int = 300, regex: Boolean = false, stop: () -> Boolean = { false }): List<Hit> {
        if (query.isBlank()) return emptyList()
        val re = try { if (regex) Regex(query, RegexOption.IGNORE_CASE) else Regex(Regex.escape(query), RegexOption.IGNORE_CASE) } catch (e: Exception) { return emptyList() }
        val out = ArrayList<Hit>()
        for (p in projects) {
            val walk = p.walkTopDown().onEnter { it == p || it.name !in SKIP }
            for (f in walk) {
                if (out.size >= limit || stop()) return out
                if (!f.isFile || f.length() > 1_000_000 || f.extension.lowercase() !in TEXT) continue
                val rel = f.relativeTo(p).path.replace('\\', '/')
                try { f.useLines { lines -> lines.forEachIndexed { i, l -> if (out.size < limit && re.containsMatchIn(l)) out.add(Hit(p.name, rel, i + 1, l.trim().take(160))) } } } catch (e: Exception) { }
            }
        }
        return out
    }
}

/**
 * The whole project checked at once (GitHub's code scanning): secrets, broken JSON, leftover conflict markers, unfinished
 * stand-in code. Findings become issues labelled "code check". Pure logic over the files.
 */
object CodeCheck {
    class Finding(val title: String, val detail: String)

    fun run(files: List<Pair<String, String>>): List<Finding> {
        val out = ArrayList<Finding>()
        val secrets = SecurityScan.scan(files)
        for ((path, group) in secrets.groupBy { it.path }.entries.take(30))
            out.add(Finding("Possible secret or risky code in $path", group.joinToString("\n") { "line ${it.line}: ${it.what} (${it.level.name.lowercase()}). ${it.fix}" }.take(1500)))
        for ((p, t) in files) {
            if (p.endsWith(".json", true)) try { MiniJson.parse(t) } catch (e: Exception) { out.add(Finding("$p is not valid JSON", (e.message ?: "syntax error").take(300))) }
            if (VcsText.hasConflictMarkers(t)) out.add(Finding("$p still has merge conflict markers", "Lines between <<<<<<< and >>>>>>> need a decision."))
        }
        val stubs = files.filter { (_, t) -> Regex("""(?i)\b(TODO: implement|not implemented|stub|placeholder implementation)\b""").containsMatchIn(t) }.map { it.first }
        if (stubs.isNotEmpty()) out.add(Finding("Unfinished code in ${stubs.size} file(s)", stubs.take(20).joinToString("\n")))
        return out
    }
}
