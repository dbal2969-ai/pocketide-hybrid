package dev.pocketide.app.core

/**
 * Agent SOPs: step-by-step procedures the assistant follows when asked with /sop <name> <task> (AWS's Strands "Agent SOPs"
 * are markdown procedures with MUST/SHOULD steps). A few are built in; a project adds its own as .pocketide/sops/<name>.sop.md,
 * and a project file with the same name replaces the built-in one.
 */
object Sops {
    const val DIR = ".pocketide/sops"
    const val SUFFIX = ".sop.md"

    data class Sop(val name: String, val title: String, val body: String, val builtIn: Boolean)

    val BUILT_IN: List<Sop> = listOf(
        Sop("debug", "Find and fix a bug", """
            1. You MUST reproduce or locate the failure first: read the error text, then read the code it points to. Do not guess.
            2. You MUST state the cause in one sentence before changing anything.
            3. You SHOULD make the smallest change that fixes the cause, not the symptom.
            4. You MUST check the fix (run the tests or the program, or look_at_preview for a page) and report what you saw.
            5. If the same fix fails twice, stop and explain what you learned instead of trying a third variation.
        """.trimIndent(), true),
        Sop("feature", "Add a feature", """
            1. You MUST read the files the feature touches and follow the project's existing style.
            2. You SHOULD write a short plan with update_plan, one item per file or behavior.
            3. Work on one item at a time; mark it done only after checking it works.
            4. You SHOULD add or update a test when the project has tests.
            5. Finish with what changed, what you checked, and anything left undone.
        """.trimIndent(), true),
        Sop("refactor", "Restructure code without changing behavior", """
            1. You MUST find the checks that prove behavior is unchanged (tests, a run, a preview) and run them first.
            2. Change one thing at a time and re-run the checks after each.
            3. You MUST NOT add features or fix unrelated bugs in the same pass; list them at the end instead.
            4. Finish by running the checks again and summarizing what moved.
        """.trimIndent(), true),
        Sop("game-qa", "Play-test a browser game", """
            1. You MUST open the game with look_at_preview and describe what is on screen.
            2. Use the steps option to press keys, tap, and wait, then look again; check that the HUD, player and enemies are visible and that input changes the state.
            3. Read window.render_game_to_text output when the game defines it, and compare it with what you see.
            4. For each problem found, name the file and cause, fix it, and look again.
            5. Finish with a list of what you checked and what still looks wrong.
        """.trimIndent(), true),
        Sop("review", "Review the project's recent changes", """
            1. Read the changed files before judging them.
            2. Report risks and likely bugs with file and line, missing tests, and one next step.
            3. You MUST NOT change files during a review.
        """.trimIndent(), true)
    )

    /** A project SOP from its file name and text; the first heading line is its title. */
    fun parse(path: String, text: String): Sop? {
        val file = path.substringAfterLast('/')
        if (!file.endsWith(SUFFIX)) return null
        val name = file.removeSuffix(SUFFIX).lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-')
        if (name.isEmpty() || text.isBlank()) return null
        val first = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val title = if (first.startsWith("#")) first.trimStart('#', ' ') else name
        val body = if (first.startsWith("#")) text.lines().drop(text.lines().indexOf(first) + 1).joinToString("\n").trim() else text.trim()
        return Sop(name, title.ifEmpty { name }, body.take(6000), false)
    }

    /** Built-ins with project SOPs layered on top (same name replaces). */
    fun all(project: List<Sop>): List<Sop> = (BUILT_IN.filter { b -> project.none { it.name == b.name } } + project).sortedBy { it.name }

    fun find(all: List<Sop>, name: String): Sop? = all.firstOrNull { it.name.equals(name.trim(), true) }

    fun listing(all: List<Sop>): String =
        "Procedures (use /sop <name> <task>):\n" + all.joinToString("\n") { "• ${it.name}: ${it.title}" + if (it.builtIn) "" else " (this project)" } +
            "\nAdd your own as $DIR/<name>$SUFFIX."

    fun prompt(sop: Sop, task: String): String =
        "Follow this procedure exactly. MUST means required; SHOULD means do it unless you have a stated reason not to.\n\n# ${sop.title}\n${sop.body}\n\n" +
            "Task: " + task.ifBlank { "Apply it to the current project and the file I have open." }
}
