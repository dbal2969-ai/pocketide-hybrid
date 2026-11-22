package dev.pocketide.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Toast
import dev.pocketide.app.core.FolderFiles
import dev.pocketide.app.core.GitCore
import dev.pocketide.app.core.GitHubException
import dev.pocketide.app.core.GitHubHttp
import dev.pocketide.app.core.GitRepo
import dev.pocketide.app.core.GitSync
import dev.pocketide.app.core.ProjectNames
import dev.pocketide.app.core.Templates
import java.io.File

/** Pick, create, clone, rename, duplicate and delete projects (v0.4 W1). Switching goes through Projects.switchTo. */
object ProjectsScreen {
    fun show(act: Activity, onChat: (() -> Unit)? = null, onSearchChats: (() -> Unit)? = null, beforeSwitch: () -> Unit) {
        val fs = FullScreen(act, "Projects")
        fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_SHORT).show()

        fun render() {
            fs.body.removeAllViews()
            fs.buttonRow(fs.body,
                Ui.button(act, "New project", true) { newProject(act) { name -> beforeSwitch(); Projects.switchTo(act, name); fs.dialog.dismiss() } },
                Ui.button(act, "Clone from GitHub") { clone(act) { name -> beforeSwitch(); Projects.switchTo(act, name); fs.dialog.dismiss() } }
            )
            // 1.5.125: a chat with no project, and a search over every project's chats
            if (onChat != null || onSearchChats != null) fs.buttonRow(fs.body,
                Ui.button(act, "Just chat") { fs.dialog.dismiss(); onChat?.invoke() },
                Ui.button(act, "Search all chats") { fs.dialog.dismiss(); onSearchChats?.invoke() })
            val current = Projects.key(act)
            val repos = AppSettings(act).newLayout
            fs.heading(if (repos) "Your repositories" else "Your projects")
            val now = System.currentTimeMillis()
            val names: List<String> = if (repos) dev.pocketide.app.core.RepoList.order(Projects.list(act).map { nm ->
                dev.pocketide.app.core.RepoCard(nm, Projects.fileCount(act, nm), Projects.modified(act, nm), Projects.description(act, nm),
                    Projects.gitConfig(act, nm).getProperty("repo", ""), nm == current)
            }).map { it.name } else Projects.list(act)
            for (name in names) {
                val c = fs.card()
                val isOpen = name == current
                c.addView(Ui.text(act, name, 16f, if (isOpen) Ui.ACCENT else Ui.TEXT).also { it.typeface = android.graphics.Typeface.DEFAULT_BOLD })
                if (repos) Projects.description(act, name).takeIf { it.isNotEmpty() }?.let { c.addView(Ui.text(act, it, 13f, Ui.TEXT).also { t -> t.setPadding(0, Ui.dp(act, 2), 0, 0) }) }
                val n = Projects.fileCount(act, name)
                val repo = Projects.gitConfig(act, name).getProperty("repo", "")
                val ago = if (repos) dev.pocketide.app.core.RepoBrowser.relativeTime(now, Projects.modified(act, name)) else ""
                c.addView(Ui.text(act, (if (n >= 999) "999+ files" else "$n file${if (n == 1) "" else "s"}") +
                    (if (ago.isNotEmpty()) " · updated $ago" else "") +
                    (if (repo.isNotEmpty()) " · GitHub: $repo" else "") + (if (isOpen) " · open now" else ""), 12f, Ui.MUTED))
                c.setOnClickListener {
                    if (!isOpen) { beforeSwitch(); Projects.switchTo(act, name); fs.dialog.dismiss() }
                    else fs.dialog.dismiss()
                }
                fs.buttonRow(c,
                    Ui.button(act, if (isOpen) "Open" else "Open", !isOpen) {
                        if (!isOpen) { beforeSwitch(); Projects.switchTo(act, name) }
                        fs.dialog.dismiss()
                    },
                    Ui.button(act, "More…") { more(act, name, beforeSwitch) { render() } }
                )
            }
            fs.body.addView(Ui.text(act, "Each project is its own folder with its own chats, GitHub link and open tabs.", 12f, Ui.MUTED).also {
                it.setPadding(0, Ui.dp(act, 16), 0, 0)
            })
        }
        render()
        fs.show()
    }

    fun more(act: Activity, name: String, beforeSwitch: () -> Unit, changed: () -> Unit) {
        fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_SHORT).show()
        val meta = java.io.File(java.io.File(act.filesDir, "meta"), name)
        val pinned = java.io.File(meta, "pinned").isFile; val archived = java.io.File(meta, "archived").isFile
        val labels = arrayOf("Rename", "Duplicate", "Delete", "Save as a template", if (pinned) "Unpin" else "Pin to the top", if (archived) "Unarchive" else "Archive (read-only)")
        AlertDialog.Builder(act).setTitle(name).setItems(labels) { _, i ->
            when (i) {
                4 -> { meta.mkdirs(); val f = java.io.File(meta, "pinned"); if (pinned) f.delete() else f.writeText("1"); changed() }
                5 -> { meta.mkdirs(); val f = java.io.File(meta, "archived"); if (archived) f.delete() else f.writeText("1"); toast(if (archived) "Unarchived" else "Archived: read-only until unarchived"); changed() }
                3 -> { if (name == Projects.key(act)) beforeSwitch(); WorkUi.saveAsTemplate(act, name) { t -> toast(if (t == null) "Could not save the template" else "Saved: New project now offers \"$t\"") } }
                0 -> prompt(act, "Rename project", name, "Rename") { to ->
                    if (to.isBlank()) return@prompt
                    if (name == Projects.key(act)) beforeSwitch()
                    val done = Projects.rename(act, name, to)
                    if (done == null) toast("Could not rename") else changed()
                }
                1 -> {
                    if (name == Projects.key(act)) beforeSwitch()
                    val copy = Projects.duplicate(act, name)
                    if (copy == null) toast("Could not duplicate") else { toast("Made $copy"); changed() }
                }
                else -> AlertDialog.Builder(act).setTitle("Delete $name?")
                    .setMessage("All files in this project, its chats and its GitHub link on this phone will be deleted. Anything already pushed to GitHub stays there.")
                    .setPositiveButton("Delete") { _, _ ->
                        if (name == Projects.key(act)) beforeSwitch()
                        if (Projects.delete(act, name)) changed() else toast("Could not delete")
                    }.setNegativeButton("Cancel", null).show()
            }
        }.show()
    }

    private fun prompt(act: Activity, title: String, initial: String, ok: String, onOk: (String) -> Unit) {
        val box = android.widget.FrameLayout(act)
        box.setPadding(Ui.dp(act, 20), Ui.dp(act, 8), Ui.dp(act, 20), 0)
        val et = EditText(act)
        et.setText(initial)
        et.setSingleLine()
        et.setSelection(initial.length)
        box.addView(et)
        AlertDialog.Builder(act).setTitle(title).setView(box)
            .setPositiveButton(ok) { _, _ -> onOk(et.text.toString().trim()) }
            .setNegativeButton("Cancel", null).show()
    }

    /** Name + template, then creates the project and hands back its name. */
    fun newProject(act: Activity, created: (String) -> Unit) {
        val box = Ui.vertical(act)
        box.setPadding(Ui.dp(act, 20), Ui.dp(act, 8), Ui.dp(act, 20), 0)
        val name = EditText(act)
        name.hint = "Project name"
        name.setSingleLine()
        name.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        box.addView(name)
        box.addView(Ui.text(act, "Start from", 13f, Ui.MUTED).also { it.setPadding(0, Ui.dp(act, 12), 0, Ui.dp(act, 4)) })
        val group = RadioGroup(act)
        val ids = HashMap<Int, String>()
        for ((i, t) in Templates.all.withIndex()) {
            val rb = RadioButton(act)
            rb.id = 1000 + i
            rb.text = "${t.title} — ${t.description}"
            rb.setTextColor(Ui.TEXT)
            ids[rb.id] = t.id
            group.addView(rb)
        }
        // the person's own templates (a project saved with "Save as a template")
        for ((i, t) in WorkUi.userTemplates(act).withIndex()) {
            val rb = RadioButton(act)
            rb.id = 2000 + i
            rb.text = "$t — your template"
            rb.setTextColor(Ui.TEXT)
            ids[rb.id] = "user:$t"
            group.addView(rb)
        }
        group.check(1000)
        box.addView(group)
        val scroll = ScrollView(act)
        scroll.addView(box)
        val dlg = AlertDialog.Builder(act).setTitle("New project").setView(scroll)
            .setPositiveButton("Create", null).setNegativeButton("Cancel", null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val chosen = ids[group.checkedRadioButtonId] ?: "web"
                val user = chosen.removePrefix("user:").takeIf { chosen.startsWith("user:") }
                val template = if (user != null) Templates.EMPTY else Templates.byId(chosen)
                val wanted = name.text.toString().ifBlank { (user ?: template.title) + " project" }
                if (ProjectNames.sanitize(wanted) == null) { name.error = "Use letters, numbers and spaces"; return@setOnClickListener }
                val made = Projects.create(act, wanted, template)
                if (user != null && !WorkUi.fillFromTemplate(act, user, made)) Toast.makeText(act, "Could not copy the template's files", Toast.LENGTH_SHORT).show()
                dlg.dismiss()
                created(made)
            }
        }
        dlg.show()
    }

    /** owner/repo + branch + optional token, then downloads the branch into a new project. */
    fun clone(act: Activity, created: (String) -> Unit) {
        val box = Ui.vertical(act)
        box.setPadding(Ui.dp(act, 20), Ui.dp(act, 8), Ui.dp(act, 20), 0)
        fun field(hint: String, value: String, type: Int): EditText {
            val e = EditText(act)
            e.hint = hint
            e.setText(value)
            e.setSingleLine()
            e.inputType = type
            box.addView(e)
            return e
        }
        val repoField = field("owner/repository or GitHub URL", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val branchField = field("Branch", "main", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        val tokenField = field("Token (needed for private repos and to push)", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        box.addView(Ui.text(act,
            "Public repositories work without a token, but GitHub limits those downloads to about 60 requests an hour, " +
                "so a big repository may need one. See Help & FAQ → GitHub for making a token.", 12f, Ui.MUTED).also { it.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 8)) })
        val scroll = ScrollView(act)
        scroll.addView(box)
        val dlg = AlertDialog.Builder(act).setTitle("Clone from GitHub").setView(scroll)
            .setPositiveButton("Clone", null).setNegativeButton("Cancel", null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val r = GitRepo.parse(repoField.text.toString())
                val b = GitCore.branch(branchField.text.toString().ifBlank { "main" })
                val t = tokenField.text.toString().trim()
                when {
                    r == null -> repoField.error = "Use owner/repository, for example dbal2969-ai/Test-Coder2.2"
                    b == null -> branchField.error = "Not a valid branch name"
                    t.isNotEmpty() && !GitCore.tokenLooksValid(t) -> tokenField.error = "This does not look like a GitHub token"
                    else -> { dlg.dismiss(); runClone(act, r, b, t, created) }
                }
            }
        }
        dlg.show()
    }

    private fun runClone(act: Activity, repo: GitRepo, branch: String, token: String, created: (String) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val name = ProjectNames.fromRepo(repo.name, Projects.list(act))
        val msg = Ui.text(act, "Starting…", 14f, Ui.TEXT)
        msg.setPadding(Ui.dp(act, 20), Ui.dp(act, 12), Ui.dp(act, 20), Ui.dp(act, 12))
        val progress = AlertDialog.Builder(act).setTitle("Cloning ${repo.identity}").setView(msg).setCancelable(false).create()
        progress.show()
        Thread {
            val made = Projects.create(act, name, Templates.EMPTY)
            Projects.setGitValue(act, "repo", repo.identity, made)
            Projects.setGitValue(act, "branch", branch, made)
            Projects.setGitValue(act, "token", token, made)
            val dir = File(Projects.root(act), made)
            val error: String? = try {
                val sync = GitSync(GitHubHttp(token), FolderFiles(dir), File(Projects.metaDir(act, made), "git"), repo, branch) { s -> main.post { msg.text = s } }
                val out = sync.pull(overwrite = true, replace = true)
                if (!out.applied) "GitHub reported conflicts in a new project, which should not happen." else null
            } catch (e: GitHubException) {
                when (e.code) {
                    404 -> "Could not find ${repo.identity} (branch $branch). Check the name, or add a token if it is private."
                    401, 403 -> if (token.isEmpty()) "GitHub refused the download. The repository may be private, or the hourly limit for downloads without a token was reached. Add a token and try again."
                        else "GitHub refused the token. Check that it can read ${repo.identity}."
                    else -> e.message
                }
            } catch (e: java.net.UnknownHostException) {
                "Could not reach GitHub. Check your internet connection."
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
            main.post {
                try { progress.dismiss() } catch (e: Exception) { /* activity gone */ }
                if (error != null) {
                    Projects.delete(act, made)
                    AlertDialog.Builder(act).setTitle("Clone failed").setMessage(error).setPositiveButton("OK", null).show()
                } else {
                    Toast.makeText(act, "Cloned ${repo.identity} into \"$made\"", Toast.LENGTH_SHORT).show()
                    created(made)
                }
            }
        }.start()
    }
}
