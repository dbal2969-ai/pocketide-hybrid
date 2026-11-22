package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RenameCheckTest {
    @Test fun theRenameIsReadFromTheRequest() {
        assertEquals(RenameCheck.Rename("getUser", "findUserById"), RenameCheck.asked(
            "Rename getUser to findUserById everywhere: the function, every place that uses it, the tests and the README."))
        assertEquals(RenameCheck.Rename("slugify", "toSlug"), RenameCheck.asked("please rename the function `slugify()` to `toSlug`"))
        assertEquals(RenameCheck.Rename("max_rows", "row_limit"), RenameCheck.asked("Can you rename max_rows as row_limit?"))
    }

    @Test fun narrowOrNonCodeRenamesAreLeftAlone() {
        assertNull(RenameCheck.asked("Rename getUser to findUserById only in src/users.js"))
        assertNull(RenameCheck.asked("rename i to index inside this function"))
        assertNull(RenameCheck.asked("rename the Save button to Submit"))
        assertNull(RenameCheck.asked("Fix the rename dialog"))
        assertNull(RenameCheck.asked("rename id to id"))
    }

    @Test fun onlyWholeWordsAreLeftAndHistoryFilesAreSkipped() {
        val files = mapOf(
            "src/users.js" to "function findUserById(id) {}\nfunction getUserName(id) {\n  const u = getUser(id);\n}\nfunction getUsers() {}\n",
            "README.md" to "- `getUser(id)`: one user\n- `getUsers(role)`\n",
            "CHANGELOG.md" to "Renamed getUser to findUserById.\n",
            "src/other.js" to "const \$getUser = 1; const getUser_old = 2;\n")
        val (lines, n) = RenameCheck.left("getUser", files.keys) { files[it] }
        assertEquals(2, n)
        assertEquals(listOf("README.md:1: - `getUser(id)`: one user", "src/users.js:3: const u = getUser(id);"), lines)
    }

    @Test fun theAskListsThePlacesAndHowManyMore() {
        val lines = (1..RenameCheck.SHOWN).map { "a.js:$it: getUser()" }
        val ask = RenameCheck.ask(RenameCheck.Rename("getUser", "findUserById"), lines, RenameCheck.SHOWN + 3)
        assertTrue(ask.startsWith("The rename is not finished: getUser still appears in ${RenameCheck.SHOWN + 3} place(s):\na.js:1: getUser()"))
        assertTrue(ask.contains("… and 3 more"))
        assertTrue(ask.contains("Change each one to findUserById"))
    }
}
