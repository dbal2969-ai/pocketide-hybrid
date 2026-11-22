package dev.pocketide.app

import android.content.Context
import dev.pocketide.app.core.CommandRules

/** The person's command rules, kept in the app's own settings (never in a project, so a repository cannot allow itself commands). */
object CommandRulesStore {
    private const val PREFS = "command-rules"
    private const val KEY = "rules"

    fun load(ctx: Context): CommandRules.Rules =
        CommandRules.decode(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun save(ctx: Context, r: CommandRules.Rules) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, CommandRules.encode(r)).apply()
    }
}
