package dev.pocketide.app

import dev.pocketide.app.core.Autocomplete
import dev.pocketide.app.core.Brackets
import dev.pocketide.app.core.ProjectNames
import dev.pocketide.app.core.ProjectSearch
import dev.pocketide.app.core.SearchOptions
import dev.pocketide.app.core.Templates
import org.junit.Test

class WorkspaceTest {
    @Test fun all() { WorkspaceSelfTest.runAll(); check(WorkspaceSelfTest.failed == 0) { "${WorkspaceSelfTest.failed} workspace checks failed" } }
}

object WorkspaceSelfTest {
    var failed = 0
    private var passed = 0
    private fun ok(c: Boolean, name: String) { if (c) passed++ else { failed++; println("FAIL  $name") } }
    private fun <T> eq(a: T, b: T, name: String) { ok(a == b, name); if (a != b) println("      got: $a\n      want: $b") }

    fun runAll() {
        failed = 0; passed = 0
        names(); templates(); search(); replace(); autocomplete(); brackets()
        println("\n$passed passed, $failed failed")
    }

    private fun names() {
        eq(ProjectNames.sanitize("  My   cool app  "), "My cool app", "names: spaces collapsed and trimmed")
        eq(ProjectNames.sanitize("a/b\\c:d*e?f\"g<h>i|j"), "a-b-c-d-e-f-g-h-i-j", "names: path and shell characters replaced")
        eq(ProjectNames.sanitize("..hidden"), "hidden", "names: leading dots removed")
        eq(ProjectNames.sanitize("..."), null, "names: only dots is not a name")
        eq(ProjectNames.sanitize("   "), null, "names: blank is not a name")
        eq(ProjectNames.sanitize("x".repeat(80))?.length, ProjectNames.MAX, "names: cut to the maximum length")
        eq(ProjectNames.sanitize("tab\there"), "tab-here", "names: control characters replaced")
        eq(ProjectNames.unique("App", listOf("Other")), "App", "unique: free name kept")
        eq(ProjectNames.unique("App", listOf("app", "App 2")), "App 3", "unique: case-insensitive clash gets a number")
        eq(ProjectNames.copyName("Site", listOf("Site", "Site copy")), "Site copy 2", "copy name")
        eq(ProjectNames.fromRepo("Test-Coder2.2", emptyList()), "Test-Coder2.2", "name from a repository")
        eq(ProjectNames.fromRepo("...", emptyList()), ProjectNames.DEFAULT, "unusable repository name falls back")
        val long = ProjectNames.unique("y".repeat(ProjectNames.MAX), listOf("y".repeat(ProjectNames.MAX)))
        ok(long.length <= ProjectNames.MAX && long.endsWith(" 2"), "unique: numbered name stays within the maximum")
    }

    private fun templates() {
        ok(Templates.all.map { it.id }.toSet().size == Templates.all.size, "templates: ids unique")
        ok(Templates.all.all { t -> t.open == null || t.files.containsKey(t.open) }, "templates: the file to open exists")
        eq(Templates.byId("nope"), Templates.EMPTY, "templates: unknown id is Empty")
        eq(Templates.slug("My Cool App!"), "my-cool-app", "slug")
        eq(Templates.slug("!!!"), "app", "slug fallback")
        val node = Templates.render(Templates.NODE, "My App")
        ok(node.getValue("package.json").contains("\"name\": \"my-app\""), "node: package name is the slug")
        ok(node.getValue("index.js").contains("greet(\"My App\")") && node.getValue("index.js").contains("\${name}"), "node: project name inserted, JS template literal kept")
        val go = Templates.render(Templates.GO, "Go Demo")
        ok(go.getValue("go.mod").startsWith("module go-demo"), "go: module path is the slug")
        ok(go.getValue("main.go").contains("package main") && go.getValue("main.go").contains("\tfmt.Println"), "go: tabs kept")
        ok(Templates.render(Templates.PYTHON, "Say \"hi\"").getValue("main.py").contains("greet(\"Say 'hi'\")"), "python: quotes in the name cannot break the string")
        ok(Templates.all.none { t -> Templates.render(t, "X").values.any { it.contains("{{") } }, "templates: no placeholders left")
        ok(Templates.EMPTY.files.isEmpty(), "empty template has no files")
    }

    private val files = mapOf(
        "src/app.js" to "const total = 1;\nfunction addTotal(x) {\n  return total + x; // Total\n}\n",
        "README.md" to "Total of things\n",
        "b.txt" to "nothing here\n"
    )

    private fun search() {
        var r = ProjectSearch.search(files, SearchOptions("total"))
        eq(r.hits.size, 5, "search: case-insensitive finds every total")
        eq(r.hits.map { it.path }.distinct(), listOf("README.md", "src/app.js"), "search: files in path order")
        eq(r.fileCount, 2, "search: file count")
        val first = r.hits.first { it.path == "src/app.js" }
        eq(first.line to first.col, 1 to 7, "search: line and column are 1-based")
        val third = r.hits.filter { it.path == "src/app.js" }[2]
        eq(third.line, 3, "search: line numbers advance")
        ok(third.preview.startsWith("return total") && third.preview.substring(third.previewStart, third.previewStart + third.length).equals("total", true), "search: preview trimmed and match located")
        r = ProjectSearch.search(files, SearchOptions("Total", caseSensitive = true))
        eq(r.hits.size, 3, "search: case-sensitive (Total in addTotal, the comment and README)")
        r = ProjectSearch.search(files, SearchOptions("total", wholeWord = true))
        eq(r.hits.size, 4, "search: whole word skips addTotal")
        r = ProjectSearch.search(files, SearchOptions("add\\w+\\(", regex = true))
        eq(r.hits.single().length, 9, "search: regex match length")
        r = ProjectSearch.search(files, SearchOptions("(", regex = true))
        ok(r.error != null && r.hits.isEmpty(), "search: invalid regex reports an error")
        r = ProjectSearch.search(files, SearchOptions("x*", regex = true))
        ok(r.error != null, "search: a pattern matching empty text is refused")
        r = ProjectSearch.search(files, SearchOptions("(x)", regex = false))
        eq(r.hits.map { it.line }, listOf(2), "search: plain mode treats regex characters literally")
        eq(ProjectSearch.search(files, SearchOptions("t.tal", regex = false)).hits.size, 0, "search: a dot is not a wildcard in plain mode")
        eq(ProjectSearch.search(files, SearchOptions("")).hits.size, 0, "search: empty query finds nothing")
        val many = mapOf("big.txt" to "a\n".repeat(ProjectSearch.MAX_HITS + 50))
        r = ProjectSearch.search(many, SearchOptions("a"))
        ok(r.truncated && r.hits.size == ProjectSearch.MAX_HITS, "search: capped and marked truncated")
        val longLine = "x".repeat(300) + "needle" + "y".repeat(300)
        r = ProjectSearch.search(mapOf("l.txt" to longLine), SearchOptions("needle"))
        val h = r.hits.single()
        ok(h.preview.length <= 125 && h.preview.substring(h.previewStart, h.previewStart + 6) == "needle", "search: long lines shortened around the match")
        ok(ProjectSearch.eligible(10, false) && !ProjectSearch.eligible(10, true) && !ProjectSearch.eligible(2_000_000, false), "search: binary and huge files skipped")
    }

    private fun replace() {
        var (t, n) = ProjectSearch.replace("a.b a.b", SearchOptions("a.b"), "\$1")
        eq(t to n, "\$1 \$1" to 2, "replace: plain mode is literal (dot and dollar)")
        val r2 = ProjectSearch.replace("john smith", SearchOptions("(\\w+) (\\w+)", regex = true), "\$2, \$1")
        eq(r2, "smith, john" to 1, "replace: regex groups")
        val r3 = ProjectSearch.replace("cost 5", SearchOptions("(\\d)", regex = true), "\\$\$1")
        eq(r3.first, "cost \$5", "replace: escaped dollar")
        val plan = ProjectSearch.replaceInProject(files, SearchOptions("total", wholeWord = true), "sum")
        eq(plan.keys.toList(), listOf("README.md", "src/app.js"), "replace in project: only changed files")
        ok(plan.getValue("src/app.js").first.contains("addTotal") && plan.getValue("src/app.js").second == 3, "replace in project: counts and keeps non-matches")
        t = ProjectSearch.replace("Total", SearchOptions("total", caseSensitive = true), "x").first
        eq(t, "Total", "replace: respects case sensitivity")
    }

    private fun autocomplete() {
        val text = "function add(a, b) {\n  return \n}\n"
        val cur = text.indexOf("return ") + 7
        val ctx = Autocomplete.around(text, cur)
        eq(ctx.before.substringAfterLast('\n'), "  return ", "around: before ends at the cursor")
        eq(ctx.after, "\n}\n", "around: after starts at the cursor")
        ok(Autocomplete.userMessage("a.js", ctx).contains("return ${Autocomplete.CURSOR}\n}"), "prompt marks the cursor")
        eq(Autocomplete.clean("a + b;", ctx), "a + b;", "clean: plain insertion kept")
        eq(Autocomplete.clean("```js\na + b;\n```", ctx), "a + b;", "clean: fences stripped")
        eq(Autocomplete.clean("  return a + b;", ctx), "a + b;", "clean: echoed line prefix removed")
        eq(Autocomplete.clean("a + b;\n}", ctx), "a + b;", "clean: echoed text after the cursor removed")
        eq(Autocomplete.clean("<think>hmm</think>a + b;", ctx), "a + b;", "clean: thinking removed")
        eq(Autocomplete.clean("Sorry, I can't help with that.", ctx), "", "clean: apology is not code")
        eq(Autocomplete.clean("   \n  ", ctx), "", "clean: blank is nothing")
        val long = (1..40).joinToString("\n") { "line$it" }
        eq(Autocomplete.clean(long, ctx).lines().size, Autocomplete.MAX_LINES, "clean: limited to a few lines")
        val big = "x".repeat(Autocomplete.SIDE * 3)
        val c2 = Autocomplete.around(big, big.length / 2)
        ok(c2.before.length <= Autocomplete.SIDE && c2.after.length <= Autocomplete.SIDE, "around: trimmed to the side limit")
        val c3 = Autocomplete.around("abc", 99)
        eq(c3.before to c3.after, "abc" to "", "around: cursor clamped")
    }

    private fun brackets() {
        val s = "f(a[1], {b: (2)})"
        eq(Brackets.match(s, 2), 1 to 16, "brackets: after an opening bracket")
        eq(Brackets.match(s, 17), 16 to 1, "brackets: after a closing bracket")
        eq(Brackets.match(s, 4), 3 to 5, "brackets: square brackets")
        eq(Brackets.match("abc", 1), null, "brackets: no bracket near the cursor")
        eq(Brackets.match("(((", 1), null, "brackets: unbalanced has no partner")
        eq(Brackets.match("", 0), null, "brackets: empty text")
        eq(Brackets.lineOf("a\nb\nc", 4), 3, "lineOf")
        eq(Brackets.offsetOfLine("a\nbb\nc", 3), 5, "offsetOfLine")
        eq(Brackets.offsetOfLine("a\nb", 99), 3, "offsetOfLine clamps past the end")
        eq(Brackets.offsetOfLine("a\nb", 0), 0, "offsetOfLine clamps before the start")
    }
}
