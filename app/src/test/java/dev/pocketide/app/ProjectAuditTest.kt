package dev.pocketide.app

import dev.pocketide.app.core.ProjectAudit
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectAuditTest {
    @Test fun findsCopiesNestingAndUnusedFiles() {
        val files = mapOf(
            "art/hero.png" to "PIXELS".toByteArray(),
            "old/hero.png" to "PIXELS".toByteArray(),
            "cache/cache/data.json" to "{}".toByteArray(),
            "game/index.html" to "<img src=\"art/hero.png\"><script src=\"main.js\"></script>".toByteArray(),
            "game/main.js" to "console.log(1)".toByteArray(),
            "sounds/unused.wav" to "RIFF".toByteArray(),
            "game/.pocketide/progress.json" to "{}".toByteArray())
        val r = ProjectAudit.run(files.keys, { files[it]!!.size.toLong() }, { files[it] })
        assertTrue(r, r.contains("Identical copies: 1 group"))
        assertTrue(r, r.contains("Folders repeated inside themselves: 1 (cache/cache)"))
        assertTrue(r, r.contains("- sounds/unused.wav"))
        assertTrue(r, !r.contains("\n- art/hero.png\n") && !r.contains("\n- game/main.js"))
        assertTrue(r, !r.contains(".pocketide"))
    }
}
