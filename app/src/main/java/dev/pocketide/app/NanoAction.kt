package dev.pocketide.app

import com.google.firebase.ai.annotations.Generable
import com.google.firebase.ai.annotations.Guide
import kotlinx.serialization.Serializable

/**
 * 1.5.220 (experiment "structured output", off unless ticked): one step of the on-phone agent as a typed object. Firebase AI
 * Logic's on-device structured output needs a class whose schema is generated at build time, so the step's arguments are flat
 * here: the ones a tool does not use stay empty, and [json] writes the step back in the form the agent already reads
 * ({"reason", "tool", "tool_args"}). Only the arguments Gemini Nano used in run 1127-2155 are here.
 */
@Serializable
@Generable(description = "One step of a coding assistant: why, which tool, and the tool's arguments. Leave out the arguments the tool does not use.")
data class NanoAction(
    @Guide(description = "One short sentence: why this step.") val reason: String = "",
    @Guide(description = "The tool to use, or answer to reply to the person.") val tool: String = "",
    @Guide(description = "File path, for read_file, edit_file, edit_lines, write_file, replace_function.") val path: String? = null,
    @Guide(description = "edit_file: the exact text to find.") val search: String? = null,
    @Guide(description = "edit_file: the text to put in its place.") val replace: String? = null,
    @Guide(description = "edit_lines: the new lines. answer: the reply to the person.") val text: String? = null,
    @Guide(description = "write_file: the whole file.") val content: String? = null,
    @Guide(description = "edit_lines or read_file: first line number.") val start: Int? = null,
    @Guide(description = "edit_lines or read_file: last line number.") val end: Int? = null,
    @Guide(description = "run_command: the command.") val command: String? = null,
    @Guide(description = "replace_function: the function's name.") val name: String? = null,
    @Guide(description = "replace_function: the whole new function.") val code: String? = null,
    @Guide(description = "search: what to look for.") val query: String? = null,
) {
    companion object

    /** The step as the JSON the agent's parser reads. */
    fun json(): String {
        val args = LinkedHashMap<String, Any?>()
        fun put(k: String, v: Any?) { if (v != null && !(v is String && v.isEmpty())) args[k] = v }
        put("path", path); put("search", search); put("replace", replace); put("text", text); put("content", content)
        put("start", start); put("end", end); put("command", command); put("name", name); put("code", code); put("query", query)
        // an edit that only removes text has an empty "replace", which must stay
        if (tool == "edit_file" && search != null && replace != null) args["replace"] = replace
        return dev.pocketide.app.core.MiniJson.write(linkedMapOf("reason" to reason, "tool" to tool, "tool_args" to args))
    }
}
