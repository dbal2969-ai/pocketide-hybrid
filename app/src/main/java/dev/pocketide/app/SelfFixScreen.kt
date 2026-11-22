package dev.pocketide.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.ScrollView
import android.widget.Toast
import dev.pocketide.app.core.AppLog
import dev.pocketide.app.core.GitHubHttp
import dev.pocketide.app.core.SelfFix
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * "Something went wrong in the app itself": hand the crash to the assistant, and fetch the APK
 * that GitHub built from the current source so a fix can be installed. The person always taps Install.
 */
object SelfFixScreen {
    private val main = Handler(Looper.getMainLooper())

    fun show(act: Activity, settings: AppSettings, onAsk: (String) -> Unit) {
        val version = try { act.packageManager.getPackageInfo(act.packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }
        val device = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}"
        val crash = try { java.io.File(act.filesDir, "last-crash.txt").takeIf { it.isFile }?.readText() } catch (e: Exception) { null }
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add((if (crash == null) "Ask the assistant about a problem in the app" else "Send the last crash to the assistant") to {
            askForWhatHappened(act) { note ->
                onAsk(SelfFix.report(crash, device, "PocketIDE $version (${act.packageName})", dev.pocketide.app.core.AppLog.format(), note))
            }
        })
        if (crash != null) items.add("Show the last crash" to {
            val tv = Ui.text(act, crash, 11f, Ui.TEXT, true)
            tv.setPadding(Ui.dp(act, 16), Ui.dp(act, 8), Ui.dp(act, 16), Ui.dp(act, 8))
            tv.setTextIsSelectable(true)
            AlertDialog.Builder(act).setTitle("Last crash").setView(ScrollView(act).also { it.addView(tv) })
                .setPositiveButton("Close", null)
                .setNegativeButton("Forget it") { _, _ -> java.io.File(act.filesDir, "last-crash.txt").delete() }.show()
        })
        items.add("Fetch the newest build of PocketIDE…" to { fetchBuild(act, settings, version) })
        AlertDialog.Builder(act).setTitle("Fix the app itself")
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .setNegativeButton("Close", null).show()
    }

    private fun askForWhatHappened(act: Activity, onDone: (String) -> Unit) {
        val et = android.widget.EditText(act).also { it.hint = "What were you doing? (optional)"; it.setSingleLine(false); it.minLines = 2 }
        val box = Ui.vertical(act).also { it.setPadding(Ui.dp(act, 18), Ui.dp(act, 8), Ui.dp(act, 18), 0); it.addView(et) }
        AlertDialog.Builder(act).setTitle("Tell the assistant").setView(box)
            .setPositiveButton("Send") { _, _ -> onDone(et.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }

    /** Finds the newest successful workflow run, downloads its APK artifact and offers to install it. */
    private fun fetchBuild(act: Activity, settings: AppSettings, version: String) {
        val repo = dev.pocketide.app.core.GitRepo.parse(settings.ghRepo)
        if (repo == null || settings.ghToken.isBlank()) {
            AlertDialog.Builder(act).setTitle("Set up GitHub first")
                .setMessage("This reads the builds of the repository that holds PocketIDE's source. Open ⋮ → Git sync and enter the repository and a token with Actions: Read.")
                .setPositiveButton("OK", null).show()
            return
        }
        val api = GitHubHttp(settings.ghToken)
        val slug = repo.owner + "/" + repo.name
        val busy = Toast.makeText(act, "Asking GitHub for the latest build…", Toast.LENGTH_SHORT)
        busy.show()
        Thread {
            val fetched: Pair<SelfFix.Run, ByteArray> = (try {
                val runs = api.call("GET", SelfFix.runsPath(slug))
                if (runs.code !in 200..299) throw IllegalStateException("GitHub answered ${runs.code} for the list of builds. A token with Actions: Read is needed.")
                val run = SelfFix.newestGood(SelfFix.parseRuns(String(runs.body, Charsets.UTF_8)))
                    ?: throw IllegalStateException("No finished, successful build was found for $slug.")
                val arts = api.call("GET", SelfFix.artifactsPath(slug, run.id))
                if (arts.code !in 200..299) throw IllegalStateException("GitHub answered ${arts.code} for that build's files.")
                val apk = SelfFix.pickApk(SelfFix.parseArtifacts(String(arts.body, Charsets.UTF_8)))
                    ?: throw IllegalStateException("That build kept no APK (artifacts expire after a while).")
                val zip = api.call("GET", SelfFix.downloadPath(slug, apk.id), raw = true)
                if (zip.code !in 200..299) throw IllegalStateException("The download answered ${zip.code}.")
                run to zip.body
            } catch (e: Throwable) {
                AppLog.add("selffix", e.message ?: e.javaClass.simpleName)
                main.post {
                    AlertDialog.Builder(act).setTitle("Could not fetch the build")
                        .setMessage(e.message ?: "It did not work.").setPositiveButton("OK", null).show()
                }
                null
            }) ?: return@Thread
            val run = fetched.first
            val zipBytes = fetched.second
            val apkBytes = try { apkFromZip(zipBytes) } catch (e: Throwable) { null }
            main.post {
                if (apkBytes == null) {
                    AlertDialog.Builder(act).setTitle("No APK inside")
                        .setMessage("The build's file did not contain an .apk.").setPositiveButton("OK", null).show()
                    return@post
                }
                AlertDialog.Builder(act).setTitle("Install this build?")
                    .setMessage(SelfFix.describe(run, version) + "\n\n" + (apkBytes.size / 1024 / 1024) + " MB.\n\n" + SelfFix.INSTALL_NOTE)
                    .setPositiveButton("Save and install") { _, _ -> saveAndInstall(act, apkBytes, SelfFix.fileName(version, run.head)) }
                    .setNeutralButton("Just save it") { _, _ -> saveAndInstall(act, apkBytes, SelfFix.fileName(version, run.head), install = false) }
                    .setNegativeButton("Cancel", null).show()
            }
        }.start()
    }

    fun apkFromZip(zip: ByteArray): ByteArray? {   // also the Linux add-on download (LinuxAddon, 1.5.128)
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (!e.isDirectory) names.add(e.name) }
        }
        val want = SelfFix.apkEntry(names) ?: return null
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == want) return z.readBytes()
            }
        }
        return null
    }

    /** Writes the APK into Downloads (no permission needed) and hands it to Android's installer. */
    fun saveAndInstall(act: Activity, apk: ByteArray, name: String, install: Boolean = true) {
        val uri: Uri? = try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val u = act.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (u != null) {
                    act.contentResolver.openOutputStream(u)?.use { it.write(apk) }
                    values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                    act.contentResolver.update(u, values, null, null)
                }
                u
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val f = java.io.File(dir, name)
                f.writeBytes(apk)
                Uri.fromFile(f)
            }
        } catch (e: Throwable) { AppLog.add("selffix", "save failed: " + (e.message ?: "")); null }
        if (uri == null) {
            AlertDialog.Builder(act).setTitle("Could not save it")
                .setMessage("The APK could not be written to Downloads.").setPositiveButton("OK", null).show()
            return
        }
        if (!install) { Toast.makeText(act, "Saved $name to Downloads.", Toast.LENGTH_LONG).show(); return }
        try {
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, "application/vnd.android.package-archive")
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            act.startActivity(i)
        } catch (e: Throwable) {
            AlertDialog.Builder(act).setTitle("Saved, but not opened")
                .setMessage("$name is in your Downloads folder. Open it there to install it." +
                    (if (Build.VERSION.SDK_INT >= 26) " Android may first ask you to allow installing apps from PocketIDE." else ""))
                .setPositiveButton("OK", null).show()
        }
    }
}
