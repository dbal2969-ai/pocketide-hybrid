package dev.pocketide.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.pocketide.app.core.CountingInput
import dev.pocketide.app.core.LinuxDistro
import dev.pocketide.app.core.ProotCommand
import dev.pocketide.app.core.RootfsExtractor
import dev.pocketide.app.core.Sha256
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * GPL route: a real Linux userland (Alpine) run through PRoot.
 *
 * PRoot ships inside the APK as libproot.so so that Android extracts it to nativeLibraryDir,
 * the only app folder an app targeting API 29+ may execute files from. The Alpine rootfs is
 * downloaded on the phone, checked against a pinned SHA-256 and unpacked under filesDir/linux.
 * When PRoot is missing (for example an APK built without the native step) everything falls
 * back to Android's own shell.
 */
class LinuxRuntime(private val ctx: Context, private val settings: AppSettings) {
    init { LspCheck.install(ctx, this, settings) }
    private val main = Handler(Looper.getMainLooper())
    val base = File(ctx.filesDir, "linux")
    val rootfs = File(base, "rootfs")
    private val marker = File(base, "installed.txt")
    val tmp = File(ctx.cacheDir, "proot-tmp")
    /** PRoot's folder: in this APK (GPL edition) or in the Linux add-on (MIT edition, 1.5.128); looked up each time, so an add-on
     *  installed while the app runs is found. */
    private val libDir: File get() = LinuxAddon.libDir(ctx)
    val launcher: File get() = File(libDir, "libproot.so")

    @Volatile private var job: Thread? = null
    @Volatile private var cancel = false

    /** PRoot, its loader and talloc are in the APK. */
    val prootPresent: Boolean
        get() = launcher.isFile && File(libDir, "libproot-loader.so").isFile && File(libDir, "libtalloc.so").isFile

    val installed: Boolean get() = marker.isFile && File(rootfs, "bin/sh").let { it.exists() || java.nio.file.Files.isSymbolicLink(it.toPath()) }

    /** Commands go through Linux when everything is in place and the user has not switched it off. */
    val active: Boolean get() = prootPresent && installed && settings.useLinux

    val busy: Boolean get() = job != null

    fun status(): String = when {
        !prootPresent -> LinuxAddon.missingNote(ctx)
        !installed -> "Not installed. ${distro().name}, about ${distro().approxMb} MB download."
        else -> "Installed: ${marker.readText().trim()}" + if (settings.useLinux) "" else " (switched off)"
    }

    /** Argument list and Android-side environment for one command, run in the project folder as /work. */
    /** The project's own /tmp inside Linux, so files one project leaves there are not seen by another. */
    fun guestTmp(project: File): File = File(File(tmp, "guest"), dev.pocketide.app.core.ProjectIsolation.guestTmpName(project.name)).also { it.mkdirs() }

    fun command(project: File, line: String): Pair<List<String>, Map<String, String>> {
        tmp.mkdirs()
        return ProotCommand.args(launcher.absolutePath, rootfs.absolutePath, project.absolutePath, guestTmp(project).absolutePath, line) to
            ProotCommand.env(libDir.absolutePath, tmp.absolutePath)
    }

    /** Same for an interactive login shell in the terminal screen. */
    fun shell(project: File): Pair<List<String>, Map<String, String>> {
        tmp.mkdirs()
        return ProotCommand.args(launcher.absolutePath, rootfs.absolutePath, project.absolutePath, guestTmp(project).absolutePath, null, "xterm-256color") to
            ProotCommand.env(libDir.absolutePath, tmp.absolutePath)
    }

    /** The Linux base this install uses. */
    fun distro(): dev.pocketide.app.core.Distro =
        if (installed) dev.pocketide.app.core.Distros.detect(
            File(rootfs, "etc/alpine-release").exists(),
            File(rootfs, "usr/bin/apt-get").exists() || File(rootfs, "bin/apt-get").exists(),
            settings.linuxDistro
        ) else dev.pocketide.app.core.Distros.byId(settings.linuxDistro)

    fun cancelInstall() { cancel = true }

    /** Downloads, verifies and unpacks the rootfs on a background thread. Callbacks run on the main thread. */
    fun install(onProgress: (String) -> Unit, onDone: (String?) -> Unit) {
        if (job != null) return
        cancel = false
        val t = Thread {
            val err = try { doInstall { s -> main.post { onProgress(s) } }; null } catch (e: Throwable) { e.message ?: e.toString() }
            job = null
            main.post { onDone(err) }
        }
        job = t
        t.start()
    }

    private fun doInstall(progress: (String) -> Unit) {
        base.mkdirs()
        val archive = File(base, "rootfs.tar.gz")
        val part = File(base, "rootfs.tar.gz.part")
        val d = distro()
        if (rootfs.exists() && dev.pocketide.app.core.RootfsSwap.runningIn(rootfs) > 0) throw IllegalStateException(dev.pocketide.app.core.RootfsSwap.IN_USE)
        dev.pocketide.app.core.RootfsSwap.spaceNote(dev.pocketide.app.core.RootfsSwap.neededBytes(d.approxMb), base.usableSpace)?.let { throw IllegalStateException(it) }
        if (!archive.isFile || Sha256.ofFile(archive) != d.sha256) {
            archive.delete()
            progress("Downloading ${d.name}…")
            download(d.url, part, progress)
            val sum = Sha256.ofFile(part)
            if (sum != d.sha256) {
                part.delete()
                throw IllegalStateException("The download did not match its checksum, so it was not used. Expected ${d.sha256}, got $sum.")
            }
            part.renameTo(archive)
        }
        progress("Unpacking…")
        val staging = File(base, "rootfs.new")
        RootfsExtractor.deleteTree(staging)
        archive.inputStream().buffered().use { raw ->
            GZIPInputStream(raw, 64 * 1024).use { gz ->
                RootfsExtractor(maxEntries = d.maxEntries, maxBytes = d.maxBytes).extract(gz, staging, { bytes, n ->
                    if (n % 200 == 0) progress("Unpacking… $n files, ${bytes / (1024 * 1024)} MB")
                }, { cancel })
            }
        }
        // Network and a friendlier shell. PRoot binds /dev and /proc, but DNS needs resolv.conf.
        File(staging, "etc/resolv.conf").let { it.delete(); it.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n") }
        File(staging, "etc/hosts").let { if (!it.exists() || it.length() == 0L) it.writeText("127.0.0.1 localhost\n::1 localhost\n") }
        File(staging, "etc/profile.d").mkdirs()
        File(staging, "etc/profile.d/pocketide.sh").writeText(
            "export PS1='\\w \\$ '\ncd /work 2>/dev/null\n" +
                "[ -f /root/.pocketide-hint ] || { echo 'PocketIDE Linux (${d.name}). Install tools with: ${d.installHint}'; touch /root/.pocketide-hint; }\n"
        )
        File(staging, "root").mkdirs()
        File(staging, "work").mkdirs()
        if (rootfs.exists() && dev.pocketide.app.core.RootfsSwap.runningIn(rootfs) > 0) { RootfsExtractor.deleteTree(staging); throw IllegalStateException(dev.pocketide.app.core.RootfsSwap.IN_USE) }
        dev.pocketide.app.core.RootfsSwap.swap(staging, rootfs, { RootfsExtractor.deleteTree(it) })
        archive.delete()
        marker.writeText(d.name + "\n")
        settings.useLinux = true
        progress("Installed.")
    }

    private fun download(url: String, to: File, progress: (String) -> Unit) {
        var u = URL(url)
        var redirects = 0
        while (true) {
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IllegalStateException("Bad redirect")
                c.disconnect()
                u = URL(u, loc)
                if (u.protocol != "https" || ++redirects > 5) throw IllegalStateException("Refusing redirect to $u")
                continue
            }
            if (code != 200) throw IllegalStateException("Download failed: HTTP $code")
            val total = c.contentLengthLong
            var last = -1L
            CountingInput(c.inputStream) { n ->
                val mb = n / (1024 * 1024)
                if (mb != last) {
                    last = mb
                    progress(if (total > 0) "Downloading… $mb of ${total / (1024 * 1024) + 1} MB" else "Downloading… $mb MB")
                }
            }.use { input ->
                to.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancel) throw IllegalStateException("Cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
            c.disconnect()
            return
        }
    }

    /** Deletes the Linux files (installed packages included). Returns how many entries could not be deleted (0 = all gone). */
    fun remove(): Int {
        marker.delete()
        var left = RootfsExtractor.deleteTree(rootfs)
        left += RootfsExtractor.deleteTree(File(base, "rootfs.new"))
        File(base, "rootfs.tar.gz").delete()
        File(base, "rootfs.tar.gz.part").delete()
        left += RootfsExtractor.deleteTree(tmp)
        return left
    }
}
