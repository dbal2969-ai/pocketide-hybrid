package dev.pocketide.app

import dev.pocketide.app.core.CiFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CiFailureTest {
    @Test fun findsFailedJobAndStep() {
        val json = """{"jobs":[{"id":1,"name":"lint","conclusion":"success","steps":[]},
            {"id":22,"name":"build","conclusion":"failure","steps":[{"name":"Checkout","conclusion":"success"},{"name":"Gradle build","conclusion":"failure"},{"name":"Upload","conclusion":"skipped"}]}]}"""
        val f = CiFailure.failedJobs(json)
        assertEquals(1, f.size)
        assertEquals(CiFailure.FailedJob(22, "build", "Gradle build"), f[0])
        assertEquals(emptyList<CiFailure.FailedJob>(), CiFailure.failedJobs("nope"))
    }

    @Test fun excerptKeepsTheError() {
        val log = (1..300).joinToString("\n") { "2026-10-22T10:00:00.0000000Z line $it" } +
            "\n2026-10-22T10:00:01.0000000Z e: MainActivity.kt:12:5 Unresolved reference: foo\n2026-10-22T10:00:01.0000000Z ##[error]Process completed with exit code 1.\n" +
            (1..5).joinToString("\n") { "2026-10-22T10:00:02.0000000Z after $it" }
        val e = CiFailure.excerpt(log)
        assertTrue(e.contains("Unresolved reference: foo"))
        assertTrue(e.contains("ERROR: Process completed"))
        assertTrue(!e.contains("2026-10-22T"))
        assertTrue(!e.contains("line 10\n"))
        val p = CiFailure.prompt("Fix menu", "Android CI", CiFailure.FailedJob(22, "build", "Gradle build"), e)
        assertTrue(p.contains("at step \"Gradle build\""))
    }
}
