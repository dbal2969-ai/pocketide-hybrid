package dev.pocketide.app.core

/**
 * The record of how a reply was made (1.5.95): the model's thinking, its words between tool calls and each step, in order, saved
 * with the reply in the chat, as Copilot and Claude keep theirs. Before this, the thinking was counted and thrown away and the words
 * between steps only lived on screen, so a run that spent an hour without changing anything could not be looked back at.
 */
object WorkLog {
    enum class Kind(val key: String) { THINK("think"), SAY("say"), STEP("step") }

    /** One entry. [secs]: how long the thinking took, for THINK (0 when not known). */
    data class Item(val kind: Kind, val text: String, val secs: Int = 0)

    /** One thought is kept up to this many characters (its start and end); thinking models can write 15,000 a round. */
    const val THOUGHT_CAP = 12_000
    /** Thinking kept per reply in all; past this the oldest thoughts keep only their first lines. */
    const val THINKING_TOTAL = 150_000
    private const val SAY_CAP = 8_000
    private const val STEP_CAP = 300

    /** Adds an item, keeping each within its cap and the whole log's thinking within [THINKING_TOTAL]. */
    fun add(log: MutableList<Item>, kind: Kind, text: String, secs: Int = 0) {
        val t = text.trim()
        if (t.isEmpty()) return
        log.add(Item(kind, when (kind) { Kind.THINK -> clip(t, THOUGHT_CAP); Kind.SAY -> clip(t, SAY_CAP); Kind.STEP -> t.take(STEP_CAP) }, secs))
        var total = log.sumOf { if (it.kind == Kind.THINK) it.text.length else 0 }
        var i = 0
        while (total > THINKING_TOTAL && i < log.size - 1) {
            val it = log[i]
            if (it.kind == Kind.THINK && !it.text.endsWith(TRIMMED)) {
                val short = it.text.lineSequence().take(3).joinToString("\n").take(400) + TRIMMED
                total -= it.text.length - short.length
                log[i] = it.copy(text = short)
            }
            i++
        }
    }

    private const val TRIMMED = "\n… (rest of this thought not kept: the reply's thinking was very long)"

    /** The start and end of a long text, with a marker between. */
    fun clip(t: String, cap: Int): String {
        if (t.length <= cap) return t
        val head = cap * 2 / 3
        return t.take(head) + "\n… (${t.length - cap} characters not kept) …\n" + t.takeLast(cap - head)
    }

    /**
     * A step on one line: "Ran python3 -c \"\nimport json\nwith open('game/data/maps…" becomes
     * "Ran python3 -c: import json; with open('game/data/maps…". The full command stays in the step's chip and in the trace.
     */
    fun oneLine(step: String, max: Int = 110): String {
        val lines = step.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ""
        val first = lines[0].removeSuffix("\"").removeSuffix("'").trimEnd()
        val rest = lines.drop(1).filter { it != "\"" && it != "'" }
        val s = if (rest.isEmpty()) first else "$first: " + rest.joinToString("; ")
        val flat = s.replace(Regex("""\s+"""), " ")
        return if (flat.length <= max) flat else flat.take(max - 1).trimEnd() + "…"
    }

    /** "Thought 4 times (1m 20s) · 3 notes · 12 steps", for the folded line under a saved reply. */
    fun summary(log: List<Item>): String {
        val thoughts = log.filter { it.kind == Kind.THINK }
        val says = log.count { it.kind == Kind.SAY }
        val steps = log.count { it.kind == Kind.STEP }
        val parts = ArrayList<String>()
        if (thoughts.isNotEmpty()) {
            val secs = thoughts.sumOf { it.secs }
            parts.add("Thought " + (if (thoughts.size == 1) "once" else "${thoughts.size} times") + (if (secs > 0) " (" + LocalModels.clock(secs.toLong()) + ")" else ""))
        }
        if (says > 0) parts.add("$says note" + if (says == 1) "" else "s")
        if (steps > 0) parts.add("$steps step" + if (steps == 1) "" else "s")
        return parts.joinToString(" · ")
    }

    /**
     * Added to the request when a cut-off run is continued (1.5.96): the files it changed, the last steps and what it had found, so
     * the model carries on instead of reading everything again. Thoughts are left out (long, and partly superseded); its notes stay.
     */
    fun resume(log: List<Item>): String {
        val steps = log.filter { it.kind == Kind.STEP }.map { it.text }
        val changed = steps.filter { it.startsWith("Edited ") || it.startsWith("Wrote ") || it.startsWith("Created ") || it.startsWith("Patched ") }
            .map { it.substringAfter(' ').substringBefore(' ').trimEnd(',', '.') }.distinct()
        val said = log.filter { it.kind == Kind.SAY }.map { it.text } +
            log.lastOrNull { it.kind == Kind.THINK }?.let { listOf("(last thought) " + it.text.takeLast(600)) }.orEmpty()
        return StopNote.text(said, steps, changed, emptyList(), "the run was cut off before it finished (the app was closed, or an error)")
    }

    /** "Thought for 12s" or "Thought". */
    fun thoughtTitle(it: Item): String = if (it.secs > 0) "Thought for " + LocalModels.clock(it.secs.toLong()) else "Thought"

    fun toJson(log: List<Item>): List<Map<String, Any?>> = log.map { i ->
        val m = LinkedHashMap<String, Any?>(); m["k"] = i.kind.key; m["t"] = i.text; if (i.secs > 0) m["s"] = i.secs; m
    }

    fun fromJson(x: Any?): List<Item> = (MiniJson.list(x) ?: emptyList()).mapNotNull { v ->
        val m = MiniJson.obj(v) ?: return@mapNotNull null
        val kind = Kind.values().firstOrNull { it.key == m["k"] } ?: return@mapNotNull null
        Item(kind, m["t"] as? String ?: return@mapNotNull null, (m["s"] as? Double)?.toInt() ?: 0)
    }

    /** The log as Markdown for an exported chat: thoughts as quotes, notes as text, steps as a list. */
    fun markdown(log: List<Item>, commands: Boolean = true): String {
        if (log.isEmpty()) return ""
        val sb = StringBuilder("\n<details><summary>How it worked: ").append(summary(log)).append("</summary>\n\n")
        var inSteps = false
        for (i in log) {
            if (i.kind == Kind.STEP) {
                if (!commands && i.text.startsWith("Ran ")) continue
                if (!inSteps) { sb.append("\n"); inSteps = true }
                sb.append("- ").append(oneLine(i.text, 200)).append("\n"); continue
            }
            if (inSteps) { sb.append("\n"); inSteps = false }
            when (i.kind) {
                Kind.THINK -> { sb.append("**").append(thoughtTitle(i)).append(":**\n\n"); for (l in i.text.lines()) sb.append("> ").append(l).append("\n"); sb.append("\n") }
                else -> sb.append(i.text).append("\n\n")
            }
        }
        return sb.append("\n</details>\n").toString()
    }
}
