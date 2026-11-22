package dev.pocketide.app

import dev.pocketide.app.core.Lsp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class LspTest {
    /** A minimal language server: answers initialize and reports one error when a file is opened. */
    private val fake = """
import sys, json
def read():
    n = 0
    while True:
        h = sys.stdin.buffer.readline().decode().strip()
        if h == '': break
        if h.lower().startswith('content-length'): n = int(h.split(':')[1])
    return json.loads(sys.stdin.buffer.read(n)) if n else None
def send(o):
    d = json.dumps(o).encode()
    sys.stdout.buffer.write(b'Content-Length: %d\r\n\r\n' % len(d) + d); sys.stdout.buffer.flush()
while True:
    m = read()
    if m is None: break
    if m.get('method') == 'initialize': send({'jsonrpc': '2.0', 'id': m['id'], 'result': {'capabilities': {}}})
    if m.get('method') == 'textDocument/didOpen':
        uri = m['params']['textDocument']['uri']
        send({'jsonrpc': '2.0', 'method': 'textDocument/publishDiagnostics', 'params': {'uri': uri, 'diagnostics': [
            {'range': {'start': {'line': 2, 'character': 0}, 'end': {'line': 2, 'character': 1}}, 'severity': 1, 'message': 'Expected ; here\nmore'},
            {'range': {'start': {'line': 5, 'character': 0}, 'end': {'line': 5, 'character': 1}}, 'severity': 4, 'message': 'a hint'}]}})
    if m.get('method') == 'textDocument/references':
        u = m['params']['textDocument']['uri']; pos = m['params']['position']
        send({'jsonrpc': '2.0', 'id': m['id'], 'result': [
            {'uri': u, 'range': {'start': {'line': pos['line'], 'character': pos['character']}, 'end': {'line': pos['line'], 'character': 9}}},
            {'uri': 'file:///project/game/other%20file.js', 'range': {'start': {'line': 9, 'character': 2}, 'end': {'line': 9, 'character': 8}}}]})
    if m.get('method') == 'textDocument/definition':
        send({'jsonrpc': '2.0', 'id': m['id'], 'result': [{'targetUri': 'file:///project/game/def.js', 'targetRange': {'start': {'line': 0, 'character': 0}, 'end': {'line': 4, 'character': 1}},
            'targetSelectionRange': {'start': {'line': 2, 'character': 9}, 'end': {'line': 2, 'character': 15}}}]})
"""

    @Test fun frameRoundTrip() {
        val msg = "{\"a\":1,\"b\":\"héllo\"}"
        assertEquals(msg, Lsp.read(ByteArrayInputStream(Lsp.frame(msg))))
        assertNull(Lsp.read(ByteArrayInputStream(ByteArray(0))))
    }

    @Test fun languages() {
        assertEquals("typescript", Lsp.languageId("src/a.ts"))
        assertEquals(null, Lsp.languageId("notes.md"))
    }

    @Test fun diagnosticsFromAServer() {
        val python = listOf("python3", "/usr/bin/python3", "/usr/local/bin/python3").firstOrNull { java.io.File(it).exists() }
        org.junit.Assume.assumeTrue("needs python3", python != null)
        val p = ProcessBuilder(python!!, "-c", fake).redirectError(java.io.File("/dev/null")).start()
        try {
            val lines = Lsp.diagnose(p.inputStream, p.outputStream, "/project", "game/main.js", "let x = 1\n", 10_000)
            assertEquals(listOf("line 3: Expected ; here"), lines)
        } finally { p.destroy() }
    }

    @Test fun silentServerTimesOut() {
        val out = ByteArrayOutputStream()
        val lines = Lsp.diagnose(ByteArrayInputStream(ByteArray(0)), out, "/p", "a.js", "x", 200)
        assertNull(lines)
    }

    private fun server(): Process? {
        val python = listOf("python3", "/usr/bin/python3", "/usr/local/bin/python3").firstOrNull { java.io.File(it).exists() } ?: return null
        return ProcessBuilder(python, "-c", fake).redirectError(java.io.File("/dev/null")).start()
    }

    @Test fun locateFindsAWholeWord() {
        assertEquals(1 to 4, Lsp.locate("let a = 1\nfoo(addItem)\naddItem2()", "addItem"))
        assertNull(Lsp.locate("additem()", "addItem"))
        assertEquals("game/a b.js", Lsp.relative("/work", "file:///work/game/a%20b.js"))
    }

    @Test fun referencesAndDefinitionFromAServer() {
        val p1 = server(); org.junit.Assume.assumeTrue("needs python3", p1 != null)
        try {
            val refs = Lsp.locations(p1!!.inputStream, p1.outputStream, "/project", "game/main.js", "x\n  addItem(3)\n", "references", 1, 2, 10_000)
            assertEquals(listOf(Lsp.Location("game/main.js", 2), Lsp.Location("game/other file.js", 10)), refs)
            val text = Lsp.formatLocations("addItem", "references", refs!!) { p, ln -> if (p == "game/main.js" && ln == 2) "  addItem(3)" else null }
            assertTrue(text, text.contains("addItem is defined or used in 2 place(s)") && text.contains("- game/main.js:2: addItem(3)"))
        } finally { p1?.destroy() }
        val p2 = server()!!
        try {
            assertEquals(listOf(Lsp.Location("game/def.js", 3)), Lsp.locations(p2.inputStream, p2.outputStream, "/project", "game/main.js", "addItem()", "definition", 0, 0, 10_000))
        } finally { p2.destroy() }
    }

    @Test fun nothingFoundSaysSo() {
        assertTrue(Lsp.formatLocations("zap", "definition", emptyList()) { _, _ -> null }.contains("no definition of zap"))
    }
}
