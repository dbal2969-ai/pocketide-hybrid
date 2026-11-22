package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDevTest {
    @Test fun addresses() {
        val c = RemoteDev.parse("ana@box.example.com", "", "My Game!")!!
        assertEquals("ana", c.user); assertEquals("box.example.com", c.host); assertEquals(22, c.port); assertEquals("~/pocketide/My_Game_", c.dir)
        assertEquals(2222, RemoteDev.parse("ana@10.0.0.5:2222", "/srv/app/", "x")!!.port)
        assertEquals("/srv/app", RemoteDev.parse("ana@10.0.0.5:2222", "/srv/app/", "x")!!.dir)
        assertEquals(2200, RemoteDev.parse("ssh -p 2200 ana@h", "", "x")!!.port)
        assertEquals("ana@h:2200", RemoteDev.parse("ssh -p 2200 ana@h", "", "x")!!.label)
        for (bad in listOf("box.example.com", "ana@", "a b@h", "ana@h:99999", "ana@h;rm -rf /")) assertNull(bad, RemoteDev.parse(bad, "", "x"))
        assertNull(RemoteDev.parse("ana@h", "../etc", "x")); assertNull(RemoteDev.parse("ana@h", "/srv/\$(id)", "x"))
    }

    @Test fun commandsRunInTheFolderWithALoginShell() {
        val c = RemoteDev.parse("ana@h", "~/pocketide/my game", "x")!!
        assertEquals("~/'pocketide/my game'", RemoteDev.shellDir(c.dir))
        val w = RemoteDev.wrap(c, "npm test && echo 'ok'")
        assertEquals("mkdir -p ~/'pocketide/my game' && cd ~/'pocketide/my game' && touch .pocketide-mark && bash -lc 'npm test && echo '\\''ok'\\'''", w)
        assertTrue(RemoteDev.changedSince(c).contains("-newer .pocketide-mark") && RemoteDev.changedSince(c).contains("-not -path './node_modules/*'"))
        assertEquals(listOf("src/a.js", "dist2/x"), RemoteDev.changedPaths("./src/a.js\n./node_modules/x/y.js\n\n./dist2/x\n/etc/passwd\n./../up"))
    }

    @Test fun syncSendsOnlyWhatChanged() {
        val synced = mapOf("a.js" to "1", "b.js" to "2", "gone.js" to "3")
        val local = mapOf("a.js" to "1", "b.js" to "9", "new.js" to "4", "node_modules/x.js" to "5")
        val p = RemoteDev.plan(local, synced)
        assertEquals(listOf("b.js", "new.js"), p.upload); assertEquals(listOf("gone.js"), p.delete)
        val m = RemoteDev.manifest(mapOf("b" to "2", "a dir/x y.js" to "1"))
        assertEquals(mapOf("b" to "2", "a dir/x y.js" to "1"), RemoteDev.manifestParse(m))
        assertEquals(40, RemoteDev.hash("x".toByteArray()).length)
    }
}
