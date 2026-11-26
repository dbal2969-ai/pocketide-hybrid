package dev.pocketide.app.core

import dev.pocketide.app.HybridCloud
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 1.5.197: Gemini Nano's tuning from Remote Config, and the template in the repository that lists every key. */
class RemoteTuningTest {
    @After fun reset() { PyHelper.remoteNote = null; PyHelper.remoteExamples = ""; PyHelper.remoteLong = null; SmallLocal.remoteNote = "" }

    @Test fun defaultsAreTheBuiltInValues() {
        assertEquals(PyHelper.NOTE, PyHelper.note())
        assertEquals(PyHelper.LONG, PyHelper.long())
        assertEquals("", SmallLocal.remoteLine())
    }

    @Test fun remoteValuesChangeWhatNanoSees() {
        PyHelper.remoteNote = "Python: use 4 spaces."
        PyHelper.remoteExamples = "edit_lines start 12 end 12 text \"    return a + b\""
        assertEquals("Python: use 4 spaces.\nWorked examples:\nedit_lines start 12 end 12 text \"    return a + b\"", PyHelper.note())
        PyHelper.remoteLong = 200
        val text = (1..250).joinToString("\n") { if (it % 50 == 1) "def f$it():" else "    x = $it" }
        assertTrue(PyHelper.outline("m.py", text)!!.startsWith("m.py has 250 lines"))
        PyHelper.remoteLong = 7   // out of range: the built-in length
        assertEquals(PyHelper.LONG, PyHelper.long())
        SmallLocal.remoteNote = "Prefer edit_lines."
        assertEquals("\nPrefer edit_lines.", SmallLocal.remoteLine())
        SmallLocal.remoteNote = "x".repeat(3000)
        assertEquals(1501, SmallLocal.remoteLine().length)
    }

    @Test fun theTemplateListsEveryKeyWithTheAppsDefaults() {
        val t = MiniJson.obj(MiniJson.parse(File("../remoteconfig.template.json").readText()))!!
        val params = t["parameters"] as Map<*, *>
        val keys = listOf(HybridCloud.KEY_MODEL, HybridCloud.KEY_LIMIT, HybridCloud.KEY_MODE, HybridCloud.KEY_CLOUD_ON, HybridCloud.KEY_LIMITED_TOKENS,
            HybridCloud.KEY_NANO_NOTE, HybridCloud.KEY_PY_NOTE, HybridCloud.KEY_PY_EXAMPLES, HybridCloud.KEY_PY_DEFAULT, HybridCloud.KEY_PY_LINES)
        assertEquals(keys.toSet(), params.keys.map { it.toString() }.toSet())
        fun dv(k: String) = ((params[k] as Map<*, *>)["defaultValue"] as Map<*, *>)["value"].toString()
        assertEquals(HybridSettings.DEFAULT_ON_DEVICE_CHARS.toString(), dv(HybridCloud.KEY_LIMIT))
        assertEquals(PyHelper.LONG.toString(), dv(HybridCloud.KEY_PY_LINES))
        assertEquals("false", dv(HybridCloud.KEY_PY_DEFAULT))
        assertEquals("", dv(HybridCloud.KEY_PY_NOTE))
        assertEquals("", dv(HybridCloud.KEY_NANO_NOTE))
    }
}
