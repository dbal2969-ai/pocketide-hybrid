package dev.pocketide.app.core

import java.security.MessageDigest
import java.util.Base64

// GitHub sync logic with no Android dependency, so it can be checked on a plain JVM.
// Ideas (repo/branch/token validation, sync-before-push safety) come from the author's MobileCoder app;
// MobileCoder used JGit, while this version talks to the GitHub REST API directly and needs no library.

/** A GitHub repository, as owner/name. */
data class GitRepo(val owner: String, val name: String) {
    val identity: String get() = "$owner/$name"

    companion object {
        private val PART = Regex("[A-Za-z0-9_.-]{1,100}")

        /** Accepts owner/name, github.com/owner/name or https://github.com/owner/name(.git). Null if it is neither. */
        fun parse(value: String): GitRepo? {
            var v = value.trim().removeSuffix("/").removeSuffix(".git")
            v = v.removePrefix("https://").removePrefix("http://").removePrefix("www.")
            v = v.removePrefix("github.com/")
            val parts = v.split('/')
            if (parts.size != 2) return null
            val (o, n) = parts
            if (!PART.matches(o) || !PART.matches(n) || o in setOf(".", "..") || n in setOf(".", "..")) return null
            return GitRepo(o, n)
        }
    }
}

object GitCore {
    /** Git's object id for file contents: SHA-1 of "blob <size>\0" followed by the bytes. */
    fun blobSha(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("blob ${bytes.size}\u0000".toByteArray(Charsets.US_ASCII))
        md.update(bytes)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** A branch name GitHub will accept, or null. */
    fun branch(value: String): String? {
        val b = value.trim()
        val ok = b.length in 1..128 && Regex("[A-Za-z0-9._/-]+").matches(b) && !b.startsWith('/') && !b.endsWith('/') &&
            !b.contains("//") && !b.endsWith(".lock") && b.split('/').none { it.isBlank() || it == "." || it == ".." || it.startsWith(".") }
        return if (ok) b else null
    }

    /** True for the shape of a GitHub token (classic ghp_..., fine-grained github_pat_...). Not a password or URL. */
    fun tokenLooksValid(token: String): Boolean =
        token.length in 20..512 && token.all { (it.isLetterOrDigit() && it.code < 128) || it == '_' }

    /** Paths sent to GitHub: relative, no '..', no .git folder. */
    fun pathOk(path: String): Boolean =
        path.isNotEmpty() && !path.startsWith("/") && !path.contains('\u0000') && path.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
            path != ".git" && !path.startsWith(".git/")

    fun encodePath(path: String): String = path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    // ---- request bodies ----

    fun blobBody(bytes: ByteArray): String =
        MiniJson.write(mapOf("content" to Base64.getEncoder().encodeToString(bytes), "encoding" to "base64"))

    /** One tree entry; sha null deletes the path. */
    data class TreeEntry(val path: String, val sha: String?, val mode: String = "100644")

    fun treeBody(baseTree: String, entries: List<TreeEntry>): String =
        MiniJson.write(mapOf(
            "base_tree" to baseTree,
            "tree" to entries.map { mapOf("path" to it.path, "mode" to it.mode, "type" to "blob", "sha" to it.sha) }
        ))

    fun commitBody(message: String, tree: String, parent: String?): String =
        MiniJson.write(mapOf("message" to message, "tree" to tree, "parents" to (if (parent == null) emptyList<String>() else listOf(parent))))

    fun refBody(sha: String): String = MiniJson.write(mapOf("sha" to sha, "force" to false))

    /** Contents API body, used only for the first file in an empty repository. */
    fun contentsBody(message: String, bytes: ByteArray, branch: String): String =
        MiniJson.write(mapOf("message" to message, "content" to Base64.getEncoder().encodeToString(bytes), "branch" to branch))

    // ---- response parsing ----

    /** The commit sha a ref points at, from GET git/ref/heads/<branch>. */
    fun refSha(json: String): String? = MiniJson.obj(MiniJson.obj(MiniJson.parse(json))?.get("object"))?.get("sha") as? String

    /** The tree sha of a commit, from GET git/commits/<sha>. */
    fun commitTree(json: String): String? = MiniJson.obj(MiniJson.obj(MiniJson.parse(json))?.get("tree"))?.get("sha") as? String

    fun shaField(json: String): String? = MiniJson.obj(MiniJson.parse(json))?.get("sha") as? String

    data class RemoteFile(val path: String, val sha: String, val mode: String, val size: Long)
    data class RemoteTree(val files: List<RemoteFile>, val truncated: Boolean)

    /** Regular files from GET git/trees/<sha>?recursive=1. Symlinks and submodules are left out. */
    fun parseTree(json: String): RemoteTree {
        val o = MiniJson.obj(MiniJson.parse(json)) ?: return RemoteTree(emptyList(), false)
        val out = ArrayList<RemoteFile>()
        for (e in MiniJson.list(o["tree"]) ?: emptyList()) {
            val m = MiniJson.obj(e) ?: continue
            if (m["type"] != "blob") continue
            val mode = m["mode"] as? String ?: "100644"
            if (mode == "120000") continue
            val path = m["path"] as? String ?: continue
            val sha = m["sha"] as? String ?: continue
            if (!pathOk(path)) continue
            out.add(RemoteFile(path, sha, mode, ((m["size"] as? Double) ?: 0.0).toLong()))
        }
        return RemoteTree(out, o["truncated"] == true)
    }

    /** A plain-language message for a failed GitHub request. */
    fun errorMessage(code: Int, body: String, what: String): String {
        val api = try { MiniJson.obj(MiniJson.parse(body))?.get("message") as? String } catch (e: Exception) { null }
        return when (code) {
            401 -> "GitHub rejected the token. Check it in Git setup, or make a new one."
            403 -> if (api?.contains("rate limit", true) == true) "GitHub's rate limit was reached. Try again in a few minutes."
                   else "The token is not allowed to do this ($what). A fine-grained token needs Contents: Read and write on this repository."
            404 -> "Not found while trying to $what. Check the owner, repository and branch, and that the token can see this repository."
            409 -> if (api?.contains("empty", true) == true) "The repository is empty." else "GitHub reported a conflict while trying to $what${if (api != null) ": $api" else "."}"
            422 -> if (api?.contains("fast forward", true) == true || api?.contains("fast-forward", true) == true)
                       "GitHub has newer commits on this branch. Pull first, then push again."
                   else "GitHub refused to $what${if (api != null) ": $api" else "."}"
            else -> "GitHub returned $code while trying to $what${if (api != null) ": $api" else "."}"
        }
    }
}

/** What the project looked like at the last pull or push: the commit and each file's blob sha. */
data class SyncSnapshot(val repo: String, val branch: String, val commit: String, val files: Map<String, String>) {
    fun toJson(): String = MiniJson.write(mapOf("repo" to repo, "branch" to branch, "commit" to commit, "files" to files))

    companion object {
        fun fromJson(s: String): SyncSnapshot? = try {
            val o = MiniJson.obj(MiniJson.parse(s))!!
            val files = LinkedHashMap<String, String>()
            MiniJson.obj(o["files"])?.forEach { (k, v) -> if (v is String) files[k] = v }
            SyncSnapshot(o["repo"] as String, o["branch"] as String, o["commit"] as String, files)
        } catch (e: Exception) { null }
    }
}

enum class ChangeKind(val letter: Char, val label: String) { ADDED('A', "Added"), MODIFIED('M', "Modified"), DELETED('D', "Deleted") }
data class FileChange(val path: String, val kind: ChangeKind)

object Changes {
    /** Differences between the synced files (path to blob sha) and the current ones, sorted by path. */
    fun compute(base: Map<String, String>, current: Map<String, String>): List<FileChange> {
        val out = ArrayList<FileChange>()
        for ((p, sha) in current) {
            val b = base[p]
            if (b == null) out.add(FileChange(p, ChangeKind.ADDED)) else if (b != sha) out.add(FileChange(p, ChangeKind.MODIFIED))
        }
        for (p in base.keys) if (!current.containsKey(p)) out.add(FileChange(p, ChangeKind.DELETED))
        return out.sortedBy { it.path }
    }

    /**
     * How a pull should treat each file. remote is the new remote state, base the last synced state (empty on a first pull),
     * local the current files. Files changed both locally and remotely are reported as conflicts and left alone unless overwrite is true.
     */
    data class PullPlan(val write: List<String>, val delete: List<String>, val conflicts: List<String>)

    fun planPull(base: Map<String, String>, remote: Map<String, String>, local: Map<String, String>, overwrite: Boolean): PullPlan {
        val write = ArrayList<String>()
        val delete = ArrayList<String>()
        val conflicts = ArrayList<String>()
        for ((p, rsha) in remote) {
            val l = local[p]
            if (l == rsha) continue
            val remoteChanged = base[p] != rsha
            val localChanged = l != base[p]
            if (!remoteChanged) continue // only our side changed: keep it
            if (l != null && localChanged) {
                conflicts.add(p)
                if (overwrite) write.add(p)
            } else write.add(p)
        }
        for ((p, bsha) in base) {
            if (remote.containsKey(p)) continue
            val l = local[p] ?: continue
            if (l == bsha) delete.add(p) else { conflicts.add(p); if (overwrite) delete.add(p) }
        }
        return PullPlan(write.sorted(), delete.sorted(), conflicts.sorted())
    }
}
