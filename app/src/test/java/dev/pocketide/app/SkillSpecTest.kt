package dev.pocketide.app

import dev.pocketide.app.core.Skills
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillSpecTest {
    @Test fun validSkillHasNoProblems() {
        assertEquals(emptyList<String>(), Skills.specProblems("pdf-tools", "pdf-tools", "Reads PDFs"))
        assertEquals(emptyList<String>(), Skills.specProblems("a", null, "x"))
    }

    @Test fun badNamesAreReported() {
        assertTrue(Skills.specProblems("Bad_Name", null, "x").single().contains("must be 1-64"))
        assertTrue(Skills.specProblems("a--b", null, "x").single().contains("must be 1-64"))
        assertTrue(Skills.specProblems("a".repeat(65), null, "x").single().contains("must be 1-64"))
    }

    @Test fun nameMustMatchFolderAndDescriptionMustExist() {
        assertTrue(Skills.specProblems("folder", "other", "x").single().contains("does not match its folder"))
        assertEquals(listOf("no description"), Skills.specProblems("ok", null, "  "))
        assertEquals(listOf("description is longer than 1024 characters"), Skills.specProblems("ok", null, "d".repeat(1025)))
    }

    @Test fun parseKeepsProblemsAndStillLoads() {
        val s = Skills.parse(".pocketide/skills/Wrong/SKILL.md", "---\nname: Wrong\ndescription: does things\n---\nbody")
        assertEquals("does things", s.description)
        assertTrue(s.problems.isNotEmpty())
    }
}
