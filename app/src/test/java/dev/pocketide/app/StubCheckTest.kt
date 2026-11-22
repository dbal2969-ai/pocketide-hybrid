package dev.pocketide.app

import dev.pocketide.app.core.StubCheck
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StubCheckTest {
    @Test fun flagsStandIns() {
        assertNotNull(StubCheck.find("a.js", "function f() {\n  // TODO: implement\n}"))
        assertNotNull(StubCheck.find("a.py", "def f():\n    raise NotImplementedError"))
        assertNotNull(StubCheck.find("a.js", "  "))
        assertNotNull(StubCheck.find("b.kt", "// ... rest of the code"))
        assertNotNull(StubCheck.find("src/duration.js", "function parseDuration(text) {\n    // implement the function according to the rules in README.md\n    // for now, just return 0\n    return 0;\n}"))
        assertNotNull(StubCheck.find("a.py", "def f(x):\n    # for now just return None\n    return None"))
        assertNotNull(StubCheck.find("a.js", "function f() {\n  // the logic goes here\n}"))
        assertNotNull(StubCheck.find("a.js", "function f() {\n  // add your implementation here\n}"))
    }
    @Test fun commentsAboutFinishedCodeAreNotStandIns() {
        assertNull(StubCheck.find("a.js", "// Implements the rules in README.md\nfunction f() { return 1; }"))
        assertNull(StubCheck.find("a.js", "// Nothing to return for now: callers check for null\nfunction f() { return null; }"))
        assertNull(StubCheck.find("a.js", "// the code here sorts by name\nlist.sort();"))
    }
    @Test fun leavesRealCodeAndOtherFiles() {
        assertNull(StubCheck.find("a.js", "const x = 1;\nconsole.log(x);"))
        assertNull(StubCheck.find("data.json", ""))
        assertNull(StubCheck.find("README.md", "TODO: implement later"))
    }
}
