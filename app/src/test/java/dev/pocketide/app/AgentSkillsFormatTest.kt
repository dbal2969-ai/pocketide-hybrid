package dev.pocketide.app

import dev.pocketide.app.core.Skills
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSkillsFormatTest {
    private val pdf = Skills.parse(".claude/skills/pdf-forms/SKILL.md",
        "---\nname: pdf-forms\ndescription: Fill in PDF forms and read form fields. Use when the user mentions a PDF form.\nlicense: Apache-2.0\nallowed-tools: Bash Read\n---\n# PDF forms\nRun scripts/fill.py with the field values.")
    private val deploy = Skills.parse(".agents/skills/deploy/SKILL.md", "---\nname: deploy\ndescription: Release steps for the site\n---\nBuild, then upload.")
    private val old = Skills.parse(".pocketide/skills/add-a-page.md", "---\nname: Add a page\nkeywords: page, route\n---\nAdd it to the menu.")

    @Test fun folderSkillsParse() {
        assertEquals("pdf forms", pdf.name)
        assertTrue(pdf.description.startsWith("Fill in PDF forms"))
        assertTrue("name words are keywords", "pdf" in pdf.keywords && "forms" in pdf.keywords)
        assertTrue(pdf.body.startsWith("# PDF forms"))
        assertEquals("a folder without a name in its header is named after the folder", "my tool", Skills.parse("x/skills/my-tool/SKILL.md", "Body only.").name)
        assertEquals("single-file skills keep working", "Add a page", old.name)
    }

    @Test fun matchedInFullOthersListed() {
        val all = listOf(old, pdf, deploy)
        val matched = Skills.match(all, "fill in this pdf form for me")
        assertEquals(listOf(pdf), matched)
        val note = Skills.note(matched)
        assertTrue(note, note.contains("Run scripts/fill.py") && note.contains("are in .claude/skills/pdf-forms/"))
        val cat = Skills.catalog(all, matched)
        assertTrue(cat, cat.contains("deploy: Release steps for the site (.agents/skills/deploy/SKILL.md)"))
        assertFalse("matched skills are not listed again", cat.contains("pdf forms:"))
        assertFalse("skills without a description are not listed", cat.contains("Add a page"))
        assertEquals("", Skills.catalog(listOf(pdf), listOf(pdf)))
        val many = (1..80).map { Skills.parse(".agents/skills/s$it/SKILL.md", "---\ndescription: does thing number $it in a fairly long sentence\n---\nx") }
        val c = Skills.catalog(many, emptyList())
        assertTrue(c.length <= Skills.CATALOG_BUDGET + 40 && c.contains("more"))
    }

    @Test fun firstOfANameWins() {
        val a = Skills.parse(".pocketide/skills/deploy/SKILL.md", "---\nname: deploy\ndescription: ours\n---\nx")
        assertEquals(listOf(a), Skills.dedupe(listOf(a, deploy)))
        assertEquals(".pocketide/skills", Skills.DIRS.first())
    }
}
