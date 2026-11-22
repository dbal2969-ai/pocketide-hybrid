package dev.pocketide.app.core

/** Notices builds that were running the last time we looked and have now finished, so the person can be told. */
object RunWatch {
    class Seen(val id: Long, val state: RunState)

    /** Runs in [now] that [before] knew as running and that are no longer running. */
    fun finished(before: Map<Long, RunState>, now: List<Pair<SelfFix.Run, RunState>>): List<Pair<SelfFix.Run, RunState>> =
        now.filter { (r, st) -> before[r.id] == RunState.RUNNING && st != RunState.RUNNING }

    fun snapshot(now: List<Pair<SelfFix.Run, RunState>>): Map<Long, RunState> = now.associate { it.first.id to it.second }

    fun anyRunning(now: List<Pair<SelfFix.Run, RunState>>): Boolean = now.any { it.second == RunState.RUNNING }

    fun message(r: SelfFix.Run, st: RunState): String =
        (r.title.ifBlank { r.name }) + ": " + st.word.lowercase()
}
