package dev.pocketide.app.core

// v0.4 W2: plan → approve → execute. The model first writes a numbered plan; each step then runs as its own turn.

enum class StepStatus(val mark: String) { TODO("☐"), RUNNING("⏳"), DONE("✓"), FAILED("✗"), SKIPPED("–") }

class Plan(val title: String, val steps: MutableList<String>) {
    val status: MutableList<StepStatus> = MutableList(steps.size) { StepStatus.TODO }

    fun checklist(): String = steps.indices.joinToString("\n") { "${status[it].mark} ${it + 1}. ${steps[it]}" }
    fun nextTodo(): Int = status.indexOfFirst { it == StepStatus.TODO }
    val finished: Boolean get() = status.none { it == StepStatus.TODO || it == StepStatus.RUNNING }
}

object Plans {
    const val MAX_STEPS = 10

    const val REQUEST_START = "Before changing anything, write a short plan"

    /** Sent when the person skips the plan: the request again, with an explicit "no plan" so small models do not repeat one. */
    fun noPlan(task: String): String = task + "\n\nDo the work now: write the files directly. Do not write a plan or a list of steps first."

    /** Sent instead of the request when a plan is wanted first. */
    fun request(task: String): String =
        "$REQUEST_START for this request. Do not output edit blocks, file fences, commands or tool calls yet.\n" +
            "Reply in exactly this format:\n" +
            "PLAN: <one-line title>\n1. <first step>\n2. <next step>\n...\n" +
            "Use 2 to 8 steps. Each step is one concrete change that can be done and checked on its own " +
            "(for example \"Create main.go with a TCP server that accepts players\"). After the list you may add one or two sentences of notes.\n\n" +
            "Request: $task"

    /**
     * Plan mode with a tool-calling model: look around first (the app refuses every write and command in this phase), then
     * reply with the same plan format as [request]. Like plan mode in Claude Code or Cline, the plan is based on the real code.
     */
    fun researchRequest(task: String): String =
        "$REQUEST_START for this request. First look at the project with your read-only tools (list_files, file_outline, find_symbol, search, read_file) " +
            "until you know which files the work touches and how they fit together; read only what you need. In this phase you cannot change files " +
            "or run commands: the app refuses them. Then reply with no tool calls, in exactly this format:\n" +
            "PLAN: <one-line title>\n1. <first step>\n2. <next step>\n...\n" +
            "Use 2 to 8 steps. Each step is one concrete change that can be done and checked on its own, and names the real files it changes " +
            "(for example \"In src/game.js, add a Player class with move() and draw()\"). After the list you may add one or two sentences on what you found " +
            "and any risk.\n\nRequest: $task"

    private val STEP = Regex("""^\s*(?:[-*]\s*)?(?:step\s*)?(\d{1,2})[.):]\s+(.+?)\s*$""", RegexOption.IGNORE_CASE)
    private val BULLET = Regex("""^\s*[-*•]\s+(.+?)\s*$""")
    private val HEAD = Regex("""^\s*(?:#+\s*)?\**\s*PLAN\s*:?\**\s*:?\s*(.*)$""", RegexOption.IGNORE_CASE)

    /** The plan in a reply, or null when it has fewer than two numbered steps. */
    fun parse(reply: String): Plan? {
        var title = ""
        val steps = ArrayList<String>()
        var expected = 1
        var inFence = false
        val bullets = ArrayList<String>()
        for (raw in reply.replace("\r", "").split("\n")) {
            if (raw.trimStart().startsWith("```")) { inFence = !inFence; continue }
            if (inFence) continue  // code inside a fence is never a plan step
            if (title.isEmpty() && steps.isEmpty()) {
                val h = HEAD.matchEntire(raw)
                if (h != null) { title = h.groupValues[1].trim().trim('*').trim(); continue }
            }
            val b = BULLET.matchEntire(raw)
            if (b != null && bullets.size < MAX_STEPS) bullets.add(b.groupValues[1].replace(Regex("""^\*\*(.+?)\*\*:?\s*"""), "$1: ").trim().trimEnd(':'))
            val m = STEP.matchEntire(raw) ?: continue
            val n = m.groupValues[1].toInt()
            if (n != expected) { if (steps.isNotEmpty() && n == 1) break else continue }
            val text = m.groupValues[2].replace(Regex("""^\*\*(.+?)\*\*:?\s*"""), "$1: ").trim().trimEnd(':')
            if (text.isEmpty()) continue
            steps.add(text)
            expected++
            if (steps.size >= MAX_STEPS) break
        }
        // Small models often use plain bullets instead of numbers: accept those when there is no numbered list.
        if (steps.size < 2 && bullets.size >= 2) return Plan(title.ifEmpty { "Plan" }, bullets.filter { it.isNotEmpty() }.toMutableList())
        if (steps.size < 2) return null
        return Plan(title.ifEmpty { "Plan" }, steps)
    }

    /** Steps typed by the user in the Edit box, one per line (numbers optional). */
    fun fromEdited(title: String, text: String): Plan? {
        val steps = text.replace("\r", "").split("\n").map { it.trim().replace(Regex("""^(?:\d{1,2}[.):]|[-*])\s*"""), "").trim() }
            .filter { it.isNotEmpty() }.take(MAX_STEPS)
        return if (steps.isEmpty()) null else Plan(title, steps.toMutableList())
    }

    /** The hidden message that runs one step. */
    fun stepPrompt(task: String, plan: Plan, index: Int): String {
        val sb = StringBuilder()
        sb.append("We are carrying out an approved plan for this request: ").append(task).append("\n\n")
        sb.append("Plan: ").append(plan.title).append('\n').append(plan.checklist()).append("\n\n")
        sb.append("Now do step ").append(index + 1).append(" only: ").append(plan.steps[index]).append('\n')
        sb.append("Make the changes this step needs, and nothing from later steps. ")
        sb.append("If the step cannot be done, say why in one sentence. End with one line that starts with \"Step done:\" summarising what changed.")
        return sb.toString()
    }
}
