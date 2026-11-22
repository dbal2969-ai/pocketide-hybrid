package dev.pocketide.app.core

/**
 * Error reports from apps published with /deploy cloudflare (1.5.125). A published page had no way to tell its owner it broke:
 * the error stayed in a visitor's browser. Now the uploaded copy of every .html file loads a small reporter script that sends
 * uncaught errors, unhandled promise rejections, console.error and files that failed to load to /__pocketide/errors on the
 * same Worker; the Worker keeps the newest ones, and PocketIDE reads them with a key only the owner's phone has.
 *
 * Nothing in the project's own files changes: the script is an extra asset, the <script> tag is added to the uploaded bytes only,
 * and a project's own backend (worker.js) is wrapped by a generated entry module that imports it unchanged.
 *
 * Where reports are kept: a Worker has no storage without a binding. Creating a Workers KV namespace needs the token permission
 * "Workers KV Storage: Edit" (Cloudflare's API reference for POST /accounts/{id}/storage/kv/namespaces), which the token the app
 * asks for (Workers Scripts: Edit, Account Settings: Read) does not have. So the deploy tries KV, and when Cloudflare says no it
 * keeps the last [MAX] reports in the Worker's memory instead; the app says plainly that those are lost when Cloudflare restarts
 * the Worker, and that each running copy of the Worker keeps its own list.
 *
 * Pure logic: the JavaScript texts, the HTML change, the stored deploy record, reading the errors and the report for the assistant.
 */
object CfErrors {
    /** The Worker secret holding the read key; also kept with the project's secrets so the app can send it. */
    const val KEY_NAME = "POCKETIDE_ERRORS_KEY"
    /** The KV binding, when the token could make a namespace. */
    const val KV_NAME = "POCKETIDE_ERRORS"
    const val HEADER = "x-pocketide-key"
    const val ROUTE = "/__pocketide/*"
    const val ERRORS_PATH = "/__pocketide/errors"
    /** The reporter script, served as a static asset. */
    const val SCRIPT_PATH = "__pocketide/report.js"
    /** The generated entry module that wraps a project's backend. */
    const val ENTRY = "__pocketide_entry.js"
    /** Where the app remembers the last Cloudflare deploy of a project (in the project's meta folder, not its files). */
    const val INFO_FILE = "cf-deploy.json"
    const val MAX = 50

    fun kvTitle(script: String) = "pocketide-errors-$script"

    fun newKey(): String = ByteArray(24).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /**
     * Runs in the visitor's browser. Plain ES5 so old phone browsers run it too. At most 10 reports per page load, the same
     * error only once, every field cut to a size, so a page that throws in a loop cannot flood the Worker.
     */
    val REPORTER = """
        |// PocketIDE error reporter (1.5.125): added by /deploy cloudflare to the published copy only. Sends errors this page hits
        |// to its own site, where the owner's PocketIDE reads them. Remove by publishing without it; the project's files never had it.
        |(function () {
        |  if (window.__pocketideReporter) return;
        |  window.__pocketideReporter = true;
        |  var endpoint = "/__pocketide/errors", sent = 0, seen = {}, MAX = 10;
        |  function cut(v, n) { v = v == null ? "" : String(v); return v.length > n ? v.slice(0, n) : v; }
        |  function send(kind, message, source, line, column, stack) {
        |    if (sent >= MAX) return;
        |    var id = kind + "|" + message + "|" + source + "|" + line;
        |    if (seen[id]) return;
        |    seen[id] = true; sent++;
        |    var body = JSON.stringify({ kind: kind, message: cut(message, 500), source: cut(source, 300), line: line | 0, column: column | 0,
        |      stack: cut(stack, 2000), url: cut(location.href, 300), ua: cut(navigator.userAgent, 200), time: new Date().toISOString() });
        |    try { if (navigator.sendBeacon && navigator.sendBeacon(endpoint, new Blob([body], { type: "application/json" }))) return; } catch (e) {}
        |    try { fetch(endpoint, { method: "POST", body: body, headers: { "content-type": "application/json" }, keepalive: true })["catch"](function () {}); } catch (e) {}
        |  }
        |  window.addEventListener("error", function (e) {
        |    var t = e && e.target;
        |    if (t && t !== window && (t.src || t.href)) { send("resource", "Could not load " + (t.src || t.href), t.src || t.href, 0, 0, ""); return; }
        |    send("error", e.message || (e.error && e.error.message) || "Script error", e.filename, e.lineno, e.colno, e.error && e.error.stack);
        |  }, true);
        |  window.addEventListener("unhandledrejection", function (e) {
        |    var r = e && e.reason;
        |    send("promise", (r && r.message) || String(r), "", 0, 0, r && r.stack);
        |  });
        |  var original = console.error;
        |  console.error = function () {
        |    try {
        |      var parts = [], stack = "";
        |      for (var i = 0; i < arguments.length; i++) {
        |        var a = arguments[i];
        |        if (a instanceof Error) { parts.push(a.message); stack = stack || a.stack; }
        |        else if (typeof a === "string") parts.push(a);
        |        else { try { parts.push(JSON.stringify(a)); } catch (x) { parts.push(String(a)); } }
        |      }
        |      send("console", parts.join(" "), "", 0, 0, stack);
        |    } catch (x) {}
        |    return original.apply(console, arguments);
        |  };
        |})();
        |""".trimMargin()

    /**
     * The Worker side, shared by the static Worker and the wrapper: answers /__pocketide/errors (POST open but capped, GET and
     * DELETE only with the key) and passes the other /__pocketide/ files (the reporter) to the assets.
     */
    val HANDLER = """
        |// PocketIDE error reports (1.5.125): visitors' browsers POST errors here; the owner's PocketIDE reads them with the key.
        |const PI_PATH = "/__pocketide/errors";
        |const PI_MAX = $MAX;           // newest reports kept
        |const PI_BODY = 8192;         // biggest report accepted, in bytes
        |const PI_PER_MIN = 30;        // reports stored per minute by one running copy of the Worker
        |const PI_TTL = 2592000;       // KV forgets a report after 30 days
        |const piMemory = [];          // used when there is no KV binding: lost when Cloudflare restarts the Worker
        |let piMinute = 0, piCount = 0;
        |function piJson(o, status) { return new Response(JSON.stringify(o), { status: status || 200, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" } }); }
        |function piStr(v, n) { v = v == null ? "" : String(v); return v.length > n ? v.slice(0, n) : v; }
        |function piSame(a, b) {
        |  if (typeof a !== "string" || typeof b !== "string" || a.length === 0 || a.length !== b.length) return false;
        |  let d = 0; for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
        |  return d === 0;
        |}
        |function piAllowed() { const m = Math.floor(Date.now() / 60000); if (m !== piMinute) { piMinute = m; piCount = 0; } return ++piCount <= PI_PER_MIN; }
        |function piClean(o, request) {
        |  o = o && typeof o === "object" ? o : {};
        |  const now = new Date().toISOString();
        |  return { kind: piStr(o.kind || "error", 20), message: piStr(o.message, 500), source: piStr(o.source, 300), line: Number(o.line) | 0,
        |    column: Number(o.column) | 0, stack: piStr(o.stack, 2000), url: piStr(o.url, 300), ua: piStr(o.ua || request.headers.get("user-agent"), 200),
        |    time: piStr(o.time, 40) || now, received: now, country: piStr(request.cf && request.cf.country, 4) };
        |}
        |async function piStore(env, rec) {
        |  if (env.$KV_NAME) {
        |    // keys sort newest first, so a list of the first PI_MAX keys is the newest reports
        |    const id = String(9999999999999 - Date.now()).padStart(13, "0") + "-" + Math.random().toString(36).slice(2, 8);
        |    await env.$KV_NAME.put("e:" + id, JSON.stringify(rec), { expirationTtl: PI_TTL });
        |  } else {
        |    piMemory.push(rec);
        |    if (piMemory.length > PI_MAX) piMemory.splice(0, piMemory.length - PI_MAX);
        |  }
        |}
        |async function piList(env) {
        |  if (!env.$KV_NAME) return piMemory.slice().reverse();
        |  const keys = (await env.$KV_NAME.list({ prefix: "e:", limit: PI_MAX })).keys;
        |  const values = await Promise.all(keys.map(k => env.$KV_NAME.get(k.name)));
        |  return values.map(v => { try { return v ? JSON.parse(v) : null; } catch (e) { return null; } }).filter(Boolean);
        |}
        |async function piClear(env) {
        |  if (!env.$KV_NAME) { piMemory.length = 0; return; }
        |  const keys = (await env.$KV_NAME.list({ prefix: "e:", limit: 1000 })).keys;
        |  await Promise.all(keys.map(k => env.$KV_NAME.delete(k.name)));
        |}
        |async function piErrors(request, env) {
        |  if (request.method === "POST") {
        |    if (Number(request.headers.get("content-length") || 0) > PI_BODY) return new Response(null, { status: 413 });
        |    const text = await request.text();
        |    if (text.length > PI_BODY) return new Response(null, { status: 413 });
        |    if (!piAllowed()) return new Response(null, { status: 429 });
        |    let o; try { o = JSON.parse(text); } catch (e) { return new Response(null, { status: 400 }); }
        |    try { await piStore(env, piClean(o, request)); } catch (e) { return new Response(null, { status: 503 }); }
        |    return new Response(null, { status: 204 });
        |  }
        |  if (!piSame(request.headers.get("$HEADER"), env.$KEY_NAME)) return piJson({ error: "This needs the project's key." }, 401);
        |  if (request.method === "GET") return piJson({ storage: env.$KV_NAME ? "kv" : "memory", errors: await piList(env) });
        |  if (request.method === "DELETE") { await piClear(env); return new Response(null, { status: 204 }); }
        |  return new Response(null, { status: 405 });
        |}
        |// null when the request is not PocketIDE's, otherwise the answer
        |function piHandle(request, env) {
        |  const path = new URL(request.url).pathname;
        |  if (!path.startsWith("/__pocketide/")) return null;
        |  if (path !== PI_PATH) return env.ASSETS.fetch(request);
        |  return piErrors(request, env);
        |}
        |// an error the backend itself threw is recorded too, and then thrown on as before
        |function piBackendError(env, ctx, request, err) {
        |  if (!piAllowed()) return;
        |  const p = piStore(env, piClean({ kind: "backend", message: (err && err.message) || String(err), stack: err && err.stack, url: request.url }, request)).catch(() => {});
        |  if (ctx && ctx.waitUntil) ctx.waitUntil(p);
        |}
        |""".trimMargin()

    /** The Worker for a project with no backend: PocketIDE's route, then the files. */
    fun staticWorker(): String = HANDLER + "export default { async fetch(request, env, ctx) { return piHandle(request, env) || env.ASSETS.fetch(request); } };\n"

    /**
     * The entry module for a project with its own backend: imports the project's module unchanged (its named exports, such as
     * Durable Object classes, are passed through), answers PocketIDE's route, and hands every other request to the project's
     * default export. A default export that is a class (WorkerEntrypoint) is constructed per request the way Cloudflare does.
     */
    fun wrapper(entry: String): String {
        val from = "./" + entry.removePrefix("./")
        return "import * as piApp from \"$from\";\nexport * from \"$from\";\n" + HANDLER + """
            |const piOwn = piApp.default;
            |function piOwnFetch(request, env, ctx) {
            |  if (typeof piOwn === "function") return new piOwn(ctx, env).fetch(request);
            |  return piOwn.fetch(request, env, ctx);
            |}
            |export default Object.assign({}, piOwn && typeof piOwn === "object" ? piOwn : {}, {
            |  async fetch(request, env, ctx) {
            |    const own = piHandle(request, env);
            |    if (own) return own;
            |    try { return await piOwnFetch(request, env, ctx); }
            |    catch (err) { piBackendError(env, ctx, request, err); throw err; }
            |  }
            |});
            |""".trimMargin()
    }

    /** The tag added to each published page: early in <head>, so errors in the page's own scripts are caught. */
    const val TAG = "<script src=\"/__pocketide/report.js\"></script>"

    fun isHtml(path: String) = path.endsWith(".html", true) || path.endsWith(".htm", true)

    /**
     * [bytes] of an HTML file with the reporter tag after <head> (or <html>, or the doctype, or at the very start). Works on the
     * bytes as Latin-1 so a page in any encoding comes back byte for byte apart from the tag. A page that has it already is unchanged.
     */
    fun inject(bytes: ByteArray): ByteArray {
        val s = String(bytes, Charsets.ISO_8859_1)
        if (s.contains("/__pocketide/report.js")) return bytes
        val at = listOf(Regex("<head(\\s[^>]*)?>", RegexOption.IGNORE_CASE), Regex("<html(\\s[^>]*)?>", RegexOption.IGNORE_CASE), Regex("<!doctype[^>]*>", RegexOption.IGNORE_CASE))
            .firstNotNullOfOrNull { it.find(s)?.range?.last?.plus(1) } ?: 0
        return (s.substring(0, at) + TAG + s.substring(at)).toByteArray(Charsets.ISO_8859_1)
    }

    /** The files uploaded as assets: each page with the tag, plus the reporter. */
    fun withReporter(assets: Map<String, ByteArray>): Map<String, ByteArray> =
        assets.mapValues { (p, b) -> if (isHtml(p)) inject(b) else b } + (SCRIPT_PATH to REPORTER.toByteArray())

    /** The KV namespace with [title] in GET /storage/kv/namespaces's answer, or null. */
    fun namespaceId(json: String, title: String): String? {
        val o = try { MiniJson.obj(MiniJson.parse(json)) } catch (e: Exception) { null } ?: return null
        if (o["success"] != true) return null
        return MiniJson.list(o["result"]).orEmpty().mapNotNull { MiniJson.obj(it) }.firstOrNull { it["title"] == title }?.get("id") as? String
    }

    /** The id in POST /storage/kv/namespaces's answer, or null when Cloudflare said no (usually: the token lacks KV permission). */
    fun createdId(json: String): String? {
        val o = try { MiniJson.obj(MiniJson.parse(json)) } catch (e: Exception) { null } ?: return null
        return if (o["success"] == true) MiniJson.obj(o["result"])?.get("id") as? String else null
    }

    /** One line for the deploy's result. */
    fun storageNote(kv: Boolean): String = if (kv) " Errors from visitors' browsers are kept in Workers KV (30 days)."
        else " Errors from visitors' browsers are kept in the Worker's memory only: they are lost when Cloudflare restarts the Worker. " +
            "To keep them, give the token Workers KV Storage: Edit and publish again."

    /** What the app remembers about the last Cloudflare deploy of a project. */
    class Info(val url: String, val storage: String) {
        fun write(): String = MiniJson.write(linkedMapOf("url" to url, "storage" to storage))
        companion object {
            fun parse(text: String?): Info? {
                val o = try { MiniJson.obj(MiniJson.parse(text ?: return null)) } catch (e: Exception) { null } ?: return null
                val url = o["url"] as? String ?: return null
                return Info(url, o["storage"] as? String ?: "memory")
            }
        }
    }

    class Report(val kind: String, val message: String, val source: String, val line: Int, val column: Int, val stack: String,
                 val url: String, val ua: String, val time: String, val received: String, val country: String)

    class Listing(val storage: String, val errors: List<Report>)

    /** GET /__pocketide/errors's answer, newest first; null when it is not that answer (an old deploy answers with a page). */
    fun parse(json: String): Listing? {
        val o = try { MiniJson.obj(MiniJson.parse(json)) } catch (e: Exception) { null } ?: return null
        val list = MiniJson.list(o["errors"]) ?: return null
        fun s(m: Map<String, Any?>, k: String) = (m[k] as? String).orEmpty()
        fun n(m: Map<String, Any?>, k: String) = (m[k] as? Number)?.toInt() ?: 0
        val errors = list.mapNotNull { MiniJson.obj(it) }.map { m ->
            Report(s(m, "kind").ifEmpty { "error" }, s(m, "message"), s(m, "source"), n(m, "line"), n(m, "column"), s(m, "stack"),
                s(m, "url"), s(m, "ua"), s(m, "time"), s(m, "received").ifEmpty { s(m, "time") }, s(m, "country"))
        }.sortedByDescending { it.received }
        return Listing(o["storage"] as? String ?: "memory", errors)
    }

    fun kindName(kind: String): String = when (kind) {
        "promise" -> "Unhandled promise rejection"; "console" -> "console.error"; "resource" -> "A file did not load"
        "backend" -> "Error in the backend (worker.js)"; else -> "Uncaught error"
    }

    /** The project file a script address points at, when it is on the published site ("https://x.workers.dev/js/a.js" → "js/a.js"). */
    fun projectPath(source: String, site: String): String? {
        val base = site.trimEnd('/')
        if (source.isEmpty() || !source.startsWith("$base/")) return null
        return source.removePrefix("$base/").substringBefore('?').substringBefore('#').ifEmpty { "index.html" }
    }

    /** One line per report for the list. */
    fun summary(r: Report, site: String): String {
        val where = projectPath(r.source, site)?.let { "$it:${r.line}" } ?: r.source.takeIf { it.isNotEmpty() }?.let { "$it:${r.line}" }
        return kindName(r.kind) + ": " + r.message.ifEmpty { "(no message)" } + (where?.let { " ($it)" } ?: "") +
            "\n" + r.received.replace('T', ' ').take(16) + (if (r.country.isNotEmpty()) " · ${r.country}" else "")
    }

    /** "Fix this": everything the report knows, for the assistant, with keys hidden. */
    fun prompt(r: Report, site: String): String {
        val sb = StringBuilder("A visitor's browser hit an error on the published site $site (published from this project with /deploy cloudflare).\n\n")
        sb.append("## The error\n").append(kindName(r.kind)).append(": ").append(r.message.ifEmpty { "(no message)" }).append('\n')
        if (r.source.isNotEmpty()) {
            sb.append("Where: ").append(r.source).append(':').append(r.line).append(':').append(r.column)
            projectPath(r.source, site)?.let { sb.append(" (project file ").append(it).append(", line ").append(r.line).append(')') }
            sb.append('\n')
        }
        if (r.url.isNotEmpty()) sb.append("Page: ").append(r.url).append('\n')
        if (r.ua.isNotEmpty()) sb.append("Browser: ").append(r.ua).append('\n')
        sb.append("When: ").append(r.time.ifEmpty { r.received }).append('\n')
        if (r.stack.isNotBlank()) sb.append("\n## Stack\n```\n").append(Trim.middle(r.stack.trim(), 2000)).append("\n```\n")
        sb.append("\nFind the cause in this project's files (the published copy only adds PocketIDE's reporter script), name the file and line, ")
        sb.append("and make the smallest fix. Then it can be published again with /deploy cloudflare.")
        return ChatExport.redact(sb.toString())
    }
}
