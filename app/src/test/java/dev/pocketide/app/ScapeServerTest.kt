package dev.pocketide.app

import dev.pocketide.app.addon.osrs.*

import dev.pocketide.app.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ScapeServerTest {
    /** The 2009scape run steps come from the RuneScape add-on, which the app registers at start. */
    @Before fun addOn() { dev.pocketide.app.core.AddOns.register(dev.pocketide.app.addon.osrs.OsrsAddOn); dev.pocketide.app.core.AddOns.disabled = emptySet() }

    private val files = ScapeServer.TEMPLATE.files

    @Test fun stepsComeInOrderOfWhatIsMissing() {
        val fresh = ScapeServer.targets(files.keys, { false })
        assertEquals(listOf("scape-setup", "scape-build", "scape-start"), fresh.map { it.id })
        assertTrue(fresh[0].label.startsWith("1. Download"))
        val downloaded = ScapeServer.targets(files.keys, { it == "server/Server/src" })
        assertEquals("scape-build", downloaded[0].id)
        val built = ScapeServer.targets(files.keys + "server.jar", { it == "server/Server/src" })
        assertEquals("scape-start", built[0].id)
        assertEquals("Start the 2009scape server", built[0].label)
        val start = built[0]
        assertEquals(RunKind.SERVER, start.kind)
        assertEquals(ScapeServer.CLIENT_APPS, start.opensApps)
        assertEquals(ScapeServer.PORT, ServerUrls.port(start.url!!))
        assertTrue(start.readySeconds >= 300)
        assertTrue(built.first { it.id == "scape-build" }.timeoutSec!! >= 3600)
    }

    @Test fun runDetectsTheProjectAndNothingElse() {
        val all = files.keys.toList() + listOf("server/Server/src/main/content/Foo.kt", "server/Server/src/main/core/Main.java", "server/build.gradle")
        val t = RunTargets.detect(all, "server/Server/src/main/core/Main.java", { files[it] }, { false })
        assertEquals(listOf("scape-setup", "scape-build", "scape-start"), t.map { it.id })
        assertFalse(ScapeServer.isProject { p -> if (p == "start.sh") "#!/bin/sh\necho hi" else null })
        assertEquals(t[0], RunTargets.pickDefault(t, null, null, true))
        // ▶ after the download: the build, not the download again (it was the last one run)
        val next = RunTargets.detect(all, null, { files[it] }, { it == "server/Server/src" })
        assertEquals("scape-build", RunTargets.pickDefault(next, null, "scape-setup", true)!!.id)
    }

    @Test fun scriptsAreValidShell() {
        val dir = java.nio.file.Files.createTempDirectory("scape").toFile()
        for (name in listOf("setup.sh", "build.sh", "start.sh")) {
            val f = File(dir, name); f.writeText(files[name]!!)
            val p = ProcessBuilder("sh", "-n", f.absolutePath).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertEquals("$name: $out", 0, p.waitFor())
        }
        assertTrue(files["setup.sh"]!!.contains(ScapeServer.REPO))
        assertTrue(files["build.sh"]!!.contains("<version>${ScapeServer.SQLITE_NEW}</version>"))
        assertTrue(files["start.sh"]!!.contains("exec java"))
        assertFalse(files.values.any { it.contains("\${'\$'}") })   // Kotlin escapes all became plain $
    }

    @Test fun oldScriptsAreRefreshedButEditedOnesKept() {
        val disk = hashMapOf("start.sh" to files["start.sh"]!!.replace("-Xmx", "-Xmy"), "build.sh" to "#!/bin/sh\nmy own build", "setup.sh" to files["setup.sh"]!!)
        val changed = ScapeServer.refreshScripts({ disk[it] }, { p, t -> disk[p] = t })
        assertEquals(listOf("start.sh"), changed)
        assertEquals(files["start.sh"], disk["start.sh"]); assertEquals("#!/bin/sh\nmy own build", disk["build.sh"])
    }

    @Test fun memoryComesFromThePhone() {
        val start = files["start.sh"]!!
        val snippet = start.substring(start.indexOf("if [ -z \"\$SCAPE_MEMORY\" ]"), start.indexOf("cd server/Server"))
        fun heap(memKb: Long): String {
            val dir = java.nio.file.Files.createTempDirectory("mem").toFile()
            File(dir, "meminfo").writeText("MemTotal:       $memKb kB\nMemFree: 1 kB\n")
            val f = File(dir, "t.sh"); f.writeText(snippet.replace("/proc/meminfo", File(dir, "meminfo").absolutePath) + "echo \$SCAPE_MEMORY\n")
            val p = ProcessBuilder("sh", f.absolutePath).redirectErrorStream(true).start()
            return p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
        }
        assertEquals("2048m", heap(3_000_000))
        assertEquals("${7_800_000L * 45 / 100 / 1024}m", heap(7_800_000))
        assertEquals("5120m", heap(16_000_000))
    }

    @Test fun clientConfigPointsAtThePhone() {
        val c = MiniJson.obj(MiniJson.parse(ScapeServer.CLIENT_CONFIG))!!
        assertEquals("127.0.0.1", c["ip_address"]); assertEquals("127.0.0.1", c["ip_management"])
        assertEquals(ScapeServer.PORT, (c["js5_port"] as Number).toInt())
        assertEquals(ScapeServer.PORT - 1, (c["server_port"] as Number).toInt() + (c["world"] as Number).toInt() - 1)
    }

    @Test fun templateIsAddedByTheEdition() {
        val before = Templates.extra
        try {
            Templates.extra = listOf(ScapeServer.TEMPLATE)
            assertEquals(ScapeServer.TEMPLATE, Templates.byId("scape2009"))
            assertTrue(Templates.render(ScapeServer.TEMPLATE, "My world")["README.md"]!!.startsWith("# My world: your own 2009scape world"))
        } finally { Templates.extra = before }
    }
}
