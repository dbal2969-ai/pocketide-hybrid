package dev.pocketide.app

import dev.pocketide.app.core.RetryAdvice
import dev.pocketide.app.core.WorkingStatus
import org.junit.Test

class WorkingRetryTest {
    @Test
    fun workingAndRetryChecks() {
        WorkingRetrySelfTest.runAll()
    }
}

object WorkingRetrySelfTest {
    private var fails = 0
    private var passes = 0

    private fun ok(c: Boolean, m: String) {
        if (c) passes++ else fails++
        println((if (c) "PASS  " else "FAIL  ") + m)
    }

    private fun eq(a: Any?, b: Any?, m: String) = ok(a == b, if (a == b) m else "$m  got <$a> expected <$b>")

    fun runAll() {
        status(); tools(); retry()
        println("\n$passes passed, $fails failed")
        check(fails == 0) { "$fails working/retry checks failed" }
    }

    private fun status() {
        ok(WorkingStatus.isPlaceholder("…") && WorkingStatus.isPlaceholder("") && WorkingStatus.isPlaceholder("Thinking…"), "bare placeholders are recognised")
        ok(!WorkingStatus.isPlaceholder("Waiting 12 s…") && !WorkingStatus.isPlaceholder("Here is the plan"), "real text is not a placeholder")
        eq(WorkingStatus.duration(7), "7s", "seconds")
        eq(WorkingStatus.duration(75), "1m 15s", "minutes")
        eq(WorkingStatus.duration(120), "2m 00s", "minutes pad seconds")
        ok(WorkingStatus.line("Thinking…", 3, 0).let { it.startsWith("Thinking.") && it.endsWith("3s") }, "line shows phase, dots and time: " + WorkingStatus.line("Thinking…", 3, 0))
        ok(WorkingStatus.line("Thinking", 3, 1) != WorkingStatus.line("Thinking", 3, 0), "dots animate")
        eq(WorkingStatus.line("Thinking", 3, 3), WorkingStatus.line("Thinking", 3, 0), "dots loop")
        ok(!WorkingStatus.line("Thinking…", 10, 0).contains("Stop"), "no hint early")
        ok(WorkingStatus.line("Thinking…", 50, 0).contains("tap Stop"), "hint on a slow wait")
        ok(!WorkingStatus.line("Running npm test", 90, 0).contains("tap Stop"), "no hint while a command runs")
        ok(WorkingStatus.line("", 1, 0).startsWith("Working"), "empty phase falls back to Working")
        ok(WorkingStatus.line("Writing the reply", 5, 0, 1240).contains("1.2k characters"), "progress shows how much has arrived")
        eq(WorkingStatus.size(850), "850 characters", "small sizes are exact")
        ok(!WorkingStatus.line("Thinking", 60, 0, 300).contains("tap Stop"), "no slow hint once text is arriving")
    }

    private fun tools() {
        eq(WorkingStatus.forTool("read_file", mapOf("path" to "app.js")), "Reading app.js", "read")
        eq(WorkingStatus.forTool("write_file", mapOf("path" to "a/b.css")), "Writing a/b.css", "write")
        eq(WorkingStatus.forTool("edit_file", mapOf("path" to "x.py")), "Editing x.py", "edit")
        eq(WorkingStatus.forTool("run_command", mapOf("command" to "npm test\nsecond line")), "Running npm test", "run uses the first line")
        ok(WorkingStatus.forTool("run_command", mapOf("command" to "x".repeat(200))).length <= 48, "long commands are cut")
        eq(WorkingStatus.forTool("list_files", emptyMap()), "Looking at the files", "list")
        eq(WorkingStatus.forTool("mystery", emptyMap()), "Working", "unknown tool")
    }

    private fun retry() {
        ok(RetryAdvice.worthRetrying("Could not reach Groq. Check your internet connection."), "network failure is worth retrying")
        ok(RetryAdvice.worthRetrying("Groq is overloaded. Try again in a moment."), "overloaded is worth retrying")
        ok(RetryAdvice.worthRetrying("HTTP 503: service unavailable"), "503 is worth retrying")
        ok(RetryAdvice.worthRetrying("Rate limit reached for model"), "rate limit is worth retrying")
        ok(RetryAdvice.worthRetrying("The request timed out"), "timeout is worth retrying")
        ok(!RetryAdvice.worthRetrying("Invalid API key"), "a bad key is not")
        ok(!RetryAdvice.worthRetrying("The model does not exist"), "a missing model is not")
        ok(!RetryAdvice.worthRetrying("maximum context length exceeded"), "too much context is not")
        ok(!RetryAdvice.worthRetrying("Something odd"), "unknown messages are not guessed at")
        eq(RetryAdvice.otherProviders("groq", listOf("groq" to true, "gemini" to true, "openai" to false, "nvidia" to true)), listOf("gemini", "nvidia"), "other providers: usable, not current")
    }
}
