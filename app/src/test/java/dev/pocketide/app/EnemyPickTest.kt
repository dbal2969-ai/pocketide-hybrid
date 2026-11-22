package dev.pocketide.app

import dev.pocketide.app.addon.osrs.*

import dev.pocketide.app.core.*
import org.junit.Assert.*
import org.junit.Test

class EnemyPickTest {
    private fun npc(id: Int, name: String, level: Int) = CacheDefs.Def(id, linkedMapOf<String, Any?>("name" to name, "combatLevel" to level, "models" to listOf(id), "actions" to listOf(null, "Attack", null, null, null)), false)

    @Test fun pickIsRepeatableDifferentAndCounted() {
        val defs = (1..80).map { npc(it, "Mob${it % 30}", it) }
        val a = CacheTool.attackablePick(defs, "", mapOf("count" to "20", "seed" to "5", "min_level" to "10", "max_level" to "60"))!!
        val b = CacheTool.attackablePick(defs, "", mapOf("count" to "20", "seed" to "5", "min_level" to "10", "max_level" to "60"))!!
        assertEquals(a.first.map { it.id }, b.first.map { it.id })
        assertEquals(20, a.first.map { it.name }.toSet().size)
        assertTrue(a.first.all { (it.fields["combatLevel"] as Int) in 10..60 })
        assertNull(CacheTool.attackablePick(defs, "", mapOf("min_level" to "500")))
    }
    @Test fun scriptSrcIsNotReadAsPageSrc() {
        val r = GameCheck.check(listOf("g/main.js", "g/index.html"), { if (it == "g/main.js") "im.src = 'data/npcs/' + id + '.png'" else "<script src=\"main.js\"></script>" }, { 5L })
        assertTrue(r, r.contains("reads correctly"))
    }
}
