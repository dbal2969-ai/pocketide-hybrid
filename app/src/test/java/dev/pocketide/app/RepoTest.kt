package dev.pocketide.app

import dev.pocketide.app.core.Crumb
import dev.pocketide.app.core.Markdown
import dev.pocketide.app.core.MdKind
import dev.pocketide.app.core.RepoBrowser
import dev.pocketide.app.core.RepoCard
import dev.pocketide.app.core.RepoList
import org.junit.Test

class RepoTest {
    @Test
    fun repoChecks() {
        RepoSelfTest.runAll()
    }
}

/** Checks for the GitHub-style repo page logic: folder listing, breadcrumbs, README lookup, times, Markdown and the repositories list. */
object RepoSelfTest {
    private var fails = 0
    private var passes = 0

    private fun ok(c: Boolean, m: String) {
        if (c) passes++ else fails++
        println((if (c) "PASS  " else "FAIL  ") + m)
    }

    private fun eq(a: Any?, b: Any?, m: String) = ok(a == b, if (a == b) m else "$m  got <$a> expected <$b>")

    fun runAll() {
        listing(); crumbs(); readme(); times(); markdown(); describe(); repoList(); noise()
        println("\n$passes passed, $fails failed")
        check(fails == 0) { "$fails repo checks failed" }
    }

    private val files = listOf(
        "README.md", "index.html", "style.css", "Zebra.txt", ".hidden", "app/main.py", "app/util/helpers.py", "app/README.md",
        "docs/guide.md", "assets/img/logo.png", ".github/workflows/build.yml"
    )
    private val dirs = listOf("app", "app/util", "docs", "assets", "assets/img", "empty", "empty/inner", ".github")
    private val times = mapOf("index.html" to 5000L, "app/util/helpers.py" to 9000L, "app/main.py" to 7000L, "docs/guide.md" to 100L)
    private fun info(p: String): Pair<Long, Long> = (p.length.toLong()) to (times[p] ?: 1L)

    private fun listing() {
        val root = RepoBrowser.entries(files, dirs, "", ::info)
        eq(root.map { it.name }, listOf("app", "assets", "docs", "empty", "index.html", "README.md", "style.css", "Zebra.txt"), "root: folders first, then files, each A to Z (case-insensitive)")
        ok(root.none { it.name.startsWith(".") }, "root: hidden files and folders are left out")
        eq(root.first { it.name == "app" }.modified, 9000L, "folder time is the newest change inside it, however deep")
        eq(root.first { it.name == "empty" }.modified, 0L, "an empty folder is listed with an unknown time")
        eq(root.first { it.name == "index.html" }.size, "index.html".length.toLong(), "file size comes from the info lookup")
        eq(root.first { it.name == "app" }.path, "app", "folder path")
        val app = RepoBrowser.entries(files, dirs, "app", ::info)
        eq(app.map { it.name }, listOf("util", "main.py", "README.md"), "inside app: the sub-folder, then its files")
        eq(app.first { it.name == "util" }.path, "app/util", "sub-folder path includes the parent")
        eq(RepoBrowser.entries(files, dirs, "app/util", ::info).map { it.path }, listOf("app/util/helpers.py"), "two levels down")
        eq(RepoBrowser.entries(files, dirs, "app/", ::info).map { it.name }, app.map { it.name }, "a trailing slash on the folder is fine")
        eq(RepoBrowser.entries(files, dirs, "empty", ::info).map { it.name }, listOf("inner"), "an empty folder shows its empty sub-folder")
        eq(RepoBrowser.entries(files, dirs, "nope", ::info), emptyList<Any>(), "an unknown folder is empty")
        eq(RepoBrowser.entries(emptyList(), emptyList(), "", ::info), emptyList<Any>(), "empty project")
        eq(RepoBrowser.entries(listOf("apple.txt", "app/x.txt"), emptyList(), "app", ::info).map { it.name }, listOf("x.txt"), "a folder named app does not pick up apple.txt")
    }

    private fun crumbs() {
        eq(RepoBrowser.crumbs("demo", ""), listOf(Crumb("demo", "")), "root path is just the project")
        eq(RepoBrowser.crumbs("demo", "app/util"), listOf(Crumb("demo", ""), Crumb("app", "app"), Crumb("util", "app/util")), "path steps carry their full paths")
        eq(RepoBrowser.crumbs("demo", "/app/"), listOf(Crumb("demo", ""), Crumb("app", "app")), "slashes are trimmed")
        eq(RepoBrowser.parent("app/util"), "app", "parent of a nested folder")
        eq(RepoBrowser.parent("app"), "", "parent of a top folder is the root")
        eq(RepoBrowser.parent(""), "", "parent of the root is the root")
    }

    private fun readme() {
        eq(RepoBrowser.findReadme(files, ""), "README.md", "finds the README at the root")
        eq(RepoBrowser.findReadme(files, "app"), "app/README.md", "finds a README inside a folder")
        eq(RepoBrowser.findReadme(files, "docs"), null, "none when the folder has no README")
        eq(RepoBrowser.findReadme(listOf("readme.txt", "Readme.MD"), ""), "Readme.MD", "case-insensitive, and .md wins over .txt")
        eq(RepoBrowser.findReadme(listOf("README"), ""), "README", "a README with no extension counts")
        eq(RepoBrowser.findReadme(listOf("README.md.bak", "readme.py", "notes/README.md"), ""), null, "other names and nested READMEs do not count")
        eq(RepoBrowser.findReadme(listOf("README", "README.rst"), ""), "README.rst", ".rst wins over no extension")
    }

    private fun times() {
        val now = 1_000_000_000_000L
        fun ago(sec: Long) = RepoBrowser.relativeTime(now, now - sec * 1000)
        eq(RepoBrowser.relativeTime(now, 0L), "", "unknown time is empty")
        eq(ago(5), "just now", "seconds ago")
        eq(ago(60), "1 minute ago", "one minute")
        eq(ago(5 * 60), "5 minutes ago", "minutes")
        eq(ago(3600), "1 hour ago", "one hour")
        eq(ago(3 * 3600), "3 hours ago", "hours")
        eq(ago(30 * 3600), "yesterday", "about a day")
        eq(ago(3 * 86400), "3 days ago", "days")
        eq(ago(14 * 86400), "2 weeks ago", "weeks")
        eq(ago(70 * 86400), "2 months ago", "months")
        eq(ago(400 * 86400), "1 year ago", "a year")
        eq(RepoBrowser.relativeTime(now, now + 5000), "just now", "a time slightly in the future is not negative")
        eq(RepoBrowser.formatSize(0), "0 B", "size: zero")
        eq(RepoBrowser.formatSize(1023), "1023 B", "size: bytes")
        eq(RepoBrowser.formatSize(1536), "1.5 KB", "size: kilobytes")
        eq(RepoBrowser.formatSize(5 * 1024 * 1024), "5.0 MB", "size: megabytes")
    }

    private fun markdown() {
        val md = "# Title\n\nSome **bold** text with `code` and a [link](http://x.y) and ![pic](a.png).\nSecond line of the same paragraph.\n\n" +
            "## List\n- one\n* two\n1. first\n2) second\n\n> quoted\n\n---\n\n```kotlin\nval a = 1\n\nval b = 2\n```\n\n#### Small heading\nDone"
        val b = Markdown.blocks(md)
        eq(b.map { it.kind }, listOf(MdKind.H1, MdKind.PARA, MdKind.H2, MdKind.BULLET, MdKind.BULLET, MdKind.NUMBERED, MdKind.NUMBERED, MdKind.QUOTE, MdKind.RULE, MdKind.CODE, MdKind.H3, MdKind.PARA), "block kinds in order")
        eq(b[0].text, "Title", "heading text")
        eq(b[1].text, "Some bold text with code and a link and pic. Second line of the same paragraph.", "inline marks removed, lines of a paragraph joined")
        eq(b[5].text, "1. first", "numbered item keeps its number")
        eq(b[6].text, "2. second", "numbered item with a bracket")
        eq(b[9].text, "val a = 1\n\nval b = 2", "code block keeps blank lines and line breaks")
        eq(b[10].kind, MdKind.H3, "a fourth-level heading is shown as the smallest")
        eq(Markdown.blocks(""), emptyList<Any>(), "empty text")
        eq(Markdown.blocks("```\nnever closed\nstill code").map { it.kind }, listOf(MdKind.CODE), "an unclosed code fence does not loop or crash")
        eq(Markdown.blocks("<p align=\"center\"><b>Hi</b></p>\n\nText").map { it.text }, listOf("Hi", "Text"), "html tags are removed")
        eq(Markdown.blocks("# ").size, 0, "an empty heading is dropped")
        eq(Markdown.blocks((1..500).joinToString("\n\n") { "p$it" }, max = 50).size, 50, "the block limit stops long files")
        eq(Markdown.inline("snake_case_name and 2 * 3"), "snake_case_name and 2 * 3", "single underscores and stars are left alone")
    }

    private fun describe() {
        eq(Markdown.describe("# Snake\n\nA small snake game for phones.\n\n## Run"), "A small snake game for phones.", "description is the first paragraph")
        eq(Markdown.describe("# T\n\n[![build](x.svg)](y)\n\nReal text here"), "Real text here", "badge lines are skipped")
        eq(Markdown.describe("# Only a heading"), "", "no paragraph, no description")
        eq(Markdown.describe(""), "", "empty README")
        val long = Markdown.describe("x".repeat(300))
        ok(long.length == 90 && long.endsWith("…"), "long descriptions are cut to 90 characters with an ellipsis")
    }

    private fun repoList() {
        val a = RepoCard("alpha", 3, 100, "", "", false)
        val b = RepoCard("Beta", 5, 900, "", "", true)
        val c = RepoCard("charlie", 1, 100, "", "", false)
        val d = RepoCard("Able", 2, 100, "", "", false)
        eq(RepoList.order(listOf(a, b, c, d)).map { it.name }, listOf("Beta", "Able", "alpha", "charlie"), "newest first, ties A to Z ignoring case")
        eq(RepoList.order(emptyList()), emptyList<Any>(), "no projects")
    }

    private fun noise() {
        fun d(n: String) = dev.pocketide.app.core.RepoEntry(n, n, true, 0, 0)
        fun f(n: String) = dev.pocketide.app.core.RepoEntry(n, n, false, 0, 0)
        ok(RepoBrowser.isNoise(d("__pycache__")) && RepoBrowser.isNoise(d("node_modules")) && RepoBrowser.isNoise(d("tmp")), "caches and scratch folders are hidden by default")
        ok(RepoBrowser.isNoise(d("Tmp")), "folder names match in any case")
        ok(!RepoBrowser.isNoise(d("src")) && !RepoBrowser.isNoise(d("Runescape 2009 Clone")), "real folders are never hidden")
        ok(!RepoBrowser.isNoise(f("tmp")), "a file named tmp is not hidden")
    }
}
