package dev.pocketide.app

import dev.pocketide.app.core.ToolLoop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolLoopTest {
    @Test fun secondIdenticalReadIsRefusedButOtherCallsAllowTwo() {
        val a = ToolLoop(); assertFalse(a.repeated("read_file", "{\"path\":\"a\"}")); assertTrue(a.repeated("read_file", "{\"path\":\"a\"}"))
        val b = ToolLoop(); assertFalse(b.repeated("grep", "{}")); assertFalse(b.repeated("grep", "{}")); assertTrue(b.repeated("grep", "{}"))
    }

    @Test fun researchRequestsAreNotPushedToEdit() {
        val r = dev.pocketide.app.core.Requirements
        assertTrue(r.asksForResearch("Deep dive into https://archive.openrs2.org/ and runescape related links that are branching from it and see what code we can use to fill in the gaps we are missing"))
        assertTrue(r.asksForResearch("research copilot, claude, workshop.ai, openrouter and other vibe and ide programs to see what we can build off of them to improve pocketide"))
        assertTrue(r.asksForResearch("Review this repo to see if it is a good match for our project"))
        assertFalse(r.asksForResearch("Fix the game to play just like osrs"))
        assertFalse(r.asksForResearch("Look into why the preview is blank and fix it"))
        assertFalse(r.asksForResearch("Make all the menus in the hud function as intended"))
        assertFalse(r.asksForResearch("Unzip the fix and copy everything over game/"))
    }
}
