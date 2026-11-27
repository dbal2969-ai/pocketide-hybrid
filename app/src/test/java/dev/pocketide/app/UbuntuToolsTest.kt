package dev.pocketide.app

import dev.pocketide.app.core.ApkDb
import dev.pocketide.app.core.DpkgDb
import dev.pocketide.app.core.Distros
import dev.pocketide.app.core.MissingTool
import dev.pocketide.app.core.Prompts
import dev.pocketide.app.core.ToolCatalog
import org.junit.Test

class UbuntuToolsTest {
    @Test
    fun ubuntuToolChecks() {
        UbuntuToolsSelfTest.runAll()
    }
}

/** Checks that the one-tap tools, the missing-tool fixes and the AI's instructions use apt on the Ubuntu base and apk on Alpine. */
object UbuntuToolsSelfTest {
    private var fails = 0
    private var passes = 0

    private fun ok(c: Boolean, m: String) {
        if (c) passes++ else fails++
        println((if (c) "PASS  " else "FAIL  ") + m)
    }

    private fun eq(a: Any?, b: Any?, m: String) = ok(a == b, if (a == b) m else "$m  got <$a> expected <$b>")

    fun runAll() {
        commands(); coverage(); dpkg(); detect(); missing(); prompts()
        println("\n$passes passed, $fails failed")
        check(fails == 0) { "$fails ubuntu tool checks failed" }
    }

    private fun commands() {
        val py = ToolCatalog.byId("python")!!
        val node = ToolCatalog.byId("node")!!
        eq(ToolCatalog.installCommand(listOf(py)), ToolCatalog.ALPINE_REFRESH + "apk add python3 py3-pip || { sleep 5; apk add python3 py3-pip; }", "Alpine install: index refreshed (retried), then apk add (tried twice)")
        eq(ToolCatalog.installCommand(listOf(py), "alpine"), ToolCatalog.installCommand(listOf(py)), "naming alpine gives the same command as the default")
        eq(ToolCatalog.removeCommand(py), "apk del python3 py3-pip", "Alpine remove command is unchanged")
        val u = ToolCatalog.installCommand(listOf(py, node), "ubuntu")
        ok("apk" !in u, "Ubuntu install never mentions apk: $u")
        ok(u.contains("apt-get") && u.contains("install -y") && u.contains("python3 python3-pip python3-venv nodejs npm"), "Ubuntu install uses apt-get with the Ubuntu package names")
        ok(u.contains("ca-certificates"), "Ubuntu install adds ca-certificates (the base has none, and pip, npm and git need them)")
        ok(u.contains("APT::Sandbox::User=root") && u.startsWith("export DEBIAN_FRONTEND=noninteractive;"), "Ubuntu install is non-interactive and does not need the _apt user")
        eq(ToolCatalog.removeCommand(py, "ubuntu"), "apt-get remove -y python3 python3-pip python3-venv", "Ubuntu remove command")
        val office = ToolCatalog.installCommand(listOf(ToolCatalog.byId("office")!!), "ubuntu")
        ok(office.endsWith("pip3 install --break-system-packages python-docx python-pptx"), "Ubuntu Office tools install with pip --break-system-packages")
        ok(ToolCatalog.installCommand(listOf(ToolCatalog.byId("typescript")!!), "ubuntu").endsWith("npm install -g typescript"), "TypeScript still installs with npm on Ubuntu")
    }

    private fun coverage() {
        val missing = ToolCatalog.all.filter { !ToolCatalog.available(it, "ubuntu") }.map { it.id }.sorted()
        // 1.5.180: Gemini CLI and Claude Code need a newer Node than Ubuntu 24.04's 18
        eq(missing, listOf("claude-code", "firebase", "gemini-cli", "llamacpp", "ollama"), "only Ollama, llama.cpp and the npm tools that need Node 20+ (agents, Firebase CLI) are unavailable on Ubuntu")
        ok(ToolCatalog.all.all { ToolCatalog.available(it, "alpine") }, "everything is available on Alpine")
        for (t in ToolCatalog.all.filter { ToolCatalog.available(it, "ubuntu") }) {
            val cmd = try { ToolCatalog.installCommand(listOf(t), "ubuntu") } catch (e: IllegalArgumentException) { "" }
            ok(cmd.isNotEmpty() && "apk" !in cmd, "Ubuntu install command builds for ${t.id}")
        }
        ok(ToolCatalog.basics.all { ToolCatalog.available(ToolCatalog.byId(it)!!, "ubuntu") }, "the basics (Python, Node, Git) exist on Ubuntu")
        ok(!ToolCatalog.isInstalled(ToolCatalog.byId("ollama")!!, setOf("ollama"), "ubuntu"), "an unavailable tool never shows as installed on Ubuntu")
    }

    private fun dpkg() {
        val status = "Package: git\nStatus: install ok installed\nPriority: optional\n\n" +
            "Package: nodejs\nStatus: install ok installed\n\n" +
            "Package: oldthing\nStatus: deinstall ok config-files\n\n" +
            "Package: halfway\nStatus: install ok half-configured\n\n" +
            "Package: ghost\nStatus: unknown ok not-installed\n\n" +
            "Package: python3\nVersion: 3.12\nStatus: install ok installed\n"
        val got = DpkgDb.installed(status)
        eq(got, setOf("git", "nodejs", "python3"), "dpkg status: only fully installed packages count")
        eq(DpkgDb.installed(""), emptySet<String>(), "dpkg status: empty file")
        val py = ToolCatalog.byId("python")!!
        ok(!ToolCatalog.isInstalled(py, got, "ubuntu"), "Python is not installed on Ubuntu until pip and venv are there too")
        ok(ToolCatalog.isInstalled(py, got + setOf("python3-pip", "python3-venv"), "ubuntu"), "Python is installed once all its Ubuntu packages are")
        ok(ToolCatalog.isInstalled(ToolCatalog.byId("git")!!, got, "ubuntu"), "Git is detected from dpkg")
        eq(ApkDb.installed("P:git\nV:1\n\nP:python3\n"), setOf("git", "python3"), "apk database parsing still works")
    }

    private fun detect() {
        eq(Distros.detect(true, false, "ubuntu").id, "alpine", "an unpacked Alpine is Alpine even if the setting says Ubuntu")
        eq(Distros.detect(false, true, "alpine").id, "ubuntu", "an unpacked Ubuntu is Ubuntu even if the setting says Alpine")
        eq(Distros.detect(false, false, "ubuntu").id, "ubuntu", "nothing to go on: use the setting")
        eq(Distros.detect(false, false, "nonsense").id, "alpine", "unknown setting falls back to Alpine")
    }

    private fun missing() {
        val ids = ToolCatalog.all.map { it.id }
        val a = MissingTool.fixFor("htop", false, ids)!!
        ok(a.command!!.startsWith("apk add") && a.label.contains("apk"), "Alpine: an unlisted program is offered through apk")
        val u = MissingTool.fixFor("htop", false, ids, distro = "ubuntu")!!
        ok(u.command!!.contains("apt-get") && u.command!!.contains("install -y") && u.command!!.endsWith(" htop") && "apk" !in u.command!!, "Ubuntu: an unlisted program is offered through apt")
        eq(MissingTool.fixFor("node", false, ids, distro = "ubuntu")!!.toolId, "node", "Ubuntu: a listed program still goes to the one-tap install")
        eq(MissingTool.detect("/bin/sh: 1: apk: not found", ids, emptyList(), "ubuntu"), null, "Ubuntu: apk missing offers nothing (installing apk would not help)")
        eq(MissingTool.detect("sh: apt-get: not found", ids, emptyList(), "alpine"), null, "Alpine: apt-get missing offers nothing")
        val d = MissingTool.detect("/bin/sh: 1: htop: not found", ids, emptyList(), "ubuntu")
        ok(d != null && d.first == "htop" && d.second.command!!.contains("apt-get"), "Ubuntu (dash) error format is recognised and offers apt")
        eq(MissingTool.detect("/bin/sh: 1: jq: not found", ids, emptyList(), "ubuntu")?.second?.toolId, "jq", "Ubuntu: a catalog program goes to the one-tap install")
        eq(MissingTool.missing("sh: foo: not found"), "foo", "busybox format still recognised")
        eq(MissingTool.missing("bash: line 3: bar: command not found"), "bar", "bash format still recognised")
        eq(MissingTool.fixFor("requests", true, ids, listOf("python"), "ubuntu")!!.command, "pip3 install --break-system-packages requests", "pip fix is the same on both bases")
    }

    private fun prompts() {
        val a = Prompts.linuxNote("alpine")
        val u = Prompts.linuxNote("ubuntu")
        ok(a.contains("Alpine") && a.contains("apk add"), "Alpine prompt tells the model to use apk")
        ok(u.contains("Ubuntu") && u.contains("apt-get install -y") && u.contains("no apk"), "Ubuntu prompt tells the model to use apt-get and that apk does not exist")
        ok(!u.contains("Install what you need with apk"), "Ubuntu prompt never tells the model to use apk")
        val ea = Prompts.linuxEnvNote("alpine")
        val eu = Prompts.linuxEnvNote("ubuntu")
        ok(ea.contains("apk add") && eu.contains("apt-get") && eu.contains("no apk"), "native-tool prompt differs per base")
        eq(Prompts.LINUX_NOTE, Prompts.linuxNote("alpine"), "the default Linux note is the Alpine one")
        ok(Prompts.system(true, true, "ubuntu").contains("apt-get") && Prompts.system(true, true).contains("apk add"), "the full system prompt follows the base")
        ok(Prompts.system(true, false, "ubuntu").contains("Android's own shell"), "no Linux, no distro text")
    }
}
