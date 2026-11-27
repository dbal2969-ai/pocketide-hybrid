package dev.pocketide.app.core

/**
 * GPL route: the one-tap installs on the Tools screen. Each tool is a set of Alpine packages
 * (names checked against Alpine v3.24, aarch64) plus a command that proves it works.
 * Pure logic, no Android imports.
 */
data class Tool(
    val id: String,
    val name: String,
    val description: String,
    val packages: List<String>,
    /** Shell line that prints a version once installed. */
    val verify: String,
    /** Rough installed size including dependencies, in MB. */
    val approxMb: Int,
    /** Shell command run after the apk packages are added (for example a pip install). Empty for none. */
    val after: String = ""
)

object ToolCatalog {
    private val PKG = Regex("[a-z0-9][a-z0-9.+_-]*")

    val all: List<Tool> = listOf(
        Tool("python", "Python 3", "Python with pip, for scripts, data work and back ends.", listOf("python3", "py3-pip"), "python3 --version && pip3 --version", 60),
        Tool("node", "Node.js + npm", "JavaScript outside the browser, and npm packages.", listOf("nodejs", "npm"), "node --version && npm --version", 70),
        Tool("git", "Git", "Version control from the command line.", listOf("git"), "git --version", 15),
        Tool("cc", "C / C++", "gcc, g++, make and the C library headers (build-base).", listOf("build-base"), "gcc --version | head -1 && make --version | head -1", 200),
        Tool("go", "Go", "The Go compiler and tools.", listOf("go"), "go version", 175),
        Tool("rust", "Rust", "rustc and cargo.", listOf("rust", "cargo"), "rustc --version && cargo --version", 200),
        Tool("java", "Java 21", "OpenJDK 21 (javac and java).", listOf("openjdk21-jdk"), "java -version 2>&1 | head -1 && javac -version", 200),
        Tool("ollama", "Ollama (local AI)", "Runs AI models on the phone. Pick models on the Models screen.", listOf("ollama"), "ollama --version", 36),
        Tool("llamacpp", "llama.cpp server", "llama-server: runs a .gguf model with an OpenAI-style API on port 8080.", listOf("llama-server"), "llama-server --version 2>&1 | head -3", 20),
        Tool("sqlite", "SQLite", "The sqlite3 database shell.", listOf("sqlite"), "sqlite3 --version", 3),
        Tool("net", "curl & wget", "Download files and call web APIs.", listOf("curl", "wget"), "curl --version | head -1 && wget --version | head -1", 4),
        Tool("editors", "nano & vim", "Text editors for the full terminal.", listOf("nano", "vim"), "nano --version | head -1 && vim --version | head -1", 35),
        Tool("zip", "zip & unzip", "Create and open .zip files.", listOf("zip", "unzip"), "zip -v | head -2 | tail -1 && unzip -v | head -1", 1),
        Tool("office", "Office files (Word, Excel, PowerPoint)",
            "The Python libraries the AI uses to change .docx, .xlsx and .pptx files: python-docx, openpyxl and python-pptx. Needs Python and a network connection.",
            listOf("py3-lxml", "py3-pillow", "py3-openpyxl", "py3-xlsxwriter"),
            "python3 -c \"import docx, openpyxl, pptx; print('python-docx, openpyxl and python-pptx are ready')\"", 120,
            after = "apk add py3-pip && pip install --break-system-packages python-docx python-pptx"),
        Tool("imagemagick", "ImageMagick (images)", "convert and identify: resize, crop, rotate and convert images from the command line.", listOf("imagemagick"), "convert --version | head -1", 60),
        Tool("ffmpeg", "FFmpeg (audio and video)", "Convert, cut and inspect audio and video files. Large.", listOf("ffmpeg"), "ffmpeg -version | head -1", 120),
        Tool("jq", "jq (JSON on the command line)", "Read and reshape JSON in the terminal.", listOf("jq"), "jq --version", 2),
        Tool("yq", "yq (YAML on the command line)", "Read and change fields in YAML, JSON and TOML files, like jq for YAML.", listOf("yq-go"), "yq --version", 10),
        Tool("gh", "GitHub CLI", "gh: issues, pull requests, releases and the GitHub API from the terminal. Sign in once with gh auth login.", listOf("github-cli"), "gh --version | head -1", 40),
        Tool("ast-grep", "ast-grep (search code by its shape)", "Find and rewrite code by structure, not text, for example ast-grep -p 'console.log(\$\$\$A)' -l js.", listOf("ast-grep"), "ast-grep --version", 30),
        Tool("ripgrep", "ripgrep (fast search)", "rg: searches a whole project in a moment.", listOf("ripgrep"), "rg --version | head -1", 6),
        Tool("tree", "tree (show folders)", "Prints a folder as a tree.", listOf("tree"), "tree --version", 1),
        Tool("shellcheck", "ShellCheck (check shell scripts)", "Finds mistakes in .sh files.", listOf("shellcheck"), "shellcheck --version | head -2 | tail -1", 20),
        Tool("php", "PHP", "The PHP interpreter with its built-in web server.", listOf("php84"), "php --version | head -1", 40),
        Tool("ruby", "Ruby", "The Ruby interpreter and gem.", listOf("ruby", "ruby-dev"), "ruby --version && gem --version", 60),
        Tool("typescript", "TypeScript", "The tsc compiler, installed with npm. Needs Node.", listOf("nodejs", "npm"), "tsc --version", 80, after = "npm install -g typescript"),
        Tool("ts-lsp", "Language server for JavaScript and TypeScript", "Lets the language server check (Tools) find errors in .js, .ts and .tsx files. Installed with npm. Needs Node.", listOf("nodejs", "npm"), "command -v typescript-language-server >/dev/null && test -f \"$(npm root -g)/typescript/lib/tsserver.js\"", 90, after = "npm install -g typescript@5 typescript-language-server"),
        Tool("py-lsp", "Language server for Python", "Lets the language server check (Tools) find errors in .py files (pyright). Installed with npm. Needs Node.", listOf("nodejs", "npm"), "command -v pyright-langserver", 120, after = "npm install -g pyright"),
        Tool("pdf", "PDF tools", "pdftotext, pdftoppm and other command-line PDF tools (poppler).", listOf("poppler-utils"), "pdftotext -v 2>&1 | head -1", 20),
        // 1.5.180: other coding agents in the full terminal (VibeCursor runs Cursor's agent in a phone's Linux; Acode's terminal
        // offers Claude Code, Codex and OpenCode). npm picks the Alpine (musl) ARM64 build where the package has one.
        Tool("opencode", "OpenCode (coding agent)", "Another AI coding agent for the full terminal. Has free models with no account; also takes your own keys. Run: opencode", listOf("nodejs", "npm"), "opencode --version", 120, after = "npm install -g opencode-ai"),
        Tool("gemini-cli", "Gemini CLI (coding agent)", "Google's coding agent for the terminal. Sign in with a Google account or a Gemini API key. Run: gemini", listOf("nodejs", "npm"), "gemini --version", 150, after = "npm install -g @google/gemini-cli"),
        // 1.5.216: the owner's ask, to push Remote Config and Firestore rules from the phone. Signing in is the person's own step: a
        // link to open and a code to paste back (no localhost on a phone).
        Tool("firebase", "Firebase CLI", "Deploy to Firebase from the terminal: Remote Config, Firestore rules, Hosting. Sign in once with: " +
            "firebase login --no-localhost (open the link, sign in, paste the code back). Then, in a project with firebase.json: " +
            "firebase deploy --only remoteconfig --project <your-project-id>", listOf("nodejs", "npm"), "firebase --version", 160, after = "npm install -g firebase-tools"),
        Tool("codex", "Codex CLI (coding agent)", "OpenAI's coding agent for the terminal. Needs a ChatGPT plan or an OpenAI key. Run: codex", listOf("nodejs", "npm"), "codex --version", 120, after = "npm install -g @openai/codex"),
        Tool("claude-code", "Claude Code (coding agent)", "Anthropic's coding agent for the terminal. Needs a Claude plan or an Anthropic key. Run: claude", listOf("nodejs", "npm", "libgcc", "libstdc++", "ripgrep"), "claude --version", 200, after = "npm install -g @anthropic-ai/claude-code"),
        Tool("pandoc", "Pandoc (convert documents)", "Convert between Markdown, Word, HTML, EPUB and more. Writing PDF also needs a LaTeX engine, which is not included.", listOf("pandoc-cli"), "pandoc --version | head -1", 110)
    )

    /** "Install the basics". */
    val basics: List<String> = listOf("python", "node", "git")

    fun byId(id: String): Tool? = all.firstOrNull { it.id == id }

    /** Ubuntu 24.04 (noble) package names for the tools that exist there. Ollama and llama.cpp are not Ubuntu packages, so they are left out. */
    private val DEBIAN: Map<String, List<String>> = mapOf(
        "python" to listOf("python3", "python3-pip", "python3-venv"),
        "node" to listOf("nodejs", "npm"),
        "git" to listOf("git"),
        "cc" to listOf("build-essential"),
        "go" to listOf("golang-go"),
        "rust" to listOf("rustc", "cargo"),
        "java" to listOf("openjdk-21-jdk-headless"),
        "sqlite" to listOf("sqlite3"),
        "net" to listOf("curl", "wget"),
        "editors" to listOf("nano", "vim"),
        "zip" to listOf("zip", "unzip"),
        "office" to listOf("python3-pip", "python3-lxml", "python3-pil", "python3-openpyxl", "python3-xlsxwriter"),
        "imagemagick" to listOf("imagemagick"),
        "ffmpeg" to listOf("ffmpeg"),
        "jq" to listOf("jq"),
        "yq" to listOf("wget", "ca-certificates"),
        "gh" to listOf("gh"),
        "ast-grep" to listOf("nodejs", "npm"),
        "ripgrep" to listOf("ripgrep"),
        "tree" to listOf("tree"),
        "shellcheck" to listOf("shellcheck"),
        "php" to listOf("php-cli"),
        "ruby" to listOf("ruby", "ruby-dev"),
        "typescript" to listOf("nodejs", "npm"),
        "ts-lsp" to listOf("nodejs", "npm"),
        "py-lsp" to listOf("nodejs", "npm"),
        "pdf" to listOf("poppler-utils"),
        "pandoc" to listOf("pandoc"),
        "opencode" to listOf("nodejs", "npm"),
        "codex" to listOf("nodejs", "npm")
    )

    /** Shell commands run after the Ubuntu packages are added. Ubuntu's pip refuses system-wide installs without --break-system-packages. */
    private val DEBIAN_AFTER: Map<String, String> = mapOf(
        "office" to "pip3 install --break-system-packages python-docx python-pptx",
        "typescript" to "npm install -g typescript",
        "ts-lsp" to "npm install -g typescript@5 typescript-language-server",
        "py-lsp" to "npm install -g pyright",
        // Ubuntu's own yq package is a different tool (a jq wrapper); this is the yq the docs and Alpine use
        "yq" to "wget -qO /usr/local/bin/yq https://github.com/mikefarah/yq/releases/latest/download/yq_linux_arm64 && chmod +x /usr/local/bin/yq",
        "ast-grep" to "npm install -g @ast-grep/cli",
        // Ubuntu 24.04's Node is 18; OpenCode and Codex are native programs that npm downloads, so they still work there
        "opencode" to "npm install -g opencode-ai",
        "codex" to "npm install -g @openai/codex"
    )

    fun isUbuntu(distro: String): Boolean = distro == "ubuntu"

    /** The packages that provide [tool] on this Linux base, or null when the base has no package for it. */
    fun packages(tool: Tool, distro: String = "alpine"): List<String>? = if (isUbuntu(distro)) DEBIAN[tool.id] else tool.packages

    fun available(tool: Tool, distro: String = "alpine"): Boolean = packages(tool, distro) != null

    fun after(tool: Tool, distro: String = "alpine"): String = if (isUbuntu(distro)) DEBIAN_AFTER[tool.id].orEmpty() else tool.after

    fun isInstalled(tool: Tool, installed: Set<String>, distro: String = "alpine"): Boolean =
        (packages(tool, distro)?.all { it in installed } ?: false) && npmPackages(after(tool, distro)).all { "npm:$it" in installed }

    /**
     * The npm packages an install command adds globally, without versions ("npm install -g typescript@5 typescript-language-server"
     * gives typescript, typescript-language-server). 1.5.180: tools installed with npm showed as installed once Node was, because
     * only the apk packages were compared; the installed set now also holds "npm:<name>" for each global npm package.
     */
    fun npmPackages(after: String): List<String> {
        val m = Regex("""npm install -g ((?:[^\s&;|]+\s*)+)""").find(after) ?: return emptyList()
        return m.groupValues[1].trim().split(Regex("""\s+""")).filter { !it.startsWith("-") }
            .map { a -> if (a.startsWith("@")) "@" + a.drop(1).substringBefore('@') else a.substringBefore('@') }
    }

    /** "npm:<name>" for each package folder in a global node_modules listing (scoped ones as @scope/name). */
    fun npmInstalled(topLevel: List<String>, scoped: Map<String, List<String>>): Set<String> =
        (topLevel.filter { !it.startsWith("@") && !it.startsWith(".") } + scoped.flatMap { (scope, names) -> names.map { "$scope/$it" } })
            .map { "npm:$it" }.toSet()

    private fun pkgs(tools: List<Tool>, distro: String = "alpine"): String {
        val names = tools.flatMap { packages(it, distro) ?: emptyList() }.distinct()
        require(names.isNotEmpty() && names.all { PKG.matches(it) }) { "Bad package list" }
        return names.joinToString(" ")
    }

    /** Refreshes the index first so a fresh install does not fail on stale package versions. */
    /**
     * 1.5.216 (the owner's one-tap setup): "apk update" failed on a passing DNS error ("DNS: transient error (try again later)") for
     * one of the two repositories, so the whole install stopped at exit 1. The index is now refreshed up to three times, a failed
     * refresh no longer stops the install (the older index is used), and the install itself is tried twice.
     */
    const val ALPINE_REFRESH = "for i in 1 2 3; do if apk update; then break; fi; sleep 3; done; "
    const val UBUNTU_REFRESH = "for i in 1 2 3; do if apt-get -o APT::Sandbox::User=root update; then break; fi; sleep 3; done; "

    fun installCommand(tools: List<Tool>, distro: String = "alpine"): String {
        val tail = tools.map { after(it, distro) }.filter { it.isNotBlank() }.distinct().joinToString("") { " && $it" }
        return if (isUbuntu(distro)) {
            // ca-certificates: the Ubuntu base has none, and pip, npm and git need them for https. The apt sandbox user does not exist under PRoot.
            val add = "apt-get -o APT::Sandbox::User=root install -y --no-install-recommends " + pkgs(tools, distro) + " ca-certificates"
            "export DEBIAN_FRONTEND=noninteractive; " + UBUNTU_REFRESH + add + " || { sleep 5; " + add + "; }" + tail
        } else ("apk add " + pkgs(tools)).let { add -> ALPINE_REFRESH + add + " || { sleep 5; " + add + "; }" + tail }
    }

    fun removeCommand(tool: Tool, distro: String = "alpine"): String =
        if (isUbuntu(distro)) "apt-get remove -y " + pkgs(listOf(tool), distro) else "apk del " + pkgs(listOf(tool))

    fun sizeLabel(mb: Int): String = when {
        mb < 1 -> "< 1 MB"
        mb >= 1000 -> String.format("%.1f GB", mb / 1000.0)
        else -> "~$mb MB"
    }

    /**
     * The minirootfs normally lists both main and community. If community is missing (Ollama, Go,
     * npm and others live there), returns the repositories text with it added after main; null if
     * nothing needs to change.
     */
    fun withCommunity(repos: String): String? {
        val lines = repos.lines()
        if (lines.any { l -> val t = l.trim(); !t.startsWith("#") && t.trimEnd('/').endsWith("/community") }) return null
        val i = lines.indexOfFirst { l -> val t = l.trim(); !t.startsWith("#") && t.trimEnd('/').endsWith("/main") }
        if (i < 0) return null
        val main = lines[i].trim().trimEnd('/')
        val community = main.removeSuffix("/main") + "/community"
        val out = lines.toMutableList()
        out.add(i + 1, community)
        return out.joinToString("\n")
    }
}

/** Reads Alpine's package database (/lib/apk/db/installed): one "P:<name>" line per installed package. */
object ApkDb {
    fun installed(db: String): Set<String> {
        val out = HashSet<String>()
        for (line in db.lineSequence()) if (line.startsWith("P:")) { val n = line.substring(2).trim(); if (n.isNotEmpty()) out.add(n) }
        return out
    }
}

/** Reads Ubuntu's package database (/var/lib/dpkg/status): the names of packages whose status ends in "installed". */
object DpkgDb {
    fun installed(status: String): Set<String> {
        val out = HashSet<String>()
        for (para in status.split("\n\n")) {
            var name = ""
            var ok = false
            for (line in para.lineSequence()) {
                if (line.startsWith("Package:")) name = line.substring(8).trim()
                else if (line.startsWith("Status:")) ok = line.trim().endsWith(" installed") && !line.contains("not-installed")
            }
            if (name.isNotEmpty() && ok) out.add(name)
        }
        return out
    }
}
