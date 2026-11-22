package dev.pocketide.app

import dev.pocketide.app.core.Parts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParallelPartsTest {
    private val reply = """
        PARTS: Tiny game
        1. Game rules: 1, 2, 3 | files: rules.js, data/items.json
        2. Screen and controls: 4, 5 | files: `index.html`, style.css, ui/
        3. Sounds: 6 | files: sound.js

        CONTRACT:
        - index.html loads rules.js, sound.js, then ui/main.js in this order.
        - rules.js defines window.Rules.attack(attacker, target) returning the damage.
        - the canvas has id "game".
    """.trimIndent()

    @Test
    fun filesAreReadFromTheGrouping() {
        val parts = Parts.parse(reply, 6)!!
        assertEquals(3, parts.size)
        assertEquals(listOf("rules.js", "data/items.json"), parts[0].files)
        assertEquals(listOf("index.html", "style.css", "ui/"), parts[1].files)
        assertEquals(listOf(6), parts[2].numbers)
    }

    @Test
    fun theAgreementIsKept() {
        val c = Parts.contract(reply)
        assertTrue(c.contains("window.Rules.attack"))
        assertTrue(c.contains("id \"game\""))
        assertEquals("", Parts.contract("PARTS: x\n1. A: 1\n2. B: 2"))
    }

    @Test
    fun partsWithoutFilesAreNotIndependent() {
        val plain = Parts.parse("PARTS: x\n1. A: 1, 2\n2. B: 3", 3)!!
        assertFalse(Parts.independent(plain))
        assertTrue(Parts.independent(Parts.parse(reply, 6)!!))
    }

    @Test
    fun aSharedFileOrFolderMakesThePartsDependent() {
        val same = Parts.parse("PARTS: x\n1. A: 1 | files: a.js, shared.js\n2. B: 2 | files: b.js, shared.js", 2)!!
        assertFalse(Parts.independent(same))
        val folder = Parts.parse("PARTS: x\n1. A: 1 | files: src/\n2. B: 2 | files: src/b.js", 2)!!
        assertFalse(Parts.independent(folder))
        val fine = Parts.parse("PARTS: x\n1. A: 1 | files: src/world/\n2. B: 2 | files: src/ui/b.js", 2)!!
        assertTrue(Parts.independent(fine))
    }

    @Test
    fun aPartMayWriteOnlyItsOwnFiles() {
        val own = listOf("rules.js", "ui/")
        assertTrue(Parts.owns(own, "rules.js"))
        assertTrue(Parts.owns(own, "./rules.js"))
        assertTrue(Parts.owns(own, "ui/main.js"))
        assertFalse(Parts.owns(own, "sound.js"))
        assertFalse(Parts.owns(own, "uix/main.js"))
        assertTrue(Parts.refusal(own, "sound.js").contains("belongs to another part"))
    }

    @Test
    fun forgottenRequirementsKeepTheLastPartsFiles() {
        val parts = Parts.parse("PARTS: x\n1. A: 1 | files: a.js\n2. B: 2 | files: b.js", 4)!!
        assertEquals(listOf(2, 3, 4), parts[1].numbers)
        assertEquals(listOf("b.js"), parts[1].files)
    }

    @Test
    fun theMessageForAPartNamesItsFilesTheOthersAndTheAgreement() {
        val parts = Parts.parse(reply, 6)!!
        val reqs = (1..6).map { "requirement $it" }
        val m = Parts.parallelPrompt("Make a game", reqs, parts, 1, Parts.contract(reply), "")
        assertTrue(m.contains("Write only these files: index.html, style.css, ui/"))
        assertTrue(m.contains("- Game rules: rules.js, data/items.json"))
        assertTrue(m.contains("window.Rules.attack"))
        assertTrue(m.contains("Part done:"))
        assertFalse(m.contains("Earlier parts are already in the project"))
    }

    @Test
    fun theGroupingRequestAsksForFilesAndAnAgreement() {
        val r = Parts.requestParallel("Make a game", listOf("a", "b"))
        assertTrue(r.contains("files:"))
        assertTrue(r.contains("CONTRACT:"))
        assertTrue(r.contains("AT THE SAME TIME"))
    }

    @Test
    fun lanesStartWithTheMainProviderAndKeepOneMessageFormat() {
        val kind = mapOf("a" to "OPENAI", "b" to "OPENAI", "c" to "ANTHROPIC", "d" to "OPENAI")
        val lanes = Parts.lanes("b", listOf("a", "b", "c", "d"), { kind.getValue(it) })
        assertEquals(listOf("b", "a", "d"), lanes)
        assertEquals(emptyList<String>(), Parts.lanes("z", listOf("a"), { "OPENAI" }))
        assertEquals(listOf("a"), Parts.lanes("a", listOf("a", "c"), { kind.getValue(it) }))
    }

    @Test
    fun theCardShowsTheFilesEachPartOwns() {
        val p = Parts.planWithFiles(Parts.parse(reply, 6)!!, "Tiny game")
        assertEquals(3, p.steps.size)
        assertTrue(p.steps[0].contains("files: rules.js, data/items.json"))
        assertNotNull(p.title)
    }

    @Test fun theAssistantPicksAModelPerPart() {
        val reply = "PARTS: game\n1. Rules: 1, 2 | files: rules.js | model: 2\n2. Screen: 3 | files: index.html, ui.js | model: 1\n3. Sounds: 4 | files: sfx.js\nCONTRACT:\nx"
        val parts = Parts.parse(reply, 4)!!
        assertEquals(listOf(2, 1, null), parts.map { it.model })
        assertEquals(listOf("rules.js"), parts[0].files)
        assertEquals(listOf(1, 0, 0), Parts.assign(parts, 2))
        assertEquals(listOf(0, 0, 0), Parts.assign(parts, 1))   // a choice outside the team is ignored
        assertTrue(Parts.teamNote(listOf("a (A)", "b (B)")).contains("2. b (B)"))
        assertTrue(Parts.shouldSplit(3, true, always = true)); assertFalse(Parts.shouldSplit(3, true))
    }

    @Test fun teamLinesNameAProviderAndAModel() {
        val providers = listOf("anthropic" to "Anthropic", "custom" to "Custom server", "cloudflare" to "Cloudflare Workers AI")
        val team = Parts.parseTeam("anthropic claude-sonnet-5-5\nCustom server anthropic/claude-haiku-5-5\ncloudflare @cf/moonshotai/kimi-k2.6\ncloudflare @cf/moonshotai/kimi-k2.6\nnobody x\nanthropic", providers)
        assertEquals(listOf("anthropic", "custom", "cloudflare", "cloudflare", "anthropic"), team.map { it.provider })
        assertEquals("anthropic/claude-haiku-5-5", team[1].model); assertEquals("", team[4].model)
    }

    @Test
    fun wholeCheckAfterParallelParts() {
        val files = (1..70).map { "src/f$it.js" }
        val t = Parts.wholeCheckPrompt("Make a game", listOf("enemies move", "score shows"), files, "game")
        assertTrue("asks to run the tests or program", t.contains("Run the project's tests"))
        assertTrue("asks for look_at_preview", t.contains("look_at_preview"))
        assertTrue("lists requirements", t.contains("enemies move") && t.contains("score shows"))
        assertTrue("names files and counts the rest", t.contains("src/f1.js") && t.contains("and 10 more") && !t.contains("src/f61.js"))
        assertTrue("keeps the folder scope", t.contains("\"game\""))
        assertTrue("the parallel prompt promises this check", Parts.parallelPrompt("x", listOf("a"), listOf(Parts.Part("A", listOf(1), listOf("a.js"))), 0, "").contains("a check of the whole result runs after all parts are done"))
    }
}
