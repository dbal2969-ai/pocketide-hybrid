package dev.pocketide.app.core

/**
 * 1.5.214: the PocketIDEAI bridge in Preview. A page built in PocketIDE (the "Hybrid AI web app" starter, or any page) can call
 * `PocketIDEAI.ask(id, prompt)`; PocketIDE answers through its own Firebase AI Logic (Gemini Nano on the phone first) and calls back
 * `window.__pocketideAI(id, json)`. Only the project's own pages may ask (not a local server or another site), prompts are capped,
 * and the cloud answers at most [Tuning.previewCloudPerHour] of them an hour, because the free quota is shared with the agent and,
 * during judging, with the judges; past that, only Gemini Nano answers.
 */
object PreviewAi {
    const val MAX_PROMPT = 6000
    const val NAME = "PocketIDEAI"

    /** Whether a page at [url] may use the bridge: only the project's own files, served at https://<project>.pocketide.local/. */
    fun allowed(url: String?): Boolean {
        val u = url?.trim().orEmpty()
        val host = Regex("""^https://([^/:?#]+)(?:[/?#]|$)""").find(u)?.groupValues?.get(1)?.lowercase() ?: return false
        return host.endsWith(".pocketide.local")
    }

    /** Remembers when the cloud answered, to keep to the hourly allowance. */
    class Allowance(private val now: () -> Long = { System.currentTimeMillis() }) {
        private val times = ArrayDeque<Long>()
        @Synchronized fun cloudLeft(perHour: Int): Int { trim(); return (perHour - times.size).coerceAtLeast(0) }
        @Synchronized fun usedCloud() { times.addLast(now()) }
        private fun trim() { val cut = now() - 3_600_000L; while (times.isNotEmpty() && times.first() < cut) times.removeFirst() }
    }

    /** The JSON passed back to the page. */
    fun reply(text: String, onDevice: Boolean, seconds: Double, model: String): String =
        MiniJson.write(mapOf("text" to text, "onDevice" to onDevice, "seconds" to seconds, "model" to model))

    fun error(message: String): String = MiniJson.write(mapOf("error" to message))

    /** The script that hands [json] to the page's waiting request [id]. */
    fun callback(id: String, json: String): String =
        "window.__pocketideAI && window.__pocketideAI(" + MiniJson.write(id) + ", " + MiniJson.write(json) + ");"

    /** Why a request is refused before it runs, or null. */
    fun refusal(url: String?, prompt: String): String? = when {
        !allowed(url) -> "PocketIDE's AI answers only the project's own pages in Preview."
        prompt.isBlank() -> "The prompt is empty."
        prompt.length > MAX_PROMPT -> "The prompt is longer than $MAX_PROMPT characters."
        else -> null
    }

    const val CLOUD_USED_UP = "The preview's cloud allowance for this hour is used up, and Gemini Nano could not answer on this phone. Try again later."
}
