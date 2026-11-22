package dev.pocketide.app.core

/**
 * What each provider says about its models, kept from their model lists: a short description, the context window, the
 * reply limit, tool calling, image input, reasoning and price. Read from the formats the providers use:
 * OpenRouter (description, context_length, supported_parameters, architecture, pricing, top_provider),
 * Mistral (capabilities, max_context_length), Groq and other OpenAI-style lists (context_window, max_completion_tokens),
 * Gemini (description, inputTokenLimit, outputTokenLimit), Cloudflare model search (description, properties) and
 * Anthropic (display_name, max_input_tokens, capabilities). Fields a provider does not report stay null.
 */
object ModelFacts {
    data class Facts(
        val provider: String,
        val id: String,
        val description: String = "",
        val context: Int? = null,
        val maxOutput: Int? = null,
        val tools: Boolean? = null,
        val image: Boolean? = null,
        val reasoning: Boolean? = null,
        val free: Boolean? = null,
        /** Dollars per million input tokens, when listed. */
        val inPerM: Double? = null,
        /** Dollars per million output tokens, when listed. */
        val outPerM: Double? = null,
        /** Why the provider refused this model in chat ("not offered (404)", "needs sign-in"), or null. */
        val unavailable: String? = null
    ) {
        val key: String get() = "$provider/$id"
    }

    const val DESCRIPTION_MAX = 240

    private fun num(v: Any?): Int? = when (v) { is Number -> v.toInt(); is String -> v.toDoubleOrNull()?.toInt(); else -> null }
    private fun dbl(v: Any?): Double? = when (v) { is Number -> v.toDouble(); is String -> v.toDoubleOrNull(); else -> null }
    private fun short(s: Any?): String = (s as? String).orEmpty().replace(Regex("\\s+"), " ").trim().let { if (it.length <= DESCRIPTION_MAX) it else it.take(DESCRIPTION_MAX - 1).trimEnd() + "…" }

    /** Facts from a provider's /models reply, whatever its format. Models that are not for chat are left out. */
    fun parse(provider: String, json: String): List<Facts> {
        val root = try { MiniJson.obj(MiniJson.parse(json)) } catch (e: Exception) { null } ?: return emptyList()
        val list = MiniJson.list(root["data"]) ?: MiniJson.list(root["models"]) ?: MiniJson.list(root["result"]) ?: return emptyList()
        val out = ArrayList<Facts>()
        for (x in list) {
            val o = MiniJson.obj(x) ?: continue
            // Cloudflare lists a UUID as id and the model name ("@cf/…") as name
            val id = ((o["name"] as? String)?.takeIf { it.startsWith("@cf/") } ?: (o["id"] ?: o["name"]) as? String)?.removePrefix("models/") ?: continue
            if (ModelPurpose.group(id) == ModelPurpose.Group.NOT_CHAT) continue
            // OpenRouter
            val params = MiniJson.list(o["supported_parameters"])?.mapNotNull { it as? String }
            val inputs = MiniJson.list(MiniJson.obj(o["architecture"])?.get("input_modalities"))?.mapNotNull { it as? String }
            val pricing = MiniJson.obj(o["pricing"])
            val top = MiniJson.obj(o["top_provider"])
            // Mistral / Anthropic capabilities
            val caps = MiniJson.obj(o["capabilities"])
            fun cap(k: String): Boolean? = when (val c = caps?.get(k)) { is Boolean -> c; is Map<*, *> -> c["supported"] as? Boolean; else -> null }
            // Cloudflare: properties [{property_id, value}]
            val props = MiniJson.list(o["properties"])?.mapNotNull { MiniJson.obj(it) }?.associate { (it["property_id"] as? String).orEmpty() to it["value"] } ?: emptyMap()
            // Gemini: supportedGenerationMethods must allow chat
            val methods = MiniJson.list(o["supportedGenerationMethods"])?.mapNotNull { it as? String }
            if (methods != null && "generateContent" !in methods) continue
            val inPrice = dbl(pricing?.get("prompt"))
            val outPrice = dbl(pricing?.get("completion"))
            out.add(Facts(
                provider = provider,
                id = id,
                description = short(o["description"] ?: o["display_name"]),
                context = num(o["context_length"] ?: o["context_window"] ?: o["max_context_length"] ?: o["inputTokenLimit"] ?: o["max_input_tokens"] ?: props["context_window"] ?: top?.get("context_length")),
                maxOutput = num(top?.get("max_completion_tokens") ?: o["max_completion_tokens"] ?: o["outputTokenLimit"] ?: o["max_tokens"] ?: props["max_tokens"]),
                tools = params?.contains("tools") ?: cap("function_calling") ?: (props["function_calling"]?.toString()?.equals("true", true)),
                image = inputs?.contains("image") ?: cap("vision") ?: cap("image_input"),
                reasoning = params?.let { it.contains("reasoning") || it.contains("include_reasoning") } ?: cap("thinking"),
                free = if (inPrice != null && outPrice != null) inPrice == 0.0 && outPrice == 0.0 else if (id.endsWith(":free")) true else null,
                inPerM = inPrice?.let { it * 1_000_000 },
                outPerM = outPrice?.let { it * 1_000_000 },
                unavailable = known["$provider/$id"]?.unavailable
            ))
        }
        return out
    }

    /** One line about a model for the person, e.g. "tool calling · 131k context · free · Optimized for agentic coding…". */
    fun summary(f: Facts): String = listOfNotNull(
        if (f.tools == true) "tool calling" else if (f.tools == false) "no tool calling" else null,
        f.context?.let { "${it / 1000}k context" },
        f.maxOutput?.let { "replies up to ${it / 1000}k" },
        if (f.image == true) "reads images" else null,
        if (f.reasoning == true) "reasoning" else null,
        f.unavailable?.let { "✕ $it" },
        if (f.free == true) "free" else f.inPerM?.let { "$" + String.format(java.util.Locale.US, "%.2f", it) + "/M in" },
        f.description.takeIf { it.isNotEmpty() }?.let { "\"$it\"" }
    ).joinToString(" · ")

    /**
     * Learns from a chat error that a model cannot be used ("Request failed (404)", "No endpoints found", "You need to sign
     * in to use this model") or how small its context is ("maximum context length is 8192 tokens"), so the model list and
     * the recommendations stop offering it. Returns true when something was learned.
     */
    fun learnFromError(provider: String, id: String, message: String): Boolean {
        if (provider.isBlank() || id.isBlank()) return false
        val m = message.lowercase()
        val ctx = Regex("""maximum context length is (\d+)""").find(m)?.groupValues?.get(1)?.toIntOrNull()
        val why = when {
            Regex("""\(404\)|http 404|no endpoints found|model[^.]{0,40}(not found|does not exist)|unknown model""").containsMatchIn(m) -> "not offered for chat (404)"
            m.contains("sign in to use this model") -> "needs sign-in"
            // OpenRouter keeps some free models for apps on its list: this app is refused (HTTP 403)
            m.contains("only available on agentic harnesses") || (m.contains("403") && m.contains("openrouter.ai/apps")) -> "only for apps OpenRouter lists (403)"
            m.contains("returned an empty reply") -> "gave only empty replies in chat"
            else -> null
        }
        if (why == null && ctx == null) return false
        synchronized(this) {
            val f = known["$provider/$id"] ?: Facts(provider, id)
            known[f.key] = f.copy(unavailable = why ?: f.unavailable, context = ctx ?: f.context)
        }
        onChange?.invoke(encode())
        return true
    }

    /** Marks a model that answered but could not do the work (prints files as text, empty replies), so lists and advice avoid it. */
    fun markUnreliable(provider: String, id: String, why: String) {
        if (provider.isBlank() || id.isBlank()) return
        synchronized(this) {
            val f = known["$provider/$id"] ?: Facts(provider, id)
            known[f.key] = f.copy(unavailable = why)
        }
        onChange?.invoke(encode())
    }

    /** A model the person picked again is given another chance. */
    @Synchronized fun clearUnavailable(provider: String, id: String) {
        val f = known["$provider/$id"] ?: return
        if (f.unavailable == null) return
        known[f.key] = f.copy(unavailable = null)
        onChange?.invoke(encode())
    }

    // ---- kept between runs ----

    private val known = LinkedHashMap<String, Facts>()
    @Volatile var onChange: ((String) -> Unit)? = null

    @Synchronized fun remember(list: List<Facts>) {
        if (list.isEmpty()) return
        for (f in list) known[f.key] = f
        onChange?.invoke(encode())
    }

    @Synchronized fun all(): List<Facts> = known.values.toList()
    @Synchronized fun get(provider: String, id: String): Facts? = known["$provider/$id"]

    /**
     * The model's context window: what a list or an error said, else for a local server the window the app sets it up with
     * (8K on this phone, 32K for Ollama on the person's computer, PcOllama).
     */
    fun context(provider: String, id: String, base: String = ""): Int? =
        get(provider, id)?.context ?: if (!LocalModels.isLocal(provider, base)) null
            else if (PcOllama.isRemote(base)) PcOllama.CONTEXT else LocalModels.DEFAULT_CONTEXT

    /** Records what a model can do when the app set it up itself (its context window, whether it sees images). */
    fun declare(provider: String, id: String, context: Int?, image: Boolean?) {
        synchronized(this) {
            val f = known["$provider/$id"] ?: Facts(provider, id)
            known[f.key] = f.copy(context = context ?: f.context, image = image ?: f.image, unavailable = null)
        }
        onChange?.invoke(encode())
    }

    /** Records the window size a "too long" error names. */
    fun learnContext(provider: String, id: String, context: Int) {
        if (provider.isBlank() || id.isBlank() || context <= 0) return
        synchronized(this) {
            val f = known["$provider/$id"] ?: Facts(provider, id)
            known[f.key] = f.copy(context = context)
        }
        onChange?.invoke(encode())
    }

    @Synchronized fun encode(): String = MiniJson.write(known.values.map { f ->
        linkedMapOf("p" to f.provider, "i" to f.id, "d" to f.description, "c" to f.context, "o" to f.maxOutput, "t" to f.tools, "v" to f.image,
            "r" to f.reasoning, "f" to f.free, "$" to f.inPerM, "$$" to f.outPerM, "x" to f.unavailable)
    })

    @Synchronized fun load(json: String?) {
        known.clear()
        val l = try { MiniJson.parse(json.orEmpty()) as? List<*> } catch (e: Exception) { null } ?: return
        for (x in l) {
            val o = MiniJson.obj(x) ?: continue
            val f = Facts(o["p"] as? String ?: continue, o["i"] as? String ?: continue, o["d"] as? String ?: "", num(o["c"]), num(o["o"]),
                o["t"] as? Boolean, o["v"] as? Boolean, o["r"] as? Boolean, o["f"] as? Boolean, dbl(o["$"]), dbl(o["$$"]), o["x"] as? String)
            known[f.key] = f
        }
    }
}
