package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverEscapedTest {
    private val bs = "\\"

    @Test fun codeWithWrittenOutLineBreaksIsDecoded() {
        // Llama 3.3 70B on the hard suite (1.5.89), the replace text as the app received it
        val llama = "const unidecode = require('unidecode');${bs}nfunction slugify(title) {${bs}n  return String(title)${bs}n    .toLowerCase()"
        assertTrue(OverEscaped.looks(llama, "src/util/text.js"))
        assertEquals("const unidecode = require('unidecode');\nfunction slugify(title) {\n  return String(title)\n    .toLowerCase()", OverEscaped.decode(llama))
        val py = "def f(x):${bs}r${bs}n    y = x * 2${bs}n    return y${bs}n"
        assertTrue(OverEscaped.looks(py, "f.py"))
        assertEquals("def f(x):\n    y = x * 2\n    return y\n", OverEscaped.decode(py))
        // a real line break with a written-out one beside it (the same model, another call)
        val mixed = "import unidecode\ndef url(title):${bs}n    return unidecode.unidecode(title)${bs}n"
        assertTrue(OverEscaped.looks(mixed, "url.py"))
        assertEquals("import unidecode\ndef url(title):\n    return unidecode.unidecode(title)\n", OverEscaped.decode(mixed))
    }

    @Test fun stringsRegexesCommentsAndProseAreLeftAlone() {
        for (t in listOf(
            "console.log(\"a${bs}nb\");\nprint('x${bs}ny')",
            "const lines = text.split(/${bs}n/).join(', ');",
            "// turn ${bs}n and ${bs}r${bs}n into spaces\nconst t = s.replace(/${bs}r?${bs}n/g, ' ');",
            "printf(\"total: %d${bs}n\", n);\nprintf(\"done${bs}n\");",
            "const msg = `line one${bs}n  line two${bs}n  line three`;",
            "function f() {\n  return 1;\n}",
        )) {
            assertFalse(t, OverEscaped.looks(t, "a.js"))
        }
        val code = "fix();${bs}n  more();${bs}n  done();"
        assertTrue(OverEscaped.looks(code, "a.js"))
        assertFalse(OverEscaped.looks(code, "NOTES.md"))
        // inside quotes stays as written even when the rest is decoded
        assertEquals("a = \"x${bs}ny\";\n  b();\n  c();", OverEscaped.decode("a = \"x${bs}ny\";${bs}n  b();${bs}n  c();"))
    }
}
