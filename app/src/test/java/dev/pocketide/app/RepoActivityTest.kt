package dev.pocketide.app

import dev.pocketide.app.core.RepoActivity
import dev.pocketide.app.core.RunState
import dev.pocketide.app.core.SelfFix
import org.junit.Test

class RepoActivityTest {
    @Test
    fun repoActivityChecks() {
        RepoActivitySelfTest.runAll()
    }
}

object RepoActivitySelfTest {
    private var fails = 0
    private var passes = 0

    private fun ok(c: Boolean, m: String) {
        if (c) passes++ else fails++
        println((if (c) "PASS  " else "FAIL  ") + m)
    }

    fun runAll() {
        commits(); runs(); words()
        println("\n$passes passed, $fails failed")
        check(fails == 0) { "$fails repo activity checks failed" }
    }

    private fun commits() {
        val json = """[{"sha":"e86c8ee1234567890","commit":{"message":"1.1.2: one repository page\n\nlong body","author":{"name":"dbal","date":"2026-10-21T16:20:00Z"}}},
            {"sha":"cb05b23abcdef","commit":{"message":"","author":{"name":"x","date":"2026-10-20T10:00:00Z"}}},{"nosha":1}]"""
        val rows = RepoActivity.parseCommits(json)
        ok(rows.size == 2, "commits without a sha are skipped")
        ok(rows[0].message == "1.1.2: one repository page" && rows[0].short == "e86c8ee" && rows[0].author == "dbal", "first line of the message, short sha, author")
        ok(rows[1].message == "(no message)", "an empty message gets a placeholder")
        ok(rows[0].time > rows[1].time && rows[0].time > 0, "times are read and ordered")
        ok(RepoActivity.parseCommits("not json").isEmpty() && RepoActivity.parseCommits("{}").isEmpty(), "bad answers give an empty list")
        ok(RepoActivity.parseTime("2026-10-21T16:20:00Z") - RepoActivity.parseTime("2026-10-21T16:19:00Z") == 60_000L, "time stamps are exact")
        ok(RepoActivity.parseTime("garbage") == 0L && RepoActivity.parseTime(null) == 0L, "unreadable times are zero")
        ok(RepoActivity.commitsPath("a/b", "main") == "/repos/a/b/commits?sha=main&per_page=20", "commits path")
    }

    private fun run(status: String, conclusion: String) = SelfFix.Run(1, "build", status, conclusion, "abc1234", "t", "")

    private fun runs() {
        ok(RepoActivity.state(run("completed", "success")) == RunState.PASSED, "success is passed")
        ok(RepoActivity.state(run("completed", "failure")) == RunState.FAILED && RepoActivity.state(run("completed", "timed_out")) == RunState.FAILED, "failure and timeout are failed")
        ok(RepoActivity.state(run("in_progress", "")) == RunState.RUNNING && RepoActivity.state(run("queued", "")) == RunState.RUNNING, "unfinished runs are running")
        ok(RepoActivity.state(run("completed", "cancelled")) == RunState.CANCELLED, "cancelled")
        ok(RepoActivity.state(run("completed", "neutral")) == RunState.OTHER, "anything else is just finished")
        ok(RepoActivity.runUrl("a/b", 42) == "https://github.com/a/b/actions/runs/42" && RepoActivity.commitUrl("a/b", "abc") == "https://github.com/a/b/commit/abc", "links")
    }

    private fun words() {
        ok(RepoActivity.files(1) == "1 file" && RepoActivity.files(3) == "3 files", "file counts")
        ok(RepoActivity.notLinked("", "t") != null && RepoActivity.notLinked("a/b", "") != null && RepoActivity.notLinked("a/b", "t") == null, "unlinked and token messages")
        ok(RepoActivity.apiError(403).contains("token") && RepoActivity.apiError(404).contains("repository") && RepoActivity.apiError(500).contains("500"), "error wording")
    }
}
