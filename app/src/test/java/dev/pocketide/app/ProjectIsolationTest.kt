package dev.pocketide.app

import dev.pocketide.app.core.ProjectIsolation
import org.junit.Test

class ProjectIsolationTest {
    @Test
    fun isolationChecks() {
        val names = listOf("Snake", "snake", "My project", "my-project", "my_project", "A", "a", "日本語", "x".repeat(200), "Python project")
        val hosts = names.map { ProjectIsolation.previewHost(it) }
        val tmps = names.map { ProjectIsolation.guestTmpName(it) }
        check(hosts.toSet().size == names.size) { "every project name gets its own preview address: $hosts" }
        check(tmps.toSet().size == names.size) { "every project name gets its own /tmp folder: $tmps" }
        for (h in hosts) {
            check(h.endsWith(".pocketide.local") && h.length <= 253) { "valid host: $h" }
            val label = h.substringBefore('.')
            check(label.length <= 63 && Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?").matches(label)) { "valid dns label: $label" }
        }
        for (t in tmps) check(Regex("[a-z0-9][a-z0-9-]*").matches(t) && !t.contains("..") && !t.contains('/')) { "safe folder name: $t" }
        check(ProjectIsolation.previewHost("Snake") == ProjectIsolation.previewHost("Snake")) { "the address is stable" }
        check(ProjectIsolation.previewHost("../../etc").substringBefore('.').all { it.isLetterOrDigit() || it == '-' }) { "odd names stay safe" }
        println("OK project isolation: ${names.size} names, all separate")
    }
}
