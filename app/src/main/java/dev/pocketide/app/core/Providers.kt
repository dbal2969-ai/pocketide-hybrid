package dev.pocketide.app.core

enum class ProviderKind { DEMO, OPENAI, ANTHROPIC, HYBRID }

class Provider(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    val url: String = "",
    val model: String = "",
    val note: String = "",
    val keyless: Boolean = false,
    val custom: Boolean = false,
    /** For local servers: the address filled in until the user changes it. */
    val defaultBase: String = "",
    /** llama.cpp serves one model and ignores the name, so it may be left empty. */
    val modelOptional: Boolean = false
)

object Providers {
    val all: List<Provider> = listOf(
        Provider("demo", "Demo (no key)", ProviderKind.DEMO, keyless = true,
            note = "Sample replies so you can try the review flow. Pick a provider for real answers."),
        Provider("hybrid", "Hybrid AI (Gemini Nano + cloud)", ProviderKind.HYBRID, keyless = true,
            note = "Gemini Nano on this phone first, with a cloud Gemini model when Nano is missing or the request is long. No key or model name needed; set it up under Models → Hybrid AI. Works in chat and in the agent, with projects or without. In the agent, Gemini Nano works in small local model mode (one action per reply, core tools) and the cloud model uses real tool calls with every tool; a Nano reply that cannot be used goes to the cloud."),
        Provider("openrouter", "OpenRouter", ProviderKind.OPENAI, "https://openrouter.ai/api/v1/chat/completions", "poolside/laguna-s-2.1:free",
            "Free models have names ending in :free and a daily request limit. Model names change, so edit the model if this one stops working."),
        Provider("groq", "Groq", ProviderKind.OPENAI, "https://api.groq.com/openai/v1/chat/completions", "llama-3.3-70b-versatile",
            "Free tier with daily limits. Create a key in the Groq console. Edit the model if this one is retired."),
        Provider("cerebras", "Cerebras", ProviderKind.OPENAI, "https://api.cerebras.ai/v1/chat/completions", "llama-3.3-70b",
            "Free tier with daily token limits. Create a key in the Cerebras cloud console. Edit the model if this one is retired."),
        Provider("mistral", "Mistral", ProviderKind.OPENAI, "https://api.mistral.ai/v1/chat/completions", "codestral-latest",
            "Codestral is tuned for code. Mistral may now ask for a $10 deposit before it lets you create an API key, so it is no longer a no-cost start. Create a key in the Mistral console."),
        Provider("gemini", "Gemini", ProviderKind.OPENAI, "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", "gemini-2.5-flash",
            "Free tier with per-minute and daily limits. Create a key in Google AI Studio."),
        Provider("openai", "OpenAI", ProviderKind.OPENAI, "https://api.openai.com/v1/chat/completions", "gpt-4o", "Paid. Billed by OpenAI."),
        Provider("anthropic", "Anthropic", ProviderKind.ANTHROPIC, "https://api.anthropic.com/v1/messages", "claude-sonnet-5-5", "Paid. Billed by Anthropic."),
        Provider("haimaker", "haimaker", ProviderKind.OPENAI, "https://api.haimaker.ai/v1/chat/completions", "anthropic/claude-sonnet-5-5",
            "A gateway to Claude, GPT, Kimi, DeepSeek and other models with one key, billed per token (free credits on sign-up). Model names look like anthropic/claude-sonnet-5-5; tap Test connection to pick one. Tools work on models that support function calling; others switch to the text format by themselves."),
        Provider("deepseek", "DeepSeek", ProviderKind.OPENAI, "https://api.deepseek.com/chat/completions", "deepseek-flash",
            "Low-cost DeepSeek models, billed per token. Create a key on platform.deepseek.com. deepseek-flash is fast; deepseek-v4-pro is stronger. Tap Test connection to see the current names."),
        Provider("xai", "xAI (Grok)", ProviderKind.OPENAI, "https://api.x.ai/v1/chat/completions", "grok-4.7",
            "Grok models, billed per token. Create a key at console.x.ai. Edit the model or tap Test connection if this one is retired."),
        Provider("together", "Together AI", ProviderKind.OPENAI, "https://api.together.ai/v1/chat/completions", "openai/gpt-oss-120b",
            "Many open models (Qwen, DeepSeek, GLM, gpt-oss, Llama), billed per token. Create a key in your Together account settings, then tap Test connection to pick a model."),
        Provider("fireworks", "Fireworks AI", ProviderKind.OPENAI, "https://api.fireworks.ai/inference/v1/chat/completions", "accounts/fireworks/models/llama-v3p3-70b-instruct",
            "Fast open models, billed per token. Create a key in your Fireworks account (API Keys). Model names look like accounts/fireworks/models/<name>; tap Test connection to pick one."),
        Provider("deepinfra", "DeepInfra", ProviderKind.OPENAI, "https://api.deepinfra.com/v1/openai/chat/completions", "deepseek-ai/DeepSeek-V4-Flash-0731",
            "Over 100 open models (DeepSeek, Qwen, Llama, GLM, Kimi), billed per token. Create a key in your DeepInfra dashboard, then tap Test connection to pick a model."),
        Provider("moonshot", "Kimi (Moonshot AI)", ProviderKind.OPENAI, "https://api.moonshot.ai/v1/chat/completions", "kimi-k3.1-pro",
            "Kimi models, strong at long coding jobs and tool use, billed per token. Create a key at platform.kimi.ai. Tap Test connection to see the current names."),
        Provider("zai", "Z.ai (GLM)", ProviderKind.OPENAI, "https://api.z.ai/api/paas/v4/chat/completions", "glm-5.1",
            "GLM models from Z.ai, billed per token. Create a key at z.ai. On the GLM Coding Plan, use Custom server with https://api.z.ai/api/coding/paas/v4 instead."),
        Provider("nebius", "Nebius Token Factory", ProviderKind.OPENAI, "https://api.tokenfactory.nebius.com/v1/chat/completions", "openai/gpt-oss-20b",
            "Open models (DeepSeek, Qwen, Llama, gpt-oss) hosted in Europe and the US, billed per token. Create a key in Nebius Token Factory, then tap Test connection to pick a model."),
        Provider("nvidia", "NVIDIA NIM (free dev tier)", ProviderKind.OPENAI, "https://integrate.api.nvidia.com/v1/chat/completions", "openai/gpt-oss-20b",
            "Free for NVIDIA Developer Program members, with rate limits. Create a key at build.nvidia.com, paste it here, then tap Test connection and pick a model from the list. Free endpoints may log prompts."),
        Provider("huggingface", "Hugging Face (small free credit)", ProviderKind.OPENAI, "https://router.huggingface.co/v1/chat/completions", "Qwen/Qwen2.5-Coder-32B-Instruct",
            "A small monthly credit on a free account. Create a token at huggingface.co/settings/tokens (inference permission), paste it here, then tap Test connection to pick a model."),
        Provider("ollamacloud", "Ollama Cloud", ProviderKind.OPENAI, "https://ollama.com/v1/chat/completions", "gpt-oss:120b",
            "Large open models hosted by Ollama. Needs usage credits or a subscription: a free account can have no included usage, and then every request answers 402. Create a key in your Ollama account, paste it here, then tap Test connection to pick a model."),
        Provider("sambanova", "SambaNova (free tier)", ProviderKind.OPENAI, "https://api.sambanova.ai/v1/chat/completions", "Meta-Llama-3.3-70B-Instruct",
            "Fast Llama and other open models. The free tier has a low daily request cap (about 20 a day). Create a key in the SambaNova cloud console, then tap Test connection to pick a model."),
        Provider("cloudflare", "Cloudflare Workers AI (billed past a small free allowance)", ProviderKind.OPENAI, custom = true, defaultBase = "https://api.cloudflare.com/client/v4/accounts/ACCOUNT_ID/ai/v1",
            model = "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
            note = "Paste your Cloudflare account ID (Cloudflare dashboard → Workers AI → Get Account ID). The address is filled in for you. The key is an API token with Workers AI permission. Only a small daily allowance is free: usage past it is billed to your Cloudflare account, and large models use it up quickly. Check usage and set limits in the Cloudflare dashboard (Billing). Model names look like @cf/meta/llama-3.3-70b-instruct-fp8-fast."),
        Provider("kilo", "Kilo Code gateway (free models, no key)", ProviderKind.OPENAI, keyless = true, custom = true, defaultBase = "https://api.kilo.ai/api/gateway",
            model = "recursive/flint-v1.0:free", note = "Free models, about 200 requests an hour, no key needed. Flint is the default: it passed 87% of PocketIDE's benchmark tasks with 2 false \"done\" labels in 39, the best of the free models surveyed so far (November 2026); kilo-auto/free picks a different model each time. The free models change, and prompts may be logged, so don't send private code."),
        Provider("llm7", "LLM7.io (free, no key)", ProviderKind.OPENAI, keyless = true, custom = true, defaultBase = "https://api.llm7.io/v1",
            model = "gpt-oss:20b", note = "Anonymous use works with no key, within small limits. A free token raises them. Model names change, so tap Test connection to pick one."),
        Provider("ovh", "OVHcloud AI Endpoints (free, no key)", ProviderKind.OPENAI, keyless = true, custom = true, defaultBase = "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1",
            model = "gpt-oss-120b", note = "No key works but allows only 2 requests a minute. Use it as a backup. Tap Test connection to pick a model."),
        Provider(Engine.PROVIDER, "Built-in engine (on this phone)", ProviderKind.OPENAI, keyless = true, custom = true,
            defaultBase = Engine.BASE, model = "qwen3.5-4b",
            note = "Runs a model inside PocketIDE with llama.cpp, no Linux or Ollama needed. Pick and download a model under Menu ⋮ → Models (local AI) → Built-in engine; it starts by itself when you send a request."),
        Provider("llamacpp", "Local llama.cpp server", ProviderKind.OPENAI, keyless = true, custom = true,
            defaultBase = "http://127.0.0.1:8080/v1", modelOptional = true,
            note = "llama.cpp's llama-server running on this phone (for example in Termux) or on your network. Start it with: llama-server -m model.gguf --port 8080. The model name can stay empty."),
        Provider("ollama", "Ollama (local)", ProviderKind.OPENAI, keyless = true, custom = true,
            defaultBase = "http://127.0.0.1:11434/v1", model = "qwen3.5:4b",
            note = "Ollama running on this phone or on your network. Use Test connection to list the models it has pulled."),
        Provider("remote", "Remote server (GPU or PC)", ProviderKind.OPENAI, keyless = true, custom = true,
            note = "A bigger model running on your PC/Mac (Ollama over Tailscale) or a rented GPU (RunPod, Vast.ai). Enter its address ending in /v1, add a key only if it needs one, then tap Test connection. Menu ⋮ → Remote server setup has the steps."),
        Provider("custom", "Custom server", ProviderKind.OPENAI, keyless = true, custom = true,
            note = "Any server that speaks the OpenAI chat format, such as Ollama or llama.cpp running on this phone (Termux) or on your network. Example address: http://127.0.0.1:11434/v1")
    )

    /** Where to get a key (free tier or pay-as-you-go, as the note says). Keyless providers have none. */
    val keyUrls: Map<String, String> = mapOf(
        "openrouter" to "https://openrouter.ai/keys",
        "groq" to "https://console.groq.com/keys",
        "cerebras" to "https://cloud.cerebras.ai",
        "mistral" to "https://console.mistral.ai/api-keys",
        "gemini" to "https://aistudio.google.com/apikey",
        "openai" to "https://platform.openai.com/api-keys",
        "anthropic" to "https://console.anthropic.com/settings/keys",
        "haimaker" to "https://app.haimaker.ai/sign-up",
        "deepseek" to "https://platform.deepseek.com/api_keys",
        "xai" to "https://console.x.ai",
        "together" to "https://api.together.ai/settings/api-keys",
        "fireworks" to "https://fireworks.ai/account/api-keys",
        "nvidia" to "https://build.nvidia.com",
        "huggingface" to "https://huggingface.co/settings/tokens",
        "ollamacloud" to "https://ollama.com/settings/keys",
        "sambanova" to "https://cloud.sambanova.ai/apis",
        "cloudflare" to "https://dash.cloudflare.com/profile/api-tokens",
        "deepinfra" to "https://deepinfra.com/dash/api_keys",
        "moonshot" to "https://platform.kimi.ai/console/api-keys",
        "zai" to "https://z.ai/manage-apikey/apikey-list",
        "nebius" to "https://tokenfactory.nebius.com"
    )

    fun byId(id: String): Provider = all.firstOrNull { it.id == id } ?: all[0]

    /** The model list endpoint for an OpenAI-style server address (with or without /chat/completions). */
    fun modelsUrl(base: String): String? {
        val b = base.trim()
        if (!Regex("^https?://\\S+$").matches(b)) return null
        return b.trimEnd('/').replace(Regex("/chat/completions$"), "").trimEnd('/') + "/models"
    }

    /** Model names from a /models reply: OpenAI-style {"data":[{"id":..}]} or Ollama-style {"models":[{"name":..}]}. */
    /** Text-generation model names from Cloudflare's model search (result[].name, task.name); all @cf/ names when no task is given. */
    fun parseCloudflareModels(json: String): List<String> {
        val o = MiniJson.obj(MiniJson.parse(json)) ?: return emptyList()
        val all = (MiniJson.list(o["result"]) ?: emptyList()).mapNotNull { e ->
            val m = MiniJson.obj(e) ?: return@mapNotNull null
            val name = m["name"] as? String ?: return@mapNotNull null
            if (!name.startsWith("@cf/")) return@mapNotNull null
            name to ((MiniJson.obj(m["task"])?.get("name") as? String) ?: "")
        }
        val text = all.filter { it.second.isEmpty() || it.second.contains("Text Generation", true) }.map { it.first }
        return text.distinct()
    }

    fun parseModels(json: String): List<String> {
        val o = MiniJson.obj(MiniJson.parse(json)) ?: return emptyList()
        val out = ArrayList<String>()
        for (e in MiniJson.list(o["data"]) ?: emptyList()) (MiniJson.obj(e)?.get("id") as? String)?.let { out.add(it) }
        for (e in MiniJson.list(o["models"]) ?: emptyList()) (MiniJson.obj(e)?.let { it["name"] ?: it["model"] } as? String)?.let { if (it !in out) out.add(it) }
        return out
    }

    /** The chat endpoint for a custom server: the address given, with /chat/completions added when missing. */
    /** The Cloudflare Workers AI address for an account ID, or null when the ID is not 32 hex characters. */
    fun cloudflareBase(accountId: String): String? {
        val id = accountId.trim()
        return if (Regex("^[0-9a-fA-F]{32}$").matches(id)) "https://api.cloudflare.com/client/v4/accounts/$id/ai/v1" else null
    }

    /** The account ID inside a saved Cloudflare address, or "" when there is none. */
    fun cloudflareAccount(base: String): String =
        Regex("/accounts/([0-9a-fA-F]{32})/").find(base)?.groupValues?.get(1).orEmpty()

    fun customUrl(base: String): String? {
        val b = base.trim()
        if (!Regex("^https?://\\S+$").matches(b)) return null
        return if (Regex("/chat/completions/?$").containsMatchIn(b)) b else b.trimEnd('/') + "/chat/completions"
    }

    /** A server named for error messages by its host and port only: the path can hold an account ID (Cloudflare) or a token. */
    fun serverName(base: String): String =
        Regex("^https?://([^/?#@\\s]+@)?([^/?#\\s]+)").find(base.trim())?.groupValues?.get(2) ?: "the server"

    /** A hosted service with a fixed address (Cloudflare, Kilo …), named like the built-in providers rather than as "your server". */
    fun hosted(p: Provider): Boolean = p.custom && p.defaultBase.startsWith("https://")

    /** The provider's name without the note in brackets: "Cloudflare Workers AI". */
    fun shortName(p: Provider): String = p.name.substringBefore(" (").trim()
}

/** The three-file starter project and the canned replies used by Demo mode. */
object Sample {
    val files: Map<String, String> = linkedMapOf(
        "index.html" to listOf(
            "<!doctype html>", "<html lang=\"en\">", "<head>", "  <meta charset=\"utf-8\">",
            "  <meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">",
            "  <title>Hello</title>", "  <link rel=\"stylesheet\" href=\"style.css\">", "</head>", "<body>",
            "  <main>", "    <h1>Hello, PocketIDE</h1>", "    <p id=\"out\">Tap the button.</p>",
            "    <button id=\"btn\">Say hi</button>", "  </main>", "  <script src=\"app.js\"></script>", "</body>", "</html>", ""
        ).joinToString("\n"),
        "style.css" to listOf(
            "body {", "  font-family: system-ui, sans-serif;", "  display: grid;", "  place-items: center;",
            "  min-height: 100vh;", "  margin: 0;", "  background: #f6f5f1;", "}", "",
            "button {", "  font-size: 1.1rem;", "  padding: .6em 1.2em;", "  border: 0;", "  border-radius: 999px;",
            "  background: #2d56e8;", "  color: #fff;", "}", ""
        ).joinToString("\n"),
        "app.js" to listOf(
            "const out = document.getElementById('out');", "document.getElementById('btn').addEventListener('click', () => {",
            "  out.textContent = 'Hi from ' + new Date().toLocaleTimeString();", "  console.log('clicked');", "});", ""
        ).joinToString("\n")
    )

    private const val F3 = "```"

    fun demoReply(): String {
        val app = files.getValue("app.js").trimEnd() + "\n\n"
        val css = files.getValue("style.css").trimEnd() + "\n\n"
        return "Demo mode: this is a sample edit so you can try the review flow. It adds a click counter and a pressed state to the button. " +
            "Pick a provider in settings for real answers.\n\n" +
            F3 + "file:app.js\n" + app + "let clicks = 0;\nconst btn = document.getElementById('btn');\nbtn.addEventListener('click', () => {\n  clicks += 1;\n  btn.textContent = 'Clicked ' + clicks + 'x';\n});\n" + F3 + "\n\n" +
            F3 + "file:style.css\n" + css + "button:active {\n  transform: scale(.96);\n}\n" + F3
    }

    /** Agent demo: the first reply has a deliberate typo so you can watch the error come back and get fixed. */
    fun demoAgentReply(fix: Boolean): String {
        val base = files.getValue("app.js").trimEnd()
        val id = if (fix) "btn" else "button"
        val body = base + "\n\nlet clicks = 0;\nconst counter = document.getElementById('" + id + "');\ncounter.addEventListener('click', () => {\n  clicks += 1;\n  counter.textContent = 'Clicked ' + clicks + 'x';\n});\n"
        return (if (fix) "Found it: the button id is btn, not button. Fixed."
        else "Demo mode, agent: I added a click counter. This sample has a deliberate typo so you can watch the app catch the error and send it back for a fix.") +
            "\n\n" + F3 + "file:app.js\n" + body + F3
    }
}
