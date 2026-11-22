package dev.pocketide.app.core

/**
 * Requirements first, check at the end. For a big job the assistant first lists what it will build, point by point;
 * the person approves or edits the list; the work then runs against that list, and a last turn compares the result
 * with the original request and names anything left out or changed.
 */
object Requirements {
    const val MAX = 14
    const val REQUEST_START = "Before building anything, list the requirements"

    /** Asked instead of the request: only the list, no files, no tool calls. */
    fun request(task: String, scope: String = ""): String =
        "$REQUEST_START for this request. " + NO_TOOLS +
            "Write down exactly what you will build so the person can check it matches what they asked for. " +
            "Cover every feature, behaviour, look, control and rule the request mentions, one point each, in the request's own words where you can. " +
            "Do not describe steps or files; describe what the finished result does and has. Do not add things that were not asked for.\n" +
            "Plans, to-do lists and notes already in the project (PLAN.md, TODO.md and the like) are background, not requests: do not turn their " +
            "open items into points unless the request asks for them. When the request names a project or folder to take things from, the points " +
            "say what to take from it, not other work.\n" +
            "Reply in exactly this format:\n" +
            "REQUIREMENTS: <one-line name of the thing>\n1. <first requirement>\n2. <next requirement>\n...\n" +
            "Use 3 to $MAX points. If part of the request is unclear, add a last line starting with \"Assumed:\" saying what you will assume.\n" +
            (if (scope.isBlank()) "" else "One point must say that every file is created inside the folder \"${scope.trim('/')}\" and nothing outside it is changed.\n") +
            "Keep every name, game, product or style the request mentions (for example a named game to imitate) in the points. Never replace it with something else.\n\n" +
            "Request: $task"

    const val NO_TOOLS = "Do not output edit blocks, file fences, commands or tool calls yet.\n"
    const val LOOK_FIRST = "First look, read-only: open the project files the request touches, look at any reference pictures in the project, " +
        "read the links the person gave and search the web when the request names resources or asks to find them. In this phase you cannot change " +
        "files or run commands: the app refuses them. Then reply with no tool calls.\n"
    const val SOURCES = "\nEnd each point with where it comes from, in brackets: [file: <path>], [picture: <path>], [link: <address>], [request] " +
        "for the person's own words, or [assumed] when nothing you saw supports it. If a link could not be read (a search results page, a page " +
        "that needs a login), say so on the \"Assumed:\" line and ask for a screenshot instead of guessing what it shows."

    /** The requirements request for a tool-calling model: look first (read-only), then the list with a source on each point. */
    fun researchRequest(task: String, scope: String = ""): String =
        request(task, scope).replace(NO_TOOLS, LOOK_FIRST).replace("\n\nRequest: ", SOURCES + "\n\nRequest: ")

    private val HEAD = Regex("""(?im)^(\s*(?:#+\s*)?\**\s*)REQUIREMENTS(?=\s*:|\s*\*|\s*$)""")

    /** The list in a reply, or null when it has fewer than two points. */
    fun parse(reply: String): Plan? {
        val p = Plans.parse(HEAD.replace(reply, "$1PLAN")) ?: return null
        val steps = p.steps.take(MAX).toMutableList()
        return Plan(p.title.ifEmpty { "Requirements" }, steps)
    }

    private val PLAN_DOC = Regex("""(?i)\[file:\s*([^\]]*?/)?((plan|todo|to-do|roadmap|notes|backlog|handoff[^\]/]*|changelog)(\.(md|txt))?)\s*\]""")

    /**
     * Points that come only from a plan or notes file already in the project, not from the request (1.5.167): a request to bring quests
     * over from an imported server got a Part 1 that also wired equipment, prayers and magic, all copied from an old PLAN.md. A point
     * that also cites [request], or whose file the request names (or a request about "the plan"), stays.
     */
    fun offRequest(points: List<String>, task: String): List<Int> {
        val low = task.lowercase()
        if (Regex("""\b(the|my|our|this) plan\b|\btodo\b|\broadmap\b|\bbacklog\b""").containsMatchIn(low)) return emptyList()
        return points.indices.filter { i ->
            val pt = points[i]
            val m = PLAN_DOC.find(pt) ?: return@filter false
            if (pt.contains("[request]", ignoreCase = true)) return@filter false
            val name = m.groupValues[3].lowercase()
            !low.contains(name)
        }
    }

    /** Points typed in the Edit box, one per line. */
    fun fromEdited(title: String, text: String): Plan? {
        val p = Plans.fromEdited(title, text) ?: return null
        return Plan(title, p.steps.take(MAX).toMutableList())
    }

    fun list(reqs: List<String>): String = reqs.mapIndexed { i, r -> "${i + 1}. $r" }.joinToString("\n")

    /** The message that runs the job once the list is approved. */
    fun work(task: String, reqs: List<String>, scope: String = ""): String =
        task + (if (scope.isBlank()) "" else "\n\nWork only inside the folder \"${scope.trim('/')}\".") + "\n\nThe person approved this list of requirements. Build all of it, and nothing that is not on it:\n" + list(reqs) +
            "\n\nDo the work now: write the files directly. Do not write the list or a plan again. Finish every point before you stop."

    /** The message that checks the finished work against the request. Report only; no changes. */
    fun check(task: String, reqs: List<String>, scope: String = "", parts: Boolean = false, found: List<String> = emptyList()): String =
        "The work is finished. Check it against what the person asked for. Read the project files that matter; do not change any file.\n\n" +
            "Original request: $task\n\n" +
            (if (reqs.isEmpty()) "" else "Approved requirements:\n" + list(reqs) + "\n\n") +
            (if (found.isEmpty()) "" else "The app already found these problems in the files. They are real: list each one under \"Left out\".\n" + found.joinToString("\n") { "- $it" } + "\n\n") +
            (if (scope.isBlank()) "" else "Folder rule: everything belongs inside \"${scope.trim('/')}\". Also list any file you find outside it that was created for this request.\n\n") +
            "Reply in exactly this format:\n" +
            "Matches: <the points that are done, in a few words each>\n" +
            "Left out: <each point or part of the original request that is missing, including anything the person named (a game, product or style) that was swapped for something else, or \"nothing\">\n" +
            "Changed: <each thing built differently from what was asked, or \"nothing\">\n" +
            (if (parts) "Doesn't fit: <each place where one part uses a file, function or name from another part that does not exist or is named differently, or \"nothing\">\n" else "") +
            (if (reqs.isEmpty()) "" else PartsContract.VERDICT_FORMAT + "\n") +
            "Only list what you can see in the files. Do not claim something works unless you saw the code for it."

    private val BUILD = Regex("""\b(create|build|make|write|implement|design|develop|generate|code|program|add|fix)\b""", RegexOption.IGNORE_CASE)
    private val RESEARCH = Regex("""\b(find|research|look\s*up|search\s+for|compare|explain|summari[sz]e|recommend|suggest|what\s+(?:is|are)|which|list\s+(?:the|some|all)|tell\s+me|show\s+me\s+(?:resources|links|examples))\b""", RegexOption.IGNORE_CASE)

    /** A request to look something up or explain it, not to change the project. */
    fun isResearch(text: String): Boolean = RESEARCH.containsMatchIn(text) && !BUILD.containsMatchIn(text)

    /** Sent for a picture with no words, so the model looks at it instead of carrying on with earlier work. */
    const val IMAGE_ONLY = "Look at the attached image: what does it show, and what should I do about it? (No words were sent with it, so do not assume I want earlier work continued.)"

    private val RESEARCH_CUE = Regex("""\b(research|deep[- ]?dive|dig (?:into|deeper)|find (?:code|resources|examples|libraries|repos|projects)|on the (?:internet|web)|look into|look up|investigate|analy[sz]e|review|explore|study|find out|search for|see (?:what|if|whether|how|which)|evaluate|assess|survey|compare|explain|summari[sz]e|what (?:is|are|can|could|would)|which|how (?:do|does|can|could|would)|list (?:the|all|some)|tell me|why)\b""", RegexOption.IGNORE_CASE)
    private val CHANGE_VERB = Regex("""\b(create|build|make|write|implement|develop|generate|add|fix|change|update|edit|refactor|rewrite|remove|delete|rename|port|convert|install|replace|apply|restore|move|copy)\b""", RegexOption.IGNORE_CASE)
    // "what we can build", "you could add": a possibility being asked about, not an order
    private val MODAL = Regex("""\b(?:we|you|i|they|it|one)\s+(?:can|could|might|would|should|may)\s+(?:\w+\s+)?""", RegexOption.IGNORE_CASE)

    /**
     * True when the request asks to find things out (research, a deep dive, a review) and gives no order to change anything.
     * Unlike [isResearch], a build word inside a question ("see what code we can use", "what we can build") does not count.
     * Used to keep the app from pushing the assistant to edit files while it researches.
     */
    fun asksForResearch(text: String): Boolean {
        if (!RESEARCH_CUE.containsMatchIn(text)) return false
        val orders = MODAL.replace(text.replace(Regex("""https?://\S+"""), " "), " ")
        return !CHANGE_VERB.containsMatchIn(orders)
    }

    private val FOLLOW = Regex("""^\s*(?:ok(?:ay)?|yes|yeah|sure|go|go\s+ahead|do\s+it|continue|proceed|complete(?:\s+the\s+task)?|finish(?:\s+it)?|try\s+again|again)\b[.!\s]*$""", RegexOption.IGNORE_CASE)

    /** Short replies like "continue" or "complete the task": they only make sense after an earlier request. */
    fun isFollowUp(text: String): Boolean = FOLLOW.matches(text.trim())

    /** The request the list is built from: a short follow-up is joined to the earlier big request so nothing it named is lost. */
    fun task(previous: String?, current: String): String =
        if (!previous.isNullOrBlank() && previous.trim() != current.trim() && current.trim().length < 160)
            previous.trim() + "\n\nThe person then added: " + current.trim()
        else current

    /** Whether a request should get the requirements step. */
    fun wanted(enabled: Boolean, local: Boolean, hasAttachments: Boolean, big: Boolean, current: String = "", task: String = current): Boolean =
        enabled && !local && !hasAttachments && big && !isResearch(task) && !isFollowUp(current)

    /** True when a reply to [check] reports something left out or changed. */
    fun hasGaps(reply: String): Boolean {
        fun gap(label: String): Boolean {
            val m = Regex("""(?im)^\W*$label\s*:\s*(.*)$""").find(reply) ?: return false
            val v = m.groupValues[1].trim().trim('*', '"', '.', ' ').lowercase()
            return v.isNotEmpty() && v != "nothing" && v != "none" && v != "n/a" && v != "-"
        }
        return gap("Left out") || gap("Changed") || gap("Doesn't fit")
    }
}
