package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 1.5.213: the demo recording's scenes, requests and scene log. */
class DemoScriptTest {
    @Test fun scenesAreInTheVideosOrder() {
        assertEquals(listOf("welcome", "try", "offline", "build", "python", "aiweb", "cloud", "results"), DemoScript.SCENES.map { it.id })
        assertEquals(DemoScript.SCENES.size, DemoScript.SCENES.map { it.id }.toSet().size)
    }

    /** Each request goes where the scene says it does, by the app's own routing rules. */
    @Test fun eachRequestIsRoutedAsItsSceneSays() {
        assertEquals(null, HybridRoute.needsCloud(DemoScript.TRY_QUESTION))
        assertEquals(null, HybridRoute.needsCloud(DemoScript.OFFLINE_QUESTION))
        assertEquals(null, HybridRoute.needsCloud(DemoScript.SNAKE))
        assertTrue(HybridRoute.needsCloud(DemoScript.CLOUD)!!.contains("web"))
        // the build scene's request gets the one-file note and is a build request
        assertTrue(SmallLocal.isOneFile(DemoScript.SNAKE))
        assertTrue(ChatHome.isBuildRequest(DemoScript.SNAKE))
        assertEquals(null, HybridRoute.needsCloud(DemoScript.AIWEB))
    }

    @Test fun thePythonTaskIsInTheAppsAssets() {
        val assets = File("src/main/assets/bench/reliable")
        if (!assets.isDirectory) return
        assertTrue(File(assets, "python/${DemoScript.PYTHON_TASK}.zip").isFile)
        assertTrue(File(assets, "python.json").readText().contains("\"${DemoScript.PYTHON_TASK}\""))
    }

    /** The end card's numbers are the measured ones in HACKATHON.md. */
    @Test fun theEndCardMatchesHackathonMd() {
        val md = File("../HACKATHON.md").takeIf { it.isFile }?.readText() ?: return
        for (n in listOf("114", "132", "12 of 13", "10", "1 of 13")) assertTrue(n, md.contains(n))
        assertTrue(DemoScript.RESULTS.all { r -> Regex("""\d""").containsMatchIn(r) })
    }

    @Test fun theLogTellsTheTimeAndTheRoute() {
        var now = 1_000_000L
        val log = DemoScript.Log { now }
        log.scene(DemoScript.scene("try"))
        now += 65_400
        log.add(DemoScript.route("phone", 3.24, "", "try"))
        now += 10_000
        log.add(DemoScript.route("cloud", 5.0, "this needs the web, which Gemini Nano on the phone cannot reach", "agent"))
        val t = log.text("1127-1700", "samsung SM-S926U")
        assertTrue(t, t.contains("0:00  SCENE 2: On the phone."))
        assertTrue(t, t.contains("1:05  Try it ⚡ on the phone (Gemini Nano), 3.2 s"))
        assertTrue(t, t.contains("1:15  agent step ☁ in the cloud (Gemini), 5.0 s; passed on because this needs the web"))
        assertFalse(t.contains("replay"))
        assertEquals("12:05", DemoScript.clock(725_000))
    }

    /** The page scripts are valid JavaScript, and the typed text is escaped (node --check, when Node is installed). */
    @Test fun thePageScriptsParse() {
        val node = try { ProcessBuilder("node", "--version").start().waitFor() == 0 } catch (e: Exception) { false }
        org.junit.Assume.assumeTrue(node)
        for (js in listOf(DemoScript.typeIntoPage("eggs, \"feta\" & 'spinach'"), DemoScript.READ_PAGE)) {
            val f = File.createTempFile("demo", ".js"); f.writeText(js + ";\n")
            val p = ProcessBuilder("node", "--check", f.path).redirectErrorStream(true).start()
            assertEquals(p.inputStream.readBytes().toString(Charsets.UTF_8), 0, p.waitFor()); f.delete()
        }
    }
}
