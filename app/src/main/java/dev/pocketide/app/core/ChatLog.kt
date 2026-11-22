package dev.pocketide.app.core

// v0.4 W2: saved chat sessions, one JSON file per chat, kept per project outside the project folder.

/** One line of a saved chat. note is a short summary of what happened ("Edited app.js"); images counts attached pictures. */
/** [tag]: the app version and model that sent or answered this message (see BuildTag); "" in older chats. */
/** [log]: how an assistant reply was made (WorkLog: thinking, words between steps, steps), shown folded under it; not sent to the model. */
data class ChatEntry(val role: String, val content: String, val note: String = "", val images: Int = 0, val time: Long = 0L, val tag: String = "",
                     val log: List<WorkLog.Item> = emptyList())

class ChatSession(
    val id: String,
    var title: String,
    val created: Long,
    var updated: Long,
    val entries: MutableList<ChatEntry> = ArrayList()
) {
    /**
     * The work log of the run in progress (1.5.96), saved every few seconds while it runs and cleared when its reply is saved, so a
     * run that Android closes keeps what it had thought and done, and can be continued from there.
     */
    var running: List<WorkLog.Item> = emptyList()
    /** When the run in [running] started (1.5.97), to tell whether Android's last exit record is about it. */
    var runningSince: Long = 0L
    /** How many times a cut-off run of the unanswered request was carried on by itself (1.5.99); at most AUTO_RESUMES. */
    var autoResumes: Int = 0

    fun unansweredRequestIndex(): Int? {
        val index = entries.indexOfLast { it.role == "user" || it.role == "assistant" }
        val entry = entries.getOrNull(index) ?: return null
        return index.takeIf { entry.role == "user" && entry.note != "auto" }
    }

    fun toJson(): String = MiniJson.write(
        mapOf(
            "id" to id, "title" to title, "created" to created, "updated" to updated,
            "entries" to entries.map { e ->
                val m = LinkedHashMap<String, Any?>()
                m["role"] = e.role; m["content"] = e.content
                if (e.note.isNotEmpty()) m["note"] = e.note
                if (e.images > 0) m["images"] = e.images
                if (e.time > 0) m["time"] = e.time
                if (e.tag.isNotEmpty()) m["tag"] = e.tag
                if (e.log.isNotEmpty()) m["log"] = WorkLog.toJson(e.log)
                m
            }
        ) + (if (running.isEmpty()) emptyMap() else mapOf("running" to WorkLog.toJson(running), "runningSince" to runningSince, "autoResumes" to autoResumes))
    )

    /** Messages for the model: user and assistant lines only, newest kept, within a character budget. */
    fun history(maxChars: Int = ChatLog.HISTORY_CHARS, maxMessages: Int = ChatLog.HISTORY_MESSAGES): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var total = 0
        // Plan proposals (saved by older versions) teach small models to answer everything with a plan; leave them out.
        val clean = ArrayList<ChatEntry>()
        var k = 0
        while (k < entries.size) {
            val e = entries[k]
            if (e.role == "user" && e.content.startsWith(Plans.REQUEST_START)) {
                k++
                if (k < entries.size && entries[k].role == "assistant") k++
                continue
            }
            clean.add(e); k++
        }
        for (e in clean.asReversed()) {
            if (e.role != "user" && e.role != "assistant") continue
            if (e.content.isBlank()) continue
            if (out.size >= maxMessages) break
            val c = if (e.content.length > maxChars / 2) e.content.substring(0, maxChars / 2) + "\n…" else e.content
            if (total + c.length > maxChars && out.isNotEmpty()) break
            total += c.length
            out.add(e.role to c)
        }
        out.reverse()
        // a conversation sent to a model should not start with the assistant
        while (out.isNotEmpty() && out[0].first != "user") out.removeAt(0)
        return out
    }

    /**
     * Messages for the model when the chat is long: the newest [keepFull] turns as they are, and everything
     * older folded into one short digest, so the gist survives without resending the whole conversation.
     */
    fun compressedHistory(maxChars: Int, maxMessages: Int, keepFull: Int = 6): List<Pair<String, String>> {
        val full = history(maxChars, maxMessages)
        val usable = entries.filter { (it.role == "user" || it.role == "assistant") && it.content.isNotBlank() }
        if (usable.size <= keepFull || full.size >= usable.size) return full
        val older = usable.dropLast(keepFull)
        val digest = ChatExport.digest(older)
        val recent = history(maxChars - digest.length - 50, keepFull)
        val olderChars = older.sumOf { it.content.length }
        if (digest.length < 80 || recent.isEmpty() || digest.length >= olderChars) return full
        return listOf("user" to digest) + recent
    }

    companion object {
        fun fromJson(s: String): ChatSession? = try {
            val o = MiniJson.obj(MiniJson.parse(s))!!
            val entries = ArrayList<ChatEntry>()
            for (x in MiniJson.list(o["entries"]) ?: emptyList()) {
                val e = MiniJson.obj(x) ?: continue
                val role = e["role"] as? String ?: continue
                entries.add(ChatEntry(role, e["content"] as? String ?: "", e["note"] as? String ?: "", (e["images"] as? Double)?.toInt() ?: 0, (e["time"] as? Double)?.toLong() ?: 0L, e["tag"] as? String ?: "", WorkLog.fromJson(e["log"])))
            }
            ChatSession(
                o["id"] as String, o["title"] as? String ?: "Chat",
                (o["created"] as? Double)?.toLong() ?: 0L, (o["updated"] as? Double)?.toLong() ?: 0L, entries
            ).also { it.running = WorkLog.fromJson(o["running"]); it.runningSince = (o["runningSince"] as? Double)?.toLong() ?: 0L; it.autoResumes = (o["autoResumes"] as? Double)?.toInt() ?: 0 }
        } catch (e: Exception) { null }
    }
}

object ChatLog {
    const val HISTORY_CHARS = 24000
    const val HISTORY_MESSAGES = 20
    const val MAX_CHATS = 60

    fun newId(now: Long): String = "c" + now.toString(36) + (Math.random() * 1296).toInt().toString(36)

    /** A title from the first message: first line, no slash command prefix, at most ~48 characters. */
    fun title(firstMessage: String): String {
        var t = firstMessage.trim().lineSequence().firstOrNull()?.trim() ?: ""
        if (t.startsWith("/")) t = t.substringAfter(' ', "").trim().ifEmpty { t }
        t = t.replace(Regex("\\s+"), " ")
        if (t.length > 48) t = t.substring(0, 47).trimEnd() + "…"
        return t.ifEmpty { "New chat" }
    }

    /** Newest first. */
    fun sort(list: List<ChatSession>): List<ChatSession> = list.sortedByDescending { it.updated }
}
