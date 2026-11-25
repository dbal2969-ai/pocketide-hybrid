package dev.pocketide.app.core

/**
 * The app_settings tool (1.5.185): the assistant reads the person's settings, and proposes a change that only happens when the
 * person taps Apply on a card in the chat (NativeAgent shows it; AppSettingsBridge reads and writes the values). Only the tick boxes
 * and choices in AppMap can be proposed. Never API keys, tokens, passwords or addresses (they are text boxes, which have no
 * setting here, and any name that looks like a secret is refused as well), and never "Run commands from the assistant without
 * asking", which only the person may turn on. Pure logic.
 */
object AppSettingsTool {
    const val NAME = "app_settings"

    /** Settings the assistant may read but never propose: they decide what the assistant may do without asking. */
    val PERSON_ONLY = setOf("autorun")

    private val SECRET = Regex("""(?i)key|token|secret|password|anon|refresh|account|workspace""")

    /** The mode chip's choices (ChatPane.modeMenu), as the values of the "mode" setting. */
    val MODES = listOf("ask", "plan", "agent", "suggest")

    /** Every setting the tool knows: the tick boxes and choices in AppMap with a stored setting, one item each. */
    val known: List<AppMap.Item> get() = AppMap.ITEMS.filter { it.setting != null && (it.kind == AppMap.Kind.CHECKBOX || it.kind == AppMap.Kind.CHOICE) }
        .distinctBy { it.setting }

    sealed class Check {
        class Ok(val item: AppMap.Item, val value: String, val shown: String) : Check()
        class No(val why: String) : Check()
    }

    private val ON = setOf("true", "on", "yes", "1", "enable", "enabled", "tick", "ticked", "checked")
    private val OFF = setOf("false", "off", "no", "0", "disable", "disabled", "untick", "unticked", "unchecked")

    /** The item for [key]: a setting name ("editorWrap"), or the label, or most of it, as the model may give either. */
    fun find(key: String): AppMap.Item? {
        val k = key.trim().removePrefix("settings.").trim('"', ' ')
        if (k.isEmpty()) return null
        known.firstOrNull { it.setting.equals(k, ignoreCase = true) }?.let { return it }
        known.firstOrNull { it.label.equals(k, ignoreCase = true) }?.let { return it }
        if (k.length >= 6) known.firstOrNull { it.label.lowercase().startsWith(k.lowercase()) }?.let { return it }
        val (hits, _) = AppHelp.search(k)
        val s = hits.firstOrNull { it.doc.setting != null && it.score >= 6 }?.doc?.setting ?: return null
        return known.firstOrNull { it.setting == s }
    }

    /** Whether [key] may be set to [value], with the value in its stored form ("true", "best", "plan") and as the person reads it. */
    fun check(key: String, value: String): Check {
        if (SECRET.containsMatchIn(key) && find(key)?.setting?.let { SECRET.containsMatchIn(it) } != false)
            return Check.No("API keys, tokens, passwords and account details are never changed by the assistant. Tell the person to paste it themselves in ⚙ Settings › AI provider.")
        val item = find(key) ?: return Check.No("There is no setting called \"$key\" that the assistant can change. Call app_settings with action \"get\" for the list, or app_help to find where it is.")
        val s = item.setting!!
        if (s in PERSON_ONLY) return Check.No("Only the person can change this one: ${AppMap.path(item)}. Tell them where it is; do not propose it.")
        val v = value.trim().lowercase().trim('"')
        return when (s) {
            "hybrid.cloud_model" -> CloudTier.Choice.values().firstOrNull { it.id == v || it.label.lowercase() == v }
                ?.let { Check.Ok(item, it.id, it.label) } ?: Check.No("The cloud model is one of: " + CloudTier.Choice.values().joinToString(", ") { it.id } + ".")
            "mode" -> (MODES.firstOrNull { v == it || v.startsWith(it) } ?: if (v.contains("suggest")) "suggest" else null)
                ?.let { Check.Ok(item, it, modeLabel(it)) } ?: Check.No("The mode is one of: " + MODES.joinToString(", ") + ".")
            else -> when (v) {
                in ON -> Check.Ok(item, "true", "on")
                in OFF -> Check.Ok(item, "false", "off")
                else -> Check.No("\"${item.label}\" is a tick box: give value true or false.")
            }
        }
    }

    fun modeLabel(m: String): String = when (m) { "ask" -> "Ask"; "plan" -> "Plan"; "suggest" -> "Agent: suggest"; else -> "Agent" }

    /** How a stored value reads to the person. */
    fun shown(setting: String, stored: String?): String = when {
        stored == null -> "unknown"
        setting == "hybrid.cloud_model" -> CloudTier.Choice.of(stored).label
        setting == "mode" -> modeLabel(stored)
        stored == "true" -> "on"
        stored == "false" -> "off"
        else -> stored
    }

    /** The reply to action "get": every setting with its path and current value ([value] reads it), or just the ones [filter] names. */
    fun list(filter: String, value: (String) -> String?): String {
        val f = filter.trim()
        val items = if (f.isEmpty()) known else listOfNotNull(find(f)).ifEmpty {
            AppHelp.search(f).first.mapNotNull { h -> h.doc.setting?.let { s -> known.firstOrNull { it.setting == s } } }.distinct().take(8)
        }
        if (items.isEmpty()) return "No setting matches \"$f\". Call app_help to find where it is, or get with no key for the full list."
        return items.joinToString("\n") { i -> "- ${i.setting} = ${shown(i.setting!!, value(i.setting))}: ${AppMap.path(i)}" + if (i.setting in PERSON_ONLY) " (only the person can change this)" else "" } +
            "\nTo change one, call app_settings with action \"propose\", the key, the value and why. Text boxes (keys, models, addresses) are not listed: the person types those in ⚙ Settings."
    }

    /** The tool's reply once a card is shown: the change is not made yet. */
    fun proposed(c: Check.Ok, now: String): String =
        "Proposed, NOT applied yet: the person sees a card \"${c.item.label}: $now → ${c.shown}\" with Apply and No. " +
            "Tell them what it will change and that it happens when they tap Apply. Do not say it is changed."

    /** What the card's decision is recorded as in the chat's record (bench/hybrid/analyze.py reads it). */
    fun record(c: Check.Ok, decision: String) = "settings proposal: ${c.item.setting} = ${c.value} ($decision)"
}
