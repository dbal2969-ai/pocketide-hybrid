package dev.pocketide.app.core

/**
 * Project skills: small markdown files in `.pocketide/skills/` that tell the assistant how this project does a certain kind of task
 * (how to add a page, how releases work, the test style). Only the skills that match the request are put in front of the model.
 *
 * A skill file may start with a header:
 *   ---
 *   name: Add a page
 *   description: how new pages are added to this site
 *   keywords: page, route, menu
 *   ---
 *
 * Skills in the open Agent Skills format (https://agentskills.io) work too: a folder with a SKILL.md (front matter name and
 * description, then the instructions) and optional scripts/, references/ and assets/ folders. Such folders are read from
 * .pocketide/skills/ and from the folders other agents use (.agents/skills, .claude/skills, .github/skills), so a repository's
 * skills work here unchanged. Skills that match the request are put in front of the model in full; the rest are listed by name and
 * description, and the model opens a SKILL.md itself when one fits (progressive disclosure).
 */
object Skills {
    const val DIR = ".pocketide/skills"
    /** Where skill folders (NAME/SKILL.md) are looked for, in order; the first skill of a name wins. */
    val DIRS = listOf(DIR, ".agents/skills", ".claude/skills", ".github/skills")
    const val SKILL_FILE = "SKILL.md"
    const val CATALOG_BUDGET = 1_500
    private const val MAX_FILE = 12_000
    const val BUDGET = 4_000

    class Skill(val file: String, val name: String, val description: String, val keywords: Set<String>, val body: String, val problems: List<String> = emptyList())

    private val SPEC_NAME = Regex("""[a-z0-9]+(-[a-z0-9]+)*""")

    /**
     * What the open Agent Skills format asks for that a folder skill does not meet: a lowercase name of 1 to 64 characters
     * (letters, digits and single hyphens) that matches its folder, and a description of 1 to 1024 characters.
     * Skills with problems still load; the problems are shown in the Diagnostics log.
     */
    fun specProblems(folder: String?, name: String?, description: String): List<String> {
        val out = ArrayList<String>()
        val n = name ?: folder
        if (n == null) out.add("no name")
        else {
            if (n.length !in 1..64 || !SPEC_NAME.matches(n)) out.add("name \"$n\" must be 1-64 lowercase letters, digits and single hyphens")
            if (folder != null && name != null && name != folder) out.add("name \"$name\" does not match its folder \"$folder\"")
        }
        if (description.isBlank()) out.add("no description")
        else if (description.length > 1024) out.add("description is longer than 1024 characters")
        return out
    }

    private fun words(s: String): Set<String> =
        Regex("""[a-z0-9][a-z0-9+#.\-]{2,}""").findAll(s.lowercase()).map { it.value.trim('.', '-') }.filter { it.length >= 3 && it !in STOP }.toSet()

    private val STOP = setOf("the", "and", "for", "with", "that", "this", "from", "into", "when", "how", "are", "use", "using", "make", "add", "new", "can", "you", "your", "all", "not")

    /** Reads one skill file. A header is optional: the name then comes from the file name and the keywords from the text. */
    fun parse(file: String, text: String): Skill {
        var body = text.replace("\r", "")
        val meta = HashMap<String, String>()
        if (body.startsWith("---\n")) {
            val end = body.indexOf("\n---", 4)
            if (end > 0) {
                for (l in body.substring(4, end).lineSequence()) {
                    val i = l.indexOf(':')
                    if (i > 0) meta[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                }
                body = body.substring(end + 4).trimStart('\n')
            }
        }
        val inFolder = file.substringAfterLast('/') == SKILL_FILE
        val stem = (if (inFolder) file.substringBeforeLast('/').substringAfterLast('/') else file.substringAfterLast('/').removeSuffix(".md")).replace('-', ' ').replace('_', ' ')
        val name = meta["name"]?.ifBlank { null }?.let { if (inFolder) it.replace('-', ' ') else it } ?: stem
        val description = meta["description"].orEmpty()
        val keywords = (meta["keywords"].orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() } + words(name)).toSet()
        val problems = if (inFolder) specProblems(file.substringBeforeLast('/').substringAfterLast('/'), meta["name"]?.ifBlank { null }, description) else emptyList()
        return Skill(file, name, description, keywords, body.trim().take(MAX_FILE), problems)
    }

    /** How well a skill fits a request: keyword hits count most, then words of the description. 0 means it does not apply. */
    fun score(skill: Skill, request: String): Int {
        val w = words(request)
        val kw = skill.keywords.count { k -> k in w || (k.contains(' ') && request.lowercase().contains(k)) }
        val desc = words(skill.description).count { it in w }
        return if (kw > 0) kw * 3 + desc else if (desc >= 2) desc else 0
    }

    /** The best matching skills, at most [max]. */
    fun match(skills: List<Skill>, request: String, max: Int = 2): List<Skill> =
        skills.map { it to score(it, request) }.filter { it.second > 0 }.sortedByDescending { it.second }.take(max).map { it.first }

    /** Added to the system prompt for the matched skills. Empty when none match. */
    fun note(matched: List<Skill>): String {
        if (matched.isEmpty()) return ""
        val sb = StringBuilder("\n\nPROJECT SKILLS (the person's own notes for tasks like this one; follow them):")
        var left = BUDGET
        for (s in matched) {
            if (left <= 200) break
            val body = s.body.take(left - 100)
            sb.append("\n\n### ").append(s.name).append('\n')
            if (s.file.endsWith("/$SKILL_FILE")) sb.append("(from ").append(s.file).append("; scripts and references it mentions are in ").append(s.file.substringBeforeLast('/')).append("/)\n")
            sb.append(body)
            left -= body.length + s.name.length + 10
        }
        return sb.toString()
    }

    /** The skills that did not match, by name and description, so the model can open one (read_file on its file) when it fits. */
    fun catalog(skills: List<Skill>, matched: List<Skill>): String {
        val rest = skills.filter { s -> matched.none { it.file == s.file } && s.description.isNotBlank() }
        if (rest.isEmpty()) return ""
        val sb = StringBuilder("\n\nOTHER PROJECT SKILLS (read the file with read_file before using one; ignore the ones that do not fit):")
        for (s in rest) {
            val line = "\n- " + s.name + ": " + s.description.take(200) + " (" + s.file + ")"
            if (sb.length + line.length > CATALOG_BUDGET) { sb.append("\n- … and ").append(rest.size - rest.indexOf(s)).append(" more"); break }
            sb.append(line)
        }
        return sb.toString()
    }

    /** Keeps the first skill of each name (the folders in [DIRS] are read in order). */
    fun dedupe(skills: List<Skill>): List<Skill> = skills.distinctBy { it.name.lowercase() }

    /** The /skills answer: what is there, or how to add one. */
    fun listing(skills: List<Skill>): String =
        if (skills.isEmpty()) "No project skills yet. Add markdown files in $DIR/ (for example $DIR/add-a-page.md), or skill folders with a $SKILL_FILE in $DIR/, .agents/skills/ or .claude/skills/, and the assistant reads the ones that match each request."
        else "Project skills (" + skills.size + "):\n" + skills.joinToString("\n") { "• " + it.name + (if (it.description.isNotEmpty()) " — " + it.description else "") }
}
