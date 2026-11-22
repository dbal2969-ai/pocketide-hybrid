package dev.pocketide.app.core

/**
 * Whether look_at_preview is offered for a request (1.5.129).
 *
 * It used to be offered only when the project already had an .html page when the request started. A new project ("make a snake
 * game in one HTML file") has none at that moment, so the assistant could not look at the page it was about to write: an
 * emulator test (EMULATOR-TEST.md, session 2) saw it try "python3 -m http.server" instead and wait 50 minutes on that command,
 * and the app reported "No page to check" even though index.html was there by then. Now it is also offered when the project is
 * still empty or the request is about a page, and the page is looked up when the tool is used, not when the request starts.
 */
object LookOffer {
    /** The look_at_preview result when there is no page: nothing was looked at, so it is not recorded as a check. */
    const val NO_PAGE = "There is no page to look at in this project."

    private val WEB = Regex("""(?i)\b(html|web\s*(page|site|app)?|website|page|browser|canvas|css|javascript|js|game|landing)\b""")

    fun offer(hasPage: Boolean, projectEmpty: Boolean, request: String): Boolean =
        hasPage || projectEmpty || WEB.containsMatchIn(request)
}
