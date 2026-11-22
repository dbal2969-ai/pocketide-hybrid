package dev.pocketide.app

import dev.pocketide.app.core.MultiEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiEditTest {
    private val files = mapOf("a.js" to "let x = 1;\nlet y = 2;\n", "b.js" to "const k = 5;\n")

    @Test fun editsApplyInOrderAndSeeEarlierResults() {
        val items = MultiEdit.parse(listOf(
            mapOf("path" to "a.js", "search" to "let x = 1;", "replace" to "let x = 10;"),
            mapOf("path" to "a.js", "search" to "let x = 10;", "replace" to "let x = 11;"),
            mapOf("path" to "b.js", "search" to "const k = 5;", "replace" to "const k = 6;")))
        val out = MultiEdit.run(items, { files[it] }, { null })
        assertEquals(3, out.applied)
        assertEquals("let x = 11;\nlet y = 2;\n", out.texts["a.js"])
        assertEquals("const k = 6;\n", out.texts["b.js"])
    }

    @Test fun oneFailureDoesNotStopTheOthers() {
        val items = MultiEdit.parse(listOf(
            mapOf("path" to "a.js", "search" to "nope nope", "replace" to "x"),
            mapOf("path" to "missing.js", "search" to "a", "replace" to "b"),
            mapOf("path" to "secret.txt", "search" to "a", "replace" to "b"),
            mapOf("path" to "b.js", "search" to "const k = 5;", "replace" to "const k = 7;")))
        val out = MultiEdit.run(items, { files[it] }, { if (it == "secret.txt") "protected" else null })
        assertEquals(1, out.applied)
        assertEquals(3, out.notes.size)
        assertTrue(out.notes[2].contains("protected"))
        assertEquals(listOf("b.js"), out.texts.keys.toList())
    }

    @Test fun parseIgnoresBadItemsAndCapsTheList() {
        assertTrue(MultiEdit.parse("x").isEmpty())
        assertEquals(1, MultiEdit.parse(listOf(mapOf("path" to "a", "search" to "s"), 5, mapOf("search" to "s"))).size)
        assertEquals(MultiEdit.MAX, MultiEdit.parse(List(30) { mapOf("path" to "a", "search" to "s", "replace" to "r") }).size)
    }
}
