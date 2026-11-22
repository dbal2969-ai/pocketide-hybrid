package dev.pocketide.app.core

/**
 * The repair turn (1.5.164; the research handoff's first item, from Aider's benchmark): after a failed check, the next try carries on the
 * same conversation with the whole test output, is told the tests are correct and out of reach, may only read and edit, and must edit.
 * In the polyglot benchmark the second try used to go looking for the hidden test file and run commands it could not use; in rest-api
 * it changed nothing at all.
 */
object RepairTurn {
    /** The tools a repair turn offers: reading and editing the project's own files, nothing that runs or searches the web. */
    val TOOLS: Set<String> = setOf("read_file", "list_files", "search", "edit_file", "multi_edit", "edit_lines", "write_file", "apply_patch")

    /** Aider's wording (benchmark/prompts.py test_failures), plus the two lines our tool-using model needed. */
    fun message(files: Collection<String>, testOutput: String): String =
        testOutput.trim() + "\n\n####\n\nSee the testing errors above.\nThe tests are correct, don't try and change them.\n" +
            "The test file is not in the project and you cannot run the tests: do not look for it or try to run anything. The output above is " +
            "all there is.\nFix the code in " + files.joinToString(" ") + " to resolve the errors."

    const val MUST_EDIT = "You have not changed any file. The tests are correct and you cannot see or run them; the errors shown are all there is. " +
        "Make your best fix to the code now, from those errors, then finish."
}
