package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.143, Cursor parity P4 (experimental): Sweep's next-edit format and turning its window into one checked edit. */
class SweepNextEditTest {
    private val before = "function area(w, h) {\n  return w * h;\n}\n\nconst a = area(2, 3);\nconsole.log(a);\n"
    private val now = "function area(w, h, unit) {\n  return w * h;\n}\n\nconst a = area(2, 3);\nconsole.log(a);\n"

    @Test fun recognisesTheModel() {
        assertTrue(SweepNextEdit.isSweep("hf.co/sweepai/sweep-next-edit-1.5B:Q8_0"))
        assertTrue(SweepNextEdit.isSweep("sweep_next_edit"))
        assertFalse(SweepNextEdit.isSweep("qwen2.5-coder:1.5b"))
    }

    @Test fun buildsTheFormatSweepDescribes() {
        val recent = NextEdit.recentEdit(before, now)!!
        val (prompt, win) = SweepNextEdit.prompt("src/geo.js", before, now, recent, now.indexOf("unit"))
        val sep = SweepNextEdit.SEP
        assertTrue(prompt, prompt.startsWith("${sep}src/geo.js.diff\noriginal:\nfunction area(w, h) {\nupdated:\nfunction area(w, h, unit) {\n"))
        assertTrue(prompt, prompt.contains("${sep}original/src/geo.js\nfunction area(w, h) {\n"))
        assertTrue(prompt, prompt.contains("${sep}current/src/geo.js\nfunction area(w, h, unit) {\n"))
        assertTrue(prompt, prompt.endsWith("${sep}updated/src/geo.js\n"))
        assertEquals(0, win.first)
        assertEquals(now.split('\n').joinToString("\n"), win.second)   // a short file is all window
    }

    @Test fun theWindowIsTwentyOneLinesAroundTheCursor() {
        val lines = (1..60).map { "line $it" }
        val (from, w) = SweepNextEdit.window(lines, 30)
        assertEquals(20, from)
        assertEquals(21, w.size)
        assertEquals("line 31", w[10])
        assertEquals(0, SweepNextEdit.window(lines, 2).first)
        assertEquals(60, SweepNextEdit.window(lines, 59).let { it.first + it.second.size })
    }

    @Test fun theAnswerBecomesOneCheckedEdit() {
        val recent = NextEdit.recentEdit(before, now)!!
        val (_, win) = SweepNextEdit.prompt("src/geo.js", before, now, recent, now.indexOf("unit"))
        val answer = win.second.replace("const a = area(2, 3);", "const a = area(2, 3, 'cm');") + "\n<|file_sep|>junk"
        val s = SweepNextEdit.suggestion(win.second, SweepNextEdit.rewritten(answer))!!
        assertEquals("const a = area(2, 3);", s.old)
        assertEquals("const a = area(2, 3, 'cm');", s.new)
        val v = NextEdit.validate(now, s, recent)
        assertTrue(v.toString(), v is NextEdit.Verdict.Ok)
        // an answer that only repeats the window, or touches the line just edited, shows nothing
        assertNull(SweepNextEdit.suggestion(win.second, SweepNextEdit.rewritten(win.second + "\n")))
        val undo = SweepNextEdit.suggestion(win.second, win.second.replace("function area(w, h, unit) {", "function area(w, h) {"))!!
        assertTrue(NextEdit.validate(now, undo, recent) is NextEdit.Verdict.No)
    }

    @Test fun anInsertionKeepsTheLineAbove() {
        val s = SweepNextEdit.suggestion("a\nb\nc", "a\nb\nx\nc")!!
        assertEquals("b", s.old)
        assertEquals("b\nx", s.new)
    }

    @Test fun completionsAddressAndAnswer() {
        assertEquals("http://127.0.0.1:11434/v1/completions", SweepNextEdit.url("http://127.0.0.1:11434/v1/chat/completions"))
        assertNull(SweepNextEdit.url("https://api.anthropic.com/v1/messages"))
        assertNull(SweepNextEdit.url(null))
        val body = SweepNextEdit.requestBody("m", "p")
        assertTrue(body, body.contains("\"prompt\":\"p\"") && body.contains("\"temperature\":0") && body.contains("<|file_sep|>"))
        assertEquals("x\ny", SweepNextEdit.parseCompletion("""{"choices":[{"text":"x\ny","index":0}]}"""))
        assertNull(SweepNextEdit.parseCompletion("""{"error":"no"}"""))
    }

    // 1.5.146: Ollama gets its native raw call, so no chat template wraps Sweep's format
    @Test fun ollamaIsAskedRaw() {
        assertEquals("http://127.0.0.1:11434/api/generate", SweepNextEdit.ollamaUrl("http://127.0.0.1:11434/v1/completions", "custom"))
        assertEquals("http://phone:8080/api/generate", SweepNextEdit.ollamaUrl("http://phone:8080/v1/completions", "ollama"))
        assertNull(SweepNextEdit.ollamaUrl("http://127.0.0.1:8080/v1/completions", "llamacpp"))
        val body = SweepNextEdit.ollamaBody("m", "p")
        assertTrue(body, body.contains("\"raw\":true") && body.contains("\"stream\":false") && body.contains("\"num_predict\":512"))
        assertEquals("x", SweepNextEdit.parseOllama("""{"model":"m","response":"x","done":true}"""))
    }
}
