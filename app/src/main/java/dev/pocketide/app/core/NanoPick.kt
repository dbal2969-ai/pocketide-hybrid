package dev.pocketide.app.core

/**
 * 1.5.219: which Gemini Nano release to use on this phone. AICore offers up to three through Firebase AI Logic: the stable model,
 * the preview model and the fast preview model (OnDeviceModelOption). Until now the app only ever asked for a preview model,
 * because the owner's Galaxy S24+ has only "Gemini Nano 4 Fast [Preview]". A phone that has only the stable model (one not
 * enrolled in the AICore preview) was told "not available" and sent everything to the cloud. The app now asks AICore for the
 * status of each and uses the first that is ready.
 */
object NanoPick {
    const val PREVIEW_FAST = "preview_fast"
    const val PREVIEW = "preview"
    const val STABLE = "stable"

    /** The order to try in: the person's choice of model first (Fast unless "Full" is ticked), then the other preview, then stable. */
    fun order(full: Boolean): List<String> = if (full) listOf(PREVIEW, PREVIEW_FAST, STABLE) else listOf(PREVIEW_FAST, PREVIEW, STABLE)

    /** The release to use given each one's status: the first ready one, else the first being or able to be downloaded, else the first. */
    fun choose(order: List<String>, status: Map<String, String>): String =
        order.firstOrNull { status[it] == "AVAILABLE" } ?: order.firstOrNull { status[it] == "DOWNLOADING" } ?:
            order.firstOrNull { status[it] == "DOWNLOADABLE" } ?: order.first()

    fun label(id: String): String = when (id) {
        PREVIEW_FAST -> "preview, fast"
        PREVIEW -> "preview, full"
        STABLE -> "stable"
        else -> id
    }

    /** One line for the Hybrid AI screen and the benchmark report: what AICore said about each release. */
    fun describe(order: List<String>, status: Map<String, String>): String =
        order.joinToString("; ") { label(it) + ": " + (status[it] ?: "not asked").lowercase().replace('_', ' ') }

    /** True when an on-device error says the model is busy for the moment (AICore's per-app request quota), worth a short wait. */
    fun busy(message: String): Boolean = Regex("""\bBUSY\b""").containsMatchIn(message) && !message.contains("BATTERY_USE_QUOTA")

    /** The seed of rewrite [k] (0-based) of block [n] in the Python fix path: none for the first, then a different one each time. */
    fun seed(n: Int, k: Int): Int? = if (k == 0) null else 7919 * (n + 1) + 104729 * k
}
