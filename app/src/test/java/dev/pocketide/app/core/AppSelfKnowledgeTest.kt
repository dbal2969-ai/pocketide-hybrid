package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 1.5.185: the assistant knows PocketIDE itself: the app map, app_help and app_settings. */
class AppSelfKnowledgeTest {
    private val src = File("src/main/java/dev/pocketide/app")
    private val source: String by lazy {
        src.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") && it.name != "AppMapData.kt" }.joinToString("\n") { it.readText() }
    }
    private fun kt(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("\n", "\\n")

    @Test fun everyLabelIsShownByTheAppWordForWord() {
        val missing = AppMap.ITEMS.filter { i -> i.kind != AppMap.Kind.NOTE || !i.label.startsWith("one box per") }
            .filterNot { source.contains(kt(it.label)) || source.contains(it.label) }
        assertTrue("labels not in the source: " + missing.joinToString { it.label }, missing.isEmpty())
        assertTrue(AppMap.ITEMS.size > 100)
    }

    @Test fun everySettingIsARealOne() {
        val special = setOf("hybrid.private", "hybrid.full_model", "hybrid.cloud_model", "mode", "editorTheme", "cacheTtl")
        val settings = File(src, "AppSettings.kt").readText()
        for (s in AppMap.ITEMS.mapNotNull { it.setting }.distinct().filter { it !in special })
            assertTrue("$s is not a Boolean in AppSettings", Regex("""var $s: Boolean""").containsMatchIn(settings))
    }

    @Test fun theGeneratedMapIsUpToDate() {
        val script = File("../scripts/gen_app_map.py")
        if (!script.exists()) return
        val p = try { ProcessBuilder("python3", script.path, "--check").redirectErrorStream(true).start() } catch (e: Exception) { return }
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(out, 0, p.waitFor())
    }

    @Test fun findsSettingsByTheirWordsAndTheAppsSynonyms() {
        assertEquals("Word wrap in the editor", AppHelp.search("Where do I turn on word wrap?").first.first().doc.title)
        assertTrue(AppHelp.search("How do I get dark mode?").first.take(3).any { it.doc.title.startsWith("Light theme for the whole app") })
        assertTrue(AppHelp.search("I keep getting a 429 rate limit error").first.take(3).any { it.doc.title.contains("429") })
        assertTrue(AppHelp.search("how much have I spent on AI").first.take(3).any { it.doc.title == "Usage & cost" })
        assertTrue(AppHelp.search("can I use my voice").first.take(3).any { it.doc.title == "🎤" })
        val pv = AppHelp.search("keep my code private on the phone").first.take(3)
        assertTrue(pv.joinToString { it.doc.title + "=" + it.score }, pv.any { it.doc.title.startsWith("Private: keep my code") })
    }

    @Test fun namesWhatTheAppDoesNotHave() {
        val (_, missing) = AppHelp.search("How do I export my project as an iPhone IPA?")
        assertTrue(missing.toString(), "iphone" in missing && "ipa" in missing)
        assertTrue("dropbox" in AppHelp.search("How do I sync with Dropbox?").second)
        val a = AppHelp.answer("zzqx frobnicate")
        assertTrue(a, a.contains("does not seem to have"))
        val b = AppHelp.answer("Comic Sans font for the editor")
        assertTrue(b, b.contains("Not found anywhere in PocketIDE") && b.contains("\"comic\""))
    }

    @Test fun answersShowPathsValuesAndFitTheRoom() {
        val a = AppHelp.answer("word wrap", 4, 1400) { if (it == "editorWrap") "off" else null }
        assertTrue(a, a.contains("⚙ Settings › Editor and layout › \"Word wrap in the editor\" (now: off)"))
        assertTrue(a.length < 1400 + AppHelp.FOOTER.length + 10)
        assertTrue(AppHelp.answer("what can PocketIDE do").contains("Git: commit, push, pull"))
    }

    @Test fun settingRequestsAreAboutTheAppNotTheProject() {
        assertEquals("editorWrap", AppHelp.settingRequest("Turn on word wrap in the editor")?.setting)
        assertEquals("hybrid.cloud_model", AppHelp.settingRequest("Switch the hybrid cloud model to Best")?.setting)
        assertNull(AppHelp.settingRequest("Turn on dark mode in my game"))
        assertNull(AppHelp.settingRequest("Add a reset button to index.html"))
        assertNull(AppHelp.settingRequest("Where is word wrap?"))
        assertEquals(Intent.Kind.QUESTION, Intent.of("Turn on word wrap in the editor"))
        assertEquals(Intent.Kind.TASK, Intent.of("Make the snake faster in game.js"))
        assertTrue(AppHelp.aboutApp("Where do I paste my Groq API key?"))
        assertFalse(AppHelp.aboutApp("What does step() in snake.js return?"))
    }

    @Test fun valuesAndAppSignalsFromInAppRun1() {
        assertEquals("true", AppHelp.settingValue("Turn on word wrap in the editor."))
        assertEquals("false", AppHelp.settingValue("Turn word wrap off"))
        assertEquals("best", AppHelp.settingValue("Switch the hybrid cloud model to Best."))
        assertEquals("plan", AppHelp.settingValue("Switch to plan mode"))
        assertTrue(AppHelp.aboutApp("How do I keep my code on this phone so it never goes to the cloud?"))
        assertTrue(AppHelp.aboutApp("How do I turn on the built-in Pomodoro timer?"))
        assertTrue(AppHelp.aboutApp("I keep getting a 429 rate limit error. What should I do?"))
        assertFalse(AppHelp.aboutApp("How do I turn on sound in my game?"))
        assertFalse(AppHelp.aboutApp("Fix add in app.js so the test passes"))
    }

    @Test fun proposalsAreCheckedAndKeysNeverChange() {
        val ok = AppSettingsTool.check("editorWrap", "true") as AppSettingsTool.Check.Ok
        assertEquals("true", ok.value)
        assertTrue(AppSettingsTool.check("Word wrap in the editor", "on") is AppSettingsTool.Check.Ok)
        assertEquals("best", (AppSettingsTool.check("hybrid.cloud_model", "Best") as AppSettingsTool.Check.Ok).value)
        assertEquals("plan", (AppSettingsTool.check("mode", "Plan") as AppSettingsTool.Check.Ok).value)
        assertTrue(AppSettingsTool.check("autorun", "true") is AppSettingsTool.Check.No)
        assertTrue(AppSettingsTool.check("Run commands from the assistant without asking", "on") is AppSettingsTool.Check.No)
        for (k in listOf("openaiKey", "api key", "ghToken", "braveKey", "supaAnon", "Brave Search API key"))
            assertTrue(k, AppSettingsTool.check(k, "x") is AppSettingsTool.Check.No)
        assertTrue(AppSettingsTool.check("editorWrap", "maybe") is AppSettingsTool.Check.No)
        assertTrue(AppSettingsTool.check("autoSwitchCredit", "on") is AppSettingsTool.Check.Ok)
        val list = AppSettingsTool.list("") { null }
        assertTrue(list.contains("editorWrap") && list.contains("hybrid.cloud_model") && !list.contains("braveKey"))
        assertTrue(AppSettingsTool.proposed(ok, "off").contains("NOT applied"))
    }

    @Test fun theSmallModelIsPointedAtTheAppTools() {
        val st = SmallLocal.State(true, false, false, false, false, aboutApp = true)
        assertTrue(SmallLocal.next(st).contains("app_help"))
        val set = SmallLocal.State(true, false, false, false, false, aboutApp = true, setting = "editorWrap")
        assertTrue(SmallLocal.next(set).contains("\"propose\""))
        assertTrue(SmallLocal.next(SmallLocal.State(true, false, false, false, false, aboutApp = true, setting = "editorWrap", proposed = true)).contains("Apply"))
        assertFalse(SmallLocal.next(SmallLocal.State(false, false, false, false, false, aboutApp = true)).contains("app_help"))
        assertTrue(Tools.smallCatalogue(false, true).contains("app_help") && Tools.smallCatalogue(false, true).contains("app_settings"))
        assertFalse(Tools.smallCatalogue(false).contains("app_help"))
        assertTrue(SmallLocal.system(Tools.smallCatalogue(false, true)).contains("app_help first"))
        assertTrue(SmallLocal.forApp("Turn on word wrap in the editor") && !SmallLocal.forApp("Fix add in app.js"))
        assertTrue(AppModes.GUIDE.contains("app_help"))
    }
}
