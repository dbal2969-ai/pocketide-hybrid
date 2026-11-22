package dev.pocketide.app.core

/**
 * The app's own verdict on a task run, shown above the model's words (1.5.170, HANDOFF-FABLE-TRUST-RESPONSE change 1).
 *
 * Before, the reply was the model's account ("All tests pass, the fix is done") with the app's record of checks as one line at the
 * bottom, and a run that changed nothing could still end as a normal finished answer. A weak model says "done" whatever happened, so
 * the verdict must come from what the app saw, not from what the model wrote. Three states:
 * - [Kind.CHECKED]: files changed and a check that runs the program passed after the last change, with nothing left open;
 * - [Kind.UNCHECKED]: files changed, nothing shows they work (nothing ran, or only syntax checks);
 * - [Kind.NOT_DONE]: nothing changed, the last check failed, the run was cut off, or something the app found is still open.
 * Pure logic.
 */
object Outcome {
    const val NOTHING = "no file was changed"

    enum class Kind(val ending: String) { CHECKED("finished (checked)"), UNCHECKED("finished (not checked)"), NOT_DONE("not finished") }

    class Verdict(val kind: Kind, val why: String) {
        /** The first line of the reply. */
        val header: String get() = when (kind) {
            Kind.CHECKED -> "✓ Done and checked: $why."
            Kind.UNCHECKED -> "⚠ Changed, not checked: $why."
            Kind.NOT_DONE -> if (why == NOTHING) "✗ Nothing was changed: no file in the project was written." else "✗ Not finished: $why."
        }
    }

    /**
     * The verdict for a run that was a task (not a question, a run-and-report or research). [changed] the files written;
     * [passed] the check that ran the program and passed after the last change ("" for none); [failed] the last such check when it
     * failed; [cut] why the run was asked to wrap up ("" when it was not); [open] things the app found still open after its
     * send-backs (plan steps, an unfinished rename, stand-in files, names nothing defines).
     */
    fun of(changed: Int, passed: String, failed: String, cut: String, open: List<String>, staged: Int = 0): Verdict = when {
        changed == 0 && staged > 0 -> Verdict(Kind.UNCHECKED, "the changes are suggestions for you to apply")
        changed == 0 -> Verdict(Kind.NOT_DONE, NOTHING)
        failed.isNotBlank() -> Verdict(Kind.NOT_DONE, "the last check after the last change failed ($failed)")
        open.isNotEmpty() -> Verdict(Kind.NOT_DONE, open.take(3).joinToString("; "))
        cut.isNotBlank() && passed.isBlank() -> Verdict(Kind.NOT_DONE, "the run was stopped before it finished ($cut)")
        passed.isNotBlank() -> Verdict(Kind.CHECKED, passed + (if (cut.isNotBlank()) ", though the run was stopped early ($cut)" else ""))
        else -> Verdict(Kind.UNCHECKED, "$changed file${if (changed == 1) "" else "s"} changed, and nothing that ran shows the change works; try it before relying on it")
    }
}
