package dev.pocketide.app.core

/**
 * The app_help tool (1.5.185): the assistant's knowledge of PocketIDE itself, so it can answer "where is…", "how do I…" and "why
 * does the app say…" without guessing. It searches, in order of weight, the settings, menus and buttons (AppMap, generated from
 * the screens' source), Help & FAQ (HelpContent), the slash commands, the workflows, the advisor modes and the hybrid cloud models.
 *
 * Words of the question that match nothing in the app are named in the result, so a question about a feature PocketIDE does not
 * have ("export an iPhone app") is answered "PocketIDE does not have that" instead of with invented steps. Pure logic.
 */
object AppHelp {
    /** One thing the search can find: [where] is the path to it, [title] its exact label, [body] the rest of its text. */
    class Doc(val where: String, val title: String, val body: String = "", val keywords: List<String> = emptyList(), val setting: String? = null) {
        // a symbol's label (⚙, 🎤) has no words: its path says what it is
        internal val strong: Set<String> = words(title + " " + keywords.joinToString(" ")).toSet().ifEmpty { words(where).toSet() }
        internal val weak: Set<String> = words(where + " " + body).toSet()
    }

    private const val HELP = "⋮ Menu › App › Help & FAQ"

    /** Every Doc, built once from the app's own data. */
    val docs: List<Doc> by lazy {
        val out = ArrayList<Doc>()
        for (i in AppMap.ITEMS) out.add(Doc(i.where, i.label, i.kind.about, emptyList(), i.setting))
        for (s in HelpContent.sections) {
            out.add(Doc("$HELP › ${s.title}", s.title, s.intro))
            for (e in s.entries) out.add(Doc("$HELP › ${s.title}", e.title,
                e.body + (if (e.steps.isNotEmpty()) " Steps: " + e.steps.mapIndexed { n, t -> "${n + 1}) $t" }.joinToString(" ") else "") +
                    (e.url?.let { " ($it)" } ?: ""), e.keywords))
        }
        for (c in Slash.ALL) out.add(Doc("AI tab: type it in the message box", c.usage, c.help, listOf("slash", "command", c.name)))
        val wf = "⋮ Menu › AI › Multi-model workflow and advisor"
        for (w in Workflow.ALL) out.add(Doc(wf, Workflow.title(w), Workflow.explain(w), listOf("workflow", "multi-model")))
        for (m in Advisor.ALL) out.add(Doc("$wf › ADVISOR", Advisor.title(m), Advisor.explain(m), listOf("advisor")))
        val tiers = "⋮ Menu › AI › Models (local AI) › Open hybrid AI › Model settings › Cloud model (used when Gemini Nano passes a request on)"
        for (c in CloudTier.Choice.values()) out.add(Doc(tiers, c.label, c.about, listOf("cloud", "hybrid", "tier", "gemini"), "hybrid.cloud_model"))
        out
    }

    /** What PocketIDE has, from its own menus: the answer to "what can you do" and the context when nothing else matches. */
    fun overview(): String = buildString {
        append("PocketIDE's parts (from its own screens): tabs ")
        append(AppMap.ITEMS.filter { it.kind == AppMap.Kind.TAB }.joinToString(", ") { it.label })
        append(". ⚙ opens Settings; ⋮ opens the Menu: ")
        append(AppMap.ITEMS.filter { it.where.startsWith("⋮ Menu › ") && it.where.count { c -> c == '›' } == 1 }
            .groupBy { it.where.removePrefix("⋮ Menu › ") }.entries.joinToString("; ") { (s, list) -> s + ": " + list.joinToString(", ") { it.label } })
        append(". Slash commands in the AI tab: ").append(Slash.ALL.joinToString(", ") { "/" + it.name }).append('.')
    }

    // words that say nothing about which feature is meant; they are neither scored nor reported as missing
    private val GENERIC = setOf("how", "do", "does", "did", "can", "could", "the", "a", "an", "to", "my", "in", "of", "on", "off", "for",
        "is", "it", "its", "where", "what", "which", "why", "when", "turn", "switch", "set", "enable", "disable", "change", "make", "use",
        "using", "find", "pocketide", "app", "setting", "settings", "option", "options", "feature", "button", "there", "way", "get",
        "see", "show", "want", "would", "like", "please", "you", "your", "me", "this", "that", "with", "from", "and", "or", "have",
        "has", "be", "are", "i", "im", "not", "keep", "so", "only", "into", "as", "at", "by", "all", "any", "go", "open", "tell", "need",
        "should", "will", "just", "now", "also", "some", "more", "than", "then", "up", "out", "about", "help", "ide", "phone", "page",
        "screen", "one", "same", "new", "add", "put", "there's", "isnt", "isn't", "doesnt", "doesn't", "don't", "dont", "cant", "can't",
        "keeps", "getting", "got", "say", "says", "said", "own", "it's", "anything", "something", "things", "thing", "give", "three",
        "two", "besides", "other", "much", "many", "lot", "lots", "instead", "every", "each", "again", "after", "before", "stop", "start", "they", "them", "we")

    // what people say → the app's own words
    private val SYNONYMS = mapOf(
        "dark" to listOf("light", "theme"), "night" to listOf("light", "theme"), "theme" to listOf("light"),
        "spend" to listOf("usage", "cost"), "spent" to listOf("usage", "cost"), "money" to listOf("usage", "cost"),
        "bill" to listOf("usage", "cost"), "price" to listOf("usage", "cost"), "expensive" to listOf("cost"),
        "offline" to listOf("private", "local", "nano"), "privacy" to listOf("private"), "private" to listOf("private"),
        "voice" to listOf("speak"), "talk" to listOf("speak"), "microphone" to listOf("speak"), "mic" to listOf("speak"), "dictate" to listOf("speak"),
        "revert" to listOf("undo", "checkpoint"), "rollback" to listOf("undo", "checkpoint"),
        "github" to listOf("git"), "commit" to listOf("git"), "push" to listOf("git"), "pull" to listOf("git"),
        "shell" to listOf("terminal"), "console" to listOf("terminal"),
        "quota" to listOf("429", "rate", "limit"), "ratelimit" to listOf("429", "rate", "limit"),
        "unauthorized" to listOf("401", "key"), "invalid" to listOf("401"),
        "blank" to listOf("blank", "preview"), "white" to listOf("blank", "preview"),
        "apikey" to listOf("key"), "token" to listOf("key", "token"), "keys" to listOf("key"),
        "confirm" to listOf("asking", "approve"), "permission" to listOf("asking", "allow"), "approval" to listOf("asking", "approve"),
        "autocomplete" to listOf("suggestions", "typing"), "completion" to listOf("suggestions"),
        "gemini" to listOf("gemini", "hybrid"), "nano" to listOf("nano", "hybrid"), "font" to listOf("font"),
        "wrap" to listOf("wrap"), "wrapping" to listOf("wrap"), "zip" to listOf("zip"), "download" to listOf("download", "save", "export"),
        "budget" to listOf("limit", "dollars", "tokens"), "plan" to listOf("plan"), "ask" to listOf("ask"),
    )

    internal fun stem(w: String): String = when {
        w.length > 5 && w.endsWith("ing") -> w.dropLast(3)
        w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
        w.length > 4 && w.endsWith("ed") -> w.dropLast(2)
        w.length > 4 && w.endsWith("es") && !w.endsWith("ses") -> w.dropLast(2)
        w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
        else -> w
    }

    internal fun words(text: String): List<String> =
        Regex("""[\p{L}\p{N}]+""").findAll(text.lowercase().replace("'", "")).map { stem(it.value) }.filter { it.length >= 2 || it.all(Char::isDigit) }.toList()

    /** The words of [query] that name something (not GENERIC), each with the app words it stands for. */
    internal fun terms(query: String): List<Pair<String, Set<String>>> =
        Regex("""[\p{L}\p{N}']+""").findAll(query.lowercase()).map { it.value.replace("'", "") }
            .filter { it !in GENERIC && it.replace("'", "") !in GENERIC && (it.length >= 2 || it.all(Char::isDigit)) }
            .distinct().map { w -> w to (setOf(stem(w)) + SYNONYMS[w].orEmpty().map { stem(it) }) }.toList()

    /** [score]: each word of the question that the doc names counts by how rare it is in the app; a word in its label counts thrice. */
    class Hit(val doc: Doc, val score: Double)

    private val df: Map<String, Int> by lazy {
        val m = HashMap<String, Int>()
        for (d in docs) for (w in d.strong + d.weak) m[w] = (m[w] ?: 0) + 1
        m
    }

    private fun rarity(alts: Set<String>): Double = Math.log(1.0 + docs.size.toDouble() / (1 + (alts.maxOfOrNull { df[it] ?: 0 } ?: 0)))

    /** The docs that match [query], best first, and the query's words that match nothing in the app. */
    fun search(query: String): Pair<List<Hit>, List<String>> {
        val t = terms(query)
        if (t.isEmpty()) return emptyList<Hit>() to emptyList()
        val weights = t.map { (_, alts) -> rarity(alts) }
        val hits = docs.map { d ->
            Hit(d, t.indices.sumOf { k -> val alts = t[k].second; when { alts.any { it in d.strong } -> 3 * weights[k]; alts.any { it in d.weak } -> weights[k]; else -> 0.0 } })
        }.filter { it.score >= MIN_SCORE }.sortedByDescending { it.score }
        val missing = t.filter { (_, alts) -> alts.none { (df[it] ?: 0) > 0 } }.map { it.first }
        return hits to missing
    }

    /** A doc must name at least one rarer word of the question, or several common ones (a weak hit of a common word is about 2). */
    private const val MIN_SCORE = 3.0

    const val FOOTER = "Answer from these results only, and quote the path and label exactly as written here. " +
        "To change one of these settings for the person, call app_settings with action \"propose\": they see a card, and nothing " +
        "changes until they tap Apply. API keys and tokens are never changed that way: tell them where to paste them."

    /**
     * The tool's reply for [query]: up to [max] matches in about [room] characters, the words that match nothing, and how to answer.
     * [value] gives a setting's current value ("on", "Balanced"), or null.
     */
    fun answer(query: String, max: Int = 6, room: Int = 2400, value: (String) -> String? = { null }): String {
        val q = query.trim()
        if (q.isEmpty()) return "Give a few words about what the person asked, for example \"word wrap\" or \"429 error\"."
        val (hits, missing) = search(q)
        val wantsOverview = Regex("""(?i)\b(what can|capabilit|features?\b|overview|able to|what does pocketide|what else)""").containsMatchIn(q)
        val sb = StringBuilder()
        if (wantsOverview) sb.append(overview()).append('\n')
        val top = hits.take(max)
        if (top.isEmpty() && !wantsOverview) {
            sb.append("Nothing in PocketIDE's settings, menus, buttons or Help matches \"").append(q.take(80)).append("\". ")
            sb.append("Tell the person PocketIDE does not seem to have that; do not invent buttons, menus or steps.")
            return sb.toString()
        }
        if (top.isNotEmpty()) sb.append("Found in PocketIDE:\n")
        for (h in top) {
            val d = h.doc
            val now = d.setting?.let(value)?.let { " (now: $it)" }.orEmpty()
            val body = d.body.replace(Regex("""\s+"""), " ").let { if (it.length > BODY) it.take(BODY).trimEnd() + "…" else it }
            val line = "- " + d.where + " › \"" + d.title + "\"" + now + (if (body.isNotBlank() && !isKindWord(body)) ": " + body else "") + "\n"
            if (sb.length + line.length > room) { if (sb.length + 80 < room) sb.append(line.take(room - sb.length - 2).trimEnd()).append("…\n"); break }
            sb.append(line)
        }
        if (missing.isNotEmpty())
            sb.append("Not found anywhere in PocketIDE: ").append(missing.joinToString(", ") { "\"$it\"" })
                .append(". If the question depends on that, say PocketIDE does not have it.\n")
        sb.append(FOOTER)
        return sb.toString()
    }

    /** Characters of a Help entry shown per match, so four matches fit a small model's room. */
    private const val BODY = 420

    private fun isKindWord(s: String) = AppMap.Kind.values().any { it.about == s }

    private val ABOUT_APP = Regex("""(?i)\b(pocketide|this app|the app|in the app|setting|settings|menu|button|tab|api key|key for|rate limit|quota|""" +
        """4(01|03|04|29)\b|preview (tab|is blank)|word wrap|light theme|dark (mode|theme)|agent mode|ask mode|plan mode|hybrid|gemini nano|""" +
        """private mode|cloud model|usage|cost|slash command|checkpoint|undo|terminal|git(hub)?\b|provider|model)\b""")
    private val ABOUT_PROJECT = Regex("""(?i)\b(my|the|this) (web ?app|page|site|website|game|project|code|function|file|script)\b|\.(js|html|css|json|py|ts|kt)\b|\bin (the )?(index|app|game|main)\b""")

    /** True when [request] is about PocketIDE itself rather than the person's project (small models get pointed at app_help). */
    fun aboutApp(request: String): Boolean = ABOUT_APP.containsMatchIn(request) && !ABOUT_PROJECT.containsMatchIn(request)

    private val CHANGE_SETTING = Regex("""(?i)^\s*(please\s+)?(can you\s+|could you\s+)?((turn|switch|set|change)\b.*\b(on|off|to)\b|(enable|disable|untick|tick)\b)""")

    /**
     * The setting [request] asks to change ("Turn on word wrap in the editor" → editorWrap), or null. Only requests that start with a
     * change verb, are not about the project, and match one tick box or choice by its label.
     */
    fun settingRequest(request: String): AppMap.Item? {
        if (!CHANGE_SETTING.containsMatchIn(request) || ABOUT_PROJECT.containsMatchIn(request)) return null
        val (hits, _) = search(request)
        val best = hits.firstOrNull { it.doc.setting != null } ?: return null
        if (best.score < 6 || hits.first().score > best.score) return null
        return AppMap.bySetting(best.doc.setting!!)
    }
}
