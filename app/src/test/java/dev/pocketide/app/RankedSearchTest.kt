package dev.pocketide.app

import dev.pocketide.app.core.RankedSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RankedSearchTest {
    private val files = mapOf(
        "game/combat.js" to "// fighting\nexport function applyDamage(target, amount) {\n  target.hp -= amount;\n  if (target.hp <= 0) target.die();\n}\n",
        "game/ui.js" to "export function drawHealthBar(ctx, player) {\n  ctx.fillRect(0, 0, player.hp, 4);\n}\n",
        "game/bank.js" to "export class Bank {\n  deposit(id, n) { this.items[id] = (this.items[id] || 0) + n; }\n}\n",
        "tests/combat.test.js" to "test('apply damage lowers hp', () => { applyDamage(p, 3); expect(p.hp).toBe(7); });\n")

    @Test fun wordsSplitIdentifiers() {
        assertEquals(listOf("load", "user", "user", "name"), RankedSearch.words("loadUser user_name"))
        assertEquals(listOf("health", "bar"), RankedSearch.words("HealthBars"))
    }

    @Test fun findsCodeByWhatItDoes() {
        val hits = RankedSearch.search("where is damage applied to the target", files)
        assertEquals("game/combat.js", hits.first().path)
        assertTrue(hits.first().lines.any { it.second.contains("applyDamage") })
    }

    @Test fun testFilesRankBelowTheCodeUnlessAsked() {
        val normal = RankedSearch.search("apply damage", files).map { it.path }
        assertTrue(normal.indexOf("game/combat.js") < normal.indexOf("tests/combat.test.js"))
        assertEquals("tests/combat.test.js", RankedSearch.search("test that apply damage lowers hp", files).first().path)
    }

    @Test fun nothingMatchesGivesAHelpfulAnswer() {
        assertTrue(RankedSearch.search("zebra spaceship", files).isEmpty())
        assertTrue(RankedSearch.format("zebra", emptyList()).contains("Try other words"))
        val f = RankedSearch.format("bank deposit", RankedSearch.search("bank deposit", files))
        assertTrue(f, f.contains("game/bank.js:1-") && f.contains("deposit"))
    }

    @Test fun longFilesGiveOneHitPerStretch() {
        val big = (1..200).joinToString("\n") { if (it == 120) "function saveGame() { localStorage.setItem('save', json); }" else "// line $it" }
        val hits = RankedSearch.search("save game", mapOf("game/save.js" to big))
        assertTrue(hits.isNotEmpty()); assertTrue(hits.first().start <= 120 && hits.first().end >= 120)
        assertEquals(hits.size, hits.map { it.start }.distinct().size)
    }
}
