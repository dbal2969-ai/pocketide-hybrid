package dev.pocketide.app

import dev.pocketide.app.core.SwallowedErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwallowedErrorsTest {
    @Test fun findsHandlersThatThrowTheErrorAway() {
        // the hard suite's async-order run
        val js = "async function saveAll(items) {\n  let saved = 0;\n  for (const item of items) {\n    try {\n      await store.insert(item);\n" +
            "      saved++;\n    } catch (e) {\n      // Invalid rows are ignored, but not counted as saved.\n    }\n  }\n  return saved;\n}\n"
        assertEquals(listOf(7), SwallowedErrors.lines("src/importer.js", js))
        assertEquals(listOf(1, 2, 3), SwallowedErrors.lines("a.ts", "p.catch(() => {});\nq.catch(e => null);\ntry { x() } catch {}\n"))
        assertEquals(listOf(3), SwallowedErrors.lines("a.py", "try:\n    x()\nexcept ValueError:\n    # fine\n    pass\n"))
        assertEquals(listOf(3), SwallowedErrors.lines("b.py", "try:\n    x()\nexcept Exception: pass\n"))
    }

    @Test fun handlersThatDoSomethingAreFine() {
        for (ok in listOf("try { a() } catch (e) { console.error(e); }", "try { a() } catch (e) { throw new Error('x: ' + e.message); }",
            "p.catch(e => log(e));", "p.catch(err => { errors.push(err); });"))
            assertTrue(ok, SwallowedErrors.lines("a.js", ok).isEmpty())
        assertTrue(SwallowedErrors.lines("b.py", "try:\n    x()\nexcept ValueError as e:\n    log(e)\n    pass\n").isEmpty())
        assertTrue("not code", SwallowedErrors.lines("notes.md", "catch (e) {}").isEmpty())
        assertTrue("a library", SwallowedErrors.lines("node_modules/x/a.js", "catch (e) {}").isEmpty())
    }

    @Test fun onlyAddedHandlersCount() {
        val old = "try { a() } catch (e) {}\n"
        assertTrue(SwallowedErrors.added("a.js", old, old + "b();\n").isEmpty())
        assertEquals(listOf(2), SwallowedErrors.added("a.js", old, old + "try { b() } catch (e) {}\n"))
        assertEquals(listOf(1), SwallowedErrors.added("a.js", null, old))
        assertTrue("unknown before", SwallowedErrors.added("a.js", null, old, known = false).isEmpty())
    }

    @Test fun complaintsOfSilence() {
        for (r in listOf("Our import says it saved every row, but right after it the store is often missing rows, they come out in a random order, and a bad row is silently ignored. Fix saveAll in src/importer.js.",
            "The upload fails without an error", "Errors are swallowed by the loader", "Invalid rows are ignored"))
            assertTrue(r, SwallowedErrors.complainsOfSilence(r))
        for (r in listOf("Add a dark mode", "The save button crashes", "Ignore the tests folder"))
            assertFalse(r, SwallowedErrors.complainsOfSilence(r))
        assertTrue(SwallowedErrors.note("a.js", listOf(4), true).contains("complained"))
        assertFalse(SwallowedErrors.note("a.js", listOf(4), false).contains("complained"))
    }
}
