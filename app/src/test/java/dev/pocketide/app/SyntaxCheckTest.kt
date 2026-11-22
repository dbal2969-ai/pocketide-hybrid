package dev.pocketide.app

import dev.pocketide.app.core.SyntaxCheck
import dev.pocketide.app.core.SyntaxCheck.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntaxCheckTest {
    @Test fun kinds() {
        assertEquals(Kind.SCRIPT, SyntaxCheck.kind("game.js", "class A {}"))
        assertEquals(Kind.MODULE, SyntaxCheck.kind("game.js", "import * as THREE from './engine/three.module.min.js';"))
        assertEquals(Kind.MODULE, SyntaxCheck.kind("a.js", "const x = 1;\nexport default x;"))
        assertEquals(Kind.JSON, SyntaxCheck.kind("package.json", "{}"))
        assertEquals(Kind.HTML, SyntaxCheck.kind("index.html", ""))
        assertEquals(Kind.NONE, SyntaxCheck.kind("notes.md", ""))
    }

    @Test fun parserResults() {
        assertNull(SyntaxCheck.jsResult("\"ok\""))
        assertNull(SyntaxCheck.jsResult("\"SKIP\""))
        assertEquals("line 2, column 18: Unexpected token", SyntaxCheck.jsResult("\"ERR 2 18 Unexpected token\""))
        assertEquals("line 12, column 3: Unexpected token", SyntaxCheck.jsResult("\"ERR 2 3 Unexpected token\"", 10))
        assertTrue(SyntaxCheck.jsCall("a\n\"b\"", true).contains("sourceType:'module'"))
    }

    @Test fun jsonAndCss() {
        assertNull(SyntaxCheck.json("{\"a\": [1, 2]}"))
        assertTrue(SyntaxCheck.json("{\n\"a\": 1,\n}")!!.startsWith("line "))
        assertNull(SyntaxCheck.css("a { color: red } /* } */ b { content: '}' }"))
        assertEquals("line 2: a { that is never closed", SyntaxCheck.css("a { color: red }\nb { color: blue"))
        assertEquals("line 1: a } with no { before it", SyntaxCheck.css("a { } }"))
    }

    @Test fun htmlScripts() {
        val html = "<html>\n<head>\n<script src=\"x.js\"></script>\n<script>\nlet a = 1;\n</script>\n<script type=\"application/json\">{}</script>\n<script type=\"module\">import x from './x.js'</script>\n</head></html>"
        val s = SyntaxCheck.inlineScripts(html)
        assertEquals(2, s.size)
        assertEquals(4, s[0].firstLine)
        assertTrue(s[1].module)
        assertEquals("a <script> tag is never closed with </script>", SyntaxCheck.html("<script>let a = 1;"))
        assertNull(SyntaxCheck.html(html))
    }
}
