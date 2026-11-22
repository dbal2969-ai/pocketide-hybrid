package dev.pocketide.app

import dev.pocketide.app.core.PageProbe
import org.junit.Test

class PageProbeTest {
    @Test
    fun pageProbeChecks() {
        PageProbeSelfTest.runAll()
    }
}

object PageProbeSelfTest {
    private var fails = 0
    private var passes = 0

    private fun ok(c: Boolean, m: String) {
        if (c) passes++ else fails++
        println((if (c) "PASS  " else "FAIL  ") + m)
    }

    private fun look(text: String = "", media: Double = 0.0, painted: Double = 0.0, canvases: List<Any?> = emptyList(), buttons: Double = 0.0): Map<String, Any?> =
        mapOf("text" to text, "visible" to 5.0, "media" to media, "painted" to painted, "buttons" to buttons, "canvases" to canvases)

    fun runAll() {
        parsing(); judging(); labels(); scripts()
        println("\n$passes passed, $fails failed")
        check(fails == 0) { "$fails page probe checks failed" }
    }

    private fun parsing() {
        val raw = "\"{\\\"text\\\":\\\"Hi\\\",\\\"media\\\":2,\\\"canvases\\\":[true,null]}\""
        val m = PageProbe.parse(raw)
        ok(m != null && m["text"] == "Hi" && m["media"] == 2.0, "parses what evaluateJavascript returns")
        ok((m?.get("canvases") as? List<*>) == listOf(true, null), "canvas list survives")
        ok(PageProbe.parse(null) == null && PageProbe.parse("null") == null && PageProbe.parse("\"nonsense\"") == null, "missing or broken results give null")
        ok(PageProbe.parse("{\"text\":\"x\"}")?.get("text") == "x", "a bare object also parses")
    }

    private fun judging() {
        ok(PageProbe.problems(look(), look(), false).any { it.contains("shows nothing") }, "an empty page is reported")
        val sizeless = look() + mapOf("canvasTotal" to 1.0)
        ok(PageProbe.problems(sizeless, sizeless, true).none { it.contains("shows nothing") }, "a canvas game that has not sized itself yet is not an empty page")
        ok(PageProbe.problems(look("Hello"), look("Hello"), false).isEmpty(), "a page with text is fine")
        ok(PageProbe.problems(look(media = 1.0), look(media = 1.0), false).isEmpty(), "a page with only a button or image is fine")
        ok(PageProbe.problems(look(painted = 3.0), look(painted = 3.0), false).isEmpty(), "a page drawn with colored boxes is fine")
        ok(PageProbe.problems(look(), look("Score: 3"), false).isEmpty(), "content that appears after tapping is fine")
        ok(PageProbe.problems(mapOf("nobody" to true), null, false).isEmpty(), "no body: nothing to judge")
        ok(PageProbe.problems(null, null, false).isEmpty(), "no result: nothing to judge")
        ok(PageProbe.problems(look("Total: NaN"), look("Total: NaN"), false).any { it.contains("NaN") }, "NaN on screen is reported")
        ok(PageProbe.problems(look("Hello undefined"), null, false).any { it.contains("undefined") }, "undefined on screen is reported")
        ok(PageProbe.problems(look("Name: [object Object]"), null, false).any { it.contains("[object Object]") }, "[object Object] on screen is reported")
        ok(PageProbe.problems(look("Banana", media = 1.0), null, false).isEmpty(), "words that merely contain NaN are fine")
        ok(PageProbe.problems(look("x", media = 1.0, canvases = listOf(true)), look("x", media = 1.0, canvases = listOf(true)), true).any { it.contains("canvas") }, "a flat canvas in an animated project is reported")
        ok(PageProbe.problems(look("x", media = 1.0, canvases = listOf(true), buttons = 1.0), look("x", media = 1.0, canvases = listOf(true), buttons = 1.0), true).isEmpty(), "a flat canvas next to a Start button is not reported (drawing may wait for a tap)")
        ok(PageProbe.relevant(listOf("notes.md", "index.html")) && PageProbe.relevant(listOf("a/b.CSS")) && PageProbe.relevant(listOf("game.js")), "web files are worth checking")
        ok(!PageProbe.relevant(listOf("AGENTS.md", "README.md", "data.json", "main.py", "x.txt")) && !PageProbe.relevant(emptyList()), "notes, docs, data and other languages are not")
        ok(PageProbe.problems(look("x", media = 1.0, canvases = listOf(true)), look("x", media = 1.0, canvases = listOf(true)), false).isEmpty(), "a flat canvas in a static project is fine")
        ok(PageProbe.problems(look("x", media = 1.0, canvases = listOf(false)), look("x", media = 1.0, canvases = listOf(false)), true).isEmpty(), "a drawn canvas is fine")
        ok(PageProbe.problems(look("x", media = 1.0, canvases = listOf(null)), look("x", media = 1.0, canvases = listOf(null)), true).isEmpty(), "an unreadable canvas (WebGL) is not judged")
        ok(PageProbe.problems(look("x", media = 1.0, canvases = listOf(true, false)), null, true).isEmpty(), "one drawn canvas among flat ones is fine")
    }

    private fun labels() {
        for (l in listOf("Delete", "Clear all", "Log out", "Sign out", "Submit order", "Buy now", "Send", "Reset scores", "Download", "Pay $5", "Unsubscribe")) {
            ok(PageProbe.avoided(l), "not tapped: $l")
        }
        for (l in listOf("Start", "Play", "+", "Add item", "Next", "Roll dice", "Jump", "Left", "Menu", "Toggle theme")) {
            ok(!PageProbe.avoided(l), "tapped: $l")
        }
        ok(PageProbe.hasDrawingLoop(mapOf("game.js" to "function loop(){requestAnimationFrame(loop)}")), "drawing loop found in js")
        ok(PageProbe.hasDrawingLoop(mapOf("index.html" to "<script>setInterval(tick,100)</script>")), "timer found in html")
        ok(!PageProbe.hasDrawingLoop(mapOf("style.css" to "a{color:red} /* requestAnimationFrame */", "index.html" to "<p>hi</p>")), "no loop in plain pages (css ignored)")
    }

    private fun scripts() {
        ok(PageProbe.LOOK.startsWith("(function(){") && PageProbe.LOOK.endsWith("})()"), "look script is a self-running function")
        ok(PageProbe.tapScript(5).contains("n<5"), "tap limit is built in")
        ok(PageProbe.tapScript().contains(PageProbe.AVOID), "tap script carries the avoid list")
        System.getenv("PROBE_OUT")?.let { dir ->
            java.io.File(dir, "look.js").writeText(PageProbe.LOOK)
            java.io.File(dir, "tap.js").writeText(PageProbe.tapScript())
        }
        ok(!PageProbe.LOOK.contains("\$max") && !PageProbe.tapScript().contains("\$max"), "no unreplaced placeholders")
        // 1.5.106: controls wired with addEventListener (inventory slots) are noted before any page script runs, and tapped
        val page = PageProbe.recordTaps("<html><head><title>x</title><script src=\"main.js\"></script></head><body></body></html>")
        ok(page.indexOf(PageProbe.RECORD_TAPS) in 0 until page.indexOf("main.js"), "the tap recorder runs before the page's scripts")
        ok(PageProbe.recordTaps("<p>no head</p>").startsWith(PageProbe.RECORD_TAPS), "a page with no head still gets it")
        ok(PageProbe.RECORD_TAPS.contains("touchend)\$/") && !PageProbe.RECORD_TAPS.contains("\\\$"), "the event regex ends with a plain \$")
        ok(PageProbe.tapScript().contains("__pkTaps") && PageProbe.tapScript().contains("'pointerdown'"), "recorded controls get a finger's events")
    }
}
