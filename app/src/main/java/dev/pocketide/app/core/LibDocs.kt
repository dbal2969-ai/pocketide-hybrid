package dev.pocketide.app.core

/**
 * Up-to-date library docs (1.5.112): the assistant reads the docs of the exact version a project uses before it writes code against
 * a library, instead of guessing an API from memory (the Context7 server does this for Claude Code and Cursor). The version comes
 * from the project (package.json, package-lock.json, a CDN script tag, requirements.txt); the docs are that version's README and,
 * for npm packages, its type definitions, from jsDelivr and PyPI. Only the sections that match the question are returned.
 * Pure logic: fetching is passed in, so it is JVM-testable.
 */
object LibDocs {
    const val MAX = 7000
    private const val CAP = 600_000

    enum class Kind { NPM, PYPI }
    class Lib(val name: String, val version: String?, val kind: Kind, val from: String)

    private val NPM_NAME = Regex("""^(?:@[a-z0-9][\w.-]*/)?[a-z0-9][\w.-]*$""", RegexOption.IGNORE_CASE)
    private val PY_NAME = Regex("""^[A-Za-z0-9][\w.-]*$""")
    private val CDN = Regex("""https?://(?:cdn\.jsdelivr\.net/npm|unpkg\.com|esm\.sh|cdn\.skypack\.dev)/((?:@[\w.-]+/)?[\w.-]+)@(\d[\w.+-]*)""")
    private val CDN_NOVER = Regex("""https?://(?:cdn\.jsdelivr\.net/npm|unpkg\.com|esm\.sh)/((?:@[\w.-]+/)?[\w.-]+)(?=[/"'])""")
    private val REQ = Regex("""(?m)^\s*([A-Za-z0-9][\w.-]*)\s*(?:\[[^\]]*])?\s*(?:==|~=|>=)\s*([\w.+-]+)""")
    private val REQ_BARE = Regex("""(?m)^\s*([A-Za-z0-9][\w.-]*)\s*(?:#.*)?$""")

    fun validName(name: String, kind: Kind): Boolean = name.length <= 214 && (if (kind == Kind.NPM) NPM_NAME else PY_NAME).matches(name)

    /** The libraries a project uses, with the version it pins when it pins one. Lock files win over ranges. */
    fun used(paths: Collection<String>, read: (String) -> String?): List<Lib> {
        val out = LinkedHashMap<String, Lib>()
        fun put(l: Lib) { val k = l.kind.name + ":" + l.name.lowercase(); val old = out[k]; if (old == null || (old.version == null && l.version != null) || l.from.endsWith("lock.json")) out[k] = l }
        val sorted = paths.filter { !it.contains("node_modules/") }.sorted()
        for (p in sorted.filter { it.endsWith("package.json") }) {
            val o = try { MiniJson.obj(MiniJson.parse(read(p) ?: continue)) } catch (e: Exception) { null } ?: continue
            for (sec in listOf("dependencies", "devDependencies", "peerDependencies")) {
                for ((n, v) in MiniJson.obj(o[sec]).orEmpty()) {
                    if (!validName(n, Kind.NPM)) continue
                    val ver = (v as? String)?.trim()?.removePrefix("^")?.removePrefix("~")?.removePrefix(">=")?.trim()?.takeIf { Regex("""^\d[\w.+-]*$""").matches(it) }
                    put(Lib(n, ver, Kind.NPM, p))
                }
            }
        }
        for (p in sorted.filter { it.endsWith("package-lock.json") }) {
            val text = read(p)?.takeIf { it.length < CAP * 4 } ?: continue
            val o = try { MiniJson.obj(MiniJson.parse(text)) } catch (e: Exception) { null } ?: continue
            for ((k, v) in MiniJson.obj(o["packages"]).orEmpty()) {
                if (!k.startsWith("node_modules/") || k.substringAfter("node_modules/").contains("node_modules/")) continue
                val n = k.removePrefix("node_modules/"); val ver = MiniJson.obj(v)?.get("version") as? String ?: continue
                if (out.containsKey("NPM:" + n.lowercase())) put(Lib(n, ver, Kind.NPM, p))
            }
        }
        for (p in sorted.filter { Regex("""(?i)\.(html?|js|mjs|ts|jsx|tsx)$""").containsMatchIn(it) }.take(200)) {
            val t = read(p)?.takeIf { it.length < CAP } ?: continue
            for (m in CDN.findAll(t)) if (validName(m.groupValues[1], Kind.NPM)) put(Lib(m.groupValues[1], m.groupValues[2], Kind.NPM, p))
            for (m in CDN_NOVER.findAll(t)) if (validName(m.groupValues[1], Kind.NPM)) put(Lib(m.groupValues[1], null, Kind.NPM, p))
        }
        for (p in sorted.filter { it.substringAfterLast('/') == "requirements.txt" }) {
            val t = read(p) ?: continue
            for (m in REQ.findAll(t)) put(Lib(m.groupValues[1], m.groupValues[2], Kind.PYPI, p))
            for (m in REQ_BARE.findAll(t)) put(Lib(m.groupValues[1], null, Kind.PYPI, p))
        }
        return out.values.toList()
    }

    /** The library [name] as the project uses it, or a guess at its kind (Python when the project has only Python files). */
    fun find(name: String, used: List<Lib>, pythonProject: Boolean): Lib {
        used.firstOrNull { it.name.equals(name, true) }?.let { return it }
        val at = Regex("""^((?:@[\w.-]+/)?[\w.-]+)@(\d[\w.+-]*)$""").find(name.trim())
        if (at != null) return Lib(at.groupValues[1], at.groupValues[2], if (pythonProject) Kind.PYPI else Kind.NPM, "asked")
        return Lib(name.trim(), null, if (pythonProject) Kind.PYPI else Kind.NPM, "asked")
    }

    /** Addresses to fetch, in order: for npm the package's package.json (to find its types) and README; for PyPI its JSON page. */
    fun packageJsonUrl(l: Lib): String = "https://cdn.jsdelivr.net/npm/${l.name}${l.version?.let { "@$it" } ?: ""}/package.json"
    fun readmeUrls(l: Lib): List<String> = listOf("README.md", "readme.md", "Readme.md").map { "https://cdn.jsdelivr.net/npm/${l.name}${l.version?.let { v -> "@$v" } ?: ""}/$it" }
    fun typesUrl(l: Lib, pkgJson: String): String? {
        val o = try { MiniJson.obj(MiniJson.parse(pkgJson)) } catch (e: Exception) { null } ?: return null
        val t = (o["types"] as? String) ?: (o["typings"] as? String) ?: return null
        val ver = (o["version"] as? String) ?: l.version
        return "https://cdn.jsdelivr.net/npm/${l.name}${ver?.let { "@$it" } ?: ""}/" + t.removePrefix("./")
    }
    /** Type files a type file re-exports or imports from (export * from './SupabaseClient'), as addresses, up to [max]. */
    fun typeRefs(typesUrl: String, text: String, max: Int = 8): List<String> {
        val dir = typesUrl.substringBeforeLast('/')
        return Regex("""(?:from|import)\s*['"](\.{1,2}/[^'"]+)['"]""").findAll(text).map { it.groupValues[1] }.distinct().mapNotNull { rel ->
            val parts = ArrayList(dir.split('/'))
            for (seg in rel.split('/')) when (seg) { "." -> {}; ".." -> if (parts.size > 6) parts.removeAt(parts.size - 1) else return@mapNotNull null; else -> parts.add(seg) }
            val path = parts.joinToString("/")
            when {
                path.endsWith(".d.ts") -> path
                path.endsWith(".js") || path.endsWith(".mjs") -> path.substringBeforeLast('.') + ".d.ts"
                else -> "$path.d.ts"
            }
        }.filter { it != typesUrl }.take(max).toList()
    }

    fun versionOf(pkgJson: String): String? = try { MiniJson.obj(MiniJson.parse(pkgJson))?.get("version") as? String } catch (e: Exception) { null }
    fun pypiUrl(l: Lib): String = "https://pypi.org/pypi/${l.name}${l.version?.let { "/$it" } ?: ""}/json"
    fun pypiText(json: String): Pair<String?, String> {
        val info = try { MiniJson.obj(MiniJson.obj(MiniJson.parse(json))?.get("info")) } catch (e: Exception) { null } ?: return null to ""
        return (info["version"] as? String) to ((info["summary"] as? String).orEmpty() + "\n\n" + (info["description"] as? String).orEmpty())
    }

    /** Markdown split at its headings; type definitions split at each top-level declaration. Each piece keeps its heading. */
    fun sections(text: String, types: Boolean): List<String> {
        val lines = text.take(CAP).lines()
        val start = if (types) Regex("""^(?:export\s+|declare\s+)+(?:default\s+)?(?:abstract\s+)?(?:class|interface|function|const|type|enum|namespace|let|var)\b|^(?:class|interface|function|type|enum)\s""")
            else Regex("""^#{1,4}\s""")
        val out = ArrayList<String>(); val cur = StringBuilder()
        for (l in lines) { if (start.containsMatchIn(l) && cur.isNotBlank()) { out.add(cur.toString()); cur.clear() }; cur.append(l).append('\n') }
        if (cur.isNotBlank()) out.add(cur.toString())
        return out
    }

    /** The sections that best match [topic] (all words, case-insensitive), in their original order, up to [max] characters. */
    fun pick(parts: List<String>, topic: String, max: Int = MAX): String {
        val words = topic.lowercase().split(Regex("""[^a-z0-9_$.]+""")).filter { it.length >= 2 }.distinct()
        if (words.isEmpty()) return parts.joinToString("").take(max)
        val scored = parts.mapIndexed { i, s ->
            val low = s.lowercase(); val head = low.lineSequence().firstOrNull().orEmpty()
            Triple(i, s, words.sumOf { w -> Regex(Regex.escape(w)).findAll(low).count().coerceAtMost(8) + (if (head.contains(w)) 6 else 0) })
        }.filter { it.third > 0 }.sortedByDescending { it.third }.let { s -> val top = s.firstOrNull()?.third ?: 0; s.filter { it.third * 4 >= top } }   // a passing mention (slerp for lerp) is not a match
        val keep = ArrayList<Triple<Int, String, Int>>(); var size = 0
        for (s in scored) { if (size + s.second.length > max) { if (keep.isEmpty()) { keep.add(Triple(s.first, s.second.take(max), s.third)); size = max }; continue }; keep.add(s); size += s.second.length }
        return keep.sortedBy { it.first }.joinToString("\n…\n") { it.second.trimEnd() }
    }

    /** The tool's answer: where the docs came from and which version, then the matching sections. */
    fun answer(l: Lib, version: String?, readme: String?, types: String?, topic: String): String {
        val ver = version ?: l.version
        val head = "Docs for ${l.name}${ver?.let { " $it" } ?: " (latest; the project does not pin a version)"}" +
            (if (l.from != "asked") ", the version ${l.from} uses" else "") + ". "
        if (readme.isNullOrBlank() && types.isNullOrBlank()) return head + "No README or type definitions were found for it."
        val t = types?.let { pick(sections(it, true), topic, MAX / 2) }.orEmpty()
        val r = readme?.let { pick(sections(it, false), topic, MAX - t.length.coerceAtMost(MAX / 2)) }.orEmpty()
        if (t.isBlank() && r.isBlank()) return head + "Nothing in its README" + (if (types != null) " or type definitions" else "") + " mentions \"$topic\". Try other words, such as the name of a class or function."
        return head + (if (topic.isBlank()) "" else "Sections about \"$topic\":") + (if (r.isNotBlank()) "\n\n--- README ---\n$r" else "") + (if (t.isNotBlank()) "\n\n--- Type definitions ---\n$t" else "")
    }

    /** Hosts the tool reads from: package names are all it sends, so it needs no permission card. */
    val HOSTS = setOf("cdn.jsdelivr.net", "pypi.org")

    /** System prompt line, when the project uses libraries. */
    fun note(libs: List<Lib>): String = if (libs.isEmpty()) "" else
        "\n\nThis project uses: " + libs.take(12).joinToString(", ") { it.name + (it.version?.let { v -> " $v" } ?: "") } +
            ". Before writing code that calls a library API you are not sure of for this version, read its docs with library_docs (name and a few words on what you need)."
}
