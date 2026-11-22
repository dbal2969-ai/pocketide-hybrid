package dev.pocketide.app

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * A panel that slides in from the left over the screen's content (v0.4 W1). Tapping outside closes it.
 */
class Drawer(ctx: Context, private val panel: View) : FrameLayout(ctx) {
    private val scrim = View(ctx)
    val isOpen: Boolean get() = visibility == View.VISIBLE

    init {
        visibility = View.GONE
        scrim.setBackgroundColor(0x88000000.toInt())
        scrim.setOnClickListener { close() }
        addView(scrim, LayoutParams(Ui.MATCH, Ui.MATCH))
        val w = minOf(Ui.dp(ctx, 330), (ctx.resources.displayMetrics.widthPixels * 0.86f).toInt())
        addView(panel, LayoutParams(w, Ui.MATCH, Gravity.START))
        panel.isClickable = true  // taps on the panel don't fall through to the scrim
    }

    fun open() {
        if (isOpen) return
        visibility = View.VISIBLE
        panel.translationX = -panel.layoutParams.width.toFloat()
        panel.animate().translationX(0f).setDuration(160).start()
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(160).start()
    }

    fun close() {
        if (!isOpen) return
        panel.animate().translationX(-panel.width.toFloat()).setDuration(140).withEndAction { visibility = View.GONE }.start()
        scrim.animate().alpha(0f).setDuration(140).start()
    }

    fun toggle() = if (isOpen) close() else open()
}

/**
 * The project's folders and files as a collapsible tree. Long-press a row for New / Rename / Move / Delete.
 * The host reacts to changes through the callbacks (open the file, fix tabs, refresh the preview).
 */
class FileTreePanel(private val ctx: Context, private val store: ProjectStore) : LinearLayout(ctx) {
    private val title = Ui.text(ctx, "", 15f, Ui.TEXT)
    private val list = Ui.vertical(ctx)
    private val expanded = HashSet<String>()
    private var selected = ""

    var onOpen: (String) -> Unit = {}
    var onCreated: (String) -> Unit = {}
    /** A file or folder was deleted (path of what was removed). */
    var onDeleted: (String) -> Unit = {}
    var onMoved: (from: String, to: String) -> Unit = { _, _ -> }
    var onClose: () -> Unit = {}
    /** Called after the list was rebuilt (something was created, moved or deleted), so other screens can refresh. */
    var onRefreshed: () -> Unit = {}
    /** Save a copy of a file to the phone (the host shows the system save dialog). */
    var onSaveCopy: (String) -> Unit = {}
    /** A file's history (blame = false) or who changed each line (blame = true), when the project keeps history. */
    var onHistory: (String, Boolean) -> Unit = { _, _ -> }
    /** Save a folder as a zip. */
    var onSaveFolder: (String) -> Unit = {}
    /** Add files from the phone into a folder ("" for the project root). */
    var onAddFiles: (String) -> Unit = {}
    /** Called before changing files, so unsaved editor text is written first. */
    var beforeChange: () -> Unit = {}

    init {
        orientation = VERTICAL
        setBackgroundColor(Ui.SURFACE)
        val head = Ui.horizontal(ctx)
        head.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4))
        title.typeface = android.graphics.Typeface.DEFAULT_BOLD
        title.maxLines = 1
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        head.addView(title, Ui.lp(0, Ui.WRAP, 1f))
        head.addView(headButton("✕") { onClose() })
        addView(head, Ui.lp(Ui.MATCH, Ui.WRAP))
        val actions = Ui.horizontal(ctx)
        actions.setPadding(Ui.dp(ctx, 10), 0, Ui.dp(ctx, 10), Ui.dp(ctx, 6))
        actions.addView(Ui.chip(ctx, "+ File") { newFile("") })
        actions.addView(Ui.chip(ctx, "+ Folder") { newFolder("") }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 6) })
        actions.addView(Ui.chip(ctx, "+ Add files") { onAddFiles("") }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 6) })
        actions.addView(Ui.chip(ctx, "Collapse") { expanded.clear(); refresh() }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 6) })
        val actionScroll = android.widget.HorizontalScrollView(ctx)
        actionScroll.isHorizontalScrollBarEnabled = false
        actionScroll.addView(actions)
        addView(actionScroll, Ui.lp(Ui.MATCH, Ui.WRAP))
        val sc = ScrollView(ctx)
        sc.addView(list)
        addView(sc, Ui.lp(Ui.MATCH, 0, 1f))
        addView(Ui.text(ctx, "Tap to open · long-press for more", 11f, Ui.MUTED).also {
            it.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 6), Ui.dp(ctx, 14), Ui.dp(ctx, 10))
        })
    }

    private fun headButton(label: String, onClick: () -> Unit): TextView {
        val t = Ui.text(ctx, label, 15f, Ui.MUTED)
        t.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 10), Ui.dp(ctx, 12), Ui.dp(ctx, 10))
        t.setOnClickListener { onClick() }
        t.minHeight = Ui.dp(ctx, 48)
        Ui.describe(t, label)
        return t
    }

    private fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

    /** Marks the open file and expands the folders above it. */
    fun select(path: String) {
        selected = path
        var p = path.substringBeforeLast('/', "")
        while (p.isNotEmpty()) { expanded.add(p); p = p.substringBeforeLast('/', "") }
    }

    /** Forgets expanded folders, for a project switch. */
    fun reset() { expanded.clear(); selected = "" }

    fun refresh() {
        title.text = store.root.name
        list.removeAllViews()
        val dirs = store.listDirs()
        val files = store.list()
        // children per folder ("" is the project root)
        val kids = HashMap<String, MutableList<Pair<String, Boolean>>>()
        for (d in dirs) kids.getOrPut(d.substringBeforeLast('/', "")) { ArrayList() }.add(d to true)
        for (f in files) kids.getOrPut(f.substringBeforeLast('/', "")) { ArrayList() }.add(f to false)
        fun add(parent: String, depth: Int) {
            val items = kids[parent] ?: return
            // folders first, then files, each alphabetically
            for ((p, isDir) in items.sortedWith(compareBy<Pair<String, Boolean>>({ !it.second }, { it.first.substringAfterLast('/').lowercase() }))) {
                list.addView(row(p, isDir, depth))
                if (isDir && p in expanded) add(p, depth + 1)
            }
        }
        add("", 0)
        if (files.isEmpty() && dirs.isEmpty()) {
            list.addView(Ui.text(ctx, "No files yet. Tap + File to start.", 13f, Ui.MUTED).also {
                it.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 16), Ui.dp(ctx, 16), Ui.dp(ctx, 16))
            })
        }
        onRefreshed()
    }

    private fun row(path: String, isDir: Boolean, depth: Int): View {
        val name = path.substringAfterLast('/')
        val open = path in expanded
        val label = if (isDir) (if (open) "▾  " else "▸  ") + name else "    " + name
        val t = Ui.text(ctx, label, 14f, when {
            path == selected -> Ui.ACCENT
            isDir -> Ui.TEXT
            store.isBinary(path) -> Ui.MUTED
            else -> Ui.TEXT
        }, !isDir)
        if (isDir) t.typeface = android.graphics.Typeface.DEFAULT_BOLD
        t.maxLines = 1
        t.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        t.setPadding(Ui.dp(ctx, 14 + depth * 16), Ui.dp(ctx, 9), Ui.dp(ctx, 12), Ui.dp(ctx, 9))
        if (path == selected) t.setBackgroundColor(Ui.SURFACE2)
        t.setOnClickListener {
            if (isDir) { if (open) expanded.remove(path) else expanded.add(path); refresh() }
            else if (path.endsWith(".zip", ignoreCase = true)) zipChoice(path)
            else { selected = path; onOpen(path); refresh() }
        }
        t.setOnLongClickListener { if (isDir) folderActions(path) else fileActions(path); true }
        return t
    }

    // ---- actions ----

    private fun prompt(title: String, initial: String, ok: String, hint: String = "", onOk: (String) -> Unit) {
        val box = FrameLayout(ctx)
        box.setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 8), Ui.dp(ctx, 20), 0)
        val et = EditText(ctx)
        et.setText(initial)
        et.hint = hint
        et.setSingleLine()
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        et.setSelection(initial.length)
        box.addView(et)
        AlertDialog.Builder(ctx).setTitle(title).setView(box)
            .setPositiveButton(ok) { _, _ -> onOk(et.text.toString().trim().removePrefix("./").trim('/')) }
            .setNegativeButton("Cancel", null).show()
        et.requestFocus()
    }

    private fun inFolder(folder: String, name: String) = if (folder.isEmpty()) name else "$folder/$name"

    fun newFile(folder: String) {
        prompt(if (folder.isEmpty()) "New file" else "New file in $folder", "", "Create", "e.g. app.js or src/main.py") { name ->
            if (name.isEmpty()) return@prompt
            val p = inFolder(folder, name)
            when {
                store.exists(p) -> toast("That file already exists")
                store.isDir(p) -> toast("A folder has that name")
                !store.write(p, "") -> toast("Not a valid file name")
                else -> { if (folder.isNotEmpty()) expanded.add(folder); select(p); refresh(); onCreated(p); onOpen(p) }
            }
        }
    }

    fun newFolder(folder: String) {
        prompt(if (folder.isEmpty()) "New folder" else "New folder in $folder", "", "Create", "e.g. src") { name ->
            if (name.isEmpty()) return@prompt
            val p = inFolder(folder, name)
            if (store.exists(p) || store.isDir(p)) { toast("That name is taken"); return@prompt }
            if (!store.mkdir(p)) { toast("Not a valid folder name"); return@prompt }
            if (folder.isNotEmpty()) expanded.add(folder)
            expanded.add(p)
            refresh()
        }
    }

    /** Tapping a zip file: unpack it into a folder next to it, or open it as a file. */
    private fun zipChoice(path: String) {
        val folder = path.substringBeforeLast('.')
        AlertDialog.Builder(ctx).setTitle(path.substringAfterLast('/'))
            .setMessage("Unpack this zip into \"$folder/\"? Files with the same names there are replaced.")
            .setPositiveButton("Unzip") { _, _ -> unzip(path) }
            .setNeutralButton("Open as file") { _, _ -> selected = path; onOpen(path); refresh() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun unzip(path: String) {
        beforeChange()
        toast("Unzipping…")
        Thread {
            val n = store.unzip(path)
            (ctx as? android.app.Activity)?.runOnUiThread {
                when {
                    n >= 0 -> { expanded.add(path.substringBeforeLast('.')); refresh(); toast("Unpacked $n file${if (n == 1) "" else "s"}") }
                    n == -1 -> toast("The zip is too big to unpack (over the size limit)")
                    n == -2 -> toast("The zip has too many files to unpack")
                    else -> toast("Could not read this zip")
                }
            }
        }.start()
    }

    fun fileActions(path: String) {
        val zip = path.endsWith(".zip", ignoreCase = true)
        val history = java.io.File(store.root, ".git/HEAD").isFile
        val labels = listOfNotNull("Open", if (zip) "Unzip here" else null, if (history) "History of this file" else null, if (history) "Who changed each line" else null,
            "Rename", "Move to folder…", "Duplicate", "Save a copy to phone…", "Delete")
        AlertDialog.Builder(ctx).setTitle(path).setItems(labels.toTypedArray()) { _, i ->
            when (labels[i]) {
                "Open" -> { selected = path; onOpen(path); refresh() }
                "Unzip here" -> unzip(path)
                "History of this file" -> { beforeChange(); onHistory(path, false) }
                "Who changed each line" -> { beforeChange(); onHistory(path, true) }
                "Rename" -> rename(path, false)
                "Move to folder…" -> move(path, false)
                "Duplicate" -> duplicate(path)
                "Save a copy to phone…" -> { beforeChange(); onSaveCopy(path) }
                else -> AlertDialog.Builder(ctx).setTitle("Delete $path?").setMessage("This cannot be undone.")
                    .setPositiveButton("Delete") { _, _ ->
                        beforeChange()
                        if (store.delete(path)) { refresh(); onDeleted(path) } else toast("Could not delete")
                    }.setNegativeButton("Cancel", null).show()
            }
        }.show()
    }

    fun folderActions(path: String) {
        AlertDialog.Builder(ctx).setTitle("$path/").setItems(arrayOf("New file here", "New folder here", "Add files here…", "Rename", "Move to folder…", "Save folder as zip…", "Delete folder")) { _, i ->
            when (i) {
                0 -> newFile(path)
                1 -> newFolder(path)
                2 -> onAddFiles(path)
                3 -> rename(path, true)
                4 -> move(path, true)
                5 -> { beforeChange(); onSaveFolder(path) }
                else -> {
                    val n = store.list().count { it.startsWith("$path/") }
                    AlertDialog.Builder(ctx).setTitle("Delete $path/?")
                        .setMessage(if (n == 0) "The folder is empty." else "This deletes $n file${if (n == 1) "" else "s"} inside it. This cannot be undone.")
                        .setPositiveButton("Delete") { _, _ ->
                            beforeChange()
                            store.deleteDir(path)
                            expanded.removeAll { it == path || it.startsWith("$path/") }
                            refresh()
                            onDeleted(path)
                        }.setNegativeButton("Cancel", null).show()
                }
            }
        }.show()
    }

    private fun rename(path: String, isDir: Boolean) {
        val folder = path.substringBeforeLast('/', "")
        prompt("Rename", path.substringAfterLast('/'), "Rename") { name ->
            if (name.isEmpty() || name.contains('/')) { if (name.contains('/')) toast("Use Move to put it in another folder"); return@prompt }
            val to = inFolder(folder, name)
            if (to == path) return@prompt
            doMove(path, to, isDir)
        }
    }

    private fun move(path: String, isDir: Boolean) {
        val name = path.substringAfterLast('/')
        val choices = listOf("(project root)") + store.listDirs().filter { !(isDir && (it == path || it.startsWith("$path/"))) }
        AlertDialog.Builder(ctx).setTitle("Move $name to").setItems(choices.toTypedArray()) { _, i ->
            val dest = if (i == 0) name else "${choices[i]}/$name"
            if (dest != path) doMove(path, dest, isDir)
        }.setNegativeButton("Cancel", null).show()
    }

    private fun doMove(from: String, to: String, isDir: Boolean, replace: Boolean = false) {
        // 1.5.117: a file already there is offered for replacing, instead of the move just failing
        if (!replace && store.exists(to)) {
            val name = to.substringAfterLast('/'); val where = to.substringBeforeLast('/', "").ifEmpty { "the project root" }
            if (isDir) { toast("A file named $name is already in $where"); return }
            AlertDialog.Builder(ctx).setTitle("Replace $name?")
                .setMessage("$where already has a file named $name. Replace it with ${from}? The file that is there now will be gone.")
                .setPositiveButton("Replace") { _, _ -> doMove(from, to, isDir, true) }
                .setNegativeButton("Cancel", null).show()
            return
        }
        if (store.isDir(to)) { toast("A folder named ${to.substringAfterLast('/')} is already there"); return }
        beforeChange()
        if (!(if (replace) store.replace(from, to) else store.move(from, to))) { toast("Could not move: the name is not valid"); return }
        if (isDir && from in expanded) { expanded.remove(from); expanded.add(to) }
        val parent = to.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) expanded.add(parent)
        if (selected == from) selected = to
        refresh()
        onMoved(from, to)
    }

    private fun duplicate(path: String) {
        val folder = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        var to: String
        do { to = inFolder(folder, "$stem copy${if (n > 1) " $n" else ""}$ext"); n++ } while (store.exists(to))
        beforeChange()
        val bytes = store.readBytes(path)
        if (bytes == null || !store.writeBytes(to, bytes)) { toast("Could not duplicate"); return }
        refresh()
        onCreated(to)
    }
}
