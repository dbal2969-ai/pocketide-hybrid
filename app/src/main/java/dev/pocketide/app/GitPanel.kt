package dev.pocketide.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.Toast
import dev.pocketide.app.core.ChangeKind
import dev.pocketide.app.core.FileChange
import dev.pocketide.app.core.FolderFiles
import dev.pocketide.app.core.GitCore
import dev.pocketide.app.core.GitHubException
import dev.pocketide.app.core.GitHubHttp
import dev.pocketide.app.core.GitRepo
import dev.pocketide.app.core.GitSync
import java.io.File

/** The Git button: pull from and push to a GitHub branch, see what changed, and revert files. */
class GitPanel(
    private val act: Activity,
    private val store: ProjectStore,
    private val settings: AppSettings,
    private val beforeSync: () -> Unit,
    private val onFilesChanged: () -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val stateDir: File get() = File(Projects.metaDir(act), "git")  // v0.4 W1: per project
    private var busy = false

    private fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_LONG).show()
    private fun pad() = Ui.dp(act, 20)

    private fun repo(): GitRepo? = GitRepo.parse(settings.ghRepo)
    private fun branch(): String? = GitCore.branch(settings.ghBranch)

    private fun sync(progress: (String) -> Unit = {}): GitSync? {
        val r = repo() ?: return null
        val b = branch() ?: return null
        return GitSync(GitHubHttp(settings.ghToken), FolderFiles(store.root), stateDir, r, b, progress)
    }

    /** v0.4: local changes since the last sync as "M app.js" lines, for the AI's /commit. Empty when not synced. */
    fun changesSummary(): String = try {
        sync()?.changes()?.joinToString("\n") { "${it.kind.letter} ${it.path}" } ?: ""
    } catch (e: Exception) { "" }

    fun open() {
        val r = repo()
        if (r == null || branch() == null || settings.ghToken.isEmpty()) { setup(); return }
        AlertDialog.Builder(act).setTitle("GitHub: ${r.identity} (${settings.ghBranch})")
            .setItems(arrayOf("Pull from GitHub", "Changes since last sync", "Push to GitHub", "Branches…", "History", "Send a task to Copilot", "Copilot's pull requests", "Setup")) { _, i ->
                when (i) { 0 -> pull(); 1 -> changes(); 2 -> push(); 3 -> branches(); 4 -> history(); 5 -> sendToCopilot(); 6 -> copilotPulls(); else -> setup() }
            }.setNegativeButton("Close", null).show()
    }

    // ---- GitHub Copilot coding agent ----

    private fun openUrl(url: String) {
        try { act.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } catch (e: Exception) { toast("No app can open $url") }
    }

    /** Writes the task as an issue and assigns it to Copilot, which builds it on GitHub and opens a draft pull request. */
    private fun sendToCopilot() {
        val r = repo() ?: return setup()
        val field = EditText(act).also {
            it.hint = "What should Copilot do? The first line becomes the issue title."
            it.minLines = 4
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val box = Ui.vertical(act)
        box.setPadding(pad(), Ui.dp(act, 8), pad(), 0)
        box.addView(Ui.text(act, "Copilot works on ${r.identity} in GitHub Actions and opens a draft pull request. Push your local changes first if it needs them. Needs a Copilot plan with the coding agent turned on for this repository.", 12f, Ui.MUTED))
        box.addView(field)
        AlertDialog.Builder(act).setTitle("Send a task to Copilot").setView(box)
            .setPositiveButton("Send") { _, _ ->
                val task = field.text.toString().trim()
                if (task.isEmpty()) { toast("Write the task first."); return@setPositiveButton }
                work("Sending to Copilot", { p -> p("Creating the issue…"); dev.pocketide.app.core.CopilotHandoff.send(GitHubHttp(settings.ghToken), r, task, settings.ghBranch) }) { sent ->
                    AlertDialog.Builder(act).setTitle(if (sent.assigned) "Sent to Copilot" else "Issue created").setMessage(sent.message())
                        .setPositiveButton("Open issue") { _, _ -> openUrl(sent.url) }
                        .setNegativeButton("Close", null).show()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** Copilot's open pull requests; tap one to review it on GitHub, then Pull here once it is merged. */
    private fun copilotPulls() {
        val r = repo() ?: return setup()
        work("Copilot's pull requests", { p -> p("Asking GitHub…"); dev.pocketide.app.core.CopilotHandoff.pulls(GitHubHttp(settings.ghToken), r) }) { list ->
            if (list.isEmpty()) { AlertDialog.Builder(act).setTitle("Copilot's pull requests").setMessage("Copilot has no open pull requests in ${r.identity}.").setPositiveButton("OK", null).show(); return@work }
            AlertDialog.Builder(act).setTitle("Copilot's pull requests (${list.size})")
                .setItems(list.map { "#${it.number} " + (if (it.draft) "[draft] " else "") + it.title }.toTypedArray()) { _, i -> openUrl(list[i].url) }
                .setNegativeButton("Close", null).show()
        }
    }

    // ---- setup ----

    fun setup() {
        val box = Ui.vertical(act)
        box.setPadding(pad(), Ui.dp(act, 8), pad(), 0)
        fun field(hint: String, value: String, type: Int): EditText {
            val e = EditText(act)
            e.hint = hint
            e.setText(value)
            e.setSingleLine()
            e.inputType = type
            box.addView(e)
            return e
        }
        val repoField = field("owner/repository or GitHub URL", settings.ghRepo, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val branchField = field("Branch", settings.ghBranch, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        val tokenField = field("Personal access token", settings.ghToken, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        box.addView(Ui.text(act,
            "Make a token on github.com: Settings > Developer settings > Personal access tokens > Fine-grained tokens. " +
                "Choose only this repository and give it Contents: Read and write. To push a GitHub Actions workflow (.github/workflows) also give it Workflows: Read and write. The token stays in this app's private storage.",
            12f, Ui.MUTED).also { it.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 8)) })
        val scroll = ScrollView(act)
        scroll.addView(box)
        val dlg = AlertDialog.Builder(act).setTitle("GitHub setup").setView(scroll)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val r = GitRepo.parse(repoField.text.toString())
                val b = GitCore.branch(branchField.text.toString().ifBlank { "main" })
                val t = tokenField.text.toString().trim()
                when {
                    r == null -> repoField.error = "Use owner/repository, for example dbal2969-ai/Test-Coder2.2"
                    b == null -> branchField.error = "Not a valid branch name"
                    !GitCore.tokenLooksValid(t) -> tokenField.error = "This does not look like a GitHub token"
                    else -> {
                        settings.ghRepo = r.identity
                        settings.ghBranch = b
                        settings.ghToken = t
                        dlg.dismiss()
                        toast("Saved. Pull first to link this project to ${r.identity}.")
                    }
                }
            }
        }
        dlg.show()
    }

    // ---- background work with a small progress dialog ----

    private fun <T> work(title: String, task: (progress: (String) -> Unit) -> T, done: (T) -> Unit) {
        if (busy) { toast("A GitHub operation is already running."); return }
        busy = true
        beforeSync()
        val msg = Ui.text(act, "Starting…", 14f, Ui.TEXT)
        msg.setPadding(pad(), Ui.dp(act, 12), pad(), Ui.dp(act, 12))
        val dlg = AlertDialog.Builder(act).setTitle(title).setView(msg).setCancelable(false).create()
        dlg.show()
        Thread {
            val result = try { Result.success(task { s -> main.post { msg.text = s } }) } catch (e: Throwable) { Result.failure<T>(e) }
            main.post {
                busy = false
                try { dlg.dismiss() } catch (e: Exception) { /* activity gone */ }
                result.fold(done) { e -> problem(e) }
            }
        }.start()
    }

    private fun problem(e: Throwable) {
        val m = when (e) {
            is GitHubException -> e.message
            is java.net.UnknownHostException -> "Could not reach GitHub. Check your internet connection."
            is java.io.IOException -> "Network problem: ${e.message}"
            else -> e.message ?: e.toString()
        }
        AlertDialog.Builder(act).setTitle("GitHub").setMessage(m).setPositiveButton("OK", null).show()
    }

    // ---- branches and history ----

    private fun branches() {
        val s = sync() ?: return setup()
        work("Branches", { s.branches() }) { names ->
            val cur = branch()
            val labels = (names.map { if (it == cur) "$it  (current)" else it } + "New branch from $cur…").toTypedArray()
            AlertDialog.Builder(act).setTitle("Branches").setItems(labels) { _, i ->
                if (i < names.size) switchTo(names[i]) else newBranch(s)
            }.setNegativeButton("Close", null).show()
        }
    }

    private fun switchTo(name: String) {
        if (name == branch()) return
        AlertDialog.Builder(act).setTitle("Switch to $name?")
            .setMessage("Your files stay as they are. Pull afterwards to load $name, or push to put your files on it.")
            .setPositiveButton("Switch") { _, _ -> settings.ghBranch = name; toast("Now on $name") }
            .setNegativeButton("Cancel", null).show()
    }

    private fun newBranch(s: GitSync) {
        val input = EditText(act).also { it.hint = "feature/name"; it.setSingleLine(true) }
        val box = Ui.vertical(act); box.setPadding(pad(), Ui.dp(act, 8), pad(), 0); box.addView(input)
        AlertDialog.Builder(act).setTitle("New branch").setView(box)
            .setPositiveButton("Create") { _, _ ->
                val name = GitCore.branch(input.text.toString())
                if (name == null) { toast("That is not a valid branch name."); return@setPositiveButton }
                work("Creating $name", { s.createBranch(name) }) { settings.ghBranch = name; toast("Created $name and switched to it") }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun history() {
        val s = sync() ?: return setup()
        work("History", { s.history() }) { list ->
            val msg = Ui.text(act, if (list.isEmpty()) "No commits yet." else list.joinToString("\n") { it.line }, 12f, Ui.TEXT, true)
            msg.setPadding(pad(), Ui.dp(act, 8), pad(), Ui.dp(act, 8)); msg.setTextIsSelectable(true)
            val sv = android.widget.ScrollView(act); sv.addView(msg)
            AlertDialog.Builder(act).setTitle(branch() ?: "History").setView(sv).setPositiveButton("Close", null).show()
        }
    }

    // ---- pull ----

    private fun pull() {
        val s = sync() ?: return setup()
        if (s.snapshot() == null) {
            AlertDialog.Builder(act).setTitle("First pull from ${settings.ghRepo}")
                .setMessage("Replace project: the project becomes an exact copy of the branch.\n\nKeep my files: GitHub's files are added and replace same-named files here; your other files stay.")
                .setPositiveButton("Replace project") { _, _ -> runPull(overwrite = true, replace = true) }
                .setNeutralButton("Keep my files") { _, _ -> runPull(overwrite = true, replace = false) }
                .setNegativeButton("Cancel", null).show()
        } else runPull(overwrite = false, replace = false)
    }

    private fun runPull(overwrite: Boolean, replace: Boolean) {
        work("Pulling", { p -> sync(p)!!.pull(overwrite, replace) }) { r ->
            if (!r.applied) {
                AlertDialog.Builder(act).setTitle("Changed in both places")
                    .setMessage("These files changed here and on GitHub since the last sync:\n\n" + r.conflicts.joinToString("\n") { "• $it" } +
                        "\n\nOverwrite replaces your versions with GitHub's. Cancel to keep yours (then copy what you need, revert, and pull again).")
                    .setPositiveButton("Overwrite mine") { _, _ -> runPull(overwrite = true, replace = false) }
                    .setNegativeButton("Cancel", null).show()
                return@work
            }
            onFilesChanged()
            val n = r.written.size + r.deleted.size
            toast(if (n == 0) "Already up to date (${r.commit.take(7)})." else "Pulled ${r.written.size} file(s), removed ${r.deleted.size} (${r.commit.take(7)}).")
        }
    }

    // ---- push ----

    private fun push() {
        work("Checking changes", { _ -> sync()!!.changes() }) { list ->
            if (list != null && list.isEmpty()) { toast("Nothing to push: no changes since the last sync."); return@work }
            val box = Ui.vertical(act)
            box.setPadding(pad(), Ui.dp(act, 8), pad(), 0)
            val summary = if (list == null) "Not synced yet. If the repository is empty, this starts it with your files; otherwise pull first."
            else list.take(30).joinToString("\n") { "${it.kind.letter}  ${it.path}" } + if (list.size > 30) "\n… ${list.size - 30} more" else ""
            box.addView(Ui.text(act, summary, 12f, Ui.MUTED, true))
            val msg = EditText(act)
            msg.hint = "Commit message"
            msg.setText(if (list == null) "Initial commit from PocketIDE" else defaultMessage(list))
            msg.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            box.addView(msg)
            val scroll = ScrollView(act)
            scroll.addView(box)
            AlertDialog.Builder(act).setTitle("Push to ${settings.ghRepo} (${settings.ghBranch})").setView(scroll)
                .setPositiveButton("Push") { _, _ ->
                    val m = msg.text.toString().trim().ifEmpty { "Update from PocketIDE" }
                    work("Pushing", { p -> sync(p)!!.push(m) }) { r ->
                        toast(if (r.commit == null) "Nothing to push." else "Pushed ${r.changes.size} change(s) as ${r.commit.take(7)}.")
                    }
                }.setNegativeButton("Cancel", null).show()
        }
    }

    private fun defaultMessage(list: List<FileChange>): String =
        if (list.size == 1) "${list[0].kind.label} ${list[0].path}" else "Update ${list.size} files"

    // ---- changes ----

    private fun changes() {
        work("Comparing", { _ -> sync()!!.changes() }) { list ->
            when {
                list == null -> toast("This project has not been synced with ${settings.ghRepo} yet. Pull first.")
                list.isEmpty() -> toast("No changes since the last sync.")
                else -> {
                    val labels = list.map { "${it.kind.letter}   ${it.path}" }.toTypedArray()
                    AlertDialog.Builder(act).setTitle("Changes (${list.size})")
                        .setItems(labels) { _, i -> showDiff(list[i]) }
                        .setNegativeButton("Close", null).show()
                }
            }
        }
    }

    private fun showDiff(c: FileChange) {
        work("Loading ${c.path}", { _ ->
            val s = sync()!!
            val old = if (c.kind == ChangeKind.ADDED) null else s.baseBytes(c.path)
            val new = if (c.kind == ChangeKind.DELETED) null else FolderFiles(store.root).read(c.path)
            old to new
        }) { (old, new) ->
            val body: CharSequence = if (!DiffView.isText(old) || !DiffView.isText(new)) "Binary file ${c.kind.label.lowercase()}."
            else DiffView.render(old?.toString(Charsets.UTF_8), new?.toString(Charsets.UTF_8))
            val t = Ui.text(act, body, 11f, Ui.TEXT, true)
            t.setPadding(Ui.dp(act, 12), Ui.dp(act, 8), Ui.dp(act, 12), Ui.dp(act, 8))
            t.setTextIsSelectable(true)
            val hs = HorizontalScrollView(act)
            hs.addView(t)
            val vs = ScrollView(act)
            vs.addView(hs)
            AlertDialog.Builder(act).setTitle("${c.kind.label}: ${c.path}").setView(vs)
                .setPositiveButton("Revert") { _, _ -> confirmRevert(c) }
                .setNegativeButton("Back") { _, _ -> changes() }.show()
        }
    }

    private fun confirmRevert(c: FileChange) {
        val what = when (c.kind) {
            ChangeKind.ADDED -> "delete ${c.path} (it is not on GitHub)"
            ChangeKind.DELETED -> "restore ${c.path}"
            ChangeKind.MODIFIED -> "discard your changes to ${c.path}"
        }
        AlertDialog.Builder(act).setTitle("Revert?").setMessage("This will $what.")
            .setPositiveButton("Revert") { _, _ ->
                work("Reverting", { _ -> sync()!!.revert(c.path) }) { ok ->
                    onFilesChanged()
                    toast(if (ok) "Reverted ${c.path}." else "Could not revert ${c.path}.")
                }
            }.setNegativeButton("Cancel", null).show()
    }
}
