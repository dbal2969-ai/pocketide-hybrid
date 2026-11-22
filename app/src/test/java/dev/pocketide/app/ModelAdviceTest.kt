package dev.pocketide.app

import dev.pocketide.app.core.ModelAdvice
import org.junit.Test

class ModelAdviceTest {
    @Test fun advice() {
        val f = ArrayList<String>()
        fun check(ok: Boolean, m: String) { if (!ok) f.add(m) }
        check(ModelAdvice.note("groq", "gemma2-9b-it", false) != null, "gemma tools")
        check(ModelAdvice.note("groq", "llama-3.1-8b-instant", true) != null, "small big")
        check(ModelAdvice.note("groq", "llama-3.1-8b-instant", false) == null, "small simple")
        check(ModelAdvice.note("groq", "llama-3.3-70b-versatile", true) == null, "70b fine")
        check(ModelAdvice.note("openai", "gpt-5-nano", true) != null, "nano")
        check(ModelAdvice.note("openai", "gpt-5", true) == null, "gpt-5 fine")
        check(ModelAdvice.note("demo", "x", true) == null, "demo")
        check(ModelAdvice.note("groq", "openai/gpt-oss-120b", true) == null, "120b fine")
        if (f.isNotEmpty()) throw AssertionError(f.joinToString("; "))
    }
}
