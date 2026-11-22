package dev.pocketide.app.core

/**
 * An advisor for every model, after Anthropic's advisor tool but done in the app so it works with any provider:
 * the model doing the work (the executor) can ask a stronger model for guidance, and in Auto mode the app asks at the
 * two moments that matter most, before the first change and before declaring done.
 */
object Advisor {
    const val OFF = "off"
    const val ASK = "ask"
    const val AUTO = "auto"
    val ALL = listOf(OFF, ASK, AUTO)

    fun parse(s: String): String = if (s in ALL) s else OFF

    fun title(m: String): String = when (m) { ASK -> "Ask when needed"; AUTO -> "Automatic checkpoints"; else -> "Off" }

    fun explain(m: String): String = when (m) {
        ASK -> "The assistant gets an advisor tool and decides when to use it. Some models rarely ask."
        AUTO -> "The app asks the advisor for a plan before the first change and for a review before the assistant says it is done. The assistant can also ask. Most reliable; costs two or three advisor calls per job."
        else -> "No advisor."
    }

    /** A chosen advisor and its limits for one request. */
    class Plan(val mode: String, val slot: Workflow.Slot, val maxCalls: Int, val words: Int = 150)

    enum class Purpose { HELP, START, FINISH }

    val CALL_CHOICES = listOf(1, 2, 3, 5, 8)

    const val TOOL_DESC = "Ask a stronger reviewer model for guidance. It takes no parameters: it sees the whole conversation so far, including the task, your tool calls and their results. " +
        "Use it after you have looked around and before you start changing things, when you are stuck or about to change approach, and once more before you give your final answer."

    const val SYSTEM = "You are an advisor: a senior engineer helping another AI assistant that is doing a coding task with tools in a phone IDE. " +
        "You cannot use tools or open files; you only see the transcript. Be direct and specific: name files, functions and the order of steps. " +
        "Do not write whole programs. If something in the transcript looks wrong or missing, say exactly what. If you are unsure, say what to check. " +
        "Hold it to good habits: answer what was asked (a question gets an answer, not edits); reproduce a problem and find its cause before fixing; " +
        "reuse existing open-source code or data instead of rewriting large systems; be honest about what cannot be done; and do not let it claim " +
        "something works unless a check that ran shows it."

    /** Providers in the order they are tried as the advisor when none was picked: the strongest models first. */
    val STRONG_ORDER = listOf("anthropic", "openai", "gemini", "haimaker", "xai", "deepseek")

    /**
     * The advisor when none was picked: the first provider in [STRONG_ORDER] that has a key and is not the one doing the work.
     * [hasKey] tells whether a provider has a key saved; [modelOf] gives its model.
     */
    fun bestOther(main: String, hasKey: (String) -> Boolean, modelOf: (String) -> String): Workflow.Slot? =
        STRONG_ORDER.firstOrNull { it != main && hasKey(it) }?.let { Workflow.Slot(it, modelOf(it)) }

    /** A one-time tip when a stronger model is available but the advisor is off. */
    fun tip(strong: String, main: String): String =
        "Tip: you have a $strong key. Turn on ⋮ → AI → Multi-model workflow and advisor → Automatic checkpoints, and $strong will plan and review the work " +
            "while $main does the edits (two or three $strong calls per job)."

    /** Added to the assistant's instructions when an advisor is available. */
    fun executorNote(mode: String): String = if (mode == OFF) "" else
        " You have an advisor tool backed by a stronger model; it takes no parameters and sees your whole conversation. " +
            "Call it once after you have looked at the files and before you make the first change, again if you get stuck or want to change approach, and once before you finish (save your work first). " +
            "Give its advice serious weight. If the files or a command result contradict it, say so and adapt. Skip it for trivial one-step jobs." +
            (if (mode == AUTO) " The app may also add the advisor's advice to a tool result: follow it unless you have evidence it is wrong." else "")

    private fun label(role: String): String = when (role) { "user" -> "User"; "assistant" -> "Assistant"; "tool" -> "Tool result"; else -> role }

    private fun text(c: Any?): String = when (c) {
        is String -> c
        is List<*> -> c.joinToString("\n") { b -> ((b as? Map<*, *>)?.get("text") as? String).orEmpty() }
        else -> ""
    }

    /** The conversation as plain text that any provider can read. Long results are trimmed; the task and the latest steps are kept. */
    fun transcript(messages: List<Map<String, Any?>>, maxChars: Int = 24000): String {
        val lines = ArrayList<String>()
        fun add(who: String, t: String, cap: Int = 1500) { if (t.isNotBlank()) lines.add("$who: " + t.trim().let { if (it.length > cap) it.take(cap) + " …" else it }) }
        for (m in messages) {
            val role = m["role"] as? String ?: continue
            val c = m["content"]
            if (c is List<*>) {
                for (b in c) {
                    val o = b as? Map<*, *> ?: continue
                    when (o["type"]) {
                        "text" -> add(label(role), o["text"] as? String ?: "")
                        "tool_use" -> add("Assistant called", "${o["name"]} ${o["input"]}", 600)
                        "tool_result" -> add("Tool result", text(o["content"]))
                    }
                }
            } else add(label(role), text(c))
            (m["tool_calls"] as? List<*>)?.forEach { tc ->
                val f = (tc as? Map<*, *>)?.get("function") as? Map<*, *>
                add("Assistant called", "${f?.get("name")} ${f?.get("arguments")}", 600)
            }
        }
        val all = lines.joinToString("\n")
        if (all.length <= maxChars) return all
        val head = all.take(3000)
        return head + "\n… (middle of the conversation left out) …\n" + all.takeLast(maxChars - 3000)
    }

    fun prompt(task: String, transcript: String, purpose: Purpose, words: Int): String =
        "The task the assistant is working on:\n" + task.trim().take(3000) + "\n\nThe conversation so far:\n" + transcript + "\n\n" + when (purpose) {
            Purpose.START -> "The assistant is about to make its first change. Give the plan: the approach, which files to create or change, the order, and the main risks."
            Purpose.FINISH -> "The assistant thinks it is done. Check the work against the task. If everything asked for is done and nothing looks wrong, reply with exactly: LGTM. Otherwise list the specific problems to fix."
            Purpose.HELP -> "The assistant asked for guidance. Tell it what it most needs to know to do this well."
        } + "\n(Advisor: keep your answer under $words words.)"

    /** True when the advisor found nothing to fix. */
    fun clear(advice: String): Boolean {
        val t = advice.trim().trim('.', '!', '*', ' ').lowercase()
        return t == "lgtm" || (t.startsWith("lgtm") && t.length < 40)
    }

    fun heldMessage(advice: String): String =
        "Your change was held so the advisor could look first. The advisor says:\n" + advice.trim() + "\n\nNow make your changes again, adjusted if the advice says so."

    fun heldAgain(): String = "Held: read the advisor's advice above, then make your changes again."

    fun finishMessage(advice: String): String =
        "A reviewer checked your work and found problems:\n" + advice.trim() + "\n\nFix what is wrong (skip anything that is incorrect), then finish with a short summary."
}
