package dev.pocketide.app

import dev.pocketide.app.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

class ExtrasTest {
    private fun tmp(): File = java.nio.file.Files.createTempDirectory("extras").toFile()

    /** GitHub's git data API, backed by a store of its own. */
    class FakeGitHub(dir: File) : GitApi {
        val store = VcsStore(dir).also { it.init() }
        val refs = HashMap<String, String>()
        var calls = 0
        private fun ok(o: Any?) = GitResponse(200, MiniJson.write(o).toByteArray())
        private fun person(p: VcsStore.Person) = linkedMapOf("name" to p.name, "email" to p.email, "date" to java.time.Instant.ofEpochSecond(p.time).toString())
        private fun parsePerson(o: Any?): VcsStore.Person { val m = MiniJson.obj(o)!!; return VcsStore.Person(m["name"] as String, m["email"] as String, java.time.Instant.parse(m["date"] as String).epochSecond) }
        override fun call(method: String, path: String, body: String?, raw: Boolean): GitResponse {
            calls++
            val p = path.removePrefix("/repos/me/app")
            val b = body?.let { MiniJson.obj(MiniJson.parse(it)) }
            return when {
                method == "POST" && p == "/git/blobs" -> ok(mapOf("sha" to store.write("blob", Base64.getDecoder().decode(b!!["content"] as String))))
                method == "POST" && p == "/git/trees" -> ok(mapOf("sha" to store.writeFlat(MiniJson.list(b!!["tree"])!!.map { MiniJson.obj(it)!! }.associate { (it["path"] as String) to ((it["mode"] as String) to (it["sha"] as String)) })))
                method == "POST" && p == "/git/commits" -> ok(mapOf("sha" to store.writeCommit(b!!["tree"] as String, MiniJson.list(b["parents"])!!.map { it as String }, parsePerson(b["author"]), parsePerson(b["committer"]), b["message"] as String)))
                method == "GET" && p.startsWith("/git/ref/heads/") -> refs[p.removePrefix("/git/ref/heads/")]?.let { ok(mapOf("object" to mapOf("sha" to it))) } ?: GitResponse(404, "{}".toByteArray())
                method == "POST" && p == "/git/refs" -> { refs[(b!!["ref"] as String).removePrefix("refs/heads/")] = b["sha"] as String; ok(mapOf<String, Any>()) }
                method == "PATCH" && p.startsWith("/git/refs/heads/") -> {
                    val name = p.removePrefix("/git/refs/heads/"); val to = b!!["sha"] as String; val cur = refs[name]
                    // only forward: the current tip must be in the new commit's history
                    val anc = HashSet<String>(); val q = ArrayDeque(listOf(to)); while (q.isNotEmpty()) { val c = q.removeFirst(); if (anc.add(c)) store.readCommit(c)?.parents?.forEach { q.add(it) } }
                    if (cur != null && cur !in anc) GitResponse(422, "{}".toByteArray()) else { refs[name] = to; ok(mapOf<String, Any>()) }
                }
                method == "GET" && p.startsWith("/commits?") -> {
                    val branch = Regex("sha=([^&]+)").find(p)!!.groupValues[1]; val page = Regex("[?&]page=(\\d+)").find(p)?.groupValues?.get(1)?.toInt() ?: 1
                    if (page > 1) ok(emptyList<Any>()) else {
                        val list = ArrayList<String>(); var c = refs[branch]
                        val seen = HashSet<String>(); val q = ArrayDeque(listOfNotNull(c))
                        while (q.isNotEmpty()) { val x = q.removeFirst(); if (seen.add(x)) { list.add(x); store.readCommit(x)!!.parents.forEach { q.add(it) } } }
                        ok(list.map { mapOf("sha" to it) })
                    }
                }
                method == "GET" && p.startsWith("/git/commits/") -> { val c = store.readCommit(p.removePrefix("/git/commits/"))!!
                    ok(linkedMapOf("sha" to c.id, "tree" to mapOf("sha" to c.tree), "parents" to c.parents.map { mapOf("sha" to it) }, "author" to person(c.author), "committer" to person(c.committer), "message" to c.message)) }
                method == "GET" && p.startsWith("/git/trees/") -> ok(mapOf("tree" to store.flatten(p.removePrefix("/git/trees/").substringBefore('?')).map { (path, e) -> mapOf("path" to path, "mode" to e.first, "type" to "blob", "sha" to e.second) }))
                method == "GET" && p.startsWith("/git/blobs/") -> ok(mapOf("content" to Base64.getEncoder().encodeToString(store.readBlob(p.removePrefix("/git/blobs/"))!!), "encoding" to "base64"))
                else -> GitResponse(404, "{\"message\":\"$method $p\"}".toByteArray())
            }
        }
    }

    @Test fun historyGoesToGitHubAndComesBack() {
        val a = tmp(); File(a, "index.html").writeText("<h1>1</h1>\n"); File(a, "js/app.js").also { it.parentFile.mkdirs() }.writeText("x\n")
        val va = Vcs(a); va.start(); File(a, "index.html").writeText("<h1>2</h1>\n"); va.commit("second", null)
        val gh = FakeGitHub(tmp())
        assertEquals(2, GitBridge(gh, "me/app", va).exportHistory("main"))
        val before = gh.calls
        assertEquals(0, GitBridge(gh, "me/app", va).exportHistory("main"))      // nothing new: nothing sent again
        assertTrue(gh.calls - before < 4)
        // another phone (project) imports it
        val b = tmp(); val vb = Vcs(b)
        assertEquals(2, GitBridge(gh, "me/app", vb).importHistory("main"))
        assertEquals(listOf("second", "Start of history"), vb.log("main").map { it.title })
        assertEquals("<h1>2</h1>\n", String(vb.contentAt(vb.store.resolve("main")!!, "index.html")!!))
        vb.restoreAll(vb.store.resolve("main")!!)
        assertEquals("x\n", File(b, "js/app.js").readText()); assertTrue(vb.status().isEmpty())
        // GitHub moves on without us: sending is refused until we import
        File(b, "index.html").writeText("<h1>3 from b</h1>\n"); vb.commit("third (b)", null); GitBridge(gh, "me/app", vb).exportHistory("main")
        File(a, "index.html").writeText("<h1>3 from a</h1>\n"); va.commit("third (a)", null)
        val err = try { GitBridge(gh, "me/app", va).exportHistory("main"); null } catch (e: GitHubException) { e }
        assertEquals(422, err!!.code)
    }

    @Test fun secretsAreMaskedAndNamed() {
        val s = Secrets(File(tmp(), "secrets.json"))
        s.set("API_KEY", "sk-12345678"); s.set("PIN", "12")
        assertEquals(mapOf("API_KEY" to "sk-12345678", "PIN" to "12"), s.all())
        assertEquals("key=***API_KEY*** pin=12", Secrets.mask("key=sk-12345678 pin=12", s.all()))
        assertTrue(Secrets.note(s.all().keys).contains("API_KEY, PIN")); assertFalse(Secrets.note(s.all().keys).contains("sk-"))
        val bad = try { s.set("1BAD", "x"); false } catch (e: IllegalArgumentException) { true }
        assertTrue(bad); s.remove("PIN"); assertEquals(setOf("API_KEY"), s.all().keys)
    }

    @Test fun setupFileAndSnippets() {
        val st = ProjectSetup.parse("""{"packages":["python3","nodejs; rm -rf /"],"run":["npm install"]}""")!!
        assertEquals(listOf("python3"), st.packages)
        val cmd = ProjectSetup.command(st)
        assertTrue(cmd.contains("apk add --no-cache python3") && cmd.endsWith("&& npm install"))
        assertNull(ProjectSetup.parse("{}")); assertNull(ProjectSetup.parse("not json"))
        assertTrue(ProjectSetup.parse(ProjectSetup.draft(setOf("python", "node")))!!.packages.containsAll(listOf("python3", "nodejs", "npm")))
        val sn = Snippets(File(tmp(), "s.json"))
        sn.add("debounce", "js", "function d(){}"); sn.add("debounce", "js", "function d2(){}"); sn.add("", "py", "print(1)\nprint(2)")
        assertEquals(listOf("debounce" to "function d2(){}", "print(1)" to "print(1)\nprint(2)"), sn.all().map { it.title to it.code })
        sn.remove("debounce"); assertEquals(1, sn.all().size)
    }

    @Test fun searchAndCodeCheck() {
        val p1 = tmp(); val p2 = tmp()
        File(p1, "a.js").writeText("let goblin = 1;\n"); File(p2, "b.md").writeText("Goblins live here\n")
        File(p2, "node_modules/x.js").also { it.parentFile.mkdirs() }.writeText("goblin\n")
        val hits = CrossSearch.search(listOf(p1, p2), "goblin")
        assertEquals(listOf(p1.name to "a.js", p2.name to "b.md"), hits.map { it.project to it.path }); assertEquals(1, hits[0].line)
        val f = CodeCheck.run(listOf("a.json" to "{bad", "b.js" to "<<<<<<< x\na\n=======\nb\n>>>>>>> y\n", "k.js" to "const key = \"sk-ant-api03-" + "a".repeat(40) + "\";"))
        assertTrue(f.map { it.title }.toString(), f.any { it.title == "a.json is not valid JSON" } && f.any { it.title.startsWith("b.js still has merge conflict") } && f.any { it.title.contains("k.js") })
    }

    @Test fun dependencyUpdates() {
        val files = mapOf("package.json" to """{"dependencies":{"three":"^0.150.0","left-pad":"1.3.0"},"devDependencies":{"vite":"latest"}}""", "requirements.txt" to "flask==2.0.1  # web\nrequests\n-r other.txt\n")
        val deps = Deps.find(files.keys.toList()) { files[it] }
        assertEquals(listOf("three" to "0.150.0", "left-pad" to "1.3.0", "vite" to "", "flask" to "2.0.1", "requests" to ""), deps.map { it.name to it.version })
        assertTrue(Deps.newer("0.160.1", "0.150.0")); assertFalse(Deps.newer("1.3.0", "1.3")); assertTrue(Deps.newer("2.0.10", "2.0.9"))
        val reports = Deps.check(deps, { url -> when {
            url.endsWith("/three/latest") -> """{"version":"0.160.1"}"""
            url.contains("pypi.org/pypi/flask") -> """{"info":{"version":"3.0.3"}}"""
            url.endsWith("/left-pad/latest") -> """{"version":"1.3.0"}"""
            else -> null } }, { _, body -> assertTrue(body.contains("\"flask\"")); """{"results":[{},{"vulns":[{"id":"GHSA-1"}]},{"vulns":[{"id":"PYSEC-9"}]}]}""" })
        val byName = reports.associateBy { it.dep.name }
        assertTrue(byName["three"]!!.outdated); assertFalse(byName["left-pad"]!!.outdated); assertEquals(listOf("GHSA-1"), byName["left-pad"]!!.vulns)
        assertEquals(listOf("PYSEC-9"), byName["flask"]!!.vulns); assertTrue(byName["flask"]!!.outdated)
        assertTrue(Deps.request(reports.filter { it.outdated || it.vulns.isNotEmpty() }).contains("left-pad (package.json): 1.3.0 → 1.3.0 — known vulnerabilities: GHSA-1"))
    }

    @Test fun insights() {
        val d = tmp(); File(d, "a.txt").writeText("1\n"); val v = Vcs(d); v.start()
        v.createBranch("f"); v.switchTo("f"); File(d, "b.txt").writeText("b\n"); v.commit("on f", null)
        v.switchTo("main"); File(d, "a.txt").writeText("2\n3\n"); v.commit("on main", null, Vcs.ASSISTANT); v.merge("f")
        val log = v.log()
        val g = Insights.graph(log)
        assertEquals(4, g.size); assertTrue(g[0], g[0].startsWith("○ ╮")); assertTrue(g.last(), g.last().startsWith("●") && g.last().contains("Start of history"))
        assertTrue(g.any { it.startsWith("│ ●") || it.startsWith("● │") })
        val today = Insights.day(System.currentTimeMillis() / 1000)
        assertEquals(4, Insights.perDay(log)[today])
        val grid = Insights.grid(Insights.perDay(log)); assertEquals(16, grid.size); assertEquals(4, grid.last().sum())
        val freq = Insights.codeFrequency(v, log); assertEquals(1, freq.size); assertEquals(3, freq[0].commits)
        val hot = Insights.hotFiles(v, log)
        assertEquals(Triple("a.txt", 2, 1), hot.first { it.first == "a.txt" })
    }
}
