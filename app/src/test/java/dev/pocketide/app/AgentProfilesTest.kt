package dev.pocketide.app

import dev.pocketide.app.core.AgentProfiles

import org.junit.Assert.*
import org.junit.Test

class AgentProfilesTest {
    @Test fun unknownFallsBackToGeneral() { assertEquals(AgentProfiles.GENERAL, AgentProfiles.byId("nope").id) }
    @Test fun generalAddsNothing() { assertEquals("", AgentProfiles.note(AgentProfiles.GENERAL)); assertEquals("", AgentProfiles.chipSuffix(AgentProfiles.GENERAL)) }
    @Test fun readOnlyRoles() {
        for (id in listOf("explorer", "planner", "reviewer")) assertTrue(id, AgentProfiles.readOnly(id))
        for (id in listOf("general", "debugger", "tester", "artist", "gamedev", "builder")) assertFalse(id, AgentProfiles.readOnly(id))
    }
    @Test fun everyRoleHasRulesAndUniqueIds() {
        assertEquals(AgentProfiles.ALL.size, AgentProfiles.ALL.map { it.id }.toSet().size)
        for (p in AgentProfiles.ALL.drop(1)) { assertTrue(p.id, p.rules.contains("Role:")); assertTrue(p.id, p.rules.contains("Never claim")) }
        assertTrue(AgentProfiles.note("reviewer").contains("never change anything"))
        assertEquals(" · Reviewer", AgentProfiles.chipSuffix("reviewer"))
    }
}
