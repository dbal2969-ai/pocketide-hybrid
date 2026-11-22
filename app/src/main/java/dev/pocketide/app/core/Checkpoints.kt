package dev.pocketide.app.core

import java.io.File

// v0.4 W2: checkpoints. Each one stores the files a turn (or plan step) changed, as they were before it,
// so the project can be put back to any earlier point. Kept per project outside the project folder.

/** [chat] marks the conversation at the time ("sessionId:entries"), so a restore can rewind the chat too; "" when unknown. */
data class Checkpoint(val id: String, val label: String, val time: Long, val paths: List<String>, val bytes: Long, val chat: String = "") {
    /** The chat id and how many entries it had, or null. */
    val chatMark: Pair<String, Int>? get() = chat.substringBeforeLast(':', "").takeIf { it.isNotEmpty() }?.let { id -> chat.substringAfterLast(':').toIntOrNull()?.let { id to it } }
}

class Checkpoints(private val dir: File, private val maxCount: Int = 20, private val maxBytes: Long = 50L * 1024 * 1024) {
    companion object {
        /** Checkpoints kept per project (long runs save one per round). */
        const val KEEP = 60
        /** Round checkpoints in one request at most; later rounds are kept together in the end-of-request checkpoint. */
        const val MAX_ROUNDS = 25
        /** "Build the game · round 4: main.js, ui.js and 2 more" */
        fun roundLabel(request: String, round: Int, paths: List<String>): String =
            request.take(50) + " · round " + round + ": " + paths.take(2).joinToString(", ") { it.substringAfterLast('/') } + (if (paths.size > 2) " and ${paths.size - 2} more" else "")
    }


    /** Saves the "before" state of the given files (null = the file did not exist). Returns null when nothing changed. */
    fun save(label: String, before: Map<String, ByteArray?>, now: Long = System.currentTimeMillis(), chat: String = ""): Checkpoint? {
        if (before.isEmpty()) return null
        dir.mkdirs()
        var id = now.toString()
        while (File(dir, id).exists()) id += "x"
        val cp = File(dir, id)
        val files = File(cp, "files")
        files.mkdirs()
        val entries = ArrayList<Map<String, Any?>>()
        var size = 0L
        var n = 0
        for ((path, data) in before) {
            val m = LinkedHashMap<String, Any?>()
            m["path"] = path
            if (data == null) m["absent"] = true
            else {
                val name = "f" + (n++)
                File(files, name).writeBytes(data)
                m["file"] = name
                size += data.size
            }
            entries.add(m)
        }
        val manifest = linkedMapOf<String, Any?>("label" to label, "time" to now, "entries" to entries)
        if (chat.isNotEmpty()) manifest["chat"] = chat
        File(cp, "manifest.json").writeText(MiniJson.write(manifest))
        prune()
        return Checkpoint(id, label, now, before.keys.toList(), size, chat)
    }

    /** Newest first. */
    fun list(): List<Checkpoint> {
        val out = ArrayList<Checkpoint>()
        for (d in dir.listFiles() ?: emptyArray()) {
            val m = File(d, "manifest.json")
            if (!m.isFile) continue
            val o = try { MiniJson.obj(MiniJson.parse(m.readText())) } catch (e: Exception) { null } ?: continue
            val entries = MiniJson.list(o["entries"]) ?: emptyList()
            val paths = entries.mapNotNull { MiniJson.obj(it)?.get("path") as? String }
            val bytes = (File(d, "files").listFiles() ?: emptyArray()).sumOf { it.length() }
            out.add(Checkpoint(d.name, o["label"] as? String ?: "", (o["time"] as? Double)?.toLong() ?: 0L, paths, bytes, o["chat"] as? String ?: ""))
        }
        return out.sortedByDescending { it.time }
    }

    /**
     * Puts the project back to how it was before checkpoint id: applies that checkpoint and every newer one,
     * newest first. write/delete act on project paths. Returns the paths touched, and removes the used checkpoints.
     */
    fun restore(id: String, write: (String, ByteArray) -> Boolean, delete: (String) -> Unit): List<String> {
        val all = list()
        val target = all.firstOrNull { it.id == id } ?: return emptyList()
        val touched = LinkedHashSet<String>()
        for (cp in all.filter { it.time >= target.time }) {   // newest first
            val d = File(dir, cp.id)
            val o = try { MiniJson.obj(MiniJson.parse(File(d, "manifest.json").readText())) } catch (e: Exception) { null } ?: continue
            for (x in MiniJson.list(o["entries"]) ?: emptyList()) {
                val e = MiniJson.obj(x) ?: continue
                val path = e["path"] as? String ?: continue
                if (e["absent"] == true) delete(path)
                else File(File(d, "files"), e["file"] as? String ?: continue).takeIf { it.isFile }?.let { write(path, it.readBytes()) }
                touched.add(path)
            }
            deleteTree(d)
        }
        return touched.toList()
    }

    fun clear() { for (d in dir.listFiles() ?: emptyArray()) deleteTree(d) }

    private fun prune() {
        val all = list()
        var total = 0L
        for ((i, cp) in all.withIndex()) {
            total += cp.bytes
            if (i >= maxCount || (i > 0 && total > maxBytes)) deleteTree(File(dir, cp.id))
        }
    }

    private fun deleteTree(f: File) {
        if (f.isDirectory) f.listFiles()?.forEach { deleteTree(it) }
        f.delete()
    }
}
