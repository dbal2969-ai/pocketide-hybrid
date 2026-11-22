package dev.pocketide.app

import dev.pocketide.app.core.FileKinds
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class FilesTest {
    @Test fun all() { FilesSelfTest.runAll(); check(FilesSelfTest.failed == 0) { "${FilesSelfTest.failed} file-handling checks failed" } }
}

object FilesSelfTest {
    var failed = 0
    private var passed = 0
    private fun ok(c: Boolean, name: String) { if (c) passed++ else { failed++; println("FAIL  $name") } }
    private fun <T> eq(a: T, b: T, name: String) { ok(a == b, name); if (a != b) println("      got: $a\n      want: $b") }

    private fun zipBytes(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, t) in files) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray(Charsets.UTF_8)); z.closeEntry() } }
        return out.toByteArray()
    }

    fun runAll() {
        failed = 0; passed = 0

        // names and types
        eq(FileKinds.mime("a/report.DOCX"), "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx media type")
        eq(FileKinds.mime("data.weird"), "application/octet-stream", "unknown media type")
        ok(FileKinds.isImage("x.JPG") && !FileKinds.isImage("x.svg") && !FileKinds.isImage("x.pdf"), "isImage")
        eq(FileKinds.safeName("../../etc/passwd"), "passwd", "folders removed from a file name")
        eq(FileKinds.safeName("..\\evil\\a.txt"), "a.txt", "windows separators removed")
        eq(FileKinds.safeName(".hidden"), "hidden", "leading dots removed")
        eq(FileKinds.safeName("   "), "file", "blank name replaced")
        eq(FileKinds.safeName("a\u0000b\nc.txt"), "abc.txt", "control characters removed")
        ok(FileKinds.safeName("x".repeat(300) + ".docx").let { it.length <= 120 && it.endsWith(".docx") }, "long name shortened, extension kept")
        eq(FileKinds.uniqueName("a.txt") { false }, "a.txt", "free name unchanged")
        eq(FileKinds.uniqueName("a.txt") { it == "a.txt" }, "a (1).txt", "taken name gets (1)")
        eq(FileKinds.uniqueName("a.txt") { it == "a.txt" || it == "a (1).txt" }, "a (2).txt", "then (2)")
        eq(FileKinds.uniqueName("Makefile") { it == "Makefile" }, "Makefile (1)", "no extension")
        eq(FileKinds.changed(mapOf("a" to 1L, "b" to 2L), mapOf("a" to 1L, "b" to 3L, "c" to 4L)), listOf("b", "c"), "changed lists new and modified files")
        eq(FileKinds.changed(mapOf("a" to 1L), mapOf("a" to 1L)), emptyList(), "nothing changed")

        ok(FileKinds.looksBinary(byteArrayOf(80, 75, 3, 4, 0, 0)) && !FileKinds.looksBinary("plain text".toByteArray()) && !FileKinds.looksBinary(ByteArray(0)), "looksBinary finds zero bytes")
        ok(!FileKinds.looksBinary(ByteArray(5000) { if (it == 4500) 0 else 65 }), "looksBinary only checks the first 4 KB")
        ok(FileKinds.deliverable("dist/app.apk") && FileKinds.deliverable("report.docx") && !FileKinds.deliverable("__pycache__/x.cpython-314.pyc") &&
            !FileKinds.deliverable("src/__pycache__/y.pyc") && !FileKinds.deliverable("node_modules/a/b.node") && !FileKinds.deliverable("web/node_modules/a.node"), "deliverable skips caches and dependency folders")

        // the store, on a real folder
        val dir = java.nio.file.Files.createTempDirectory("pocketide-files").toFile()
        try {
            val st = ProjectStore(File(dir, "p"), seed = false)

            // binary detection by content, not only by extension
            st.write("a.txt", "hello\n")
            st.writeBytes("compiled.xyz", byteArrayOf(1, 2, 0, 3, 4))
            st.writeBytes("notes.xyz", "plain text with an odd extension\n".toByteArray())
            st.writeBytes("empty.xyz", ByteArray(0))
            ok(!st.isBinary("a.txt"), "text file is not binary")
            ok(st.isBinary("compiled.xyz"), "zero byte in the first 4 KB means binary")
            ok(!st.isBinary("notes.xyz") && !st.isBinary("empty.xyz"), "odd extension but text, and empty files, are not binary")
            ok(st.isBinary("__pycache__/main.cpython-314.pyc") && st.isBinary("x.pyc") && st.isBinary("x.docx") && st.isBinary("x.odt"), "extension list covers .pyc and documents")
            ok(!st.isBinary("does/not/exist.txt"), "a missing file with a text extension is not binary")
            st.writeBytes("compiled.xyz", "now it is text".toByteArray())
            ok(!st.isBinary("compiled.xyz"), "detection follows the file when it changes")
            eq(st.read("compiled.xyz"), "now it is text", "and it can be read as text then")

            // reading documents for the AI
            val docx = zipBytes("word/document.xml" to "<w:document><w:body><w:p><w:r><w:t>Quarterly plan</w:t></w:r></w:p></w:body></w:document>")
            st.writeBytes("docs/plan.docx", docx)
            eq(st.read("docs/plan.docx"), null, "read() still refuses a docx")
            eq(st.readDoc("docs/plan.docx"), "Quarterly plan", "readDoc gives the text")
            eq(st.readForAi("docs/plan.docx"), "Quarterly plan", "readForAi reads documents")
            eq(st.readForAi("a.txt"), "hello\n", "readForAi reads text files")
            eq(st.readForAi("docs/missing.docx"), null, "missing document gives null")
            st.writeBytes("blob.png", byteArrayOf(1, 2, 3))
            eq(st.readForAi("blob.png"), null, "images are not text")

            // importing a stream
            ok(st.writeStream("in/new.bin", ByteArrayInputStream(ByteArray(100_000) { (it % 251).toByte() })), "writeStream copies a stream")
            eq(st.size("in/new.bin"), 100_000L, "streamed size")
            ok(!st.writeStream("in/big.bin", ByteArrayInputStream(ByteArray(2000)), limit = 1000), "writeStream refuses over the limit")
            ok(!st.exists("in/big.bin") && File(st.root, "in").list()!!.none { it.endsWith(".part") }, "a refused import leaves nothing behind")
            ok(st.writeStream("in/new.bin", ByteArrayInputStream(byteArrayOf(9)), 10), "writeStream replaces an existing file")
            eq(st.size("in/new.bin"), 1L, "replaced size")
            ok(!st.writeStream("../outside.txt", ByteArrayInputStream(byteArrayOf(1))), "writeStream refuses a path outside the project")

            // unique names
            eq(st.uniquePath("", "a.txt"), "a (1).txt", "unique path at the top")
            eq(st.uniquePath("docs", "plan.docx"), "docs/plan (1).docx", "unique path in a folder")
            eq(st.uniquePath("docs/", "other.docx"), "docs/other.docx", "free name in a folder")
            eq(st.uniquePath("", "../../x.txt"), "x.txt", "unsafe name made safe")

            // what a command changed
            val before = st.snapshot()
            Thread.sleep(20)
            st.write("a.txt", "hello, changed\n")
            st.writeBytes("out/result.pdf", byteArrayOf(37, 80, 68, 70))
            eq(FileKinds.changed(before, st.snapshot()), listOf("a.txt", "out/result.pdf"), "snapshot finds edited and new files")
            eq(FileKinds.changed(st.snapshot(), st.snapshot()), emptyList(), "no change, no files")

            // zip of one folder
            val out = ByteArrayOutputStream()
            st.exportZip(out, "docs")
            val names = ArrayList<String>()
            ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z -> while (true) { val e = z.nextEntry ?: break; names.add(e.name) } }
            eq(names, listOf("plan.docx"), "folder zip has the folder's files at the top")
            val all = ByteArrayOutputStream()
            st.exportZip(all)
            val allNames = ArrayList<String>()
            ZipInputStream(ByteArrayInputStream(all.toByteArray())).use { z -> while (true) { val e = z.nextEntry ?: break; allNames.add(e.name) } }
            ok("docs/plan.docx" in allNames && "a.txt" in allNames, "whole-project zip keeps folders")

            // export without large files: the left-out files are returned and listed inside the zip
            st.writeBytes("big/model.bin", ByteArray(3000))
            val small = ByteArrayOutputStream()
            val skipped = st.exportZip(small, "", 2000L)
            eq(skipped, listOf("big/model.bin" to 3000L), "export returns the left-out file and its size")
            val smallNames = ArrayList<String>(); var listed = ""
            ZipInputStream(ByteArrayInputStream(small.toByteArray())).use { z -> while (true) { val e = z.nextEntry ?: break; smallNames.add(e.name); if (e.name == ProjectStore.LEFT_OUT) listed = z.readBytes().toString(Charsets.UTF_8) } }
            ok("big/model.bin" !in smallNames && "a.txt" in smallNames, "the big file is left out, small ones kept")
            ok(listed.contains("big/model.bin") && listed.contains("2.9 KB") && listed.contains("over 2.0 KB"), "the zip lists what was left out: $listed")
            eq(st.exportZip(ByteArrayOutputStream()), emptyList<Pair<String, Long>>(), "a full export leaves nothing out")
        } finally {
            dir.deleteRecursively()
        }

        // rate limits: wait and retry by themselves
        val RL = dev.pocketide.app.core.RateLimit
        eq(RL.waitSeconds(429, "Rate limit reached for model x on tokens per minute (TPM): Limit 8000, Used 7131, Requested 2842. Please try again in 14.7975s. Need more tokens?", null), 16, "groq per-minute wait")
        eq(RL.waitSeconds(429, "Please try again in 250ms.", null), 2, "milliseconds wait")
        eq(RL.waitSeconds(429, "Please try again in 1m3.5s.", null), null, "over a minute is shown as an error")
        eq(RL.waitSeconds(429, "Please try again in 21m30s.", null), null, "daily limit is shown as an error")
        eq(RL.waitSeconds(429, "You exceeded your current quota", null), null, "no time given: error")
        eq(RL.waitSeconds(429, "slow down", "7"), 8, "Retry-After header")
        eq(RL.waitSeconds(500, "Please try again in 5s", null), null, "only 429 is retried")
        eq(RL.waitSeconds(429, "Please retry after 20 seconds", null), 21, "plain seconds wording")
        eq(RL.waitSeconds(429, "Please try again in 45s.", null), 46, "45 seconds is within the limit")
        ok(RL.status(9, 2).contains("9s") && RL.status(9, 2).contains("2 of 3"), "countdown text")

        // APK guide
        val AG = dev.pocketide.app.core.ApkGuide
        ok(AG.isApkRequest("Make an APK of this app"), "apk request: make an APK")
        ok(AG.isApkRequest("can you build me an android app from this website?"), "apk request: android app")
        ok(AG.isApkRequest("How do I get an apk of my game"), "apk request: how do I")
        ok(AG.isApkRequest("compile this into an apk file"), "apk request: compile")
        ok(!AG.isApkRequest("apk add python3"), "alpine apk add is not an apk request")
        ok(!AG.isApkRequest("run apk update and then install git"), "alpine apk update is not an apk request")
        ok(!AG.isApkRequest("Add a dark mode toggle"), "ordinary request")
        ok(!AG.isApkRequest("/plan make an apk"), "slash commands are left alone")
        eq(AG.kind(listOf("index.html", "app.js")), dev.pocketide.app.core.ApkGuide.Kind.WEB, "web project kind")
        eq(AG.kind(listOf("app/build.gradle.kts", "app/src/main/AndroidManifest.xml", "gradlew")), dev.pocketide.app.core.ApkGuide.Kind.ANDROID_GRADLE, "gradle project kind")
        eq(AG.kind(listOf("main.py")), dev.pocketide.app.core.ApkGuide.Kind.OTHER, "other project kind")
        ok(AG.workflow(dev.pocketide.app.core.ApkGuide.Kind.WEB, "My Game!")!!.contains("npx cap init \"My Game!\" \"com.pocketide.mygame\""), "web workflow names the app")
        ok(AG.workflow(dev.pocketide.app.core.ApkGuide.Kind.ANDROID_GRADLE, "x")!!.contains("./gradlew --no-daemon --stacktrace assembleDebug"), "gradle workflow builds")
        ok(AG.workflow(dev.pocketide.app.core.ApkGuide.Kind.WEB, "x")!!.contains("\$ANDROID_HOME") && !AG.workflow(dev.pocketide.app.core.ApkGuide.Kind.WEB, "x")!!.contains("\${'$'}"), "workflow keeps shell variables")
        eq(AG.workflow(dev.pocketide.app.core.ApkGuide.Kind.OTHER, "x"), null, "no workflow for other projects")
        val g = AG.steps(dev.pocketide.app.core.ApkGuide.Kind.WEB, "Snake", "me/snake", false, false)
        ok(g.contains("Workflows: Read and write") && g.contains("git push -u origin main") && g.contains("https://github.com/me/snake.git") && g.contains("Actions"), "guide has the token permission, push and Actions steps")
        ok(AG.steps(dev.pocketide.app.core.ApkGuide.Kind.OTHER, "x", "", false, false).contains("web app"), "other projects are pointed at a web app")
        ok(AG.steps(dev.pocketide.app.core.ApkGuide.Kind.WEB, "x", "", true, false).contains("already in your project"), "guide notices an existing workflow")
        ok(AG.termuxCommands("My App", "").contains("unzip -o ~/storage/downloads/My-App.zip") && AG.termuxCommands("My App", "").contains("YOUR-USERNAME/YOUR-REPO"), "termux commands use the zip name and placeholders")

        println("\n$passed passed, $failed failed")
    }
}
