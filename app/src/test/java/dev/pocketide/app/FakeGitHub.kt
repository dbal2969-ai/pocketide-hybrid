package dev.pocketide.app

import dev.pocketide.app.core.GitApi
import dev.pocketide.app.core.GitCore
import dev.pocketide.app.core.GitResponse
import dev.pocketide.app.core.MiniJson
import java.security.MessageDigest
import java.util.Base64

/** An in-memory stand-in for the parts of the GitHub REST API that GitSync uses. */
class FakeGitHub(private val owner: String = "me", private val name: String = "repo", private val branch: String = "main") : GitApi {
    val blobs = HashMap<String, ByteArray>()
    private val trees = HashMap<String, Map<String, String>>() // tree sha -> path -> blob sha
    private val commits = HashMap<String, Pair<String, String?>>() // commit -> (tree, parent)
    var head: String? = null
    var calls = 0

    private fun sha1(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun ok(v: Any?, code: Int = 200) = GitResponse(code, MiniJson.write(v).toByteArray())
    private fun err(code: Int, msg: String) = GitResponse(code, MiniJson.write(mapOf("message" to msg)).toByteArray())

    private fun putTree(files: Map<String, String>): String {
        val id = sha1("tree" + files.toSortedMap().entries.joinToString("\n") { it.key + " " + it.value })
        trees[id] = HashMap(files)
        return id
    }

    private fun putCommit(tree: String, parent: String?, msg: String): String {
        val id = sha1("commit $tree $parent $msg ${commits.size}")
        commits[id] = tree to parent
        return id
    }

    fun files(): Map<String, ByteArray> {
        val h = head ?: return emptyMap()
        return trees.getValue(commits.getValue(h).first).mapValues { blobs.getValue(it.value) }
    }

    /** Someone else commits on GitHub. Null bytes deletes the file. */
    fun commitDirect(changes: Map<String, ByteArray?>) {
        val cur = HashMap(head?.let { trees.getValue(commits.getValue(it).first) } ?: emptyMap())
        for ((p, b) in changes) {
            if (b == null) cur.remove(p) else { val s = GitCore.blobSha(b); blobs[s] = b; cur[p] = s }
        }
        head = putCommit(putTree(cur), head, "direct")
    }

    override fun call(method: String, path: String, body: String?, raw: Boolean): GitResponse {
        calls++
        val r = "/repos/$owner/$name"
        if (!path.startsWith(r)) return err(404, "Not Found")
        val rest = path.removePrefix(r)
        val json = body?.let { MiniJson.obj(MiniJson.parse(it)) }
        return when {
            method == "GET" && rest == "" -> ok(mapOf("size" to (if (head == null) 0 else 10)))
            method == "GET" && rest == "/git/ref/heads/$branch" ->
                if (head == null) err(409, "Git Repository is empty.") else ok(mapOf("object" to mapOf("sha" to head)))
            method == "GET" && rest.startsWith("/git/ref/heads/") -> err(404, "Not Found")
            method == "GET" && rest.startsWith("/git/commits/") -> {
                val c = commits[rest.substringAfterLast('/')] ?: return err(404, "Not Found")
                ok(mapOf("tree" to mapOf("sha" to c.first)))
            }
            method == "GET" && rest.startsWith("/git/trees/") -> {
                val t = trees[rest.substringAfter("/git/trees/").substringBefore('?')] ?: return err(404, "Not Found")
                ok(mapOf("truncated" to false, "tree" to t.entries.sortedBy { it.key }.map {
                    mapOf("path" to it.key, "mode" to "100644", "type" to "blob", "sha" to it.value, "size" to blobs.getValue(it.value).size)
                }))
            }
            method == "GET" && rest.startsWith("/git/blobs/") -> {
                if (!raw) return err(415, "expected raw")
                val b = blobs[rest.substringAfterLast('/')] ?: return err(404, "Not Found")
                GitResponse(200, b)
            }
            method == "POST" && rest == "/git/blobs" -> {
                if (head == null) return err(409, "Git Repository is empty.")
                val b = Base64.getDecoder().decode(json!!["content"] as String)
                val s = GitCore.blobSha(b); blobs[s] = b
                ok(mapOf("sha" to s), 201)
            }
            method == "POST" && rest == "/git/trees" -> {
                val base = trees[json!!["base_tree"] as String] ?: return err(422, "bad base tree")
                val cur = HashMap(base)
                for (e in MiniJson.list(json["tree"])!!) {
                    val m = MiniJson.obj(e)!!
                    val p = m["path"] as String
                    val s = m["sha"] as String?
                    if (s == null) cur.remove(p) else { if (!blobs.containsKey(s)) return err(422, "unknown blob"); cur[p] = s }
                }
                ok(mapOf("sha" to putTree(cur)), 201)
            }
            method == "POST" && rest == "/git/commits" -> {
                val tree = json!!["tree"] as String
                if (!trees.containsKey(tree)) return err(422, "unknown tree")
                val parent = MiniJson.list(json["parents"])!!.firstOrNull() as String?
                ok(mapOf("sha" to putCommit(tree, parent, json["message"] as String)), 201)
            }
            method == "PATCH" && rest == "/git/refs/heads/$branch" -> {
                val sha = json!!["sha"] as String
                val c = commits[sha] ?: return err(422, "unknown commit")
                if (c.second != head) return err(422, "Update is not a fast forward")
                head = sha
                ok(mapOf("object" to mapOf("sha" to sha)))
            }
            method == "PUT" && rest.startsWith("/contents/") -> {
                if (head != null) return err(422, "exists")
                val p = java.net.URLDecoder.decode(rest.removePrefix("/contents/"), "UTF-8")
                val b = Base64.getDecoder().decode(json!!["content"] as String)
                val s = GitCore.blobSha(b); blobs[s] = b
                head = putCommit(putTree(mapOf(p to s)), null, json["message"] as String)
                ok(mapOf("commit" to mapOf("sha" to head)), 201)
            }
            else -> err(404, "Not Found: $method $rest")
        }
    }
}
