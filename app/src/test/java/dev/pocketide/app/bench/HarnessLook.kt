package dev.pocketide.app.bench

import dev.pocketide.app.core.MiniJson
import dev.pocketide.app.core.PageLook
import dev.pocketide.app.core.PageProbe
import dev.pocketide.app.core.PageSteps
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * look_at_preview in the benchmark (1.5.147, Copilot's HANDOFF-RAISE-CEILING WP2). On the phone the assistant can look at the running
 * page in a hidden browser; the harness had no such browser, so web tasks (all the realproject ones) were scored without the tool
 * the app offers. This drives headless Chromium through Playwright the way PreviewPane.look drives its WebView, with the app's own
 * pieces: PageLook.KEEP_CANVAS before any script, the steps from PageSteps (played in the page, polled until done), PageLook.REPORT,
 * and the report text from PageLook.summary. Files are served from the task's folder only; every other request is refused, so a page
 * cannot reach the internet. Log points (trace) are not supported here and say so.
 * Needs node with playwright-core (or playwright) on NODE_PATH, and a Chromium (POCKETIDE_BENCH_CHROME, or Playwright's own).
 */
object HarnessLook {
    const val WIDTH = 360
    const val HEIGHT = 780

    private val SCRIPT = """
const fs = require('fs'), path = require('path');
let pw; try { pw = require('playwright-core'); } catch (e) { pw = require('playwright'); }
const TYPES = { '.html': 'text/html', '.htm': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css',
  '.json': 'application/json', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.gif': 'image/gif', '.webp': 'image/webp',
  '.svg': 'image/svg+xml', '.wav': 'audio/wav', '.mp3': 'audio/mpeg', '.ogg': 'audio/ogg', '.txt': 'text/plain', '.wasm': 'application/wasm' };
(async () => {
  const a = JSON.parse(fs.readFileSync(0, 'utf8'));
  const exe = process.env.POCKETIDE_BENCH_CHROME;
  const b = await pw.chromium.launch(exe ? { executablePath: exe } : {});
  const ctx = await b.newContext({ viewport: { width: a.width, height: a.height }, deviceScaleFactor: 2, isMobile: false, hasTouch: true });
  const p = await ctx.newPage();
  const errors = [];
  // Chrome asks for /favicon.ico by itself, outside the page and its routes; a phone's WebView never does, so that request's error
  // is not the page's (1.5.149: on the runner it made every look's verdict FAIL)
  p.on('console', m => { const l = m.location(); const where = String(l.url || ''); if (m.type() === 'error' && errors.length < 20 && !/favicon\.ico$/.test(where)) errors.push(m.text() + ' (' + where.split('/').pop() + ':' + (l.lineNumber || 0) + ')'); });
  p.on('pageerror', e => { if (errors.length < 20) errors.push(String(e && e.message || e)); });
  p.on('dialog', d => d.dismiss().catch(() => {}));
  await p.addInitScript(a.keep);
  const root = path.resolve(a.dir);
  let outside = 0;
  await p.route('**/*', async r => {
    const u = new URL(r.request().url());
    // outside the project: answered empty instead of fetched (the phone is online; a network error here would fail pages that work there)
    if (u.host !== 'project.local') { outside++; return r.fulfill({ status: 204, body: '' }); }
    if (r.request().method() !== 'GET') return r.fulfill({ status: 204, body: '' });
    const f = path.resolve(root, decodeURIComponent(u.pathname.slice(1)) || a.entry);
    if (!(f === root || f.startsWith(root + path.sep)) || !fs.existsSync(f) || fs.statSync(f).isDirectory()) return r.fulfill({ status: 404, body: 'Not found' });
    return r.fulfill({ status: 200, body: fs.readFileSync(f), contentType: TYPES[path.extname(f).toLowerCase()] || 'application/octet-stream' });
  });
  await p.goto('https://project.local/' + a.entry, { waitUntil: 'load', timeout: 15000 }).catch(e => errors.push('the page did not finish loading: ' + e.message.split('\n')[0]));
  await p.waitForTimeout(2500);   // games build their first screen after load, as in the app
  let before = '', checks = '';
  if (a.steps) {
    before = await p.evaluate(a.state).catch(() => '');
    await p.evaluate(a.steps).catch(e => errors.push('the steps did not start: ' + e.message.split('\n')[0]));
    const t0 = Date.now();
    while (Date.now() - t0 < a.totalMs + 4000) {
      checks = await p.evaluate(a.results).catch(() => '');
      if (checks) break;
      await p.waitForTimeout(250);
    }
    await p.waitForTimeout(300);
  }
  const look = await p.evaluate(a.report).catch(() => null);
  const shot = (await p.screenshot({ type: 'jpeg', quality: 70, scale: 'css' }).catch(() => null));
  process.stdout.write(JSON.stringify({ look, errors, before, checks, outside, shot: shot ? shot.toString('base64') : '' }));
  await b.close();
})().catch(e => { process.stdout.write(JSON.stringify({ fatal: String(e && e.message || e).split('\n')[0] })); process.exit(0); });
""".trimStart()

    private val scriptFile: File by lazy { File.createTempFile("pocketide-look", ".js").also { it.writeText(SCRIPT); it.deleteOnExit() } }

    /** How long one look may take: loading, the settle, the steps and the report. */
    fun timeoutSeconds(stepsMs: Int): Long = 30L + stepsMs / 1000

    /** Whether node can load Playwright here (checked once). */
    val available: Boolean by lazy {
        try {
            val p = ProcessBuilder("node", "-e", "try{require('playwright-core')}catch(e){require('playwright')}").redirectErrorStream(true).start()
            p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
        } catch (e: Exception) { false }
    }

    /** The report and screenshot (a data URL, or null) for [entry] in [dir] after [steps], as the app's look_at_preview gives them. */
    fun look(dir: File, entry: String, steps: String, trace: String): Pair<String, String?> {
        val plan = PageSteps.parse(steps)
        if (plan.error != null) return "Steps not played: ${plan.error}. Nothing was loaded; fix the steps and call look_at_preview again." to null
        val req = MiniJson.write(mapOf(
            "dir" to dir.absolutePath, "entry" to entry, "width" to WIDTH, "height" to HEIGHT,
            "keep" to PageLook.KEEP_CANVAS.removePrefix("<script>").removeSuffix("</script>"),
            "steps" to (if (plan.steps.isEmpty()) "" else PageSteps.script(plan.steps)), "totalMs" to plan.totalMs,
            "state" to PageSteps.STATE, "results" to PageSteps.RESULTS, "report" to PageLook.REPORT))
        val out = try {
            val p = ProcessBuilder("node", scriptFile.absolutePath).redirectError(File("/dev/null")).start()
            p.outputStream.use { it.write(req.toByteArray()) }
            // 1.5.151 (Copilot, ANALYSIS-1.5.150 P2): the output is read on its own thread, so the wait below can time out; before, the
            // read blocked until the browser exited, and a hung browser held the task until the 900 s limit
            val buf = StringBuilder()
            val reader = Thread { try { buf.append(p.inputStream.bufferedReader().readText()) } catch (e: Exception) { } }.also { it.isDaemon = true; it.start() }
            if (!p.waitFor(timeoutSeconds(plan.totalMs), TimeUnit.SECONDS)) {
                p.destroyForcibly(); reader.join(2000)
                return PageLook.summary(null, listOf("the page check timed out after ${timeoutSeconds(plan.totalMs)} s and was stopped"), "") to null
            }
            reader.join(5000)
            buf.toString()
        } catch (e: Exception) { return "The page could not be checked here (${e.message})." to null }
        val r = MiniJson.obj(MiniJson.parse(out.ifBlank { "{}" })) ?: emptyMap()
        (r["fatal"] as? String)?.let { return "The page could not be checked here ($it)." to null }
        val look = PageProbe.parse(r["look"] as? String)
        val errors = (r["errors"] as? List<*>).orEmpty().map { it.toString() }
        val shot = (r["shot"] as? String).orEmpty()
        val img = if (shot.isEmpty()) null else "data:image/jpeg;base64,$shot"
        val shotNote = if (img == null) "Screenshot: could not be taken." else "Screenshot: attached (${WIDTH}px wide)."
        var text = if (plan.steps.isEmpty()) PageLook.summary(look, errors, shotNote) else {
            val checks = PageSteps.results(r["checks"] as? String)
            val was = PageSteps.text(r["before"] as? String)
            val prefix = "Played ${plan.steps.size} step${if (plan.steps.size == 1) "" else "s"}" + (if (checks == null) " (they did not finish in time)" else "") +
                "; the report below is after them." + (if (was.isNotEmpty()) "\nGame state before the steps: $was" else "") + "\n"
            val s = PageLook.summary(look, errors, shotNote, checks ?: listOf(PageSteps.Check("steps", false, "did not finish within ${plan.totalMs + 4000} ms")))
            val nl = s.indexOf('\n'); if (nl < 0) s else s.substring(0, nl + 1) + prefix + s.substring(nl + 1)
        }
        val outside = (r["outside"] as? Number)?.toInt() ?: 0
        if (outside > 0) text += "\n$outside request${if (outside == 1) "" else "s"} to addresses outside the project were answered empty in this check (no internet here)."
        if (trace.isNotBlank()) text += "\n\nLog points (trace) are not available in this check; use steps with expect instead."
        return text to img
    }
}
