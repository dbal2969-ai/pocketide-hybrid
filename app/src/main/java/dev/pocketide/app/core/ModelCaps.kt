package dev.pocketide.app.core

/** What Anthropic's model list says about each model (real reply limit, whether it reads images and PDFs), kept so the app stops guessing from the name. */
object ModelCaps {
    class Caps(val maxTokens: Int, val image: Boolean?, val pdf: Boolean?)

    private val known = HashMap<String, Caps>()
    @Volatile var onChange: ((String) -> Unit)? = null
    @Volatile var tried = false

    @Synchronized fun get(model: String): Caps? = known[model]
    @Synchronized fun known(model: String): Boolean = known.containsKey(model)

    /** The reply length the model really allows, or null when unknown. */
    fun maxTokens(model: String): Int? = get(model)?.maxTokens?.takeIf { it > 0 }

    /** True or false when the list said so, null when unknown. */
    fun image(model: String): Boolean? = get(model)?.image

    /** Reads a /v1/models reply. */
    fun parse(json: String): Map<String, Caps> {
        val out = LinkedHashMap<String, Caps>()
        val data = try { MiniJson.list(MiniJson.obj(MiniJson.parse(json))?.get("data")) } catch (e: MiniJson.JsonError) { null } ?: return out
        for (x in data) {
            val o = MiniJson.obj(x) ?: continue
            val id = o["id"] as? String ?: continue
            val max = (o["max_tokens"] as? Double)?.toInt() ?: 0
            val caps = MiniJson.obj(o["capabilities"])
            fun sup(k: String): Boolean? = (MiniJson.obj(caps?.get(k))?.get("supported") as? Boolean)
            out[id] = Caps(max, sup("image_input"), sup("pdf_input"))
        }
        return out
    }

    @Synchronized fun merge(m: Map<String, Caps>) {
        if (m.isEmpty()) return
        known.putAll(m)
        onChange?.invoke(encode())
    }

    private fun encode(): String = known.entries.joinToString("\n") { (k, v) -> listOf(k, v.maxTokens, v.image ?: "", v.pdf ?: "").joinToString("\t") }

    @Synchronized fun load(text: String) {
        known.clear()
        for (line in text.lines()) {
            val p = line.split('\t')
            if (p.size < 4 || p[0].isBlank()) continue
            known[p[0]] = Caps(p[1].toIntOrNull() ?: 0, p[2].toBooleanStrictOrNull(), p[3].toBooleanStrictOrNull())
        }
    }
}
