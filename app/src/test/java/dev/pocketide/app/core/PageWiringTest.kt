package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 1.5.190: pages wired up wrong (from-scratch run 1126-0628, s05 and s09, which the app's checks passed) are found. */
class PageWiringTest {
    private fun page(dir: String) = File("src/test/resources/pages/$dir").listFiles()!!.associate { it.name to it.readText() }
    private fun wiring(files: Map<String, String>) = PageRefs.wiring(files.keys.toList()) { files[it] }

    @Test fun aPageThatLoadsNoScript() {
        val w = wiring(page("s05-todo-empty"))
        assertTrue(w.toString(), w.any { it.startsWith("index.html loads no script, so script.js never runs") })
        // the same page with its script tag is fine
        val fixed = page("s05-todo-empty").let { it + ("index.html" to it.getValue("index.html").replace("</html>", "<script src=\"script.js\"></script></body></html>")) }
        assertTrue(wiring(fixed).none { it.contains("loads no script") })
    }

    @Test fun anIdNoElementHas() {
        val w = wiring(page("s09-tip-empty"))
        assertEquals(listOf("script.js looks up #tipResult but no page has an element with that id, so it is null. Use the ids the page has, or add the elements."), w)
        // ids given by a script are fine
        val made = mapOf("index.html" to "<body><script src=\"a.js\"></script></body>",
            "a.js" to "const d = document.createElement('div'); d.id = 'board'; document.body.appendChild(d); document.getElementById('board').textContent = 'x';" +
                " document.body.innerHTML += '<p id=\"score\"></p>'; document.querySelector('#score');")
        assertTrue(wiring(made).toString(), wiring(made).isEmpty())
    }

    @Test fun noFalseAlarmsOnWorkingProjects() {
        val projects = (HybridBenchTasks.ALL + HybridBenchTasks.HARD + HybridBenchTasks.GAMES + HybridBenchTasks.LOCAL).map { it.id to it.files } +
            Starters.all.map { it.id to it.files }
        for ((id, files) in projects) {
            if (files.keys.none { it.endsWith(".html") }) continue
            assertTrue("$id: ${wiring(files)}", wiring(files).isEmpty())
        }
    }
}
