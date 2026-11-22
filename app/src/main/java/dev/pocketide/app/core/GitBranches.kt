package dev.pocketide.app.core

/** Branch list, new branch and commit history, read from GitHub's REST replies. Pure parsing; GitSync makes the calls. */
object GitBranches {
    data class Entry(val sha: String, val message: String, val author: String, val date: String) {
        val line: String get() = "${sha.take(7)}  $message" + (if (author.isNotEmpty()) "  ($author, ${date.take(10)})" else "")
    }

    fun names(json: String): List<String> =
        try { (MiniJson.list(MiniJson.parse(json)) ?: emptyList()).mapNotNull { MiniJson.obj(it)?.get("name") as? String }.sorted() } catch (e: Exception) { emptyList() }

    fun createBody(name: String, sha: String): String = MiniJson.write(linkedMapOf("ref" to "refs/heads/$name", "sha" to sha))

    fun log(json: String): List<Entry> =
        try {
            (MiniJson.list(MiniJson.parse(json)) ?: emptyList()).mapNotNull { x ->
                val o = MiniJson.obj(x) ?: return@mapNotNull null
                val c = MiniJson.obj(o["commit"]) ?: return@mapNotNull null
                val a = MiniJson.obj(c["author"])
                Entry(o["sha"] as? String ?: return@mapNotNull null, (c["message"] as? String).orEmpty().lineSequence().firstOrNull().orEmpty().take(90),
                    (a?.get("name") as? String).orEmpty(), (a?.get("date") as? String).orEmpty())
            }
        } catch (e: Exception) { emptyList() }
}
