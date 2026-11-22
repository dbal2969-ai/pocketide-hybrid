package dev.pocketide.app.core

import java.io.File
import java.util.Base64

/**
 * Moves a project's history between the phone and GitHub through GitHub's REST API (the app has no git network protocol):
 * import brings a branch's recent commits with their files into the project's history; export sends local commits that GitHub
 * lacks (files, folders, commits, then the branch). Ids are matched in .git/pocketide/github-ids, so each object is sent once.
 * Blob and folder ids are the same on both sides (they are content hashes); commit ids can differ, so they are mapped.
 */
class GitBridge(private val api: GitApi, private val repo: String, private val v: Vcs) {
    private val mapFile = File(v.store.dir, "pocketide/github-ids")
    /** local id -> GitHub id, for commits */
    private val map = LinkedHashMap<String, String>()
    private val sentBlobs = HashSet<String>()

    init {
        if (mapFile.isFile) for (l in mapFile.readLines()) { val p = l.split(' '); if (p.size == 2) { if (p[0] == "blob") sentBlobs.add(p[1]) else map[p[0]] = p[1] } }
    }

    private fun saveMap() { mapFile.parentFile?.mkdirs(); mapFile.writeText(map.entries.joinToString("") { "${it.key} ${it.value}\n" } + sentBlobs.joinToString("") { "blob $it\n" }) }

    private fun json(method: String, path: String, body: String? = null): Map<String, Any?> {
        val r = api.call(method, "/repos/$repo$path", body)
        if (r.code !in 200..299) throw GitHubException("GitHub said ${r.code} for $method $path: " + r.text.take(200), r.code)
        return MiniJson.obj(MiniJson.parse(r.text)) ?: emptyMap()
    }

    private fun jsonList(path: String): List<Map<String, Any?>> {
        val r = api.call("GET", "/repos/$repo$path", null)
        if (r.code !in 200..299) throw GitHubException("GitHub said ${r.code} for $path", r.code)
        return MiniJson.list(MiniJson.parse(r.text)).orEmpty().mapNotNull { MiniJson.obj(it) }
    }

    private fun person(o: Any?): VcsStore.Person {
        val m = MiniJson.obj(o) ?: return Vcs.YOU
        val t = (m["date"] as? String)?.let { try { java.time.OffsetDateTime.parse(it).toEpochSecond() } catch (e: Exception) { null } } ?: 0L
        return VcsStore.Person(m["name"] as? String ?: "", m["email"] as? String ?: "", t)
    }

    /**
     * Brings the last [limit] commits of [branch] into the project's history as branch [into] (default: the same name).
     * Returns how many commits were added. The working folder is not changed.
     */
    fun importHistory(branch: String, limit: Int = 100, into: String = branch): Int {
        if (!v.enabled()) v.store.init(into)
        val list = ArrayList<Map<String, Any?>>(); var page = 1
        while (list.size < limit) {
            val got = jsonList("/commits?sha=${GitCore.encodePath(branch)}&per_page=${minOf(100, limit)}&page=$page")
            if (got.isEmpty()) break
            list.addAll(got); page++
            if (got.size < 100) break
        }
        val wanted = list.take(limit).mapNotNull { it["sha"] as? String }
        val remoteToLocal = map.entries.associate { it.value to it.key }.toMutableMap()
        var added = 0
        for (sha in wanted.reversed()) {           // oldest first, so parents exist
            if (sha in remoteToLocal) continue
            val c = json("GET", "/git/commits/$sha")
            val treeSha = MiniJson.obj(c["tree"])?.get("sha") as? String ?: continue
            val tree = json("GET", "/git/trees/$treeSha?recursive=1")
            val files = LinkedHashMap<String, Pair<String, String>>()
            for (e in MiniJson.list(tree["tree"]).orEmpty().mapNotNull { MiniJson.obj(it) }) {
                if (e["type"] != "blob") continue
                val id = e["sha"] as? String ?: continue; val path = e["path"] as? String ?: continue
                if (!v.store.has(id)) {
                    val b = json("GET", "/git/blobs/$id")
                    val data = Base64.getMimeDecoder().decode((b["content"] as? String).orEmpty())
                    v.store.write("blob", data)
                }
                files[path] = (e["mode"] as? String ?: "100644") to id
            }
            val localTree = v.store.writeFlat(files)
            val parents = MiniJson.list(c["parents"]).orEmpty().mapNotNull { (MiniJson.obj(it)?.get("sha") as? String)?.let { p -> remoteToLocal[p] } }
            val local = v.store.writeCommit(localTree, parents, person(c["author"]), person(c["committer"]), c["message"] as? String ?: "")
            map[local] = sha; remoteToLocal[sha] = local; added++
        }
        wanted.firstOrNull()?.let { remoteToLocal[it] }?.let { v.store.setRef("refs/remotes/github/$into", it); if (v.store.resolve("refs/heads/$into") == null) v.store.setRef("refs/heads/$into", it) }
        saveMap()
        return added
    }

    /**
     * Sends the current branch's commits that GitHub lacks, then moves GitHub's branch to them (only forward; when GitHub has
     * commits the phone lacks, import and merge first). Returns how many commits were sent.
     */
    fun exportHistory(branch: String = v.branch() ?: "main"): Int {
        val head = v.store.resolve("refs/heads/$branch") ?: throw IllegalStateException("No branch $branch.")
        val todo = ArrayList<VcsStore.Commit>()
        val seen = HashSet<String>(); val stack = ArrayDeque(listOf(head))
        while (stack.isNotEmpty()) {
            val id = stack.removeLast(); if (!seen.add(id) || id in map) continue
            val c = v.store.readCommit(id) ?: continue
            todo.add(c); c.parents.forEach { stack.addLast(it) }
        }
        // parents before children
        val order = ArrayList<VcsStore.Commit>(); val done = HashSet<String>(map.keys)
        while (order.size < todo.size) {
            val next = todo.firstOrNull { it.id !in done && it.parents.all { p -> p in done } } ?: break
            order.add(next); done.add(next.id)
        }
        for (c in order) {
            val files = v.filesAt(c.id)
            for ((_, e) in files) if (e.second !in sentBlobs) {
                val data = v.store.readBlob(e.second) ?: continue
                val r = json("POST", "/git/blobs", MiniJson.write(linkedMapOf("content" to Base64.getEncoder().encodeToString(data), "encoding" to "base64")))
                sentBlobs.add(r["sha"] as? String ?: e.second)
            }
            val tree = json("POST", "/git/trees", MiniJson.write(linkedMapOf("tree" to files.map { (p, e) -> linkedMapOf("path" to p, "mode" to e.first, "type" to "blob", "sha" to e.second) })))
            fun who(p: VcsStore.Person) = linkedMapOf("name" to p.name, "email" to p.email, "date" to java.time.Instant.ofEpochSecond(p.time).toString())
            val made = json("POST", "/git/commits", MiniJson.write(linkedMapOf("message" to c.message, "tree" to tree["sha"],
                "parents" to c.parents.mapNotNull { map[it] }, "author" to who(c.author), "committer" to who(c.committer))))
            map[c.id] = made["sha"] as String
            saveMap()
        }
        val target = map[head] ?: throw IllegalStateException("Nothing to send.")
        val ref = api.call("GET", "/repos/$repo/git/ref/heads/${GitCore.encodePath(branch)}", null)
        val r = if (ref.code == 404) api.call("POST", "/repos/$repo/git/refs", MiniJson.write(linkedMapOf("ref" to "refs/heads/$branch", "sha" to target)))
            else api.call("PATCH", "/repos/$repo/git/refs/heads/${GitCore.encodePath(branch)}", MiniJson.write(linkedMapOf("sha" to target, "force" to false)))
        if (r.code == 422) throw GitHubException("GitHub's $branch has commits this phone does not have. Import its history and merge it first, then send again.", 422)
        if (r.code !in 200..299) throw GitHubException("GitHub said ${r.code} when moving $branch: " + r.text.take(200), r.code)
        v.store.setRef("refs/remotes/github/$branch", head)
        return order.size
    }
}
