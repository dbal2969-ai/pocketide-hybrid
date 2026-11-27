package dev.pocketide.app.core

/**
 * Everything about Gemini Nano and hybrid routing that can be tuned from Remote Config without an app update (1.5.198). HybridCloud
 * copies the console's values in after each fetch and each real-time update ([apply]); every default here is the app's built-in
 * behaviour, so nothing changes until a value is published. Values out of range are ignored. The keys, defaults and descriptions are
 * also in remoteconfig.template.json (RemoteTuningTest keeps the two in step). Pure logic.
 */
object Tuning {
    // ---- defaults for people who have not saved their own (Models › Hybrid AI › settings) ----
    @Volatile var nanoTemperature = HybridSettings.DEFAULT_TEMPERATURE
    @Volatile var nanoTopK = HybridSettings.DEFAULT_TOP_K
    @Volatile var nanoMaxOutput = HybridSettings.DEFAULT_MAX_OUTPUT
    @Volatile var nanoFullModel = false
    /** The cloud tier for people who have not picked one: auto, fast, balanced or best. */
    @Volatile var cloudTier = CloudTier.Choice.AUTO.id

    // ---- thresholds ----
    /** Failed tool calls in a row before Gemini Nano hands the rest of a request to the cloud. */
    @Volatile var handoffAfterFailures = 2
    /** Times a step waits out a per-minute quota limit before the cloud counts as out. */
    @Volatile var quotaWaits = 2
    /** How long a step waits when every cloud tier is busy, once. */
    @Volatile var busyWaitMs = 8_000L
    /** How long the cloud counts as out after a quota limit that is not daily. */
    @Volatile var cloudOutMinutes = 15
    /** Times Nano is shown a file again when it rereads it with no cloud to hand to. */
    @Volatile var nanoReshows = 2

    // ---- switches for the newer repairs (true = on); a way to turn one off at once if it misbehaves on some phones ----
    @Volatile var repairBrokenJson = true
    @Volatile var askNanoAgain = true
    @Volatile var pythonSyntaxCheck = true
    @Volatile var pageWiringCheck = true

    // ---- the Python fix path (1.5.201, PyFix) ----
    /** The app finds the function, Nano rewrites it whole, the test picks the rewrite that passes. */
    @Volatile var pyFix = true
    /** Rewrites tried for the first function picked (half as many for the next). */
    @Volatile var pyFixSamples = 4
    /** The temperature of the rewrites after the first. */
    @Volatile var pyFixTemperature = 0.7f

    /** The Remote Config keys, in the order of the template. */
    object Keys {
        const val NANO_TEMPERATURE = "hybrid_nano_temperature"
        const val NANO_TOP_K = "hybrid_nano_top_k"
        const val NANO_MAX_OUTPUT = "hybrid_nano_max_output_tokens"
        const val NANO_FULL_MODEL = "hybrid_nano_full_model"
        const val CLOUD_TIER = "hybrid_cloud_tier_default"
        const val HANDOFF_FAILURES = "hybrid_handoff_after_failures"
        const val QUOTA_WAITS = "hybrid_quota_waits"
        const val BUSY_WAIT_MS = "hybrid_busy_wait_ms"
        const val CLOUD_OUT_MINUTES = "hybrid_cloud_out_minutes"
        const val NANO_RESHOWS = "hybrid_nano_reread_reshows"
        const val REPAIR_JSON = "hybrid_repair_broken_json"
        const val ASK_AGAIN = "hybrid_nano_ask_again"
        const val PY_SYNTAX = "hybrid_python_syntax_check"
        const val PAGE_WIRING = "hybrid_page_wiring_check"
        const val PY_FIX = "hybrid_python_fix"
        const val PY_FIX_SAMPLES = "hybrid_python_fix_samples"
        const val PY_FIX_TEMPERATURE = "hybrid_python_fix_temperature"
    }

    /** The built-in value of each key, as Remote Config's in-app defaults. */
    val DEFAULTS: Map<String, Any> = mapOf(
        Keys.NANO_TEMPERATURE to HybridSettings.DEFAULT_TEMPERATURE.toDouble(), Keys.NANO_TOP_K to HybridSettings.DEFAULT_TOP_K.toLong(),
        Keys.NANO_MAX_OUTPUT to HybridSettings.DEFAULT_MAX_OUTPUT.toLong(), Keys.NANO_FULL_MODEL to false, Keys.CLOUD_TIER to CloudTier.Choice.AUTO.id,
        Keys.HANDOFF_FAILURES to 2L, Keys.QUOTA_WAITS to 2L, Keys.BUSY_WAIT_MS to 8_000L, Keys.CLOUD_OUT_MINUTES to 15L, Keys.NANO_RESHOWS to 2L,
        Keys.REPAIR_JSON to true, Keys.ASK_AGAIN to true, Keys.PY_SYNTAX to true, Keys.PAGE_WIRING to true,
        Keys.PY_FIX to true, Keys.PY_FIX_SAMPLES to 4L, Keys.PY_FIX_TEMPERATURE to 0.7)

    /**
     * Copies values in. [str] gives a key's value as text, or null when Remote Config has none (then the built-in value stays);
     * a value that is not a number, or is out of range, is ignored.
     */
    fun apply(str: (String) -> String?) {
        fun num(k: String) = str(k)?.trim()?.toDoubleOrNull()
        fun bool(k: String) = str(k)?.trim()?.lowercase()?.let { if (it == "true") true else if (it == "false") false else null }
        nanoTemperature = num(Keys.NANO_TEMPERATURE)?.toFloat()?.takeIf { it in HybridSettings.TEMP_MIN..HybridSettings.TEMP_MAX } ?: HybridSettings.DEFAULT_TEMPERATURE
        nanoTopK = num(Keys.NANO_TOP_K)?.toInt()?.takeIf { it in HybridSettings.TOP_K_MIN..HybridSettings.TOP_K_MAX } ?: HybridSettings.DEFAULT_TOP_K
        nanoMaxOutput = num(Keys.NANO_MAX_OUTPUT)?.toInt()?.takeIf { it in HybridSettings.OUT_MIN..HybridSettings.OUT_MAX } ?: HybridSettings.DEFAULT_MAX_OUTPUT
        nanoFullModel = bool(Keys.NANO_FULL_MODEL) ?: false
        cloudTier = str(Keys.CLOUD_TIER)?.trim()?.lowercase()?.takeIf { id -> CloudTier.Choice.values().any { it.id == id } } ?: CloudTier.Choice.AUTO.id
        handoffAfterFailures = num(Keys.HANDOFF_FAILURES)?.toInt()?.takeIf { it in 1..10 } ?: 2
        quotaWaits = num(Keys.QUOTA_WAITS)?.toInt()?.takeIf { it in 0..10 } ?: 2
        busyWaitMs = num(Keys.BUSY_WAIT_MS)?.toLong()?.takeIf { it in 0..60_000 } ?: 8_000L
        cloudOutMinutes = num(Keys.CLOUD_OUT_MINUTES)?.toInt()?.takeIf { it in 1..1440 } ?: 15
        nanoReshows = num(Keys.NANO_RESHOWS)?.toInt()?.takeIf { it in 0..10 } ?: 2
        repairBrokenJson = bool(Keys.REPAIR_JSON) ?: true
        askNanoAgain = bool(Keys.ASK_AGAIN) ?: true
        pythonSyntaxCheck = bool(Keys.PY_SYNTAX) ?: true
        pageWiringCheck = bool(Keys.PAGE_WIRING) ?: true
        pyFix = bool(Keys.PY_FIX) ?: true
        pyFixSamples = num(Keys.PY_FIX_SAMPLES)?.toInt()?.takeIf { it in 1..10 } ?: 4
        pyFixTemperature = num(Keys.PY_FIX_TEMPERATURE)?.toFloat()?.takeIf { it in HybridSettings.TEMP_MIN..HybridSettings.TEMP_MAX } ?: 0.7f
    }

    /** Back to the built-in values (tests). */
    fun reset() = apply { null }
}
