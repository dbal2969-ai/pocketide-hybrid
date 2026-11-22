package dev.pocketide.app

import dev.pocketide.app.core.FolderScope
import org.junit.Test

class ShellFenceTest {
    @Test fun fence() {
        val f = ArrayList<String>()
        fun v(cmd: String) = FolderScope.commandViolation("game", cmd)
        fun check(ok: Boolean, m: String) { if (!ok) f.add(m) }
        check(v("npm install") == null, "plain command")
        check(FolderScope.commandViolation("", "rm -rf /") == null, "no scope")
        check(v("cd game && npm install && node index.js") == null, "cd inside")
        check(v("mkdir game/src && touch game/src/a.js") == null, "inside writes")
        check(v("touch notes.txt") == "notes.txt", "outside touch: " + v("touch notes.txt"))
        check(v("echo hi > readme.md") == "readme.md", "redirect outside")
        check(v("echo hi > game/readme.md") == null, "redirect inside")
        check(v("echo hi >> ../x.txt") != null, "redirect parent")
        check(v("cd game && echo hi > a.txt") == null, "redirect after cd")
        check(v("cd game && touch ../b.txt") != null, "parent after cd")
        check(v("cd .. && ls") != null, "cd out")
        check(v("rm -rf /home/x") != null, "absolute rm")
        check(v("sed -i s/a/b/ other/file.js") != null, "sed -i outside")
        check(v("sed s/a/b/ other/file.js") == null, "sed read")
        check(v("cat other/file.js | grep x > /dev/null") == null, "dev null")
        check(v("git checkout -- other.js") != null, "git checkout outside")
        check(v("git status") == null, "git status")
        check(v("python3 game/main.py 2>&1") == null, "stderr merge")
        check(v("echo 'a > b' ") == null, "quoted >")
        if (f.isNotEmpty()) throw AssertionError(f.joinToString("; "))
    }
}
