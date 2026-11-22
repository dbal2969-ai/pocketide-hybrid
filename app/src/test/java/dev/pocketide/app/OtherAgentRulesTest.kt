package dev.pocketide.app

import dev.pocketide.app.core.Memory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OtherAgentRulesTest {
    private val files = mapOf(
        ".clinerules/style.md" to "Use 2-space indents.",
        ".clinerules/api.md" to "---\npaths:\n  - \"src/api/**\"\n---\nValidate every request body.",
        ".kilo/rules/kilo.md" to "Kilo rule.",
        ".roo/rules/roo.txt" to "Roo rule.",
        ".cursor/rules/react.mdc" to "---\ndescription: React\nglobs: src/**/*.tsx, src/**/*.jsx\nalwaysApply: false\n---\nUse function components.",
        ".cursor/rules/always.mdc" to "---\nalwaysApply: true\n---\nNever commit secrets.",
        ".cursor/rules/manual.mdc" to "---\ndescription: only when asked\nalwaysApply: false\n---\nManual rule.",
        ".windsurf/rules/model.md" to "---\ntrigger: model_decision\n---\nModel rule.",
        ".windsurfrules" to "Windsurf root rule.",
        ".cursor/rules/image.png" to "not a rule"
    )

    @Test fun frontMatter() {
        assertEquals(mapOf("paths" to listOf("src/api/**")), Memory.frontMatter(files[".clinerules/api.md"]!!))
        val fm = Memory.frontMatter(files[".cursor/rules/react.mdc"]!!)
        assertEquals(listOf("src/**/*.tsx", "src/**/*.jsx"), fm["globs"]); assertEquals(listOf("false"), fm["alwaysApply"])
        assertEquals(mapOf("globs" to listOf("a", "b")), Memory.frontMatter("---\nglobs: [\"a\", \"b\"]\n---\nx"))
        assertTrue(Memory.frontMatter("no front matter").isEmpty())
    }

    @Test fun pickedByFocus() {
        val none = Memory.ruleSections(files.keys, { files[it] }, listOf("", "README.md"))
        for (r in listOf("Use 2-space indents.", "Kilo rule.", "Roo rule.", "Never commit secrets.", "Windsurf root rule.")) assertTrue(r, none.contains(r))
        for (r in listOf("Validate every request body.", "Use function components.", "Manual rule.", "Model rule.", "not a rule")) assertFalse(r, none.contains(r))
        val api = Memory.ruleSections(files.keys, { files[it] }, listOf("", "src/api/users.ts"))
        assertTrue(api.contains("Validate every request body.") && !api.contains("Use function components."))
        val ui = Memory.ruleSections(files.keys, { files[it] }, listOf("src", "src/ui/App.tsx"))
        assertTrue(ui.contains("Use function components."))
        assertTrue("root single files come first", none.indexOf("Windsurf root rule.") < none.indexOf("Use 2-space indents."))
        assertTrue("capped", Memory.ruleSections((1..40).map { ".kilo/rules/r$it.md" }, { "x".repeat(400) }, emptyList()).length <= Memory.RULES_MAX)
    }

    @Test fun dotFoldersAreFoundInARealProject() {
        // the project list skips dot-folders; the chat adds Memory.HIDDEN_DIRS with filesUnder, so check that finds them
        val dir = File(System.getProperty("java.io.tmpdir"), "rules-" + System.nanoTime())
        try {
            val st = ProjectStore(dir, seed = false)
            st.write(".github/instructions/ts.instructions.md", "---\napplyTo: \"**/*.ts\"\n---\nStrict TS.")
            st.write(".clinerules/a.md", "Cline A.")
            st.write("src/main.ts", "x")
            assertFalse("the normal list hides them", st.list().any { it.startsWith(".") })
            val hidden = Memory.HIDDEN_DIRS.flatMap { st.filesUnder(it) } + Memory.RULE_FILES.filter { st.exists(it) }
            assertTrue(hidden.toString(), ".github/instructions/ts.instructions.md" in hidden && ".clinerules/a.md" in hidden)
            assertTrue(Memory.scopedSections(st.list() + hidden, { st.read(it) }, listOf("", "src/main.ts")).contains("Strict TS."))
            assertTrue(Memory.ruleSections(hidden, { st.read(it) }, emptyList()).contains("Cline A."))
        } finally { dir.deleteRecursively() }
    }

    // 1.5.141 (Cursor parity): rules picked by description are listed for the model; @name in the request includes a rule
    @Test fun rulesPickedByDescriptionAreListed() {
        val out = Memory.ruleSections(files.keys, { files[it] }, listOf("src/server.js"), "Fix the login page")
        assertTrue(out, out.contains("Rules from .cursor/rules/always.mdc"))
        assertFalse(out, out.contains("Use function components."))   // globs do not match src/server.js
        assertFalse(out, out.contains("- .cursor/rules/react.mdc"))   // it has globs, so it is not picked by description
        assertTrue(out, out.contains("- .cursor/rules/manual.mdc: only when asked"))
        assertFalse(out, out.contains("Manual rule."))
    }

    @Test fun anAtNameIncludesTheRule() {
        val out = Memory.ruleSections(files.keys, { files[it] }, listOf("src/server.js"), "Tidy this up, see @manual")
        assertTrue(out, out.contains("Rules from .cursor/rules/manual.mdc"))
        assertTrue(out, out.contains("Manual rule."))
        assertFalse(out, out.contains("- .cursor/rules/manual.mdc:"))
        assertFalse(Memory.mentioned(".cursor/rules/manual.mdc", "email me at x@manual.com"))
    }

    @Test fun aRuleWithNoDescriptionIsNotListed() {
        val fm = Memory.frontMatter("---\nalwaysApply: false\n---\nx")
        assertFalse(Memory.agentRequested(fm))
        assertTrue(Memory.agentRequested(Memory.frontMatter("---\ndescription: Database work\nalwaysApply: false\n---\nx")))
        assertTrue(Memory.agentRequested(Memory.frontMatter("---\ntrigger: model_decision\ndescription: Tests\n---\nx")))
        assertFalse(Memory.agentRequested(Memory.frontMatter("---\ndescription: React\nglobs: src/**/*.tsx\nalwaysApply: false\n---\nx")))
    }

    @Test fun theNoteNamesTheFilesFollowed() {
        val section = Memory.section("Use tabs.") + Memory.sections { f -> if (f == "AGENTS.md") "Run npm test." else null } +
            Memory.ruleSections(files.keys, { files[it] }, emptyList())
        val src = Memory.sources(section)
        assertEquals(listOf("POCKETIDE.md", "AGENTS.md"), src.take(2))
        assertTrue(src.toString(), ".cursor/rules/always.mdc" in src && ".cursor/rules/manual.mdc" !in src)
        assertEquals("Following POCKETIDE.md and AGENTS.md.", Memory.sourcesNote(listOf("POCKETIDE.md", "AGENTS.md")))
        assertNull(Memory.sourcesNote(emptyList()))
    }
}
