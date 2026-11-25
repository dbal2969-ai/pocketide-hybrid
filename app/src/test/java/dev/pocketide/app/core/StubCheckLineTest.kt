package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** StubCheck names the exact line (benchmark run 2, task 05: Nano edited a different comment and the run ended unfinished). */
class StubCheckLineTest {
    private val app = "function setDay(day, id) {\n  days[day] = id;\n}\n\nfunction dayOf(day) {\n  // Placeholder implementation to retrieve the recipe ID for a given day\n  return days[day] || null;\n}\n"

    @Test fun namesTheLineAndItsNumber() {
        val why = StubCheck.find("app.js", app)!!
        assertTrue(why, why.startsWith("line 6 is a stand-in: `// Placeholder implementation to retrieve the recipe ID for a given day`"))
        assertTrue(why, why.contains("delete this comment line"))
    }

    @Test fun aStubInCodeIsNotCalledAComment() {
        val why = StubCheck.find("a.py", "def f():\n    raise NotImplementedError\n")!!
        assertEquals("line 2 is a stand-in: `raise NotImplementedError`", why)
    }

    @Test fun finishedCodeIsClean() = assertNull(StubCheck.find("app.js", app.replace("  // Placeholder implementation to retrieve the recipe ID for a given day\n", "")))
}
