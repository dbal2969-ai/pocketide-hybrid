package dev.pocketide.app

import dev.pocketide.app.core.HybridStats
import dev.pocketide.app.core.HybridStats.Run
import org.junit.Test

class HybridStatsTest {
    @Test fun emptyHasANote() { check(HybridStats.summary(emptyList()).contains("No hybrid AI requests")) }

    @Test fun summaryCountsWhereRequestsRan() {
        val s = HybridStats.summary(listOf(
            Run("phone", 1.5, "", "chat"), Run("phone", 2.5, "", "agent"),
            Run("cloud", 3.0, "this needs the web, which Gemini Nano on the phone cannot reach", "chat"),
            Run("strong", 0.0, "Gemini Nano said it could not do it", "agent")))
        check(s.contains("Last 4 requests: 2 on this phone (50%), 1 in the cloud (25%), 1 passed to your strong model (25%)")) { s }
        check(s.contains("Average time on this phone: 2.0 s")) { s }
        check(s.contains("Average time in the cloud: 3.0 s")) { s }
        check(s.contains("1 needed the web") && s.contains("1 Nano could not do it")) { s }
    }
}
