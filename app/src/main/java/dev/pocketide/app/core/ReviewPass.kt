package dev.pocketide.app.core

/**
 * A second opinion when the assistant says it is done: a read-only reviewer on a different model reads the changed files
 * and checks them against the request (Emergent runs a separate testing agent; Copilot offers a reviewer and a "second
 * opinion"). A reviewer that did not write the code does not explain away its mistakes. It runs only when a second model is
 * set (the cheap draft model), at most once per request, and its findings go back to the assistant once.
 */
object ReviewPass {
    const val PROMPT = "You are a careful code reviewer helping another assistant. You did not write these changes. Read the changed files with read_file " +
        "(use start and end for long files) and check them against the person's request. Look for: requested things that are missing or only half done, " +
        "code that cannot run (syntax errors, missing files or functions, wrong names), obvious bugs, and secrets or passwords written into code. " +
        "Go through the request item by item: every place, creature, feature, screen or rule it names must exist in the code and be reachable by the player or user, not just mentioned in a plan or a comment. " +
        "When a checklist (PLAN.md) and play tests (tests/play.txt) are given, every requested item needs a checklist line and a test that really checks it (expect or until); a missing item or a test that cannot fail is a problem. " +
        "Do not change files or run commands. Ignore style. Reply with the first line exactly VERDICT: OK or VERDICT: FIX. " +
        "After VERDICT: FIX, list at most five concrete problems, each with the file and line and what to change. Keep it under 200 words."

    fun question(request: String, changed: Collection<String>, plan: String? = null, tests: String? = null): String =
        "The person asked: \"" + request.take(1500) + "\"\nThe assistant changed these files: " + changed.take(20).joinToString(", ") + "." +
            (if (plan != null) "\nIts checklist (PLAN.md):\n" + plan.take(2500) else "") +
            (if (tests != null) "\nIts saved play tests (tests/play.txt), by name: " + PlayTests.parse(tests).joinToString("; ") { it.name }.take(800) else "") +
            "\nReview the changes against the request and reply as instructed."

    /** True when the reviewer asked for fixes. A reply without a verdict line counts as OK, so a confused reviewer cannot start a loop. */
    fun wantsFix(reply: String): Boolean = Regex("""(?im)^\s*\**\s*VERDICT\s*:\s*FIX""").containsMatchIn(reply)

    /** The reviewer's findings without the verdict line. */
    fun findings(reply: String): String = reply.lines().filterNot { Regex("""(?i)^\s*\**\s*VERDICT\s*:""").containsMatchIn(it) }.joinToString("\n").trim().take(2500)

    fun request(reply: String): String =
        "A reviewer on another model read your changes and found these problems:\n" + findings(reply) +
            "\nFix the ones that are real. If one is wrong, say briefly why. Then check the result and finish."

    /**
     * The automatic look at the finished page (Replit's App Testing and Emergent's browser tests): the page as it is now,
     * with a screenshot when the model can see images, checked against the request before the assistant finishes.
     */
    fun acceptance(request: String, report: String, withScreenshot: Boolean): String =
        "Before you finish, here is the page as it is now" + (if (withScreenshot) " (the screenshot is attached)" else "") + ":\n" + report.take(3000) +
            "\nCheck it against the request: \"" + request.take(800) + "\". If anything requested is missing, broken or not visible on screen, fix it and look again with look_at_preview. " +
            "If everything is there and works, give your final summary without calling more tools."
}
