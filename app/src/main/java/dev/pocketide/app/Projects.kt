package dev.pocketide.app

import android.content.Context
import dev.pocketide.app.core.ProjectNames
import dev.pocketide.app.core.Template
import dev.pocketide.app.core.Templates
import java.io.File
import java.util.Properties

/**
 * Several projects, each a real folder under filesDir/projects/<name>/ (v0.4 W1).
 *
 * Per-project app data that must not end up in the project itself (chat history, checkpoints,
 * GitHub sync state and settings, open tabs) lives in filesDir/meta/<name>/, see metaDir.
 * The open project is remembered in SharedPreferences. Everything runs on the main thread except
 * where noted; the file work is small.
 */
object Projects {
    private const val PREFS = "pocketide"
    private const val CURRENT = "project.current"
    private const val MIGRATED = "projects.migrated"

    /** Called on the main thread after the open project changes, with the new project's name. */
    val listeners = ArrayList<(String) -> Unit>()

    fun root(ctx: Context): File = File(ctx.filesDir, "projects")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The open project's folder. Migrates the pre-0.4 single project and creates a first project if needed. */
    @Synchronized
    fun current(ctx: Context): File {
        migrate(ctx)
        val root = root(ctx).also { it.mkdirs() }
        val name = prefs(ctx).getString(CURRENT, null)
        if (name != null) {
            val dir = File(root, name)
            if (dir.isDirectory) return dir
        }
        val first = list(ctx).firstOrNull() ?: createFiles(ctx, ProjectNames.DEFAULT, Templates.WEB).name
        prefs(ctx).edit().putString(CURRENT, first).apply()
        return File(root, first)
    }

    /** The open project's name; also the key for its metaDir. */
    fun key(ctx: Context): String = current(ctx).name

    /** filesDir/meta/<key>/: per-project app data (chats, checkpoints, git sync state). Never inside the project folder. */
    fun metaDir(ctx: Context, key: String = key(ctx)): File = File(File(ctx.filesDir, "meta"), key).also { it.mkdirs() }

    /** The Chat home's hidden folder (ChatHome, 1.5.125): a project with no files whose chats only talk. The dot keeps it out of [list]. */
    const val CHAT = ".chat"

    fun isChat(ctx: Context): Boolean = key(ctx) == CHAT

    /** Opens the Chat home, making its folder the first time. */
    fun openChat(ctx: Context): Boolean { File(root(ctx), CHAT).mkdirs(); return switchTo(ctx, CHAT) }

    /** What to show for a project's name: the Chat home has a name of its own. */
    fun label(name: String): String = dev.pocketide.app.core.ChatHome.projectLabel(name, CHAT)

    /** Every saved chat of every project, the Chat home included: (project, chat). For searching all chats. */
    fun allChats(ctx: Context): List<Pair<String, dev.pocketide.app.core.ChatSession>> {
        dev.pocketide.app.core.ChatWriter.flush(3000)   // chats are saved on a background thread (1.5.136)
        return (File(ctx.filesDir, "meta").listFiles() ?: emptyArray()).filter { it.isDirectory && (it.name == CHAT || File(root(ctx), it.name).isDirectory) }.flatMap { m ->
            (File(m, "chats").listFiles() ?: emptyArray()).filter { it.name.endsWith(".json") }.mapNotNull { f ->
                try { dev.pocketide.app.core.ChatSession.fromJson(f.readText())?.let { m.name to it } } catch (e: Exception) { null }
            }
        }
    }

    /** Project names, case-insensitively sorted. */
    fun list(ctx: Context): List<String> =
        (root(ctx).listFiles() ?: emptyArray()).filter { it.isDirectory && !it.name.startsWith(".") }
            .map { it.name }.sortedWith(String.CASE_INSENSITIVE_ORDER)

    /** Number of files in a project, for the picker. Stops counting at 999. */
    fun fileCount(ctx: Context, name: String): Int {
        var n = 0
        fun walk(d: File) {
            for (k in d.listFiles() ?: return) {
                if (n >= 999) return
                if (k.name.startsWith(".")) continue
                if (k.isDirectory) walk(k) else n++
            }
        }
        walk(File(root(ctx), name))
        return n
    }

    /** When any file in the project last changed (milliseconds), or 0. */
    fun modified(ctx: Context, name: String): Long {
        var newest = 0L
        var seen = 0
        fun walk(d: File) {
            for (k in d.listFiles() ?: return) {
                if (seen++ > 3000) return
                if (k.name.startsWith(".")) continue
                if (k.isDirectory) walk(k) else newest = maxOf(newest, k.lastModified())
            }
        }
        walk(File(root(ctx), name))
        return newest
    }

    /** The first sentence of the project's README, for the repositories list. */
    fun description(ctx: Context, name: String): String {
        val dir = File(root(ctx), name)
        val readme = dev.pocketide.app.core.RepoBrowser.findReadme((dir.listFiles() ?: emptyArray()).filter { it.isFile }.map { it.name }, "") ?: return ""
        return try { dev.pocketide.app.core.Markdown.describe(File(dir, readme).readText().take(8000)) } catch (e: Exception) { "" }
    }

    private fun createFiles(ctx: Context, wanted: String, template: Template): File {
        val name = ProjectNames.unique(ProjectNames.sanitize(wanted) ?: ProjectNames.DEFAULT, list(ctx))
        val dir = File(root(ctx), name)
        dir.mkdirs()
        for ((p, text) in Templates.render(template, name)) {
            val f = File(dir, p)
            f.parentFile?.mkdirs()
            f.writeText(text, Charsets.UTF_8)
        }
        // bundled engine files (a game template): copied from the app so the project works offline
        for ((dest, asset) in template.assets) {
            val f = File(dir, dest)
            f.parentFile?.mkdirs()
            try { ctx.assets.open(asset).use { ins -> f.outputStream().use { ins.copyTo(it) } } } catch (e: Exception) { }
        }
        return dir
    }

    /** Creates a project from a template. Returns its (possibly adjusted) name. Does not switch to it. */
    fun create(ctx: Context, name: String, template: Template): String = createFiles(ctx, name, template).name

    /** Renames a project and its app data. Returns the new name, or null if it could not be renamed. */
    fun rename(ctx: Context, from: String, to: String): String? {
        val clean = ProjectNames.sanitize(to) ?: return null
        if (clean == from) return from
        val others = list(ctx).filter { !it.equals(from, ignoreCase = true) }
        val name = ProjectNames.unique(clean, others)
        val a = File(root(ctx), from)
        val b = File(root(ctx), name)
        if (!a.isDirectory) return null
        if (!from.equals(name, ignoreCase = true) && b.exists()) return null
        if (!a.renameTo(b)) return null
        val ma = File(File(ctx.filesDir, "meta"), from)
        if (ma.isDirectory) ma.renameTo(File(File(ctx.filesDir, "meta"), name))
        if (prefs(ctx).getString(CURRENT, null) == from) {
            prefs(ctx).edit().putString(CURRENT, name).apply()
            fire(name)
        }
        return name
    }

    /** Copies a project (not its chats or sync state). Returns the copy's name. */
    fun duplicate(ctx: Context, name: String): String? {
        val src = File(root(ctx), name)
        if (!src.isDirectory) return null
        val copy = ProjectNames.copyName(name, list(ctx))
        return if (src.copyRecursively(File(root(ctx), copy), overwrite = false)) copy else null
    }

    /** Deletes a project and its app data. If it was open, another project (or a new one) is opened. */
    fun delete(ctx: Context, name: String): Boolean {
        val dir = File(root(ctx), name)
        if (!dir.isDirectory) return false
        val ok = dir.deleteRecursively()
        File(File(ctx.filesDir, "meta"), name).deleteRecursively()
        if (prefs(ctx).getString(CURRENT, null) == name) {
            prefs(ctx).edit().remove(CURRENT).apply()
            fire(current(ctx).name)
        }
        return ok
    }

    /** Opens another project. Listeners run afterwards. Returns false if there is no such project. */
    fun switchTo(ctx: Context, name: String): Boolean {
        if (!File(root(ctx), name).isDirectory) return false
        prefs(ctx).edit().putString(CURRENT, name).apply()
        fire(name)
        return true
    }

    private fun fire(name: String) { for (l in listeners.toList()) l(name) }

    // ---- per-project GitHub settings, kept in metaDir so they never reach the repository ----

    private fun gitConfigFile(ctx: Context, key: String) = File(metaDir(ctx, key), "github.properties")

    fun gitConfig(ctx: Context, key: String = key(ctx)): Properties {
        val p = Properties()
        val f = gitConfigFile(ctx, key)
        if (f.isFile) try { f.inputStream().use { p.load(it) } } catch (e: Exception) { /* start empty */ }
        return p
    }

    fun setGitValue(ctx: Context, field: String, value: String, key: String = key(ctx)) {
        val p = gitConfig(ctx, key)
        // the token is sealed wherever it is written (new projects, clones, the old migration), not only from Settings (1.5.134)
        p.setProperty(field, if (field == "token") dev.pocketide.app.core.Vault.seal(value) else value)
        val f = gitConfigFile(ctx, key)
        val tmp = File(f.path + ".tmp")
        tmp.outputStream().use { p.store(it, "PocketIDE GitHub settings for this project") }
        tmp.renameTo(f)
    }

    // ---- one-time move from the single pre-0.4 project ----

    private fun migrate(ctx: Context) {
        val sp = prefs(ctx)
        if (sp.getBoolean(MIGRATED, false)) return
        val root = root(ctx).also { it.mkdirs() }
        val old = File(ctx.filesDir, "project")
        if (old.isDirectory && (old.list()?.isNotEmpty() == true)) {
            val name = ProjectNames.unique(ProjectNames.DEFAULT, list(ctx))
            val dest = File(root, name)
            if (!old.renameTo(dest)) { old.copyRecursively(dest, overwrite = false); old.deleteRecursively() }
            val meta = metaDir(ctx, name)
            val oldGit = File(ctx.filesDir, "git")
            if (oldGit.isDirectory && !oldGit.renameTo(File(meta, "git"))) {
                oldGit.copyRecursively(File(meta, "git"), overwrite = false); oldGit.deleteRecursively()
            }
            // the old app-wide GitHub settings become this project's
            val repo = sp.getString("gh.repo", "") ?: ""
            if (repo.isNotEmpty()) {
                setGitValue(ctx, "repo", repo, name)
                setGitValue(ctx, "branch", sp.getString("gh.branch", "main") ?: "main", name)
                setGitValue(ctx, "token", sp.getString("gh.token", "") ?: "", name)
            }
            sp.edit().putString(CURRENT, name).apply()
        } else if (old.isDirectory) {
            old.delete()
        }
        sp.edit().putBoolean(MIGRATED, true).apply()
    }
}
