package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 1.5.187: fixes from reliability run 1125-1815 (Gemini Nano Full alone, 141 runs). */
class ReliableRun1Test {
    @Test fun allTrueWithABlocksFirstLineReplacesTheBlock() {
        // l05 run 3, r06 run 2: the most common refused edit (a stray brace and "NOT SAVED: syntax error")
        val cur = "function add(a, b) {\n  return a - b;\n}\nmodule.exports = { add };\n"
        val r = EditBlocks.applyAll(cur, EditBlock("app.js", "function add(a, b) {", "function add(a, b) {\n  return a + b;\n}"))
        assertEquals("function add(a, b) {\n  return a + b;\n}\nmodule.exports = { add };\n", r.text)
        val shapes = "function area(r) {\n  return r.width * r.width;\n}\nfunction perimeter(r) {\n  return 2 * (r.width + r.height);\n}\n"
        val r2 = EditBlocks.applyAll(shapes, EditBlock("shapes.js", "function area(r) {", "function area(r) {\n  return r.width * r.height;\n}"))
        assertEquals("function area(r) {\n  return r.width * r.height;\n}\nfunction perimeter(r) {\n  return 2 * (r.width + r.height);\n}\n", r2.text)
        // a rename with all=true still renames every use
        assertEquals("let b = 1; b++; f(b);", EditBlocks.applyAll("let a = 1; a++; f(a);", EditBlock("x.js", "a", "b")).text?.let { it } ?: "")
    }

    @Test fun aBareNameIsARename() {
        // r20 run 1: "matches more than once", run after run
        val cur = "const { MAX_HP } = require('./config.js');\nfunction bar(hp) { return hp + '/' + MAX_HP; }\nconst MAX_HPX = 1;\n"
        val r = EditBlocks.renameAll(cur, EditBlock("hud.js", "MAX_HP", "MAX_HEALTH"))!!
        assertEquals("const { MAX_HEALTH } = require('./config.js');\nfunction bar(hp) { return hp + '/' + MAX_HEALTH; }\nconst MAX_HPX = 1;\n", r.text)
        // without a rename request (NativeAgent decides), a repeated name is still refused as ambiguous
        assertNotEquals(null, EditBlocks.apply(cur, EditBlock("hud.js", "MAX_HP", "MAX_HEALTH")).error)
        assertNull(EditBlocks.renameAll("x = 1;\nx = 1;\n", EditBlock("a.js", "x = 1;", "x = 2;")))
        assertNull(EditBlocks.renameAll("a a", EditBlock("a.js", "a", "a")))
    }

    @Test fun doNotFixAnythingChangesNothing() {
        // r03 run 2: Nano edited text.js on "tell me which check fails. Do not fix anything."
        assertNotEquals(Intent.Kind.TASK, Intent.of("Run node test.js and tell me which check fails. Do not fix anything."))
        assertNotEquals(Intent.Kind.TASK, Intent.of("Run node test.js and tell me whether it passes. Only report; do not change any file, even if something is wrong."))
        assertEquals(Intent.Kind.TASK, Intent.of("Fix add in app.js so node test.js prints ok."))
    }
}
