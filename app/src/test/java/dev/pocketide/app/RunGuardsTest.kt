package dev.pocketide.app

import dev.pocketide.app.core.HistoryTrim
import dev.pocketide.app.core.PageRefs
import dev.pocketide.app.core.ProjectGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunGuardsTest {
    @Test
    fun theShortenedNoteIsNeverAcceptedAsContent() {
        assertTrue(HistoryTrim.isPlaceholder("{\"path\":\"game/skills.js\",\"content\":\"[long text removed: 2750 characters]\"}"))
        assertTrue(HistoryTrim.isPlaceholder("{\"content\":\"[old text not kept: 2750 characters; not file content, do not copy]\"}"))
        assertTrue(HistoryTrim.isPlaceholder("cat x\n[older output removed to save space]"))
        assertFalse(HistoryTrim.isPlaceholder("{\"path\":\"a.js\",\"content\":\"export const x = 1;\"}"))
        // what the trimmer writes is what the guard recognises
        val shrunk = HistoryTrim.compact(arrayListOf<Map<String, Any?>>())
        assertEquals(0, shrunk)
    }

    @Test
    fun commandsOutsideTheProjectAreRefused() {
        val root = "/data/user/0/dev.pocketide.app/files/projects/p5"
        assertNotNull(ProjectGuard.outsideRefusal("cd /work/game && cat > main.js.part4 << 'EOF'", root))
        assertNotNull(ProjectGuard.outsideRefusal("echo hi > /work/game/main.js", root))
        assertNotNull(ProjectGuard.outsideRefusal("mkdir -p /work/game", root))
        assertNotNull(ProjectGuard.outsideRefusal("cp a.js /home/x/a.js", root))
        assertNull(ProjectGuard.outsideRefusal("cd game && node --check main.js", root))
        assertNull(ProjectGuard.outsideRefusal("node a.js > /dev/null 2>&1", root))
        assertNull(ProjectGuard.outsideRefusal("echo x > /tmp/out.txt", root))
        assertNull(ProjectGuard.outsideRefusal("cd $root/game && ls", root))
        assertNull(ProjectGuard.outsideRefusal("cat a | grep -v /x", root))
    }

    @Test
    fun aMissingPageFileIsReportedRightAway() {
        val files = mapOf("game/index.html" to "<script type=\"module\" src=\"main.js\"></script><link href=\"s.css\" rel=\"stylesheet\">", "game/skills.js" to "x", "game/s.css" to "")
        val out = PageRefs.missing(files.keys.toList(), { files[it] }, { files[it]?.length?.toLong() ?: 0L })
        assertTrue(out.any { it.contains("game/main.js") && it.contains("does not exist") })
        assertTrue(out.any { it.contains("game/s.css") && it.contains("empty") })
        val ok = files + ("game/main.js" to "y") + ("game/s.css" to "a{}")
        assertTrue(PageRefs.missing(ok.keys.toList(), { ok[it] }, { ok[it]?.length?.toLong() ?: 0L }).isEmpty())
    }
}
