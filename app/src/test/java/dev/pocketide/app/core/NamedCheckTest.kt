package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Benchmark run 6, task 08: Nano's regex lost its backslashes, and Nano then rewrote the test it was asked to pass. */
class NamedCheckTest {
    private val ask = "Make signup reject empty or malformed emails so node test.js prints ok"
    private val test = "const { signup } = require('./signup.js');\nlet bad = 0;\nif (signup({ email: 'a@b.co' }).ok !== true) { console.error('good email was refused'); bad++; }\nif (bad) process.exit(1);\nconsole.log('ok');\n"

    @Test fun aRegexWithSingleBackslashesKeepsThem() {
        val o = MiniJson.obj(MiniJson.parse("""{"content":"if (!/^\S+@\S+\.\S+$/.test(e)) return false;\nok"}"""))!!
        assertEquals("if (!/^\\S+@\\S+\\.\\S+$/.test(e)) return false;\nok", o["content"])
    }

    @Test fun validEscapesAreUnchanged() =
        assertEquals("a\"b\\c/d\te", MiniJson.obj(MiniJson.parse("""{"x":"a\"b\\c\/d\te"}"""))!!["x"])

    @Test fun theNamedTestIsFound() = assertEquals(setOf("test.js"), Scope.namedChecks(ask))

    @Test fun changingALineOfTheNamedTestIsRefused() {
        val why = Scope.checkRefusal("test.js", test, test.replace("bad++; }", "bad++; } else if (x) {}"), ask)
        assertNotNull(why); assertTrue(why!!, why.startsWith("Not written: test.js is the check the person asked to pass"))
    }

    @Test fun addingLinesIsAllowed() = assertNull(Scope.checkRefusal("test.js", test, test + "console.log('more');\n", ask))

    @Test fun otherFilesAreNotGuarded() = assertNull(Scope.checkRefusal("signup.js", "a\n", "b\n", ask))

    @Test fun aRequestToChangeTheTestsIsFollowed() =
        assertNull(Scope.checkRefusal("test.js", test, "console.log('ok');\n", "Update the tests in test.js for the new rules, then run node test.js"))
}
