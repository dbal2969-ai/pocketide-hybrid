package dev.pocketide.app.core

/** What an entry's buttons do. */
enum class HelpAction { NONE, SETTINGS, MODELS }

data class HelpEntry(
    val id: String,
    val title: String,
    val body: String,
    val steps: List<String> = emptyList(),
    val url: String? = null,
    val urlLabel: String = "Open website",
    /** For key guides: the provider whose settings "Paste key in Settings" opens. */
    val providerId: String? = null,
    val action: HelpAction = HelpAction.NONE,
    val keywords: List<String> = emptyList()
)

data class HelpSection(val id: String, val title: String, val intro: String, val entries: List<HelpEntry>)

/**
 * The Help & FAQ text. Kept as data, without Android classes, so it can be checked by tests.
 * Free-tier limits change often; they are given as approximate figures checked in 2026.
 */
object HelpContent {
    const val KEYS = "keys"
    const val LOCAL = "local"
    const val GIT = "git"
    const val AGENT = "agent"
    const val LINUX = "linux"
    const val FILES = "files"
    const val TROUBLE = "trouble"
    /** Shown in Help and in every model list. Measured on PocketIDE's benchmark (BENCHMARKS.md, 1.5.93); keep it provider-neutral. */
    const val MODEL_ADVICE =
        "For coding, pick a large model that supports tool calls. In PocketIDE's own tests, Kimi K2.6 and GPT-OSS 120B finished " +
            "most coding tasks reliably; Llama 3.3 70B often failed to edit files. Results vary by provider and model version: " +
            "if a model keeps stopping without changing anything, try another."

    /** Providers with a free key guide, in the order they are listed. */
    val keyProviders = listOf("groq", "gemini", "cerebras", "openrouter", "mistral", "nvidia", "huggingface",
        "sambanova", "ollamacloud", "cloudflare", "openai", "anthropic")

    private fun key(
        id: String, title: String, good: String, limits: String, url: String, steps: List<String>, extra: String = ""
    ) = HelpEntry(
        id = id, title = title,
        body = "Good for: $good\nLimits (approximate, checked 2026; providers change them): $limits" + (if (extra.isNotEmpty()) "\n$extra" else ""),
        steps = steps, url = url, urlLabel = "Open $title", providerId = id, action = HelpAction.SETTINGS,
        keywords = listOf(id, title.lowercase(), "key", "api key", "free")
    )

    val sections: List<HelpSection> = listOf(
        HelpSection(KEYS, "AI provider keys",
            "Each provider needs its own key. Most of these have a free tier with no credit card; OpenAI and Anthropic are paid. " +
                "Make a key, then paste it in Settings under that provider. " +
                "Groq is a good first pick for fast replies; Gemini when you want the AI to see a whole project. " +
                MODEL_ADVICE + " " +
                "Keys are stored only on this phone. Treat them like passwords.",
            listOf(
                key("groq", "Groq", "very fast replies with open models such as GPT-OSS 120B.",
                    "about 30 requests a minute and about 1,000 a day.", "https://console.groq.com/keys",
                    listOf("Open console.groq.com/keys and sign in (Google or GitHub works).",
                        "Tap \"Create API Key\", give it any name, and create it.",
                        "Copy the key straight away. It is shown only once.",
                        "Come back to PocketIDE and tap \"Paste key in Settings\".")),
                key("gemini", "Gemini", "very long context, so the whole project fits; strong general help.",
                    "5 to 15 requests a minute and 20 to 1,500 a day depending on the model. Only a Google account is needed.",
                    "https://aistudio.google.com/apikey",
                    listOf("Open aistudio.google.com/apikey and sign in with your Google account.",
                        "Accept the terms if asked. AI Studio sets up a project for you.",
                        "Tap \"Create API key\" and copy it.",
                        "Come back to PocketIDE and tap \"Paste key in Settings\"."),
                    "If the default model hits its daily limit, try a different Gemini Flash model in Settings."),
                key("cerebras", "Cerebras", "fast replies and a generous daily token allowance.",
                    "about 30 requests a minute and about 1 million tokens a day.", "https://cloud.cerebras.ai",
                    listOf("Open cloud.cerebras.ai and sign up or log in.",
                        "Choose \"API Keys\" in the left menu (on a phone, open the menu first).",
                        "Create a key and copy it.",
                        "Come back to PocketIDE and tap \"Paste key in Settings\".")),
                key("openrouter", "OpenRouter", "trying many different models with one key, including Qwen3 Coder.",
                    "free models (names ending in :free) allow about 50 requests a day; about 1,000 a day after a one-time \$10 credit purchase.",
                    "https://openrouter.ai/settings/keys",
                    listOf("Open openrouter.ai and sign in.",
                        "Open Settings > Keys (openrouter.ai/settings/keys) and tap \"Create API key\".",
                        "Copy the key. It is shown only once.",
                        "In PocketIDE keep a model whose name ends in :free, then paste the key.")),
                key("mistral", "Mistral", "Codestral, a model tuned for code.",
                    "a monthly allowance. Mistral has started asking for a $10 deposit before creating a key, so try Groq or Gemini first if you want no cost.", "https://console.mistral.ai/api-keys",
                    listOf("Open console.mistral.ai and create an account (it may ask to verify your phone number).",
                        "Choose the free plan if asked.",
                        "Open \"API Keys\" in the left menu and tap \"Create new key\".",
                        "Copy the key. It is shown only once. Then tap \"Paste key in Settings\"."),
                    "The free plan may use your prompts to train Mistral's models. Don't send private code through it."),
                key("nvidia", "NVIDIA NIM", "open models such as gpt-oss and Llama, through an OpenAI-style API.",
                    "free trial credits when you sign up at build.nvidia.com. NVIDIA describes these as a trial, so limits may change.",
                    "https://build.nvidia.com",
                    listOf("Open build.nvidia.com and sign in or create an account.",
                        "Open any model page and choose the option to get an API key.",
                        "Copy the key. It starts with nvapi-.",
                        "Come back to PocketIDE and tap \"Paste key in Settings\"."),
                    "Free endpoints may log prompts, so don't send private code through them."),
                key("huggingface", "Hugging Face", "many open models, including Qwen2.5 Coder, through the inference router.",
                    "a small monthly credit on a free account. Credit amounts change.",
                    "https://huggingface.co/settings/tokens",
                    listOf("Open huggingface.co/settings/tokens and sign in or create an account.",
                        "Tap \"Create new token\".",
                        "Choose a fine-grained token and tick the permission for Inference Providers (named like \"Make calls to Inference Providers\"). A Read token also works for most calls.",
                        "Create it, copy it, then tap \"Paste key in Settings\"."),
                    "If you get a 403 error, the token is missing the Inference Providers permission. Create a new token with it."),
                key("sambanova", "SambaNova", "fast Llama and other open models.",
                    "a low daily request cap on the free tier (about 20 requests a day). Limits change.",
                    "https://cloud.sambanova.ai",
                    listOf("Open cloud.sambanova.ai and create an account.",
                        "Open the API keys page (docs.sambanova.ai, search \"API keys\") or the API keys link in the console.",
                        "Generate a key and copy it now. It can't be viewed again.",
                        "Come back to PocketIDE and tap \"Paste key in Settings\".")),
                key("ollamacloud", "Ollama Cloud", "large open models hosted by Ollama.",
                    "needs usage credits or a subscription; a free account can have none included (requests answer 402).",
                    "https://ollama.com/settings/keys",
                    listOf("Open ollama.com/settings/keys and sign in or create an account.",
                        "Create a new API key and copy it. Do not use a device public key (those start with ssh-ed25519); they do not work here.",
                        "Come back to PocketIDE, tap \"Paste key in Settings\", then tap \"Test connection\" to pick a model.")),
                key("cloudflare", "Cloudflare Workers AI", "open models on Cloudflare's network, with a daily free allowance.",
                    "a daily free allowance. Allowances change.",
                    "https://dash.cloudflare.com/?to=/:account/ai/workers-ai",
                    listOf("Sign in to the Cloudflare dashboard (signing up is free) and open Workers AI.",
                        "Copy your Account ID, shown under \"Get Account ID\", and paste it into PocketIDE's Cloudflare account ID field. The address is filled in for you.",
                        "Choose \"Use REST API\", then \"Create a Workers AI API Token\" and create it. Copy the token.",
                        "Come back to PocketIDE and tap \"Paste key in Settings\"."),
                    "If you build a custom token instead of the template, give it Workers AI Read and Edit permissions."),
                key("openai", "OpenAI", "GPT models, the most widely supported API.",
                    "paid. New accounts may get a small trial credit; higher limits need credits added under Billing.",
                    "https://platform.openai.com/api-keys",
                    listOf("Open platform.openai.com/api-keys and sign in or create an account.",
                        "Add credits under Billing if the API asks for them.",
                        "Tap \"Create new secret key\" and copy it. It is shown only once.",
                        "Come back to PocketIDE and tap \"Paste key in Settings\"."),
                    "A ChatGPT subscription does not include API access. The API is billed separately."),
                key("anthropic", "Anthropic (Claude)", "Claude models, strong at careful, long code work.",
                    "paid and billed by Anthropic. Check the Billing page for whether you need to add credits first.",
                    "https://platform.claude.com/settings/keys",
                    listOf("Open platform.claude.com and create or sign in to a Console account.",
                        "Open Settings > API keys (platform.claude.com/settings/keys).",
                        "Create a key and copy it. Keep it safe.",
                        "Come back to PocketIDE and tap \"Paste key in Settings\"."),
                    "A Claude Pro or Max subscription does not include API access. The API is billed separately.")
            )),
        HelpSection(LOCAL, "Local models",
            "A local model runs on the phone itself: no key, no internet once downloaded, nothing leaves the phone. " +
                "Phone-sized models are much weaker than the free cloud ones, so they are best for offline use.",
            listOf(
                HelpEntry("local-how", "How do I use a local model?",
                    "PocketIDE talks to Ollama, a small server that runs models. Open ⋮ > Models: it shows whether Ollama is running, " +
                        "lets you download a model with one tap, and \"Use\" switches the AI tab to it.",
                    action = HelpAction.MODELS, keywords = listOf("ollama", "local", "offline", "model", "download")),
                HelpEntry("local-where", "Where does Ollama run?",
                    "PocketIDE can install and run Ollama inside its own Linux environment: tap \"Start Ollama\" on the Models screen. " +
                        "It keeps running in the background with a notification until you stop it. (The MIT edition needs the Linux add-on first: ⋮ → Tools & Linux.)",
                    steps = listOf("Termux: pkg install ollama", "Termux: termux-wake-lock (stops Android from pausing Termux)",
                        "Termux: " + LocalModels.serveCommand + "  (an 8k context and a smaller memory cache: Ollama's own default context on a phone is too short for the assistant)",
                        "PocketIDE: ⋮ > Models > Check again"),
                    keywords = listOf("termux", "ollama serve", "start")),
                HelpEntry("local-size", "Which model size should I pick?",
                    "Bigger models write better code but answer more slowly and need more memory. On most phones start with " +
                        "Qwen2.5 Coder 1.5B. With 8 GB of memory or more, try the 3B. 7B models work on 12 GB phones but are slow. " +
                        "The Models screen marks each one Runs well, Slow or Too big for your phone.",
                    keywords = listOf("size", "ram", "memory", "slow", "1.5b", "3b", "7b")),
                HelpEntry("local-space", "How much storage do models use?",
                    "About their download size (from 0.4 GB to 5 GB). Delete models you no longer use on the Models screen.",
                    keywords = listOf("storage", "space", "delete"))
            )),
        HelpSection(GIT, "GitHub sync",
            "The Git button pulls from and pushes to a GitHub repository through GitHub's API, so no git install is needed.",
            listOf(
                HelpEntry("git-token", "How do I make a GitHub token?",
                    "PocketIDE needs a fine-grained personal access token that can read and write your repository's contents.",
                    steps = listOf("On github.com open Settings > Developer settings > Personal access tokens > Fine-grained tokens.",
                        "Tap \"Generate new token\". Give it a name and an expiry date.",
                        "Repository access: \"Only select repositories\", then pick your repository.",
                        "Permissions > Repository permissions > Contents: Read and write.",
                        "Generate the token and copy it.",
                        "In PocketIDE tap Git > Setup, enter owner/repository, the branch (usually main) and the token."),
                    url = "https://github.com/settings/personal-access-tokens/new", urlLabel = "Open GitHub token page",
                    keywords = listOf("github", "token", "git", "push", "pull")),
                HelpEntry("git-flow", "How do pull and push work?",
                    "Pull first. Then edit, check Git > Changes, and Push. If someone else pushed in the meantime, PocketIDE asks you to pull " +
                        "first and lists files changed in both places, so nothing is overwritten silently.",
                    keywords = listOf("conflict", "changes", "revert"))
            )),
        HelpSection(AGENT, "Agent mode & commands",
            "How the AI applies changes and runs commands.",
            listOf(
                HelpEntry("agent-what", "What does Agent mode do?",
                    "Normally the AI's changes arrive as cards you Apply or Reject. In Agent mode they are applied straight away, " +
                        "the preview page is loaded, and any errors are sent back to the AI to fix (up to 3 tries). Undo changes reverts a whole turn.",
                    keywords = listOf("agent", "apply", "undo")),
                HelpEntry("agent-run", "Can the AI run commands?",
                    "Yes. Before answering, the AI may read files, search, list folders and run commands. Commands ask first unless " +
                        "\"Run commands from the assistant without asking\" is on in Settings. Each step shows as a chip you can tap.",
                    keywords = listOf("run", "command", "terminal", "autorun")),
                HelpEntry("agent-context", "What does the AI see?",
                    "The open file and files named in your request, or every project file if \"Send all project files\" is on. " +
                        "Large projects use more of a provider's free allowance.",
                    keywords = listOf("context", "files", "tokens"))
            )),
        HelpSection(LINUX, "Linux environment",
            "PocketIDE can install a small Alpine Linux system inside the app, so real tools run on the phone. The GPL edition carries PRoot, which runs it; the MIT edition gets PRoot from the small PocketIDE Linux add-on, downloaded when you set Linux up.",
            listOf(
                HelpEntry("linux-setup", "How do I set it up?",
                    "Open ⋮ > Tools & Linux and tap Install Linux (about 4 MB download; in the MIT edition, tap Get the Linux add-on first). Then use the one-tap installs " +
                        "for Python, Node.js, Git and more, or type apk add <name> (Alpine) or apt-get install <name> (Ubuntu) in the terminal.",
                    keywords = listOf("linux", "alpine", "install", "apk", "python", "node")),
                HelpEntry("linux-pkg", "\"pkg: not found\"?",
                    "pkg is the Termux app's installer. Inside PocketIDE's Linux the installer is apk on the Alpine base and apt-get on the Ubuntu base, for example apk add python3.",
                    keywords = listOf("pkg", "not found", "apk"))
            )),
        HelpSection(FILES, "Files and documents",
            "Any file can go into a project and come back out. Word, Excel, PowerPoint, PDF and archives open in the Code tab, and the AI can read them.",
            listOf(
                HelpEntry("files-add", "Add files from your phone",
                    "Files are copied into the project folder (up to 512 MB each; zips up to 1 GB unpacked). Pictures, PDFs and documents open in the Code tab; Word, Excel, PowerPoint and archives show their text read-only.",
                    steps = listOf("Open ⋮ → Add files from phone, or tap + Add files at the top of the ☰ file list. Long-press a folder to add into it.",
                        "Pick one or more files.",
                        "Tap one in the list to open it."),
                    keywords = listOf("upload", "import", "add file", "attach", "docx", "pdf", "excel", "word")),
                HelpEntry("files-save", "Save or download a file",
                    "Any project file can be saved to your phone, for example a document the AI edited or a program a command built.",
                    steps = listOf("Long-press a file in the ☰ list and choose Save a copy to phone. Long-press a folder for Save folder as zip.",
                        "Or open the file and use ⋯ → Save a copy to phone.",
                        "After the AI or a command makes a document, build or archive, a Files ready row with ⬇ buttons appears under the answer and under the Terminal."),
                    keywords = listOf("download", "export", "save", "share", "compile", "build", "apk", "output")),
                HelpEntry("files-ai", "Let the AI read or edit a document",
                    "The AI reads text files, Word (.docx), Excel (.xlsx), PowerPoint (.pptx), OpenDocument (.odt .ods .odp), EPUB, PDF text and the file list inside zip, jar and apk files. PDFs are read without scanning, so photographed pages give nothing. Old .doc, .xls and .ppt files cannot be read: save them as .docx, .xlsx or .pptx first. Pictures can be attached for models that can see them.",
                    steps = listOf("Tap 📎 in the AI tab and choose File from phone.",
                        "Choose Add to project (the AI can read and change it) or This message only (read-only).",
                        "Ask for what you want, for example \"Summarize this report\" or \"Change the title on slide 1 and replace Q3 with Q4\".",
                        "To edit without the AI, open the file in the Code tab and tap ✎: add a heading, replace text, set a cell, add a slide and more. Large models can also use these actions; models of 8B or under often cannot edit files reliably.",
                        "When it finishes, tap the ⬇ button under the answer to save the file to your phone."),
                    keywords = listOf("word", "excel", "powerpoint", "pdf", "docx", "xlsx", "pptx", "document", "spreadsheet", "attach"))
            )),
        HelpSection(TROUBLE, "Troubleshooting",
            "Common problems and what to do.",
            listOf(
                HelpEntry("trouble-401", "\"401\", \"invalid API key\" or \"unauthorized\"",
                    "The key is wrong, expired, or belongs to a different provider. Make a new key and paste it under the matching provider in Settings. " +
                        "Check there are no spaces before or after it.",
                    keywords = listOf("401", "403", "unauthorized", "invalid", "api key", "forbidden")),
                HelpEntry("trouble-429", "\"429\", \"rate limit\" or \"quota\"",
                    "You hit the free tier's limit. Wait a minute (per-minute limit) or until tomorrow (daily limit), or switch to another " +
                        "provider in Settings: each has its own allowance.",
                    keywords = listOf("429", "rate", "limit", "quota", "too many")),
                HelpEntry("trouble-model", "\"Model not found\" or \"model does not exist\"",
                    "Providers retire model names. Open Settings, tap Test connection, and pick a model from the list. For Ollama, download the model on the Models screen first.",
                    action = HelpAction.MODELS, keywords = listOf("model not found", "not found", "does not exist", "decommissioned", "retired")),
                HelpEntry("trouble-local", "Can't connect to the local server",
                    "Ollama or llama.cpp isn't running, or the address is wrong. Open ⋮ > Models to check and start Ollama. " +
                        "If it runs in Termux, make sure Termux is still open and ran termux-wake-lock.",
                    action = HelpAction.MODELS, keywords = listOf("could not reach", "connect", "refused", "127.0.0.1", "localhost", "timeout")),
                HelpEntry("trouble-crash", "The app closed by itself",
                    "Open PocketIDE again: it shows what went wrong with a Copy button. Paste that text wherever you're getting help.",
                    keywords = listOf("crash", "closed", "stopped")),
                HelpEntry("trouble-preview", "The Preview tab is blank",
                    "Preview shows the project's index.html (or the first .html file). Check the red error lines under the preview bar, " +
                        "and that file names in <script> and <link> tags match exactly. Preview can't show a server started from the terminal.",
                    keywords = listOf("preview", "blank", "white", "html"))
            ))
    )

    fun section(id: String): HelpSection? = sections.firstOrNull { it.id == id }

    /** Where an id points: a section id, or an entry id (returns the entry's section). */
    fun locate(id: String?): Pair<HelpSection, HelpEntry?>? {
        if (id == null) return null
        section(id)?.let { return it to null }
        for (s in sections) s.entries.firstOrNull { it.id == id }?.let { return s to it }
        return null
    }

    /** Entries matching every word of the query, searching titles, text and keywords. */
    fun search(query: String): List<Pair<HelpSection, HelpEntry>> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
        if (words.isEmpty()) return emptyList()
        val out = ArrayList<Pair<HelpSection, HelpEntry>>()
        for (s in sections) for (e in s.entries) {
            val hay = (e.title + " " + e.body + " " + e.steps.joinToString(" ") + " " + e.keywords.joinToString(" ")).lowercase()
            if (words.all { it in hay }) out.add(s to e)
        }
        return out
    }

    /** The help entry for a chat error: key problems go to that provider's key guide, the rest to troubleshooting. */
    fun forError(message: String, needsSettings: Boolean, providerId: String): String {
        val m = message.lowercase()
        val keyProblem = needsSettings || Regex("\\b(401|403)\\b").containsMatchIn(m) || "api key" in m || "unauthorized" in m || "invalid key" in m
        return when {
            keyProblem && providerId in keyProviders -> providerId
            keyProblem -> "trouble-401"
            Regex("\\b429\\b").containsMatchIn(m) || "rate limit" in m || "quota" in m || "too many requests" in m -> "trouble-429"
            "not found" in m && "model" in m || "does not exist" in m || "decommissioned" in m -> "trouble-model"
            "could not reach" in m || "connect" in m || "timed out" in m || "timeout" in m -> if (providerId in listOf("ollama", "llamacpp", "custom", "remote")) "trouble-local" else TROUBLE
            else -> TROUBLE
        }
    }
}
