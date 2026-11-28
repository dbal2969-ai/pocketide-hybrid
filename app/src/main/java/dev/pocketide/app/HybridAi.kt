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
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.FunctionCallingConfig
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.GenerationConfig
import com.google.firebase.ai.type.TextPart
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.ToolConfig
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

    class Reply(val text: String, val onDevice: Boolean, val seconds: Double, val note: String, val model: String = "") {
        val where: String get() = if (onDevice) "on this phone (Gemini Nano)" else "in the cloud (${model.ifBlank { HybridCloud.cloudModel() }})"
    }

    // ---------------- cloud tiers (CloudTier, 1.5.184) ----------------

    /** Tiers this project could not use in this session (not available, or billing needed); the next one down answers. */
    val skipTiers: MutableSet<dev.pocketide.app.core.CloudTier.Tier> = java.util.Collections.synchronizedSet(HashSet())
    /** Tiers whose quota ran out, with the time they may be tried again (CloudTier.quota). */
    val coolUntil: MutableMap<dev.pocketide.app.core.CloudTier.Tier, Long> = java.util.concurrent.ConcurrentHashMap()

    /**
     * 1.5.185 (from-scratch run 1125-1619: 9 of 10 tasks ended on quota errors that lasted the whole evening): when every cloud tier is
     * out of its free quota even after waiting, the cloud counts as out until this time, and Gemini Nano carries on alone meanwhile.
     */
    @Volatile var cloudOutUntil: Long = 0L
    fun cloudOut(): Boolean = System.currentTimeMillis() < cloudOutUntil

    /** Tiers not to use now: unusable for the session, or cooling down after their quota ran out. */
    fun skipNow(): Set<dev.pocketide.app.core.CloudTier.Tier> {
        val now = System.currentTimeMillis()
        return skipTiers.toSet() + coolUntil.filterValues { it > now }.keys
    }

    /** Set after a cloud reply was cut off at its token limit (MAX_TOKENS): cloud replies get the largest limit from then on. */
    @Volatile var bigOutput = false

    /** Tiers whose thinking setting was refused; they are asked without it. */
    val noThinking: MutableSet<dev.pocketide.app.core.CloudTier.Tier> = java.util.Collections.synchronizedSet(HashSet())

    fun cloudChoice(ctx: Context): dev.pocketide.app.core.CloudTier.Choice =
        dev.pocketide.app.core.CloudTier.Choice.of(ctx.getSharedPreferences(PREFS, 0).getString("cloudTier", null) ?: dev.pocketide.app.core.Tuning.cloudTier)

    fun saveCloudChoice(ctx: Context, c: dev.pocketide.app.core.CloudTier.Choice) {
        ctx.getSharedPreferences(PREFS, 0).edit().putString("cloudTier", c.id).apply()
        reset()
    }

    /** The tier a request starts on (Auto starts on Balanced), skipping tiers this project cannot use. */
    fun startTier(ctx: Context): dev.pocketide.app.core.CloudTier.Tier =
        dev.pocketide.app.core.CloudTier.pick(cloudChoice(ctx), emptyList(), emptyList(), emptyList(), skipNow()).tier

    /** The model name for [t]: the one Remote Config pins, else the tier's own. */
    fun modelFor(t: dev.pocketide.app.core.CloudTier.Tier): String = HybridCloud.pinnedModel() ?: t.model

    private val models = HashMap<Mode, GenerativeModel>()
    @Volatile private var appCheckDone = false

    /** True when this build has a Firebase config (app/google-services.json at build time). */
    fun configured(ctx: Context): Boolean = try {
        FirebaseApp.getApps(ctx).isNotEmpty() || FirebaseApp.initializeApp(ctx) != null
    } catch (e: Exception) { false }

    /**
     * The Firebase AI Logic service. 1.5.185, from the firebase-ai-logic-basics agent skill: with App Check limited-use tokens, so every
     * cloud request carries a fresh short-lived token that cannot be replayed (Remote Config hybrid_limited_use_app_check_tokens
     * turns it off without a new build).
     */
    private fun ai(): FirebaseAI = FirebaseAI.getInstance(FirebaseApp.getInstance(), GenerativeBackend.googleAI(), HybridCloud.limitedUseTokens())

    @Synchronized
    private fun model(ctx: Context, mode: Mode): GenerativeModel {
        check(configured(ctx)) { "This build has no Firebase config, so hybrid AI is off. See HACKATHON.md." }
        if (!appCheckDone) { AppCheckSetup.install(FirebaseApp.getInstance()); appCheckDone = true }
        val s = settings(ctx)
        return models.getOrPut(mode) {
            ai()
                .generativeModel(modelName = HybridCloud.cloudModel(), generationConfig = gen(s, tierOf(HybridCloud.cloudModel())),
                    onDeviceConfig = OnDeviceConfig(mode = mode.inference, maxOutputTokens = s.maxOutputTokens,
                        temperature = s.temperature, topK = s.topK, modelOption = optionOf(ctx, s)))
        }
    }

    /** The tier whose model this is, or null (a model Remote Config pins). */
    private fun tierOf(model: String) = dev.pocketide.app.core.CloudTier.Tier.values().firstOrNull { it.model == model }

    /**
     * The generation settings. A tier's cloud model also gets its thinking level (Best thinks hard); its output limit is raised so
     * thinking and a whole file still fit (thinking tokens count against it). The phone's settings are the person's own.
     */
    private fun gen(s: HybridSettings, tier: dev.pocketide.app.core.CloudTier.Tier? = null, cloud: Boolean = tier != null): GenerationConfig = GenerationConfig.builder().apply {
        temperature = s.temperature; topK = s.topK; maxOutputTokens = s.maxOutputTokens
        // 1.5.184 (hard run 2, h07): the cloud model stopped at MAX_TOKENS because the phone's 1,024-token setting applied to it too
        if (cloud) maxOutputTokens = maxOf(s.maxOutputTokens, if (bigOutput) 32768 else 8192)
        if (tier != null && tier !in noThinking) {
            maxOutputTokens = maxOf(maxOutputTokens ?: 0, if (tier == dev.pocketide.app.core.CloudTier.Tier.BEST) 16384 else 8192)
            // 1.5.204: thought summaries come back (Remote Config hybrid_show_thoughts), shown like an API model's thinking
            thinkingConfig = com.google.firebase.ai.type.ThinkingConfig.Builder().setThinkingLevel(when (tier.thinking) {
                "high" -> com.google.firebase.ai.type.ThinkingLevel.HIGH
                "medium" -> com.google.firebase.ai.type.ThinkingLevel.MEDIUM
                else -> com.google.firebase.ai.type.ThinkingLevel.LOW
            }).setIncludeThoughts(dev.pocketide.app.core.Tuning.showThoughts).build()
        }
    }.build()

    private val samplers = HashMap<Pair<Float, Int?>, GenerativeModel>()

    /**
     * 1.5.201: one request on Gemini Nano at [temperature] (the Python fix path's rewrites after the first, so they differ). Blocking;
     * throws when Nano cannot run it. 1.5.219 (run 1127-2155: six identical rewrites at 0.7): with a [seed], so that two requests
     * with the same prompt and temperature do not come back the same.
     */
    fun onDeviceSample(ctx: Context, prompt: String, temperature: Float, seed: Int? = null): String {
        probe(ctx)
        val m = synchronized(this) {
            check(configured(ctx)) { "This build has no Firebase config, so hybrid AI is off. See HACKATHON.md." }
            if (!appCheckDone) { AppCheckSetup.install(FirebaseApp.getInstance()); appCheckDone = true }
            val s = settings(ctx)
            if (temperature == s.temperature && seed == null) model(ctx, Mode.ONLY_ON_DEVICE) else samplers.getOrPut(temperature to seed) {
                ai().generativeModel(modelName = HybridCloud.cloudModel(), generationConfig = gen(s, tierOf(HybridCloud.cloudModel())),
                    onDeviceConfig = OnDeviceConfig(mode = Mode.ONLY_ON_DEVICE.inference, maxOutputTokens = s.maxOutputTokens,
                        temperature = temperature, topK = s.topK, seed = seed, modelOption = optionOf(ctx, s)))
            }
        }
        return patient { HybridCloud.traced("hybrid_phone") { runBlocking { m.generateContent(prompt) } } }.text?.trim().orEmpty()
    }

    /** One request on Gemini Nano only, never the cloud (the agent's phone path). Blocking; throws when Nano cannot run it. */
    fun onDeviceText(ctx: Context, prompt: String): String {
        probe(ctx)
        return patient { HybridCloud.traced("hybrid_phone") { runBlocking { model(ctx, Mode.ONLY_ON_DEVICE).generateContent(prompt) } } }.text?.trim().orEmpty()
    }

    /**
     * 1.5.219: AICore limits how many requests an app may make in a short time and answers BUSY past it (ML Kit GenAI: "consider
     * using exponential backoff"). A busy answer is waited out, 1, 2 and 4 seconds, before the step counts as failed. Every
     * on-device failure is also written to a running benchmark's record of events.
     */
    private fun <T> patient(f: () -> T): T {
        var wait = 1000L
        while (true) {
            try { return f() } catch (e: Exception) {
                val msg = generateSequence(e as Throwable?) { it.cause }.take(4).joinToString(" | ") { (it.message ?: it.javaClass.simpleName) }
                val busy = dev.pocketide.app.core.NanoPick.busy(msg) && wait <= 4000L
                HybridBench.event((if (busy) "Gemini Nano was busy, trying again in ${wait / 1000} s: " else "Gemini Nano did not run: ") + msg.take(200))
                if (!busy) throw e
                Thread.sleep(wait); wait *= 2
            }
        }
    }

    // ---- which Gemini Nano release this phone has (core/NanoPick) ----
    @Volatile private var optionId: String? = null
    @Volatile private var probed = false
    @Volatile private var warmed = false
    private val probeLock = Any()
    /** AICore's name for the model in use, and what it said about each release; empty until asked. */
    @Volatile var nanoModelName: String = ""
    @Volatile var nanoProbe: String = ""

    private fun sdkOption(id: String): OnDeviceModelOption = when (id) {
        dev.pocketide.app.core.NanoPick.PREVIEW -> OnDeviceModelOption.PREVIEW
        dev.pocketide.app.core.NanoPick.STABLE -> OnDeviceModelOption.STABLE
        else -> OnDeviceModelOption.PREVIEW_FAST
    }
    private fun optionKey(s: HybridSettings) = if (s.fullModel) "nanoOption.full" else "nanoOption.fast"
    private fun optionIdOf(ctx: Context, s: HybridSettings): String =
        optionId ?: ctx.getSharedPreferences(PREFS, 0).getString(optionKey(s), null) ?: dev.pocketide.app.core.NanoPick.order(s.fullModel).first()
    private fun optionOf(ctx: Context, s: HybridSettings): OnDeviceModelOption = sdkOption(optionIdOf(ctx, s))

    /** The release in use, in words: "preview, fast (model name)". */
    fun nanoLabel(ctx: Context): String = dev.pocketide.app.core.NanoPick.label(optionIdOf(ctx, settings(ctx))) + (if (nanoModelName.isNotBlank()) " ($nanoModelName)" else "")

    /**
     * Asks AICore for the status of each Gemini Nano release, once per start of the app (or again with [force]), and uses the first
     * that is ready. Blocking, so it does nothing on the main thread; a request made there uses the release found last time.
     */
    fun probe(ctx: Context, force: Boolean = false) {
        if ((probed && !force) || android.os.Looper.myLooper() == android.os.Looper.getMainLooper() || !configured(ctx)) return
        synchronized(probeLock) {
            if (probed && !force) return
            try {
                synchronized(this) { if (!appCheckDone) { AppCheckSetup.install(FirebaseApp.getInstance()); appCheckDone = true } }
                val s = settings(ctx)
                val order = dev.pocketide.app.core.NanoPick.order(s.fullModel)
                val status = LinkedHashMap<String, String>()
                val exts = HashMap<String, com.google.firebase.ai.OnDeviceExtension?>()
                for (id in order) status[id] = try {
                    val ext = ai().generativeModel(modelName = HybridCloud.cloudModel(), generationConfig = gen(s, tierOf(HybridCloud.cloudModel())),
                        onDeviceConfig = OnDeviceConfig(mode = Mode.ONLY_ON_DEVICE.inference, modelOption = sdkOption(id))).onDeviceExtension
                    exts[id] = ext
                    when (runBlocking { kotlinx.coroutines.withTimeoutOrNull(8000L) { ext?.checkStatus() } }) {
                        OnDeviceModelStatus.AVAILABLE -> "AVAILABLE"
                        OnDeviceModelStatus.DOWNLOADABLE -> "DOWNLOADABLE"
                        OnDeviceModelStatus.DOWNLOADING -> "DOWNLOADING"
                        else -> "UNAVAILABLE"
                    }
                } catch (e: Exception) { "UNAVAILABLE" }
                val pick = dev.pocketide.app.core.NanoPick.choose(order, status)
                nanoProbe = dev.pocketide.app.core.NanoPick.describe(order, status)
                val changed = pick != optionIdOf(ctx, s)
                optionId = pick
                ctx.getSharedPreferences(PREFS, 0).edit().putString(optionKey(s), pick).apply()
                if (changed) reset()
                if (status[pick] == "AVAILABLE") nanoModelName = try {
                    runBlocking { kotlinx.coroutines.withTimeoutOrNull(5000L) { exts[pick]?.getModelName() } }.orEmpty()
                } catch (e: Exception) { "" }
                HybridBench.event("Gemini Nano releases on this phone: $nanoProbe; using ${nanoLabel(ctx)}")
            } catch (e: Exception) { }
            probed = true
        }
    }

    /**
     * Gets Gemini Nano ready before the first request: finds the release, then loads the model into memory (the first request after
     * a start otherwise waits for that, up to a minute by Google's account). On its own thread; only while PocketIDE is on screen.
     */
    fun prepare(ctx: Context) {
        if (warmed || !configured(ctx)) return
        warmed = true
        val app = ctx.applicationContext
        Thread {
            try {
                probe(app)
                if (HybridAgent.foreground(app)) runBlocking { kotlinx.coroutines.withTimeoutOrNull(90_000L) { model(app, Mode.ONLY_ON_DEVICE).onDeviceExtension?.warmUp() } }
            } catch (e: Exception) { }
        }.apply { isDaemon = true; name = "nano-prepare" }.start()
    }

    /** The cloud model with PocketIDE's tools as functions (the agent's cloud path). Built per request, since the tools can change. */
    @Synchronized
    fun cloudToolModel(ctx: Context, system: String, tools: List<FunctionDeclaration>, tier: dev.pocketide.app.core.CloudTier.Tier = startTier(ctx)): GenerativeModel {
        check(configured(ctx)) { "This build has no Firebase config, so hybrid AI is off. See HACKATHON.md." }
        check(HybridCloud.cloudEnabled()) { "The cloud model is switched off right now (Remote Config: ${HybridCloud.KEY_CLOUD_ON}). Gemini Nano on the phone still works." }
        if (!appCheckDone) { AppCheckSetup.install(FirebaseApp.getInstance()); appCheckDone = true }
        return ai().generativeModel(
            modelName = modelFor(tier), generationConfig = gen(settings(ctx), if (HybridCloud.pinnedModel() == null) tier else null, cloud = true),
            tools = if (tools.isEmpty()) null else listOf(Tool.functionDeclarations(tools)),
            toolConfig = if (tools.isEmpty()) null else ToolConfig(FunctionCallingConfig.auto()),
            systemInstruction = if (system.isBlank()) null else Content("system", listOf(TextPart(system))))
    }

    /** Drops the cached models, so the next request uses new settings or a new cloud model from Remote Config. */
    @Synchronized
    fun reset() { models.clear(); samplers.clear() }

    /** After the person changes Fast/Full: ask AICore again which release to use. */
    fun forgetRelease() { optionId = null; probed = false }

    /** The settings the person saved (Models → Hybrid AI), clamped to safe ranges. */
    fun settings(ctx: Context): HybridSettings {
        val p = ctx.getSharedPreferences(PREFS, 0)
        val d = HybridSettings.DEFAULT
        // Remote Config's defaults (core/Tuning) until the person saves their own
        val t = dev.pocketide.app.core.Tuning
        return HybridSettings.clamp(HybridSettings(
            temperature = p.getFloat("temperature", t.nanoTemperature),
            topK = p.getInt("topK", t.nanoTopK),
            maxOutputTokens = p.getInt("maxOutputTokens", t.nanoMaxOutput),
            onDeviceMaxChars = p.getInt("onDeviceMaxChars", HybridCloud.remoteLimit()),
            fullModel = p.getBoolean("fullModel", t.nanoFullModel)))
    }

    /** Saves the settings; the models are rebuilt on the next request. */
    @Synchronized
    fun saveSettings(ctx: Context, s: HybridSettings) {
        val c = HybridSettings.clamp(s)
        ctx.getSharedPreferences(PREFS, 0).edit()
            .putFloat("temperature", c.temperature).putInt("topK", c.topK)
            .putInt("maxOutputTokens", c.maxOutputTokens).putInt("onDeviceMaxChars", c.onDeviceMaxChars)
            .putBoolean("fullModel", c.fullModel).apply()
        models.clear(); samplers.clear(); forgetRelease()
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
    private fun chatPrompt(system: String, turns: List<Pair<String, String>>): String = buildString {
        if (system.isNotBlank()) append(system.trim()).append("\n\n")
        for ((role, text) in turns) {
            val t = HybridAgent.clean(text)
            if (t.isNotBlank()) append(role).append(": ").append(t).append("\n\n")
        }
        append("assistant:")
    }

    /** What the phone did with a chat request: the reply, or null and why it leaves the phone (empty: cloud-first, nothing to say). */
    class PhoneReply(val reply: Reply?, val reason: String)

    /** Chat on the phone first. A request that needs the web, or is over the on-phone limit, or that Nano only refuses, is passed on. */
    fun chatPhone(ctx: Context, system: String, turns: List<Pair<String, String>>): PhoneReply {
        val prompt = chatPrompt(system, turns)
        val mode = HybridScreen.mode(ctx)
        if (mode == Mode.PREFER_IN_CLOUD) return PhoneReply(null, "")
        if (mode == Mode.ONLY_ON_DEVICE) return PhoneReply(ask(ctx, prompt, mode), "")
        val web = dev.pocketide.app.core.HybridRoute.needsCloud(turns.lastOrNull { it.first == "user" }?.second.orEmpty())
        if (web != null) return PhoneReply(null, web)
        val limit = settings(ctx).onDeviceMaxChars
        if (prompt.length > limit) return PhoneReply(null, "this request is longer than my on-phone limit ($limit characters)")
        val r = ask(ctx, prompt, mode)   // the SDK itself uses the cloud when Nano is not available; the label says so
        if (r.onDevice && dev.pocketide.app.core.HybridRoute.refused(r.text)) return PhoneReply(null, "I could not do this one on the phone")
        return PhoneReply(r, "")
    }

    /** Chat on the cloud model. Blocking. */
    fun chatCloud(ctx: Context, system: String, turns: List<Pair<String, String>>): Reply = ask(ctx, chatPrompt(system, turns), Mode.PREFER_IN_CLOUD)

    /** Chat, phone first, then the cloud model. Blocking. */
    fun chat(ctx: Context, system: String, turns: List<Pair<String, String>>): Reply {
        val p = chatPhone(ctx, system, turns)
        p.reply?.let { return it }
        val c = chatCloud(ctx, system, turns)
        return if (p.reason.isEmpty()) c else Reply(c.text, c.onDevice, c.seconds, "Sent to the cloud: ${p.reason}.")
    }

    /** One single-turn request. Blocking. */
    fun ask(ctx: Context, prompt: String, mode: Mode): Reply {
        probe(ctx)   // 1.5.219: use the Gemini Nano release this phone has
        var (use, note) = route(mode, prompt.length, settings(ctx).onDeviceMaxChars)
        if (!HybridCloud.cloudEnabled() && use != Mode.ONLY_ON_DEVICE) {
            use = Mode.ONLY_ON_DEVICE
            note = "The cloud is switched off right now (Remote Config), so this stayed on the phone."
        }
        val t0 = System.nanoTime()
        fun once() = HybridCloud.traced(if (use == Mode.ONLY_ON_DEVICE) "hybrid_phone" else "hybrid_chat") { runBlocking { model(ctx, use).generateContent(prompt) } }
        // 1.5.184: a cloud tier this project cannot use is skipped (the next one down answers); a refused thinking setting is dropped
        var r: com.google.firebase.ai.type.GenerateContentResponse? = null
        var tries = 0
        while (r == null) {
            try { r = once() } catch (e: Exception) {
                val msg = explain(e)
                val tier = tierOf(HybridCloud.cloudModel())
                if (++tries > 3 || tier == null || use == Mode.ONLY_ON_DEVICE || HybridCloud.pinnedModel() != null) throw e
                when {
                    dev.pocketide.app.core.CloudTier.thinkingRefused(msg) && tier !in noThinking -> noThinking.add(tier)
                    dev.pocketide.app.core.CloudTier.unavailable(msg) && dev.pocketide.app.core.CloudTier.lower(tier) != null -> {
                        skipTiers.add(tier); note = listOf(note, "${tier.label} (${tier.model}) is not available to this project, so the next model down answered.").filter { it.isNotBlank() }.joinToString(" ")
                    }
                    else -> throw e
                }
                reset()
            }
        }
        val secs = (System.nanoTime() - t0) / 1e9
        val done = r!!
        return Reply(done.text?.trim().orEmpty(), done.inferenceSource == InferenceSource.ON_DEVICE, secs, note, HybridCloud.cloudModel())
    }

    /** Gemini Nano on this phone: "AVAILABLE", "DOWNLOADABLE", "DOWNLOADING", "UNAVAILABLE" or an error. Blocking. */
    fun nanoStatus(ctx: Context): String = try {
        probe(ctx, force = true)
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
        // a judges' build carries one token for every phone (R.string.firebase_app_check_debug_token, set at build time)
        ctx.resources.getIdentifier("firebase_app_check_debug_token", "string", ctx.packageName).takeIf { it != 0 }
            ?.let { ctx.getString(it) }?.takeIf { it.isNotBlank() }?.let { return it }
        val key = FirebaseApp.getInstance().persistenceKey
        ctx.getSharedPreferences("com.google.firebase.appcheck.debug.store.$key", Context.MODE_PRIVATE)
            .getString("com.google.firebase.appcheck.debug.DEBUG_SECRET", null)
    } catch (e: Exception) { null }

    /** A short, actionable message for the usual setup problems. */
    fun explain(e: Throwable): String {
        // 1.5.184: the SDK's UnknownException says only "Something unexpected happened."; the cause underneath is what helps
        val root = generateSequence(e.cause) { it.cause }.take(5).lastOrNull { !it.message.isNullOrBlank() }
        val m = (e.message ?: e.javaClass.simpleName) + (root?.let { " (" + it.javaClass.simpleName + ": " + it.message!!.take(200) + ")" } ?: "")
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
