package dev.pocketide.app

import dev.pocketide.app.core.Share
import org.junit.Test

class ShareTest {
    @Test fun share() {
        val f = ArrayList<String>()
        fun check(ok: Boolean, m: String) { if (!ok) f.add(m) }
        val files = mapOf("index.html" to "<html></html>", "src/app.js" to "let a = 1", ".pocketide/notes.md" to "x", "node_modules/x/i.js" to "y", "big.txt" to "z".repeat(300_000), "logo.png" to "")
        val snap = Share.snapshot(files.keys.toList()) { files[it]?.takeIf { t -> t != "" } }
        check(snap.files.keys.toList() == listOf("index.html", "src__app.js"), "kept " + snap.files.keys)
        check(snap.skipped == 2, "skipped ${snap.skipped}")
        val p = Share.payload("PocketIDE: demo", snap.files)
        check(p.contains("\"public\":false") && p.contains("src__app.js"), "payload")
        check(Share.parseUrl("{\"html_url\":\"https://gist.github.com/x/1\"}") == "https://gist.github.com/x/1", "url")
        check(Share.parseUrl("nope") == null, "bad url")
        check(Share.error(404).contains("gist"), "error")
        if (f.isNotEmpty()) throw AssertionError(f.joinToString("; "))
    }
}
