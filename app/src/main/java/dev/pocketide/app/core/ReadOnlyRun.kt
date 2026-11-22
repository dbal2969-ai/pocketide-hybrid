package dev.pocketide.app.core

/**
 * What a read-only run (a review, Ask mode, the Chat home, a read-only profile) says when the model tries to change something
 * (1.5.127). Before, the card took the change's own label and added "(read-only)", so a refused write read "Wrote index.html
 * (read-only)" (found in the emulator test of the Chat home), and the reply called every read-only run "a review".
 */
object ReadOnlyRun {
    const val REFUSAL = "Refused: nothing can be changed in this conversation (read-only). Do not try other tools to change files or run " +
        "commands. Give the answer in your reply instead: what should change, with any code in the reply."

    /** The card: what was refused, never phrased as if it happened. */
    fun label(tool: String, target: String?): String {
        val what = when (tool) {
            "write_file" -> "write"
            "edit_file", "edit_lines", "multi_edit", "apply_patch", "doc_edit" -> "edit"
            "delete_file" -> "delete"
            "run_command" -> "run"
            "remember" -> "save a note"
            else -> tool.replace('_', ' ')
        }
        val t = target?.trim()?.lineSequence()?.firstOrNull()?.take(60).orEmpty()
        return "Not done (read-only): $what" + (if (t.isEmpty()) "" else " $t")
    }
}
