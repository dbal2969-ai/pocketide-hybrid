package dev.pocketide.app.core

// v0.4 W2: slash commands typed in the AI input box.

data class SlashCommand(val name: String, val arg: String)

object Slash {
    data class Info(val name: String, val usage: String, val help: String)

    val ALL: List<Info> = listOf(
        Info("plan", "/plan <task>", "Write a plan first, approve it, then build it step by step"),
        Info("fix", "/fix [details]", "Fix the last errors or problems"),
        Info("explain", "/explain [what]", "Explain the open file or selection"),
        Info("test", "/test [what]", "Write tests and run them"),
        Info("review", "/review [what]", "Review the changes like a pull request: summary, risks, bugs, missing tests"),
        Info("skills", "/skills", "List the project's skill files (.pocketide/skills)"),
        Info("commit", "/commit", "Write a commit message for the changes and open Git"),
        Info("remember", "/remember <rule>", "Add a rule to POCKETIDE.md"),
        Info("sop", "/sop [name] <task>", "Follow a step-by-step procedure (debug, feature, refactor, game-qa, review, or your own in .pocketide/sops)"),
        Info("cloud", "/cloud <task>", "Run a task on GitHub in the background and get a pull request"),
        Info("nightly", "/nightly [when]: <task> | off", "Night shift: run a saved task on GitHub on a schedule (e.g. every night at 3am) and get a pull request"),
        Info("reviewbot", "/reviewbot [off]", "Review every pull request on GitHub: comments on likely bugs, like Cursor's BugBot (uses the /cloud settings)"),
        // 1.5.125: the benchmark from the phone, so a /cloud branch can be compared with main before the owner merges it
        Info("bench", "/bench <tasks> [on <branch>] [with <provider:model>]", "Run named benchmark tasks on GitHub (at most 8, one model, one run each), compare a branch with main, and post the result on its pull request"),
        Info("rules", "/rules [allow|deny|remove|clear] [command]", "Show or change which commands the assistant may run without asking, or never"),
        Info("deploy", "/deploy [cloudflare|errors]", "Publish this web project with GitHub Pages, or with Cloudflare (/deploy cloudflare: files plus a worker.js backend and your secrets), then check that the site answers; /deploy errors shows what the Cloudflare site's visitors' browsers reported"),
        Info("share", "/share", "Make a read-only link to a snapshot of this project (a secret GitHub gist)"),
        Info("progress", "/progress [clear]", "Show the saved plan of a long job, or clear it so the assistant stops carrying it on"),
        Info("clear", "/clear", "Start a new chat"),
        Info("model", "/model", "Switch provider or model")
    )

    /** A command when the text starts with a known /name, else null (so "/etc/hosts?" is an ordinary question). */
    fun parse(text: String): SlashCommand? {
        val t = text.trim()
        if (!t.startsWith("/")) return null
        val name = t.substring(1).takeWhile { !it.isWhitespace() }.lowercase()
        if (ALL.none { it.name == name }) return null
        return SlashCommand(name, t.substring(1 + name.length).trim())
    }

    /** Suggestions while the user is still typing the command name. */
    fun suggest(text: String): List<Info> {
        if (!text.startsWith("/") || text.contains(' ') || text.contains('\n')) return emptyList()
        val p = text.substring(1).lowercase()
        return ALL.filter { it.name.startsWith(p) }
    }

    fun fixPrompt(problems: List<String>, extra: String): String {
        val sb = StringBuilder()
        if (problems.isNotEmpty()) {
            sb.append("Fix these problems:\n")
            problems.take(20).forEach { sb.append("- ").append(it).append('\n') }
        } else sb.append("Find and fix the bug")
        if (extra.isNotBlank()) sb.append(if (problems.isEmpty()) ": " else "\nAlso: ").append(extra)
        if (problems.isEmpty() && extra.isBlank()) sb.append(" in the open file. Look at the code first and explain the cause in one sentence before the fix.")
        return sb.toString().trimEnd()
    }

    fun explainPrompt(file: String, selection: String, extra: String): String = when {
        selection.isNotBlank() -> "Explain this code from ${file.ifEmpty { "the project" }} step by step, in plain words. Do not change any files.\n\n```\n${selection.take(6000)}\n```" +
            (if (extra.isNotBlank()) "\n\nFocus on: $extra" else "")
        extra.isNotBlank() -> "Explain $extra in this project, in plain words. Do not change any files."
        file.isNotEmpty() -> "Explain what $file does and how it fits into the project, in plain words. Do not change any files."
        else -> "Explain how this project works, in plain words. Do not change any files."
    }

    fun testPrompt(file: String, extra: String): String =
        "Write tests for " + (extra.ifBlank { file.ifEmpty { "the main code of this project" } }) +
            ", using the test tools that fit the language (for example Python's unittest, Node's built-in node:test, Go's testing package). " +
            "Put them in the usual place for that language, then run them and fix any failures in the code or the tests."

    fun reviewPrompt(changes: String, extra: String): String =
        "Review these changes as a careful pull request reviewer. First read the changed files. Then reply with: " +
            "Summary (two or three sentences), Risks and possible bugs (each with the file and line), Missing tests or checks, and one suggested next step. " +
            "Be specific and honest; say \"no problems found\" if that is true. Do not change any files." +
            (if (extra.isNotBlank()) "\nFocus on: $extra" else "") + "\n\nChanges:\n" +
            changes.ifBlank { "(No change list was available; review the files edited in this chat, or the project's main files.)" }

    fun commitPrompt(changes: String): String =
        "Write a git commit message for these changes: a summary line of at most 60 characters, a blank line, then up to five short bullet points. " +
            "Reply with only the message in a ```text fence. Do not change any files.\n\n" + changes.ifBlank { "(No change list was available; base it on the edits made in this chat.)" }
}
