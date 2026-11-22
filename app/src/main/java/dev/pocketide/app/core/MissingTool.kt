package dev.pocketide.app.core

/** Works out which program a failed command was missing, and which one-tap install provides it. */
object MissingTool {
    private val PATTERNS = listOf(
        // busybox ash prints "sh: foo: not found"; bash "bash: line 1: foo: command not found"; Ubuntu's dash "/bin/sh: 1: foo: not found"
        Regex("""(?:^|\n)\s*(?:sh|bash|ash|dash|/bin/sh|/bin/bash):\s*(?:(?:line\s+)?\d+:\s*)?([\w.+-]+):\s*(?:command\s+)?not found""", RegexOption.IGNORE_CASE),
        Regex("""(?:^|\n)\s*([\w.+-]+):\s*command not found""", RegexOption.IGNORE_CASE),
        Regex("""(?:^|\n)\s*command not found:\s*([\w.+-]+)""", RegexOption.IGNORE_CASE),
        Regex("""(?:^|\n)\s*(?:exec|env):\s*([\w.+-]+):\s*not found""", RegexOption.IGNORE_CASE),
        Regex("""No such file or directory:\s*'([\w.+-]+)'"""),
        Regex("""(?:^|\n)\s*ModuleNotFoundError: No module named '([\w.]+)'"""),
        Regex("""(?:^|\n)\s*ImportError: No module named '?([\w.]+)'?"""),
    )

    /** The program or module name a command could not find, or null. */
    fun missing(output: String): String? {
        for (r in PATTERNS) r.find(output)?.let { m -> m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return it.trim() } }
        return null
    }

    /** True when the name came from a Python import rather than a shell command. */
    fun isPythonModule(output: String): Boolean =
        Regex("""(ModuleNotFoundError|ImportError: No module named)""").containsMatchIn(output)

    /** Programs each catalog entry provides, beyond the tool id itself. */
    private val PROVIDES: Map<String, List<String>> = mapOf(
        "python" to listOf("python", "python3", "pip", "pip3"),
        "node" to listOf("node", "npm", "npx"),
        "git" to listOf("git"),
        "cc" to listOf("gcc", "g++", "cc", "c++", "make", "ld", "ar"),
        "go" to listOf("go", "gofmt"),
        "rust" to listOf("rustc", "cargo"),
        "java" to listOf("java", "javac", "jar", "keytool"),
        "ollama" to listOf("ollama"),
        "llamacpp" to listOf("llama-server", "llama-cli"),
        "sqlite" to listOf("sqlite3"),
        "net" to listOf("curl", "wget"),
        "editors" to listOf("nano", "vim", "vi"),
        "zip" to listOf("zip", "unzip"),
        "pdf" to listOf("pdftotext", "pdftoppm", "pdfinfo", "pdfimages", "pdfunite", "pdfseparate"),
        "pandoc" to listOf("pandoc"),
        "office" to listOf("python3"),
        "imagemagick" to listOf("convert", "magick", "identify", "mogrify"),
        "ffmpeg" to listOf("ffmpeg", "ffprobe"),
        "jq" to listOf("jq"),
        "ripgrep" to listOf("rg"),
        "tree" to listOf("tree"),
        "make" to listOf("make"),
        "php" to listOf("php"),
        "ruby" to listOf("ruby", "gem"),
        "shellcheck" to listOf("shellcheck")
    )

    /** Python packages installed with pip rather than a catalog entry. */
    private val PIP: Map<String, String> = mapOf(
        "docx" to "python-docx", "openpyxl" to "openpyxl", "pptx" to "python-pptx", "odf" to "odfpy",
        "fitz" to "pymupdf", "PIL" to "pillow", "pil" to "pillow", "numpy" to "numpy", "pandas" to "pandas",
        "requests" to "requests", "flask" to "flask", "django" to "django", "bs4" to "beautifulsoup4",
        "yaml" to "pyyaml", "pytest" to "pytest", "matplotlib" to "matplotlib", "scipy" to "scipy"
    )

    class Fix(val label: String, val toolId: String?, val command: String?, val note: String)

    /** What to offer when [name] is missing. [installed] is the set of catalog ids already installed. */
    fun fixFor(name: String, pythonModule: Boolean, catalogIds: Collection<String>, installed: Collection<String> = emptyList(), distro: String = "alpine"): Fix? {
        val n = name.trim().removeSuffix(":")
        if (n.isEmpty()) return null
        // the wrong package manager for this base: installing it would not help, so offer nothing
        if (n == "apk" && ToolCatalog.isUbuntu(distro)) return null
        if ((n == "apt" || n == "apt-get") && !ToolCatalog.isUbuntu(distro)) return null
        if (pythonModule) {
            val pkg = PIP[n] ?: PIP[n.substringBefore('.')] ?: n
            return if ("python" !in installed) Fix("Install Python", "python", null, "Python is not installed yet; $pkg needs it.")
            else Fix("Install $pkg with pip", null, "pip3 install --break-system-packages $pkg", "$n is a Python package.")
        }
        val id = PROVIDES.entries.firstOrNull { (k, progs) -> k in catalogIds && (n == k || n in progs) }?.key
            ?: catalogIds.firstOrNull { it == n }
        if (id != null) return Fix("Install ${id}", id, null, "$n comes with it.")
        if (ToolCatalog.isUbuntu(distro) && !Regex("[a-z0-9][a-z0-9.+_-]*").matches(n)) return null
        return if (ToolCatalog.isUbuntu(distro))
            Fix("Install $n with apt", null, "export DEBIAN_FRONTEND=noninteractive; apt-get -o APT::Sandbox::User=root update && apt-get -o APT::Sandbox::User=root install -y --no-install-recommends $n",
                "$n is not in the one-tap list; this installs the Ubuntu package of the same name if there is one.")
        else Fix("Install $n with apk", null, "apk add --no-cache $n", "$n is not in the one-tap list; this installs the Alpine package of the same name if there is one.")
    }

    /** The whole thing: nothing to offer unless a command really was missing. */
    fun detect(output: String, catalogIds: Collection<String>, installed: Collection<String> = emptyList(), distro: String = "alpine"): Pair<String, Fix>? {
        val name = missing(output) ?: return null
        val fix = fixFor(name, isPythonModule(output), catalogIds, installed, distro) ?: return null
        return name to fix
    }
}
