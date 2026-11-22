package dev.pocketide.app.core

/**
 * The built-in engine (1.5.182): llama.cpp's llama-server compiled for the phone (arm64, dotprod and fp16, KleidiAI kernels) and
 * shipped inside the app, so a local model runs at native speed with no Linux environment, no Termux and no Ollama to install.
 * Operit and other on-phone agents run GGUF models in the app the same way. The server speaks the same OpenAI-style API as Ollama,
 * so the agent, small mode and the 8K sizing work unchanged. Pure helpers here; EngineHost starts and stops the process.
 */
object Engine {
    const val PROVIDER = "engine"
    const val PORT = 8181
    const val BASE = "http://127.0.0.1:$PORT/v1"
    /** The program, packaged as a library so Android unpacks it where an app may run programs (nativeLibraryDir). */
    const val BINARY = "libllama_server.so"
    /** The phone settings used for Ollama (LocalModels.OLLAMA_SETTINGS): an 8K window and a q8_0 cache. */
    val CONTEXT: Int get() = LocalModels.DEFAULT_CONTEXT

    class Model(
        val id: String,
        val name: String,
        val repo: String,
        val file: String,
        val bytes: Long,
        val sha256: String,
        /** Phone memory it needs to run comfortably, in GB. */
        val minRamGb: Int,
        val note: String
    ) {
        val url: String get() = "https://huggingface.co/$repo/resolve/main/$file"
        /** Billions of parameters, for SmallLocal (small mode for 8B or less). */
        val sizeB: Double get() = Regex("""(\d+(?:\.\d+)?)[bB]""").find(id)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
    }

    /**
     * Sizes and checksums from Hugging Face (2026-11-18). The order and notes follow PocketIDE's runs on real Ollama with the phone
     * settings (November 2026): qwen3.5:4b and granite4.2:8b passed 7/7 easy tasks and about half of the hard ones; the 2B and smaller
     * pass little and often say "done" wrongly, so they are for quick questions.
     */
    val MODELS: List<Model> = listOf(
        Model("qwen3.5-4b", "Qwen3.5 4B", "unsloth/Qwen3.5-4B-GGUF", "Qwen3.5-4B-Q4_K_M.gguf", 2_740_937_888L,
            "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4", 8,
            "Recommended for phones with 8 GB or more. Fast, and the best small model in PocketIDE's tests."),
        Model("granite-4.2-8b", "Granite 4.2 8B", "ibm-granite/granite-4.2-8b-GGUF", "granite-4.2-8b-Q4_K_M.gguf", 5_347_917_952L,
            "16a9369d0805f80b7377d25d87f937a90c05dc04ad79173a52001e42c9aab311", 12,
            "For phones with 12 GB or more. Slower than Qwen3.5 4B, a little more accurate and more honest about what it checked."),
        Model("qwen3.5-9b", "Qwen3.5 9B", "unsloth/Qwen3.5-9B-GGUF", "Qwen3.5-9B-Q4_K_M.gguf", 5_680_522_464L,
            "03b74727a860a56338e042c4420bb3f04b2fec5734175f4cb9fa853daf52b7e8", 12,
            "For phones with 12 GB or more. Not yet measured in PocketIDE's own runs."),
        Model("qwen3.5-2b", "Qwen3.5 2B", "unsloth/Qwen3.5-2B-GGUF", "Qwen3.5-2B-Q4_K_M.gguf", 1_280_835_840L,
            "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223", 4,
            "For phones with 4-6 GB. Quick answers and small edits; it often says \"done\" when it is not."),
        Model("qwen3.5-0.8b", "Qwen3.5 0.8B", "unsloth/Qwen3.5-0.8B-GGUF", "Qwen3.5-0.8B-Q4_K_M.gguf", 532_517_120L,
            "bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517", 3,
            "The smallest. Questions about code, not agent work."))

    fun byId(id: String): Model? = MODELS.firstOrNull { it.id == id }

    /** The model to suggest for a phone with [ramBytes] of memory: the largest recommended one that fits, else the smallest. */
    fun suggest(ramBytes: Long): Model {
        val gb = ramBytes / 1_000_000_000.0
        return listOf("granite-4.2-8b", "qwen3.5-4b", "qwen3.5-2b").mapNotNull { byId(it) }.firstOrNull { gb >= it.minRamGb - 0.6 } ?: MODELS.last()
    }

    /**
     * Threads for prompt and generation: the big cores. Using every core includes the slow efficiency ones, which makes llama.cpp
     * wait on them; phones have 2-4 big cores of 8, so half the cores (at least 2, at most 6).
     */
    fun threads(cores: Int): Int = (cores / 2).coerceIn(2, 6)

    /** The command line: the phone settings (8K window, q8_0 cache, flash attention), the chat template's own tool-call format. */
    fun args(binary: String, modelPath: String, cores: Int): List<String> = listOf(
        binary, "-m", modelPath, "--host", "127.0.0.1", "--port", "$PORT",
        "-c", "$CONTEXT", "-t", "${threads(cores)}", "-fa", "on", "-ctk", "q8_0", "-ctv", "q8_0",
        "--jinja", "--no-webui", "-np", "1", "--alias", modelPath.substringAfterLast('/').removeSuffix(".gguf"))

    /** The file name a model is kept under while downloading. */
    fun partName(m: Model): String = m.file + ".part"

    /** Readable size, e.g. 2.7 GB. */
    fun size(bytes: Long): String = if (bytes >= 1_000_000_000L) "%.1f GB".format(bytes / 1e9) else "${bytes / 1_000_000} MB"
}
