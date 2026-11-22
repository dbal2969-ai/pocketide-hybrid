package dev.pocketide.app.core

/**
 * The plan of a long job, saved in the project (.pocketide/progress.json) every time the assistant updates it, and shown
 * to the assistant at the start of the next request. A long job then survives the app being closed, a model switch or a
 * trimmed conversation (Anthropic's long-running harness keeps a progress file and a JSON feature list for the same reason;
 * JSON because models rewrite it less freely than prose). Only the app writes the file.
 */
object Progress {
    const val FILE = ".pocketide/progress.json"

    data class Item(val text: String, val status: String)
    data class State(val request: String, val items: List<Item>, val updated: String)

    fun encode(s: State): String = MiniJson.write(linkedMapOf(
        "request" to s.request, "updated" to s.updated,
        "items" to s.items.map { linkedMapOf("text" to it.text, "status" to it.status) }))

    fun decode(json: String?): State? {
        if (json.isNullOrBlank()) return null
        val o = try { MiniJson.obj(MiniJson.parse(json)) } catch (e: Exception) { null } ?: return null
        val items = (o["items"] as? List<*>).orEmpty().mapNotNull { x ->
            val m = x as? Map<*, *> ?: return@mapNotNull null
            val t = (m["text"] as? String)?.trim().orEmpty()
            if (t.isEmpty()) null else Item(t, (m["status"] as? String) ?: "todo")
        }
        if (items.isEmpty()) return null
        return State((o["request"] as? String).orEmpty(), items, (o["updated"] as? String).orEmpty())
    }

    /**
     * The state to save after the assistant set [steps] while working on [request]. A plan that continues the saved one
     * (most of its steps are the same) keeps the original request, so "continue" does not replace what the job was.
     */
    fun next(old: State?, request: String, steps: List<Pair<String, String>>, day: String): State {
        val items = steps.map { Item(it.first.trim(), it.second) }.filter { it.text.isNotEmpty() }
        val same = old != null && old.items.isNotEmpty() && items.count { n -> old.items.any { it.text.equals(n.text, true) } } * 2 >= minOf(old.items.size, items.size).coerceAtLeast(1)
        return State(if (same) old!!.request else request.take(300), items, day)
    }

    fun unfinished(s: State?): Boolean = s != null && s.items.any { it.status != "done" }

    /** Added to the project context while the saved job is unfinished; empty otherwise. */
    fun contextNote(json: String?): String {
        val s = decode(json) ?: return ""
        if (!unfinished(s)) return ""
        // The current step in full; finished and remaining steps by title only, so the model keeps the thread
        // without the whole plan (a bare current step invites tunnel vision and redoing finished work).
        val mark = mapOf("done" to "done", "doing" to "in progress", "failed" to "failed")
        val current = s.items.indexOfFirst { it.status != "done" }
        if (current < 0) return ""
        val item = s.items[current]
        val finished = s.items.take(current).filter { it.status == "done" }
        val after = s.items.drop(current + 1).filter { it.status != "done" }
        return "\n\nSaved progress from an earlier request" + (if (s.updated.isNotEmpty()) " (${s.updated})" else "") +
            (if (s.request.isNotEmpty()) ": \"${s.request.take(200)}\"" else "") + "\n" +
            (if (finished.isNotEmpty()) "Finished: " + finished.take(8).joinToString("; ") { it.text.take(80) } + "\n" else "") +
            "Current step (${current + 1} of ${s.items.size}): [${mark[item.status] ?: "to do"}] ${item.text}\n" +
            (if (after.isNotEmpty()) "Still to do after it: " + after.take(8).joinToString("; ") { it.text.take(80) } + "\n" else "") +
            "Work on the current step now and change only what it needs. Do not redo finished steps. When it is checked, mark it done with update_plan and go on to the next one. " +
            "Mark a step done only after you have checked it works (look_at_preview, tests, or running it). If the message is about something else, ignore this note.\n"
    }

    /** One line for the person: "Saved progress: 3 of 7 done". */
    fun summary(s: State): String = "Saved progress: ${s.items.count { it.status == "done" }} of ${s.items.size} done"
}
