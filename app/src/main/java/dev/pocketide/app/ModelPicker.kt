package dev.pocketide.app

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import dev.pocketide.app.core.ModelPick
import dev.pocketide.app.core.Provider
import dev.pocketide.app.core.ProviderKind
import dev.pocketide.app.core.Providers

/** Asks a provider which models it offers, and lets the user search and choose one, so a model name never has to be typed from memory. */
object ModelPicker {
    /**
     * One tiny chat request with [model], the real test of a key and a model. [done] runs on the main thread with
     * whether chat works (429 counts: the key works, the free limit is used up) and the message for the person.
     */
    fun ping(p: Provider, key: String, base: String, model: String, done: (Boolean, String) -> Unit) {
        val url = if (p.custom) Providers.customUrl(base.ifBlank { p.defaultBase }) else p.url
        if (url == null || url.contains("ACCOUNT_ID")) { done(false, if (p.id == "cloudflare") "Enter the Cloudflare account ID first." else "Enter the server address first."); return }
        val anthropic = p.kind == ProviderKind.ANTHROPIC
        val main = Handler(Looper.getMainLooper())
        fun post(withMax: Boolean): Pair<Int, String> {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            try {
                c.connectTimeout = 10000
                c.readTimeout = 30000
                c.setRequestProperty("User-Agent", "PocketIDE")
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                if (key.isNotBlank()) {
                    if (anthropic) { c.setRequestProperty("x-api-key", key); c.setRequestProperty("anthropic-version", "2023-06-01") }
                    else c.setRequestProperty("Authorization", "Bearer $key")
                }
                val body = linkedMapOf<String, Any?>("model" to model, "messages" to listOf(linkedMapOf("role" to "user", "content" to "Reply with OK.")))
                if (anthropic || withMax) body["max_tokens"] = 16
                c.outputStream.use { it.write(dev.pocketide.app.core.MiniJson.write(body).toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                return code to text
            } finally { c.disconnect() }
        }
        Thread {
            val r: Pair<Boolean, String> = try {
                var (code, text) = post(true)
                // some models (OpenAI's newer ones) refuse max_tokens; try once without it
                if (code == 400 && text.contains("max_tokens")) { val again = post(false); code = again.first; text = again.second }
                dev.pocketide.app.core.ConnectionCheck.works(code) to dev.pocketide.app.core.ConnectionCheck.chatVerdict(p, code, text, model, key)
            } catch (e: Exception) {
                false to dev.pocketide.app.core.ConnectionCheck.chatVerdict(p, -1, e.message ?: e.javaClass.simpleName, model, key)
            }
            main.post { done(r.first, r.second) }
        }.start()
    }

    /** GET <base>/models in the background; [done] runs on the main thread with the list, or null and a message. */
    fun fetch(p: Provider, key: String, base: String, done: (List<String>?, String) -> Unit) {
        val cfAccount = Providers.cloudflareAccount(base)
        val url = if (p.id == "cloudflare") (if (cfAccount.isEmpty()) null else "https://api.cloudflare.com/client/v4/accounts/$cfAccount/ai/models/search?per_page=100")
            else (if (p.custom) Providers.modelsUrl(base.ifBlank { p.defaultBase }) else Providers.modelsUrl(p.url.removeSuffix("/chat/completions").removeSuffix("/messages")))?.let { if (p.kind == ProviderKind.ANTHROPIC) "$it?limit=1000" else it }
        if (url == null) { done(null, "Enter a server address first."); return }
        val main = Handler(Looper.getMainLooper())
        Thread {
            val r: Pair<List<String>?, String> = try {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 15000
                c.setRequestProperty("User-Agent", "PocketIDE")
                if (key.isNotBlank()) {
                    if (p.kind == ProviderKind.ANTHROPIC) { c.setRequestProperty("x-api-key", key); c.setRequestProperty("anthropic-version", "2023-06-01"); if (ModelClient.workspaceId.isNotBlank()) c.setRequestProperty("anthropic-workspace-id", ModelClient.workspaceId) }
                    else c.setRequestProperty("Authorization", "Bearer $key")
                }
                val code = c.responseCode
                val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                c.disconnect()
                if (code !in 200..299) {
                    val why = dev.pocketide.app.core.SseDecoder.errorMessage(body)?.take(200)
                    null to "The server answered $code" + (if (why != null) ": $why" else ".") +
                        (if (p.id == "ollamacloud" && code == 403) " Ollama Cloud refused this key. Create a new key in your Ollama account settings, paste it, then tap Save and Test connection again." else if (code == 401 || code == 403) " Check the API key." else if (code == 400 && p.kind == ProviderKind.ANTHROPIC) " The model list may be unavailable for this key; Save and send a chat message to test it for real." else "")
                }
                else {
                    if (p.kind == ProviderKind.ANTHROPIC) dev.pocketide.app.core.ModelCaps.merge(dev.pocketide.app.core.ModelCaps.parse(body))
                    else dev.pocketide.app.core.ModelPurpose.remember(dev.pocketide.app.core.ModelPurpose.parseMeta(body))  // tool support, image input, price
                    dev.pocketide.app.core.ModelFacts.remember(dev.pocketide.app.core.ModelFacts.parse(p.id, body))  // what the provider says each model can do
                    (if (p.id == "cloudflare") Providers.parseCloudflareModels(body) else Providers.parseModels(body)) to "ok"
                }
            } catch (e: Exception) {
                null to "Could not connect: ${e.message ?: e.javaClass.simpleName}. Check that the server is running and the address is right."
            }
            main.post { done(r.first, r.second) }
        }.start()
    }

    /** A searchable list. Models that cannot chat (speech, safety, embeddings) are hidden unless "Show all" is ticked via the button. */
    /**
     * The model list, grouped by what each model is designed for (coding, reasoning, images, general, small and fast), with
     * tags for tool calling, image input and free use when the provider lists them. Models with tool calling come first in
     * each group, because they get the live plan and the checks after every write.
     */
    fun show(ctx: Context, title: String, all: List<String>, current: String, hint: String = "", onPick: (String) -> Unit) {
        var showAll = false
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 8), Ui.dp(ctx, 16), 0) }
        if (hint.isNotEmpty()) box.addView(Ui.text(ctx, hint, 13f, Ui.MUTED, false), Ui.lp(Ui.MATCH, Ui.WRAP))
        val search = EditText(ctx).apply { this.hint = "Search part of a name, e.g. llama 70 or kimi"; setSingleLine(true) }
        box.addView(search, Ui.lp(Ui.MATCH, Ui.WRAP))
        // filter chips: tap to keep only models of that kind; several chips must all hold
        val chosen = LinkedHashSet<dev.pocketide.app.core.ModelFilter.Kind>()
        val chipRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val chipScroll = android.widget.HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) }
        box.addView(chipScroll, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 4); it.bottomMargin = Ui.dp(ctx, 4) })
        var rebuildLater: () -> Unit = {}
        // rows: a group heading (id null) or a model
        val rows = ArrayList<Pair<String, String?>>()
        val adapter = object : ArrayAdapter<String>(ctx, android.R.layout.simple_list_item_1, ArrayList<String>()) {
            override fun getCount() = rows.size
            override fun getItem(position: Int) = rows[position].first
            override fun isEnabled(position: Int) = rows[position].second != null
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getView(position, convertView, parent) as android.widget.TextView
                val heading = rows[position].second == null
                v.text = rows[position].first
                v.setTextColor(if (heading) Ui.ACCENT else Ui.TEXT)
                v.textSize = if (heading) 13f else 15f
                v.setTypeface(null, if (heading) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                v.minHeight = if (heading) Ui.dp(ctx, 32) else Ui.dp(ctx, 44)
                return v
            }
        }
        val lv = ListView(ctx).apply { this.adapter = adapter }
        box.addView(lv, LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(ctx, 360)))
        val count = Ui.text(ctx, "", 12f, Ui.MUTED, false)
        box.addView(count, Ui.lp(Ui.MATCH, Ui.WRAP))
        fun rebuild() {
            val meta = dev.pocketide.app.core.ModelPurpose.known()
            val query = dev.pocketide.app.core.ModelFilter.parse(search.text.toString(), chosen)
            val words = query.terms.joinToString(" ")
            val base = all.filter { dev.pocketide.app.core.ModelFilter.nameMatches(it, query.terms) }
            val groups = dev.pocketide.app.core.ModelPurpose.grouped(base, meta, showAll || words.isNotEmpty())
            val factsById = dev.pocketide.app.core.ModelFacts.all().filter { it.id in base }.associateBy { it.id }
            rows.clear()
            var n = 0
            // the provider's own facts, scored for building apps: the top three come first
            if (query.isEmpty) {
                val ids = all.toSet()
                val facts = dev.pocketide.app.core.ModelFacts.all().filter { it.id in ids }
                val recs = dev.pocketide.app.core.ModelRecommend.best(dev.pocketide.app.core.ModelRecommend.Task.BUILD, facts, facts.map { it.provider }.toSet())
                    .filter { it.score > 0 }
                if (recs.isNotEmpty()) {
                    rows.add("Recommended for building apps (${recs.size})" to null)
                    for (r in recs) rows.add("★ " + r.facts.id + "   · " + r.why.substringBefore(" · \"").take(70) to r.facts.id)
                }
            }
            for ((g, list) in groups) {
                val refused = dev.pocketide.app.core.ModelFacts.all().filter { it.unavailable != null }.associate { it.id to it.unavailable!! }
                val items = list.filter { dev.pocketide.app.core.ModelFilter.kindMatches(it, query.kinds, refused[it.id], factsById[it.id]) }.sortedBy { if (it.id in refused) 1 else 0 }
                if (items.isEmpty()) continue
                rows.add(g.label + " (" + items.size + ")" to null)
                for (i in items.take(300)) {
                    val tags = listOfNotNull(refused[i.id]?.let { "✕ $it" }, dev.pocketide.app.core.ModelPurpose.tags(i).ifEmpty { null }).joinToString(" · ")
                    rows.add((if (i.id == current) "✓ " else "") + i.id + (if (tags.isEmpty()) "" else "   · $tags") to i.id)
                    n++
                }
            }
            adapter.notifyDataSetChanged()
            val hidden = all.size - ModelPick.chatModels(all).size
            count.text = "$n of ${all.size} models" + (if (!showAll && words.isEmpty() && hidden > 0) " ($hidden not for chat hidden)" else "") +
                (if (query.kinds.isEmpty()) "" else ", filtered: " + query.kinds.joinToString(", ") { it.label.lowercase() }) +
                (if (meta.isEmpty()) "" else ". Tools = tool calling, which gets the live plan and checks.")
        }
        rebuildLater = { rebuild() }
        for (k in dev.pocketide.app.core.ModelFilter.Kind.values()) {
            lateinit var chip: android.widget.TextView
            chip = Ui.chip(ctx, k.label) {
                if (!chosen.remove(k)) chosen.add(k)
                chip.text = (if (k in chosen) "✓ " else "") + k.label
                chip.setTextColor(if (k in chosen) Ui.ACCENT else Ui.MUTED)
                rebuildLater()
            }
            chipRow.addView(chip, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).also { it.rightMargin = Ui.dp(ctx, 6) })
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { rebuild() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        rebuild()
        val dlg = AlertDialog.Builder(ctx).setTitle(title).setView(box)
            .setNeutralButton("Show all") { _, _ -> }
            .setNegativeButton("Close", null).create()
        lv.setOnItemClickListener { _, _, i, _ -> val m = rows.getOrNull(i)?.second; if (m != null) { dlg.dismiss(); onPick(m) } }
        // long press: what the provider says about the model
        lv.setOnItemLongClickListener { _, _, i, _ ->
            val m = rows.getOrNull(i)?.second ?: return@setOnItemLongClickListener false
            val f = dev.pocketide.app.core.ModelFacts.all().firstOrNull { it.id == m }
            val g = dev.pocketide.app.core.ModelPurpose.group(m)
            AlertDialog.Builder(ctx).setTitle(m)
                .setMessage(g.label + "\n\n" + (f?.let { dev.pocketide.app.core.ModelFacts.summary(it).replace(" · ", "\n") } ?: "The provider did not describe this model."))
                .setPositiveButton("Use it") { _, _ -> dlg.dismiss(); onPick(m) }
                .setNegativeButton("Back", null).show()
            true
        }
        dlg.show()
        // keep the dialog open when toggling "Show all"
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            showAll = !showAll
            (it as android.widget.Button).text = if (showAll) "Chat only" else "Show all"
            rebuild()
        }
        if (current.isNotEmpty()) { val i = rows.indexOfFirst { it.second == current }; if (i >= 0) lv.setSelection(maxOf(0, i - 1)) }
    }

    /**
     * One search across every provider that has a key (or needs none): type part of a model name, or pick kinds such as
     * Tools, Free or Coding, whatever the provider. Lists arrive one provider at a time and the results fill in as they do.
     * A model the app has seen refuse tool calls is tagged and left out of the Tools filter.
     */
    fun searchAll(ctx: Context, settings: AppSettings, onPick: (Provider, String) -> Unit) {
        val active = Providers.all.filter { it.id != "demo" && (it.keyless || settings.key(it.id).isNotBlank()) }
        if (active.isEmpty()) { AlertDialog.Builder(ctx).setTitle("No providers set up").setMessage("Add an API key for a provider in Settings first.").setPositiveButton("OK", null).show(); return }
        val entries = ArrayList<Pair<Provider, String>>()
        val failed = ArrayList<String>()
        var answered = 0
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 8), Ui.dp(ctx, 16), 0) }
        val search = EditText(ctx).apply { hint = "Part of a model name, e.g. kimi or qwen coder"; setSingleLine(true) }
        box.addView(search, Ui.lp(Ui.MATCH, Ui.WRAP))
        val chosen = LinkedHashSet<dev.pocketide.app.core.ModelFilter.Kind>()
        val chipRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        box.addView(android.widget.HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) },
            Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 4); it.bottomMargin = Ui.dp(ctx, 4) })
        val rows = ArrayList<Pair<String, Pair<Provider, String>?>>()
        val adapter = object : ArrayAdapter<String>(ctx, android.R.layout.simple_list_item_1, ArrayList<String>()) {
            override fun getCount() = rows.size
            override fun getItem(position: Int) = rows[position].first
            override fun isEnabled(position: Int) = rows[position].second != null
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getView(position, convertView, parent) as android.widget.TextView
                val heading = rows[position].second == null
                v.text = rows[position].first
                v.setTextColor(if (heading) Ui.MUTED else Ui.TEXT)
                v.textSize = if (heading) 13f else 15f
                v.minHeight = Ui.dp(ctx, if (heading) 32 else 44)
                return v
            }
        }
        val lv = ListView(ctx).apply { this.adapter = adapter }
        box.addView(lv, LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(ctx, 360)))
        val count = Ui.text(ctx, "", 12f, Ui.MUTED, false)
        box.addView(count, Ui.lp(Ui.MATCH, Ui.WRAP))

        fun rebuild() {
            val meta = dev.pocketide.app.core.ModelPurpose.known()
            val facts = dev.pocketide.app.core.ModelFacts.all().associateBy { it.provider + "\t" + it.id }
            val query = dev.pocketide.app.core.ModelFilter.parse(search.text.toString(), chosen)
            val hits = entries.filter { (p, id) ->
                val f = facts[p.id + "\t" + id]
                val info = dev.pocketide.app.core.ModelPurpose.info(id, meta[id])
                val noTools = settings.toolsRejected(p.id, id)
                (info.group != dev.pocketide.app.core.ModelPurpose.Group.NOT_CHAT || query.terms.isNotEmpty()) &&
                    dev.pocketide.app.core.ModelFilter.nameMatches(id, query.terms) &&
                    dev.pocketide.app.core.ModelFilter.kindMatches(info, query.kinds, f?.unavailable, f) &&
                    !(noTools && dev.pocketide.app.core.ModelFilter.Kind.TOOLS in query.kinds)
            }.sortedWith(compareBy<Pair<Provider, String>> { it.second.lowercase() }.thenBy { it.first.name })
            rows.clear()
            for ((p, id) in hits.take(500)) {
                val f = facts[p.id + "\t" + id]
                val info = dev.pocketide.app.core.ModelPurpose.info(id, meta[id])
                val tags = listOfNotNull(f?.unavailable?.let { "✕ $it" }, if (settings.toolsRejected(p.id, id)) "refused tool calls here" else null,
                    dev.pocketide.app.core.ModelPurpose.tags(info).ifEmpty { null }).joinToString(" · ")
                val cur = settings.provider == p.id && settings.model(p.id) == id
                rows.add((if (cur) "✓ " else "") + id + "\n   " + p.name + (if (tags.isEmpty()) "" else " · $tags") to (p to id))
            }
            if (hits.size > 500) rows.add("${hits.size - 500} more: type more of the name or pick a kind" to null)
            adapter.notifyDataSetChanged()
            val loading = if (answered < active.size) "Asking providers… $answered of ${active.size} answered. " else ""
            count.text = loading + "${hits.size} of ${entries.size} models from ${active.size - failed.size} provider${if (active.size - failed.size == 1) "" else "s"}" +
                (if (query.kinds.isEmpty()) "" else ", filtered: " + query.kinds.joinToString(", ") { it.label.lowercase() }) +
                (if (failed.isEmpty()) "" else ". No list from: " + failed.joinToString(", ")) + ". Long-press a model for details."
        }
        for (k in dev.pocketide.app.core.ModelFilter.Kind.values()) {
            lateinit var chip: android.widget.TextView
            chip = Ui.chip(ctx, k.label) {
                if (!chosen.remove(k)) chosen.add(k)
                chip.text = (if (k in chosen) "✓ " else "") + k.label
                chip.setTextColor(if (k in chosen) Ui.ACCENT else Ui.MUTED)
                rebuild()
            }
            chipRow.addView(chip, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).also { it.rightMargin = Ui.dp(ctx, 6) })
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { rebuild() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        val dlg = AlertDialog.Builder(ctx).setTitle("Search all providers").setView(box).setNegativeButton("Close", null).create()
        lv.setOnItemClickListener { _, _, i, _ -> val e = rows.getOrNull(i)?.second; if (e != null) { dlg.dismiss(); onPick(e.first, e.second) } }
        lv.setOnItemLongClickListener { _, _, i, _ ->
            val e = rows.getOrNull(i)?.second ?: return@setOnItemLongClickListener false
            val f = dev.pocketide.app.core.ModelFacts.all().firstOrNull { it.provider == e.first.id && it.id == e.second }
            AlertDialog.Builder(ctx).setTitle(e.second)
                .setMessage(e.first.name + "\n" + dev.pocketide.app.core.ModelPurpose.group(e.second).label + "\n\n" +
                    (f?.let { dev.pocketide.app.core.ModelFacts.summary(it).replace(" · ", "\n") } ?: "The provider did not describe this model."))
                .setPositiveButton("Use it") { _, _ -> dlg.dismiss(); onPick(e.first, e.second) }
                .setNegativeButton("Back", null).show()
            true
        }
        rebuild()
        dlg.show()
        for (p in active) fetch(p, settings.key(p.id), settings.baseFor(p.id)) { list, _ ->
            answered++
            if (list == null || list.isEmpty()) failed.add(p.name) else entries.addAll(list.distinct().map { p to it })
            if (dlg.isShowing) rebuild()
        }
    }

    /** Fetches the provider's list and shows it; [onPick] gets the chosen name. */
    fun pick(ctx: Context, p: Provider, key: String, base: String, typed: String, onPick: (String) -> Unit) {
        val toast = android.widget.Toast.makeText(ctx, "Asking ${p.name} for its models…", android.widget.Toast.LENGTH_SHORT); toast.show()
        fetch(p, key, base) { list, err ->
            if (list == null) { AlertDialog.Builder(ctx).setTitle("Could not get the list").setMessage(err).setPositiveButton("OK", null).show(); return@fetch }
            if (list.isEmpty()) { AlertDialog.Builder(ctx).setTitle("No models listed").setMessage("The server connected but did not list any models.").setPositiveButton("OK", null).show(); return@fetch }
            val close = ModelPick.closest(typed, list)
            show(ctx, "Models from ${p.name}", list, typed,
                if (close.isNotEmpty()) "Closest to \"$typed\": ${close.joinToString(", ")}" else "", onPick)
        }
    }
}
