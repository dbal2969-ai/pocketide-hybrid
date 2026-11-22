package dev.pocketide.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.pocketide.app.core.RemoteDev
import java.io.File

/**
 * The Remote machine screen (RemoteDev, 1.5.112): the server's SSH address and folder, the phone's public key to put on it,
 * Connect, Sync, a command box, and the switch that sends the assistant's commands there.
 */
object RemoteScreen {
    fun manifestFile(ctx: Context, projectKey: String) = File(Projects.metaDir(ctx, projectKey), "remote-manifest.txt")

    /** The project's files to send: everything but the skipped folders and files over 2 MB. */
    fun projectFiles(store: ProjectStore): Map<String, ByteArray> = store.list().filter { !RemoteDev.skipped(it) }.mapNotNull { p ->
        store.readBytes(p)?.takeIf { it.size <= 2 * 1024 * 1024 }?.let { p to it }
    }.toMap()

    fun config(settings: AppSettings, projectKey: String, projectName: String): RemoteDev.Config? =
        RemoteDev.parse(settings.remoteAddress(projectKey), settings.remoteDir(projectKey), projectName)

    fun show(ctx: Context, settings: AppSettings, store: ProjectStore, projectKey: String, onFilesChanged: (List<String>) -> Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val pad = Ui.dp(ctx, 14)
        val box = Ui.vertical(ctx).also { it.setPadding(pad, Ui.dp(ctx, 4), pad, 0) }
        val address = EditText(ctx).apply { hint = "user@host or user@host:2222"; setSingleLine(true); setText(settings.remoteAddress(projectKey)); inputType = InputType.TYPE_TEXT_VARIATION_URI }
        val folder = EditText(ctx).apply { hint = "Folder on the server (default ~/pocketide/${RemoteDev.safeName(store.root.name)})"; setSingleLine(true); setText(settings.remoteDir(projectKey)) }
        val password = EditText(ctx).apply { hint = "Password (only if the server takes no key)"; setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setText(settings.remotePassword(projectKey)) }
        box.addView(address, Ui.lp(Ui.MATCH, Ui.WRAP)); box.addView(folder, Ui.lp(Ui.MATCH, Ui.WRAP)); box.addView(password, Ui.lp(Ui.MATCH, Ui.WRAP))
        val runThere = CheckBox(ctx).apply { text = "Run the assistant's commands there (files are synced before and brought back after)"; isChecked = settings.remoteRun(projectKey) }
        box.addView(runThere)
        val keyNote = Ui.text(ctx, "Making the phone's key…", 12f, Ui.MUTED, false)
        box.addView(keyNote)
        val actions = Ui.horizontal(ctx)
        box.addView(actions, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
        val cmd = EditText(ctx).apply { hint = "A command to run there, e.g. npm test"; typeface = Typeface.MONOSPACE; textSize = 13f; setSingleLine(true) }
        box.addView(cmd, Ui.lp(Ui.MATCH, Ui.WRAP))
        val out = TextView(ctx).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextColor(Ui.TEXT); setTextIsSelectable(true) }
        val sv = ScrollView(ctx).also { it.addView(out) }
        box.addView(sv, LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(ctx, 240)))

        fun save() {
            settings.setRemoteAddress(projectKey, address.text.toString()); settings.setRemoteDir(projectKey, folder.text.toString())
            settings.setRemotePassword(projectKey, password.text.toString()); settings.setRemoteRun(projectKey, runThere.isChecked && config(settings, projectKey, store.root.name) != null)
        }
        fun client() = RemoteClient(ctx.filesDir, password.text.toString())
        fun cfg(): RemoteDev.Config? { save(); return config(settings, projectKey, store.root.name).also { if (it == null) out.text = "Type the address as user@host (or user@host:port). The folder may use letters, numbers, / and ~ only." } }
        fun busy(text: String, work: () -> String) { out.text = text; Thread { val r = try { work() } catch (e: Exception) { "Could not do it: " + (e.message ?: e.javaClass.simpleName) + RemoteHints.hint(e.message) }; main.post { out.text = r; sv.scrollTo(0, 0) } }.start() }

        Thread { val k = try { client().publicKey() } catch (e: Exception) { "" }; main.post { keyNote.text = if (k.isEmpty()) "Could not make a key on this phone." else RemoteDev.setupSteps(k); keyNote.tag = k } }.start()
        actions.addView(Ui.button(ctx, "Copy key", false) {
            val k = keyNote.tag as? String ?: return@button
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ssh key", k))
            Toast.makeText(ctx, "Public key copied. Add it to ~/.ssh/authorized_keys on the server.", Toast.LENGTH_LONG).show()
        })
        actions.addView(Ui.button(ctx, "Connect", true) {
            val c = cfg() ?: return@button
            busy("Connecting to ${c.label}…") { val (code, o) = client().exec(c, "uname -srm; echo; bash --version | head -1; command -v node python3 java git | tr '\\n' ' '", 30); if (code == 0) "Connected to ${c.label}.\n\n$o" else "Connected, but the test command failed ($code):\n$o" }
        }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 8) })
        actions.addView(Ui.button(ctx, "Sync", false) {
            val c = cfg() ?: return@button
            busy("Copying the project to ${c.label}…") {
                val mf = manifestFile(ctx, projectKey)
                val (m, n) = client().syncUp(c, projectFiles(store), mf.takeIf { it.isFile }?.readText())
                mf.writeText(m); "Synced: $n file${if (n == 1) "" else "s"} sent or removed. The project is in ${c.dir} on ${c.label}."
            }
        }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 8) })
        actions.addView(Ui.button(ctx, "Run", false) {
            val c = cfg() ?: return@button
            val command = cmd.text.toString().trim().ifEmpty { return@button }
            busy("Running on ${c.label}: $command") {
                val mf = manifestFile(ctx, projectKey)
                val r = client().run(c, command, projectFiles(store), mf.takeIf { it.isFile }?.readText(), 600)
                mf.writeText(r.manifest)
                for ((p, b) in r.changed) store.writeBytes(p, b)
                if (r.changed.isNotEmpty()) main.post { onFilesChanged(r.changed.keys.toList()) }
                "exit code ${r.code}" + (if (r.changed.isEmpty()) "" else " · brought back: " + r.changed.keys.take(10).joinToString(", ")) + "\n\n" + r.output
            }
        }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 8) })
        actions.addView(Ui.button(ctx, "Forget servers", false) {
            File(File(ctx.filesDir, "ssh"), "known_hosts").writeText("")
            Toast.makeText(ctx, "The phone no longer remembers any server's identity; the next connection trusts the server again.", Toast.LENGTH_LONG).show()
        }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 8) })
        AlertDialog.Builder(ctx).setTitle("Remote machine").setView(ScrollView(ctx).also { it.addView(box) })
            .setOnDismissListener { save() }.setNegativeButton("Close", null).show()
    }
}

/** Plain words for the usual SSH failures. */
object RemoteHints {
    fun hint(message: String?): String {
        val m = message.orEmpty()
        return when {
            m.contains("Auth fail", true) || m.contains("Auth cancel", true) -> "\n\nThe server did not accept the phone's key. Add the key (Copy key) to ~/.ssh/authorized_keys for that user, or type the password."
            m.contains("UnknownHost", true) || m.contains("unknown host", true) -> "\n\nThat host name was not found. Check the address."
            m.contains("timed out", true) || m.contains("timeout", true) -> "\n\nNo answer. Check the address and port, and that the server accepts SSH from the internet."
            m.contains("refused", true) -> "\n\nThe server refused the connection: is SSH running on that port?"
            m.contains("HostKey", true) || m.contains("CHANGED", true) || m.contains("reject", true) -> "\n\nThe server's identity changed since the last connection. If you reinstalled it, tap Forget servers and connect again; otherwise do not connect."
            else -> ""
        }
    }
}
