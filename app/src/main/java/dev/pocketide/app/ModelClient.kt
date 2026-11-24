package dev.pocketide.app

import android.os.Handler
import android.os.Looper
import dev.pocketide.app.core.MiniJson
import dev.pocketide.app.core.LocalModels
import dev.pocketide.app.core.ModelException
import dev.pocketide.app.core.Provider
import dev.pocketide.app.core.ProviderKind
import dev.pocketide.app.core.Providers
import dev.pocketide.app.core.Sample
import dev.pocketide.app.core.SseDecoder
import dev.pocketide.app.core.UsageCollector
import dev.pocketide.app.core.UsageMeter
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.Executors

data class Msg(val role: String, val content: String)

/** Callbacks arrive on the main thread. */
interface ModelListener {
    fun onStatus(text: String)
    fun onDelta(acc: String)
    fun onDone(reply: String)
    /** The user pressed Stop. partial is whatever had arrived. */
    fun onStopped(partial: String)
    fun onError(message: String, needsSettings: Boolean)
}

/** v0.4 W2: callbacks for one native tool-calling request. Arrive on the main thread. */
interface NativeListener {
    fun onStatus(text: String)
    fun onDelta(acc: String)
    /** The response finished: its text and any tool calls. */
    fun onTurn(turn: dev.pocketide.app.core.ToolTurn)
    fun onStopped(partial: String)
    /** toolsRejected: the provider refused the tools (the caller falls back to the text protocol). */
    fun onError(message: String, needsSettings: Boolean, toolsRejected: Boolean)
}

/**
 * Starts one tool-calling model request for the agent loop. The app uses the network ([ModelClient.startNative]); the agent
 * harness (tests and benchmarks, test/.../bench) swaps in a scripted model so the real loop can run on a computer (1.5.85).
 */
interface ModelGateway {
    fun startNative(settings: AppSettings, system: String, messages: List<Map<String, Any?>>, tools: Boolean, listener: NativeListener,
                    docs: Boolean = false, loaded: Set<String>? = null): ModelCall

    companion object {
        val NETWORK: ModelGateway = object : ModelGateway {
            override fun startNative(settings: AppSettings, system: String, messages: List<Map<String, Any?>>, tools: Boolean, listener: NativeListener,
                                     docs: Boolean, loaded: Set<String>?): ModelCall = ModelClient.startNative(settings, system, messages, tools, listener, docs, loaded)
        }
        /** The gateway the agent loop uses. Only tests change it. */
        @Volatile var current: ModelGateway = NETWORK
    }
}

open class ModelCall {
    @Volatile var cancelled = false
    @Volatile var conn: HttpURLConnection? = null

    open fun cancel() {
        cancelled = true
        val c = conn
        if (c != null) Thread { try { c.disconnect() } catch (e: Exception) { /* already closed */ } }.start()
    }
}

object ModelClient {
    /**
     * Readies the built-in engine before a request to it (1.5.182): starts llama-server with the chosen model, or says why it
     * cannot. Set by MainActivity (EngineHost.ensure); null in tests. Runs on the request thread, so loading does not block the screen.
     */
    @Volatile var engineReady: ((model: String) -> String?)? = null

    /** Anthropic keys that are not tied to one workspace need this header; empty = not sent. Set from Settings. */
    @Volatile var workspaceId: String = ""

    /** Providers known to report token usage in a streamed reply when asked. */
    private val USAGE_PROVIDERS = setOf("openai", "groq", "openrouter", "mistral", "gemini", "cerebras")

    private val pool = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())

    private class Problem(message: String, val needsSettings: Boolean = false) : Exception(message)

    /**
     * Sends one request and streams the reply. buildSystem runs on the background thread, so ranking a large
     * project does not freeze the screen.
     */
    fun start(settings: AppSettings, buildSystem: () -> String, history: List<Msg>, user: String, listener: ModelListener,
              images: List<String> = emptyList(),  // v0.4 W2: data URLs attached to this message
              buildContext: (() -> String)? = null,  // project files, sent with the message so the system prompt stays identical between requests
              modelOverride: String = ""  // a different model for this one request (inline suggestions)
    ): ModelCall {
        val call = ModelCall()
        val providerId = settings.provider
        val provider = Providers.byId(providerId)
        val key = settings.key(providerId)
        val model = modelOverride.ifBlank { settings.model(providerId).ifBlank { provider.model } }
        val base = settings.baseFor(providerId)
        val agent = settings.agent
        pool.execute {
            var acc = ""
            try {
                if (provider.kind == ProviderKind.DEMO) {
                    acc = streamDemo(call, user, agent) { s -> main.post { if (!call.cancelled) listener.onDelta(s) } }
                } else if (provider.kind == ProviderKind.HYBRID) {
                    val withCtx = if (buildContext != null) dev.pocketide.app.core.PromptCache.withContext(buildContext(), user) else user
                    val turns = history.map { it.role to it.content } + ("user" to withCtx)
                    val hctx = hybridCtx()
                    val sys = buildSystem()
                    val ph = hybrid { HybridAi.chatPhone(hctx, sys, turns) }
                    val got = ph.reply
                    if (got != null) {
                        acc = got.text + "\n\n" + HybridScreen.label(got)   // the reply says where it ran, and how long it took
                        HybridCloud.log(if (got.onDevice) "phone" else "cloud", got.seconds, "", "chat")
                    }
                    else {
                        // Gemini Nano passes it on, and says so at once: to the strong model when one is set, else the cloud model.
                        // The cloud-first mode (no reason) always uses the cloud model.
                        val strong = if (ph.reason.isNotEmpty()) hybridStrong(settings) else null
                        val handoff = if (ph.reason.isEmpty()) "" else HybridAgent.handoff(ph.reason, hybridTarget(strong))
                        if (handoff.isNotEmpty()) main.post { if (!call.cancelled) { listener.onStatus(handoff); listener.onDelta(handoff) } }
                        if (strong != null) {
                            val done = java.util.concurrent.atomic.AtomicBoolean(false)
                            HybridCloud.log("strong", 0.0, ph.reason, "chat")
                            val inner = start(AppSettings(hctx).also { it.override = strong }, buildSystem, history, user,
                                handedChat(listener, handoff, done, hybridTarget(strong)) { why ->
                                    pool.execute {
                                        try {
                                            val note = HybridAgent.fallback(why, HybridCloud.cloudModel())
                                            main.post { if (!call.cancelled) { listener.onStatus(note); listener.onDelta(listOf(handoff, note).filter { it.isNotBlank() }.joinToString("\n\n")) } }
                                            val r = hybrid { HybridAi.chatCloud(hctx, sys, turns) }
                                            HybridCloud.log("cloud", r.seconds, ph.reason, "chat")
                                            val text = listOf(handoff, note, r.text, HybridScreen.label(r)).filter { it.isNotBlank() }.joinToString("\n\n")
                                            main.post { if (!call.cancelled) listener.onDone(text) }
                                        } catch (e: Problem) { main.post { if (!call.cancelled) listener.onError(e.message ?: "Request failed", e.needsSettings) } }
                                        catch (e: Exception) { main.post { if (!call.cancelled) listener.onError(HybridAi.explain(e), false) } }
                                    }
                                }, images, buildContext)
                            link(call, inner, done)
                            return@execute
                        }
                        val r = hybrid { HybridAi.chatCloud(hctx, sys, turns) }
                        HybridCloud.log("cloud", r.seconds, ph.reason, "chat")
                        acc = listOf(handoff, r.text, HybridScreen.label(r)).filter { it.isNotBlank() }.joinToString("\n\n")
                    }
                } else {
                    val result = StringBuilder()
                    val local = LocalModels.isLocal(providerId, base)
                    val withCtx = if (buildContext != null) dev.pocketide.app.core.PromptCache.withContext(buildContext(), user) else user
                    val sent = if (local && LocalModels.wantsNoThink(model)) LocalModels.addNoThink(withCtx) else withCtx
                    stream(call, provider, key, model, base, buildSystem(), history, sent, local, images, settings.promptCache, settings.outputCap(providerId, model),
                        { st -> main.post { if (!call.cancelled) listener.onStatus(st) } },
                        { s -> result.setLength(0); result.append(s); main.post { if (!call.cancelled) listener.onDelta(s) } },
                        settings.openRouterExtra(providerId, model, false))
                    acc = LocalModels.stripThinking(result.toString()).text
                }
                if (call.cancelled) main.post { listener.onStopped(acc) }
                else if (acc.isEmpty()) main.post { listener.onError("The model returned an empty reply.", false) }
                else main.post { listener.onDone(acc) }
            } catch (e: Problem) {
                main.post { listener.onError(e.message ?: "Something went wrong.", e.needsSettings) }
            } catch (e: ModelException) {
                main.post { listener.onError(e.message ?: "The provider returned an error.", false) }
            } catch (e: Exception) {
                if (call.cancelled) main.post { listener.onStopped(acc) }
                else main.post { listener.onError(describe(e, provider, base), false) }
            }
        }
        return call
    }

    /** Hybrid AI (Gemini Nano on the phone, cloud Gemini when needed). Text only: the agent falls back to its text format. */
    private fun hybridChat(system: String, turns: List<Pair<String, String>>): HybridAi.Reply = hybrid { HybridAi.chat(hybridCtx(), system, turns) }

    private fun hybridCtx(): android.content.Context =
        HybridAi.appContext ?: throw Problem("Hybrid AI is not ready yet. Close and reopen PocketIDE.", false)

    /** Runs a hybrid AI step with its errors explained the same way as quick help. */
    private inline fun <T> hybrid(f: () -> T): T =
        try { f() } catch (e: Problem) { throw e } catch (e: Exception) { throw Problem(HybridAi.explain(e), false) }

    /** Where Gemini Nano passes requests it cannot do: the strong model set for workflows, when that is another provider ready to use. */
    private fun hybridStrong(settings: AppSettings): dev.pocketide.app.core.Workflow.Slot? {
        val s = settings.strongSlot() ?: return null
        if (s.provider == "hybrid" || s.provider == "demo") return null
        if (!Providers.byId(s.provider).keyless && settings.key(s.provider).isBlank()) return null
        return s
    }

    private fun hybridTarget(s: dev.pocketide.app.core.Workflow.Slot?): String =
        if (s == null) "the cloud model (${HybridCloud.cloudModel()})"
        else s.model.ifBlank { Providers.byId(s.provider).model }.ifBlank { Providers.byId(s.provider).name } + " (your strong model)"

    /** Stops [inner] when [outer] is stopped, until [done]. */
    private fun link(outer: ModelCall, inner: ModelCall, done: java.util.concurrent.atomic.AtomicBoolean) {
        Thread {
            try { while (!done.get()) { if (outer.cancelled) { inner.cancel(); break }; Thread.sleep(200) } } catch (e: InterruptedException) { /* done */ }
        }.apply { isDaemon = true }.start()
    }

    /** The strong model's chat reply, with Gemini Nano's handoff line kept above it. */
    private fun handedChat(l: ModelListener, prefix: String, done: java.util.concurrent.atomic.AtomicBoolean, target: String,
                           fallback: (String) -> Unit) = object : ModelListener {
        private fun p(s: String) = if (prefix.isEmpty()) s else prefix + "\n\n" + s
        override fun onStatus(text: String) = l.onStatus(text)
        override fun onDelta(acc: String) = l.onDelta(p(acc))
        override fun onDone(reply: String) { done.set(true); l.onDone(p(reply)) }
        override fun onStopped(partial: String) { done.set(true); l.onStopped(p(partial)) }
        override fun onError(message: String, needsSettings: Boolean) { done.set(true); fallback("$target could not answer: ${message.take(160)}") }
    }

    /** The strong model's agent turn, with Gemini Nano's handoff line kept above it. */
    private fun handedNative(l: NativeListener, prefix: String, done: java.util.concurrent.atomic.AtomicBoolean, target: String,
                             fallback: (String) -> Unit) = object : NativeListener {
        private fun p(s: String) = if (prefix.isEmpty()) s else prefix + "\n\n" + s
        override fun onStatus(text: String) = l.onStatus(text)
        override fun onDelta(acc: String) = l.onDelta(p(acc))
        override fun onTurn(turn: dev.pocketide.app.core.ToolTurn) { done.set(true); l.onTurn(turn.copy(text = p(turn.text))) }
        override fun onStopped(partial: String) { done.set(true); l.onStopped(p(partial)) }
        override fun onError(message: String, needsSettings: Boolean, toolsRejected: Boolean) { done.set(true); fallback("$target could not answer: ${message.take(160)}") }
    }

    // ---- v0.4 W2: native tool calling ----

    /**
     * One request in a tool-calling conversation. messages are already in the provider's format (see Tools);
     * tools=false sends the same conversation without tool definitions.
     */
    /** The reply limit for a tool-calling request, lowered when the prompt nearly fills a known window (ContextFit.replyFor). */
    private fun replyCap(settings: AppSettings, providerId: String, model: String, system: String, messages: List<Map<String, Any?>>, tools: Boolean, docs: Boolean, loaded: Set<String>?): Int {
        val prompt = system.length + dev.pocketide.app.core.HistoryTrim.total(messages) + (if (tools) dev.pocketide.app.core.Tools.toolChars(docs, loaded) else 0)
        return dev.pocketide.app.core.ContextFit.replyFor(settings.outputCap(providerId, model), dev.pocketide.app.core.ModelFacts.context(providerId, model, settings.baseFor(providerId)), prompt)
    }

    fun startNative(settings: AppSettings, system: String, messages: List<Map<String, Any?>>, tools: Boolean, listener: NativeListener, docs: Boolean = false, loaded: Set<String>? = null): ModelCall {
        val call = ModelCall()
        val providerId = settings.provider
        val p = Providers.byId(providerId)
        val key = settings.key(providerId)
        val model = settings.model(providerId).ifBlank { p.model }
        val base = settings.baseFor(providerId)
        pool.execute {
            val anthropic = p.kind == ProviderKind.ANTHROPIC
            var ts = dev.pocketide.app.core.ToolStream(anthropic)
            try {
                if (!p.keyless && key.isBlank()) throw Problem("Add your API key in settings first.", true)
                if (p.kind == ProviderKind.HYBRID) {
                    if (tools) {
                        // the agent: real tool calls, from Gemini Nano (small local model mode) or the cloud (function calling)
                        val toolList = dev.pocketide.app.core.Tools.body(false, "", system, messages, true, docs, false, false, 0, loaded)["tools"] as? List<*>
                        main.post { if (!call.cancelled) listener.onStatus("Thinking (hybrid AI)…") }
                        val hctx = hybridCtx()
                        fun emit(res: HybridAgent.Result, handoff: String, reason: String) {
                            HybridCloud.log(if (res.onDevice) "phone" else "cloud", res.seconds, reason, "agent")
                            val lab = HybridScreen.label(HybridAi.Reply("", res.onDevice, res.seconds, res.note))
                            val shown = listOf(handoff, res.turn.text, lab).filter { it.isNotBlank() }.joinToString("\n\n")
                            main.post { if (!call.cancelled) { listener.onStatus(lab); listener.onTurn(res.turn.copy(text = shown)) } }
                        }
                        val ph = hybrid { HybridAgent.phone(hctx, messages, docs) }
                        val here = ph.result
                        if (here != null) emit(here, "", "")
                        else {
                            // Gemini Nano passes it on, and says so at once: to the strong model when one is set, else the cloud model.
                            // The cloud-first mode (no reason) always uses the cloud model.
                            val strong = if ((ph.reason.isNotEmpty() || ph.stay) && !ph.strongFailed) hybridStrong(settings) else null
                            val handoff = if (ph.reason.isEmpty()) "" else HybridAgent.handoff(ph.reason, hybridTarget(strong))
                            if (handoff.isNotEmpty()) main.post { if (!call.cancelled) { listener.onStatus(handoff); listener.onDelta(handoff) } }
                            if (strong != null) {
                                val done = java.util.concurrent.atomic.AtomicBoolean(false)
                                HybridCloud.log("strong", 0.0, ph.reason, "agent")
                                val inner = startNative(AppSettings(hctx).also { it.override = strong }, system, messages, tools,
                                    handedNative(listener, handoff, done, hybridTarget(strong)) { why ->
                                        // the strong model failed (a sign-in, a key, a limit): the cloud model answers instead, and Nano says so
                                        pool.execute {
                                            try {
                                                val note = HybridAgent.fallback(why, HybridCloud.cloudModel())
                                                main.post { if (!call.cancelled) listener.onStatus(note) }
                                                emit(hybrid { HybridAgent.cloud(hctx, system, messages, toolList) }, listOf(handoff, note).filter { it.isNotBlank() }.joinToString("\n\n"), ph.reason)
                                            } catch (e: Problem) { main.post { if (!call.cancelled) listener.onError(e.message ?: "Request failed", e.needsSettings, false) } }
                                            catch (e: Exception) { main.post { if (!call.cancelled) listener.onError(HybridAi.explain(e), false, false) } }
                                        }
                                    }, docs, loaded)
                                link(call, inner, done)
                                return@execute
                            }
                            emit(hybrid { HybridAgent.cloud(hctx, system, messages, toolList) }, handoff, ph.reason)
                        }
                    } else {
                        val r = hybridChat(system, messages.map { ((it["role"] as? String) ?: "user") to (it["content"]?.toString() ?: "") })
                        main.post { if (!call.cancelled) { listener.onStatus(HybridScreen.label(r)); listener.onTurn(dev.pocketide.app.core.ToolTurn(r.text + "\n\n" + HybridScreen.label(r), emptyList(), "stop")) } }
                    }
                    return@execute
                }
                if (providerId == dev.pocketide.app.core.Engine.PROVIDER) engineReady?.invoke(model)?.let { throw Problem(it, true) }
                val url = if (p.custom && base.contains("ACCOUNT_ID")) throw Problem("Replace ACCOUNT_ID in the server address with your Cloudflare account ID (Settings).", true) else if (p.custom) Providers.customUrl(base) ?: throw Problem("Enter the server address in settings.", true) else p.url
                if (model.isBlank() && !p.modelOptional) throw Problem("Enter a model name in settings.", true)
                val bodyMap = dev.pocketide.app.core.Tools.body(anthropic, model, system, messages, tools, docs, providerId in USAGE_PROVIDERS, settings.promptCache, replyCap(settings, providerId, model, system, messages, tools, docs, loaded), loaded) + settings.openRouterExtra(providerId, model, tools)
                val effort = dev.pocketide.app.core.ThinkEffort.extra(settings.thinkEffort, anthropic, providerId, model)
                // 1.5.177: small local model mode (SmallLocal) asks the server for one JSON action per reply, with Qwen's sampling profile
                val smallBody = if (!tools && dev.pocketide.app.core.SmallLocal.applies(providerId, base, model)) dev.pocketide.app.core.SmallLocal.body() else emptyMap()
                var bodyJson = MiniJson.write(bodyMap + effort + smallBody)
                main.post { if (!call.cancelled) listener.onStatus("Thinking…") }
                val localModel = LocalModels.isLocal(providerId, base)
                val started = System.currentTimeMillis()
                val done = java.util.concurrent.atomic.AtomicBoolean(false)
                val note = java.util.concurrent.atomic.AtomicReference<String?>(null)
                // Shows the elapsed time from the moment the request is sent, even before the server answers at all.
                val ticker = Thread {
                    try {
                        while (!done.get() && !call.cancelled) {
                            if (ts.textSoFar.isEmpty() && note.get() == null) {
                                val st = LocalModels.waitingStatus((System.currentTimeMillis() - started) / 1000, ts.reasoningChars, localModel)
                                main.post { if (!call.cancelled && !done.get() && note.get() == null) listener.onStatus(st) }
                            }
                            Thread.sleep(1000)
                        }
                    } catch (e: InterruptedException) { /* done */ }
                }
                ticker.isDaemon = true
                ticker.start()
                var conn: HttpURLConnection? = null
                try {
                  var stalls = 0
                  var breaks = 0
                  while (true) {
                    val reply = send(call, url, anthropic, key, bodyJson.toByteArray(Charsets.UTF_8), if (localModel) 600_000 else 120_000, note) { t -> main.post { if (!call.cancelled) listener.onStatus(t) } }
                    conn = reply.conn
                    val code = reply.code
                    if (code == -2) { main.post { listener.onStopped("") }; return@execute }
                    if (code !in 200..299) {
                        val raw = reply.error
                        val msg = SseDecoder.errorMessage(raw) ?: raw.take(300).ifBlank { "Request failed." }
                        // a provider that does not take the thinking-effort field: ask again without it, and do not send it again
                        if (effort.isNotEmpty() && dev.pocketide.app.core.ThinkEffort.rejects(code, msg) && bodyJson.contains("\"reasoning_effort\"")) {
                            dev.pocketide.app.core.ThinkEffort.reject(providerId, model)
                            bodyJson = MiniJson.write(bodyMap + smallBody)
                            continue
                        }
                        if (tools && dev.pocketide.app.core.Tools.isRejection(code, msg)) {
                            main.post { listener.onError("$msg (HTTP $code)", false, true) }
                            return@execute
                        }
                        if (code == 402 && providerId == dev.pocketide.app.core.OpenRouter.ID) throw Problem(dev.pocketide.app.core.OpenRouter.paymentNote(), true)
                        throw Problem("$msg (HTTP $code)", code == 401 || code == 403)
                    }
                    try {
                        BufferedReader(InputStreamReader(reply.conn.inputStream, Charsets.UTF_8)).use { r ->
                            while (true) {
                                if (call.cancelled) break
                                val line = r.readLine() ?: break
                                val piece = ts.feed(line)
                                if (piece.isNotEmpty()) {
                                    val acc = LocalModels.stripThinking(ts.textSoFar).text
                                    main.post { if (!call.cancelled) listener.onDelta(acc) }
                                }
                            }
                        }
                        break
                    } catch (e: SocketTimeoutException) {
                        if (call.cancelled || !dev.pocketide.app.core.NetRetry.stalledBeforeAnswer(e, ts.nothingYet, stalls)) throw e
                        stalls++
                        try { reply.conn.disconnect() } catch (x: Exception) { /* ignore */ }
                        main.post { if (!call.cancelled) listener.onStatus(dev.pocketide.app.core.NetRetry.STALL_STATUS) }
                    } catch (e: java.io.IOException) {
                        // the answer was cut off part way ("Premature EOF"): nothing has been acted on yet, so ask for it once more
                        if (call.cancelled || !dev.pocketide.app.core.NetRetry.brokenStream(e, breaks)) throw e
                        breaks++
                        try { reply.conn.disconnect() } catch (x: Exception) { /* ignore */ }
                        ts = dev.pocketide.app.core.ToolStream(anthropic)
                        main.post { if (!call.cancelled) listener.onStatus(dev.pocketide.app.core.NetRetry.BROKEN_STATUS) }
                    }
                  }
                    UsageMeter.report(providerId, model, if (ts.usage.any) ts.usage.result() else UsageCollector.estimate(bodyJson.length, ts.textSoFar.length))
                } finally {
                    done.set(true)
                    ticker.interrupt()
                    try { conn?.disconnect() } catch (e: Exception) { /* ignore */ }
                }
                val turn = ts.result()
                val tagged = LocalModels.thoughtsIn(turn.text)
                val clean = turn.copy(text = LocalModels.stripThinking(turn.text).text,
                    thinking = listOf(turn.thinking, tagged).filter { it.isNotBlank() }.joinToString("\n\n"))
                if (call.cancelled) main.post { listener.onStopped(clean.text) }
                else main.post { listener.onTurn(clean) }
            } catch (e: Problem) {
                main.post { listener.onError(e.message ?: "Something went wrong.", e.needsSettings, false) }
            } catch (e: ModelException) {
                main.post { listener.onError(e.message ?: "The provider returned an error.", false, false) }
            } catch (e: Exception) {
                if (call.cancelled) main.post { listener.onStopped(ts.textSoFar) }
                else main.post { listener.onError(describe(e, p, base), false, false) }
            }
        }
        return call
    }

    private class Reply(val conn: HttpURLConnection, val code: Int, val error: String)

    /**
     * Sends the request. A short rate limit ("try again in 15s") is waited out and retried here, up to
     * RateLimit.RETRIES times, with a countdown in the status line; anything else is returned as it is.
     */
    private fun send(
        call: ModelCall, url: String, anthropic: Boolean, key: String, body: ByteArray, readTimeout: Int,
        note: java.util.concurrent.atomic.AtomicReference<String?>, onWait: (String) -> Unit
    ): Reply {
        var attempt = 0
        var netTries = 0
        while (true) {
            val conn = URL(url).openConnection() as HttpURLConnection
            call.conn = conn
            conn.requestMethod = "POST"
            // 1.5.178: Ollama Cloud's edge refuses Android's default User-Agent ("Dalvik/2.1.0 …") with an HTML 403 page; Test connection
            // passed (ModelPicker sends "PocketIDE") while every chat request failed
            conn.setRequestProperty("User-Agent", dev.pocketide.app.core.BuildTag.userAgent)
            conn.connectTimeout = 20_000
            conn.readTimeout = readTimeout
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
            if (anthropic) {
                conn.setRequestProperty("x-api-key", key)
                conn.setRequestProperty("anthropic-version", "2023-06-01")
                if (workspaceId.isNotBlank()) conn.setRequestProperty("anthropic-workspace-id", workspaceId)
            } else if (key.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $key")
            // 1.5.175: OpenRouter's app attribution, so it can tell requests come from a coding app (some free models are served only to listed apps)
            if (url.contains("openrouter.ai")) {
                conn.setRequestProperty("HTTP-Referer", "https://github.com/dbal2969-ai/test-coder2.2")
                conn.setRequestProperty("X-Title", "PocketIDE")
            }
            val code: Int
            try {
                conn.outputStream.use { it.write(body) }
                code = conn.responseCode
            } catch (e: Exception) {
                // no answer yet: a dropped connection is tried again a couple of times before the error is shown
                if (call.cancelled || !dev.pocketide.app.core.NetRetry.transient(e) || netTries >= dev.pocketide.app.core.NetRetry.RETRIES) throw e
                try { conn.disconnect() } catch (x: Exception) { /* ignore */ }
                netTries++
                var left = dev.pocketide.app.core.NetRetry.waitSeconds(netTries)
                while (left > 0 && !call.cancelled) {
                    val t = dev.pocketide.app.core.NetRetry.status(left, netTries)
                    note.set(t); onWait(t)
                    Thread.sleep(1000)
                    left--
                }
                note.set(null)
                if (call.cancelled) return Reply(conn, -2, "")
                continue
            }
            if (code in 200..299) return Reply(conn, code, "")
            val raw = try { conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "" } catch (e: Exception) { "" }
            val msg = SseDecoder.errorMessage(raw) ?: raw.take(300)
            val wait = if (attempt < dev.pocketide.app.core.RateLimit.RETRIES && !call.cancelled)
                dev.pocketide.app.core.RateLimit.waitSeconds(code, msg, conn.getHeaderField("Retry-After"), attempt) else null
            if (wait == null) return Reply(conn, code, raw)
            try { conn.disconnect() } catch (e: Exception) { /* ignore */ }
            attempt++
            var left = wait
            while (left > 0 && !call.cancelled) {
                val t = dev.pocketide.app.core.RateLimit.status(left, attempt)
                note.set(t); onWait(t)
                Thread.sleep(1000)
                left--
            }
            note.set(null)
            if (call.cancelled) return Reply(conn, -2, "")
        }
    }

    private fun describe(e: Exception, p: Provider, base: String): String = when {
        e is SocketTimeoutException ->
            if (p.custom && !Providers.hosted(p)) "${Providers.serverName(base)} did not answer in time. Check that the server is running, or try a smaller model."
            else dev.pocketide.app.core.NetRetry.timeoutMessage(Providers.shortName(p))
        e is UnknownHostException || e is java.net.ConnectException || e is IOException && e.message?.contains("Unable to resolve") == true ||
            dev.pocketide.app.core.NetRetry.transient(e) ->
            if (p.custom && !Providers.hosted(p)) "Could not reach ${Providers.serverName(base)}. Check that the server is running and the address is right."
            else dev.pocketide.app.core.NetRetry.offlineMessage(Providers.shortName(p))
        else -> e.message ?: "Something went wrong."
    }

    private fun streamDemo(call: ModelCall, user: String, agent: Boolean, onDelta: (String) -> Unit): String {
        val full = if (agent) Sample.demoAgentReply(user.contains("produced these errors")) else Sample.demoReply()
        var i = 0
        while (i < full.length) {
            if (call.cancelled) return full.substring(0, i)
            Thread.sleep(18)
            i = minOf(full.length, i + 24)
            onDelta(full.substring(0, i))
        }
        return full
    }

    private fun stream(
        call: ModelCall, p: Provider, key: String, model: String, base: String, system: String,
        history: List<Msg>, user: String, local: Boolean, images: List<String>, cache: Boolean, outCap: Int, onStatus: (String) -> Unit, onDelta: (String) -> Unit,
        extra: Map<String, Any?> = emptyMap()
    ) {
        if (!p.keyless && key.isBlank()) throw Problem("Add your API key in settings first.", true)
        if (p.id == dev.pocketide.app.core.Engine.PROVIDER) engineReady?.invoke(model)?.let { throw Problem(it, true) }
        val url: String = if (p.custom) {
            (if (base.contains("ACCOUNT_ID")) throw Problem("Replace ACCOUNT_ID in the server address with your Cloudflare account ID (Settings).", true) else Providers.customUrl(base)) ?: throw Problem("Enter the server address in settings, for example ${p.defaultBase.ifEmpty { "http://127.0.0.1:11434/v1" }}.", true)
        } else p.url
        if (model.isBlank() && !p.modelOptional) throw Problem("Enter a model name in settings.", true)

        val messages = ArrayList<Map<String, Any?>>()
        if (p.kind == ProviderKind.OPENAI) messages.add(mapOf("role" to "system", "content" to system))
        history.takeLast(12).forEach { messages.add(mapOf("role" to it.role, "content" to it.content)) }
        messages.add(mapOf("role" to "user", "content" to dev.pocketide.app.core.Images.content(p.kind == ProviderKind.ANTHROPIC, user, images)))  // v0.4 W2
        val opts: Map<String, Any?> = if (p.kind != ProviderKind.ANTHROPIC && p.id in USAGE_PROVIDERS) mapOf("stream_options" to mapOf("include_usage" to true)) else emptyMap()
        val body: Map<String, Any?> = if (p.kind == ProviderKind.ANTHROPIC)
            mapOf("model" to model, "max_tokens" to 8192, "system" to (if (cache) dev.pocketide.app.core.PromptCache.systemBlocks(system) else system),
                "messages" to (if (cache) dev.pocketide.app.core.PromptCache.markLast(messages) else messages), "stream" to true)
        else
            (if (model.isBlank()) mapOf("messages" to messages, "stream" to true) else mapOf("model" to model, "messages" to messages, "stream" to true)) + opts +
                (if (outCap > 0) mapOf("max_tokens" to outCap) else emptyMap()) + extra
        val bodyJson = MiniJson.write(body)

        onStatus("Thinking…")
        var conn: HttpURLConnection? = null
        val note = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val decoder = SseDecoder(p.kind == ProviderKind.ANTHROPIC)
        val acc = StringBuilder()
        // The clock starts when the request is sent: a model on the phone can spend minutes reading the prompt before any reply.
        val started = System.currentTimeMillis()
        val gotText = java.util.concurrent.atomic.AtomicBoolean(false)
        val ticker = Thread {
            try {
                while (!gotText.get() && !call.cancelled) {
                    if (note.get() == null) onStatus(LocalModels.waitingStatus((System.currentTimeMillis() - started) / 1000, decoder.reasoningChars, local))
                    Thread.sleep(1000)
                }
            } catch (e: InterruptedException) { /* done */ }
        }
        ticker.isDaemon = true
        ticker.start()
        try {
            val reply = send(call, url, p.kind == ProviderKind.ANTHROPIC, key, bodyJson.toByteArray(Charsets.UTF_8), if (local) 600_000 else 120_000, note) { t -> onStatus(t) }
            conn = reply.conn
            val code = reply.code
            if (code == -2) return
            if (code !in 200..299) {
                val raw = reply.error
                if (code == 402 && p.id == dev.pocketide.app.core.OpenRouter.ID) throw Problem(dev.pocketide.app.core.OpenRouter.paymentNote(), true)
                throw Problem(SseDecoder.errorMessage(raw)?.let { "$it (HTTP $code)" } ?: "Request failed ($code).", code == 401 || code == 403)
            }
            try {
                BufferedReader(InputStreamReader(reply.conn.inputStream, Charsets.UTF_8)).use { r ->
                    while (true) {
                        if (call.cancelled) break
                        val line = r.readLine() ?: break
                        val piece = decoder.feed(line)
                        if (piece.isNotEmpty()) {
                            acc.append(piece)
                            val v = LocalModels.stripThinking(acc.toString())
                            if (v.thinking || v.text.isEmpty()) {
                                if (v.thoughtChars > 0) onStatus(LocalModels.waitingStatus((System.currentTimeMillis() - started) / 1000, v.thoughtChars, local))
                            } else {
                                gotText.set(true)
                                onDelta(v.text)
                            }
                        }
                    }
                }
            } finally {
                val hadText = gotText.getAndSet(true)
                ticker.interrupt()
                if (!hadText && acc.isNotEmpty()) onDelta(acc.toString())
            }
            UsageMeter.report(p.id, model, if (decoder.usage.any) decoder.usage.result() else UsageCollector.estimate(bodyJson.length, acc.length))

        } finally {
            gotText.set(true)
            ticker.interrupt()
            try { conn?.disconnect() } catch (e: Exception) { /* ignore */ }
        }
    }
}
