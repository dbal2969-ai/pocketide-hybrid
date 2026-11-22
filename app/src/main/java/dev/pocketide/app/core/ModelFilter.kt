package dev.pocketide.app.core

/**
 * Search and filters for a provider's model list. Search words match any part of a model name, with or without the
 * separators (so "kimik2" finds "kimi-k2" and "r1 0528" finds "deepseek-r1-0528"). Filter words, typed in the search box
 * or picked as chips, keep only models of that kind; several filters must all hold.
 */
object ModelFilter {
    enum class Kind(val label: String, val words: Set<String>) {
        TOOLS("Tools", setOf("tools", "tool")),
        IMAGES("Images", setOf("images", "image", "vision")),
        FREE("Free", setOf("free")),
        CODING("Coding", setOf("coding", "code")),
        REASONING("Reasoning", setOf("reasoning", "thinking")),
        FAST("Small & fast", setOf("fast", "small")),
        WORKS("Works here", setOf("works", "working"))
    }

    class Query(val terms: List<String>, val kinds: Set<Kind>) {
        val isEmpty get() = terms.isEmpty() && kinds.isEmpty()
    }

    /** Splits typed text into name words and filter words; [chips] are filters picked by tapping. */
    fun parse(text: String, chips: Set<Kind> = emptySet()): Query {
        val kinds = LinkedHashSet(chips)
        val terms = ArrayList<String>()
        for (w in text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val k = Kind.values().firstOrNull { w in it.words }
            if (k != null) kinds.add(k) else terms.add(w)
        }
        return Query(terms, kinds)
    }

    private fun squash(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    /** True when every search word is part of the name, read with or without separators. */
    fun nameMatches(id: String, terms: List<String>): Boolean {
        if (terms.isEmpty()) return true
        val low = id.lowercase(); val flat = squash(id)
        return terms.all { t -> low.contains(t) || (squash(t).isNotEmpty() && flat.contains(squash(t))) }
    }

    /**
     * [refused] is the provider's reason for refusing this model, or null when it works. [facts] is what the provider's list
     * says about the model, when known; it can add a kind the name alone does not show (a coding model that also reasons).
     */
    fun kindMatches(info: ModelPurpose.Info, kinds: Set<Kind>, refused: String?, facts: ModelFacts.Facts? = null): Boolean = kinds.all { k ->
        when (k) {
            Kind.TOOLS -> info.tools == true || facts?.tools == true
            Kind.IMAGES -> info.image == true || facts?.image == true || info.group == ModelPurpose.Group.VISION
            Kind.FREE -> info.free || facts?.free == true
            Kind.CODING -> info.group == ModelPurpose.Group.CODING
            Kind.REASONING -> info.group == ModelPurpose.Group.REASONING || facts?.reasoning == true
            Kind.FAST -> info.group == ModelPurpose.Group.FAST
            Kind.WORKS -> refused == null
        }
    }
}
