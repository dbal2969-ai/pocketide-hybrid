package dev.pocketide.app.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.sqrt

/**
 * Search by meaning (1.5.141, Cursor parity P3): an optional embeddings index of the project for find_code. Files are cut into the
 * same overlapping chunks as [RankedSearch]; each chunk is turned into a vector by an embeddings model the person picks (any
 * provider with an OpenAI-style /embeddings endpoint: Cloudflare's bge models, Gemini, OpenAI, Mistral, or Ollama on the phone).
 * A query is embedded the same way and the chunks closest to it are ranked; [fuse] merges that list with the keyword ranking
 * (reciprocal rank fusion), so exact names still win and plain-words questions find code that shares none of their words.
 * Only chunks of changed files are embedded again, the index is capped at [MAX_CHUNKS], and it is saved per project. With no
 * embeddings model set, or when a call fails, find_code stays keyword-only as before.
 */
class SemanticIndex {
    class Entry(val path: String, val start: Int, val end: Int, val vector: FloatArray)

    /** path to the hash of the text its chunks were embedded from. */
    private val hashes = HashMap<String, Int>()
    private val entries = ArrayList<Entry>()
    var model: String = ""
        private set

    val size: Int get() = entries.size

    /**
     * Brings the index up to date with [files], embedding only chunks of new or changed files with [embed] (texts in, one vector
     * each out), [BATCH] at a time. A different [model] starts the index again. Returns how many chunks were embedded.
     */
    @Synchronized
    fun update(files: Map<String, String>, model: String, embed: (List<String>) -> List<FloatArray>): Int {
        if (model != this.model) { hashes.clear(); entries.clear(); this.model = model }
        val gone = hashes.keys - files.keys
        if (gone.isNotEmpty()) { gone.forEach { hashes.remove(it) }; entries.removeAll { it.path in gone } }
        val todo = ArrayList<Triple<String, Int, Int>>()
        val texts = ArrayList<String>()
        for ((path, raw) in files.entries.sortedBy { it.key }) {
            if (raw.length > MAX_FILE_CHARS || !indexable(path) || secretContent(path, raw)) continue
            val h = raw.hashCode()
            if (hashes[path] == h) continue
            entries.removeAll { it.path == path }
            hashes.remove(path)
            // a file is indexed whole or not at all, and counts as indexed only then: files past the cap are tried again when
            // there is room (1.5.146; before, they were marked indexed with no chunks and never tried again)
            val cs = chunks(path, raw)
            if (entries.size + todo.size + cs.size > MAX_CHUNKS) continue
            for ((s, e, body) in cs) { todo.add(Triple(path, s, e)); texts.add(body) }
            hashes[path] = h
        }
        var done = 0
        for (from in texts.indices step BATCH) {
            val to = minOf(texts.size, from + BATCH)
            val vs = embed(texts.subList(from, to))
            require(vs.size == to - from) { "the embeddings model answered ${vs.size} vectors for ${to - from} texts" }
            for (i in from until to) { val (p, s, e) = todo[i]; entries.add(Entry(p, s, e, normalized(vs[i - from]))) }
            done += to - from
        }
        return done
    }

    /** The [limit] chunks closest in meaning to [queryVector], as hits with the lines of [files] they cover. */
    @Synchronized
    fun query(queryVector: FloatArray, files: Map<String, String>, limit: Int = 8): List<RankedSearch.Hit> {
        val q = normalized(queryVector)
        val scored = entries.filter { it.vector.size == q.size }.map { it to dot(it.vector, q) }.sortedByDescending { it.second }
        val taken = ArrayList<Pair<Entry, Double>>()
        for (pair in scored) {
            val c = pair.first
            if (taken.any { it.first.path == c.path && c.start <= it.first.end && it.first.start <= c.end }) continue
            taken.add(pair)
            if (taken.size >= limit) break
        }
        return taken.mapNotNull { (c, score) ->
            val lines = files[c.path]?.split('\n') ?: return@mapNotNull null
            val shown = (c.start..minOf(c.end, lines.size)).map { it to lines[it - 1].trim().take(160) }.filter { it.second.isNotEmpty() }.take(3)
            RankedSearch.Hit(c.path, c.start, c.end, score, shown)
        }
    }

    @Synchronized
    fun save(file: File) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { o ->
            o.writeInt(FORMAT); o.writeUTF(model)
            o.writeInt(hashes.size); for ((p, h) in hashes) { o.writeUTF(p); o.writeInt(h) }
            o.writeInt(entries.size)
            for (e in entries) { o.writeUTF(e.path); o.writeInt(e.start); o.writeInt(e.end); o.writeInt(e.vector.size); for (x in e.vector) o.writeFloat(x) }
        }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        const val MAX_CHUNKS = 4000
        const val MAX_FILE_CHARS = 200_000
        const val BATCH = 32
        /** Characters of a chunk sent to the model (embedding models read about 512 tokens). */
        const val CHUNK_CHARS = 2000
        private const val FORMAT = 1
        /** Reciprocal rank fusion's constant: higher flattens the difference between ranks. */
        private const val RRF_K = 60.0

        private val SKIP = Regex("""(^|/)(node_modules|\.git|build|dist|vendor|\.gradle)/|\.(min\.js|map|lock|png|jpe?g|gif|webp|ico|svg|woff2?|ttf|zip|jar|apk|so|bin)$""", RegexOption.IGNORE_CASE)

        /**
         * Files that hold secrets, never sent to the embeddings provider (1.5.146, Copilot's ANALYSIS-1.5.145 N1): .env files, keys
         * and certificates, keystores, SSH keys, credentials and secrets files, and the property files Android and package tools keep
         * tokens in. What is sent from the other files goes through [SecretMask] first.
         */
        private val SECRET_FILE = Regex(
            """(^|/)(\.env(\.[^/]*)?|[^/]*\.(pem|key|p12|pfx|jks|keystore|crt|cer|der|gpg|asc)|id_(rsa|dsa|ecdsa|ed25519)[^/]*|""" +
                // a secrets or credentials file in a config or data format, or with no extension; code such as tokenizer.js stays
                """[^/]*(secret|credential|password|passwd|token)[^/]*\.(json|ya?ml|txt|ini|toml|conf|cfg|properties|xml|plist|csv)|""" +
                """[^/.]*(secrets?|credentials?)|(local|github|signing|gradle)\.properties|\.(npmrc|pypirc|netrc|pgpass|htpasswd)|""" +
                """[^/]*\.tfstate([.][^/]*)?|""" +
                // 1.5.151 (Copilot, ANALYSIS-1.5.150 P1): Android signing files, cloud service accounts, Terraform variables, Apple keys,
                // git and Kubernetes credentials, password databases, VPN profiles, WordPress and Ansible secrets, app.env files
                """(key|keystore|release|upload)\.properties|application(-[^/]*)?\.properties|[^/]*service-?account[^/]*\.json|""" +
                """firebase-adminsdk[^/]*\.json|google-services\.json|GoogleService-Info\.plist|[^/]*\.tfvars(\.json)?|[^/]*\.(p8|kdbx|ovpn|keychain)|""" +
                """\.git-credentials|kubeconfig|wp-config\.php|vault\.ya?ml|\.vault_pass(\.txt)?|[^/]*\.env)$|""" +
                """(^|/)\.(ssh|aws|gnupg|docker|kube|azure|gcloud)/""", RegexOption.IGNORE_CASE)

        /** Config-format files: skipped whole when a line holds a password, secret or token (P1: a plain password has no key shape). */
        private val CONFIG = Regex("""\.(properties|ya?ml|json|ini|toml|conf|cfg|xml|plist|env)$""", RegexOption.IGNORE_CASE)
        private val SECRET_LINE = Regex("""(?im)^[\s{\[,]*["']?[\w.\-]*(password|passwd|pwd|secret|api[_\-]?key|apikey|token|private[_\-]?key|access[_\-]?key)[\w.\-]*["']?\s*[:=]\s*["']?(?!\$\{|\{\{|<|%|null\b|none\b|""|''|changeme\b)[^\s"',}]{3,}""")
        /** A quoted value given to a password- or secret-like name in code: the value is blanked, the code stays readable. */
        private val SECRET_ASSIGN = Regex("""(?i)((?:password|passwd|pwd|secret|api[_\-]?key|apikey|token|private[_\-]?key|access[_\-]?key)\w*["']?\s*[:=]\s*["'`])[^"'`\n]{3,}(["'`])""")

        /** Whether [text] of the config file [path] holds a secret value (then it is not indexed at all). */
        fun secretContent(path: String, text: String): Boolean = CONFIG.containsMatchIn(path) && SECRET_LINE.containsMatchIn(text)

        /** [text] with quoted secret values blanked, then the key shapes [SecretMask] knows. */
        fun masked(text: String): String = SecretMask.mask(SECRET_ASSIGN.replace(text) { m -> m.groupValues[1] + SecretMask.HIDDEN + m.groupValues[2] })

        fun secretFile(path: String): Boolean = SECRET_FILE.containsMatchIn(path)

        fun indexable(path: String): Boolean = !SKIP.containsMatchIn(path) && !secretFile(path)

        /** (first line, last line, text) for each chunk, the text starting with the path so the file's name counts too. */
        fun chunks(path: String, raw: String): List<Triple<Int, Int, String>> {
            val lines = raw.split('\n')
            val out = ArrayList<Triple<Int, Int, String>>()
            var s = 0
            while (true) {
                val e = minOf(lines.size, s + RankedSearch.CHUNK_LINES)
                val body = lines.subList(s, e).joinToString("\n")
                // keys and tokens in an ordinary file are masked before the text leaves the phone (N1)
                if (body.isNotBlank()) out.add(Triple(s + 1, e, masked(("$path\n$body").take(CHUNK_CHARS))))
                if (e >= lines.size) break
                s += RankedSearch.STEP
            }
            return out
        }

        /** The saved index, or a new empty one when there is none or it cannot be read. */
        fun load(file: File): SemanticIndex {
            val idx = SemanticIndex()
            if (!file.isFile) return idx
            try {
                DataInputStream(file.inputStream().buffered()).use { i ->
                    if (i.readInt() != FORMAT) return SemanticIndex()
                    idx.model = i.readUTF()
                    repeat(i.readInt()) { idx.hashes[i.readUTF()] = i.readInt() }
                    repeat(i.readInt()) {
                        val p = i.readUTF(); val s = i.readInt(); val e = i.readInt()
                        val v = FloatArray(i.readInt()) { i.readFloat() }
                        idx.entries.add(Entry(p, s, e, v))
                    }
                }
            } catch (e: Exception) { return SemanticIndex() }
            return idx
        }

        /** Merges two rankings (keyword and meaning) by reciprocal rank fusion; a stretch found by both comes first. */
        fun fuse(keyword: List<RankedSearch.Hit>, meaning: List<RankedSearch.Hit>, limit: Int = 8): List<RankedSearch.Hit> {
            class Slot(val hit: RankedSearch.Hit, var score: Double)
            val slots = ArrayList<Slot>()
            fun addAll(list: List<RankedSearch.Hit>) = list.forEachIndexed { rank, h ->
                val same = slots.firstOrNull { it.hit.path == h.path && h.start <= it.hit.end && it.hit.start <= h.end }
                val add = 1.0 / (RRF_K + rank + 1)
                if (same != null) same.score += add else slots.add(Slot(h, add))
            }
            addAll(keyword); addAll(meaning)
            return slots.sortedByDescending { it.score }.take(limit).map { RankedSearch.Hit(it.hit.path, it.hit.start, it.hit.end, it.score, it.hit.lines) }
        }

        fun normalized(v: FloatArray): FloatArray {
            var n = 0.0; for (x in v) n += x * x
            val len = sqrt(n).toFloat()
            return if (len == 0f) v.copyOf() else FloatArray(v.size) { v[it] / len }
        }

        private fun dot(a: FloatArray, b: FloatArray): Double { var s = 0.0; for (i in a.indices) s += a[i] * b[i]; return s }

        /** The body of an OpenAI-style /embeddings request. */
        fun requestBody(model: String, texts: List<String>): String = MiniJson.write(mapOf("model" to model, "input" to texts))

        /** The vectors of an /embeddings answer, in input order (by "index" when given). */
        @Suppress("UNCHECKED_CAST")
        fun parseVectors(json: String): List<FloatArray> {
            val root = MiniJson.parse(json) as? Map<String, Any?> ?: error("not an embeddings answer")
            val data = root["data"] as? List<Any?> ?: error((root["error"] as? Map<String, Any?>)?.get("message")?.toString() ?: "no data in the embeddings answer")
            return data.mapIndexed { i, d ->
                val m = d as Map<String, Any?>
                val idx = (m["index"] as? Number)?.toInt() ?: i
                idx to (m["embedding"] as List<Any?>).map { (it as Number).toFloat() }.toFloatArray()
            }.sortedBy { it.first }.map { it.second }
        }
    }
}
