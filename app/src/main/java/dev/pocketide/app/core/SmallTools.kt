package dev.pocketide.app.core

/**
 * A small tool set for a model that is struggling (1.5.170, HANDOFF-FABLE-TRUST-RESPONSE change 4). The full set is about twenty tools
 * with five ways to edit; the 1.5.91 analysis of Llama 3.3 found edits before reading, invalid line numbers and failed checks. After
 * [LIMIT] calls in one request that could not run or did not apply, the rest of the request offers only reading, one edit tool,
 * writing a new file, running a command and the plan. Pure logic; the loop sets Tools.only for the rest of the request.
 */
object SmallTools {
    const val LIMIT = 2
    private val BASE = setOf("read_file", "list_files", "search", "edit_file", "write_file", "run_command", "update_plan")

    fun set(look: Boolean, projects: Boolean): Set<String> = BASE + (if (look) setOf("look_at_preview") else emptySet()) + (if (projects) setOf(ProjectsTool.NAME) else emptySet())

    /** A tool result that says an edit did not apply (calls that could not run at all are counted by the loop as bad calls). */
    fun slipped(result: String): Boolean = result.startsWith("Edit not applied") || result.startsWith("Patch not applied")

    fun due(slips: Int): Boolean = slips >= LIMIT

    const val NOTE = "From now on you have a few plain tools: read_file, list_files, search, edit_file (search must be copied exactly from " +
        "read_file's output, without the line numbers), write_file (new files, or a whole file you have just read), run_command, update_plan. " +
        "Read a file before you edit it. Make one change at a time and check it."
}
