package dev.pocketide.app.core


/**
 * v0.4 W3: works out how to run a project from the files in it (the ▶ button).
 * Pure logic, no Android imports, so both editions share it and it is JVM-tested.
 *
 * Commands are shell lines run in the project folder (/work inside Linux). Everything except
 * the HTML preview and shell scripts needs the Linux environment (GPL edition); the MIT edition
 * passes canUseLinux = false and those targets are shown as unavailable with a reason.
 */
enum class RunKind {
    /** Show the project's HTML in the Preview tab. */
    PREVIEW,
    /** A command that finishes: runs in the Terminal tab and feeds the Problems list. */
    COMMAND,
    /** A long-running server: runs in the background and opens in the Preview tab when it answers. */
    SERVER
}

data class RunTarget(
    val id: String,
    val label: String,
    val command: String,
    val kind: RunKind,
    /** ToolCatalog id the command needs (python, node, go, rust, cc, java), or null. */
    val toolId: String? = null,
    val needsLinux: Boolean = true,
    val custom: Boolean = false,
    /** Where a SERVER is expected to answer, e.g. http://127.0.0.1:3000 (a guess until the log says otherwise). */
    val url: String? = null,
    /** How long a COMMAND may run (null: the usual limit). */
    val timeoutSec: Int? = null,
    /** How long to wait for a SERVER to answer. */
    val readySeconds: Int = 60,
    /** A SERVER that is not a web page: once it answers, offer to open the first of these installed apps instead of Preview. */
    val opensApps: List<String> = emptyList()
)

object RunTargets {
    private val IGNORED_DIRS = listOf("node_modules/", "vendor/", "dist/", "build/", "target/", "__pycache__/", ".git/", "venv/", ".venv/")
    private const val OUT = "/tmp/pocketide-a.out"

    fun ignored(path: String): Boolean = IGNORED_DIRS.any { path.startsWith(it) || path.contains("/$it") }

    /**
     * [files] less those inside a nested repository: a folder below the project root for which [isRepo] is true (it has its own .git,
     * so it was cloned or downloaded, not written here). 1.5.164.
     */
    fun ownFiles(files: List<String>, isRepo: (String) -> Boolean): List<String> {
        val repos = HashMap<String, Boolean>()
        fun inRepo(p: String): Boolean {
            var i = p.indexOf('/')
            while (i > 0) { val d = p.substring(0, i); if (repos.getOrPut(d) { isRepo(d) }) return true; i = p.indexOf('/', i + 1) }
            return false
        }
        return files.filter { !inRepo(it) }
    }

    /**
     * True when [dir] holds a repository the project downloaded rather than one it works in: it has its own .git, and the project's
     * .gitmodules does not list it (a submodule the person edits keeps its checks; Copilot N5, PR #27).
     */
    fun downloadedRepo(root: java.io.File, dir: String): Boolean {
        if (!java.io.File(root, "$dir/.git").exists()) return false
        val modules = try { java.io.File(root, ".gitmodules").takeIf { it.isFile }?.readText().orEmpty() } catch (e: Exception) { "" }
        return !Regex("""(?m)^\s*path\s*=\s*${Regex.escape(dir)}\s*$""").containsMatchIn(modules)
    }

    /** Quotes a file name for sh. */
    fun q(s: String): String = if (Regex("""^[\w./\-]+$""").matches(s)) s else "'" + s.replace("'", "'\\''") + "'"

    /**
     * All targets for a project. files are project-relative paths; open is the file in the editor (or null);
     * read gives a file's text (null if missing or binary); hasDir tells whether a folder exists (node_modules).
     */
    fun detect(files: List<String>, open: String?, read: (String) -> String?, hasDir: (String) -> Boolean = { false }): List<RunTarget> {
        val fs = files.filter { !ignored(it) }
        val set = fs.toSet()
        val out = ArrayList<RunTarget>()
        fun add(t: RunTarget) { if (out.none { it.id == t.id }) out.add(t) }

        // --- a 2009scape server project: its own steps, and nothing else (its thousands of files are not run on their own) ---
        AddOns.runTargets(set, read, hasDir)?.let { return it }

        // --- Node: package.json scripts ---
        if ("package.json" in set) {
            val pkg = try { MiniJson.obj(MiniJson.parse(read("package.json") ?: "{}")) } catch (e: Exception) { null }
            val scripts = MiniJson.obj(pkg?.get("scripts")) ?: emptyMap()
            val deps = (MiniJson.obj(pkg?.get("dependencies"))?.size ?: 0) + (MiniJson.obj(pkg?.get("devDependencies"))?.size ?: 0)
            val prep = if (deps > 0 && !hasDir("node_modules")) "[ -d node_modules ] || npm install; " else ""
            if (deps > 0) add(RunTarget("npm-install", "npm install", "npm install", RunKind.COMMAND, "node"))
            for (name in listOf("dev", "start", "serve", "test", "build")) {
                val script = scripts[name]?.toString() ?: continue
                val cmd = prep + if (name == "start" || name == "test") "npm $name" else "npm run $name"
                val server = name in setOf("dev", "start", "serve") && looksLikeServerScript(script)
                add(RunTarget("npm-$name", "npm ${if (name == "start" || name == "test") name else "run $name"}", cmd,
                    if (server) RunKind.SERVER else RunKind.COMMAND, "node", url = if (server) url(guessPort(script, null) ?: defaultNodePort(script)) else null))
            }
            if (scripts.isEmpty()) {
                val main = pkg?.get("main")?.toString()?.removePrefix("./")
                val entry = listOfNotNull(main, "server.js", "index.js", "app.js", "main.js").firstOrNull { it in set }
                if (entry != null) add(nodeTarget(entry, read(entry)))
            }
        }

        // --- the open file, when it is runnable on its own ---
        if (open != null && open in set) fileTarget(open, read(open), set)?.let { add(it) }

        // --- Python ---
        for (f in listOf("main.py", "app.py", "manage.py", "server.py", "bot.py")) if (f in set) fileTarget(f, read(f), set)?.let { add(it) }
        if ("requirements.txt" in set) add(RunTarget("pip-install", "pip install -r requirements.txt",
            "pip install --break-system-packages -r requirements.txt", RunKind.COMMAND, "python"))

        // --- Node without package.json ---
        if ("package.json" !in set) for (f in listOf("server.js", "index.js", "main.js", "app.js")) {
            if (f in set && isNodeScript(read(f))) add(nodeTarget(f, read(f)))
        }

        // --- Go ---
        if ("go.mod" in set || "main.go" in set) {
            val src = fs.filter { it.endsWith(".go") }.joinToString("\n") { read(it) ?: "" }
            val server = Regex("""ListenAndServe|net\.Listen\(|\.Run\(":?\d*""").containsMatchIn(src)
            add(RunTarget("go-run", "go run .", "go run .", if (server) RunKind.SERVER else RunKind.COMMAND, "go",
                url = if (server) url(guessPort(null, src) ?: 8080) else null))
            if ("go.mod" in set) add(RunTarget("go-test", "go test ./...", "go test ./...", RunKind.COMMAND, "go"))
        }

        // --- Rust ---
        if ("Cargo.toml" in set) {
            val src = fs.filter { it.endsWith(".rs") }.joinToString("\n") { read(it) ?: "" }
            val server = Regex("""TcpListener::bind|HttpServer::new|axum::|rocket::|warp::serve""").containsMatchIn(src)
            add(RunTarget("cargo-run", "cargo run", "cargo run", if (server) RunKind.SERVER else RunKind.COMMAND, "rust",
                url = if (server) url(guessPort(null, src) ?: 8080) else null))
            add(RunTarget("cargo-test", "cargo test", "cargo test", RunKind.COMMAND, "rust"))
        }

        // --- C / C++ (the whole folder when there is no Makefile) ---
        if ("Makefile" in set || "makefile" in set) add(RunTarget("make", "make", "make", RunKind.COMMAND, "cc"))
        val cFiles = fs.filter { it.endsWith(".c") }
        val cppFiles = fs.filter { it.endsWith(".cpp") || it.endsWith(".cc") }
        if (cFiles.isNotEmpty() && "Makefile" !in set) add(RunTarget("c-all", "Build and run C (${cFiles.size} file${if (cFiles.size == 1) "" else "s"})",
            "cc -O1 -o $OUT ${cFiles.joinToString(" ") { q(it) }} -lm && $OUT", RunKind.COMMAND, "cc"))
        if (cppFiles.isNotEmpty() && "Makefile" !in set) add(RunTarget("cpp-all", "Build and run C++ (${cppFiles.size} file${if (cppFiles.size == 1) "" else "s"})",
            "c++ -O1 -o $OUT ${cppFiles.joinToString(" ") { q(it) }} && $OUT", RunKind.COMMAND, "cc"))

        // --- Java ---
        val mainJava = fs.firstOrNull { it == "Main.java" || it.endsWith("/Main.java") } ?: fs.firstOrNull { it.endsWith(".java") && (read(it) ?: "").contains("static void main") }
        if (mainJava != null) fileTarget(mainJava, read(mainJava), set)?.let { add(it) }

        // --- shell scripts ---
        for (f in listOf("run.sh", "start.sh", "build.sh")) if (f in set) fileTarget(f, read(f), set)?.let { add(it) }

        // --- web page ---
        val html = if ("index.html" in set) "index.html" else fs.firstOrNull { it.endsWith(".html") }
        if (html != null) {
            add(RunTarget("preview", "Preview $html", "", RunKind.PREVIEW, needsLinux = false))
            add(RunTarget("http-server", "Serve the folder (python3 -m http.server 8000)", "python3 -m http.server 8000 --bind 127.0.0.1",
                RunKind.SERVER, "python", url = url(8000)))
        }
        return out
    }

    /** A target for one file, or null if the file type is not runnable by itself. */
    fun fileTarget(path: String, text: String?, files: Set<String> = emptySet()): RunTarget? {
        val ext = path.substringAfterLast('.', "").lowercase()
        val qp = q(path)
        return when (ext) {
            "py" -> {
                val t = text ?: ""
                when {
                    path.endsWith("manage.py") && t.contains("django", true) ->
                        RunTarget("file:$path", "Django dev server", "python3 $qp runserver 127.0.0.1:8000", RunKind.SERVER, "python", url = url(8000))
                    Regex("""FastAPI\(""").containsMatchIn(t) && !t.contains("uvicorn.run") ->
                        RunTarget("file:$path", "uvicorn ${path.removeSuffix(".py").replace('/', '.')}:app",
                            "python3 -m uvicorn ${path.removeSuffix(".py").replace('/', '.')}:app --host 127.0.0.1 --port 8000", RunKind.SERVER, "python", url = url(8000))
                    isPythonServer(t) -> RunTarget("file:$path", "python3 $path", "python3 $qp", RunKind.SERVER, "python",
                        url = url(guessPort(null, t) ?: if (t.contains("flask", true) || t.contains("Flask(")) 5000 else 8000))
                    else -> RunTarget("file:$path", "python3 $path", "python3 $qp", RunKind.COMMAND, "python")
                }
            }
            "js", "mjs", "cjs" -> if (ext != "js" || isNodeScript(text) || "package.json" in files) nodeTarget(path, text) else null
            "ts" -> RunTarget("file:$path", "node $path (TypeScript, Node 22+)", "node --experimental-strip-types $qp", RunKind.COMMAND, "node")
            "go" -> RunTarget("file:$path", "go run $path", "go run $qp", RunKind.COMMAND, "go")
            "c" -> RunTarget("file:$path", "Build and run $path", "cc -O1 -o $OUT $qp -lm && $OUT", RunKind.COMMAND, "cc")
            "cpp", "cc" -> RunTarget("file:$path", "Build and run $path", "c++ -O1 -o $OUT $qp && $OUT", RunKind.COMMAND, "cc")
            "java" -> RunTarget("file:$path", "java $path", "java $qp", RunKind.COMMAND, "java")
            "rs" -> if ("Cargo.toml" in files) null else
                RunTarget("file:$path", "Build and run $path", "rustc -o $OUT $qp && $OUT", RunKind.COMMAND, "rust")
            "sh" -> RunTarget("file:$path", "sh $path", "sh $qp", RunKind.COMMAND, null, needsLinux = false)
            "html", "htm" -> RunTarget("preview", "Preview $path", "", RunKind.PREVIEW, needsLinux = false)
            else -> null
        }
    }

    private fun nodeTarget(path: String, text: String?): RunTarget {
        val t = text ?: ""
        val server = isNodeServer(t)
        return RunTarget("file:$path", "node $path", "node ${q(path)}", if (server) RunKind.SERVER else RunKind.COMMAND, "node",
            url = if (server) url(guessPort(null, t) ?: 3000) else null)
    }

    /** Browser scripts (app.js next to index.html) must not be run with node. */
    fun isNodeScript(text: String?): Boolean {
        val t = text ?: return false
        return Regex("""\brequire\s*\(|\bprocess\.(argv|env|exit|stdout)|from\s+['"](node:|express|http|fs|path)['"]|module\.exports""").containsMatchIn(t) &&
            !Regex("""\bdocument\.|\bwindow\.""").containsMatchIn(t)
    }

    fun isNodeServer(t: String): Boolean = Regex("""\.listen\s*\(|createServer\s*\(|express\s*\(\s*\)|fastify\s*\(|new\s+Koa\b|Bun\.serve""").containsMatchIn(t)

    fun isPythonServer(t: String): Boolean =
        Regex("""\.run\s*\(|uvicorn\.run|HTTPServer|serve_forever|socketserver|app\s*=\s*Flask\(|streamlit|aiohttp\.web|\.listen\(""").containsMatchIn(t) &&
            !Regex("""def\s+test_""").containsMatchIn(t)

    private fun looksLikeServerScript(s: String): Boolean =
        Regex("""vite(?!\s+build)|next\s+(dev|start)|nodemon|node\s+\S+|react-scripts\s+start|webpack(-dev-server|\s+serve)|http-server|serve\b|astro\s+dev|nuxt|remix|parcel(?!\s+build)|express|ts-node|tsx\s""").containsMatchIn(s)

    private fun defaultNodePort(script: String): Int = when {
        script.contains("vite") || script.contains("astro") -> 5173
        script.contains("parcel") -> 1234
        script.contains("http-server") -> 8080
        else -> 3000
    }

    fun url(port: Int): String = "http://127.0.0.1:$port"

    /** A port number from a command line or source text, or null. */
    fun guessPort(command: String?, source: String?): Int? {
        val c = command ?: ""
        Regex("""(?:--port[ =]|-p\s+|PORT=)(\d{2,5})""").find(c)?.let { return it.groupValues[1].toInt() }
        Regex("""http\.server\s+(\d{2,5})""").find(c)?.let { return it.groupValues[1].toInt() }
        Regex("""(?:127\.0\.0\.1|localhost|0\.0\.0\.0):(\d{2,5})""").find(c)?.let { return it.groupValues[1].toInt() }
        val s = source ?: return null
        for (re in listOf(
            Regex("""\.listen\s*\(\s*(\d{2,5})"""),
            Regex("""port\s*[=:]\s*(\d{2,5})""", RegexOption.IGNORE_CASE),
            Regex("""["'](?:0\.0\.0\.0|127\.0\.0\.1|localhost)?:(\d{2,5})["']"""),
            Regex("""PORT\s*\|\|\s*(\d{2,5})"""),
            Regex("""\(\s*\(?\s*["'](?:[\d.]*|localhost)["']\s*,\s*(\d{2,5})\s*\)""")
        )) re.find(s)?.let { val p = it.groupValues[1].toInt(); if (p in 80..65535) return p }
        return null
    }

    /** Whether a target can run here, and if not, why. */
    fun unavailableReason(t: RunTarget, canUseLinux: Boolean, linuxInstalled: Boolean = canUseLinux): String? = when {
        !t.needsLinux -> null
        !canUseLinux -> "Needs the Linux environment (⋮ → Tools & Linux)."
        !linuxInstalled -> "Set up the Linux environment first (⋮ → Tools & Linux)."
        else -> null
    }

    /** What ▶ runs: the last choice if still there, else the open file, else the first target in priority order. */
    fun pickDefault(targets: List<RunTarget>, open: String?, lastId: String?, canUseLinux: Boolean): RunTarget? {
        val usable = targets.filter { unavailableReason(it, canUseLinux) == null }
        if (usable.any { AddOns.isStep(it.id) }) return usable.firstOrNull()   // steps in order: always the next one, not the last one run
        lastId?.let { id -> usable.firstOrNull { it.id == id }?.let { return it } }
        if (open != null) usable.firstOrNull { it.id == "file:$open" }?.let { return it }
        val order = listOf("npm-dev", "npm-start", "file:main.py", "file:app.py", "file:manage.py", "file:server.py", "go-run", "cargo-run",
            "make", "c-all", "cpp-all", "file:server.js", "file:index.js")
        for (id in order) usable.firstOrNull { it.id == id }?.let { return it }
        return usable.firstOrNull { it.kind != RunKind.SERVER || it.id != "http-server" } ?: usable.firstOrNull()
    }

    /** Kind for a user-written command. */
    fun kindOf(command: String): RunKind {
        val c = command.trim()
        val server = Regex("""\b(npm\s+(run\s+)?(dev|start|serve)|http\.server|runserver|uvicorn|flask\s+run|gunicorn|vite(\s|$)|next\s+dev|php\s+-S|serve\b)""").containsMatchIn(c)
        return if (server) RunKind.SERVER else RunKind.COMMAND
    }

    fun custom(id: String, label: String, command: String): RunTarget {
        val kind = kindOf(command)
        val tool = toolFor(command)
        // plain shell lines (ls, sh run.sh) also work in Android's own shell; language tools need Linux
        return RunTarget(id, label.ifBlank { command }, command, kind, tool, needsLinux = tool != null || kind == RunKind.SERVER,
            custom = true, url = if (kind == RunKind.SERVER) url(guessPort(command, null) ?: 3000) else null)
    }

    /** The tool a command needs, judged by its first word. */
    fun toolFor(command: String): String? {
        val w = command.trim().removePrefix("[ -d node_modules ] || npm install; ").split(Regex("\\s+")).firstOrNull() ?: return null
        return when (w) {
            "python3", "python", "pip", "pip3" -> "python"
            "node", "npm", "npx" -> "node"
            "go" -> "go"
            "cargo", "rustc" -> "rust"
            "cc", "gcc", "c++", "g++", "make", "clang" -> "cc"
            "java", "javac" -> "java"
            else -> null
        }
    }

    // --- per-project settings: last choice and custom commands (JSON) ---

    data class Config(val last: String? = null, val custom: List<RunTarget> = emptyList())

    fun encode(c: Config): String = MiniJson.write(mapOf(
        "last" to c.last,
        "custom" to c.custom.map { mapOf("id" to it.id, "label" to it.label, "command" to it.command) }
    ))

    fun decode(json: String?): Config {
        if (json.isNullOrBlank()) return Config()
        val o = try { MiniJson.obj(MiniJson.parse(json)) } catch (e: Exception) { null } ?: return Config()
        val list = MiniJson.list(o["custom"])?.mapNotNull { MiniJson.obj(it) }?.mapNotNull { m ->
            val id = m["id"]?.toString() ?: return@mapNotNull null
            val cmd = m["command"]?.toString() ?: return@mapNotNull null
            custom(id, m["label"]?.toString() ?: cmd, cmd)
        } ?: emptyList()
        return Config(o["last"]?.toString(), list)
    }

    /** Custom commands override detected targets with the same id ("Edit command"), and the rest are added. */
    fun merge(detected: List<RunTarget>, custom: List<RunTarget>): List<RunTarget> {
        val byId = custom.associateBy { it.id }
        val out = detected.map { d ->
            val e = byId[d.id]
            if (e == null || d.kind == RunKind.PREVIEW) d
            else {
                val server = d.kind == RunKind.SERVER || e.kind == RunKind.SERVER
                d.copy(label = d.label + " (edited)", command = e.command, kind = if (server) RunKind.SERVER else RunKind.COMMAND,
                    toolId = e.toolId ?: d.toolId, url = if (server) (guessPort(e.command, null)?.let { url(it) } ?: d.url ?: e.url) else null)
            }
        }.toMutableList()
        for (c in custom) if (detected.none { it.id == c.id }) out.add(c)
        return out
    }

    // --- "Check project": fast syntax checks for files that are present ---

    /** One shell line that syntax-checks the project with the tools that are installed (null = unknown, assume present). */
    fun checkCommand(files: List<String>, installedTools: Set<String>?): String? {
        val fs = files.filter { !ignored(it) }
        fun has(tool: String) = installedTools == null || tool in installedTools
        val parts = ArrayList<String>()
        val py = fs.filter { it.endsWith(".py") }
        if (py.isNotEmpty() && has("python")) parts += "python3 -m py_compile ${py.take(200).joinToString(" ") { q(it) }}"
        val js = fs.filter { it.endsWith(".js") || it.endsWith(".mjs") || it.endsWith(".cjs") }
        if (js.isNotEmpty() && has("node")) parts += "for f in ${js.take(200).joinToString(" ") { q(it) }}; do node --check \"\$f\" || true; done"
        if ("go.mod" in fs && has("go")) parts += "go vet ./..."
        val c = fs.filter { it.endsWith(".c") }
        if (c.isNotEmpty() && has("cc")) parts += "for f in ${c.take(200).joinToString(" ") { q(it) }}; do cc -fsyntax-only \"\$f\" || true; done"
        val cpp = fs.filter { it.endsWith(".cpp") || it.endsWith(".cc") }
        if (cpp.isNotEmpty() && has("cc")) parts += "for f in ${cpp.take(200).joinToString(" ") { q(it) }}; do c++ -fsyntax-only \"\$f\" || true; done"
        val sh = fs.filter { it.endsWith(".sh") }
        if (sh.isNotEmpty()) parts += "for f in ${sh.take(200).joinToString(" ") { q(it) }}; do sh -n \"\$f\" || true; done"
        if (parts.isEmpty()) return null
        return parts.joinToString(" ; ") + " ; echo 'Check finished.'"
    }
}
