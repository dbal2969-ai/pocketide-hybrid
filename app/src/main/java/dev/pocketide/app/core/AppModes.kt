package dev.pocketide.app.core

/**
 * What the assistant can do in each mode, told to the model and to the person (1.5.84).
 *
 * Found on the phone: asked in Ask mode and in Agent: suggest mode to "Run look_at_preview with test=all", the model had no such
 * tool and made up how to run it ("press the Run button, which opens look_at_preview", "use the command from the preview
 * interface"). Neither exists. The model had never been told what PocketIDE's modes are, so it guessed. Now the prompt says
 * what the modes are and that the person cannot run the model's tools, and a request that names a tool the current mode does
 * not have gets the app's own answer before the model's.
 */
object AppModes {
    /** Tools that change files or run commands: refused in Ask mode and in read-only reviews. */
    val CHANGES = setOf("write_file", "edit_file", "edit_lines", "multi_edit", "apply_patch", "run_command", "doc_edit", "game_assets", "remember")

    /** Every tool name the person might type, for spotting one in a message. */
    private val NAMES = CHANGES + setOf("look_at_preview", "look_at_images", "read_file", "search", "find_code", "repo_map", "list_files",
        "file_outline", "find_symbol", "find_references", "update_plan", "explore", "advisor", "web_search", "web_fetch", "game_cache",
        "game_data", "project_audit", "history", "doc_outline")

    /** Tool names written in [text] (look_at_preview, `run_command` …). */
    fun named(text: String): List<String> = NAMES.filter { Regex("""(?<![A-Za-z0-9_])""" + Regex.escape(it) + """(?![A-Za-z0-9_])""").containsMatchIn(text) }.sorted()

    /**
     * The tools named in [text] that this request cannot use, each with the reason. [readOnly]: Ask mode or a read-only profile;
     * [hasPage]: the project has an .html page; [usesTools]: the model is called with tools at all.
     */
    fun missing(text: String, readOnly: Boolean, hasPage: Boolean, usesTools: Boolean): List<Pair<String, String>> = named(text).mapNotNull { n ->
        when {
            !usesTools -> n to "this model is not called with tools"
            n in CHANGES && readOnly -> n to "it changes files or runs commands, and this mode only reads"
            n == "look_at_preview" && !hasPage -> n to "this project has no .html page to open"
            else -> null
        }
    }

    /** Shown under the person's message, in plain words, with what to do. */
    fun personNote(missing: List<Pair<String, String>>, modeLabel: String): String {
        if (missing.isEmpty()) return ""
        val names = missing.joinToString(", ") { it.first }
        val why = missing.map { it.second }.distinct().joinToString("; ")
        val fix = when {
            missing.any { it.second.startsWith("it changes") } -> " Tap the mode chip (it says \"$modeLabel\") and choose Agent, then send the message again."
            missing.any { it.second.startsWith("this model") } -> " Pick a model that supports tool calling, or switch to Agent mode."
            else -> ""
        }
        return "$names can't run here: $why.$fix"
    }

    /** Added to the model's instructions, so it says the same thing instead of inventing a way. */
    fun modelNote(missing: List<Pair<String, String>>): String {
        if (missing.isEmpty()) return ""
        return "\n\nThe person named " + missing.joinToString(", ") { it.first + " (not available: " + it.second + ")" } +
            ". The app has already told them why. Do not describe other ways to run it and do not invent buttons or menus; " +
            "answer what you can with the tools you have, and say plainly what you could not do."
    }

    /** What the model is told about the app, in every mode. Facts only, so it has nothing to guess. */
    const val GUIDE = "\n\nAbout PocketIDE (rely only on this, and never invent other buttons, menus or commands): the mode chip next to the model " +
        "name sets what you may do. Ask: you read and answer, nothing is changed. Plan: you write a plan the person approves first. " +
        "Agent: suggest: your edits become cards the person approves. Agent: your edits are applied and checked. Your tools are the " +
        "functions you were given in this request; the person cannot run them for you, and no button runs them. If a tool you were " +
        "asked to use is not among yours, say it is not available in this mode and that Agent mode has the full set. If you do not " +
        "know how the app does something, say you do not know."
}
