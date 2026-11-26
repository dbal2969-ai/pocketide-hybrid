package dev.pocketide.app

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import com.google.firebase.remoteconfig.FirebaseRemoteConfigValue
import dev.pocketide.app.core.HybridSettings
import dev.pocketide.app.core.HybridStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The other Firebase products in hybrid AI (#BuildHybridAI):
 * - Remote Config: the cloud model, the default on-phone limit and routing mode, and a switch that turns the cloud off,
 *   all changed from the Firebase console without an app update. Settings the person saved on the phone still win.
 * - Authentication: an anonymous account per install, so usage is kept per person with no sign-up.
 * - Firestore: where each hybrid request ran, how long it took and why Gemini Nano passed it on. No prompts and no code
 *   are stored. Read back as the usage summary on the Hybrid AI screen.
 */
object HybridCloud {
    const val KEY_MODEL = "hybrid_cloud_model"
    const val KEY_LIMIT = "hybrid_on_device_max_chars"
    const val KEY_MODE = "hybrid_default_mode"
    const val KEY_CLOUD_ON = "hybrid_cloud_enabled"
    /** 1.5.185 (firebase-ai-logic-basics skill): App Check limited-use tokens (replay protection); false in the console turns them off. */
    const val KEY_LIMITED_TOKENS = "hybrid_limited_use_app_check_tokens"
    /** 1.5.197: tuning Gemini Nano from the console (see remoteconfig.template.json); the defaults are the app's built-in values. */
    const val KEY_NANO_NOTE = "hybrid_nano_note"
    const val KEY_PY_NOTE = "hybrid_python_note"
    const val KEY_PY_EXAMPLES = "hybrid_python_examples"
    const val KEY_PY_DEFAULT = "hybrid_python_helper_default"
    const val KEY_PY_LINES = "hybrid_python_outline_lines"
    /** The model when no tier applies (Remote Config not fetched, no settings yet): Balanced. */
    const val DEFAULT_MODEL = "gemini-3.8-flash"

    private val io = Executors.newSingleThreadExecutor()

    /** Tests only: no Remote Config, sign-in or usage records. */
    @Volatile var off = false

    @Volatile var configNote: String = "Remote Config: not fetched yet; using the built-in defaults."
        private set
    @Volatile var authNote: String = "Firebase Authentication: not signed in yet."
        private set

    /** At app start: fetch Remote Config and sign in anonymously, in the background. */
    fun start(ctx: Context) {
        if (off) return
        val app = ctx.applicationContext
        io.execute {
            if (!HybridAi.configured(app)) return@execute
            try {
                val rc = FirebaseRemoteConfig.getInstance()
                Tasks.await(rc.setConfigSettingsAsync(FirebaseRemoteConfigSettings.Builder().setMinimumFetchIntervalInSeconds(3600).build()), 10, TimeUnit.SECONDS)
                Tasks.await(rc.setDefaultsAsync(mapOf<String, Any>(
                    KEY_MODEL to "",   // 1.5.184: empty = the person's cloud model choice (CloudTier); a value set in the console pins one model
                    KEY_LIMIT to HybridSettings.DEFAULT_ON_DEVICE_CHARS.toLong(),
                    KEY_MODE to HybridAi.Mode.PREFER_ON_DEVICE.name,
                    KEY_CLOUD_ON to true,
                    KEY_LIMITED_TOKENS to true,
                    KEY_NANO_NOTE to "",
                    KEY_PY_NOTE to "",   // empty = the built-in note (PyHelper.NOTE)
                    KEY_PY_EXAMPLES to "",
                    KEY_PY_DEFAULT to false,
                    KEY_PY_LINES to dev.pocketide.app.core.PyHelper.LONG.toLong()) + dev.pocketide.app.core.Tuning.DEFAULTS), 10, TimeUnit.SECONDS)
                Tasks.await(rc.fetchAndActivate(), 20, TimeUnit.SECONDS)
                applyTuning()
                configNote = "Remote Config: cloud model ${cloudModel()}, on-phone limit ${remoteLimit()} characters, " +
                    "cloud ${if (cloudEnabled()) "on" else "switched off"} (fetched at ${SimpleDateFormat("HH:mm", Locale.US).format(Date())})."
                HybridAi.reset()   // models are rebuilt with the fetched cloud model
                // 1.5.185 (firebase-remote-config-basics skill): real-time updates, so a change in the console (a pinned model, the
                // cloud switched off during the judging week's shared quota) reaches running apps at once instead of after an hour
                rc.addOnConfigUpdateListener(object : com.google.firebase.remoteconfig.ConfigUpdateListener {
                    override fun onUpdate(update: com.google.firebase.remoteconfig.ConfigUpdate) {
                        rc.activate().addOnSuccessListener { HybridAi.reset(); applyTuning(); configNote = "Remote Config: updated live (${update.updatedKeys.joinToString(", ")})." }
                    }
                    override fun onError(error: com.google.firebase.remoteconfig.FirebaseRemoteConfigException) { /* the next start fetches again */ }
                })
            } catch (e: Exception) {
                configNote = "Remote Config: using the built-in defaults (${short(e)})."
            }
            signIn()
        }
    }

    // ---------------- Performance Monitoring ----------------

    /**
     * Runs [f] inside a Performance Monitoring trace named [name] (hybrid_phone, hybrid_cloud, …), so the console shows how long
     * requests take on the phone and in the cloud, on every phone that runs the app. Does nothing without Firebase.
     */
    fun <T> traced(name: String, f: () -> T): T {
        val ctx = HybridAi.appContext
        val t = try {
            if (off || ctx == null || !HybridAi.configured(ctx)) null
            else com.google.firebase.perf.FirebasePerformance.getInstance().newTrace(name).also {
                it.putAttribute("on_phone_model", if (HybridAi.settings(ctx).fullModel) "full" else "fast")
                it.start()
            }
        } catch (e: Exception) { null }
        try { return f() } finally { try { t?.stop() } catch (e: Exception) { /* traces are best effort */ } }
    }

    // ---------------- Remote Config ----------------

    /** A value set in the console or the in-app defaults; null before the defaults are set (then the built-in values apply). */
    private fun value(key: String): FirebaseRemoteConfigValue? = try {
        FirebaseRemoteConfig.getInstance().getValue(key).takeIf { it.source != FirebaseRemoteConfig.VALUE_SOURCE_STATIC }
    } catch (e: Exception) { null }

    /** Copies the Nano tuning values into the code that uses them (after a fetch and after each live update). */
    private fun applyTuning() {
        try {
            dev.pocketide.app.core.SmallLocal.remoteNote = value(KEY_NANO_NOTE)?.asString().orEmpty()
            dev.pocketide.app.core.PyHelper.remoteNote = value(KEY_PY_NOTE)?.asString()
            dev.pocketide.app.core.PyHelper.remoteExamples = value(KEY_PY_EXAMPLES)?.asString().orEmpty()
            dev.pocketide.app.core.PyHelper.remoteLong = value(KEY_PY_LINES)?.asLong()?.toInt()
            dev.pocketide.app.core.Tuning.apply { k -> value(k)?.asString() }   // 1.5.198: Nano's defaults, thresholds and switches
        } catch (e: Exception) { /* the built-in values stay */ }
    }

    /** Whether the Python helper is on for a person who has not set it (Remote Config; off by default). */
    fun pythonHelperDefault(): Boolean = try { value(KEY_PY_DEFAULT)?.asBoolean() } catch (e: Exception) { null } ?: false

    /** A model Remote Config pins for every cloud step (set in the console), or null. */
    fun pinnedModel(): String? = try { value(KEY_MODEL)?.asString()?.trim()?.takeIf { it.isNotEmpty() } } catch (e: Exception) { null }

    /** The cloud model a request starts on: the pinned one, else the person's choice (Auto starts on Balanced). */
    fun cloudModel(): String = pinnedModel() ?: HybridAi.appContext?.let { HybridAi.startTier(it).model } ?: DEFAULT_MODEL

    fun remoteLimit(): Int = try { value(KEY_LIMIT)?.asLong()?.toInt()?.coerceIn(HybridSettings.CHARS_MIN, HybridSettings.CHARS_MAX) } catch (e: Exception) { null }
        ?: HybridSettings.DEFAULT_ON_DEVICE_CHARS

    fun remoteMode(): HybridAi.Mode = try {
        value(KEY_MODE)?.asString()?.trim()?.let { s -> HybridAi.Mode.entries.firstOrNull { it.name == s } }
    } catch (e: Exception) { null } ?: HybridAi.Mode.PREFER_ON_DEVICE

    /** False when the cloud is switched off in Remote Config (for example to stop costs at once); requests then stay on the phone. */
    fun limitedUseTokens(): Boolean = try { value(KEY_LIMITED_TOKENS)?.asBoolean() } catch (e: Exception) { null } ?: true

    fun cloudEnabled(): Boolean = try { value(KEY_CLOUD_ON)?.asBoolean() } catch (e: Exception) { null } ?: true

    // ---------------- Authentication ----------------

    /** The anonymous user's id, signing in when needed. Blocking. */
    private fun signIn(): String? = try {
        val auth = FirebaseAuth.getInstance()
        val u = auth.currentUser ?: Tasks.await(auth.signInAnonymously(), 20, TimeUnit.SECONDS).user
        authNote = if (u != null) "Firebase Authentication: signed in anonymously (user ${u.uid.take(8)}…)." else "Firebase Authentication: not signed in."
        u?.uid
    } catch (e: Exception) {
        val m = short(e)
        authNote = "Firebase Authentication: not signed in. " +
            if (m.contains("OPERATION_NOT_ALLOWED", true) || m.contains("not enabled", true) || m.contains("disabled", true))
                "Turn on Anonymous sign-in in the Firebase console (Authentication → Sign-in method)." else m
        null
    }

    // ---------------- Firestore ----------------

    /** Records one hybrid request: where it ran (phone, cloud or strong), its time, why Nano passed it on, and from where (chat, agent, try). */
    fun log(where: String, seconds: Double, reason: String, kind: String) {
        if (off) return
        val ctx = HybridAi.appContext ?: return
        io.execute {
            if (!HybridAi.configured(ctx)) return@execute
            try {
                val uid = FirebaseAuth.getInstance().currentUser?.uid ?: signIn() ?: return@execute
                val model = when (where) {
                    HybridStats.CLOUD -> cloudModel()
                    HybridStats.PHONE -> if (HybridAi.settings(ctx).fullModel) "gemini-nano-full" else "gemini-nano-fast"
                    else -> ""
                }
                FirebaseFirestore.getInstance().collection("users").document(uid).collection("hybrid_runs").add(mapOf(
                    "where" to where, "seconds" to seconds, "reason" to reason.take(200), "kind" to kind, "model" to model,
                    "at" to FieldValue.serverTimestamp()))
            } catch (e: Exception) { /* usage records are best effort */ }
        }
    }

    /** The usage summary for the Hybrid AI screen. Blocking: call it off the main thread. */
    fun summary(): String {
        val ctx = HybridAi.appContext ?: return "Hybrid AI is not ready yet."
        if (!HybridAi.configured(ctx)) return "This build has no Firebase config."
        return try {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: signIn() ?: return authNote
            val snap = Tasks.await(FirebaseFirestore.getInstance().collection("users").document(uid).collection("hybrid_runs")
                .orderBy("at", Query.Direction.DESCENDING).limit(200).get(), 20, TimeUnit.SECONDS)
            HybridStats.summary(snap.documents.map { d ->
                HybridStats.Run(d.getString("where").orEmpty(), d.getDouble("seconds") ?: 0.0, d.getString("reason").orEmpty(), d.getString("kind").orEmpty())
            })
        } catch (e: Exception) {
            val m = short(e)
            when {
                m.contains("PERMISSION_DENIED", true) -> "Firestore refused the read: add the security rules from HACKATHON.md (Firestore Database → Rules)."
                m.contains("NOT_FOUND", true) || m.contains("does not exist", true) ->
                    "Create the Firestore database first (Firebase console → Firestore Database → Create database)."
                else -> "Could not read the usage: $m"
            }
        }
    }

    private fun short(e: Exception): String = (e.cause?.message ?: e.message ?: e.javaClass.simpleName).take(200)
}
