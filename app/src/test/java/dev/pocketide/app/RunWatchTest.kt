package dev.pocketide.app

import dev.pocketide.app.core.RunState
import dev.pocketide.app.core.RunWatch
import dev.pocketide.app.core.SelfFix
import org.junit.Test

class RunWatchTest {
    private fun run(id: Long) = SelfFix.Run(id, "Build", "", "", "abc", "Add page", "")
    @Test fun watch() {
        val a = run(1); val b = run(2)
        val before = RunWatch.snapshot(listOf(a to RunState.RUNNING, b to RunState.PASSED))
        val now = listOf(a to RunState.PASSED, b to RunState.PASSED)
        check(RunWatch.finished(before, now).map { it.first.id } == listOf(1L))
        check(RunWatch.finished(emptyMap(), now).isEmpty())   // first look never notifies
        check(RunWatch.finished(before, listOf(a to RunState.RUNNING, b to RunState.PASSED)).isEmpty())
        check(RunWatch.anyRunning(listOf(a to RunState.RUNNING)) && !RunWatch.anyRunning(now))
        check(RunWatch.message(a, RunState.FAILED) == "Add page: failed")
    }
}
