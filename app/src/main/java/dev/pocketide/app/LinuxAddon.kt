package dev.pocketide.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dev.pocketide.app.core.AppLog
import dev.pocketide.app.core.GitHubHttp
import dev.pocketide.app.core.SelfFix
import java.io.File

/**
 * Where PRoot comes from (1.5.128). The GPL edition carries PRoot, its loader and talloc inside its own APK. The MIT edition
 * carries no GPL code, so it gets them from the "PocketIDE Linux add-on": a separate, code-free GPL app (dev.pocketide.linux,
 * built from linux-addon/ in the GPL repository) that holds only those three files. Android 10+ lets an app run programs only
 * from an installed APK's library folder, so the add-on's folder is used directly; an emulator test showed one app may run
 * another's PRoot.
 *
 * The add-on is used only when it is signed with the same key as this app, so no other app installed under that name can put
 * programs into PocketIDE's Linux.
 */
object LinuxAddon {
    const val PACKAGE = "dev.pocketide.linux"
    /** The add-on's artifact in the GPL repository's "Build Android APK" runs (no "apk" in the name, so self-fix never picks it). */
    const val ARTIFACT = SelfFix.ADDON_ARTIFACT
    const val REPO = "dbal2969-ai/Test-Coder2.3"
    /** The public home of the add-on (1.5.130): its newest release is downloaded without a GitHub token. */
    const val PUBLIC_REPO = "dbal2969-ai/pocketide-linux-addon"
    const val PUBLIC_URL = "https://github.com/$PUBLIC_REPO/releases/latest/download/PocketIDE-Linux-addon.apk"
    private val FILES = listOf("libproot.so", "libproot-loader.so", "libtalloc.so")
    private val main = Handler(Looper.getMainLooper())

    private fun complete(dir: File) = FILES.all { File(dir, it).isFile }

    /** This app carries PRoot itself (the GPL edition). */
    fun builtIn(ctx: Context): Boolean = complete(File(ctx.applicationInfo.nativeLibraryDir))

    /** The folder PRoot runs from: this app's own, else the add-on's when it is installed and signed like this app. */
    fun libDir(ctx: Context): File {
        val own = File(ctx.applicationInfo.nativeLibraryDir)
        if (complete(own)) return own
        return addonDir(ctx) ?: own
    }

    @Suppress("DEPRECATION")
    fun addonDir(ctx: Context): File? = try {
        val pm = ctx.packageManager
        if (pm.checkSignatures(ctx.packageName, PACKAGE) != PackageManager.SIGNATURE_MATCH) null
        else File(pm.getApplicationInfo(PACKAGE, 0).nativeLibraryDir).takeIf { complete(it) }
    } catch (e: Exception) { null }

    /** Installed at all (maybe signed by someone else, which status() reports). */
    fun installed(ctx: Context): Boolean = try { ctx.packageManager.getPackageInfo(PACKAGE, 0); true } catch (e: Exception) { false }

    /** Why Linux cannot start yet, when PRoot is missing. */
    fun missingNote(ctx: Context): String = when {
        installed(ctx) && addonDir(ctx) == null -> "The installed Linux add-on is not signed like this app, so it is not used. Uninstall it and get it again here."
        else -> "Linux needs the PocketIDE Linux add-on: a small separate app (about 1 MB, GPL) holding PRoot, which runs Linux. " +
            "Tap Get the Linux add-on, then Install when Android asks. After that, Install Linux downloads Alpine as usual."
    }

    /** Downloads the newest public release of the add-on (no token), else, with a GitHub token, the newest build of the GPL
     *  repository, and hands it to Android's installer. Wherever it comes from, libDir() uses it only when it is signed with
     *  this app's key, so a public download cannot put someone else's programs into PocketIDE's Linux. */
    fun fetch(act: Activity, settings: AppSettings) {
        Toast.makeText(act, "Downloading the Linux add-on…", Toast.LENGTH_SHORT).show()
        Thread {
            var why = ""
            var apk: ByteArray? = try { publicApk() } catch (e: Throwable) {
                why = e.message ?: e.javaClass.simpleName
                AppLog.add("linux-addon", "public release: $why")
                null
            }
            if (apk == null && settings.ghToken.isNotBlank()) apk = try { fromBuilds(settings) } catch (e: Throwable) {
                AppLog.add("linux-addon", "builds of $REPO: ${e.message ?: e.javaClass.simpleName}")
                null
            }
            if (apk == null) main.post {
                AlertDialog.Builder(act).setTitle("Could not get the add-on")
                    .setMessage("The download from github.com/$PUBLIC_REPO did not work ($why). Check the connection and try again.")
                    .setPositiveButton("OK", null).show()
            } else main.post {
                AlertDialog.Builder(act).setTitle("Install the Linux add-on?")
                    .setMessage("PocketIDE Linux add-on, ${maxOf(1, apk.size / 1024 / 1024)} MB. It holds PRoot (GPL-2.0), its loader and talloc " +
                        "(LGPL-3.0), and no code of its own; their source is at github.com/$PUBLIC_REPO. Android will ask you to allow the " +
                        "install. Come back here afterwards and tap Install Linux.")
                    .setPositiveButton("Install") { _, _ -> SelfFixScreen.saveAndInstall(act, apk, "PocketIDE-Linux-addon.apk") }
                    .setNegativeButton("Cancel", null).show()
            }
        }.start()
    }

    private const val MAX_BYTES = 30 * 1024 * 1024

    /** The newest public release, following GitHub's redirect to its download host (https only). */
    private fun publicApk(): ByteArray {
        var u = java.net.URL(PUBLIC_URL)
        repeat(6) {
            val c = u.openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 20_000; c.readTimeout = 60_000; c.instanceFollowRedirects = false
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IllegalStateException("bad redirect")
                c.disconnect(); u = java.net.URL(u, loc)
                if (u.protocol != "https") throw IllegalStateException("refused a redirect to $u")
                return@repeat
            }
            if (code != 200) { c.disconnect(); throw IllegalStateException("GitHub answered $code") }
            return c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(64 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > MAX_BYTES) throw IllegalStateException("the file is too big") }
                out.toByteArray()
            }
        }
        throw IllegalStateException("too many redirects")
    }

    /** The newest successful build of the GPL repository (needs a token that can read it). */
    private fun fromBuilds(settings: AppSettings): ByteArray {
        val api = GitHubHttp(settings.ghToken)
        val runs = api.call("GET", SelfFix.buildRunsPath(REPO))
        if (runs.code !in 200..299) throw IllegalStateException("GitHub answered ${runs.code} for $REPO's builds")
        val run = SelfFix.newestGood(SelfFix.parseRuns(String(runs.body, Charsets.UTF_8)))
            ?: throw IllegalStateException("no successful build of $REPO was found")
        val arts = api.call("GET", SelfFix.artifactsPath(REPO, run.id))
        if (arts.code !in 200..299) throw IllegalStateException("GitHub answered ${arts.code} for that build's files")
        val art = SelfFix.pickAddon(SelfFix.parseArtifacts(String(arts.body, Charsets.UTF_8)))
            ?: throw IllegalStateException("that build has no Linux add-on")
        val zip = api.call("GET", SelfFix.downloadPath(REPO, art.id), raw = true)
        if (zip.code !in 200..299) throw IllegalStateException("the download answered ${zip.code}")
        return SelfFixScreen.apkFromZip(zip.body) ?: throw IllegalStateException("the download held no .apk")
    }
}
