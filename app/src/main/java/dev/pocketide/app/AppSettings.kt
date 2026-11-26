package dev.pocketide.app

import android.content.Context
import android.content.SharedPreferences

/** Saved choices. API keys are kept in the app's private storage, which other apps cannot read. */
class AppSettings(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("pocketide", Context.MODE_PRIVATE)
    private val app: Context = ctx.applicationContext  // v0.4 W1: per-project GitHub settings

    /** A model used only for the current stage of a multi-model workflow; null the rest of the time. Never saved. */
    @Volatile var override: dev.pocketide.app.core.Workflow.Slot? = null

    var provider: String
        get() = override?.provider ?: storedProvider
        set(v) { sp.edit().putString("provider", v).apply() }
    private val storedProvider: String get() = sp.getString("provider", null) ?: dev.pocketide.app.core.Starter.FIRST_PROVIDER

    /** off, ask or auto (core/Advisor). */
    var advisorMode: String
        get() = dev.pocketide.app.core.Advisor.parse(sp.getString("advisor.mode", "off") ?: "off")
        set(v) { sp.edit().putString("advisor.mode", dev.pocketide.app.core.Advisor.parse(v)).apply() }

    var advisorCalls: Int
        get() = sp.getInt("advisor.calls", 3).coerceIn(1, 8)
        set(v) { sp.edit().putInt("advisor.calls", v.coerceIn(1, 8)).apply() }

    fun setAdvisorSlot(s: dev.pocketide.app.core.Workflow.Slot) { sp.edit().putString("advisor.slot", s.encode()).apply() }

    /** The advisor model: the one picked, else the strong model of the workflow settings. */
    fun advisorSlot(): dev.pocketide.app.core.Workflow.Slot? {
        dev.pocketide.app.core.Workflow.decode(sp.getString("advisor.slot", "") ?: "")?.let { return it }
        dev.pocketide.app.core.Workflow.decode(sp.getString("workflow.strong", "") ?: "")?.let { return it }
        // none picked: the strongest other provider with a key, else the main model (which turns the advisor off)
        return strongerOther() ?: strongSlot()
    }

    /** The strongest provider with a key that is not the main one (Advisor.STRONG_ORDER), or null. */
    fun strongerOther(): dev.pocketide.app.core.Workflow.Slot? =
        dev.pocketide.app.core.Advisor.bestOther(storedProvider, { key(it).isNotBlank() }) { storedModel(it).ifBlank { dev.pocketide.app.core.Providers.byId(it).model } }


    /** Commit the assistant's finished work automatically in projects that keep version history. */
    var historyAutoCommit: Boolean
        get() = sp.getBoolean("history.autocommit", true)
        set(v) { sp.edit().putBoolean("history.autocommit", v).apply() }

    /** Shown once: the tip to turn on the advisor when a stronger model has a key. */
    var advisorTipShown: Boolean
        get() = sp.getBoolean("advisor.tip", false)
        set(v) { sp.edit().putBoolean("advisor.tip", v).apply() }

    /** The advisor for the next request, or null when it is off or would be the same model as the one doing the work. */
    fun advisorPlan(): dev.pocketide.app.core.Advisor.Plan? {
        val mode = advisorMode
        if (fastMode || mode == dev.pocketide.app.core.Advisor.OFF) return null
        val slot = advisorSlot() ?: return null
        val me = dev.pocketide.app.core.Workflow.Slot(provider, model(provider).ifBlank { dev.pocketide.app.core.Providers.byId(provider).model })
        if (slot.same(me) || (slot.provider == me.provider && slot.model.ifBlank { me.model } == me.model)) return null
        return dev.pocketide.app.core.Advisor.Plan(mode, slot, advisorCalls)
    }

    /** The preset agent profile (core/AgentProfiles). */
    var profile: String
        get() = dev.pocketide.app.core.AgentProfiles.byId(sp.getString("profile", "general") ?: "general").id
        set(v) { sp.edit().putString("profile", dev.pocketide.app.core.AgentProfiles.byId(v).id).apply() }

    /** single, cascade or critique (see core/Workflow). */
    var workflow: String
        get() = dev.pocketide.app.core.Workflow.parse(sp.getString("workflow", "single") ?: "single")
        set(v) { sp.edit().putString("workflow", dev.pocketide.app.core.Workflow.parse(v)).apply() }

    /** The draft or strong slot exactly as saved ("" when none was picked), so a benchmark can put it back. */
    fun savedSlot(draft: Boolean): String = sp.getString(if (draft) "workflow.draft" else "workflow.strong", "") ?: ""
    fun restoreSlot(draft: Boolean, saved: String) { sp.edit().putString(if (draft) "workflow.draft" else "workflow.strong", saved).apply() }

    fun setSlot(draft: Boolean, s: dev.pocketide.app.core.Workflow.Slot) { sp.edit().putString(if (draft) "workflow.draft" else "workflow.strong", s.encode()).apply() }

    /** The model that drafts: the one picked, else a cheap provider that has a key and differs from the strong one. */
    fun draftSlot(): dev.pocketide.app.core.Workflow.Slot? {
        dev.pocketide.app.core.Workflow.decode(sp.getString("workflow.draft", "") ?: "")?.let { return it }
        val strong = strongSlot() ?: return null
        val usable = dev.pocketide.app.core.Providers.all.filter { it.id != "demo" && (key(it.id).isNotBlank() || it.keyless) }.map { it.id }
        val pid = dev.pocketide.app.core.Workflow.defaultDraftProvider(usable, strong.provider) ?: return null
        return dev.pocketide.app.core.Workflow.Slot(pid, storedModel(pid).ifBlank { dev.pocketide.app.core.Providers.byId(pid).model })
    }

    /** The explore helper's model: the draft model when one was picked for workflows, else the main model (null). */
    fun explorerSlot(): dev.pocketide.app.core.Workflow.Slot? = if (fastMode) null else dev.pocketide.app.core.Workflow.decode(sp.getString("workflow.draft", "") ?: "")

    /** Build the parts of a long job at the same time on different providers, when the parts share no files. */
    var verifySteps: Boolean
        get() = sp.getBoolean("steps.verify", true)
        set(v) { sp.edit().putBoolean("steps.verify", v).apply() }

    var parallelParts: Boolean
        get() = sp.getBoolean("parts.parallel", true)
        set(v) { sp.edit().putBoolean("parts.parallel", v).apply() }

    /**
     * The models that can each take a part at once: the person's team (Settings → Parts) when they chose one, else three copies
     * of the main model (1.5.120; before, every provider with a saved key), else none, so the parts are built one after the other.
     */
    fun parallelLanes(): List<dev.pocketide.app.core.Workflow.Slot> {
        val usable = dev.pocketide.app.core.Providers.all.filter { p ->
            p.id != "demo" && (key(p.id).isNotBlank() || p.keyless) && dev.pocketide.app.core.Tools.providerSupports(p.id) &&
                (p.id != "custom" || dev.pocketide.app.core.Providers.customUrl(baseFor(p.id)) != null) &&   // a custom server only once its address is set
                !dev.pocketide.app.core.LocalModels.isLocal(p.id, baseFor(p.id))
        }.map { it.id }
        // the person's own team: each line may be any provider with a key, and the same model may be listed more than once
        val team = partsTeam.filter { it.provider in usable }
        if (team.isNotEmpty()) return team.map { if (it.model.isBlank()) it.copy(model = storedModel(it.provider).ifBlank { dev.pocketide.app.core.Providers.byId(it.provider).model }) else it }
        // 1.5.120: no team chosen means copies of the main model, never every provider that has a key: with many keys saved, that
        // sent parts to paid accounts and weak free tiers the person never picked (one person had 13 listed for 3 parts)
        val main = dev.pocketide.app.core.Parts.lanes(storedProvider, usable) { dev.pocketide.app.core.Providers.byId(it).kind.name }.firstOrNull()
            ?: return emptyList()
        return if (partsSameModel) List(3) { dev.pocketide.app.core.Workflow.Slot(main, storedModel(main).ifBlank { dev.pocketide.app.core.Providers.byId(main).model }) } else emptyList()
    }

    /** The models that build parts at the same time, chosen by the person (empty: every provider with a key). */
    var partsTeam: List<dev.pocketide.app.core.Workflow.Slot>
        get() = (sp.getString("parts.team", "") ?: "").lines().mapNotNull { dev.pocketide.app.core.Workflow.decode(it) }
        set(v) { sp.edit().putString("parts.team", v.joinToString("\n") { it.encode() }).apply() }
    /** Let the assistant pick which team model builds each part (otherwise they take turns). */
    var partsPick: Boolean
        get() = sp.getBoolean("parts.pick", true)
        set(v) { sp.edit().putBoolean("parts.pick", v).apply() }
    /** Build in parts for any list of 3 or more requirements, not only long lists. */
    var partsAlways: Boolean
        get() = sp.getBoolean("parts.always", false)
        set(v) { sp.edit().putBoolean("parts.always", v).apply() }
    /** Without a chosen team, run copies of the main model at the same time. */
    /** Add-ons the person switched off (their ids); the RuneScape game-cache add-on is on unless switched off. */
    var disabledAddOns: Set<String>
        get() = (sp.getString("addons.off", "") ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        set(v) { sp.edit().putString("addons.off", v.sorted().joinToString(",")).apply() }

    var partsSameModel: Boolean
        get() = sp.getBoolean("parts.same", true)
        set(v) { sp.edit().putBoolean("parts.same", v).apply() }

    /** Fast mode: no second-opinion model calls (advisor, reviewer, explore helper, summaries by a second model, cascade and critique). */
    var fastMode: Boolean
        get() = sp.getBoolean("fast.mode", false)
        set(v) { sp.edit().putBoolean("fast.mode", v).apply() }

    /** Phase routing: reading rounds of an agent run go to a cheaper model; edits, commands and the answer stay with the main model. */
    /** Pieces of small files are answered with the whole file (ReadPlan, 1.5.99). Not in Settings: the benchmark turns it off to compare. */
    var readWhole: Boolean
        get() = sp.getBoolean("read.whole", true)
        set(v) { sp.edit().putBoolean("read.whole", v).apply() }

    /** How hard thinking models think before each step: "" (the model's own), low, medium or high (ThinkEffort, 1.5.99). */
    var thinkEffort: String
        get() = sp.getString("think.effort", "") ?: ""
        set(v) { sp.edit().putString("think.effort", v).apply() }

    var phaseRouting: Boolean
        get() = sp.getBoolean("route.phase", false)
        set(v) { sp.edit().putBoolean("route.phase", v).apply() }

    /** Offer a retry or another provider when a reply shows no sign of life for a minute. */
    var slowOffer: Boolean
        get() = sp.getBoolean("slow.offer", true)
        set(v) { sp.edit().putBoolean("slow.offer", v).apply() }

    /** The cheap model for reading rounds: the draft model picked in the workflow settings, else a cheap provider that has a key. */
    fun routeSlot(): dev.pocketide.app.core.Workflow.Slot? =
        if (!phaseRouting) null else dev.pocketide.app.core.Workflow.decode(sp.getString("workflow.draft", "") ?: "") ?: draftSlot()

    /** The model that checks and fixes: the one picked, else the model chosen in Settings. */
    fun strongSlot(): dev.pocketide.app.core.Workflow.Slot? {
        dev.pocketide.app.core.Workflow.decode(sp.getString("workflow.strong", "") ?: "")?.let { return it }
        val pid = storedProvider
        if (pid == "demo") return null
        return dev.pocketide.app.core.Workflow.Slot(pid, storedModel(pid).ifBlank { dev.pocketide.app.core.Providers.byId(pid).model })
    }

    var base: String
        get() = sp.getString("base", "") ?: ""
        set(v) { sp.edit().putString("base", v).apply() }

    /** True: send all project files as context. False: only the open file, plus files the request names. */
    var everything: Boolean
        get() = sp.getBoolean("everything", false)
        set(v) { sp.edit().putBoolean("everything", v).apply() }

    var agent: Boolean
        get() = sp.getBoolean("agent", false)
        set(v) { sp.edit().putBoolean("agent", v).apply() }

    /** Choose agent mode for each request: questions stay in review, building or fixing applies changes. */
    var autoMode: Boolean
        get() = sp.getBoolean("auto.mode", true)
        set(v) { sp.edit().putBoolean("auto.mode", v).apply() }

    /** When a provider says the account is out of credit, send the request to the next provider that has a key. */
    var autoSwitchCredit: Boolean
        get() = sp.getBoolean("auto.credit", true)
        set(v) { sp.edit().putBoolean("auto.credit", v).apply() }

    fun markOutOfCredit(provider: String) { sp.edit().putLong("credit.out.$provider", System.currentTimeMillis()).apply() }
    fun clearOutOfCredit(provider: String) { sp.edit().remove("credit.out.$provider").apply() }
    fun outOfCreditRecently(provider: String): Boolean =
        System.currentTimeMillis() - sp.getLong("credit.out.$provider", 0L) < dev.pocketide.app.core.CreditSwitch.SKIP_MS

    var autorun: Boolean
        get() = sp.getBoolean("autorun", false)
        set(v) { sp.edit().putBoolean("autorun", v).apply() }

    /** Remote MCP servers the person connected (name, address, optional token). */
    var mcpServers: List<dev.pocketide.app.core.McpServer>
        get() = dev.pocketide.app.core.Mcp.decode(secret("mcp.servers"))
        set(v) { sp.edit().putString("mcp.servers", dev.pocketide.app.core.Vault.seal(dev.pocketide.app.core.Mcp.encode(v))).apply() }
    /** Server tools the person chose to always allow. */
    var mcpAlways: Set<String>
        get() = sp.getStringSet("mcp.always", emptySet()) ?: emptySet()
        set(v) { sp.edit().putStringSet("mcp.always", v).apply() }

    var openFile: String
        get() = sp.getString("open", "") ?: ""
        set(v) { sp.edit().putString("open", v).apply() }

    /** GPL route: which Linux base to install (see core/Distros). Changing it only matters before installing. */
    var linuxDistro: String
        get() = sp.getString("linux.distro", "alpine") ?: "alpine"
        set(v) { sp.edit().putString("linux.distro", v).apply() }

    /** GPL route: run commands in the Linux environment when it is installed. */
    var useLinux: Boolean
        get() = sp.getBoolean("useLinux", true)
        set(v) { sp.edit().putBoolean("useLinux", v).apply() }

    /** GPL route, opt-in: after the assistant writes a JavaScript, TypeScript or Python file, a language server in Linux checks it. */
    var lspOn: Boolean
        get() = sp.getBoolean("lsp.on", false)
        set(v) { sp.edit().putBoolean("lsp.on", v).apply() }

    fun key(provider: String): String = secret("key.$provider")

    /** A key, password or token: sealed at rest with the Android Keystore (Vault, 1.5.133). */
    private fun secret(name: String): String = dev.pocketide.app.core.Vault.open(sp.getString(name, "") ?: "")
    private fun putSecret(name: String, v: String) { sp.edit().putString(name, dev.pocketide.app.core.Vault.seal(v)).apply(); refreshMask() }

    /** Tells SecretMask which values to hide in the assistant's records (1.5.133). */
    fun refreshMask() {
        dev.pocketide.app.core.SecretMask.known = (sp.all.filter { (k, v) -> v is String && dev.pocketide.app.core.Vault.isSecretKey(k) && !dev.pocketide.app.core.Vault.isBundle(k) }
            .map { dev.pocketide.app.core.Vault.open(it.value as String) } + ghToken).filter { it.length >= 8 }.distinct()
    }
    /** The assistant may search the web and read pages. */
    var webResearch: Boolean
        get() = sp.getBoolean("web.research", true)
        set(v) { sp.edit().putBoolean("web.research", v).apply(); dev.pocketide.app.core.Tools.webEnabled = v }
    /** Optional Brave Search API key; without it the free DuckDuckGo page is used. */
    var braveKey: String
        get() = secret("web.brave")
        set(v) { putSecret("web.brave", dev.pocketide.app.core.KeyClean.clean(v)) }
    /** Sites the person chose to always let the assistant read. */
    var webAllowed: Set<String>
        get() = sp.getStringSet("web.allowed", emptySet()) ?: emptySet()
        set(v) { sp.edit().putStringSet("web.allowed", v).apply() }

    /** Anthropic workspace ID, only needed when the key is not scoped to one workspace. */
    var anthropicWorkspace: String
        get() = sp.getString("anthropic.workspace", "") ?: ""
        set(v) { val c = v.trim(); sp.edit().putString("anthropic.workspace", c).apply(); ModelClient.workspaceId = c }

    fun setKey(provider: String, v: String) { putSecret("key.$provider", dev.pocketide.app.core.KeyClean.clean(v)) }

    private fun storedModel(provider: String): String = sp.getString("model.$provider", "") ?: ""
    fun model(provider: String): String = override?.takeIf { it.provider == provider && it.model.isNotBlank() }?.model ?: storedModel(provider)
    fun setModel(provider: String, v: String) { sp.edit().putString("model.$provider", v.trim()).apply() }

    /** Token totals per day and model (JSON, see core/UsageLog) and the prices the user entered (see core/Prices). */
    var usageJson: String
        get() = sp.getString("usage.log", "") ?: ""
        set(v) { sp.edit().putString("usage.log", v).apply() }
    var pricesJson: String
        get() = sp.getString("usage.prices", "") ?: ""
        set(v) { sp.edit().putString("usage.prices", v).apply() }

    /** A smaller, faster model used only for inline suggestions while typing; empty = the chat model. Stored per provider. */
    fun completionModel(provider: String): String = sp.getString("completion.model.$provider", "") ?: ""
    fun setCompletionModel(provider: String, v: String) { sp.edit().putString("completion.model.$provider", v.trim()).apply() }

    /** Limit for one request (core/RunBudget): dollars and tokens; 0 = off. The assistant pauses and asks before going past it. */
    var runUsd: Double
        get() = sp.getFloat("run.usd", dev.pocketide.app.core.RunBudget.DEFAULT_USD.toFloat()).toDouble()
        set(v) { sp.edit().putFloat("run.usd", v.toFloat()).apply() }
    var runTokens: Long
        get() = sp.getLong("run.tokens", dev.pocketide.app.core.RunBudget.DEFAULT_TOKENS)
        set(v) { sp.edit().putLong("run.tokens", v).apply() }

    /** Spending limits per provider id (see core/SpendBudget). */
    var budgets: Map<String, dev.pocketide.app.core.SpendBudget.Limit>
        get() = dev.pocketide.app.core.SpendBudget.decode(sp.getString("usage.budgets", "") ?: "")
        set(v) { sp.edit().putString("usage.budgets", dev.pocketide.app.core.SpendBudget.encode(v)).apply() }

    /** Warning lines when the current provider is near or over its limits (30 days of money, today's tokens). */
    fun budgetWarnings(providerId: String, providerName: String): List<String> {
        val limit = budgets[providerId] ?: return emptyList()
        if (limit.isOff) return emptyList()
        val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US); val cal = java.util.Calendar.getInstance()
        val days = (0 until 30).map { val x = f.format(cal.time); cal.add(java.util.Calendar.DAY_OF_YEAR, -1); x }
        val rows = dev.pocketide.app.core.UsageLog.parse(usageJson)
        return dev.pocketide.app.core.SpendBudget.warnings(providerName, providerId, limit, dev.pocketide.app.core.UsageLog.forDays(rows, days), dev.pocketide.app.core.UsageLog.forDays(rows, days.take(1)), dev.pocketide.app.core.Prices.parse(pricesJson))
    }

    @Synchronized fun addUsage(provider: String, model: String, u: dev.pocketide.app.core.Usage) {
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        usageJson = dev.pocketide.app.core.UsageLog.write(dev.pocketide.app.core.UsageLog.add(dev.pocketide.app.core.UsageLog.parse(usageJson), day, dev.pocketide.app.core.Prices.key(provider, model), u))
    }

    fun priceFor(provider: String, model: String): dev.pocketide.app.core.Price? = dev.pocketide.app.core.Prices.parse(pricesJson)[dev.pocketide.app.core.Prices.key(provider, model)] ?: dev.pocketide.app.core.KnownPrices.find(provider, model)

    /** How long the reusable start of a request is kept: "5m" or "1h" (Anthropic). */
    var cacheTtl: String
        get() = if (sp.getString("cache.ttl", "5m") == "1h") "1h" else "5m"
        set(v) { sp.edit().putString("cache.ttl", if (v == "1h") "1h" else "5m").apply(); dev.pocketide.app.core.PromptCache.ttl = cacheTtl }

    init {
        KeystoreCipher.install()
        sealPlainSecrets()
        try { refreshMask() } catch (t: Throwable) { }
        dev.pocketide.app.core.PromptCache.ttl = cacheTtl
        dev.pocketide.app.core.ModelCaps.load(sp.getString("modelcaps", "") ?: "")
        dev.pocketide.app.core.ModelCaps.onChange = { sp.edit().putString("modelcaps", it).apply() }
        // what each provider says about its models, used for model recommendations
        dev.pocketide.app.core.ModelFacts.load(sp.getString("modelfacts", null))
        dev.pocketide.app.core.ModelFacts.onChange = { sp.edit().putString("modelfacts", it).apply() }
        // how models did on this phone, for suggestions (1.5.128)
        dev.pocketide.app.core.ModelTrack.load(sp.getString("modeltrack", null))
        dev.pocketide.app.core.ModelTrack.onChange = { sp.edit().putString("modeltrack", it).apply() }
    }

    /** Seals keys and tokens that are still stored as plain text (from before 1.5.133), once per start; cheap when none are left. */
    private fun sealPlainSecrets() {
        if (dev.pocketide.app.core.Vault.cipher == null) return
        val e = sp.edit(); var n = 0
        for ((k, v) in sp.all) if (v is String && v.isNotEmpty() && dev.pocketide.app.core.Vault.isSecretKey(k) && !dev.pocketide.app.core.Vault.isSealed(v)) {
            val sealed = dev.pocketide.app.core.Vault.seal(v)
            if (sealed != v) { e.putString(k, sealed); n++ }
        }
        if (n > 0) e.apply()
        try {
            for (name in Projects.list(app)) {
                val t = Projects.gitConfig(app, name).getProperty("token", "")
                if (t.isNotEmpty() && !dev.pocketide.app.core.Vault.isSealed(t)) Projects.setGitValue(app, "token", dev.pocketide.app.core.Vault.seal(t), name)
                dev.pocketide.app.core.Secrets(java.io.File(Projects.metaDir(app, name), "secrets.json")).sealPlain()
            }
        } catch (t: Throwable) { dev.pocketide.app.core.AppLog.add("vault", "tokens not sealed: " + t.javaClass.simpleName) }
    }

    /** Reuse the start of each request where the provider supports it (cheaper repeated requests). */
    var promptCache: Boolean
        get() = sp.getBoolean("prompt.cache", true)
        set(v) { sp.edit().putBoolean("prompt.cache", v).apply() }
    /** OpenRouter: models to try, in order, when the main one fails (comma separated). */
    var openRouterFallbacks: String
        get() = sp.getString("openrouter.fallbacks", "") ?: ""
        set(v) { sp.edit().putString("openrouter.fallbacks", v.trim()).apply() }
    /** OpenRouter: which providers serve the model (cheapest, fastest, no data collection). */
    var openRouterRoute: dev.pocketide.app.core.OpenRouter.Route
        get() = try { dev.pocketide.app.core.OpenRouter.Route.valueOf(sp.getString("openrouter.route", "DEFAULT") ?: "DEFAULT") } catch (e: Exception) { dev.pocketide.app.core.OpenRouter.Route.DEFAULT }
        set(v) { sp.edit().putString("openrouter.route", v.name).apply() }
    /** Extra request fields for OpenRouter (fallbacks, routing); empty for other providers. */
    fun openRouterExtra(providerId: String, model: String, tools: Boolean): Map<String, Any?> =
        if (providerId != dev.pocketide.app.core.OpenRouter.ID) emptyMap()
        else dev.pocketide.app.core.OpenRouter.routing(model, tools, dev.pocketide.app.core.OpenRouter.parseFallbacks(openRouterFallbacks, model), openRouterRoute)

    /** Smart routing: questions and small edits use the cheap profile, builds and multi-file work the strong one (profile names). */
    var routeOn: Boolean
        get() = sp.getBoolean("route.on", false)
        set(v) { sp.edit().putBoolean("route.on", v).apply() }
    var routeCheap: String
        get() = sp.getString("route.cheap", "") ?: ""
        set(v) { sp.edit().putString("route.cheap", v).apply() }
    var routeStrong: String
        get() = sp.getString("route.strong", "") ?: ""
        set(v) { sp.edit().putString("route.strong", v).apply() }

    /** The GitHub-style repository page for the Code tab and a repositories list (on by default; can be turned off in Settings). */
    var newLayout: Boolean
        get() = sp.getBoolean("layout.new", true)
        set(v) { sp.edit().putBoolean("layout.new", v).apply() }

    /** Cloudflare deploys (CfDeploy, 1.5.112): an API token with Workers Scripts: Edit, and the account id. */
    var cfDeployToken: String
        get() = secret("cf.deploy.token")
        set(v) { putSecret("cf.deploy.token", dev.pocketide.app.core.KeyClean.clean(v)) }
    var cfAccount: String
        get() = (sp.getString("cf.account", "") ?: "").ifEmpty { Regex("""/accounts/([0-9a-f]{32})/""").find(baseFor("cloudflare"))?.groupValues?.get(1).orEmpty() }
        set(v) { sp.edit().putString("cf.account", v.trim()).apply() }

    /** The remote machine of a project (RemoteDev, 1.5.112): its SSH address and folder, a password when the server takes no key, and whether the assistant's commands run there. */
    fun remoteAddress(project: String): String = sp.getString("remote.addr.$project", "") ?: ""
    fun setRemoteAddress(project: String, v: String) { sp.edit().putString("remote.addr.$project", v.trim()).apply() }
    fun remoteDir(project: String): String = sp.getString("remote.dir.$project", "") ?: ""
    fun setRemoteDir(project: String, v: String) { sp.edit().putString("remote.dir.$project", v.trim()).apply() }
    fun remotePassword(project: String): String = secret("remote.pw.$project")
    fun setRemotePassword(project: String, v: String) { putSecret("remote.pw.$project", v) }
    fun remoteRun(project: String): Boolean = sp.getBoolean("remote.run.$project", false)
    fun setRemoteRun(project: String, v: Boolean) { sp.edit().putBoolean("remote.run.$project", v).apply() }

    /** The strong model the person picked in Settings → Workflow, without the fallback to the main model (Escalate, 1.5.125). */
    fun pickedStrongSlot(): dev.pocketide.app.core.Workflow.Slot? = dev.pocketide.app.core.Workflow.decode(sp.getString("workflow.strong", "") ?: "")

    /** True when [provider] can be called: it has a key, or needs none (and is not the demo). */
    fun canCall(provider: String): Boolean = provider != "demo" && (key(provider).isNotBlank() || dev.pocketide.app.core.Providers.byId(provider).keyless)

    /** When a request ends unfinished, carry on with the strong model at once instead of offering a button (Escalate, 1.5.125). */
    var autoEscalate: Boolean
        get() = sp.getBoolean("escalate.auto", false)
        set(v) { sp.edit().putBoolean("escalate.auto", v).apply() }

    /** When a request's first attempt ends unfinished, undo it and try once more from the start (SecondTry, 1.5.112). */
    var secondTry: Boolean
        get() = sp.getBoolean("second.try", false)
        set(v) { sp.edit().putBoolean("second.try", v).apply() }

    /** During the page check, tap a few harmless buttons to see whether the page breaks. */
    var pageTap: Boolean
        get() = sp.getBoolean("page.tap", true)
        set(v) { sp.edit().putBoolean("page.tap", v).apply() }

    /** After the assistant changes code (Agent on), run the project's tests or checks and let it fix failures. */
    var verify: Boolean
        get() = sp.getBoolean("verify", true)
        set(v) { sp.edit().putBoolean("verify", v).apply() }

    /** How many rounds of tool calls one request may use (see Rounds). */
    var maxRounds: Int
        get() = sp.getInt("max.rounds", dev.pocketide.app.core.Rounds.DEFAULT)
        set(v) { sp.edit().putInt("max.rounds", v).apply() }

    /** Saved model profiles (JSON, see core/Profiles). */
    var profilesJson: String
        get() = sp.getString("profiles", "") ?: ""
        set(v) { sp.edit().putString("profiles", v).putLong("profiles.stamp", System.currentTimeMillis()).apply() }

    /** Supabase sync (the user's own project). The password is never stored, only a refresh token. */
    var supaUrl: String
        get() = sp.getString("supa.url", "") ?: ""
        set(v) { sp.edit().putString("supa.url", v.trim()).apply() }
    var supaAnon: String
        get() = sp.getString("supa.anon", "") ?: ""
        set(v) { sp.edit().putString("supa.anon", v.trim()).apply() }
    var supaEmail: String
        get() = sp.getString("supa.email", "") ?: ""
        set(v) { sp.edit().putString("supa.email", v).apply() }
    var supaRefresh: String
        get() = secret("supa.refresh")
        set(v) { putSecret("supa.refresh", v) }

    /** When the saved model profiles last changed, so sync can tell which copy is newer. */
    var profilesStamp: Long
        get() = sp.getLong("profiles.stamp", 0L)
        set(v) { sp.edit().putLong("profiles.stamp", v).apply() }

    /** A provider whose per-minute limit is small (learned from an error, or Groq's free tier): send smaller requests. */
    /** A reply-length cap learned from an output-tokens-per-minute error; 0 means none. */
    // Only a provider's per-minute output limit (Groq's OTPM) caps a reply. Earlier versions also halved the limit after every
    // cut-off reply and kept it ("outcap."), which made long replies get cut sooner and sooner; those stored values are ignored.
    fun outputCap(provider: String, model: String): Int {
        val otpm = sp.getInt("otpmcap.$provider.$model", 0)
        // a small context window (a self-hosted 32K server) must keep room for the question: the reply gets at most a quarter of it
        val ctx = dev.pocketide.app.core.ModelFacts.get(provider, model)?.context
        val fit = if (ctx != null && ctx > 0) dev.pocketide.app.core.ContextFit.replyTokens(ctx, dev.pocketide.app.core.OutputLimit.maxTokens(model))
            else dev.pocketide.app.core.OutputLimit.whenUnknown(provider, model)
        return listOf(otpm, fit).filter { it > 0 }.minOrNull() ?: 0
    }
    fun setOutputCap(provider: String, model: String, v: Int) { sp.edit().putInt("otpmcap.$provider.$model", v).apply() }

    fun tight(provider: String): Boolean = sp.getBoolean("tight.$provider", provider == "groq")
    fun setTight(provider: String, v: Boolean) { sp.edit().putBoolean("tight.$provider", v).apply() }

    /** Server address for a local or custom provider. The custom server keeps using the older shared setting. */
    fun baseFor(provider: String): String {
        val own = sp.getString("base.$provider", null)
        if (own != null) return own
        return if (provider == "custom") base else dev.pocketide.app.core.Providers.byId(provider).defaultBase
    }
    fun setBaseFor(provider: String, v: String) {
        sp.edit().putString("base.$provider", v.trim()).apply()
        if (provider == "custom") base = v.trim()
    }

    /** The first-run welcome has been shown. */
    var welcomed: Boolean
        get() = sp.getBoolean("welcomed", false)
        set(v) { sp.edit().putBoolean("welcomed", v).apply() }

    // GitHub sync, per project since v0.4 (W1): stored in the open project's metaDir, never in the project.
    // The token is kept in the app's private storage, like the API keys.
    var ghRepo: String
        get() = Projects.gitConfig(app).getProperty("repo", "")
        set(v) { Projects.setGitValue(app, "repo", v.trim()) }
    var ghBranch: String
        get() = Projects.gitConfig(app).getProperty("branch", "main").ifEmpty { "main" }
        set(v) { Projects.setGitValue(app, "branch", v.trim()) }
    var ghToken: String
        get() = dev.pocketide.app.core.Vault.open(Projects.gitConfig(app).getProperty("token", ""))
        set(v) { Projects.setGitValue(app, "token", dev.pocketide.app.core.Vault.seal(v.trim())); refreshMask() }
    /** The folder the assistant may write in for the open project; empty means the whole project. */
    var folderScope: String
        get() = Projects.gitConfig(app).getProperty("scope", "")
        set(v) { Projects.setGitValue(app, "scope", v.trim().trim('/')) }

    // v0.4 W1: editor preferences
    /** Editor text size in sp. */
    var editorFontSize: Float
        get() = sp.getFloat("editor.font", 13f)
        set(v) { sp.edit().putFloat("editor.font", v.coerceIn(8f, 28f)).apply() }
    /** "dark" or "light" (editor only; the rest of the app stays dark). */
    /** The whole app in light colours (Ui.light, 1.5.113). */
    var appLight: Boolean
        get() = sp.getBoolean("app.light", false)
        set(v) { sp.edit().putBoolean("app.light", v).apply() }

    var editorTheme: String
        get() = sp.getString("editor.theme", "dark") ?: "dark"
        set(v) { sp.edit().putString("editor.theme", v).apply() }
    /** The embeddings model for search by meaning, "provider:model" (SemanticIndex, 1.5.141); empty = keyword search only. */
    var embedModel: String
        get() = sp.getString("index.embed", "") ?: ""
        set(v) { sp.edit().putString("index.embed", v.trim()).apply() }
    /**
     * Before finishing a request with several asks, check the work ask by ask (AskLedger, 1.5.138). Off by default since 1.5.145:
     * on Kimi K2.6 and Claude Haiku 5.5 it passed no more tasks than without it, led to no edit in 38 runs, and cost about a fifth
     * more requests (Copilot's ANALYSIS-1.5.139-ASKS rule B). Kept as a tick box.
     */
    var askCheck: Boolean
        // a new key (1.5.146): Save stores every tick box, so "ask.check" holds true for anyone who saved Settings while it was the
        // default; nobody could have chosen on, so the old value is left behind and everyone starts from off
        get() = sp.getBoolean("ask.check2", false)
        set(v) { sp.edit().putBoolean("ask.check2", v).apply() }
    /** Problem reports: name the cause before the first change, and question changed data files (CauseGate, 1.5.147; off until measured). */
    var causeFirst: Boolean
        get() = sp.getBoolean("cause.first", false)
        set(v) { sp.edit().putBoolean("cause.first", v).apply() }
    /** Code that reads outside input (CSV, web pages): try it on shapes the example did not show before finishing (InputVariety, 1.5.154; off until measured). */
    var varietyProbe: Boolean
        get() = sp.getBoolean("variety.probe", false)
        set(v) { sp.edit().putBoolean("variety.probe", v).apply() }
    /** Keep to what was asked: report what the project lacks instead of adding it, and question unasked edits (ScopeGate, 1.5.156; off until measured). */
    var scopeCheck: Boolean
        get() = sp.getBoolean("scope.check", false)
        set(v) { sp.edit().putBoolean("scope.check", v).apply() }
    /**
     * The explore helper (a second model call that reads the project for the assistant). Off by default since 1.5.162: in the planned
     * pair (Kimi K2.6, realproject, 65 runs a side) no helper scored 47/65 against 43/65 with it, on 1.78M fresh tokens against 4.15M.
     */
    var exploreHelper: Boolean
        get() = sp.getBoolean("explore.helper", false)
        set(v) { sp.edit().putBoolean("explore.helper", v).apply() }
    /** Check claims that something disappeared before building it, and let the assistant report instead (PremiseCheck, 1.5.164; off until measured). */
    var premiseCheck: Boolean
        get() = sp.getBoolean("premise.check", false)
        set(v) { sp.edit().putBoolean("premise.check", v).apply() }
    /** A second look in a fresh context before finishing: request + diff + final message only (FreshVerify, 1.5.161; off until measured). */
    var verifyFresh: Boolean
        get() = sp.getBoolean("verify.fresh", false)
        set(v) { sp.edit().putBoolean("verify.fresh", v).apply() }
    /** Spoken updates while the assistant works ("Reading", "Running"): TalkBack only; off silences them (1.5.135). */
    var workAnnounce: Boolean
        get() = sp.getBoolean("a11y.work_announce", true)
        set(v) { sp.edit().putBoolean("a11y.work_announce", v).apply() }
    /** 1.5.185: notes in the chat that suggest another model (a larger one, a recommended one, the advisor). Off hides them all. */
    var modelTips: Boolean
        get() = sp.getBoolean("chat.model_tips", true)
        set(v) { sp.edit().putBoolean("chat.model_tips", v).apply() }
    /** 1.5.194: the Python helper for Gemini Nano (core/PyHelper): a Python note, edits re-indented, outlines, where a test failed. */
    var pythonHelper: Boolean
        get() = sp.getBoolean("hybrid.python_helper", false)
        set(v) { sp.edit().putBoolean("hybrid.python_helper", v).apply() }
    var editorWrap: Boolean
        get() = sp.getBoolean("editor.wrap", true)
        set(v) { sp.edit().putBoolean("editor.wrap", v).apply() }
    /** Ask the AI for a suggestion automatically after a short pause in typing. */
    var autoComplete: Boolean
        get() = sp.getBoolean("editor.autocomplete", false)
        set(v) { sp.edit().putBoolean("editor.autocomplete", v).apply() }
    /**
     * Next-edit suggestions from the suggestion model (core/NextEdit, 1.5.125). Until the person chooses, this is on except on a
     * metered connection, checked at each pause, so mobile data never pays for a suggestion nobody asked for.
     */
    fun nextEditOn(metered: Boolean): Boolean =
        dev.pocketide.app.core.NextEdit.enabled(if (sp.contains("editor.nextedit")) sp.getBoolean("editor.nextedit", true) else null, metered)
    fun setNextEdit(on: Boolean) { sp.edit().putBoolean("editor.nextedit", on).apply() }

    // v0.4 W2: native tool calling. Auto (true) uses tools on providers that support them; Off keeps the text protocol.
    var nativeTools: Boolean
        get() = sp.getBoolean("tools.native", true)
        set(v) { sp.edit().putBoolean("tools.native", v).apply() }
    /** Ask for a plan first when a request is big (not for local models). */
    /** The chat box mode: "ask" (reads and answers, never changes anything), "plan" (a plan to approve first) or "agent" (builds directly). */
    var mode: String
        get() = (sp.getString("chat.mode", "agent") ?: "agent").let { if (it == "ask" || it == "plan") it else "agent" }
        set(v) { sp.edit().putString("chat.mode", if (v == "ask" || v == "plan") v else "agent").apply() }
    val askOnly: Boolean get() = mode == "ask"
    val planMode: Boolean get() = mode == "plan"

    var planFirst: Boolean
        get() = sp.getBoolean("plan.first", true)
        set(v) { sp.edit().putBoolean("plan.first", v).apply() }
    /** For big requests: list the requirements first, approve them, then check the result against the request. */
    var requirementsFirst: Boolean
        get() = sp.getBoolean("requirements.first", true)
        set(v) { sp.edit().putBoolean("requirements.first", v).apply() }
    /** For long requirements lists: build in parts (each its own focused pass) and check that they fit together. */
    var splitParts: Boolean
        get() = sp.getBoolean("split.parts", true)
        set(v) { sp.edit().putBoolean("split.parts", v).apply() }
    /** A provider + model that refused tool calls once; it then uses the text protocol. */
    fun toolsRejected(provider: String, model: String): Boolean =
        sp.getBoolean("tools.no.$provider.$model", false) && dev.pocketide.app.core.Rounds.rejectionFresh(sp.getLong("tools.no.at.$provider.$model", 0L), System.currentTimeMillis())
    fun setToolsRejected(provider: String, model: String) { sp.edit().putBoolean("tools.no.$provider.$model", true).putLong("tools.no.at.$provider.$model", System.currentTimeMillis()).apply() }
}
