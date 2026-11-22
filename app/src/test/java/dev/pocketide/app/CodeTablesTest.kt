package dev.pocketide.app

import dev.pocketide.app.addon.osrs.*

import dev.pocketide.app.addon.osrs.CodeTables
import dev.pocketide.app.addon.osrs.ServerData
import org.junit.Assert.*
import org.junit.Test

class CodeTablesTest {
    private val java = """
        package x;
        import org.rs09.consts.Items;
        /** Things to cook. */
        public enum CookableItems {
            // meats
            CHICKEN(2140, 2138, 2144, 1, 30, 128, 512),
            SHRIMP(Items.SHRIMPS_315, Items.RAW_SHRIMPS_317, 7954, 1, 30.0, 128, 512), /* fish */
            LAVA_EEL(2149, 2148, 3383, 53, 30, 256, 256) { @Override public boolean never() { return true; } };
            private static final int SPEED = 4;
            CookableItems(int cooked, int raw, int burnt, int level, double experience, int low, int high) { this.cooked = cooked; }
            public int getRaw() { return raw; }
        }
    """.trimIndent()

    private val kotlin = """
        package y
        import org.rs09.consts.Items
        private const val BASE = 10
        enum class Gem(val uncut: Int, val cut: Int, val level: Int, val xp: Double, val anim: Animation, vararg val extra: Item) {
            SAPPHIRE(Items.UNCUT_SAPPHIRE_1623, Items.SAPPHIRE_1607, 20, 50.0, Animation(888), Item(Items.CHISEL_1755), Item(1592, 2)),
            OPAL(1625, 1609, BASE + 1, BASE * 1.5, Animation(890));
        }
        enum class QuestRequirements(val quest: Quests, vararg val requirements: Requirement) {
            COOK_ASSIST (Quests.COOKS_ASSISTANT),
            LOST_CITY (Quests.LOST_CITY, SkillReq(Skills.CRAFTING, 31, true), SkillReq(Skills.WOODCUTTING, 36, true)),
        }
        enum class Simple { A, B, C }
        enum class Words(val text: String, val pair: Pair<Int, Int>, val ids: IntArray) {
            HELLO("Hi, \"you\"!" + " there", 1 to 2, intArrayOf(1, 2, 3)),
        }
    """.trimIndent()

    @Test fun javaEnumsBecomeTablesWithTheConstructorsNames() {
        val t = CodeTables.enums(java, "CookableItems.java").single()
        assertEquals("CookableItems", t.name)
        assertEquals(listOf("cooked", "raw", "burnt", "level", "experience", "low", "high"), t.columns)
        assertEquals(3, t.rows.size)
        assertEquals(mapOf("key" to "SHRIMP", "cooked" to 315, "raw" to 317, "burnt" to 7954, "level" to 1, "experience" to 30.0, "low" to 128, "high" to 512), t.rows[1])
        assertEquals("item", t.kinds["cooked"]); assertEquals("item", t.kinds["raw"]); assertNull(t.kinds["burnt"])
        assertEquals(53, t.rows[2]["level"])   // a constant with its own body
    }

    @Test fun kotlinEnumsWithVarargsCallsAndConstants() {
        val ts = CodeTables.enums(kotlin, "Gem.kt").associateBy { it.name }
        val gem = ts.getValue("Gem")
        assertEquals(listOf("uncut", "cut", "level", "xp", "anim", "extra"), gem.columns)
        val s = gem.rows[0]
        assertEquals(1623, s["uncut"]); assertEquals(mapOf("call" to "Animation", "args" to listOf(888)), s["anim"])
        assertEquals(listOf(mapOf("item" to 1755, "amount" to 1), mapOf("item" to 1592, "amount" to 2)), s["extra"])
        assertEquals(11, gem.rows[1]["level"]); assertEquals(15.0, gem.rows[1]["xp"])   // BASE + 1, BASE * 1.5
        assertEquals(emptyList<Any?>(), gem.rows[1]["extra"])
        val q = ts.getValue("QuestRequirements").rows
        assertEquals("Quests.COOKS_ASSISTANT", q[0]["quest"])
        assertEquals(listOf(mapOf("call" to "SkillReq", "args" to listOf("crafting", 31, true)), mapOf("call" to "SkillReq", "args" to listOf("woodcutting", 36, true))), q[1]["requirements"])
        assertEquals(listOf("A", "B", "C"), ts.getValue("Simple").rows.map { it["key"] })
        val w = ts.getValue("Words").rows[0]
        assertEquals("Hi, \"you\"! there", w["text"]); assertEquals(listOf(1, 2), w["pair"]); assertEquals(listOf(1, 2, 3), w["ids"])
    }

    @Test fun commentsAndStringsDoNotConfuseTheReader() {
        assertEquals("a \"b\" // c", CodeTables.value("\"a \\\"b\\\" // c\""))
        assertEquals("x y", CodeTables.strip("x /* enum Fake { A } */ y").replace(Regex("\\s+"), " "))
        assertEquals("Hello {player.username}!", CodeTables.value("\"Hello \${player.username}!\""))
        assertEquals(7, CodeTables.value("(1 + 2) * 2 + 1"))
        assertEquals(-5, CodeTables.value("-5")); assertEquals(255, CodeTables.value("0xFF")); assertEquals(1.5, CodeTables.value("1.5f"))
        assertEquals("cooking", CodeTables.value("Skills.COOKING"))
    }

    @Test fun gameDataListsAndWritesTables() {
        val files = mutableMapOf(
            "s/Server/data/configs/npc_spawns.json" to "[]", "s/Server/data/configs/npc_configs.json" to "[]",
            "s/Server/src/main/content/global/skill/cooking/CookableItems.java" to java,
            "s/Server/src/main/content/global/skill/crafting/Gem.kt" to kotlin)
        val env = ServerData.Env(files = { files.keys.toList() }, read = { files[it] }, put = { p, b -> files[p] = String(b); true })
        val list = ServerData.run(mapOf("action" to "tables", "query" to "cooking"), env)
        assertTrue(list, list.contains("CookableItems (3 rows)") && !list.contains("Gem"))
        assertTrue(ServerData.run(mapOf("action" to "tables"), env).contains("5 data tables"))
        val one = ServerData.run(mapOf("action" to "table", "query" to "gem", "out" to "game/data"), env)
        assertTrue(one, one.startsWith("Wrote game/data/server/tables/Gem.json (2 rows)"))
        assertTrue(files.getValue("game/data/server/tables/Gem.json").contains("\"uncut\":1623"))
        assertTrue(ServerData.run(mapOf("action" to "table", "query" to "nothing"), env).startsWith("No table named"))
    }
}
