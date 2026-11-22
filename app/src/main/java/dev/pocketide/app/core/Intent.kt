package dev.pocketide.app.core

/**
 * What kind of request a message is, decided once (1.5.87). Before, three separate phrase checks (Habits.isQuestion,
 * Scope.reportOnly, Requirements.asksForResearch) were each called where they were needed, with their own order, and a request
 * misread by one of them silently ran with different tools. Now one decision, in one order, is made when the message is sent,
 * shown under the message ([Kind.label]), and can be overridden with a tap ("Run it as a task").
 */
object Intent {
    enum class Kind(val label: String) {
        /** Answer it: read and look, change nothing. */
        QUESTION("Taken as a question: it reads and answers, and changes nothing."),
        /** Run or check something and report: commands and page looks run, no file is changed. */
        REPORT("Taken as run-and-report: it runs and checks, and changes no files."),
        /** Find things out: not pushed to edit while it researches. */
        RESEARCH("Taken as research: it looks things up and reports, and is not pushed to change files."),
        /** Do the work. */
        TASK(""),
    }

    /** The kind of [text], in this order: a question, then run-and-report, then research, else a task. */
    fun of(text: String): Kind = when {
        Habits.isQuestion(text) -> Kind.QUESTION
        Scope.reportOnly(text) -> Kind.REPORT
        Requirements.asksForResearch(text) -> Kind.RESEARCH
        else -> Kind.TASK
    }

    const val RUN_AS_TASK = "Run it as a task instead"
}
