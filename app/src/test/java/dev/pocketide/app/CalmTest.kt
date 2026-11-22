package dev.pocketide.app

import dev.pocketide.app.core.TurnChangeKind
import dev.pocketide.app.core.TurnChanges
import dev.pocketide.app.core.NetRetry
import org.junit.Test

class CalmTest {
    @Test
    fun calmChecks() {
        CalmSelfTest.runAll()
    }
}

object CalmSelfTest {
    private var fails = 0
    private var passes = 0

    private fun ok(c: Boolean, m: String) {
        if (c) passes++ else fails++
        println((if (c) "PASS  " else "FAIL  ") + m)
    }

    fun runAll() {
        changes(); retry()
        println("\n$passes passed, $fails failed")
        check(fails == 0) { "$fails calm checks failed" }
    }

    private fun b(s: String) = s.toByteArray()

    private fun changes() {
        ok(TurnChanges.kind(null, b("x")) == TurnChangeKind.NEW, "a file that did not exist is new")
        ok(TurnChanges.kind(b("x"), null) == TurnChangeKind.DELETED, "a file that is gone was deleted")
        ok(TurnChanges.kind(b("x"), b("y")) == TurnChangeKind.EDITED, "different bytes are an edit")
        ok(TurnChanges.kind(b("x"), b("x")) == TurnChangeKind.SAME && TurnChanges.kind(null, null) == TurnChangeKind.SAME, "unchanged files are left out")
        ok(TurnChanges.summary(listOf(TurnChangeKind.NEW, TurnChangeKind.NEW, TurnChangeKind.EDITED)) == "What changed: 2 new, 1 edited", "summary counts by kind")
        ok(TurnChanges.summary(listOf(TurnChangeKind.SAME)) == "No files changed", "nothing changed")
        ok(TurnChanges.lineDelta("a\nb\n", "a\nc\nd\n") == "+2 −1", "line counts")
        ok(TurnChanges.lineDelta("same", "same") == null, "no delta when equal")
    }

    private fun retry() {
        ok(NetRetry.transient(java.net.UnknownHostException("x")) && NetRetry.transient(java.net.ConnectException()) && NetRetry.transient(java.net.SocketTimeoutException()), "lost connections are retried")
        ok(NetRetry.transient(java.io.IOException("Connection reset by peer")) && NetRetry.transient(java.io.IOException("Unable to resolve host")), "resets are retried")
        ok(!NetRetry.transient(IllegalStateException("bad")) && !NetRetry.transient(java.io.IOException("Permission denied")), "other errors are not retried")
        ok(NetRetry.waitSeconds(1) == 2 && NetRetry.waitSeconds(2) == 6 && NetRetry.waitSeconds(9) == 6, "backoff grows then holds")
        ok(NetRetry.status(2, 1).contains("try 1 of ${NetRetry.RETRIES}"), "status line names the try")
        ok(NetRetry.timeoutMessage("Groq").contains("did not answer in time") && NetRetry.timeoutMessage("Groq").contains("Retry"), "timeout message is its own")
        ok(NetRetry.offlineMessage("Groq") == "Could not reach Groq. Check your internet connection.", "offline message kept")
        // a stream that stalls before any of the answer arrives is sent once more; one that already gave text is not repeated
        val fresh = dev.pocketide.app.core.ToolStream(false)
        ok(fresh.nothingYet && NetRetry.stalledBeforeAnswer(java.net.SocketTimeoutException(), fresh.nothingYet, 0), "silent stall is sent again")
        ok(!NetRetry.stalledBeforeAnswer(java.net.SocketTimeoutException(), true, NetRetry.STALL_RETRIES), "only once")
        fresh.feed("""data: {"choices":[{"delta":{"content":"Hi"}}]}""")
        ok(!fresh.nothingYet && !NetRetry.stalledBeforeAnswer(java.net.SocketTimeoutException(), fresh.nothingYet, 0), "a started answer is not sent again")
        // 1.5.114: an answer cut off part way is asked for once more
        ok(NetRetry.brokenStream(java.io.IOException("Premature EOF"), 0), "a cut-off answer is sent again")
        ok(NetRetry.brokenStream(java.net.SocketException("Connection reset"), 0), "a reset connection is sent again")
        ok(!NetRetry.brokenStream(java.io.IOException("Premature EOF"), NetRetry.BROKEN_RETRIES), "only once")
        ok(!NetRetry.brokenStream(java.net.SocketTimeoutException("Read timed out"), 0), "a timeout is not a broken stream")
        ok(!NetRetry.brokenStream(java.io.IOException("No space left on device"), 0), "other errors are shown")
        val calling = dev.pocketide.app.core.ToolStream(false)
        calling.feed("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"a","function":{"name":"read_file","arguments":""}}]}}]}""")
        ok(!calling.nothingYet, "a started tool call counts as an answer")
        ok(!NetRetry.stalledBeforeAnswer(java.io.IOException("Connection reset"), true, 0), "only a timeout")
    }
}
