@file:OptIn(com.google.firebase.ai.type.PublicPreviewAPI::class)

package dev.pocketide.app

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.DownloadStatus
import com.google.firebase.ai.FirebaseAI
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.InferenceMode
import com.google.firebase.ai.InferenceSource
import com.google.firebase.ai.OnDeviceConfig
import com.google.firebase.ai.OnDeviceModelOption
import com.google.firebase.ai.OnDeviceModelStatus
import com.google.firebase.ai.type.GenerationConfig
import com.google.firebase.ai.type.GenerativeBackend
import dev.pocketide.app.core.HybridSettings
import kotlinx.coroutines.runBlocking

/**
 * Hybrid AI (#BuildHybridAI 2026): Firebase AI Logic with on-device Gemini Nano and a cloud Gemini fallback.
 *
 * Quick, single-turn coding help (explain lines, spot a bug, write a docstring, draft a commit message) runs on the phone
 * with Gemini Nano when it is there: offline, private, free. Long inputs, phones without Nano and the "prefer cloud" mode
 * go to a cloud Gemini model through the same Firebase API. Every reply says where it ran.
 *
 * All calls block: run them on a background thread (the screens use an executor, like the rest of PocketIDE).
 */
object HybridAi {
    /** The cloud model (Gemini Developer API through Firebase AI Logic). */
    const val CLOUD_MODEL = "gemini-3.1-flash-lite"

    private const val PREFS = "hybrid_ai"

    enum class Mode(val label: String, val inference: InferenceMode) {
        PREFER_ON_DEVICE("On this phone first (cloud if Nano is missing)", InferenceMode.PREFER_ON_DEVICE),
        PREFER_IN_CLOUD("Cloud first (this phone when offline)", InferenceMode.PREFER_IN_CLOUD),
        ONLY_ON_DEVICE("Only on this phone (never sends code out)", InferenceMode.ONLY_ON_DEVICE),
    }

    class Reply(val text: String, val onDevice: Boolean, val seconds: Double, val note: String) {
        val where: String get() = if (onDevice) "on this phone (Gemini Nano)" else "in the cloud ($CLOUD_MODEL)"
    }

    private val models = HashMap<Mode, GenerativeModel>()
    @Volatile private var appCheckDone = false

    /** True when this build has a Firebase config (app/google-services.json at build time). */
    fun configured(ctx: Context): Boolean = try {
        FirebaseApp.getApps(ctx).isNotEmpty() || FirebaseApp.initializeApp(ctx) != null
    } catch (e: Exception) { false }

    @Synchronized
    private fun model(ctx: Context, mode: Mode): GenerativeModel {
        check(configured(ctx)) { "This build has no Firebase config, so hybrid AI is off. See HACKATHON.md." }
        if (!appCheckDone) { AppCheckSetup.install(FirebaseApp.getInstance()); appCheckDone = true }
        val s = settings(ctx)
        return models.getOrPut(mode) {
            val gen = GenerationConfig.builder().apply {
                temperature = s.temperature; topK = s.topK; maxOutputTokens = s.maxOutputTokens
            }.build()
            FirebaseAI.getInstance(FirebaseApp.getInstance(), GenerativeBackend.googleAI())
                .generativeModel(modelName = CLOUD_MODEL, generationConfig = gen,
                    onDeviceConfig = OnDeviceConfig(mode = mode.inference, maxOutputTokens = s.maxOutputTokens,
                        temperature = s.temperature, topK = s.topK, modelOption = OnDeviceModelOption.PREVIEW_FAST))
        }
    }

    /** The settings the person saved (Models → Hybrid AI), clamped to safe ranges. */
    fun settings(ctx: Context): HybridSettings {
        val p = ctx.getSharedPreferences(PREFS, 0)
        val d = HybridSettings.DEFAULT
        return HybridSettings.clamp(HybridSettings(
            temperature = p.getFloat("temperature", d.temperature),
            topK = p.getInt("topK", d.topK),
            maxOutputTokens = p.getInt("maxOutputTokens", d.maxOutputTokens),
            onDeviceMaxChars = p.getInt("onDeviceMaxChars", d.onDeviceMaxChars)))
    }

    /** Saves the settings; the models are rebuilt on the next request. */
    @Synchronized
    fun saveSettings(ctx: Context, s: HybridSettings) {
        val c = HybridSettings.clamp(s)
        ctx.getSharedPreferences(PREFS, 0).edit()
            .putFloat("temperature", c.temperature).putInt("topK", c.topK)
            .putInt("maxOutputTokens", c.maxOutputTokens).putInt("onDeviceMaxChars", c.onDeviceMaxChars).apply()
        models.clear()
    }

    /** Where a request of this size will go in this mode (the routing rule shown to the person). */
    fun route(mode: Mode, chars: Int, limit: Int): Pair<Mode, String> = when {
        mode == Mode.PREFER_ON_DEVICE && chars > limit ->
            Mode.PREFER_IN_CLOUD to "Sent to the cloud: ${chars} characters is more than the on-phone limit ($limit)."
        mode == Mode.ONLY_ON_DEVICE && chars > limit ->
            mode to "Long for Gemini Nano ($chars characters); the start of it may be all it reads."
        else -> mode to ""
    }

    /** The app context, set when the app starts, so the chat and the agent can reach hybrid AI. */
    @Volatile var appContext: Context? = null

    /** A conversation as one prompt, routed like quick help: Nano first in on-phone mode, the cloud when it is long. Blocking. */
    fun chat(ctx: Context, system: String, turns: List<Pair<String, String>>): Reply {
        val prompt = buildString {
            if (system.isNotBlank()) append(system.trim()).append("\n\n")
            for ((role, text) in turns) if (text.isNotBlank()) append(role).append(": ").append(text.trim()).append("\n\n")
            append("assistant:")
        }
        return ask(ctx, prompt, HybridScreen.mode(ctx))
    }

    /** One single-turn request. Blocking. */
    fun ask(ctx: Context, prompt: String, mode: Mode): Reply {
        val (use, note) = route(mode, prompt.length, settings(ctx).onDeviceMaxChars)
        val t0 = System.nanoTime()
        val r = runBlocking { model(ctx, use).generateContent(prompt) }
        val secs = (System.nanoTime() - t0) / 1e9
        return Reply(r.text?.trim().orEmpty(), r.inferenceSource == InferenceSource.ON_DEVICE, secs, note)
    }

    /** Gemini Nano on this phone: "AVAILABLE", "DOWNLOADABLE", "DOWNLOADING", "UNAVAILABLE" or an error. Blocking. */
    fun nanoStatus(ctx: Context): String = try {
        val st = runBlocking { model(ctx, Mode.PREFER_ON_DEVICE).onDeviceExtension?.checkStatus() }
        when (st) {
            OnDeviceModelStatus.AVAILABLE -> "AVAILABLE"
            OnDeviceModelStatus.DOWNLOADABLE -> "DOWNLOADABLE"
            OnDeviceModelStatus.DOWNLOADING -> "DOWNLOADING"
            null -> "UNAVAILABLE (this build has no on-device model object)"
            else -> "UNAVAILABLE (SDK status: $st)"
        }
    } catch (e: Exception) { "ERROR: " + explain(e) }

    /** Downloads Gemini Nano through Android AICore; progress gets bytes so far. Returns null or an error. Blocking. */
    fun downloadNano(ctx: Context, progress: (Long, Long) -> Unit): String? = try {
        var total = 0L
        var failed: String? = null
        runBlocking {
            val ext = model(ctx, Mode.PREFER_ON_DEVICE).onDeviceExtension
            if (ext == null) { failed = "This build has no on-device support."; return@runBlocking }
            ext.download().collect { st ->
                when (st) {
                    is DownloadStatus.DownloadStarted -> { total = st.bytesToDownload; progress(0L, total) }
                    is DownloadStatus.DownloadInProgress -> progress(st.totalBytesDownloaded, total)
                    is DownloadStatus.DownloadFailed -> failed = explain(st.exception)
                    else -> {}
                }
            }
        }
        failed
    } catch (e: Exception) { explain(e) }

    /** App Check debug secret of this install (debug builds), to register in the Firebase console. */
    fun appCheckDebugSecret(ctx: Context): String? = try {
        val key = FirebaseApp.getInstance().persistenceKey
        ctx.getSharedPreferences("com.google.firebase.appcheck.debug.store.$key", Context.MODE_PRIVATE)
            .getString("com.google.firebase.appcheck.debug.DEBUG_SECRET", null)
    } catch (e: Exception) { null }

    /** A short, actionable message for the usual setup problems. */
    fun explain(e: Throwable): String {
        val m = (e.message ?: e.javaClass.simpleName)
        return when {
            m.contains("App Check", true) || m.contains("attestation", true) ->
                "App Check refused the request. In a debug build, add this phone's debug token in the Firebase console (App Check → Apps → Manage debug tokens). ($m)"
            m.contains("API has not been used", true) || m.contains("SERVICE_DISABLED", true) || m.contains("PERMISSION_DENIED", true) ->
                "Firebase AI Logic is not enabled for this project yet (Firebase console → AI Logic → Get started → Gemini Developer API). ($m)"
            else -> m
        }
    }

    /** Ready-made quick actions for code from the editor. */
    enum class Action(val label: String, val prompt: (String, String) -> String) {
        EXPLAIN("Explain these lines", { lang, code -> "Explain what this $lang code does, briefly and concretely, for the developer who is editing it.\n\n```$lang\n$code\n```" }),
        BUGS("Look for bugs", { lang, code -> "Review this $lang code for bugs. List each real problem with the line and a one-line fix. If you see none, say so.\n\n```$lang\n$code\n```" }),
        DOC("Write a doc comment", { lang, code -> "Write only a short doc comment (in $lang comment syntax) for this code. No other text.\n\n```$lang\n$code\n```" }),
        COMMIT("Draft a commit message", { lang, code -> "Write a one-line git commit message (under 72 characters) for a change that adds or edits this $lang code. Only the message.\n\n```$lang\n$code\n```" }),
    }
}
