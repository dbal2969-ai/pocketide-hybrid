package dev.pocketide.app

import dev.pocketide.app.core.GitBranches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitBranchesTest {
    @Test fun parsesBranchNamesSorted() {
        assertEquals(listOf("dev", "main"), GitBranches.names("""[{"name":"main","commit":{}},{"name":"dev"}]"""))
        assertTrue(GitBranches.names("not json").isEmpty())
    }
    @Test fun parsesHistory() {
        val e = GitBranches.log("""[{"sha":"abcdef1234567","commit":{"message":"Fix HUD\n\nlong body","author":{"name":"Dan","date":"2026-10-22T10:00:00Z"}}}]""")
        assertEquals(1, e.size)
        assertEquals("abcdef1  Fix HUD  (Dan, 2026-10-22)", e[0].line)
    }
    @Test fun createBodyHasRefAndSha() {
        assertEquals("""{"ref":"refs/heads/feature/x","sha":"abc"}""", GitBranches.createBody("feature/x", "abc"))
    }
}
