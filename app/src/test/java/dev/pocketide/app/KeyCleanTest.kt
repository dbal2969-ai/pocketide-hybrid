package dev.pocketide.app

import dev.pocketide.app.core.KeyClean
import org.junit.Test

class KeyCleanTest {
    @Test fun clean() {
        val f = ArrayList<String>()
        fun check(got: String, want: String, m: String) { if (got != want) f.add("$m: got $got") }
        check(KeyClean.clean("  sk-ant-abc123\n"), "sk-ant-abc123", "trim")
        check(KeyClean.clean("sk-ant-abc\n123"), "sk-ant-abc123", "line break inside")
        check(KeyClean.clean("\"sk-ant-abc123\""), "sk-ant-abc123", "quotes")
        check(KeyClean.clean("Bearer sk-ant-abc123"), "sk-ant-abc123", "bearer")
        check(KeyClean.clean("x-api-key: sk-ant-abc123"), "sk-ant-abc123", "header label")
        check(KeyClean.clean("export ANTHROPIC_API_KEY=\"sk-ant-abc123\""), "sk-ant-abc123", "export line")
        check(KeyClean.clean("sk-ant-abc123"), "sk-ant-abc123", "plain")
        if (f.isNotEmpty()) throw AssertionError(f.joinToString("; "))
    }
}
