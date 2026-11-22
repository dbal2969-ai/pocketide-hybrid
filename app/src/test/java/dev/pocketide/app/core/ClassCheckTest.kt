package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassCheckTest {
    private val page = "<!doctype html><html><head><link rel=\"stylesheet\" href=\"style.css\"></head>" +
        "<body><button id=\"dark\">Dark</button><script src=\"app.js\"></script></body></html>\n"
    private val toggle = "document.getElementById('dark').addEventListener('click', () => {\n  document.body.classList.toggle('dark');\n});\n"

    private fun check(files: Map<String, String>, changed: Collection<String> = files.keys) = ClassCheck.unstyled(files.keys, changed) { files[it] }

    @Test fun aToggledClassWithNoRuleIsFound() {
        val m = check(mapOf("index.html" to page, "app.js" to "// hi\n" + toggle, "style.css" to "body { padding: 16px; }\n"), listOf("app.js", "index.html"))
        assertEquals(listOf(ClassCheck.Miss("dark", "app.js", 3, "toggle")), m)
    }

    @Test fun aRuleInAnyStylesheetOrStyleBlockCounts() {
        assertTrue(check(mapOf("index.html" to page, "app.js" to toggle, "style.css" to "body.dark { background: #111; }\n")).isEmpty())
        assertTrue(check(mapOf("index.html" to page.replace("</head>", "<style>.dark{color:#eee}</style></head>"), "app.js" to toggle)).isEmpty())
        assertTrue(check(mapOf("index.html" to page, "app.js" to toggle, "css/theme.scss" to ":root.dark, .dark .card { color: white; }\n")).isEmpty())
    }

    @Test fun aRuleForALongerNameDoesNotCount() {
        val m = check(mapOf("index.html" to page, "app.js" to toggle, "style.css" to ".dark-mode { background: #111; }\n"))
        assertEquals(listOf("dark"), m.map { it.cls })
    }

    @Test fun aClassTheScriptsLookUpIsAMarkerNotAStyle() {
        val js = "el.classList.add('picked');\nif (el.classList.contains('picked')) go();\nb.classList.add('seen');\nfor (const s of document.getElementsByClassName('seen')) s.remove();\n" +
            "c.classList.add('done');\ndocument.querySelectorAll('li.done').forEach(f);\n"
        assertTrue(check(mapOf("index.html" to page, "app.js" to js)).isEmpty())
    }

    @Test fun onlyStringArgumentsOfTheChangedFilesAreRead() {
        val js = "el.classList.toggle('open', isOpen);\nel.classList.add('a', \"b\");\nel.classList.add(name);\nel.classList.toggle(`x-\${n}`);\n"
        assertEquals(listOf("open", "a", "b"), check(mapOf("index.html" to page, "app.js" to js)).map { it.cls })
        assertTrue(check(mapOf("index.html" to page, "app.js" to js), changed = listOf("index.html")).isEmpty())
        assertTrue(check(mapOf("index.html" to page, "app.js" to js, "tests/app.test.js" to js), changed = listOf("tests/app.test.js")).isEmpty())
    }

    @Test fun anInlineHandlerInThePageIsRead() {
        val inline = page.replace("<button id=\"dark\">", "<button onclick=\"document.body.classList.toggle('dark')\">")
        val m = check(mapOf("index.html" to inline, "style.css" to "body { padding: 16px; }\n"))
        assertEquals(listOf(ClassCheck.Miss("dark", "index.html", 1, "toggle")), m)
    }

    @Test fun aCssFrameworkOrWebStylesheetTurnsTheCheckOff() {
        val cdn = page.replace("<link rel=\"stylesheet\" href=\"style.css\">", "<link rel=\"stylesheet\" href=\"https://cdn.jsdelivr.net/npm/bootstrap@5/dist/css/bootstrap.min.css\">")
        assertTrue(check(mapOf("index.html" to cdn, "app.js" to toggle)).isEmpty())
        val tw = page.replace("</head>", "<script src=\"https://cdn.tailwindcss.com\"></script></head>")
        assertTrue(check(mapOf("index.html" to tw, "app.js" to toggle)).isEmpty())
        assertTrue(check(mapOf("index.html" to page, "app.js" to toggle, "tailwind.config.js" to "module.exports = {};\n")).isEmpty())
        assertTrue(check(mapOf("index.html" to page, "app.js" to toggle, "package.json" to "{\"dependencies\": {\"bootstrap\": \"^5.3.0\"}}\n")).isEmpty())
        // a web font is not a stylesheet of classes
        val font = page.replace("</head>", "<link rel=\"stylesheet\" href=\"https://fonts.googleapis.com/css2?family=Inter\"></head>")
        assertEquals(listOf("dark"), check(mapOf("index.html" to font, "app.js" to toggle)).map { it.cls })
    }

    @Test fun theAskNamesEachClassAndWhere() {
        val ask = ClassCheck.ask(listOf(ClassCheck.Miss("dark", "app.js", 6, "toggle"), ClassCheck.Miss("open", "menu.js", 2, "add")))
        assertTrue(ask, ask.contains("- app.js:6 toggles 'dark', and nothing styles .dark"))
        assertTrue(ask, ask.contains("- menu.js:2 adds 'open', and nothing styles .open"))
        assertTrue(ask, ask.contains("or say why none are needed"))
    }
}
