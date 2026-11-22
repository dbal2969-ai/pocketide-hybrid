package dev.pocketide.app

import dev.pocketide.app.core.ApplyPatch
import dev.pocketide.app.core.ApplyPatch.Add
import dev.pocketide.app.core.ApplyPatch.Delete
import dev.pocketide.app.core.ApplyPatch.Update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplyPatchTest {
    private val app = "function start() {\n  let x = 1;\n  draw(x);\n}\n\nfunction stop() {\n  let x = 1;\n  clear(x);\n}\n"

    @Test fun thePatchIsFoundUnderAnyArgumentName() {
        // 1.5.106: gpt-oss-120b calls apply_patch with {"patch": …}; the app read only "input" and refused every call
        val p = "*** Begin Patch\n*** Delete File: a.js\n*** End Patch"
        assertEquals(p, ApplyPatch.argument(mapOf("input" to p)))
        assertEquals(p, ApplyPatch.argument(mapOf("patch" to p)))
        assertEquals(p, ApplyPatch.argument(mapOf("diff" to p, "note" to "x")))
        assertEquals(p, ApplyPatch.argument(mapOf("note" to "x", "changes" to p)))
        assertEquals("", ApplyPatch.argument(emptyMap()))
        // and some end it twice
        val twice = ApplyPatch.parse("*** Begin Patch\n*** Update File: a.py\n@@\n-x = 1\n+x = 2\n*** End Patch\n*** End Patch")
        assertEquals(null, twice.error); assertEquals(1, twice.ops.size)
    }

    @Test fun parsesAllOperations() {
        val p = ApplyPatch.parse("""```
apply_patch <<'EOF'
*** Begin Patch
*** Add File: src/new.js
+export const a = 1;
+
+export const b = 2;
*** Update File: src/app.js
*** Move to: src/main.js
@@ function stop() {
-  let x = 1;
+  let x = 2;
*** Delete File: old.js
*** End Patch
EOF
```""")
        assertNull(p.error)
        assertEquals(3, p.ops.size)
        assertEquals(Add("src/new.js", "export const a = 1;\n\nexport const b = 2;\n"), p.ops[0])
        val u = p.ops[1] as Update
        assertEquals("src/main.js", u.moveTo); assertEquals("function stop() {", u.chunks[0].anchor)
        assertEquals(Delete("old.js"), p.ops[2])
    }

    @Test fun anchorPicksTheRightPlace() {
        val p = ApplyPatch.parse("*** Begin Patch\n*** Update File: a.js\n@@ function stop() {\n-  let x = 1;\n+  let x = 2;\n*** End Patch")
        val out = ApplyPatch.apply(app, (p.ops[0] as Update).chunks)
        assertNull(out.error)
        assertEquals(app.replaceFirst("function stop() {\n  let x = 1;", "function stop() {\n  let x = 2;"), out.text)
        assertTrue("the first function is untouched", out.text!!.startsWith("function start() {\n  let x = 1;"))
    }

    @Test fun contextAndSeveralChangesInOrder() {
        val patch = "*** Begin Patch\n*** Update File: a.js\n@@\n   let x = 1;\n-  draw(x);\n+  draw(x, true);\n }\n@@\n   let x = 1;\n-  clear(x);\n+  clear();\n*** End Patch"
        val out = ApplyPatch.apply(app, (ApplyPatch.parse(patch).ops[0] as Update).chunks)
        assertNull(out.error)
        assertTrue(out.text!!.contains("draw(x, true);") && out.text!!.contains("  clear();") && out.text!!.endsWith("}\n"))
    }

    @Test fun whitespaceTolerance() {
        val patch = "*** Begin Patch\n*** Update File: a.js\n@@\n let x = 1;   \n-  draw(x);\n+  paint(x);\n*** End Patch"
        val out = ApplyPatch.apply(app, (ApplyPatch.parse(patch).ops[0] as Update).chunks)
        assertTrue(out.error, out.text!!.contains("  paint(x);"))
    }

    @Test fun endOfFileAndAdding() {
        val patch = "*** Begin Patch\n*** Update File: a.js\n@@\n+// end\n*** End of File\n*** End Patch"
        val out = ApplyPatch.apply("a\nb\n", (ApplyPatch.parse(patch).ops[0] as Update).chunks)
        assertEquals("a\nb\n// end\n", out.text)
        assertEquals("x\n", ApplyPatch.apply("", listOf(ApplyPatch.Chunk(null, emptyList(), listOf("x"), false))).text)
        assertEquals("keeps a missing final newline", "a\nB", ApplyPatch.apply("a\nb", listOf(ApplyPatch.Chunk(null, listOf("b"), listOf("B"), false))).text)
    }

    @Test fun clearErrors() {
        assertNotNull(ApplyPatch.parse("no patch").error)
        assertTrue(ApplyPatch.parse("*** Begin Patch\n*** Update File: a\n-x\n").error!!.contains("End Patch"))
        assertTrue(ApplyPatch.parse("*** Begin Patch\n*** Add File: a\nno plus\n*** End Patch").error!!.contains("must start with +"))
        assertTrue(ApplyPatch.parse("*** Begin Patch\n*** Rename: a\n*** End Patch").error!!.contains("Unexpected line"))
        assertNotNull(ApplyPatch.parse("*** Begin Patch\n*** End Patch").error)
        val miss = ApplyPatch.apply(app, listOf(ApplyPatch.Chunk(null, listOf("nothing like this"), listOf("y"), false)))
        assertNull(miss.text); assertTrue(miss.error!!.contains("not found"))
        assertTrue(ApplyPatch.apply(app, listOf(ApplyPatch.Chunk("function missing()", listOf("x"), listOf("y"), false))).error!!.contains("@@"))
    }

    @Test fun whichModels() {
        for (m in listOf("gpt-5.1-codex", "openai/gpt-5", "gpt-4.1-mini", "o3", "openai/o4-mini", "codex-mini-latest")) assertTrue(m, ApplyPatch.suits("openrouter", m))
        for (m in listOf("claude-sonnet-5-5", "anthropic/claude-haiku-5-5", "@cf/moonshotai/kimi-k2.6", "llama-3.3-70b", "gemma-4", "deepseek-chat")) assertFalse(m, ApplyPatch.suits("openrouter", m))
    }
}
