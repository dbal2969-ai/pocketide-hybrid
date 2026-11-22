package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class OffloadTest {
    @Test fun theWorkRunsOnTheWorkerAndTheNextStepIsPostedAfterIt() {
        val order = ArrayList<String>()
        val posted = ArrayList<Runnable>()
        Offload.run({ order.add("snapshot") }, { posted.add(it) }, { r -> order.add("worker"); r.run() }) { order.add("command") }
        assertEquals(listOf("worker", "snapshot"), order)
        posted.single().run()
        assertEquals(listOf("worker", "snapshot", "command"), order)
    }

    @Test fun theCommandStillRunsWhenTheSnapshotFails() {
        var ran = false
        Offload.run({ throw OutOfMemoryError("big") }, { it.run() }, { it.run() }) { ran = true }
        assertEquals(true, ran)
    }
}
