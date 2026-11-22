package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 1.5.143, Cursor parity P5: the pull request review workflow and its script, run end to end against a fake GitHub and model. */
class ReviewBotTest {
    @Test fun theWorkflowCannotBeSteeredByThePullRequest() {
        val w = ReviewBot.workflow()
        assertTrue(w, w.contains("pull_request:\n    types: [opened, synchronize, reopened, ready_for_review]"))
        assertTrue(w, w.contains("ref: \${{ github.event.pull_request.base.sha }}"))     // the script from the base branch
        assertTrue(w, w.contains("sparse-checkout: .github/pocketide"))
        assertTrue(w, w.contains("contents: read") && w.contains("pull-requests: write") && !w.contains("contents: write"))
        assertTrue(w, w.contains("github.event.pull_request.draft == false"))
        assertTrue(w, w.contains("'no-review'"))
        assertTrue(w, w.contains("head.repo.full_name == github.repository"))           // forks have no secrets: skipped
        assertFalse(w, w.contains("pull_request_target"))
    }

    private fun python(): Boolean = try { ProcessBuilder("python3", "--version").start().waitFor() == 0 } catch (e: Exception) { false }

    private val patch = "@@ -1,3 +1,4 @@\n function pay(cart) {\n-  return cart.total;\n+  const t = cart.total;\n+  return t - cart.discount;\n }"

    @Test fun postsOnlyFindingsOnLinesOfTheDiff() {
        assumeTrue(python())
        val dir = kotlin.io.path.createTempDirectory("reviewbot").toFile()
        val script = File(dir, "review.py").also { it.writeText(ReviewBot.script()) }
        val ss = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val seen = java.util.Collections.synchronizedList(ArrayList<String>())
        val modelReply = MiniJson.write(mapOf("summary" to "One bug.", "findings" to listOf(
            mapOf("path" to "src/pay.js", "line" to 3, "message" to "A missing discount makes the total NaN."),
            mapOf("path" to "src/pay.js", "line" to 4, "message" to "An old finding that was already posted."),
            mapOf("path" to "src/pay.js", "line" to 40, "message" to "Not a line of the diff."),
            mapOf("path" to "other.js", "line" to 1, "message" to "Not a file of the diff."))))
        val server = Thread {
            try {
                while (true) {
                    ss.accept().use { sock ->
                        val inp = sock.getInputStream().bufferedReader()
                        val head = generateSequence { inp.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                        val len = head.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
                        val body = CharArray(len).also { var r = 0; while (r < len) { val n = inp.read(it, r, len - r); if (n < 0) break; r += n } }
                        val line = head.firstOrNull().orEmpty()
                        seen.add(line + "\n" + String(body))
                        val out = when {
                            line.contains("/pulls/7/comments") -> MiniJson.write(listOf(
                                mapOf("path" to "src/pay.js", "line" to 2, "user" to mapOf("type" to "Bot"), "body" to "An  old finding that was already posted."),
                                mapOf("path" to "src/pay.js", "line" to 3, "user" to mapOf("type" to "User"), "body" to "A missing discount makes the total NaN.")))
                            line.contains("/pulls/7/files") -> MiniJson.write(listOf(mapOf("filename" to "src/pay.js", "status" to "modified", "patch" to patch)))
                            line.contains("/chat/completions") -> MiniJson.write(mapOf("choices" to listOf(mapOf("message" to mapOf("content" to "<think>hm</think>Here:\n$modelReply")))))
                            else -> "{}"
                        }
                        sock.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${out.toByteArray().size}\r\nConnection: close\r\n\r\n$out".toByteArray())
                    }
                }
            } catch (e: Exception) { }
        }.also { it.isDaemon = true; it.start() }
        try {
            val url = "http://127.0.0.1:${ss.localPort}"
            val pb = ProcessBuilder("python3", script.path).redirectErrorStream(true)
            pb.environment().putAll(mapOf("GITHUB_REPOSITORY" to "o/r", "PR_NUMBER" to "7", "GH_TOKEN" to "t", "POCKETIDE_API_KEY" to "k",
                "POCKETIDE_BASE_URL" to "$url/v1", "POCKETIDE_MODEL" to "m", "HEAD_SHA" to "abc", "GITHUB_API_URL" to url))
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            assertEquals(out, 0, p.waitFor())
            assertTrue(out, out.contains("posted 1 comment"))
            val review = seen.first { it.startsWith("POST /repos/o/r/pulls/7/reviews") }
            assertTrue(review, review.contains("\"line\": 3") && review.contains("missing discount") && review.contains("\"commit_id\": \"abc\""))
            assertFalse(review, review.contains("Not a line") || review.contains("Not a file"))
            // the bot said that one before (a person's same words do not count): not repeated after a push
            assertFalse(review, review.contains("old finding"))
            val ask = seen.first { it.startsWith("POST /v1/chat/completions") }
            assertTrue(ask, ask.contains("L3 ") && ask.contains("return t - cart.discount"))   // lines carry their new-file numbers
        } finally { ss.close(); dir.deleteRecursively() }
    }

    @Test fun withoutSettingsItSaysWhatToSet() {
        assumeTrue(python())
        val f = File.createTempFile("review", ".py").also { it.writeText(ReviewBot.script()); it.deleteOnExit() }
        val pb = ProcessBuilder("python3", f.path).redirectErrorStream(true)
        pb.environment().putAll(mapOf("GITHUB_REPOSITORY" to "o/r", "PR_NUMBER" to "7", "GH_TOKEN" to "t"))
        pb.environment().remove("POCKETIDE_API_KEY")
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(1, p.waitFor())
        assertTrue(out, out.contains("POCKETIDE_API_KEY") && out.contains("/cloud"))
    }
}
