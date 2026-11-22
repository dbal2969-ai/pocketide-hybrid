package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class CfDeployTest {
    private val project = mapOf(
        "index.html" to "<h1>Hi</h1>", "app.js" to "fetch('/api/hi')", "style.css" to "h1{}", "README.md" to "notes",
        "worker.js" to "import { hi } from './api/hi.js';\nexport default { async fetch(r, env) { return new Response(hi(env.API_KEY)); } };",
        "api/hi.js" to "import './util';\nexport function hi(k) { return 'hi'; }", "api/util.js" to "export const x = 1;",
        ".git/config" to "x", "node_modules/a/index.js" to "x", ".pocketide/notes" to "x", "package-lock.json" to "{}")

    @Test fun whatIsPublishedAndWhatRunsAsTheBackend() {
        assertEquals("my-game-2", CfDeploy.scriptName("My Game 2!")); assertEquals("app-3d", CfDeploy.scriptName("3d"))
        assertEquals("worker.js", CfDeploy.entry(project.keys))
        val backend = CfDeploy.modules("worker.js") { project[it] }
        assertEquals(listOf("worker.js", "api/hi.js", "api/util.js"), backend)
        assertEquals(listOf("app.js", "index.html", "style.css"), CfDeploy.assets(project.keys, backend))
        assertNull(CfDeploy.resolve("a.js", "../../x"))
        val meta = MiniJson.obj(MiniJson.parse(CfDeploy.metadata("worker.js", "done", mapOf("API_KEY" to "k1"), project.keys)))!!
        assertEquals("worker.js", meta["main_module"])
        val assets = MiniJson.obj(meta["assets"])!!
        assertEquals("done", assets["jwt"]); assertEquals("single-page-application", MiniJson.obj(assets["config"])!!["not_found_handling"])
        // 1.5.123: API calls reach the backend before the single-page fallback; a static site has no such rule
        assertNull(MiniJson.obj(assets["config"])!!["run_worker_first"])
        val withBackend = MiniJson.obj(MiniJson.obj(MiniJson.obj(MiniJson.parse(CfDeploy.metadata("worker.js", "done", emptyMap(), project.keys, backend = true)))!!["assets"])!!["config"])!!
        assertEquals(listOf("/api/*"), MiniJson.list(withBackend["run_worker_first"]))
        val bindings = MiniJson.list(meta["bindings"])!!.map { MiniJson.obj(it)!! }
        assertEquals(listOf("ASSETS" to "assets", "API_KEY" to "secret_text"), bindings.map { it["name"] to it["type"] })
        assertEquals("k1", bindings[1]["text"])
        assertEquals(32, CfDeploy.hash("x".toByteArray()).length)
        assertTrue(CfDeploy.result("""{"success":false,"errors":[{"code":10000,"message":"Authentication error"}]}""").second.contains("Authentication error (code 10000)"))
        assertTrue(CfDeploy.hint("Authentication error (code 10000)").contains("Workers Scripts: Edit"))
    }

    /** A fake Cloudflare API: answers the deploy's calls and keeps each request (method, path, auth, body). */
    private class Fake(val buckets: List<String> = listOf(CfDeploy.hash("<h1>Hi</h1>".toByteArray()), CfDeploy.hash("h1{}".toByteArray())), val kv: Boolean = false) : AutoCloseable {
        val ss = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val seen = java.util.Collections.synchronizedList(ArrayList<Triple<String, String, Pair<String, String>>>())
        init {
            Thread {
                while (!ss.isClosed) {
                    val s = try { ss.accept() } catch (e: Exception) { break }
                    s.use { sock ->
                        val ins = java.io.BufferedInputStream(sock.getInputStream())
                        fun line(): String { val b = StringBuilder(); while (true) { val c = ins.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) b.append(c.toChar()) }; return b.toString() }
                        val (method, path) = line().split(' ').let { it[0] to it[1] }
                        val hs = HashMap<String, String>()
                        while (true) { val l = line(); if (l.isEmpty()) break; hs[l.substringBefore(':').trim().lowercase()] = l.substringAfter(':').trim() }
                        val n = hs["content-length"]?.toIntOrNull() ?: 0
                        val body = String(ByteArray(n).also { var r = 0; while (r < n) { val k = ins.read(it, r, n - r); if (k < 0) break; r += k } })
                        seen.add(Triple(method, path, hs["authorization"].orEmpty() to body))
                        val res = when {
                            path.endsWith("/workers/subdomain") -> """{"success":true,"result":{"subdomain":"ana"}}"""
                            path.endsWith("/assets-upload-session") -> """{"success":true,"result":{"jwt":"up1","buckets":[${buckets.joinToString(",") { "[\"$it\"]" }}]}}"""
                            // 1.5.125: KV for error reports; a token without KV permission gets Cloudflare's authentication error
                            path.contains("/storage/kv/namespaces") && !kv -> """{"success":false,"errors":[{"code":10000,"message":"Authentication error"}]}"""
                            path.contains("/storage/kv/namespaces") && method == "GET" -> """{"success":true,"result":[{"id":"other","title":"someone-else"}]}"""
                            path.contains("/storage/kv/namespaces") -> """{"success":true,"result":{"id":"kv123","title":"pocketide-errors-my-game"}}"""
                            path.contains("/workers/assets/upload") -> if (seen.count { it.second.contains("/assets/upload") } == 2) """{"success":true,"result":{"jwt":"done"}}""" else """{"success":true,"result":{}}"""
                            path.contains("/workers/scripts/") -> """{"success":true,"result":{}}"""
                            path == "/site" -> "ok"
                            else -> """{"success":false,"errors":[{"code":7003,"message":"no route"}]}"""
                        }.toByteArray()
                        sock.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${res.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(res); flush() }
                    }
                }
            }.also { it.isDaemon = true }.start()
        }
        val base get() = "http://127.0.0.1:${ss.localPort}"
        override fun close() = ss.close()
    }

    @Test fun theDeploySendsTheRightRequestsInOrder() {
        Fake().use { f ->
            val acc = "0123456789abcdef0123456789abcdef"
            val out = CfDeployRun("tok", acc, f.base, waitMs = 10) { _, _ -> f.base + "/site" }
                .deploy("My Game", project.mapValues { it.value.toByteArray() }, { project[it] }, mapOf("API_KEY" to "k1"))
            assertTrue(out.message, out.ok)
            val calls = f.seen.map { it.first + " " + it.second.substringBefore('?') }
            assertEquals(listOf("GET /accounts/$acc/workers/subdomain", "POST /accounts/$acc/workers/scripts/my-game/assets-upload-session",
                "POST /accounts/$acc/workers/assets/upload", "POST /accounts/$acc/workers/assets/upload", "PUT /accounts/$acc/workers/scripts/my-game",
                "POST /accounts/$acc/workers/scripts/my-game/subdomain", "GET /site"), calls)
            val manifest = f.seen[1].third.second
            assertTrue(manifest.contains("\"/index.html\"") && manifest.contains("\"/app.js\"") && !manifest.contains("worker.js") && !manifest.contains(".git") && !manifest.contains("README"))
            assertEquals("Bearer up1", f.seen[2].third.first)                      // uploads use the upload token, not the API token
            assertTrue(f.seen[2].third.second.contains(java.util.Base64.getEncoder().encodeToString("<h1>Hi</h1>".toByteArray())) && f.seen[2].third.second.contains("Content-Type: text/html"))
            val put = f.seen[4].third
            assertEquals("Bearer tok", put.first)
            assertTrue(put.second.contains("\"jwt\":\"done\"") && put.second.contains("name=\"api/hi.js\"") && put.second.contains("application/javascript+module") && put.second.contains("\"secret_text\""))
            assertTrue(out.message.startsWith("Live at ${f.base}/site (3 files and the backend from worker.js, 1 secret)"))
        }
    }

    @Test fun badInputIsSaidPlainly() {
        assertFalse(CfDeployRun("t", "nope").deploy("x", emptyMap(), { null }, emptyMap()).ok)
        assertTrue(CfDeployRun("t", "nope").deploy("x", emptyMap(), { null }, emptyMap()).message.contains("32-character"))
    }

    // ---- 1.5.125: error reports from the published site (CfErrors) ----

    @Test fun errorReportsArePureLogic() {
        // the tag goes right after <head>, else <html>, else the doctype, else first; a page that has it stays as it is
        assertEquals("<html><head>${CfErrors.TAG}<title>t</title></head></html>", String(CfErrors.inject("<html><head><title>t</title></head></html>".toByteArray())))
        assertEquals("<HTML lang=\"en\">${CfErrors.TAG}<body>", String(CfErrors.inject("<HTML lang=\"en\"><body>".toByteArray())))
        assertEquals("<!doctype html>${CfErrors.TAG}<p>", String(CfErrors.inject("<!doctype html><p>".toByteArray())))
        assertEquals("${CfErrors.TAG}<h1>Hi</h1>", String(CfErrors.inject("<h1>Hi</h1>".toByteArray())))
        val once = CfErrors.inject("<head></head>".toByteArray()); assertTrue(once.contentEquals(CfErrors.inject(once)))
        // bytes that are not UTF-8 come back unchanged apart from the tag
        val latin = byteArrayOf(0x3c, 0x68, 0x65, 0x61, 0x64, 0x3e, 0xe9.toByte(), 0xff.toByte())
        assertTrue(CfErrors.inject(latin).copyOfRange(6 + CfErrors.TAG.length, 6 + CfErrors.TAG.length + 2).contentEquals(byteArrayOf(0xe9.toByte(), 0xff.toByte())))
        val up = CfErrors.withReporter(mapOf("index.html" to "<head>".toByteArray(), "app.js" to "x".toByteArray(), "about.htm" to "<p>".toByteArray()))
        assertEquals(setOf("index.html", "app.js", "about.htm", "__pocketide/report.js"), up.keys)
        assertEquals("x", String(up.getValue("app.js"))); assertTrue(String(up.getValue("about.htm")).startsWith(CfErrors.TAG))
        assertTrue(String(up.getValue("__pocketide/report.js")).contains("unhandledrejection"))
        // the wrapper imports the project's entry unchanged and passes its named exports through
        val w = CfErrors.wrapper("src/worker.js")
        assertTrue(w.startsWith("import * as piApp from \"./src/worker.js\";\nexport * from \"./src/worker.js\";"))
        assertTrue(w.contains("piHandle(request, env)") && w.contains("env.POCKETIDE_ERRORS_KEY") && !w.contains("\$"))
        assertTrue(CfErrors.staticWorker().contains("env.ASSETS.fetch(request)"))
        // metadata: the route, the key as a secret (once, even when the project's secrets hold it), KV only when there is one
        val meta = MiniJson.obj(MiniJson.parse(CfDeploy.metadata(CfErrors.ENTRY, "j", mapOf("API_KEY" to "k1", CfErrors.KEY_NAME to "rk"), project.keys, backend = true, reports = CfDeploy.Reports("rk", "kv1"))))!!
        assertEquals(listOf("/api/*", "/__pocketide/*"), MiniJson.list(MiniJson.obj(MiniJson.obj(meta["assets"])!!["config"])!!["run_worker_first"]))
        val b = MiniJson.list(meta["bindings"])!!.map { MiniJson.obj(it)!! }
        assertEquals(listOf("ASSETS", "POCKETIDE_ERRORS", "API_KEY", "POCKETIDE_ERRORS_KEY"), b.map { it["name"] })
        assertEquals("kv1", b[1]["namespace_id"]); assertEquals("kv_namespace", b[1]["type"]); assertEquals("rk", b[3]["text"])
        val noKv = MiniJson.obj(MiniJson.parse(CfDeploy.metadata("index.js", "j", emptyMap(), listOf("index.html"), reports = CfDeploy.Reports("rk", null))))!!
        assertEquals(listOf("/__pocketide/*"), MiniJson.list(MiniJson.obj(MiniJson.obj(noKv["assets"])!!["config"])!!["run_worker_first"]))
        assertEquals(listOf("assets", "secret_text"), MiniJson.list(noKv["bindings"])!!.map { MiniJson.obj(it)!!["type"] })
        // Cloudflare's KV answers
        assertEquals("n2", CfErrors.namespaceId("""{"success":true,"result":[{"id":"n1","title":"a"},{"id":"n2","title":"pocketide-errors-x"}]}""", CfErrors.kvTitle("x")))
        assertNull(CfErrors.namespaceId("""{"success":false,"errors":[{"code":10000,"message":"Authentication error"}]}""", "x"))
        assertEquals("n3", CfErrors.createdId("""{"success":true,"result":{"id":"n3","title":"t"}}"""))
        assertNull(CfErrors.createdId("""{"success":false,"errors":[]}"""))
        assertEquals(48, CfErrors.newKey().length); assertTrue(CfErrors.newKey() != CfErrors.newKey())
        // the list, newest first, and the report for the assistant with keys hidden
        val site = "https://my-game.ana.workers.dev"
        val l = CfErrors.parse("""{"storage":"memory","errors":[{"kind":"error","message":"old","received":"2026-11-10T10:00:00.000Z"},
            {"kind":"promise","message":"x is undefined sk-abcdefghijklmnop1234","source":"$site/js/app.js?v=2","line":12,"column":4,"stack":"at go ($site/js/app.js:12:4)","url":"$site/","ua":"Firefox","time":"2026-11-10T11:00:00.000Z","received":"2026-11-10T11:00:01.000Z","country":"GB"}]}""")!!
        assertEquals("memory", l.storage); assertEquals(listOf("x is undefined sk-abcdefghijklmnop1234", "old"), l.errors.map { it.message })
        assertEquals("js/app.js", CfErrors.projectPath(l.errors[0].source, site)); assertNull(CfErrors.projectPath("https://cdn.example/x.js", site))
        assertTrue(CfErrors.summary(l.errors[0], site).startsWith("Unhandled promise rejection: x is undefined") && CfErrors.summary(l.errors[0], site).contains("(js/app.js:12)"))
        val p = CfErrors.prompt(l.errors[0], site)
        assertTrue(p, p.contains("project file js/app.js, line 12") && p.contains("## Stack") && p.contains("Firefox") && !p.contains("abcdefghijklmnop1234"))
        assertNull(CfErrors.parse("<!doctype html><h1>page</h1>"))
        assertEquals("kv", CfErrors.Info.parse(CfErrors.Info(site, "kv").write())!!.storage)
    }

    @Test fun aDeployWithReportsUploadsTheReporterTheWrapperAndTheRoute() {
        val page = CfErrors.inject("<h1>Hi</h1>".toByteArray())
        Fake(listOf(CfDeploy.hash(page), CfDeploy.hash(CfErrors.REPORTER.toByteArray())), kv = true).use { f ->
            val acc = "0123456789abcdef0123456789abcdef"
            val out = CfDeployRun("tok", acc, f.base, waitMs = 10) { _, _ -> f.base + "/site" }
                .deploy("My Game", project.mapValues { it.value.toByteArray() }, { project[it] }, mapOf("API_KEY" to "k1", CfErrors.KEY_NAME to "readkey"), reportKey = "readkey")
            assertTrue(out.message, out.ok)
            assertEquals("kv", out.storage)
            assertTrue(out.message, out.message.startsWith("Live at ${f.base}/site (3 files and the backend from worker.js, 1 secret).") && out.message.contains("Workers KV"))
            val calls = f.seen.map { it.first + " " + it.second.substringBefore('?') }
            assertEquals(listOf("GET /accounts/$acc/workers/subdomain", "GET /accounts/$acc/storage/kv/namespaces", "POST /accounts/$acc/storage/kv/namespaces",
                "POST /accounts/$acc/workers/scripts/my-game/assets-upload-session", "POST /accounts/$acc/workers/assets/upload", "POST /accounts/$acc/workers/assets/upload",
                "PUT /accounts/$acc/workers/scripts/my-game", "POST /accounts/$acc/workers/scripts/my-game/subdomain", "GET /site"), calls)
            assertEquals("{\"title\":\"pocketide-errors-my-game\"}", f.seen[2].third.second)
            val manifest = f.seen[3].third.second
            assertTrue(manifest, manifest.contains("\"/__pocketide/report.js\"") && manifest.contains(CfDeploy.hash(page)) && !manifest.contains(CfDeploy.hash("<h1>Hi</h1>".toByteArray())))
            // the page as uploaded has the tag; the reporter goes up as JavaScript
            val enc = java.util.Base64.getEncoder()
            assertTrue(f.seen[4].third.second.contains(enc.encodeToString(page)) && f.seen[4].third.second.contains("Content-Type: text/html"))
            assertTrue(f.seen[5].third.second.contains(enc.encodeToString(CfErrors.REPORTER.toByteArray())) && f.seen[5].third.second.contains("Content-Type: application/javascript"))
            val put = f.seen[6].third.second
            val meta = MiniJson.obj(MiniJson.parse(put.substringAfter("Content-Type: application/json\r\n\r\n").substringBefore("\r\n--")))!!
            assertEquals(CfErrors.ENTRY, meta["main_module"])
            assertEquals(listOf("/api/*", "/__pocketide/*"), MiniJson.list(MiniJson.obj(MiniJson.obj(meta["assets"])!!["config"])!!["run_worker_first"]))
            val bindings = MiniJson.list(meta["bindings"])!!.map { MiniJson.obj(it)!! }
            assertEquals(listOf("ASSETS", "POCKETIDE_ERRORS", "API_KEY", "POCKETIDE_ERRORS_KEY"), bindings.map { it["name"] })
            assertEquals("kv123", bindings[1]["namespace_id"])
            // the project's modules go up unchanged beside the wrapper
            assertTrue(put.contains("name=\"${CfErrors.ENTRY}\"") && put.contains("import * as piApp from \"./worker.js\";") && put.contains(project.getValue("worker.js")) && put.contains("name=\"api/hi.js\""))
        }
    }

    @Test fun aStaticSiteWithoutKvPermissionKeepsReportsInMemoryAndSaysSo() {
        val files = mapOf("index.html" to "<!doctype html><html><head><title>t</title></head><body>x</body></html>", "app.js" to "go()")
        Fake(kv = false).use { f ->
            val acc = "0123456789abcdef0123456789abcdef"
            val out = CfDeployRun("tok", acc, f.base, waitMs = 10) { _, _ -> f.base + "/site" }
                .deploy("site", files.mapValues { it.value.toByteArray() }, { files[it] }, emptyMap(), reportKey = "readkey")
            assertTrue(out.message, out.ok)
            assertEquals("memory", out.storage)
            assertTrue(out.message, out.message.startsWith("Live at ${f.base}/site (2 files).") && out.message.contains("lost when Cloudflare restarts the Worker"))
            val put = f.seen.first { it.first == "PUT" }.third.second
            assertTrue(put.contains("\"run_worker_first\":[\"/__pocketide/*\"]") && !put.contains("kv_namespace") && put.contains("POCKETIDE_ERRORS_KEY"))
            assertTrue(put.contains("name=\"index.js\"") && put.contains("function piHandle(") && put.contains("piHandle(request, env) || env.ASSETS.fetch(request)"))
        }
    }

    /**
     * The generated JavaScript run for real under node (skipped without node): the reporter in a fake browser window, its report
     * POSTed to the wrapper around a project's backend, read back with the key, backend errors, caps, and the static Worker with KV.
     */
    @Test fun theGeneratedJavaScriptWorksUnderNode() {
        val node = try { ProcessBuilder("node", "--version").redirectErrorStream(true).start().let { it.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) && it.exitValue() == 0 } } catch (e: Exception) { false }
        org.junit.Assume.assumeTrue("node is not installed", node)
        val dir = java.nio.file.Files.createTempDirectory("cferrors").toFile()
        try {
            java.io.File(dir, "package.json").writeText("{\"type\":\"module\"}")
            java.io.File(dir, "worker.js").writeText("export class Counter {}\nexport default { async fetch(req, env) { const p = new URL(req.url).pathname; if (p === '/boom') throw new Error('backend broke'); return new Response('own:' + p + ':' + env.API_KEY); } };\n")
            java.io.File(dir, CfErrors.ENTRY).writeText(CfErrors.wrapper("worker.js"))
            java.io.File(dir, "static.js").writeText(CfErrors.staticWorker())
            java.io.File(dir, "report.js").writeText(CfErrors.REPORTER)
            java.io.File(dir, "test.mjs").writeText(NODE_TEST.replace("ENTRY_FILE", CfErrors.ENTRY))
            val proc = ProcessBuilder("node", "test.mjs").directory(dir).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            assertTrue("node timed out", proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(out, 0, proc.exitValue())
            assertTrue(out, out.contains("ALL OK"))
            // what the Worker answered parses in the app
            val l = CfErrors.parse(out.lines().first { it.startsWith("LISTING ") }.removePrefix("LISTING "))!!
            assertEquals("memory", l.storage)
            assertEquals("backend", l.errors[0].kind); assertEquals("backend broke", l.errors[0].message)
            assertTrue(l.errors.any { it.kind == "error" && it.message == "boom" && it.line == 3 && it.source.endsWith("/app.js") })
        } finally { dir.deleteRecursively() }
    }

    private companion object {
        val NODE_TEST = """
            |import assert from "node:assert/strict";
            |import vm from "node:vm";
            |import fs from "node:fs";
            |import wrapped, { Counter } from "./ENTRY_FILE";
            |import plain from "./static.js";
            |const KEY = "k".repeat(48);
            |const site = "https://my-game.ana.workers.dev";
            |const assetsSeen = [];
            |const ASSETS = { fetch: async (req) => { assetsSeen.push(new URL(req.url).pathname); return new Response("asset"); } };
            |const waits = [];
            |const ctx = { waitUntil: (p) => waits.push(p) };
            |const req = (path, init) => new Request(site + path, init);
            |const post = (o) => req("/__pocketide/errors", { method: "POST", body: typeof o === "string" ? o : JSON.stringify(o), headers: { "content-type": "application/json" } });
            |const get = (key) => req("/__pocketide/errors", { headers: key ? { "x-pocketide-key": key } : {} });
            |
            |// 1. the reporter in a fake browser window
            |const sentBodies = [];
            |const logged = [];
            |const listeners = {};
            |const sandbox = {
            |  navigator: { userAgent: "TestBrowser/1" }, location: { href: site + "/play" },
            |  fetch: (url, init) => { sentBodies.push([url, init]); return Promise.resolve({}); },
            |  console: { error: (...a) => logged.push(a.join(" ")) },
            |  addEventListener: (type, fn) => { listeners[type] = fn; },
            |};
            |sandbox.window = sandbox;
            |vm.createContext(sandbox);
            |vm.runInContext(fs.readFileSync("report.js", "utf8"), sandbox);
            |vm.runInContext(fs.readFileSync("report.js", "utf8"), sandbox); // loaded twice: still one set of handlers
            |listeners.error({ target: sandbox, message: "boom", filename: site + "/app.js", lineno: 3, colno: 5, error: { stack: "Error: boom\n    at app.js:3:5" } });
            |listeners.error({ target: sandbox, message: "boom", filename: site + "/app.js", lineno: 3, colno: 5 });
            |assert.equal(sentBodies.length, 1, "the same error is sent once");
            |assert.equal(sentBodies[0][0], "/__pocketide/errors");
            |assert.equal(sentBodies[0][1].method, "POST");
            |const first = JSON.parse(sentBodies[0][1].body);
            |assert.equal(first.kind, "error"); assert.equal(first.message, "boom"); assert.equal(first.line, 3); assert.equal(first.column, 5);
            |assert.equal(first.url, site + "/play"); assert.equal(first.ua, "TestBrowser/1"); assert.match(first.stack, /app.js:3:5/);
            |listeners.unhandledrejection({ reason: { message: "promise said no", stack: "s" } });
            |sandbox.console.error("bad thing", 42);
            |assert.equal(logged[0], "bad thing 42", "console.error still logs");
            |listeners.error({ target: { src: site + "/missing.png" } });
            |assert.deepEqual(sentBodies.map(b => JSON.parse(b[1].body).kind), ["error", "promise", "console", "resource"]);
            |for (let i = 0; i < 30; i++) listeners.error({ target: sandbox, message: "loop " + i, filename: "x", lineno: i });
            |assert.equal(sentBodies.length, 10, "at most 10 reports per page load");
            |
            |// 2. the wrapper around a project's backend, reports kept in memory
            |const env = { ASSETS, API_KEY: "k1", POCKETIDE_ERRORS_KEY: KEY };
            |assert.equal(typeof Counter, "function", "named exports pass through");
            |assert.equal(await (await wrapped.fetch(req("/api/hi"), env, ctx)).text(), "own:/api/hi:k1");
            |assert.equal(await (await wrapped.fetch(req("/__pocketide/report.js"), env, ctx)).text(), "asset");
            |assert.deepEqual(assetsSeen, ["/__pocketide/report.js"]);
            |for (const b of sentBodies.slice(0, 4)) assert.equal((await wrapped.fetch(post(b[1].body), env, ctx)).status, 204);
            |assert.equal((await wrapped.fetch(post("x".repeat(9000)), env, ctx)).status, 413);
            |assert.equal((await wrapped.fetch(post("not json"), env, ctx)).status, 400);
            |assert.equal((await wrapped.fetch(get(), env, ctx)).status, 401);
            |assert.equal((await wrapped.fetch(get("k".repeat(47) + "x"), env, ctx)).status, 401);
            |assert.equal((await wrapped.fetch(get(KEY), { ASSETS }, ctx)).status, 401, "no key set: nobody reads");
            |let listing = await (await wrapped.fetch(get(KEY), env, ctx)).json();
            |assert.equal(listing.storage, "memory");
            |assert.deepEqual(listing.errors.map(e => e.kind), ["resource", "console", "promise", "error"], "newest first");
            |await assert.rejects(() => wrapped.fetch(req("/boom"), env, ctx), /backend broke/);
            |await Promise.all(waits);
            |const listingText = await (await wrapped.fetch(get(KEY), env, ctx)).text();
            |listing = JSON.parse(listingText);
            |assert.equal(listing.errors[0].kind, "backend"); assert.equal(listing.errors[0].message, "backend broke");
            |console.log("LISTING " + listingText);
            |assert.equal((await wrapped.fetch(req("/__pocketide/errors", { method: "DELETE", headers: { "x-pocketide-key": KEY } }), env, ctx)).status, 204);
            |assert.equal((await (await wrapped.fetch(get(KEY), env, ctx)).json()).errors.length, 0);
            |const statuses = [];
            |for (let i = 0; i < 70; i++) statuses.push((await wrapped.fetch(post({ message: "flood " + i }), env, ctx)).status);
            |assert.ok(statuses.includes(429), "a flood is cut off");
            |assert.ok((await (await wrapped.fetch(get(KEY), env, ctx)).json()).errors.length <= 50);
            |
            |// 3. the static Worker with a KV binding
            |const store = new Map();
            |const kv = { put: async (k, v) => { store.set(k, v); }, get: async (k) => store.get(k) ?? null, delete: async (k) => { store.delete(k); },
            |  list: async ({ prefix, limit }) => ({ keys: [...store.keys()].filter(k => k.startsWith(prefix)).sort().slice(0, limit).map(name => ({ name })) }) };
            |const senv = { ASSETS, POCKETIDE_ERRORS: kv, POCKETIDE_ERRORS_KEY: KEY };
            |assert.equal(await (await plain.fetch(req("/index.html"), senv, ctx)).text(), "asset");
            |assert.equal((await plain.fetch(post({ message: "older" }), senv, ctx)).status, 204);
            |await new Promise(r => setTimeout(r, 5));
            |assert.equal((await plain.fetch(post({ message: "newer" }), senv, ctx)).status, 204);
            |assert.equal(store.size, 2);
            |const kl = await (await plain.fetch(get(KEY), senv, ctx)).json();
            |assert.equal(kl.storage, "kv");
            |assert.deepEqual(kl.errors.map(e => e.message), ["newer", "older"]);
            |assert.equal((await plain.fetch(get("wrong"), senv, ctx)).status, 401);
            |assert.equal((await plain.fetch(req("/__pocketide/errors", { method: "DELETE", headers: { "x-pocketide-key": KEY } }), senv, ctx)).status, 204);
            |assert.equal(store.size, 0);
            |console.log("ALL OK");
            |""".trimMargin()
    }
}
