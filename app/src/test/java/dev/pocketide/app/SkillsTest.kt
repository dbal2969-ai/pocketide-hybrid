package dev.pocketide.app

import dev.pocketide.app.core.FilesUsed
import dev.pocketide.app.core.Skills
import dev.pocketide.app.core.Slash
import org.junit.Test

class SkillsTest {
    @Test
    fun skillsChecks() {
        SkillsSelfTest.runAll()
    }
}

object SkillsSelfTest {
    private var fails = 0
    private var passes = 0

    private fun ok(c: Boolean, m: String) {
        if (c) passes++ else fails++
        println((if (c) "PASS  " else "FAIL  ") + m)
    }

    fun runAll() {
        parsing(); matching(); files(); slash()
        println("\n$passes passed, $fails failed")
        check(fails == 0) { "$fails skills checks failed" }
    }

    private val page = Skills.parse(".pocketide/skills/add-a-page.md", "---\nname: Add a page\ndescription: how new pages are added to this site\nkeywords: page, route, navigation menu\n---\nCreate pages/<name>.html and add a link in nav.html.")
    private val rel = Skills.parse(".pocketide/skills/release_notes.md", "Write release notes in CHANGELOG.md, newest first.")

    private fun parsing() {
        ok(page.name == "Add a page" && page.description.startsWith("how new pages") && "page" in page.keywords && "navigation menu" in page.keywords, "reads the header")
        ok(page.body.startsWith("Create pages/"), "the header is not part of the body")
        ok(rel.name == "release notes" && "release" in rel.keywords && rel.body.contains("CHANGELOG.md"), "without a header the name comes from the file name")
    }

    private fun matching() {
        val all = listOf(page, rel)
        ok(Skills.match(all, "add a new page for the contact form").firstOrNull() == page, "a request about pages picks the page skill")
        ok(Skills.match(all, "put the release notes in the changelog").firstOrNull() == rel, "a request about releases picks the release skill")
        ok(Skills.match(all, "make the button blue").isEmpty(), "an unrelated request matches nothing")
        ok(Skills.match(all, "update the navigation menu with a new page and write release notes", 1).size == 1, "the limit is respected")
        val n = Skills.note(listOf(page))
        ok(n.contains("PROJECT SKILLS") && n.contains("### Add a page") && n.contains("nav.html"), "the note carries the skill text")
        ok(Skills.note(emptyList()).isEmpty(), "no match, no note")
        val big = Skills.parse("a.md", "x".repeat(20000))
        ok(Skills.note(listOf(big, big)).length < Skills.BUDGET + 400, "the note stays within the budget")
    }

    private fun files() {
        val labels = listOf("Read app.js", "Read style.css (unchanged)", "Read app.js", "Wrote app.js", "Ran ls", "Read a/b.py")
        ok(FilesUsed.paths(labels) == listOf("app.js", "style.css", "a/b.py"), "distinct paths, in order, without the suffix")
        ok(FilesUsed.line(labels) == "Read 3 files: app.js, style.css, a/b.py", "the line names the files")
        ok(FilesUsed.line(listOf("Wrote x")) == null, "nothing read, no line")
        ok(FilesUsed.line((1..8).map { "Read f$it" })!!.endsWith("and 3 more"), "long lists are cut")
        ok(FilesUsed.line(listOf("Read x"))!!.startsWith("Read 1 file:"), "singular")
    }

    private fun slash() {
        ok(Slash.parse("/review the login code")?.name == "review" && Slash.parse("/skills")?.name == "skills", "the new commands parse")
        val r = Slash.reviewPrompt("M app.js", "security")
        ok(r.contains("M app.js") && r.contains("Do not change any files") && r.contains("Focus on: security"), "the review prompt carries the changes and the focus")
        ok(Slash.reviewPrompt("", "").contains("No change list"), "a review without a change list says so")
        ok(Skills.listing(emptyList()).contains(".pocketide/skills") && Skills.listing(listOf(page)).contains("Add a page"), "the listing explains how to add skills")
    }
}
