package dev.pocketide.app

import android.app.AlertDialog
import android.content.Context
import android.widget.ScrollView
import dev.pocketide.app.core.Price
import dev.pocketide.app.core.SpendBudget
import dev.pocketide.app.core.Prices
import dev.pocketide.app.core.Providers
import dev.pocketide.app.core.UsageLog
import dev.pocketide.app.core.UsageText
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Tokens and estimated cost per day and model, with prices the user can set. Free models show tokens only. */
object UsageScreen {
    private fun days(n: Int): List<String> {
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val c = Calendar.getInstance()
        return (0 until n).map { val d = f.format(c.time); c.add(Calendar.DAY_OF_YEAR, -1); d }
    }

    fun show(ctx: Context, settings: AppSettings) {
        val rows = UsageLog.parse(settings.usageJson)
        val prices = Prices.parse(settings.pricesJson)
        // a price the person set wins; Claude models fall back to list prices
        fun priceOf(key: String): Price? = prices[key] ?: dev.pocketide.app.core.KnownPrices.find(key.substringBefore('/'), key.substringAfter('/'))
        val anyPrice = prices.isNotEmpty() || rows.any { priceOf(it.key) != null }
        fun cost(rs: List<UsageLog.Row>): Pair<Double, Boolean> {
            var sum = 0.0; var missing = false
            for (r in rs) { val p = priceOf(r.key); if (p == null) missing = true else sum += Prices.cost(r.usage, p) }
            return sum to missing
        }
        fun block(title: String, rs: List<UsageLog.Row>): String {
            val t = UsageLog.total(rs)
            if (rs.isEmpty()) return "$title: nothing yet\n"
            val (c, missing) = cost(rs)
            val sb = StringBuilder("$title: ${UsageText.line(t, if (!anyPrice) null else c)}${if (missing && anyPrice) " (some models have no price)" else ""}\n")
            for (r in rs.groupBy { it.key }.entries.sortedByDescending { UsageLog.total(it.value).total }) {
                val u = UsageLog.total(r.value); val p = priceOf(r.key)
                sb.append("   ${r.key}: ${UsageText.line(u, p?.let { Prices.cost(u, it) })}  ·  ${r.value.sumOf { it.requests }} requests" +
                    (if (UsageText.lowCache(u)) "  ·  little comes from the cache: check that prompt caching is on" else "") + "\n")
            }
            return sb.toString()
        }
        val text = block("Today", UsageLog.forDays(rows, days(1))) + "\n" + block("Last 7 days", UsageLog.forDays(rows, days(7))) + "\n" +
            block("Last 30 days", UsageLog.forDays(rows, days(30))) +
            "\nCached input is read back at about a tenth of the normal price; a high cached share means caching is paying off. " +
            "Costs use the prices you set (per million tokens), or Anthropic's list prices for Claude models you have not priced. Token counts come from the provider; when a server reports none they are estimated. " +
            "Free tiers and local models cost nothing, so leave their price empty."
        val tv = Ui.text(ctx, text, 12f, Ui.TEXT, true)
        tv.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 8), Ui.dp(ctx, 16), Ui.dp(ctx, 8))
        tv.setTextIsSelectable(true)
        val keys = rows.map { it.key }.distinct().sorted()
        fun setPrice() {
                if (keys.isEmpty()) { android.widget.Toast.makeText(ctx, "Use a model first, then set its price.", android.widget.Toast.LENGTH_SHORT).show(); return }
                AlertDialog.Builder(ctx).setTitle("Price for which model?").setItems(keys.toTypedArray()) { _, i ->
                    val k = keys[i]
                    val cur = prices[k]
                    ChatDialogs.prompt(ctx, "$k: input price and output price in US dollars per million tokens, like 3 15", cur?.let { "${it.inPerM} ${it.outPerM}" } ?: "", "Save") { t ->
                        val n = t.trim().split(Regex("[ ,/]+")).mapNotNull { it.toDoubleOrNull() }
                        val m = LinkedHashMap(prices)
                        if (n.size >= 2) { m[k] = Price(n[0], n[1]); settings.pricesJson = Prices.write(m) }
                        else if (t.isBlank()) { m.remove(k); settings.pricesJson = Prices.write(m) }
                        show(ctx, settings)
                    }
                }.setNegativeButton("Close", null).show()
        }
        AlertDialog.Builder(ctx).setTitle("Usage & cost").setView(ScrollView(ctx).also { it.addView(tv) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Prices & budget") { _, _ ->
                AlertDialog.Builder(ctx).setItems(arrayOf("Set a model price", "Set a budget for a provider")) { _, which ->
                    if (which == 0) setPrice() else budgetFor(ctx, settings)
                }.show()
            }
            .setNegativeButton("Clear") { _, _ -> settings.usageJson = ""; android.widget.Toast.makeText(ctx, "Usage cleared", android.widget.Toast.LENGTH_SHORT).show() }
            .show()
    }

    /** Pick a provider, then enter its limits: dollars per 30 days and/or tokens per day. */
    private fun budgetFor(ctx: Context, settings: AppSettings) {
        val list = Providers.all
        val names = list.map { it.name + "  (" + SpendBudget.describe(settings.budgets[it.id] ?: SpendBudget.Limit()) + ")" }.toTypedArray()
        AlertDialog.Builder(ctx).setTitle("Budget for which provider?").setItems(names) { _, i ->
            val p = list[i]
            ChatDialogs.prompt(ctx, "${p.name}: dollars for 30 days and tokens per day, like 25 200k. Use - for no dollar limit (- 200k), 0 to turn off. Warnings appear under replies at 80% and 100%.",
                settings.budgets[p.id]?.takeIf { !it.isOff }?.let { "${it.dollars.let { d -> if (d > 0) d.toString() else "-" }} ${it.tokensPerDay}" } ?: "", "Save") { t ->
                val l = SpendBudget.parse(t)
                if (l == null) android.widget.Toast.makeText(ctx, "Try something like 25 200k", android.widget.Toast.LENGTH_SHORT).show()
                else settings.budgets = settings.budgets + (p.id to l)
                show(ctx, settings)
            }
        }.setNegativeButton("Close", null).show()
    }
}
