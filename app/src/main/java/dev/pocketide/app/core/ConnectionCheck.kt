package dev.pocketide.app.core

/**
 * What Test connection tells the person. The real test is one tiny chat request with the chosen model: a model list can
 * be public (OpenRouter, Ollama Cloud and others answer without a key), missing (Cloudflare) or need other permissions,
 * so a list alone can say "connected" while chat fails, or fail while chat works. Pure wording; the requests are elsewhere.
 */
object ConnectionCheck {
    /** True when a chat request with [code] means the key and model work (429: they work, the free limit is used up for now). */
    fun works(code: Int): Boolean = code in 200..299 || code == 429

    /** The person's message after the chat request. */
    fun chatVerdict(provider: Provider, code: Int, body: String, model: String, key: String): String {
        val why = SseDecoder.errorMessage(body)?.replace(Regex("\\s+"), " ")?.take(200)
        val m = model.ifBlank { "the default model" }
        return when {
            code in 200..299 -> "Connected to ${provider.name}. Chat works with $m."
            code == 429 -> "Connected to ${provider.name}, but it is limiting requests right now (free limits). Chat with $m should work again shortly."
            key.trim().startsWith("ssh-") -> "That is a device public key (it starts with ssh-), not an API key. Create an API key on ${provider.name}'s keys page and paste that."
            (code == 401 || code == 403) && provider.keyless -> "${provider.name} refused the request ($code)" + (if (why != null) ": $why" else "") + ". It may be limiting anonymous use right now; try again later."
            code == 401 || code == 403 -> "${provider.name} refused the key ($code)" + (if (why != null) ": $why" else "") + ". " + keyHint(provider)
            code == 404 || (code == 400 && why?.contains("model", true) == true) -> "${provider.name} does not know the model \"$m\"" + (if (why != null) " ($why)" else "") + ". Pick one from the list."
            code == 402 -> "${provider.name} says the account has no credit left ($code)" + (if (why != null) ": $why" else "") + "."
            code == 405 -> "${provider.name} answered 405: the server address looks wrong." + if (provider.id == "cloudflare") " Check the account ID." else ""
            code < 0 -> "Could not reach ${provider.name}: $body"
            else -> "${provider.name} answered $code" + (if (why != null) ": $why" else ".")
        }
    }

    fun keyHint(p: Provider): String = when (p.id) {
        "ollamacloud" -> "Use an API key from ollama.com/settings/keys (not a device key), then tap Save and Test connection again."
        "cloudflare" -> "Use an API token with Workers AI permission (not the Global API Key)."
        "gemini" -> "Use a key from Google AI Studio (aistudio.google.com/apikey)."
        "huggingface" -> "Use a Hugging Face access token with Inference Providers permission."
        "openrouter" -> "Use a key from openrouter.ai/keys."
        "haimaker" -> "Use an API key from your haimaker account (paste it without \"Bearer\")."
        "deepseek" -> "Use a key from platform.deepseek.com (API keys)."
        "xai" -> "Use a key from console.x.ai."
        "together" -> "Use an API key from your Together AI account settings."
        "fireworks" -> "Use an API key from your Fireworks account (API Keys)."
        "deepinfra" -> "Use an API key from your DeepInfra dashboard (API keys)."
        "moonshot" -> "Use a key from platform.kimi.ai (API keys)."
        "zai" -> "Use an API key from your z.ai account. A GLM Coding Plan key only works on the coding address (Custom server)."
        "nebius" -> "Use an API key from Nebius Token Factory."
        else -> "Check the API key, then tap Save and Test connection again."
    }

    /** Added after the chat result when the model list could not be read. */
    fun listNote(provider: Provider, listError: String?): String =
        if (listError == null) "" else "\n(${provider.name} did not give a model list: ${listError.take(120)} Type a model name instead.)"

    /** When the chosen model is not in the provider's list: the warning, naming a suggestion when there is one. */
    fun notListed(provider: Provider, model: String, suggestion: String?, answered: Boolean = false): String =
        // 1.5.137: Z.ai answered glm-4.6v-flash (free, and busy) yet the app called it "may have been retired" and suggested
        // another model; some providers list only part of what they serve. When the test reached the model, say only that.
        if (answered) "\"$model\" is not in ${provider.name}'s model list, but ${provider.name} took the request for it, so it is still served. Some providers list only part of their models."
        else "\"$model\" is not in ${provider.name}'s model list (it may have been retired)" + (if (suggestion != null) ". Suggested: $suggestion" else "") + ". Pick a model below."
}
