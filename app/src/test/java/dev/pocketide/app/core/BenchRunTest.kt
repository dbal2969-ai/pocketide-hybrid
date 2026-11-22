package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

/** /bench (1.5.125): the caps, the dispatch, finding the run, the summary artifact and the pull-request comment. */
class BenchRunTest {
    private fun ok(p: BenchRun.Parsed): BenchRun.Request = (p as? BenchRun.Parsed.Ok)?.request ?: throw AssertionError("refused: " + (p as BenchRun.Parsed.Refused).message)
    private fun refused(p: BenchRun.Parsed): String = (p as? BenchRun.Parsed.Refused)?.message ?: throw AssertionError("accepted: $p")

    @Test fun theCommandIsReadWithItsBranchModelAndRuns() {
        val r = ok(BenchRun.parse("rp-loot,rp-bury on pocketide/issue-12", "groq:llama-3.3-70b-versatile"))
        assertEquals(listOf("rp-loot", "rp-bury"), r.tasks); assertEquals("pocketide/issue-12", r.branch)
        assertEquals("groq:llama-3.3-70b-versatile", r.model); assertEquals(1, r.repeat); assertTrue(r.bigger.isEmpty())
        assertEquals(4, r.taskRuns())
        assertTrue(r.estimate(), r.estimate().startsWith("4 task runs: 2 tasks × 2 branches (main and pocketide/issue-12), 1 run each"))
        val w = ok(BenchRun.parse("rp-loot rp-bury, add-feature with cerebras:llama-3.3-70b x3", "groq:x"))
        assertEquals(listOf("rp-loot", "rp-bury", "add-feature"), w.tasks); assertEquals("cerebras:llama-3.3-70b", w.model); assertEquals(3, w.repeat)
        assertEquals(9, w.taskRuns()); assertEquals(listOf("3 runs of each task"), w.bigger)
        // the app's provider is used only when the benchmark has a key for it; otherwise the workflow's default model
        assertNull(ok(BenchRun.parse("rp-loot", BenchRun.modelFor("anthropic", "claude-sonnet-5-5"))).model)
        assertEquals("ollamacloud:gpt-oss:120b", BenchRun.modelFor("ollamacloud", "gpt-oss:120b"))
        val s = ok(BenchRun.parse("suite realproject"))
        assertEquals("realproject", s.suite); assertTrue(s.bigger.single().contains("realproject"))
    }

    @Test fun theBudgetCapsRefuse() {
        assertTrue(refused(BenchRun.parse("")).contains("Name the tasks"))
        assertTrue(refused(BenchRun.parse("on my-branch")).contains("suite <name>"))
        assertTrue(refused(BenchRun.parse("a,b,c,d,e,f,g,h,i")).contains("at most 8"))
        ok(BenchRun.parse("a,b,c,d,e,f,g,h"))
        assertTrue(refused(BenchRun.parse("rp-loot x4")).contains("1, 2 or 3"))
        assertTrue(refused(BenchRun.parse("rp-loot with groq:a;cerebras:b")).contains("One model"))
        assertTrue(refused(BenchRun.parse("rp-loot with madeup:model")).contains("no key for \"madeup\""))
        assertTrue(refused(BenchRun.parse("rp-loot with groq")).contains("provider:model"))
        assertTrue(refused(BenchRun.parse("suite everything")).contains("not a task set"))
        assertTrue(refused(BenchRun.parse("suite hard rp-loot")).contains("not both"))
        assertTrue(refused(BenchRun.parse("Rp_Loot")).contains("not a task name"))
        assertTrue(refused(BenchRun.parse("rp-loot on bad..name")).contains("not a branch"))
        assertTrue(refused(BenchRun.parse("rp-loot on")).contains("which branch"))
    }

    @Test fun theDispatchBodyCarriesOnlyWhatWasAsked() {
        val ab = MiniJson.obj(MiniJson.parse(BenchRun.dispatchBody(ok(BenchRun.parse("rp-loot,rp-bury on pocketide/issue-12", "groq:m")), "main")))!!
        assertEquals("main", ab["ref"])
        assertEquals(mapOf("tasks" to "rp-loot,rp-bury", "repeat" to "1", "refs" to "main,pocketide/issue-12", "models" to "groq:m"), ab["inputs"])
        val one = MiniJson.obj(MiniJson.obj(MiniJson.parse(BenchRun.dispatchBody(ok(BenchRun.parse("rp-loot")), "trunk")))!!["inputs"])!!
        assertEquals(mapOf("tasks" to "rp-loot", "repeat" to "1"), one)   // the default branch alone, the workflow's default model
        val suite = MiniJson.obj(MiniJson.obj(MiniJson.parse(BenchRun.dispatchBody(ok(BenchRun.parse("suite hard x2")), "main")))!!["inputs"])!!
        assertEquals(mapOf("suite" to "hard", "repeat" to "2"), suite)
    }

    @Test fun ourRunIsTheNewOneAfterTheDispatch() {
        val runs = listOf(CloudFollow.Run(9, "Benchmark", "completed", "success", "2026-11-10T09:00:00Z", ""),
            CloudFollow.Run(11, "Benchmark", "queued", "", "2026-11-10T10:01:05Z", ""),
            CloudFollow.Run(10, "Benchmark", "in_progress", "", "2026-11-10T10:00:30Z", ""))
        assertEquals(11L, BenchRun.pickRun(runs, setOf(9L, 10L), "2026-11-10T09:58:00Z")?.id)
        assertEquals(10L, BenchRun.pickRun(runs, setOf(9L), "2026-11-10T09:58:00Z")?.id)
        assertNull(BenchRun.pickRun(runs, setOf(9L, 10L, 11L), "2026-11-10T09:58:00Z"))
    }

    private val summaryMd = """
# Benchmark summary

| Model | Ref | Task | Passed | Median requests | Median seconds | Median reads |
|---|---|---|---|---|---|---|
| groq:m | main | rp-bury | 0/1 | 30 | 200 | 4 |
| groq:m | pocketide_issue-12 | rp-bury | 1/1 | 22 | 150 | 3 |
| groq:m | main | rp-loot | 1/1 | 20 | 100 | 2 |
| groq:m | pocketide_issue-12 | rp-loot | 1/1 | 21 | 110 | 2 |

| Model | Ref | Passed | Median requests | Seconds in all |
|---|---|---|---|---|
| groq:m | main | 1/2 | 25 | 300 |
| groq:m | pocketide_issue-12 | 2/2 | 21 | 260 |

Refs compared: main, pocketide_issue-12. Differences of a trial or two per task are within run-to-run noise at 3 runs per task.
""".trimStart()

    @Test fun theSummaryIsReadPerRef() {
        val s = BenchRun.parseSummary(summaryMd, listOf("main", "pocketide/issue-12"))
        assertEquals(listOf("main" to 1, "pocketide/issue-12" to 2), s.totals.map { it.ref to it.passed })
        assertEquals(4, s.tasks.size)
        val note = BenchRun.note(s, "https://github.com/o/r/actions/runs/5")
        assertTrue(note, note.contains("• main: 1/2 passed") && note.contains("• pocketide/issue-12: 2/2 passed"))
        assertTrue(note, note.contains("rp-bury: main 0/1, pocketide/issue-12 1/1")); assertFalse(note, note.contains("rp-loot:"))
        val c = BenchRun.comment(s, ok(BenchRun.parse("rp-loot,rp-bury on pocketide/issue-12", "groq:m")), "https://x/run", "main")
        assertTrue(c.startsWith(BenchRun.MARK)); assertTrue(c.contains("Benchmark from PocketIDE")); assertTrue(c.contains("| groq:m | main | 1/2 |"))
        assertTrue(BenchRun.note(BenchRun.parseSummary("No benchmark reports were found.\n"), "u").contains("no results"))
    }

    @Test fun a403OnDispatchNamesThePermission() {
        val m = BenchRun.dispatchError(403, """{"message":"Resource not accessible by personal access token"}""", "o/r")
        assertTrue(m, m.contains("Actions: Read and write") && m.contains("workflow scope") && m.contains("Resource not accessible"))
        assertTrue(BenchRun.dispatchError(404, "", "o/r").contains("no benchmark.yml"))
        assertTrue(BenchRun.dispatchError(422, """{"message":"Unexpected inputs provided"}""", "o/r").contains("Unexpected inputs provided"))
    }

    private fun zipOf(vararg files: Pair<String, String>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z -> for ((n, t) in files) { z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    /** A fake GitHub: answers each path the /bench flow calls and keeps every request (method, path, body). */
    private class Fake(val dispatchCode: Int = 204, val conclusion: String = "success", val zip: ByteArray = ByteArray(0)) : AutoCloseable {
        val ss = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val seen = java.util.Collections.synchronizedList(ArrayList<Triple<String, String, String>>())
        @Volatile var dispatched = false
        @Volatile var polls = 0
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
                        seen.add(Triple(hs["x-http-method-override"] ?: method, path, body))
                        val old = """{"id":7,"display_title":"Benchmark","status":"completed","conclusion":"success","created_at":"2020-01-01T00:00:00Z","html_url":""}"""
                        val ours = """{"id":42,"display_title":"Benchmark","status":"queued","conclusion":null,"created_at":"2099-01-01T00:00:00Z","html_url":"https://github.com/o/r/actions/runs/42"}"""
                        var code = 200; var type = "application/json"; var location: String? = null
                        val res: ByteArray = when {
                            path == "/repos/o/r" -> """{"default_branch":"main"}""".toByteArray()
                            path == "/repos/o/r/git/ref/heads/pocketide/issue-12" -> """{"ref":"refs/heads/pocketide/issue-12"}""".toByteArray()
                            path.startsWith("/repos/o/r/git/ref/heads/") -> { code = 404; """{"message":"Not Found"}""".toByteArray() }
                            path.startsWith("/repos/o/r/actions/workflows/benchmark.yml/runs") ->
                                ("""{"workflow_runs":[""" + (if (dispatched) "$ours," else "") + old + "]}").toByteArray()
                            path == "/repos/o/r/actions/workflows/benchmark.yml/dispatches" -> {
                                code = dispatchCode; if (code == 204) { dispatched = true; ByteArray(0) } else """{"message":"Resource not accessible by personal access token"}""".toByteArray()
                            }
                            path == "/repos/o/r/actions/runs/42" -> {
                                polls++
                                (if (polls < 2) """{"id":42,"status":"in_progress","conclusion":null}""" else """{"id":42,"status":"completed","conclusion":"$conclusion"}""").toByteArray()
                            }
                            path.startsWith("/repos/o/r/actions/runs/42/artifacts") ->
                                """{"artifacts":[{"id":5,"name":"bench-0-all-main","size_in_bytes":10,"expired":false},{"id":6,"name":"summary","size_in_bytes":${zip.size},"expired":false}]}""".toByteArray()
                            // GitHub answers an artifact download with a redirect to storage
                            path == "/repos/o/r/actions/artifacts/6/zip" -> { code = 302; location = "http://127.0.0.1:${ss.localPort}/storage/summary.zip"; ByteArray(0) }
                            path == "/storage/summary.zip" -> { type = "application/zip"; zip }
                            path.startsWith("/repos/o/r/actions/runs/42/jobs") ->
                                """{"jobs":[{"id":77,"name":"main · groq:m · all","conclusion":"failure","steps":[{"name":"Run the benchmark","conclusion":"failure"}]}]}""".toByteArray()
                            path.startsWith("/repos/o/r/pulls") -> """[{"number":12,"html_url":"https://github.com/o/r/pull/12"}]""".toByteArray()
                            path == "/repos/o/r/issues/12/comments" -> { code = 201; """{"id":1}""".toByteArray() }
                            else -> { code = 404; """{"message":"Not Found"}""".toByteArray() }
                        }
                        val status = when (code) { 200 -> "200 OK"; 201 -> "201 Created"; 204 -> "204 No Content"; 302 -> "302 Found"; 403 -> "403 Forbidden"; else -> "$code Error" }
                        sock.getOutputStream().apply {
                            write(("HTTP/1.1 $status\r\nContent-Type: $type\r\n" + (location?.let { "Location: $it\r\n" } ?: "") +
                                (if (code == 204) "" else "Content-Length: ${res.size}\r\n") + "Connection: close\r\n\r\n").toByteArray())
                            write(res); flush()
                        }
                    }
                }
            }.also { it.isDaemon = true }.start()
        }
        val base get() = "http://127.0.0.1:${ss.localPort}"
        override fun close() = ss.close()
    }

    @Test fun anABRunIsSentFollowedReadAndPostedOnThePullRequest() {
        Fake(zip = zipOf("summary.md" to summaryMd)).use { f ->
            val req = ok(BenchRun.parse("rp-loot,rp-bury on pocketide/issue-12", "groq:m"))
            val bf = BenchFollow(GitHubHttp("tok", f.base), "o/r", { null }, sleep = {}, waitMs = 1)
            val started = bf.start(req)
            assertNull(started.error); assertEquals(42L, started.run?.id); assertEquals("main", started.defaultBranch)
            val sent = f.seen.first { it.second.endsWith("/dispatches") }
            assertEquals("POST", sent.first)
            assertEquals(BenchRun.dispatchBody(req, "main"), sent.third)
            val out = bf.follow(req, started)
            assertTrue(out.message, out.ok)
            assertNotNull(out.summary)
            assertEquals(listOf("main" to 1, "pocketide/issue-12" to 2), out.summary!!.totals.map { it.ref to it.passed })
            assertTrue(f.seen.any { it.second == "/storage/summary.zip" })   // the artifact zip was downloaded through the redirect
            assertEquals(12, out.pullRequest); assertNull(out.commentError)
            assertTrue(f.seen.any { it.second.startsWith("/repos/o/r/pulls") && it.second.contains("head=o:pocketide/issue-12") })
            val comment = f.seen.first { it.second == "/repos/o/r/issues/12/comments" }
            assertEquals("POST", comment.first)
            val body = MiniJson.obj(MiniJson.parse(comment.third))!!["body"] as String
            assertTrue(body.startsWith(BenchRun.MARK)); assertTrue(body.contains("Benchmark from PocketIDE")); assertTrue(body.contains("| groq:m | pocketide_issue-12 | 2/2 |"))
            assertTrue(out.message, out.message.contains("Posted on pull request #12"))
        }
    }

    @Test fun aFailedRunStillReportsItsSummaryAndTheFailingJob() {
        Fake(conclusion = "failure", zip = zipOf("summary.md" to summaryMd)).use { f ->
            val req = ok(BenchRun.parse("rp-loot", "groq:m"))
            val bf = BenchFollow(GitHubHttp("tok", f.base), "o/r", { "2026-11-10T10:00:00.000Z ##[error]no benchmark task named rp-lot\n" }, sleep = {}, waitMs = 1)
            val out = bf.follow(req, bf.start(req))
            assertFalse(out.ok)
            assertTrue(out.message, out.message.contains("\"failure\" in \"main · groq:m · all\" at \"Run the benchmark\"") && out.message.contains("no benchmark task named"))
            assertNotNull(out.fixPrompt); assertNull(out.pullRequest)   // no branch: nothing is posted
            assertFalse(f.seen.any { it.second.contains("/comments") })
        }
    }

    @Test fun aForbiddenDispatchAndAMissingBranchAreSaidPlainly() {
        Fake(dispatchCode = 403).use { f ->
            val s = BenchFollow(GitHubHttp("tok", f.base), "o/r", { null }, sleep = {}, waitMs = 1).start(ok(BenchRun.parse("rp-loot")))
            assertNull(s.run); assertTrue(s.error!!, s.error!!.contains("Actions: Read and write"))
        }
        Fake().use { f ->
            val s = BenchFollow(GitHubHttp("tok", f.base), "o/r", { null }, sleep = {}, waitMs = 1).start(ok(BenchRun.parse("rp-loot on nope")))
            assertTrue(s.error!!, s.error!!.contains("no branch \"nope\""))
            assertFalse(f.seen.any { it.second.endsWith("/dispatches") })
        }
    }
}
