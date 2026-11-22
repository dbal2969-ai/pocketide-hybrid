package dev.pocketide.app

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText

/**
 * Search the chats of every project and of the Chat home (1.5.125), as the Claude app searches all conversations. Each project's
 * Chats list only shows its own; this finds a conversation without remembering where it was. Tapping one opens it there.
 */
object ChatSearchScreen {
    fun show(act: Activity, open: (project: String, chatId: String) -> Unit) {
        val fs = FullScreen(act, "Search all chats")
        val all = Projects.allChats(act)
        val box = EditText(act).also { it.hint = "Words in a chat or its title"; it.setSingleLine() }
        val results = Ui.vertical(act)
        fs.body.addView(box, Ui.lp(Ui.MATCH, Ui.WRAP))
        fs.body.addView(results, Ui.lp(Ui.MATCH, Ui.WRAP))
        val now = System.currentTimeMillis()
        fun render() {
            results.removeAllViews()
            val hits = dev.pocketide.app.core.ChatHome.search(all, box.text.toString())
            if (hits.isEmpty()) { results.addView(Ui.text(act, if (all.isEmpty()) "No saved chats yet." else "No chat has all of those words.", 13f, Ui.MUTED)); return }
            for (h in hits) {
                val c = fs.card(results)
                c.addView(Ui.text(act, h.title.ifBlank { "Chat" }, 15f, Ui.TEXT).also { it.typeface = android.graphics.Typeface.DEFAULT_BOLD })
                c.addView(Ui.text(act, Projects.label(h.project) + " · " + dev.pocketide.app.core.RepoBrowser.relativeTime(now, h.updated), 12f, Ui.ACCENT))
                if (h.snippet.isNotBlank()) c.addView(Ui.text(act, h.snippet, 13f, Ui.MUTED))
                c.setOnClickListener { fs.dialog.dismiss(); open(h.project, h.chatId) }
            }
        }
        box.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { render() }
        })
        render()
        fs.show()
        // 1.5.127: the screen opened with the focus on Close, so typing went nowhere until the box was tapped (emulator test)
        box.requestFocus()
        fs.dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }
}
