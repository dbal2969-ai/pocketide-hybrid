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
    const val DEFAULT_MODEL = "gemini-3.1-flash-lite"

    private val io = Executors.newSingleThreadExecutor()

    @Volatile var configNote: String = "Remote Config: not fetched yet; using the built-in defaults."
        private set
    @Volatile var authNote: String = "Firebase Authentication: not signed in yet."
        private set

    /** At app start: fetch Remote Config and sign in anonymously, in the background. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        io.execute {
            if (!HybridAi.configured(app)) return@execute
            try {
                val rc = FirebaseRemoteConfig.getInstance()
                Tasks.await(rc.setConfigSettingsAsync(FirebaseRemoteConfigSettings.Builder().setMinimumFetchIntervalInSeconds(3600).build()), 10, TimeUnit.SECONDS)
                Tasks.await(rc.setDefaultsAsync(mapOf<String, Any>(
                    KEY_MODEL to DEFAULT_MODEL,
                    KEY_LIMIT to HybridSettings.DEFAULT_ON_DEVICE_CHARS.toLong(),
                    KEY_MODE to HybridAi.Mode.PREFER_ON_DEVICE.name,
                    KEY_CLOUD_ON to true)), 10, TimeUnit.SECONDS)
                Tasks.await(rc.fetchAndActivate(), 20, TimeUnit.SECONDS)
                configNote = "Remote Config: cloud model ${cloudModel()}, on-phone limit ${remoteLimit()} characters, " +
                    "cloud ${if (cloudEnabled()) "on" else "switched off"} (fetched at ${SimpleDateFormat("HH:mm", Locale.US).format(Date())})."
                HybridAi.reset()   // models are rebuilt with the fetched cloud model
            } catch (e: Exception) {
                configNote = "Remote Config: using the built-in defaults (${short(e)})."
            }
            signIn()
        }
    }

    // ---------------- Remote Config ----------------

    /** A value set in the console or the in-app defaults; null before the defaults are set (then the built-in values apply). */
    private fun value(key: String): FirebaseRemoteConfigValue? = try {
        FirebaseRemoteConfig.getInstance().getValue(key).takeIf { it.source != FirebaseRemoteConfig.VALUE_SOURCE_STATIC }
    } catch (e: Exception) { null }

    fun cloudModel(): String = try { value(KEY_MODEL)?.asString()?.trim()?.takeIf { it.isNotEmpty() } } catch (e: Exception) { null } ?: DEFAULT_MODEL

    fun remoteLimit(): Int = try { value(KEY_LIMIT)?.asLong()?.toInt()?.coerceIn(HybridSettings.CHARS_MIN, HybridSettings.CHARS_MAX) } catch (e: Exception) { null }
        ?: HybridSettings.DEFAULT_ON_DEVICE_CHARS

    fun remoteMode(): HybridAi.Mode = try {
        value(KEY_MODE)?.asString()?.trim()?.let { s -> HybridAi.Mode.entries.firstOrNull { it.name == s } }
    } catch (e: Exception) { null } ?: HybridAi.Mode.PREFER_ON_DEVICE

    /** False when the cloud is switched off in Remote Config (for example to stop costs at once); requests then stay on the phone. */
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
