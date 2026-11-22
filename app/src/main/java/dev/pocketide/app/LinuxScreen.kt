package dev.pocketide.app

import android.app.Activity
import android.app.AlertDialog
import android.widget.ScrollView

/**
 * GPL route: the "Licenses" screen. (Linux setup moved to the Tools screen, ToolsScreen.kt.)
 */
object LinuxScreen {

    /** Lists the notices in assets/licenses and shows the chosen one. */
    fun licenses(act: Activity) {
        val names = (act.assets.list("licenses") ?: emptyArray()).sorted()
        if (names.isEmpty()) {
            AlertDialog.Builder(act).setTitle("Licenses").setMessage("No license files were packaged in this build.").setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(act).setTitle("Licenses and notices").setItems(names.toTypedArray()) { _, i ->
            val text = try { act.assets.open("licenses/" + names[i]).bufferedReader().use { it.readText() } } catch (e: Exception) { "Could not read: ${e.message}" }
            val tv = Ui.text(act, text, 11f, Ui.TEXT, true)
            tv.setTextIsSelectable(true)
            tv.setPadding(Ui.dp(act, 16), Ui.dp(act, 8), Ui.dp(act, 16), Ui.dp(act, 8))
            val sv = ScrollView(act)
            sv.addView(tv)
            AlertDialog.Builder(act).setTitle(names[i]).setView(sv).setPositiveButton("Back") { _, _ -> licenses(act) }.show()
        }.setNegativeButton("Close", null).show()
    }
}
