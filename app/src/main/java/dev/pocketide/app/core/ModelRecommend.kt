package dev.pocketide.app.core

/**
 * Recommends a model for the task at hand from what the providers said about their models (ModelFacts) and what each
 * model is designed for (ModelPurpose). Only providers the person can use (a key is saved, or none is needed) are
 * considered. The reasons shown are the providers' own facts, so the advice can be checked.
 */
object ModelRecommend {
    enum class Task(val label: String) {
        BUILD("building or changing an app over several steps"),
        EDIT("a small change"),
        QUESTION("a question"),
        IMAGES("a task with pictures"),
        LONG("work on many or long files")
    }

    data class Rec(val facts: ModelFacts.Facts, val score: Int, val why: String)

    private val BUILD = Regex("""(?i)\b(build|create|make|implement|develop|write|add|clone|game|app|website|feature|refactor|port|convert)\b""")
    private val QUESTION = Regex("""(?i)^\s*(what|why|how|explain|is|are|can|does|do|which|where|when)\b|\?\s*$""")
    private val GOOD_WORDS = Regex("""(?i)(agentic|coding|code generation|software engineering|tool use|function calling|tool calling|long[- ]context|instruction following)""")

    fun task(text: String, images: Boolean, projectFiles: Int): Task = when {
        images -> Task.IMAGES
        projectFiles > 300 || text.length > 4000 -> Task.LONG
        QUESTION.containsMatchIn(text.trim()) && !BUILD.containsMatchIn(text) -> Task.QUESTION
        BUILD.containsMatchIn(text) && (text.length > 160 || ModelSize.isBigRequest(text)) -> Task.BUILD
        else -> Task.EDIT
    }

    /** A score for [f] on [task]; null when the model cannot do it at all (no image input for a picture task). */
    fun score(f: ModelFacts.Facts, task: Task): Int? {
        val group = ModelPurpose.group(f.id)
        if (group == ModelPurpose.Group.NOT_CHAT || f.unavailable != null) return null
        if (ModelTrack.excluded(f.provider, f.id)) return null   // dismissed, or failed twice here without finishing (1.5.128)
        if (task == Task.IMAGES && f.image == false) return null
        val size = ModelPurpose.active(f.id) ?: ModelSize.billions(f.id)
        val small = group == ModelPurpose.Group.FAST || (size != null && size <= 9.0)
        var s = 0
        when (task) {
            Task.BUILD, Task.EDIT, Task.LONG -> {
                // no native tool calls: the text tool format still works, less reliably (1.5.128: was -6)
                s += when (f.tools) { true -> 6; false -> -3; null -> 0 }
                if (group == ModelPurpose.Group.CODING) s += 4
                if (group == ModelPurpose.Group.REASONING) s += 2
                if (small) s -= if (task == Task.EDIT) 2 else 5
                f.context?.let { s += if (it >= 120_000) 2 else if (it < 16_000) -3 else 0 }
                f.maxOutput?.let { s += if (it >= 16_000) 2 else if (it < 4_000) -2 else 0 }
                if (task == Task.LONG) f.context?.let { if (it >= 200_000) s += 2 }
            }
            Task.QUESTION -> {
                if (group == ModelPurpose.Group.REASONING) s += 2
                if (small) s += 1
            }
            Task.IMAGES -> {
                s += if (f.image == true || group == ModelPurpose.Group.VISION) 6 else -2
                if (f.tools == true) s += 2
            }
        }
        if (GOOD_WORDS.containsMatchIn(f.description)) s += 2
        if (f.free == true) s += 1
        // what PocketIDE measured and what happened on this phone, not only what the provider says (1.5.128)
        if (task != Task.QUESTION) s += ModelTrack.adjust(f.provider, f.id)
        return s
    }

    /** The best models for [task] among [facts] from providers in [usable], best first, at most [n]. */
    fun best(task: Task, facts: List<ModelFacts.Facts>, usable: Set<String>, n: Int = 3): List<Rec> =
        facts.filter { it.provider in usable }
            .mapNotNull { f -> score(f, task)?.let { Rec(f, it, listOf(ModelTrack.evidence(f.provider, f.id), ModelFacts.summary(f)).filter { s -> s.isNotBlank() }.joinToString("; ")) } }
            .sortedWith(compareByDescending<Rec> { it.score }.thenBy { it.facts.id })
            .distinctBy { it.facts.key }
            .take(n)

    /**
     * Advice when a clearly better model is available for this task, or null. "Clearly" means at least [margin] points
     * better than the current model (an unknown current model counts as 0).
     */
    fun advice(task: Task, currentProvider: String, currentModel: String, facts: List<ModelFacts.Facts>, usable: Set<String>, margin: Int = 5,
               now: Long = System.currentTimeMillis()): Rec? {
        val cur = facts.firstOrNull { it.provider == currentProvider && it.id == currentModel }
            ?: ModelFacts.Facts(currentProvider, currentModel)
        val curScore = score(cur, task) ?: -100
        // 1.5.128: a model nothing proves (no benchmark, no finished work here) is never put above one that is proven, and a
        // model is suggested at most once a week (before, after every restart)
        val curProven = ModelTrack.proven(cur.provider, cur.id)
        val top = facts.filter { it.provider in usable }
            .filter { !ModelTrack.recentlySuggested(it.provider, it.id, now) && (!curProven || ModelTrack.proven(it.provider, it.id)) }
            .let { best(task, it, usable, 1).firstOrNull() } ?: return null
        if (top.facts.key == cur.key || top.score < curScore + margin) return null
        return top
    }
}
