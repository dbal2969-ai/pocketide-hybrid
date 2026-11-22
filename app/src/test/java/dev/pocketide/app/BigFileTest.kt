package dev.pocketide.app

import dev.pocketide.app.core.OutputLimit
import dev.pocketide.app.core.PageRefs
import org.junit.Test

class BigFileTest {
    @Test fun big() {
        val f = ArrayList<String>()
        fun check(ok: Boolean, m: String) { if (!ok) f.add(m) }
        check(OutputLimit.maxTokens("claude-opus-5-5") == 16000 && OutputLimit.maxTokens("claude-sonnet-4-6") == 16000, "modern")
        check(OutputLimit.maxTokens("claude-3-5-haiku-latest") == 4096 && OutputLimit.maxTokens("claude-3-5-sonnet-20241022") == 8192, "old")
        check(OutputLimit.isCut("max_tokens") && OutputLimit.isCut("length") && !OutputLimit.isCut("end_turn") && !OutputLimit.isCut("tool_use"), "cut")
        check(OutputLimit.validArgs("{\"path\":\"a\"}") && OutputLimit.validArgs("") && !OutputLimit.validArgs("{\"path\":\"game.js\",\"content\":\"function a("), "valid")
        check(OutputLimit.pathOf("{\"path\":\"game.js\",\"content\":\"func") == "game.js", "path")
        check(OutputLimit.cutNote(listOf("write_file game.js")).contains("game.js") && OutputLimit.cutNote(emptyList()).contains("smaller pieces"), "note")
        check(OutputLimit.cutNote(emptyList(), listOf("index.html", "game.js")).contains("engine.js"), "a web project hears about files the page loads")
        check(!OutputLimit.cutNote(emptyList(), listOf("dominoes.py")).contains("engine.js") && OutputLimit.cutNote(emptyList(), listOf("dominoes.py")).contains("modules that import each other"), "a Python project does not (1.5.115)")

        // the uploaded RuneScape project: page loads a missing game.js, buttons call undefined functions, style.css is a stub
        val html = """<html><head><link rel="stylesheet" href="style.css"><link rel="manifest" href="manifest.json"></head><body>
            <img src="assets/rslogo.png"><button onclick="showLogin()">Play</button><button onclick="doLogin()">Login</button>
            <script src="offline.js"></script><script src="game.js"></script><script src="https://cdn.example.com/x.js"></script>
            <script>function showLogin(){}</script></body></html>"""
        val files = mapOf("index.html" to html, "style.css" to "", "manifest.json" to "{}", "offline.js" to "console.log(1)", "assets/rslogo.png" to "")
        val p = PageRefs.problems(files.keys.toList(), { files[it] }, { files[it]?.length?.toLong() ?: 0L })
        check(p.any { it.contains("game.js") && it.contains("does not exist") }, "missing game.js: $p")
        check(p.any { it.contains("style.css") && it.contains("empty") }, "empty css: $p")
        check(p.any { it.contains("doLogin") }, "undefined handler: $p")
        check(p.none { it.contains("showLogin") }, "inline function counted: $p")
        check(p.none { it.contains("cdn.example.com") || it.contains("rslogo") }, "external/image ignored: $p")
        val ok = mapOf("index.html" to "<script src=\"js/app.js\"></script><button onclick=\"go()\">x</button>", "js/app.js" to "function go(){}")
        check(PageRefs.problems(ok.keys.toList(), { ok[it] }, { ok[it]?.length?.toLong() ?: 0L }).isEmpty(), "clean page")
        val arrow = mapOf("index.html" to "<button onclick=\"start()\">x</button><script src=\"a.js\"></script>", "a.js" to "const start = () => {}")
        check(PageRefs.problems(arrow.keys.toList(), { arrow[it] }, { 5L }).isEmpty(), "arrow function")
        val nested = mapOf("web/index.html" to "<script src=\"app.js\"></script>", "web/app.js" to "x=1")
        check(PageRefs.problems(nested.keys.toList(), { nested[it] }, { 5L }).isEmpty(), "relative to page folder")
        if (f.isNotEmpty()) throw AssertionError(f.joinToString("; "))
    }
}
