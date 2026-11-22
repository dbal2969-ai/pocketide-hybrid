package dev.pocketide.app.core

/**
 * Chooses agent mode for one request. Agent mode applies changes as they are made and runs checks after each one; review
 * mode leaves each change as a card to Apply or Reject. Questions and document edits stay in review; building or fixing
 * something uses agent mode. A request that says not to change anything always stays in review. Autonomous rounds are never
 * chosen here: they run for a long time, so only the person turns them on.
 */
object AutoMode {
    class Choice(val agent: Boolean, val reason: String)

    private val NO_CHANGE = Regex("""\b(don'?t|do not|without|no need to)\s+(change|edit|modify|touch|update|alter)\b|\bjust (explain|tell|answer|show|describe)\b|\bonly (explain|tell|answer|describe)\b""", RegexOption.IGNORE_CASE)

    fun choose(text: String, route: Router.Route): Choice {
        if (NO_CHANGE.containsMatchIn(text)) return Choice(false, "you asked for no changes")
        return when (route.category) {
            Router.Category.QUESTION -> Choice(false, "a question, so nothing is changed")
            Router.Category.DOCUMENT -> Choice(false, "a document edit, so you review it before it is applied")
            Router.Category.BUILD -> Choice(true, "building something, so changes are applied and checked as they go")
            Router.Category.EDIT -> Choice(true, "${route.reason}, so changes are applied and checked as they go")
            Router.Category.DEBUG -> Choice(true, "fixing a problem, so the fix is applied and checked")
        }
    }
}
