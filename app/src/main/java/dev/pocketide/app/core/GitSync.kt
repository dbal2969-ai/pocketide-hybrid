package dev.pocketide.app.core

import java.io.File
import java.net.HttpURLConnection
import java.net.ProtocolException

// Pull, push, changes and revert against GitHub, without a git binary. Plain Java I/O only, so it is testable on a JVM.

class GitResponse(val code: Int, val body: ByteArray) {
    val text: String get() = String(body, Charsets.UTF_8)
}

class GitHubException(message: String, val code: Int = 0) : Exception(message)

/** One HTTP call to api.github.com. raw = ask for raw bytes (used for blobs). */
interface GitApi {
    fun call(method: String, path: String, body: String? = null, raw: Boolean = false): GitResponse
}

/** The real GitHub REST API over HttpURLConnection. [base] is changeable so tests can answer from a local fake server (1.5.125, /bench). */
class GitHubHttp(private val token: String, private val base: String = "https://api.github.com") : GitApi {
    override fun call(method: String, path: String, body: String?, raw: Boolean): GitResponse {
        val conn = java.net.URI("$base$path").toURL().openConnection() as HttpURLConnection
        try {
            try { conn.requestMethod = method } catch (e: ProtocolException) {
                // the desktop JVM has no PATCH; Android does. Fall back to an override header.
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-HTTP-Method-Override", method)
            }
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", if (raw) "application/vnd.github.raw" else "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.setRequestProperty("User-Agent", "PocketIDE")
            if (token.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return GitResponse(code, bytes)
        } finally {
            conn.disconnect()
        }
    }
}

/** The project folder as git sees it: every file, dot-files included, except the .git folder. */
class FolderFiles(val root: File) {
    val maxFile = 20L * 1024 * 1024

    fun list(): List<String> {
        val out = ArrayList<String>()
        fun walk(dir: File, prefix: String) {
            for (k in (dir.listFiles() ?: return).sortedBy { it.name }) {
                if (out.size >= 5000) return
                if (k.name == ".git") continue
                if (k.isDirectory) walk(k, prefix + k.name + "/") else if (k.isFile && k.length() <= maxFile) out.add(prefix + k.name)
            }
        }
        walk(root, "")
        return out.sorted()
    }

    private fun file(path: String): File? {
        if (!GitCore.pathOk(path)) return null
        val f = File(root, path).canonicalFile
        return if (f.path.startsWith(root.canonicalPath + File.separator)) f else null
    }

    fun read(path: String): ByteArray? = file(path)?.let { if (it.isFile) it.readBytes() else null }

    fun write(path: String, data: ByteArray): Boolean {
        val f = file(path) ?: return false
        return try { f.parentFile?.mkdirs(); f.writeBytes(data); true } catch (e: Exception) { false }
    }

    fun delete(path: String): Boolean {
        val f = file(path) ?: return false
        if (!f.isFile || !f.delete()) return false
        var dir = f.parentFile
        val top = root.canonicalFile
        while (dir != null && dir != top && dir.isDirectory && dir.list()?.isEmpty() == true) { dir.delete(); dir = dir.parentFile }
        return true
    }
}

data class PullOutcome(val applied: Boolean, val written: List<String>, val deleted: List<String>, val conflicts: List<String>, val commit: String)
data class PushOutcome(val commit: String?, val changes: List<FileChange>)

/**
 * Sync between the project folder and one branch of one repository.
 * stateDir keeps the last synced snapshot and a copy of each synced file, so Changes and Revert work offline.
 */
class GitSync(
    private val api: GitApi,
    private val files: FolderFiles,
    private val stateDir: File,
    private val repo: GitRepo,
    private val branch: String,
    private val progress: (String) -> Unit = {}
) {
    private val snapFile = File(stateDir, "snapshot.json")
    private val objects = File(stateDir, "objects")
    private val r = "/repos/${repo.owner}/${repo.name}"

    // ---- local state ----

    /** The last sync, if it was with this repository and branch. */
    fun snapshot(): SyncSnapshot? {
        val s = try { SyncSnapshot.fromJson(snapFile.readText()) } catch (e: Exception) { null } ?: return null
        return if (s.repo.equals(repo.identity, true) && s.branch == branch) s else null
    }

    private fun saveSnapshot(s: SyncSnapshot) {
        stateDir.mkdirs()
        val tmp = File(stateDir, "snapshot.json.tmp")
        tmp.writeText(s.toJson())
        if (!tmp.renameTo(snapFile)) { snapFile.delete(); tmp.renameTo(snapFile) }
        // drop cached copies nothing refers to any more
        val keep = s.files.values.toSet()
        objects.listFiles()?.forEach { if (it.name !in keep) it.delete() }
    }

    private fun cache(sha: String, bytes: ByteArray) {
        if (bytes.size > 5 * 1024 * 1024) return
        objects.mkdirs()
        val f = File(objects, sha)
        if (!f.exists()) f.writeBytes(bytes)
    }

    fun localShas(): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for (p in files.list()) files.read(p)?.let { m[p] = GitCore.blobSha(it) }
        return m
    }

    /** Local changes since the last sync, or null if this project has never been synced with this branch. */
    fun changes(): List<FileChange>? {
        val s = snapshot() ?: return null
        return Changes.compute(s.files, localShas())
    }

    /** The synced version of a file: from the local copy, else downloaded by its blob id. Null if it was not in the last sync. */
    fun baseBytes(path: String): ByteArray? {
        val sha = snapshot()?.files?.get(path) ?: return null
        val f = File(objects, sha)
        if (f.isFile) return f.readBytes()
        val bytes = blob(sha)
        cache(sha, bytes)
        return bytes
    }

    /** Puts a file back the way it was at the last sync (deletes it if it was added since). */
    fun revert(path: String): Boolean {
        val s = snapshot() ?: return false
        if (!s.files.containsKey(path)) return files.delete(path)
        val b = baseBytes(path) ?: return false
        return files.write(path, b)
    }

    // ---- remote ----

    private fun check(resp: GitResponse, what: String): GitResponse {
        if (resp.code !in 200..299) throw GitHubException(GitCore.errorMessage(resp.code, resp.text, what), resp.code)
        return resp
    }

    private fun blob(sha: String): ByteArray = check(api.call("GET", "$r/git/blobs/$sha", null, true), "download a file").body

    class Remote(val head: String, val tree: String, val files: List<GitCore.RemoteFile>) {
        val map: Map<String, String> get() = files.associate { it.path to it.sha }
    }

    /** Names of the repository's branches. */
    fun branches(): List<String> = GitBranches.names(check(api.call("GET", "$r/branches?per_page=100"), "list the branches").text)

    /** Makes branch [name] from the head of the current branch. */
    fun createBranch(name: String) {
        val ref = check(api.call("GET", "$r/git/ref/heads/${GitCore.encodePath(branch)}"), "read the branch")
        val sha = GitCore.refSha(ref.text) ?: throw GitHubException("GitHub sent an unexpected reply for the branch.")
        check(api.call("POST", "$r/git/refs", GitBranches.createBody(name, sha)), "create the branch")
    }

    /** The latest commits on the current branch. */
    fun history(): List<GitBranches.Entry> =
        GitBranches.log(check(api.call("GET", "$r/commits?sha=${GitCore.encodePath(branch)}&per_page=25"), "read the history").text)

    /** The branch head, its tree and files. Null if the repository is empty. */
    fun remote(): Remote? {
        progress("Reading $branch…")
        val ref = api.call("GET", "$r/git/ref/heads/${GitCore.encodePath(branch)}")
        if (ref.code == 409) return null
        if (ref.code == 404) {
            // tell a missing repository apart from a missing branch
            val repoResp = api.call("GET", r)
            if (repoResp.code == 404) throw GitHubException(GitCore.errorMessage(404, repoResp.text, "open ${repo.identity}"), 404)
            check(repoResp, "open ${repo.identity}")
            if ((MiniJson.obj(MiniJson.parse(repoResp.text))?.get("size") as? Double ?: 1.0) == 0.0) return null
            throw GitHubException("The branch $branch does not exist in ${repo.identity}.", 404)
        }
        check(ref, "read the branch")
        val head = GitCore.refSha(ref.text) ?: throw GitHubException("GitHub sent an unexpected reply for the branch.")
        val tree = GitCore.commitTree(check(api.call("GET", "$r/git/commits/$head"), "read the latest commit").text)
            ?: throw GitHubException("GitHub sent an unexpected reply for the commit.")
        val t = GitCore.parseTree(check(api.call("GET", "$r/git/trees/$tree?recursive=1"), "list the files").text)
        if (t.truncated) throw GitHubException("This repository has too many files for PocketIDE's sync (GitHub cut the file list short).")
        return Remote(head, tree, t.files)
    }

    // ---- pull ----

    /**
     * Brings GitHub's version in. Files changed both here and there are conflicts: without overwrite nothing is
     * written and the conflicts are returned so the user can decide. replace also deletes local files GitHub does not have.
     */
    fun pull(overwrite: Boolean, replace: Boolean): PullOutcome {
        val remote = remote() ?: throw GitHubException("${repo.identity} is empty. Push to start it.")
        val base = snapshot()?.files ?: emptyMap()
        val local = localShas()
        val rmap = remote.map
        val plan = Changes.planPull(base, rmap, local, overwrite || replace)
        if (plan.conflicts.isNotEmpty() && !overwrite && !replace) return PullOutcome(false, emptyList(), emptyList(), plan.conflicts, remote.head)
        val written = ArrayList<String>()
        val deleted = ArrayList<String>()
        val big = remote.files.filter { it.size > files.maxFile }.map { it.path }.toSet()
        for ((i, p) in plan.write.withIndex()) {
            if (p in big) continue
            progress("Downloading ${i + 1} of ${plan.write.size}: $p")
            val sha = rmap.getValue(p)
            val cached = File(objects, sha)
            val bytes = if (cached.isFile) cached.readBytes() else blob(sha)
            if (files.write(p, bytes)) { written.add(p); cache(sha, bytes) }
        }
        for (p in plan.delete) if (files.delete(p)) deleted.add(p)
        if (replace) for (p in local.keys) if (!rmap.containsKey(p) && files.delete(p)) deleted.add(p)
        // keep copies of files that already matched, so Revert works offline
        for ((p, sha) in rmap) if (local[p] == sha) files.read(p)?.let { cache(sha, it) }
        saveSnapshot(SyncSnapshot(repo.identity, branch, remote.head, rmap.filterKeys { it !in big }))
        return PullOutcome(true, written, deleted.distinct(), plan.conflicts, remote.head)
    }

    // ---- push ----

    /** Sends every local change as one commit. Refuses when GitHub moved on since the last sync, or was never pulled. */
    fun push(message: String): PushOutcome {
        var remote = remote()
        val snap = snapshot()
        if (remote == null) {
            remote = startEmptyRepo(message)
        } else {
            if (snap == null) throw GitHubException("Pull first, so PocketIDE knows what is already on GitHub. Pulling keeps your local files unless you choose Replace.")
            if (snap.commit != remote.head) throw GitHubException("GitHub has newer commits on $branch. Pull first, then push again.")
        }
        val local = localShas()
        val rmap = remote.map
        val modes = remote.files.associate { it.path to it.mode }
        val changes = Changes.compute(rmap, local)
        if (changes.isEmpty()) {
            saveSnapshot(SyncSnapshot(repo.identity, branch, remote.head, rmap))
            return PushOutcome(null, changes)
        }
        val entries = ArrayList<GitCore.TreeEntry>()
        val uploads = changes.filter { it.kind != ChangeKind.DELETED }
        for ((i, c) in uploads.withIndex()) {
            progress("Uploading ${i + 1} of ${uploads.size}: ${c.path}")
            val bytes = files.read(c.path) ?: continue
            val sha = GitCore.shaField(check(api.call("POST", "$r/git/blobs", GitCore.blobBody(bytes)), "upload ${c.path}").text)
                ?: local.getValue(c.path)
            cache(sha, bytes)
            entries.add(GitCore.TreeEntry(c.path, sha, modes[c.path] ?: "100644"))
        }
        for (c in changes.filter { it.kind == ChangeKind.DELETED }) entries.add(GitCore.TreeEntry(c.path, null, modes[c.path] ?: "100644"))
        progress("Creating the commit…")
        val tree = GitCore.shaField(check(api.call("POST", "$r/git/trees", GitCore.treeBody(remote.tree, entries)), "build the file tree").text)
            ?: throw GitHubException("GitHub sent an unexpected reply for the tree.")
        val commit = GitCore.shaField(check(api.call("POST", "$r/git/commits", GitCore.commitBody(message, tree, remote.head)), "create the commit").text)
            ?: throw GitHubException("GitHub sent an unexpected reply for the commit.")
        check(api.call("PATCH", "$r/git/refs/heads/${GitCore.encodePath(branch)}", GitCore.refBody(commit)), "move $branch to the new commit")
        val synced = LinkedHashMap(rmap)
        for (c in changes) if (c.kind == ChangeKind.DELETED) synced.remove(c.path) else synced[c.path] = local.getValue(c.path)
        for ((p, sha) in local) if (synced[p] == sha) files.read(p)?.let { cache(sha, it) }
        saveSnapshot(SyncSnapshot(repo.identity, branch, commit, synced))
        return PushOutcome(commit, changes)
    }

    /** GitHub's git API refuses to work on an empty repository, so the first file goes in through the contents API. */
    private fun startEmptyRepo(message: String): Remote {
        val local = files.list()
        val first = local.firstOrNull { it.equals("README.md", true) } ?: local.firstOrNull()
            ?: throw GitHubException("The project has no files to push.")
        progress("Starting the empty repository with $first…")
        val bytes = files.read(first) ?: throw GitHubException("Could not read $first.")
        check(api.call("PUT", "$r/contents/${GitCore.encodePath(first)}", GitCore.contentsBody(message, bytes, branch)), "add the first file")
        return remote() ?: throw GitHubException("GitHub did not create the first commit.")
    }
}
