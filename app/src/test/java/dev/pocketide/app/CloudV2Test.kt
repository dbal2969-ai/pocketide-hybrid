package dev.pocketide.app

import dev.pocketide.app.core.CloudAgent
import org.junit.Test

class CloudV2Test {
    @Test fun v2() {
        val f = ArrayList<String>()
        fun check(ok: Boolean, m: String) { if (!ok) f.add(m) }
        check(CloudAgent.tasks("fix the login bug").size == 1, "single")
        check(CloudAgent.tasks("1. fix login\n2. add tests for cart\n3. review the api").size == 3, "numbered")
        check(CloudAgent.tasks("- one\n- two").size == 2, "bullets")
        check(CloudAgent.tasks("- only one").size == 1, "one bullet")
        check(CloudAgent.tasks((1..9).joinToString("\n") { "$it. task $it" }).size == CloudAgent.MAX_TASKS, "cap")
        check(CloudAgent.kind("Add tests for the cart") == "tests", "tests")
        check(CloudAgent.kind("review the api module") == "review", "review")
        check(CloudAgent.kind("fix the crash on startup") == "fix", "fix")
        check(CloudAgent.kind("add a dark mode") == "feature", "feature")
        val p = CloudAgent.issuePayload("fix the crash on startup")
        check(p.contains("pocketide:fix") && p.contains("Task type: fix"), "payload")
        if (f.isNotEmpty()) throw AssertionError(f.joinToString("; "))
    }
}
