package dev.pocketide.app

import dev.pocketide.app.core.ApkDb
import dev.pocketide.app.core.ProotCommand
import dev.pocketide.app.core.ToolCatalog
import org.junit.Test

/** GPL route: one-tap installs (catalog, apk database, repositories) and PRoot guest variables. Plain JVM. */
class ToolsTest {
    @Test
    fun toolChecks() {
        ToolsSelfTest.runAll()
        org.junit.Assert.assertEquals(0, ToolsSelfTest.failures)
    }
}

object ToolsSelfTest {
    var failures = 0
        private set
    private var passes = 0
    private fun ok(c: Boolean, m: String) { if (c) passes++ else failures++; println((if (c) "PASS  " else "FAIL  ") + m) }
    private fun eq(a: Any?, b: Any?, m: String) = ok(a == b, if (a == b) m else "$m  got <$a> expected <$b>")
    private fun throws(m: String, f: () -> Unit) = ok(try { f(); false } catch (e: IllegalArgumentException) { true }, m)

    fun runAll() {
        failures = 0; passes = 0
        catalog()
        apkDb()
        repos()
        proot()
        println("\n$passes passed, $failures failed")
    }

    private fun catalog() {
        val ids = ToolCatalog.all.map { it.id }
        eq(ids.size, ids.toSet().size, "catalog: ids are unique")
        val pkg = Regex("[a-z0-9][a-z0-9.+_-]*")
        ok(ToolCatalog.all.all { t -> t.packages.isNotEmpty() && t.packages.all { pkg.matches(it) } }, "catalog: package names are plain apk names")
        ok(ToolCatalog.all.all { it.verify.isNotBlank() && it.approxMb > 0 }, "catalog: every tool has a test and a size")
        ok(ToolCatalog.basics.all { ToolCatalog.byId(it) != null }, "catalog: basics exist")
        eq(ToolCatalog.byId("python")?.packages, listOf("python3", "py3-pip"), "catalog: python packages")
        eq(ToolCatalog.byId("ollama")?.packages, listOf("ollama"), "catalog: ollama package")
        eq(ToolCatalog.byId("llamacpp")?.packages, listOf("llama-server"), "catalog: llama.cpp server package")
        eq(ToolCatalog.byId("java")?.packages, listOf("openjdk21-jdk"), "catalog: java package")
        val basics = ToolCatalog.basics.mapNotNull { ToolCatalog.byId(it) }
        eq(ToolCatalog.installCommand(basics), "apk update && apk add python3 py3-pip nodejs npm git", "install: one apk call, index refreshed first")
        eq(ToolCatalog.installCommand(listOf(ToolCatalog.byId("git")!!, ToolCatalog.byId("git")!!)), "apk update && apk add git", "install: duplicates dropped")
        eq(ToolCatalog.removeCommand(ToolCatalog.byId("rust")!!), "apk del rust cargo", "remove: apk del")
        throws("install: empty list refused") { ToolCatalog.installCommand(emptyList()) }
        eq(ToolCatalog.byId("office")?.packages, listOf("py3-lxml", "py3-pillow", "py3-openpyxl", "py3-xlsxwriter"), "catalog: office packages")
        eq(ToolCatalog.installCommand(listOf(ToolCatalog.byId("python")!!, ToolCatalog.byId("office")!!)),
            "apk update && apk add python3 py3-pip py3-lxml py3-pillow py3-openpyxl py3-xlsxwriter && apk add py3-pip && pip install --break-system-packages python-docx python-pptx",
            "install: a tool's follow-up command is chained after apk")
        eq(ToolCatalog.installCommand(listOf(ToolCatalog.byId("office")!!, ToolCatalog.byId("office")!!)).split(" && ").size, 4, "install: follow-up command not repeated")
        eq(ToolCatalog.removeCommand(ToolCatalog.byId("office")!!), "apk del py3-lxml py3-pillow py3-openpyxl py3-xlsxwriter", "remove: office leaves python alone")
        eq(ToolCatalog.byId("pdf")?.packages, listOf("poppler-utils"), "catalog: pdf package")
        eq(ToolCatalog.byId("pandoc")?.packages, listOf("pandoc-cli"), "catalog: pandoc package")
        eq(ToolCatalog.sizeLabel(36), "~36 MB", "size label")
        eq(ToolCatalog.sizeLabel(0), "< 1 MB", "size label: tiny")
    }

    private fun apkDb() {
        val db = "C:Q1abc=\nP:musl\nV:1.2.5-r0\nA:aarch64\n\nC:Q1def=\nP:python3\nV:3.12\n\nP:py3-pip\nV:24\n"
        val got = ApkDb.installed(db)
        eq(got, setOf("musl", "python3", "py3-pip"), "apk db: package names from P: lines")
        ok(ToolCatalog.isInstalled(ToolCatalog.byId("python")!!, got), "apk db: python counts as installed")
        ok(!ToolCatalog.isInstalled(ToolCatalog.byId("node")!!, got), "apk db: node is not")
        eq(ApkDb.installed(""), emptySet<String>(), "apk db: empty file")
        eq(ApkDb.installed("p:lower\nXP:nope\nP:\n"), emptySet<String>(), "apk db: only exact P: lines with a name")
    }

    private fun repos() {
        val both = "https://dl-cdn.alpinelinux.org/alpine/v3.24/main\nhttps://dl-cdn.alpinelinux.org/alpine/v3.24/community\n"
        eq(ToolCatalog.withCommunity(both), null, "repos: nothing to do when community is listed")
        val mainOnly = "https://dl-cdn.alpinelinux.org/alpine/v3.24/main\n"
        eq(ToolCatalog.withCommunity(mainOnly),
            "https://dl-cdn.alpinelinux.org/alpine/v3.24/main\nhttps://dl-cdn.alpinelinux.org/alpine/v3.24/community\n",
            "repos: community added after main")
        val commented = "https://x/alpine/v3.24/main\n#https://x/alpine/v3.24/community\n"
        eq(ToolCatalog.withCommunity(commented), "https://x/alpine/v3.24/main\nhttps://x/alpine/v3.24/community\n#https://x/alpine/v3.24/community\n",
            "repos: a commented-out community line does not count")
        eq(ToolCatalog.withCommunity("# nothing here\n"), null, "repos: no main line, leave it alone")
    }

    private fun proot() {
        val a = ProotCommand.args("/l/libproot.so", "/r", "/w", "/t", "exec ollama serve", "dumb",
            mapOf("OLLAMA_HOST" to "127.0.0.1:11434", "OLLAMA_MODELS" to "/root/.ollama/models"))
        ok("OLLAMA_HOST=127.0.0.1:11434" in a && "OLLAMA_MODELS=/root/.ollama/models" in a, "proot: guest variables passed")
        ok(a.indexOf("OLLAMA_HOST=127.0.0.1:11434") > a.indexOf("/usr/bin/env") && a.indexOf("OLLAMA_HOST=127.0.0.1:11434") < a.indexOf("/bin/sh"),
            "proot: guest variables go to env, before the shell")
        eq(a.takeLast(3), listOf("/bin/sh", "-c", "exec ollama serve"), "proot: background command")
        throws("proot: bad variable name refused") { ProotCommand.args("/p", "/r", "/w", "/t", "ls", "dumb", mapOf("A=B" to "x")) }
        eq(ProotCommand.args("/p", "/r", "/w", "/t", "ls"), ProotCommand.args("/p", "/r", "/w", "/t", "ls", "dumb", emptyMap()), "proot: no variables, same as before")
    }
}
