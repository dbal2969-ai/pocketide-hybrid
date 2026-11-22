package dev.pocketide.app

import android.app.AlertDialog
import android.content.Context
import dev.pocketide.app.core.AgentProfiles

/** Pick a preset agent profile. */
object AgentProfileScreen {
    fun show(ctx: Context, settings: AppSettings, onChanged: () -> Unit) {
        val cur = settings.profile
        val items = AgentProfiles.ALL.map { (if (it.id == cur) "● " else "○ ") + it.name + (if (it.readOnly) " (read-only)" else "") + "\n" + it.summary }.toTypedArray()
        AlertDialog.Builder(ctx).setTitle("Agent profile")
            .setItems(items) { _, i -> settings.profile = AgentProfiles.ALL[i].id; onChanged() }
            .setNegativeButton("Close", null).show()
    }
}
