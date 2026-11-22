package dev.pocketide.app.core

/**
 * Groups models by what they are designed for, so a list of hundreds (OpenRouter) is easy to choose from: coding and agents,
 * reasoning, seeing images, general chat, small and fast, and models that are not for chat at all. The group comes from the
 * model's name; when the provider's list says more (OpenRouter lists tool support, image input and price), that is used too.
 * Tool calling matters most here: models with it get PocketIDE's live plan and checks after every write.
 */
object ModelPurpose {
    enum class Group(val label: String) {
        CODING("Coding and agents"),
        REASONING("Reasoning (thinks before answering)"),
        VISION("Sees images"),
        GENERAL("General chat"),
        FAST("Small and fast"),
        NOT_CHAT("Not for chat (speech, embeddings, images, safety)")
    }

    /** What a provider's model list says about one model; null fields are unknown. */
    data class Meta(val tools: Boolean?, val image: Boolean?, val free: Boolean?)

    data class Info(val id: String, val group: Group, val tools: Boolean?, val image: Boolean?, val free: Boolean)

    private val NOT_CHAT = Regex("""(whisper|tts|orpheus|guard|embed|moderation|rerank|transcrib|dall-e|speech|audio-|-audio|image-gen|imagen|veo|lyria|flux|stable-diffusion|sdxl|kandinsky|translate|riva-|parakeet|canary-|nv-embed|clip\b)""", RegexOption.IGNORE_CASE)
    private val CODING = Regex("""(coder|codestral|devstral|codex|codegemma|starcoder|granite-.*code|code-?llama|deepcoder|swe-|kimi-k2|glm-4\.[5-9]|glm-[5-9]|qwen3-coder|laguna|north-.*code|claude-(opus|sonnet)|claude-[3-9].*(opus|sonnet)|minimax-m)""", RegexOption.IGNORE_CASE)
    private val REASONING = Regex("""(\br1\b|-r1|deepseek-reasoner|\bo[134](-|$|\b)|qwq|reason|thinking|magistral|gpt-oss|sonar-reasoning|hermes-4)""", RegexOption.IGNORE_CASE)
    private val VISION = Regex("""(vision|-vl\b|-vl-|vl-|llava|pixtral|omni|gemma-?3|llama-?4|qvq|moondream|minicpm-v)""", RegexOption.IGNORE_CASE)
    private val FAST = Regex("""(mini\b|-mini|nano|flash-lite|haiku|instant|tiny|small|-lite\b|\b(1|1\.5|2|3|4|7|8)b\b)""", RegexOption.IGNORE_CASE)

    private val ACTIVE = Regex("""-a(\d+(?:\.\d+)?)b\b""", RegexOption.IGNORE_CASE)

    /** Active parameters of a mixture-of-experts model named like "29b-a4b" (4 billion active), or null. */
    fun active(name: String): Double? = ACTIVE.find(name)?.groupValues?.get(1)?.toDoubleOrNull()

    /** The group for [id], using [meta] when the provider listed it. */
    fun group(id: String, meta: Meta? = null): Group {
        val n = id.substringAfterLast('/')
        return when {
            NOT_CHAT.containsMatchIn(n) -> Group.NOT_CHAT
            CODING.containsMatchIn(n) -> Group.CODING
            REASONING.containsMatchIn(n) -> Group.REASONING
            VISION.containsMatchIn(n) || (meta?.image == true && !FAST.containsMatchIn(n)) -> Group.VISION
            FAST.containsMatchIn(n) || ((active(n) ?: ModelSize.billions(n))?.let { it <= 9.0 } ?: false) -> Group.FAST
            else -> Group.GENERAL
        }
    }

    fun info(id: String, meta: Meta?): Info =
        Info(id, group(id, meta), meta?.tools, meta?.image, meta?.free ?: id.endsWith(":free"))

    /**
     * The models in display order: by group, then models with tool calling first, then by name. Empty groups are left out;
     * not-for-chat models only when [includeNotChat].
     */
    fun grouped(ids: List<String>, meta: Map<String, Meta>, includeNotChat: Boolean): List<Pair<Group, List<Info>>> {
        val infos = ids.distinct().map { info(it, meta[it]) }.filter { includeNotChat || it.group != Group.NOT_CHAT }
        return Group.values().mapNotNull { g ->
            val list = infos.filter { it.group == g }.sortedWith(compareBy<Info> { if (it.tools == true) 0 else 1 }.thenBy { it.id.lowercase() })
            if (list.isEmpty()) null else g to list
        }
    }

    /** Short tags shown after a model's name. */
    fun tags(i: Info): String = listOfNotNull(
        if (i.tools == true) "tools" else null,
        if (i.image == true && i.group != Group.VISION) "images" else null,
        if (i.free) "free" else null
    ).joinToString(" · ")

    /** Reads what an OpenRouter-style /models reply says about each model: supported_parameters, architecture.input_modalities, pricing. */
    fun parseMeta(json: String): Map<String, Meta> {
        val data = try { MiniJson.list(MiniJson.obj(MiniJson.parse(json))?.get("data")) } catch (e: Exception) { null } ?: return emptyMap()
        val out = LinkedHashMap<String, Meta>()
        for (x in data) {
            val o = MiniJson.obj(x) ?: continue
            val id = o["id"] as? String ?: continue
            val params = MiniJson.list(o["supported_parameters"])?.mapNotNull { it as? String }
            val inputs = MiniJson.list(MiniJson.obj(o["architecture"])?.get("input_modalities"))?.mapNotNull { it as? String }
            val pricing = MiniJson.obj(o["pricing"])
            val free = pricing?.let { p -> (p["prompt"] as? String)?.toDoubleOrNull() == 0.0 && (p["completion"] as? String)?.toDoubleOrNull() == 0.0 }
            if (params == null && inputs == null && pricing == null) continue
            out[id] = Meta(params?.contains("tools"), inputs?.contains("image"), free)
        }
        return out
    }

    /** What the last model list said, per model id; filled by the picker's fetch. */
    private val known = HashMap<String, Meta>()
    @Synchronized fun remember(m: Map<String, Meta>) { known.putAll(m) }
    @Synchronized fun known(): Map<String, Meta> = HashMap(known)
}
