package dev.pocketide.app.core

/** For bug-fix requests in a project that has tests: show the bug as a failing test before fixing it. */
object TestFirst {
    private val BUG = Regex("""\b(fix|bug|bugs|broken|crash(?:es|ing)?|doesn'?t work|does not work|not working|isn'?t working|wrong|regression|fails?|failing)\b""", RegexOption.IGNORE_CASE)
    private val NEW = Regex("""\b(create|build|make me|from scratch|new (?:app|game|project|website))\b""", RegexOption.IGNORE_CASE)

    /** True for a request to fix something that is wrong, as opposed to building something new. */
    fun isBugFix(request: String): Boolean = BUG.containsMatchIn(request) && !NEW.containsMatchIn(request)

    /** True when the project's check command runs real tests rather than only syntax checks. */
    fun hasTests(plan: Verify.Plan?): Boolean = plan != null && plan.label.contains("test", ignoreCase = true)

    fun wanted(request: String, plan: Verify.Plan?): Boolean = isBugFix(request) && hasTests(plan)

    /** Added to the system prompt for such a request. */
    const val NOTE = "\n\nTEST FIRST: this is a bug fix and the project has tests. Before you change the code, add or extend a small test that fails because of the bug, " +
        "run the tests and confirm it fails (show the failure), then fix the code and run the tests again until they pass. Keep the new test in the project."
}
