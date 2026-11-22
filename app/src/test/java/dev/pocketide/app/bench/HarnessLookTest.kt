package dev.pocketide.app.bench

import dev.pocketide.app.bench.ScriptedModel.Companion.call
import dev.pocketide.app.bench.ScriptedModel.Companion.say
import dev.pocketide.app.bench.ScriptedModel.Companion.tools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** 1.5.147: look_at_preview in the benchmark, in headless Chromium, reported as the app reports it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HarnessLookTest {
    private val page = mapOf(
        "index.html" to "<!doctype html><html><head></head><body>" +
            "<button id=\"go\" style=\"width:120px;height:60px\">Go</button><p id=\"out\">ready</p><script src=\"game.js\"></script></body></html>",
        "game.js" to "document.getElementById('go').addEventListener('click', () => { document.getElementById('out').textContent = 'went'; document.body.dataset.ok = '1'; });\n" +
            "window.render_game_to_text = () => 'out=' + document.getElementById('out').textContent;\n" +
            "fetch('https://example.com/leak').catch(() => {});\n")

    @Test fun stepsPlayAndTheReportIsTheApps() {
        assumeTrue("node with Playwright is needed", HarnessLook.available)
        val dir = Files.createTempDirectory("look").toFile()
        try {
            for ((p, t) in page) File(dir, p).writeText(t)
            val (report, img) = HarnessLook.look(dir, "index.html", "tap #go; expect document.body.dataset.ok === '1'", "")
            assertTrue(report, report.contains("VERDICT"))
            assertTrue(report, report.contains("Played 2 steps") && report.contains("Game state before the steps: out=ready"))
            assertFalse(report, report.contains("VERDICT: FAIL"))
            assertTrue(report, report.contains("1 request to addresses outside the project were answered empty"))
            // laid out phone-wide, as a phone's WebView does, not at a desktop width
            assertTrue(report, report.contains("Screen: 360x780 CSS pixels"))
            assertNotNull(img); assertTrue(img!!.startsWith("data:image/jpeg;base64,"))
            val (bad, _) = HarnessLook.look(dir, "index.html", "expect document.body.dataset.ok === '1'", "")
            assertTrue(bad, bad.contains("VERDICT: FAIL"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun theModelCanLookDuringARun() {
        assumeTrue("node with Playwright is needed", HarnessLook.available)
        val o = AgentHarness(page, "The Go button on the page does nothing. Check it on the page.", ScriptedModel(listOf(
            tools(call("look_at_preview", "steps" to "tap #go; expect document.body.dataset.ok === '1'")),
            say("The button works: tapping it shows 'went'."))), look = true).run()
        assertFalse("timed out", o.timedOut)
        val result = o.resultsOf("look_at_preview").single()
        assertTrue(result, result.contains("VERDICT") && result.contains("Played 2 steps"))
    }

    @Test fun offWithoutTheSetting() {
        val o = AgentHarness(page, "The Go button on the page does nothing. Check it on the page.", ScriptedModel(listOf(
            tools(call("look_at_preview", "steps" to "tap #go")), say("Checked."))), look = false).run()
        assertEquals(1, o.resultsOf("look_at_preview").size)
        assertTrue(o.resultsOf("look_at_preview").single().startsWith(dev.pocketide.app.core.LookOffer.NO_PAGE))
    }

    // 1.5.151 (P2): a page that never lets the browser finish is stopped at the time limit, not at the task's 900 s
    @Test fun aHungPageIsStoppedInTime() {
        assumeTrue("node with Playwright is needed", HarnessLook.available)
        val dir = Files.createTempDirectory("look").toFile()
        try {
            File(dir, "index.html").writeText("<!doctype html><html><body><script>while (true) {}</script></body></html>")
            val t0 = System.currentTimeMillis()
            val (report, img) = HarnessLook.look(dir, "index.html", "", "")
            val took = (System.currentTimeMillis() - t0) / 1000
            assertTrue("took $took s", took <= HarnessLook.timeoutSeconds(0) + 10)
            assertTrue(report, report.contains("VERDICT") || report.contains("could not be checked"))
            assertEquals(null, img)
        } finally { dir.deleteRecursively() }
    }

    // 1.5.153: the harbour chart (a few small dots) is a drawing, a blank canvas is still flat, and the icon off the edge is reported
    @Test fun sparseDrawingIsNotFlat() {
        assumeTrue("node with Playwright is needed", HarnessLook.available)
        val dir = Files.createTempDirectory("look").toFile()
        try {
            for ((p, text) in HarbourBenchTasks.files) File(dir, p).also { it.parentFile.mkdirs() }.writeText(text)
            val (text, _) = HarnessLook.look(dir, "index.html", "", "")
            assertFalse(text, text.contains("draws nothing"))
            assertTrue(text, text.contains("<img> at -22, 8, 48x48"))
            File(dir, "blank.html").writeText("<!doctype html><canvas width=400 height=400></canvas><script>document.querySelector('canvas').getContext('2d');</script>")
            assertTrue(HarnessLook.look(dir, "blank.html", "", "").first.contains("draws nothing"))
        } finally { dir.deleteRecursively() }
    }

    // 1.5.154 (Copilot, R6): a WebGL canvas is read (KEEP_CANVAS keeps its buffer): one clear colour is flat, a drawn triangle is not
    @Test fun webglCanvasesAreRead() {
        assumeTrue("node with Playwright is needed", HarnessLook.available)
        val dir = Files.createTempDirectory("look").toFile()
        fun page(draw: Boolean) = "<!doctype html><html><head></head><body><canvas width=400 height=400></canvas><script>" +
            "var gl=document.querySelector('canvas').getContext('webgl');gl.clearColor(0.1,0.2,0.3,1);gl.clear(gl.COLOR_BUFFER_BIT);" +
            (if (draw) "var p=gl.createProgram();function sh(t,s){var x=gl.createShader(t);gl.shaderSource(x,s);gl.compileShader(x);gl.attachShader(p,x);}" +
                "sh(gl.VERTEX_SHADER,'attribute vec2 a;void main(){gl_Position=vec4(a,0,1);}');sh(gl.FRAGMENT_SHADER,'void main(){gl_FragColor=vec4(1,0.5,0,1);}');" +
                "gl.linkProgram(p);gl.useProgram(p);var b=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,b);" +
                "gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([0,0.05,-0.05,-0.05,0.05,-0.05]),gl.STATIC_DRAW);gl.enableVertexAttribArray(0);" +
                "gl.vertexAttribPointer(0,2,gl.FLOAT,false,0,0);gl.drawArrays(gl.TRIANGLES,0,3);" else "") + "</script></body></html>"
        try {
            File(dir, "flat.html").writeText(page(false))
            File(dir, "drawn.html").writeText(page(true))
            val flat = HarnessLook.look(dir, "flat.html", "", "").first
            val drawn = HarnessLook.look(dir, "drawn.html", "", "").first
            if (flat.contains("could not be checked")) return   // no WebGL in this browser
            assertTrue(flat, flat.contains("draws nothing"))
            assertFalse(drawn, drawn.contains("draws nothing"))
        } finally { dir.deleteRecursively() }
    }

    // 1.5.160 (Copilot's Q3): an overlap is reported only when a box covers text: an icon set on a bar beside its text is not, an
    // icon over the text is, a dialog over a page reports the page text it covers (not the other way round), and a fixed button
    // over a heading (a second, different defect) is reported too
    @Test fun onlyTextCoveringOverlapsAreReported() {
        assumeTrue("node with Playwright is needed", HarnessLook.available)
        val dir = Files.createTempDirectory("look").toFile()
        fun bar(pad: Int) = "<!doctype html><html><head><style>body{margin:0}#hud{position:relative;height:64px;line-height:64px;padding-left:${pad}px;" +
            "background:#246;color:#fff;font:20px sans-serif}#ship{position:absolute;left:8px;top:8px;width:48px;height:48px;z-index:2}</style></head>" +
            "<body><header id=\"hud\">Brig<img id=\"ship\" src=\"data:image/gif;base64,R0lGODlhAQABAIAAAP///wAAACH5BAEAAAAALAAAAAABAAEAAAICRAEAOw==\"></header></body></html>"
        try {
            File(dir, "beside.html").writeText(bar(64))
            File(dir, "under.html").writeText(bar(20))
            File(dir, "dialog.html").writeText("<!doctype html><html><body style=\"margin:0;font:18px sans-serif\"><p id=\"a\">Your ledger shows every trade you made this week.</p>" +
                "<div id=\"modal\" style=\"position:fixed;left:10px;top:0;width:200px;height:80px;background:#fff;border:1px solid #000\">Saved</div></body></html>")
            File(dir, "heading.html").writeText("<!doctype html><html><body style=\"margin:0\"><h1 id=\"t\" style=\"margin:0;font:28px sans-serif\">Harbour Master</h1>" +
                "<button id=\"menu\" style=\"position:fixed;left:0;top:0;width:200px;height:60px\">Menu</button></body></html>")
            val beside = HarnessLook.look(dir, "beside.html", "", "").first
            assertFalse(beside, beside.contains("Overlap:"))
            val under = HarnessLook.look(dir, "under.html", "", "").first
            assertTrue(under, under.contains("Overlap: <img#ship> covers the text of <header#hud> \"Brig\""))
            val dialog = HarnessLook.look(dir, "dialog.html", "", "").first
            assertTrue(dialog, dialog.contains("Overlap: <div#modal> \"Saved\" covers the text of <p#a>"))
            assertFalse(dialog, dialog.contains("covers the text of <div#modal>"))
            val heading = HarnessLook.look(dir, "heading.html", "", "").first
            assertTrue(heading, heading.contains("Overlap: <button#menu> \"Menu\" covers the text of <h1#t> \"Harbour Master\""))
        } finally { dir.deleteRecursively() }
    }

    // 1.5.160: on the harbour page the narrow icon fix (only the icon's box clamped) leaves the top bar's text under the icon, and
    // the page check now says so; the real fix (the shared --edge clamped) reports no overlap
    @Test fun theNarrowIconFixIsVisibleToThePageCheck() {
        assumeTrue("node with Playwright is needed", HarnessLook.available)
        fun lookWith(from: String, to: String): String {
            val dir = Files.createTempDirectory("look").toFile()
            try {
                for ((p, text) in HarbourBenchTasks.files) File(dir, p).also { it.parentFile.mkdirs() }.writeText(if (p == "util/layout.js") text.replace(from, to) else text)
                return HarnessLook.look(dir, "index.html", "", "").first
            } finally { dir.deleteRecursively() }
        }
        val narrow = lookWith("return { left: edge + INSET,", "return { left: Math.max(0, edge) + INSET,")
        assertTrue(narrow, narrow.contains("Overlap: <img#ship> covers the text of <header#hud>"))
        val real = lookWith("const edge = centre(viewport, BOARD);", "const edge = Math.max(0, centre(viewport, BOARD));")
        assertFalse(real, real.contains("Overlap:"))
    }
}
