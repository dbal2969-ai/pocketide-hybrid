package dev.pocketide.app.core

/**
 * A table of example requests with what the app should decide for each: how it routes them, whether a small
 * model is warned about, what it would run to verify, and which tools the model is offered. It runs on the
 * phone (Diagnostics) and in the unit tests, so a change in the rules cannot quietly make the assistant worse.
 */
object Eval {
    class Case(
        val request: String,
        val strong: Boolean,
        val category: Router.Category,
        val warnSmallModel: Boolean = false,
        val docAttached: Boolean = false
    )

    val CASES: List<Case> = listOf(
        Case("What does the loadUser function do?", false, Router.Category.QUESTION),
        Case("explain how promises work", false, Router.Category.QUESTION),
        Case("summarize what this project does", false, Router.Category.QUESTION),
        Case("fix the typo in the page title", false, Router.Category.EDIT),
        Case("rename the Save button to Submit", false, Router.Category.EDIT),
        Case("change the header colour to dark blue", false, Router.Category.EDIT),
        Case("build a todo app with login and a sqlite database", true, Router.Category.BUILD),
        Case("create a snake game with a score and sound", true, Router.Category.BUILD),
        Case("refactor the project so each screen is its own module", true, Router.Category.BUILD),
        Case("update app.js, index.html and style.css so the layout matches the design", true, Router.Category.BUILD),
        Case("Traceback (most recent call last):\n  File \"app.py\", line 12\nValueError: invalid literal", true, Router.Category.DEBUG),
        Case("the app crashes when I tap save, here is the log:\nFATAL EXCEPTION main\n at app.kt:44", true, Router.Category.DEBUG),
        Case("add a heading to Report.docx and make the total row bold", true, Router.Category.DOCUMENT, warnSmallModel = true),
        Case("replace every 2025 with 2026 in the spreadsheet", true, Router.Category.DOCUMENT, warnSmallModel = true),
        Case("what is in this file?", false, Router.Category.QUESTION, docAttached = false)
    )

    class Result(val name: String, val ok: Boolean, val detail: String)

    /** Checks the routing and warning rules against every case. */
    fun routing(): List<Result> = CASES.map { c ->
        val r = Router.route(c.request, c.docAttached || c.category == Router.Category.DOCUMENT, true)
        val warn = ModelSize.isWeakForFiles("allam-2-7b") && (ModelSize.isFileEdit(c.request) || c.docAttached)
        val okRoute = r.strong == c.strong
        val okCat = r.category == c.category
        val okWarn = !c.warnSmallModel || warn
        Result(
            "\"" + c.request.lines().first().take(46) + "\"",
            okRoute && okCat && okWarn,
            buildString {
                if (!okRoute) append("expected ${if (c.strong) "strong" else "cheap"}, got ${if (r.strong) "strong" else "cheap"} (${r.reason}). ")
                if (!okCat) append("expected ${c.category}, got ${r.category}. ")
                if (!okWarn) append("expected a small-model warning. ")
                if (okRoute && okCat && okWarn) append("ok")
            }
        )
    }

    /** Checks that each kind of project gets the right verify command. */
    fun verifying(): List<Result> {
        val cases = listOf(
            Triple("node project with tests", listOf("package.json", "a.js") to """{"scripts":{"test":"jest"}}""", "npm test"),
            Triple("node project without tests", listOf("package.json", "a.js") to """{"scripts":{}}""", "syntax checks"),
            Triple("python project with tests", listOf("app.py", "tests/test_app.py") to "", "Python tests"),
            Triple("go project with tests", listOf("go.mod", "main_test.go") to "", "go test"),
            Triple("plain web page", listOf("index.html", "style.css") to "", "")
        )
        return cases.map { (name, input, want) ->
            val got = Verify.plan(input.first, { input.second.ifEmpty { null } })?.label ?: ""
            Result(name, got == want, if (got == want) "ok" else "expected ${want.ifEmpty { "nothing" }}, got ${got.ifEmpty { "nothing" }}")
        }
    }

    /** Checks the tools the model is offered. */
    fun agentRules(): List<Result> {
        val out = ArrayList<Result>()
        val names = Tools.NAMES
        for (n in listOf("read_file", "write_file", "edit_file", "search", "find_symbol", "file_outline", "run_command", "update_plan"))
            out.add(Result("tool $n offered", n in names, if (n in names) "ok" else "missing"))
        return out
    }

    private class ContextTask(val request: String, val path: String, val source: String)

    private val CONTEXT_TASKS = listOf(
        ContextTask("retry backoff jitter", "src/net/client.js", "export function retryWithBackoffAndJitter() {}"),
        ContextTask("jwt expiration claim", "src/auth/tokens.js", "export function readJwtExpirationClaim() {}"),
        ContextTask("stale cache eviction", "src/store/cache.js", "export function evictStaleCacheEntries() {}"),
        ContextTask("normalize empty payload", "src/api/response.js", "export function normalizeEmptyPayload() {}"),
        ContextTask("quoted comma fields", "src/data/csv.js", "export function parseQuotedCommaFields() {}"),
        ContextTask("keyboard focus outline", "src/ui/menu.css", ".menu-item:focus-visible { outline: 2px solid currentColor; } /* keyboard focus */"),
        ContextTask("daylight saving offset", "src/time/clock.js", "export function daylightSavingOffset() {}"),
        ContextTask("idempotent schema migration", "src/db/migrations.js", "export function runIdempotentSchemaMigration() {}"),
        ContextTask("trim lowercase email", "src/forms/email.js", "export function trimAndLowercaseEmail() {}"),
        ContextTask("preserve image aspect ratio", "src/images/resize.js", "export function preserveImageAspectRatio() {}")
    )

    /** Checks whether task wording can retrieve relevant code even when filenames do not echo the request. */
    fun contextTasks(): List<Result> = CONTEXT_TASKS.map { task ->
        val files = listOf(
            ProjectFile(task.path, task.source, false),
            ProjectFile("src/misc/unrelated.js", "export function unrelatedSetup() { return true; }", false)
        )
        val context = ContextRanker.build(files, null, task.request, 8000, everything = false)
        val found = context.contains("--- ${task.path} ---")
        Result(task.request, found, if (found) "ok" else "relevant file was not included in context")
    }

    fun all(): List<Result> = routing() + verifying() + agentRules() + contextTasks()

    fun format(rs: List<Result>): String {
        val bad = rs.count { !it.ok }
        return "${rs.size - bad} of ${rs.size} checks passed\n" + rs.joinToString("\n") { (if (it.ok) "✓ " else "✗ ") + it.name + (if (it.ok) "" else " — " + it.detail) }
    }
}
