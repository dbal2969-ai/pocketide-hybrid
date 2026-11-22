package dev.pocketide.app.core

/**
 * The parts of the agent's instructions that depend only on the request and the mode (1.5.85). ChatPane adds the parts that
 * come from the app's state (secrets, MCP servers, memory, skills, the advisor); the agent harness uses these as they are, so a
 * benchmark run is told what the app would tell it.
 */
object AgentPrompt {
    /** [kind]: the request's kind as decided when it was sent (Intent.of, or TASK when the person chose to run it as a task). */
    fun core(text: String, agent: Boolean, envNote: String, autonomous: Boolean, webResearch: Boolean, readOnlyMode: Boolean, hasPage: Boolean,
             kind: Intent.Kind = Intent.of(text), scope: Boolean = false, premise: Boolean = false): String =
        Prompts.toolsSystem(agent, envNote) + (if (autonomous) Prompts.AUTONOMOUS_NOTE else "") +
            Habits.limits(webResearch, autonomous) + Habits.notes(text, kind == Intent.Kind.QUESTION) + AppModes.modelNote(AppModes.missing(text, readOnlyMode, hasPage, true)) +
            (if (agent && kind == Intent.Kind.REPORT) Scope.REPORT_NOTE else "") +
            // 1.5.156 (ScopeGate, off until measured): report what the project lacks instead of adding it
            (if (agent && scope && kind != Intent.Kind.QUESTION) ScopeGate.PREMISE_NOTE else "") +
            // 1.5.164 (PremiseCheck, off until measured): reporting what is not there is a correct outcome
            (if (agent && premise && kind != Intent.Kind.QUESTION) PremiseCheck.NOTE else "")

    /** Notes about the page, play tests and design, added last. [hasGame]: the project already holds a game. */
    fun pageNotes(text: String, agent: Boolean, hasPage: Boolean, hasGame: Boolean): String =
        (if (agent && hasPage) "\n\n" + PageLook.TOOL_NOTE else "") +
            (if (agent && (hasPage || Design.wanted(text))) "\n\n" + PlayTests.NOTE else "") +
            (if (agent && Design.wanted(text) && !hasGame) "\n\n" + Design.note(text) else "")
}
