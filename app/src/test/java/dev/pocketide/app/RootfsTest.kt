package dev.pocketide.app

import dev.pocketide.app.core.LinuxDistro
import dev.pocketide.app.core.Prompts
import dev.pocketide.app.core.ProotCommand
import dev.pocketide.app.core.RootfsExtractor
import dev.pocketide.app.core.Sha256
import dev.pocketide.app.core.TarReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Test

/** GPL route: tar extraction, checksum and PRoot command-line checks. Plain JVM. */
class RootfsTest {
    @Test
    fun rootfsChecks() = RootfsSelfTest.runAll()
}

object RootfsSelfTest {
    private var fails = 0
    private var passes = 0
    private fun ok(c: Boolean, m: String) { if (c) passes++ else fails++; println((if (c) "PASS  " else "FAIL  ") + m) }
    private fun eq(a: Any?, b: Any?, m: String) = ok(a == b, if (a == b) m else "$m  got <$a> expected <$b>")

    /** A minimal tar writer for building test archives. */
    class Tar {
        private val out = ByteArrayOutputStream()
        private fun header(name: String, type: Char, mode: Int, size: Long, link: String = "", ustarPrefix: String = ""): ByteArray {
            val h = ByteArray(512)
            fun put(s: String, off: Int, len: Int) { val b = s.toByteArray(); System.arraycopy(b, 0, h, off, minOf(b.size, len)) }
            fun oct(v: Long, off: Int, len: Int) = put(java.lang.Long.toOctalString(v).padStart(len - 1, '0'), off, len - 1)
            put(name, 0, 100); oct(mode.toLong(), 100, 8); oct(0, 108, 8); oct(0, 116, 8); oct(size, 124, 12); oct(0, 136, 12)
            h[156] = type.code.toByte(); put(link, 157, 100); put("ustar", 257, 6); put("00", 263, 2); put(ustarPrefix, 345, 155)
            for (i in 148 until 156) h[i] = ' '.code.toByte()
            val sum = h.sumOf { it.toInt() and 0xff }
            put(java.lang.Long.toOctalString(sum.toLong()).padStart(6, '0') + "\u0000 ", 148, 8)
            return h
        }
        private fun pad(n: Long) { val p = ((512 - n % 512) % 512).toInt(); out.write(ByteArray(p)) }
        fun dir(name: String) = apply { out.write(header(name, '5', 493, 0)) }
        fun file(name: String, body: String, mode: Int = 420, prefix: String = "") = apply {
            val b = body.toByteArray(); out.write(header(name, '0', mode, b.size.toLong(), ustarPrefix = prefix)); out.write(b); pad(b.size.toLong())
        }
        fun symlink(name: String, target: String) = apply { out.write(header(name, '2', 511, 0, target)) }
        fun hardlink(name: String, target: String) = apply { out.write(header(name, '1', 420, 0, target)) }
        fun longName(name: String, body: String) = apply {
            val n = (name + "\u0000").toByteArray(); out.write(header("././@LongLink", 'L', 0, n.size.toLong())); out.write(n); pad(n.size.toLong())
            file(name.take(99), body)
        }
        fun pax(name: String, body: String) = apply {
            val rec0 = " path=$name\n"
            var len = rec0.length + 2
            while ((len.toString() + rec0).length != len) len = (len.toString() + rec0).length
            val p = (len.toString() + rec0).toByteArray()
            out.write(header("PaxHeaders/x", 'x', 420, p.size.toLong())); out.write(p); pad(p.size.toLong())
            file("short-name-ignored", body)
        }
        fun bytes(): ByteArray { out.write(ByteArray(1024)); return out.toByteArray() }
    }

    private fun tmp(): File = Files.createTempDirectory("rootfs-test").toFile()

    private fun throws(m: String, block: () -> Unit) {
        val threw = try { block(); false } catch (e: IOException) { true }
        ok(threw, m)
    }

    fun runAll() {
        reader(); extract(); safety(); misc()
        println("\n$passes passed, $fails failed")
        check(fails == 0) { "$fails rootfs checks failed" }
    }

    private fun reader() {
        val t = Tar().dir("etc/").file("etc/os-release", "ID=alpine\n").file("bin/busybox", "x".repeat(700), 493)
            .symlink("bin/sh", "/bin/busybox").bytes()
        val r = TarReader(ByteArrayInputStream(t))
        val a = r.next()!!
        eq(a.name to a.type, "etc/" to '5', "tar: directory entry")
        val b = r.next()!!
        eq(b.name, "etc/os-release", "tar: file name")
        eq(r.data().readBytes().toString(Charsets.UTF_8), "ID=alpine\n", "tar: file data")
        val c = r.next()!!
        eq(c.size, 700L, "tar: size across padding")
        eq(c.mode and 0b001_000_000 != 0, true, "tar: exec bit read")
        // skip data without reading it
        val d = r.next()!!
        eq(d.type to d.linkName, '2' to "/bin/busybox", "tar: symlink target (after skipping unread data)")
        eq(r.next(), null, "tar: end of archive")

        val ln = "a/" + "b".repeat(120) + "/file.txt"
        val r2 = TarReader(ByteArrayInputStream(Tar().longName(ln, "long").bytes()))
        eq(r2.next()!!.name, ln, "tar: GNU long name")
        val r3 = TarReader(ByteArrayInputStream(Tar().pax("pax/" + "c".repeat(150), "p").bytes()))
        eq(r3.next()!!.name, "pax/" + "c".repeat(150), "tar: pax path")
        val r4 = TarReader(ByteArrayInputStream(Tar().file("name.txt", "u", prefix = "usr/share").bytes()))
        eq(r4.next()!!.name, "usr/share/name.txt", "tar: ustar prefix")

        val bad = Tar().file("x", "y").bytes().also { it[0] = 'z'.code.toByte() }
        throws("tar: bad header checksum is rejected") { TarReader(ByteArrayInputStream(bad)).next() }
        val cut = Tar().file("x", "y".repeat(2000)).bytes().copyOf(1000)
        throws("tar: truncated data is rejected") { val rr = TarReader(ByteArrayInputStream(cut)); rr.next(); rr.data().readBytes() }
    }

    private fun extract() {
        val dest = File(tmp(), "rootfs")
        val t = Tar().dir("./").dir("./bin/").file("./bin/busybox", "#!/bin/true\n", 493)
            .symlink("./bin/sh", "/bin/busybox").symlink("./bin/ls", "busybox")
            .dir("./etc/").file("./etc/motd", "hi\n").hardlink("./etc/motd2", "etc/motd")
            .file("./usr/lib/deep/file.so", "so").bytes()
        var progress = 0
        val res = RootfsExtractor(hardLink = { _, _ -> throw UnsupportedOperationException("no links on Android") })
            .extract(ByteArrayInputStream(t), dest, { _, _ -> progress++ })
        eq(res.entries, 9, "extract: entry count")
        ok(File(dest, "bin/busybox").canExecute(), "extract: exec bit applied")
        ok(!File(dest, "etc/motd").canExecute(), "extract: plain file not executable")
        ok(Files.isSymbolicLink(File(dest, "bin/sh").toPath()), "extract: absolute symlink created")
        eq(Files.readSymbolicLink(File(dest, "bin/ls").toPath()).toString(), "busybox", "extract: relative symlink kept as-is")
        eq(File(dest, "etc/motd2").readText(), "hi\n", "extract: hard link falls back to a copy")
        eq(File(dest, "usr/lib/deep/file.so").readText(), "so", "extract: missing parent folders created")
        ok(progress >= 3, "extract: progress reported")
        throws("extract: refuses an existing destination") { RootfsExtractor().extract(ByteArrayInputStream(t), dest) }
    }

    private fun safety() {
        fun bad(m: String, tar: ByteArray) {
            val dest = File(tmp(), "r")
            throws(m) { RootfsExtractor().extract(ByteArrayInputStream(tar), dest) }
            ok(!dest.exists(), "$m (partial files removed)")
        }
        bad("safety: ../ path", Tar().file("ok", "1").file("../evil", "x").bytes())
        bad("safety: absolute path", Tar().file("/etc/evil", "x").bytes())
        bad("safety: nested ..", Tar().file("a/../../evil", "x").bytes())
        bad("safety: symlink pointing out", Tar().symlink("out", "../../../../etc").bytes())
        bad("safety: writing through a symlink", Tar().symlink("lnk", "/tmp").file("lnk/evil", "x").bytes())
        bad("safety: hard link to outside", Tar().hardlink("h", "../x").bytes())
        bad("safety: hard link to missing file", Tar().hardlink("h", "nothing").bytes())
        bad("safety: empty archive", ByteArray(1024))
        val dest = File(tmp(), "r")
        throws("safety: entry limit") { RootfsExtractor(maxEntries = 2).extract(ByteArrayInputStream(Tar().file("a", "1").file("b", "2").file("c", "3").bytes()), dest) }
        throws("safety: size limit") { RootfsExtractor(maxBytes = 10).extract(ByteArrayInputStream(Tar().file("a", "x".repeat(50)).bytes()), File(tmp(), "s")) }
        val root = tmp().toPath()
        ok(RootfsExtractor().linkInside(root, root.resolve("bin/sh"), "/bin/busybox", true), "safety: absolute symlink inside rootfs is fine")
        ok(!RootfsExtractor().linkInside(root, root.resolve("a"), "../..", true), "safety: escaping symlink detected")
    }

    private fun misc() {
        val f = File(tmp(), "abc.txt").also { it.writeText("abc") }
        eq(Sha256.ofFile(f), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "sha256 of 'abc'")
        eq(LinuxDistro.SHA256.length, 64, "pinned rootfs hash is 64 hex chars")
        ok(LinuxDistro.URL.startsWith("https://"), "rootfs URL is https")

        val a = ProotCommand.args("/lib/libproot.so", "/data/linux/rootfs", "/data/project", "/data/tmp", "python3 app.py")
        eq(a.first(), "/lib/libproot.so", "proot: launcher first")
        ok("--rootfs=/data/linux/rootfs" in a && "--bind=/data/project:/work" in a && "--cwd=/work" in a, "proot: rootfs, project bind, cwd")
        eq(a.takeLast(3), listOf("/bin/sh", "-c", "python3 app.py"), "proot: command via sh -c")
        ok(a.indexOf("/usr/bin/env") < a.indexOf("HOME=/root"), "proot: clean guest environment")
        eq(ProotCommand.args("/p", "/r", "/w", "/t", null).takeLast(2), listOf("/bin/sh", "-l"), "proot: interactive login shell")
        val bad = try { ProotCommand.args("/p", "/r:x", "/w", "/t", "ls"); false } catch (e: IllegalArgumentException) { true }
        ok(bad, "proot: colon in a bind path is refused")
        val env = ProotCommand.env("/lib", "/t")
        eq(env["PROOT_LOADER"], "/lib/libproot-loader.so", "proot: loader path")
        eq(env["LD_LIBRARY_PATH"], "/lib", "proot: talloc found via LD_LIBRARY_PATH")

        ok(Prompts.system(false, true).contains("apk add"), "prompt: Linux note mentions apk")
        ok(!Prompts.system(false, true).contains("not installed"), "prompt: Linux note drops the Android-shell warning")
        eq(Prompts.system(true, false), Prompts.system(true), "prompt: without Linux it is unchanged")
    }
}
