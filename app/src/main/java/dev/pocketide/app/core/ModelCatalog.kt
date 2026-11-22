package dev.pocketide.app.core

/** Phone-friendly models from ollama.com/library (sizes are the default q4_K_M downloads, checked October 2026). */
data class CatalogModel(val name: String, val title: String, val sizeMb: Int, val goodFor: String, val coding: Boolean, val recommended: Boolean = false,
                        /** The oldest Ollama that runs it ("" = any); Qwen3.5 needs 0.17.4 (1.5.178). */
                        val minOllama: String = "")

enum class ModelFit(val label: String) { GOOD("Runs well"), SLOW("Slow"), TOO_BIG("Too big for this phone") }

object ModelCatalog {
    /** True when Ollama [version] ("0.17.7", "0.40.16-rc1") is at least [min]; unknown versions pass. */
    fun ollamaAtLeast(version: String?, min: String): Boolean {
        if (min.isBlank() || version.isNullOrBlank()) return true
        fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').take(3).map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(version); val b = parts(min)
        for (i in 0 until 3) { val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }; if (x != y) return x > y }
        return true
    }

    val models: List<CatalogModel> = listOf(
        // 1.5.178: Qwen3.5 and Granite 4.2 lead the local lineup (research of 2026-11-17: qwen3.5:4b passed 4 of 5 coding tasks with
        // thinking off where qwen2.5-coder:7b did worse; Granite 4.2 8B passed 8 of 10 on a small agentic refactor where every model
        // of 4B or less passed 0). They need Ollama 0.17.4 or newer. Sizes are the full download.
        CatalogModel("qwen3.5:2b", "Qwen3.5 2B", 2683, "Questions and single small steps on phones with 6 GB or more. Too small to work on code alone.", true, minOllama = "0.17.4"),
        CatalogModel("qwen3.5:4b", "Qwen3.5 4B", 3324, "The best fit for phones with 8 GB: good at picking the next step and calling tools. Checks matter: it can still be wrong.", true, recommended = true, minOllama = "0.17.4"),
        CatalogModel("granite4.2:8b", "Granite 4.2 8B", 5348, "The strongest local coder here; phones with 12 GB or more. Slow to read long prompts.", true, recommended = true),
        CatalogModel("qwen3.5:9b", "Qwen3.5 9B", 6551, "Strong tool use; phones with 12 GB or more.", true, minOllama = "0.17.4"),
        CatalogModel("granite4.2:3b", "Granite 4.2 3B", 2244, "Small and quick; questions and lookups rather than changing code.", true),
        CatalogModel("qwen3.5:0.8b", "Qwen3.5 0.8B", 1322, "Tiny. Quick questions only.", false, minOllama = "0.17.4"),
        CatalogModel("qwen2.5-coder:1.5b", "Qwen2.5 Coder 1.5B", 986, "Older. Passed 0 to 1 of 7 standard tasks in PocketIDE's tests; for completions and questions.", true),
        CatalogModel("qwen2.5-coder:7b", "Qwen2.5 Coder 7B", 4700, "Older coder; slower and weaker at tool calls than Qwen3.5 4B.", true),
        CatalogModel("qwen3:0.6b", "Qwen3 0.6B", 523, "Tiny general model. Thinks before answering.", false),
        CatalogModel("qwen3:1.7b", "Qwen3 1.7B", 1400, "General help and explanations. Thinks before answering, so replies take longer.", false),
        CatalogModel("qwen3:4b", "Qwen3 4B", 2500, "Stronger general model with step-by-step thinking.", false),
        CatalogModel("llama3.2:1b", "Llama 3.2 1B", 1300, "Quick general questions and writing.", false),
        CatalogModel("llama3.2:3b", "Llama 3.2 3B", 2000, "General questions, summaries and writing.", false),
        CatalogModel("gemma3:1b", "Gemma 3 1B", 806, "Small general model from Google.", false),
        CatalogModel("gemma3:4b", "Gemma 3 4B", 3400, "Larger general model from Google.", false),
        CatalogModel("phi4-mini", "Phi-4 mini 3.8B", 2500, "Reasoning and maths from Microsoft.", false),
        CatalogModel("deepseek-r1:1.5b", "DeepSeek-R1 1.5B", 1100, "Writes out its reasoning first; slower answers.", false)
    )

    /**
     * A rough guide, not a guarantee. A model needs about its download size in memory plus ~30% for
     * context and the runtime, plus ~0.5 GB. Android and other apps keep a large share of memory, so:
     * up to 35% of the phone's memory runs well, up to 65% runs but slowly, more is too big.
     * Phones run models on the CPU, so anything over 3 GB is at best "Slow" whatever the memory.
     */
    fun fit(sizeBytes: Long, totalRamBytes: Long): ModelFit {
        if (totalRamBytes <= 0) return if (sizeBytes > 3_000_000_000L) ModelFit.SLOW else ModelFit.GOOD
        val need = sizeBytes * 1.3 + 500_000_000.0
        val share = need / totalRamBytes
        return when {
            share > 0.65 -> ModelFit.TOO_BIG
            share > 0.35 || sizeBytes > 3_000_000_000L -> ModelFit.SLOW
            else -> ModelFit.GOOD
        }
    }

    fun fit(m: CatalogModel, totalRamBytes: Long): ModelFit = fit(m.sizeMb * 1_000_000L, totalRamBytes)
}
