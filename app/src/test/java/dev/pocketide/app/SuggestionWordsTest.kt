package dev.pocketide.app

import dev.pocketide.app.core.SuggestionWords
import org.junit.Test

class SuggestionWordsTest {
    @Test fun words() {
        val f = ArrayList<String>()
        fun n(s: String) = SuggestionWords.nextLength(s)
        fun check(ok: Boolean, m: String) { if (!ok) f.add(m) }
        check(n("foo bar") == 3, "word")
        check(n("  foo bar") == 5, "leading spaces")
        check(n("(x, y)") == 1, "symbol")
        check(n("my_var2 = 3") == 7, "identifier")
        check(n("\nnext") == 1, "newline")
        check(n("  ") == 2 && n("") == 0, "edges")
        if (f.isNotEmpty()) throw AssertionError(f.joinToString("; "))
    }
}
