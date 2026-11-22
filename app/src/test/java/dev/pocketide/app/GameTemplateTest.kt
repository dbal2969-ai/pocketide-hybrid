package dev.pocketide.app

import dev.pocketide.app.core.GameTemplates
import dev.pocketide.app.core.Templates
import org.junit.Assert.*
import org.junit.Test

class GameTemplateTest {
    @Test fun templatesAreListedAndWired() {
        for (id in listOf("game2d", "game3d")) {
            val t = Templates.byId(id)
            assertEquals(id, t.id)
            val files = Templates.render(t, "My Game")
            assertTrue(files.getValue("index.html").contains("My Game"))
            assertTrue(files.values.any { it.contains("engine/") })
            assertEquals("game.js", t.open)
            assertTrue(t.assets.keys.any { it.endsWith("LICENSE.txt") })
            assertTrue(t.assets.values.all { it.startsWith("engine/") })
        }
        assertTrue(Templates.byId("game2d").assets.containsKey("engine/phaser.min.js"))
        assertTrue(Templates.byId("game3d").assets.containsKey("engine/three.module.min.js"))
        assertTrue(Templates.byId("game3d").files.getValue("index.html").contains("type=\"module\""))
    }
    @Test fun engineFilesAreRecognised() {
        assertTrue(GameTemplates.isEngineFile("engine/phaser.min.js"))
        assertFalse(GameTemplates.isEngineFile("game.js"))
    }
}
