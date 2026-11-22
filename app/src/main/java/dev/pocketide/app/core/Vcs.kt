package dev.pocketide.app.core

import java.io.File

/**
 * Version history for a project folder, the way GitHub shows it: commits, branches, merges, cherry-picks, reverts, stashes,
 * tags, blame and file history. It works on the project's own .git folder through [VcsStore] (no Git program or library), and
 * there is no staging area: a commit takes the changed files you pick. Pure JVM code; every call may take a while on a big
 * project, so the app runs them off the main thread.
 */
class Vcs(val root: File) {
    val store = VcsStore(root)

    companion object {
        val YOU = VcsStore.Person("You", "you@pocketide.local", 0)
        val ASSISTANT = VcsStore.Person("PocketIDE assistant", "assistant@pocketide.local", 0)
        const val MAX_TEXT = 2_000_000
    }

    fun enabled(): Boolean = store.exists()

    private fun person(p: VcsStore.Person) = p.copy(time = System.currentTimeMillis() / 1000, zone = zone())
    private fun zone(): String {
        val off = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000
        return (if (off < 0) "-" else "+") + "%02d%02d".format(Math.abs(off) / 60, Math.abs(off) % 60)
    }

    // ---------------------------------------------------------------- the working folder

    class Change(val path: String, val kind: Char) { override fun toString() = "$kind $path" }

    /** Every tracked-or-trackable file in the folder with its blob id (unchanged files are not read again: the index keeps their size and time). */
    fun scan(): Map<String, VcsWork.IndexEntry> {
        val ignore = VcsWork.Ignore(root)
        val lfs = VcsWork.lfsPatterns(root)
        val known = VcsWork.readIndex(store)
        val head = headFiles()
        val out = LinkedHashMap<String, VcsWork.IndexEntry>()
        fun walk(dir: File, prefix: String) {
            val kids = dir.listFiles() ?: return
            for (k in kids.sortedBy { it.name }) {
                val rel = prefix + k.name
                if (k.isDirectory) { if (!ignore.ignored(rel, true)) walk(k, "$rel/") ; continue }
                if (ignore.ignored(rel, false) && rel !in head) continue
                val size = k.length(); val time = k.lastModified()
                val prev = known[rel]
                val id = if (prev != null && prev.size == size && prev.mtime == time) prev.id else blobId(k, rel, lfs)
                out[rel] = VcsWork.IndexEntry(rel, id, head[rel]?.first ?: "100644", size, time)
            }
        }
        walk(root, "")
        return out
    }

    private fun isLarge(f: File, rel: String, lfs: List<Regex>) = f.length() >= VcsWork.LARGE || lfs.any { it.matches(rel) }

    private fun blobId(f: File, rel: String, lfs: List<Regex>): String =
        if (isLarge(f, rel, lfs)) store.id("blob", "version https://git-lfs.github.com/spec/v1\noid sha256:${VcsWork.sha256(f)}\nsize ${f.length()}\n".toByteArray())
        else store.id("blob", f.readBytes())

    /** Writes the file's blob (or, for a large file, its content to the large-file store and the pointer as the blob). */
    private fun storeBlob(rel: String, lfs: List<Regex>): String {
        val f = File(root, rel)
        return if (isLarge(f, rel, lfs)) store.write("blob", VcsWork.lfsStore(store, f)) else store.write("blob", f.readBytes())
    }

    fun headFiles(): Map<String, Pair<String, String>> = filesAt(store.headId())

    fun filesAt(commit: String?): Map<String, Pair<String, String>> = commit?.let { store.readCommit(it) }?.let { store.flatten(it.tree) } ?: emptyMap()

    /** Files changed since the last commit: A added, M changed, D deleted. */
    fun status(scanned: Map<String, VcsWork.IndexEntry> = scan()): List<Change> {
        val head = headFiles(); val out = ArrayList<Change>()
        for ((p, e) in scanned) { val h = head[p]; if (h == null) out.add(Change(p, 'A')) else if (h.second != e.id) out.add(Change(p, 'M')) }
        for (p in head.keys) if (p !in scanned) out.add(Change(p, 'D'))
        return out.sortedBy { it.path }
    }

    // ---------------------------------------------------------------- commits

    /** Starts the history: a .gitignore for build output, then a first commit of everything. */
    fun start(author: VcsStore.Person = YOU, branch: String = "main"): String? {
        if (enabled()) return store.headId()
        store.init(branch)
        val gi = File(root, ".gitignore")
        if (!gi.exists()) gi.writeText("# Kept out of the history (PocketIDE's defaults)\n" + VcsWork.DEFAULT_IGNORE.joinToString("\n") + "\n")
        return commit("Start of history", null, author)
    }

    /**
     * Commits [paths] (null: every change) and returns the new commit id, or null when there was nothing to commit.
     * A merge in progress becomes a merge commit (two parents).
     */
    fun commit(message: String, paths: Collection<String>?, author: VcsStore.Person = YOU): String? {
        val scanned = scan()
        val lfs = VcsWork.lfsPatterns(root)
        val head = store.headId()
        val files = LinkedHashMap(filesAt(head))
        val pick = paths?.toSet() ?: status(scanned).map { it.path }.toSet()
        for (p in pick) {
            val e = scanned[p]
            if (e == null) files.remove(p) else files[p] = e.mode to (if (store.has(e.id)) e.id else storeBlob(p, lfs))
        }
        val tree = store.writeFlat(files)
        val mergeHead = File(store.dir, "MERGE_HEAD").takeIf { it.isFile }?.readText()?.trim()
        if (head != null && mergeHead == null && store.readCommit(head)?.tree == tree) return null
        val parents = listOfNotNull(head, mergeHead)
        val who = person(author)
        val id = store.writeCommit(tree, parents, who, who, message.trim().ifEmpty { "Update" })
        moveHead(id)
        File(store.dir, "MERGE_HEAD").delete(); File(store.dir, "MERGE_MSG").delete()
        writeIndex(files, scanned)
        return id
    }

    private fun moveHead(id: String) { val b = store.headBranch(); if (b != null) store.setRef("refs/heads/$b", id) else File(store.dir, "HEAD").writeText(id + "\n") }

    private fun writeIndex(files: Map<String, Pair<String, String>>, scanned: Map<String, VcsWork.IndexEntry> = emptyMap()) {
        VcsWork.writeIndex(store, files.map { (p, v) ->
            val s = scanned[p]; val f = File(root, p)
            // unverified files get no size or time, so the next scan reads them again instead of trusting a stale id
            if (s != null && s.id == v.second) s.copy(mode = v.first) else VcsWork.IndexEntry(p, v.second, v.first, 0, 0)
        })
    }

    class LogEntry(val commit: VcsStore.Commit, val refs: List<String>)

    /** Commits reachable from [from] (default HEAD), newest first. */
    fun log(from: String? = null, limit: Int = 200, path: String? = null): List<VcsStore.Commit> {
        val start = (from?.let { store.resolve(it) } ?: store.headId()) ?: return emptyList()
        val seen = HashSet<String>(); val queue = java.util.PriorityQueue<VcsStore.Commit>(compareByDescending { it.committer.time })
        store.readCommit(start)?.let { queue.add(it); seen.add(it.id) }
        val out = ArrayList<VcsStore.Commit>()
        while (queue.isNotEmpty() && out.size < limit) {
            val c = queue.poll()
            if (path == null || touches(c, path)) out.add(c)
            for (p in c.parents) if (seen.add(p)) store.readCommit(p)?.let { queue.add(it) }
        }
        return out
    }

    private fun touches(c: VcsStore.Commit, path: String): Boolean {
        val now = blobAt(c.id, path)
        val before = c.parents.firstOrNull()?.let { blobAt(it, path) }
        return now != before
    }

    /** The blob id of [path] at [commit], walking only the folders on its path. */
    fun blobAt(commit: String, path: String): String? {
        var tree = store.readCommit(commit)?.tree ?: return null
        val parts = path.split('/')
        for ((i, part) in parts.withIndex()) {
            val e = store.readTree(tree).firstOrNull { it.name == part } ?: return null
            if (i == parts.size - 1) return if (e.isTree) null else e.id
            if (!e.isTree) return null
            tree = e.id
        }
        return null
    }

    /** A file's content at a commit (large files: their real content when it is stored here). */
    fun contentAt(commit: String, path: String): ByteArray? = blobAt(commit, path)?.let { content(it) }

    fun content(blob: String): ByteArray? {
        val d = store.readBlob(blob) ?: return null
        VcsWork.lfsPointer(d)?.let { (oid, _) -> VcsWork.lfsFile(store, oid).takeIf { it.isFile }?.let { return it.readBytes() } }
        return d
    }

    fun isBinary(d: ByteArray): Boolean = d.take(8000).any { it.toInt() == 0 } || VcsWork.lfsPointer(d) != null

    class FileChange(val path: String, val kind: Char, val before: String?, val after: String?, val added: Int, val removed: Int)

    /** What a commit changed against its first parent (or [against]). */
    fun changes(commit: String, against: String? = store.readCommit(commit)?.parents?.firstOrNull()): List<FileChange> = compare(filesAt(against), filesAt(commit))

    fun compare(a: Map<String, Pair<String, String>>, b: Map<String, Pair<String, String>>): List<FileChange> {
        val out = ArrayList<FileChange>()
        for (p in (a.keys + b.keys).toSortedSet()) {
            val x = a[p]?.second; val y = b[p]?.second
            if (x == y) continue
            val (add, rem) = lineStat(x, y)
            out.add(FileChange(p, if (x == null) 'A' else if (y == null) 'D' else 'M', x, y, add, rem))
        }
        return out
    }

    private fun text(blob: String?): String? {
        if (blob == null) return ""
        val d = store.readBlob(blob) ?: return null
        if (d.size > MAX_TEXT || isBinary(d)) return null
        return String(d, Charsets.UTF_8)
    }

    private fun lineStat(x: String?, y: String?): Pair<Int, Int> {
        val a = text(x); val b = text(y)
        if (a == null || b == null) return 0 to 0
        return VcsText.stat(a, b)
    }

    /** Diff rows between two blobs (null = no file); null when either side is binary or too big. */
    fun diff(before: String?, after: String?): List<DiffOp>? {
        val a = text(before) ?: return null; val b = text(after) ?: return null
        return VcsText.diff(VcsText.lines(a), VcsText.lines(b))
    }

    /** Diff of a working file against the last commit. */
    fun diffWorking(path: String): List<DiffOp>? {
        val f = File(root, path)
        val now = if (f.isFile) f.readBytes() else ByteArray(0)
        if (now.size > MAX_TEXT || isBinary(now)) return null
        val old = text(headFiles()[path]?.second) ?: return null
        return VcsText.diff(VcsText.lines(old), VcsText.lines(String(now, Charsets.UTF_8)))
    }

    // ---------------------------------------------------------------- branches

    fun branch(): String? = store.headBranch()
    fun branches(): Map<String, String> = store.refs("refs/heads/")

    fun createBranch(name: String, at: String? = null): String {
        require(validName(name)) { "A branch name uses letters, numbers, - _ . and /, with no spaces." }
        require(store.resolve("refs/heads/$name") == null) { "There is already a branch called $name." }
        val id = (at?.let { store.resolve(it) } ?: store.headId()) ?: throw IllegalStateException("Make a first commit before branching.")
        store.setRef("refs/heads/$name", id)
        return id
    }

    fun validName(n: String) = Regex("""^(?!/)(?!.*//)(?!.*\.\.)[A-Za-z0-9._/-]{1,100}(?<![/.])$""").matches(n) && !n.endsWith(".lock")

    fun deleteBranch(name: String) {
        require(name != branch()) { "Switch to another branch before deleting $name." }
        store.deleteRef("refs/heads/$name")
    }

    fun renameBranch(old: String, new: String) {
        require(validName(new)) { "A branch name uses letters, numbers, - _ . and /, with no spaces." }
        val id = store.resolve("refs/heads/$old") ?: throw IllegalArgumentException("No branch called $old.")
        require(store.resolve("refs/heads/$new") == null) { "There is already a branch called $new." }
        store.setRef("refs/heads/$new", id); store.deleteRef("refs/heads/$old")
        if (branch() == old) store.setHead(new)
    }

    enum class Carry { CARRY, STASH, DISCARD }

    /**
     * Switches to [name]. Uncommitted changes are carried over when the two branches agree on those files ([Carry.CARRY]),
     * put in a stash first, or thrown away. Returns a message for the person.
     */
    fun switchTo(name: String, carry: Carry = Carry.CARRY): String {
        val target = store.resolve("refs/heads/$name") ?: throw IllegalArgumentException("No branch called $name.")
        val scanned = scan(); val dirty = status(scanned)
        val head = headFiles(); val next = filesAt(target)
        var note = ""
        if (dirty.isNotEmpty()) when (carry) {
            Carry.CARRY -> {
                val clash = dirty.filter { head[it.path]?.second != next[it.path]?.second }
                require(clash.isEmpty()) { "Your changes to ${clash.take(5).joinToString { it.path }} would be overwritten: commit them, or stash them first." }
                note = " Your ${dirty.size} uncommitted change${if (dirty.size == 1) "" else "s"} came along."
            }
            Carry.STASH -> { stash("Before switching to $name"); note = " Your changes were stashed." }
            Carry.DISCARD -> {
                materialize(head, head, force = true)
                for (c in dirty) if (c.kind == 'A') { File(root, c.path).delete(); pruneEmpty(File(root, c.path).parentFile) }
                note = " Your uncommitted changes were thrown away."
            }
        }
        val keep = if (carry == Carry.CARRY) dirty.map { it.path }.toSet() else emptySet()
        materialize(if (carry == Carry.CARRY) head else headFiles(), next, skip = keep)
        store.setHead(name)
        writeIndex(next, scan().filterKeys { it !in keep })
        return "Switched to $name.$note"
    }

    /**
     * Makes the working folder hold [to] where it held [from]: files only in [from] are deleted, files that differ are written.
     * [skip] are left alone; [force] rewrites every file of [to].
     */
    private fun materialize(from: Map<String, Pair<String, String>>, to: Map<String, Pair<String, String>>, skip: Set<String> = emptySet(), force: Boolean = false) {
        for (p in from.keys) if (p !in to && p !in skip) { File(root, p).delete(); pruneEmpty(File(root, p).parentFile) }
        val now = if (force) emptyMap() else scan()
        for ((p, v) in to) {
            if (p in skip) continue
            if (!force && now[p]?.id == v.second) continue
            writeBlobTo(p, v.second)
        }
    }

    private fun writeBlobTo(p: String, blob: String) {
        val f = File(root, p); f.parentFile?.mkdirs()
        val d = store.readBlob(blob) ?: return
        val ptr = VcsWork.lfsPointer(d)
        val src = ptr?.let { VcsWork.lfsFile(store, it.first) }
        if (src != null && src.isFile) src.copyTo(f, true) else f.writeBytes(d)
    }

    private fun pruneEmpty(d: File?) {
        var dir = d
        while (dir != null && dir != root && dir.isDirectory && (dir.list()?.isEmpty() == true)) { val up = dir.parentFile; dir.delete(); dir = up }
    }

    // ---------------------------------------------------------------- merges

    fun mergeBase(a: String, b: String): String? {
        val ancA = HashSet<String>(); val q = ArrayDeque(listOf(a))
        while (q.isNotEmpty()) { val c = q.removeFirst(); if (ancA.add(c)) store.readCommit(c)?.parents?.forEach { q.addLast(it) } }
        val seen = HashSet<String>(); val pq = java.util.PriorityQueue<VcsStore.Commit>(compareByDescending { it.committer.time })
        store.readCommit(b)?.let { pq.add(it) }
        while (pq.isNotEmpty()) {
            val c = pq.poll(); if (!seen.add(c.id)) continue
            if (c.id in ancA) return c.id
            c.parents.forEach { p -> store.readCommit(p)?.let { pq.add(it) } }
        }
        return null
    }

    fun isAncestor(a: String, b: String): Boolean = mergeBase(a, b) == a

    class MergeResult(val message: String, val commit: String?, val conflicts: List<String>, val fastForward: Boolean = false, val upToDate: Boolean = false)

    /** Merges branch (or commit) [other] into the current branch. */
    fun merge(other: String, author: VcsStore.Person = YOU, message: String? = null): MergeResult {
        val theirs = store.resolve(other) ?: throw IllegalArgumentException("Nothing called $other.")
        val ours = store.headId() ?: throw IllegalStateException("Make a first commit first.")
        require(status().isEmpty()) { "Commit or stash your changes before merging." }
        val base = mergeBase(ours, theirs)
        if (base == theirs) return MergeResult("Already up to date with $other.", null, emptyList(), upToDate = true)
        if (base == ours) {
            val to = filesAt(theirs); materialize(headFiles(), to); moveHead(theirs); writeIndex(to)
            return MergeResult("Fast-forwarded to $other.", theirs, emptyList(), fastForward = true)
        }
        val conflicts = threeWay(filesAt(base), headFiles(), filesAt(theirs), branch() ?: "yours", other)
        File(store.dir, "MERGE_HEAD").writeText(theirs + "\n")
        val msg = message ?: "Merge $other into ${branch() ?: "HEAD"}"
        File(store.dir, "MERGE_MSG").writeText(msg + "\n")
        if (conflicts.isNotEmpty()) return MergeResult("${conflicts.size} file${if (conflicts.size == 1) "" else "s"} changed on both sides: resolve them, then commit.", null, conflicts)
        val id = commit(msg, null, author)
        return MergeResult("Merged $other.", id, emptyList())
    }

    fun merging(): Boolean = File(store.dir, "MERGE_HEAD").isFile
    fun mergeMessage(): String = File(store.dir, "MERGE_MSG").takeIf { it.isFile }?.readText()?.trim().orEmpty()

    fun abortMerge() {
        val head = headFiles()
        materialize(head, head, force = true)
        for (p in status().filter { it.kind == 'A' }) File(root, p.path).delete()
        File(store.dir, "MERGE_HEAD").delete(); File(store.dir, "MERGE_MSG").delete()
        writeIndex(head)
    }

    /** Files that still hold conflict markers. */
    fun unresolved(): List<String> = status().filter { it.kind != 'D' }.map { it.path }.filter { p ->
        val f = File(root, p); f.isFile && f.length() < MAX_TEXT && VcsText.hasConflictMarkers(f.readText())
    }

    /**
     * Applies the change from [base] to [theirs] onto the working folder (which holds [ours]); returns the files with conflicts.
     * Writes merged files; conflicts are left with git's markers.
     */
    private fun threeWay(base: Map<String, Pair<String, String>>, ours: Map<String, Pair<String, String>>, theirs: Map<String, Pair<String, String>>, oursName: String, theirsName: String): List<String> {
        val conflicts = ArrayList<String>()
        for (p in (base.keys + ours.keys + theirs.keys).toSortedSet()) {
            val b = base[p]?.second; val o = ours[p]?.second; val t = theirs[p]?.second
            if (o == t || b == t) continue                    // nothing new from their side
            if (b == o) {                                     // only their side changed it
                if (t == null) { File(root, p).delete(); pruneEmpty(File(root, p).parentFile) } else writeBlobTo(p, t)
                continue
            }
            // both changed it
            if (o == null || t == null) { conflicts.add(p); if (o == null && t != null) writeBlobTo(p, t); continue }
            val bt = text(b) ?: ""; val ot = text(o); val tt = text(t)
            if (ot == null || tt == null) { conflicts.add(p); continue }     // binary: ours stays, marked as a conflict
            val m = VcsText.merge3(bt, ot, tt, oursName, theirsName)
            File(root, p).also { it.parentFile?.mkdirs() }.writeText(m.text)
            if (m.conflicts > 0) conflicts.add(p)
        }
        return conflicts
    }

    /** Copies one commit's change onto the current branch. */
    fun cherryPick(commit: String, author: VcsStore.Person = YOU): MergeResult {
        val c = store.readCommit(store.resolve(commit) ?: commit) ?: throw IllegalArgumentException("No commit $commit.")
        require(status().isEmpty()) { "Commit or stash your changes first." }
        val conflicts = threeWay(filesAt(c.parents.firstOrNull()), headFiles(), filesAt(c.id), branch() ?: "yours", c.short)
        if (conflicts.isNotEmpty()) return MergeResult("Copying ${c.short} clashes in ${conflicts.size} file(s): resolve them, then commit.", null, conflicts)
        val id = commit(c.message.trim() + "\n\n(cherry picked from commit ${c.id})", null, author)
        return MergeResult(if (id == null) "That change is already here." else "Copied ${c.short} onto ${branch()}.", id, emptyList())
    }

    /** Undoes one commit with a new commit, keeping everything after it. */
    fun revert(commit: String, author: VcsStore.Person = YOU): MergeResult {
        val c = store.readCommit(store.resolve(commit) ?: commit) ?: throw IllegalArgumentException("No commit $commit.")
        require(status().isEmpty()) { "Commit or stash your changes first." }
        val conflicts = threeWay(filesAt(c.id), headFiles(), filesAt(c.parents.firstOrNull()), branch() ?: "yours", "before ${c.short}")
        if (conflicts.isNotEmpty()) return MergeResult("Undoing ${c.short} clashes with later changes in ${conflicts.size} file(s): resolve them, then commit.", null, conflicts)
        val id = commit("Revert \"${c.title}\"\n\nThis reverts commit ${c.id}.", null, author)
        return MergeResult(if (id == null) "Nothing to undo." else "Undid ${c.short}.", id, emptyList())
    }

    /** Puts one file back as it was at [commit] (deletes it when it did not exist then). Not committed. */
    fun restoreFile(path: String, commit: String) {
        val b = blobAt(commit, path)
        if (b == null) File(root, path).delete() else writeBlobTo(path, b)
    }

    /** Makes the whole folder look like [commit] (the branch stays where it is; the difference shows as changes to commit). */
    fun restoreAll(commit: String) {
        val to = filesAt(commit)
        val now = scan()
        for (p in now.keys) if (p !in to) { File(root, p).delete(); pruneEmpty(File(root, p).parentFile) }
        for ((p, v) in to) if (now[p]?.id != v.second) writeBlobTo(p, v.second)
    }

    // ---------------------------------------------------------------- stash

    class Stash(val id: String, val message: String, val branch: String, val time: Long)

    private val stashFile get() = File(store.dir, "pocketide/stash.txt")

    fun stashes(): List<Stash> = if (!stashFile.isFile) emptyList() else stashFile.readLines().mapNotNull { l ->
        val p = l.split('\t'); if (p.size < 4) null else Stash(p[0], p[3], p[1], p[2].toLongOrNull() ?: 0)
    }

    /** Sets uncommitted changes aside (the folder goes back to the last commit). Null when there was nothing to set aside. */
    fun stash(message: String = "Changes set aside"): Stash? {
        val scanned = scan(); val dirty = status(scanned); if (dirty.isEmpty()) return null
        val head = store.headId() ?: throw IllegalStateException("Make a first commit first.")
        val lfs = VcsWork.lfsPatterns(root)
        val files = LinkedHashMap(headFiles())
        for (c in dirty) { val e = scanned[c.path]; if (e == null) files.remove(c.path) else files[c.path] = e.mode to (if (store.has(e.id)) e.id else storeBlob(c.path, lfs)) }
        val who = person(YOU)
        val id = store.writeCommit(store.writeFlat(files), listOf(head), who, who, message)
        val s = Stash(id, message.replace('\t', ' ').replace('\n', ' '), branch() ?: "", System.currentTimeMillis())
        stashFile.parentFile!!.mkdirs(); stashFile.appendText("${s.id}\t${s.branch}\t${s.time}\t${s.message}\n")
        store.setRef("refs/stash", id)
        val headNow = headFiles()
        for (c in dirty) if (c.kind == 'A') { File(root, c.path).delete(); pruneEmpty(File(root, c.path).parentFile) } else headNow[c.path]?.let { writeBlobTo(c.path, it.second) }
        writeIndex(headNow)
        return s
    }

    /** Brings a stash back onto the folder and drops it (kept when it clashes). */
    fun unstash(id: String): MergeResult {
        val s = stashes().firstOrNull { it.id == id } ?: throw IllegalArgumentException("No such stash.")
        require(status().isEmpty()) { "Commit or stash your current changes first." }
        val c = store.readCommit(s.id)!!
        val conflicts = threeWay(filesAt(c.parents.firstOrNull()), headFiles(), filesAt(c.id), branch() ?: "yours", "stash")
        if (conflicts.isEmpty()) dropStash(id)
        return MergeResult(if (conflicts.isEmpty()) "Brought back: ${s.message}." else "Brought back with clashes in ${conflicts.size} file(s); the stash is kept.", null, conflicts)
    }

    fun dropStash(id: String) {
        val left = stashes().filter { it.id != id }
        stashFile.writeText(left.joinToString("") { "${it.id}\t${it.branch}\t${it.time}\t${it.message}\n" })
        if (left.isEmpty()) store.deleteRef("refs/stash") else store.setRef("refs/stash", left.last().id)
    }

    // ---------------------------------------------------------------- forks and other projects

    /** The project this one was forked from (its folder), or null. */
    fun upstream(): File? = File(store.dir, "pocketide/upstream").takeIf { it.isFile }?.readText()?.trim()?.let { File(it) }?.takeIf { File(it, ".git/HEAD").isFile }

    fun setUpstream(dir: File) { File(store.dir, "pocketide").mkdirs(); File(store.dir, "pocketide/upstream").writeText(dir.absolutePath + "\n") }

    /**
     * Copies the history of [src]'s branches that this project lacks (commits, folders, files, large files) and records them
     * as refs/remotes/[remote]/<branch>. Returns those branches with their commit ids.
     */
    fun fetchFrom(src: File, remote: String): Map<String, String> {
        val from = VcsStore(src)
        require(from.exists()) { "${src.name} keeps no history." }
        val heads = from.refs("refs/heads/")
        val seen = HashSet<String>(); val stack = ArrayDeque<String>(heads.values)
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (!seen.add(id) || store.has(id)) continue
            val o = from.read(id) ?: continue
            when (o.type) {
                "commit" -> from.readCommit(id)?.let { c -> stack.addLast(c.tree); c.parents.forEach { stack.addLast(it) } }
                "tree" -> from.readTree(id).forEach { if (it.mode != "160000") stack.addLast(it.id) }
                "blob" -> VcsWork.lfsPointer(o.data)?.let { (oid, _) ->
                    val f = VcsWork.lfsFile(from, oid); val dest = VcsWork.lfsFile(store, oid)
                    if (f.isFile && !dest.isFile) { dest.parentFile!!.mkdirs(); try { java.nio.file.Files.createLink(dest.toPath(), f.toPath()) } catch (e: Throwable) { f.copyTo(dest, true) } }
                }
            }
            store.write(o.type, o.data)
        }
        val base = "refs/remotes/$remote/"
        for (old in store.refs(base).keys) if (old !in heads) store.deleteRef(base + old)
        for ((b, id) in heads) store.setRef(base + b, id)
        return heads
    }

    /** Commits on [head] that [base] does not have, newest first. */
    fun ahead(base: String, head: String, limit: Int = 200): List<VcsStore.Commit> {
        val b = store.resolve(base) ?: return emptyList(); val h = store.resolve(head) ?: return emptyList()
        val inBase = HashSet<String>(); val q = ArrayDeque(listOf(b))
        while (q.isNotEmpty()) { val c = q.removeFirst(); if (inBase.add(c)) store.readCommit(c)?.parents?.forEach { q.addLast(it) } }
        return log(h, limit * 4).filter { it.id !in inBase }.take(limit)
    }

    /** What [head] changed since it split from [base] (GitHub's "Files changed"). */
    fun compareBranches(base: String, head: String): List<FileChange> {
        val b = store.resolve(base) ?: return emptyList(); val h = store.resolve(head) ?: return emptyList()
        return compare(filesAt(mergeBase(b, h)), filesAt(h))
    }

    /** Files that would clash if [head] were merged into [base] (changed differently on both sides since they split). */
    fun clashes(base: String, head: String): List<String> {
        val b = store.resolve(base) ?: return emptyList(); val h = store.resolve(head) ?: return emptyList()
        val mb = mergeBase(b, h) ?: return emptyList()
        val fb = filesAt(mb); val fo = filesAt(b); val ft = filesAt(h); val out = ArrayList<String>()
        for (p in (fb.keys + fo.keys + ft.keys).toSortedSet()) {
            val x = fb[p]?.second; val o = fo[p]?.second; val t = ft[p]?.second
            if (o == t || x == t || x == o) continue
            if (o == null || t == null) { out.add(p); continue }
            val bt = text(x) ?: ""; val ot = text(o); val tt = text(t)
            if (ot == null || tt == null || VcsText.merge3(bt, ot, tt).conflicts > 0) out.add(p)
        }
        return out
    }

    /** Merges [other] as one new commit on the current branch (GitHub's "Squash and merge"). */
    fun squashMerge(other: String, message: String, author: VcsStore.Person = YOU): MergeResult {
        val theirs = store.resolve(other) ?: throw IllegalArgumentException("Nothing called $other.")
        val ours = store.headId() ?: throw IllegalStateException("Make a first commit first.")
        require(status().isEmpty()) { "Commit or stash your changes first." }
        val base = mergeBase(ours, theirs)
        if (base == theirs) return MergeResult("Already up to date.", null, emptyList(), upToDate = true)
        val conflicts = threeWay(filesAt(base), headFiles(), filesAt(theirs), branch() ?: "yours", other)
        if (conflicts.isNotEmpty()) return MergeResult("${conflicts.size} file(s) clash: resolve them, then commit.", null, conflicts)
        val id = commit(message, null, author)
        return MergeResult(if (id == null) "Nothing to merge." else "Squashed and merged $other.", id, emptyList())
    }

    // ---------------------------------------------------------------- tags, blame, file history

    fun tags(): Map<String, String> = store.refs("refs/tags/")

    fun tag(name: String, at: String? = null, message: String = "", author: VcsStore.Person = YOU): String {
        require(validName(name)) { "A tag name uses letters, numbers, - _ . and /, with no spaces." }
        require(store.resolve("refs/tags/$name") == null) { "There is already a tag called $name." }
        val target = (at?.let { store.resolve(it) } ?: store.headId()) ?: throw IllegalStateException("Make a first commit first.")
        val id = if (message.isBlank()) target else store.writeTag(name, target, person(author), message)
        store.setRef("refs/tags/$name", id)
        return target
    }

    /** For each line of [path] at [at]: the commit that last changed it. */
    fun blame(path: String, at: String? = null, maxCommits: Int = 400): List<Pair<String, VcsStore.Commit?>> {
        val start = (at?.let { store.resolve(it) } ?: store.headId()) ?: return emptyList()
        val text = text(blobAt(start, path) ?: return emptyList()) ?: return emptyList()
        val lines = VcsText.lines(text)
        val owner = arrayOfNulls<VcsStore.Commit>(lines.size)
        var map = IntArray(lines.size) { it }            // line in the start version -> line in the current commit's version (-1 = settled)
        var cur = store.readCommit(start) ?: return lines.map { it to null }
        var steps = 0
        while (steps++ < maxCommits) {
            val parent = cur.parents.firstOrNull()?.let { store.readCommit(it) }
            val curBlob = blobAt(cur.id, path)
            val parBlob = parent?.let { blobAt(it.id, path) }
            if (parent == null || parBlob == null) { for (k in lines.indices) if (map[k] >= 0) owner[k] = cur; break }
            if (parBlob != curBlob) {
                val a = VcsText.lines(text(parBlob) ?: ""); val b = VcsText.lines(text(curBlob) ?: "")
                val back = IntArray(b.size) { -1 }; for ((i, j) in VcsText.matches(a, b)) back[j] = i
                map = IntArray(lines.size) { k -> val m = map[k]; if (m < 0) -1 else { val pm = back.getOrElse(m) { -1 }; if (pm < 0) { owner[k] = cur; -1 } else pm } }
            }
            if (map.all { it < 0 }) break
            cur = parent
        }
        return lines.mapIndexed { k, l -> l to owner[k] }
    }

    /** The commits that changed [path], newest first. */
    fun fileHistory(path: String, limit: Int = 100): List<VcsStore.Commit> = log(null, limit, path)
}
