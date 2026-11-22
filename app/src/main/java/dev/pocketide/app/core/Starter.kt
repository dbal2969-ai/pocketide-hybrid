package dev.pocketide.app.core

/** First-run help: three sample requests that work well on a phone, and which setup steps are still open. */
object Starter {
    class Idea(val title: String, val prompt: String)

    val IDEAS = listOf(
        Idea("Snake game", "Make a snake game in one HTML file with arrow-key and swipe controls, a score, and a restart button."),
        Idea("To-do app", "Create a to-do list web app with add, complete and delete, saved in local storage, with a clean mobile layout."),
        Idea("Personal page", "Build a one-page personal website with a header, an about section, three project cards and a contact form, styled for phones.")
    )

    class Step(val text: String, val done: Boolean)

    /**
     * The provider a fresh install starts on (1.5.180): Kilo's free gateway, which needs no key, so the first request works with no
     * setup (PockoBots and OpenCode start the same way on free models). It was the demo, which only replays a canned answer.
     */
    const val FIRST_PROVIDER = "kilo"

    /** The setup checklist; the card is hidden once [used] is true (the AI has answered at least once). */
    fun steps(hasAi: Boolean, used: Boolean, keyless: Boolean = false): List<Step> = listOf(
        Step(if (hasAi && keyless) "Free AI ready: free models, no key needed" else "Connect an AI (a free key takes about two minutes)", hasAi),
        Step("Tap a sample below and approve the short list it shows", used),
        Step("Open Run to try what it built", used)
    )

    fun show(used: Boolean): Boolean = !used

    /** The line under the checklist while a no-key provider is in use: what it costs in privacy and how to change it. */
    fun keylessNote(provider: String, hasKey: Boolean): String? {
        val p = Providers.byId(provider)
        return if (p.keyless && !hasKey && p.kind != ProviderKind.DEMO && !LocalModels.isLocal(provider))
            "${p.name}: shared free models with usage limits. Prompts may be logged, so add your own key before working on private code." else null
    }

    /**
     * A real AI is set up: a key for the chosen provider, or a provider that needs none. Not the demo (1.5.127): a fresh install
     * starts on the keyless demo, so the checklist ticked "Connect an AI" and hid the Connect button before anything was connected
     * (found in the emulator test of the first run).
     */
    fun connected(provider: String, hasKey: Boolean): Boolean {
        val p = Providers.byId(provider)
        return p.kind != ProviderKind.DEMO && (hasKey || p.keyless)
    }
}
