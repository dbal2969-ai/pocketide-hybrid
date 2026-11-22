package dev.pocketide.app.core

/** Decides whether a request needs the strong (slower, dearer) model or the cheap one. Rules only, instant, no model call. */
object Router {
    enum class Category { QUESTION, EDIT, BUILD, DEBUG, DOCUMENT }

    class Route(val category: Category, val strong: Boolean, val reason: String)

    private val BUILD = Regex("""\b(build|create|make|implement|develop|scaffold|generate|set up|setup|migrate|port|convert|integrate|refactor|rewrite|redesign|architect)\b.{0,60}\b(app|application|game|website|site|server|api|backend|frontend|project|feature|system|module|service|library|tool|pipeline|dashboard|page|cli|bot|plugin)\b""", RegexOption.IGNORE_CASE)
    private val BIG = Regex("""\b(refactor|rewrite|migrate|redesign|architecture|across (the )?(project|files|codebase)|all (the )?files|entire|whole (project|codebase|app)|from scratch|end[- ]to[- ]end|step[- ]by[- ]step|multi[- ]?file)\b""", RegexOption.IGNORE_CASE)
    private val DEBUG = Regex("""(\btraceback\b|\bexception\b|\bstack ?trace\b|\berror:|\bcrash(es|ed)?\b|\bnot working\b|\bdoesn'?t work\b|\bfails?\b|\bfailing\b|\bbug\b|\bbroken\b|\bwhy (does|is|isn'?t|doesn'?t)\b.*\b(not|error|fail))""", RegexOption.IGNORE_CASE)
    private val EDIT = Regex("""\b(change|edit|update|replace|rename|add|remove|delete|fix|insert|move|swap|tweak|adjust|set|make)\b""", RegexOption.IGNORE_CASE)
    private val SMALL_EDIT = Regex("""\b(typo|rename|comment|spelling|colou?r|font|text|title|label|heading|margin|padding|one line|small change|quick)\b""", RegexOption.IGNORE_CASE)
    private val QUESTION = Regex("""^\s*(what|why|how|when|where|which|who|can you explain|explain|describe|summari[sz]e|tell me|is there|are there|does|do you|should|could you explain|difference)\b""", RegexOption.IGNORE_CASE)
    private val CONTINUE = Regex("""^\s*(finish|continue|keep going|carry on|go on|proceed|complete|resume|next|then|also|now|do it|try again|retry|go ahead)\b""", RegexOption.IGNORE_CASE)
    private val FILES = Regex("""[\w\-./]+\.(kt|java|py|js|ts|tsx|jsx|html|css|json|go|rs|c|cpp|h|rb|php|sh|md|xml|yml|yaml|toml|sql)\b""")

    fun route(text: String, docAttached: Boolean = false, agent: Boolean = true): Route {
        val t = text.trim()
        val files = FILES.findAll(t).map { it.value }.toSet().size
        val long = t.length > 600
        if (docAttached || ModelSize.isFileEdit(t) && !QUESTION.containsMatchIn(t)) return Route(Category.DOCUMENT, strong = true, reason = "document edit")
        if (BUILD.containsMatchIn(t) || ModelSize.isBigRequest(t)) return Route(Category.BUILD, true, "building something larger")
        if (BIG.containsMatchIn(t) || files >= 3) return Route(Category.EDIT, true, if (files >= 3) "several files" else "a wide change")
        if (DEBUG.containsMatchIn(t) && (long || t.contains("\n") || files >= 1)) return Route(Category.DEBUG, true, "debugging with details")
        if (CONTINUE.containsMatchIn(t) && !t.endsWith("?") && agent) return Route(Category.EDIT, long || t.length > 12, "carrying on the work")
        if (QUESTION.containsMatchIn(t) || t.endsWith("?")) {
            return if (DEBUG.containsMatchIn(t) && long) Route(Category.DEBUG, true, "a long problem report") else Route(Category.QUESTION, false, "a question")
        }
        if (EDIT.containsMatchIn(t)) {
            if (SMALL_EDIT.containsMatchIn(t) && !long) return Route(Category.EDIT, false, "a small edit")
            return Route(Category.EDIT, agent && (long || files >= 2), if (agent && (long || files >= 2)) "an edit that needs care" else "a short edit")
        }
        return Route(Category.QUESTION, long, if (long) "a long message" else "a short message")
    }

    /** Which saved profile to use: the strong one or the cheap one. Null means leave the current model alone. */
    fun pick(route: Route, cheap: String, strong: String): String? {
        val c = cheap.trim(); val s = strong.trim()
        if (c.isEmpty() && s.isEmpty()) return null
        return if (route.strong) s.ifEmpty { null } else c.ifEmpty { null }
    }
}
