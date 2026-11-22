package dev.pocketide.app.core

/**
 * Split a big job into parts. After the requirements are approved, the assistant groups them into a few independent
 * parts (for example the game logic, the screen, the tests). Each part then runs as its own focused pass, and a last
 * pass checks that the parts fit together and that nothing in the request was left out.
 */
object Parts {
    /**
     * Seconds the planner may take to split a job into parts (1.5.83). The request streams, so the 120 s idle timeout never fires
     * while a slow model keeps writing; one run spent six minutes here before any file was touched. Past the deadline the plan
     * is taken from what was written so far, or the parts fall back to one per requirement group.
     */
    const val PLAN_DEADLINE_SECONDS = 180

    /**
     * How the parts are built, in plain words: "at the same time with 3 copies of kimi-k2.6 on Cloudflare" when every lane is the
     * same model (the "copies of the main model" setting), else "at the same time on 3 providers (A, B, C)". Before 1.5.83 the card
     * said "on 3 providers" for three copies of one model, which hid that all three calls went to one account at once.
     */
    fun lanesLabel(lanes: List<Pair<String, String>>): String {
        val distinct = lanes.distinct()
        if (distinct.size == 1) {
            val (prov, model) = distinct[0]
            return "at the same time with ${lanes.size} copies of $model on $prov"
        }
        return "at the same time on ${lanes.size} models (" + distinct.joinToString(", ") { (p, m) -> "$m on $p" } + ")"
    }

    const val MIN_REQUIREMENTS = 6
    const val MAX_PARTS = 4

    /** With "always build in parts" on, a list this short is split too. */
    const val MIN_REQUIREMENTS_ALWAYS = 3

    /** Splitting only pays off for a longer list (or the person asked for parts every time). */
    fun shouldSplit(requirements: Int, enabled: Boolean, always: Boolean = false, oneFile: Boolean = false): Boolean =
        enabled && !oneFile && requirements >= (if (always) MIN_REQUIREMENTS_ALWAYS else MIN_REQUIREMENTS)

    private val ONE_FILE = Regex("""(?i)\b(?:one|a single|single|1)[\s-]+(?:\w+[\s-]+)?file\b|\bsingle-file\b|\bin one \.?\w+\b""")
    private val FILE_NAME = Regex("""(?i)\b[\w./-]+\.(?:html?|css|m?js|jsx|tsx?|py|go|java|kt|rs|rb|php|c|cpp|h|json|md|txt|svg)\b""")

    /**
     * Everything goes into one file (1.5.129): the request says "one HTML file" or "a single file", or the request and its
     * requirements name exactly one file. Parts are built so they share no files; with one file they can only run one after
     * another, so splitting cost 3+ minutes and a second approval for nothing (emulator test: a one-file snake game).
     */
    fun oneFile(request: String, requirements: List<String>): Boolean {
        if (ONE_FILE.containsMatchIn(request)) return true
        val names = (listOf(request) + requirements).flatMap { t -> FILE_NAME.findAll(t).map { it.value.lowercase().substringAfterLast('/') } }.toSet()
        return names.size == 1
    }

    /** [model]: the team member (1-based) the assistant chose for this part, when it was asked to choose. */
    class Part(val name: String, val numbers: List<Int>, val files: List<String> = emptyList(), val model: Int? = null)

    private val MODEL = Regex("""\s*[|;,(]\s*(?:model|helper)\s*[:=#]?\s*#?(\d{1,2})\)?\s*""", RegexOption.IGNORE_CASE)

    /**
     * Added to the grouping request when the person's team has several models and the assistant picks one per part:
     * the numbered list of models, and how to name the choice.
     */
    fun teamNote(team: List<String>): String =
        "\n\nHelpers you can give each part to (pick the one that suits it: a stronger model for hard logic, a fast one for simple screens or data):\n" +
            team.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n") +
            "\nAdd \" | model: <number>\" at the end of each part line, e.g. \"1. Rules: 1, 2 | files: rules.js | model: 2\". The same helper may take several parts."

    /**
     * The team typed in Settings: one "provider model" per line; the provider by its id or name (spaces and case ignored),
     * the model as written (empty = that provider's chosen model). Lines naming no known provider are dropped.
     */
    fun parseTeam(text: String, providers: List<Pair<String, String>>): List<Workflow.Slot> {
        fun key(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { line ->
            val words = line.split(Regex("\\s+"))
            // the longest run of first words that names a provider ("Custom server", "cloudflare")
            for (n in words.size downTo 1) {
                val head = key(words.take(n).joinToString(" "))
                val p = providers.firstOrNull { key(it.first) == head || key(it.second) == head } ?: continue
                return@mapNotNull Workflow.Slot(p.first, words.drop(n).joinToString(" "))
            }
            null
        }
    }

    /** The lane (index into [lanes]) for each part: the assistant's choice when it made a valid one, otherwise in turn. */
    fun assign(parts: List<Part>, lanes: Int): List<Int> {
        var turn = 0
        return parts.map { p -> p.model?.let { it - 1 }?.takeIf { it in 0 until lanes } ?: (turn++ % lanes) }
    }

    /** Asked after the list is approved: group the numbered requirements into independent parts. */
    fun request(task: String, reqs: List<String>): String =
        "Group these requirements into 2 to $MAX_PARTS independent parts that can be built one after the other, each in its own focused pass " +
            "(for example: the data and rules, the screen and controls, the sounds and polish, the tests). " +
            "Every requirement number must be in exactly one part. Put things that others depend on first. Do not output code, files or tool calls.\n" +
            "Reply in exactly this format:\nPARTS: <one-line name of the whole thing>\n1. <Part name>: <requirement numbers, e.g. 1, 2, 5>\n2. <Part name>: <numbers>\n...\n\n" +
            "Request: $task\n\nRequirements:\n" + Requirements.list(reqs)

    private val LINE = Regex("""^\s*(?:[-*]\s*)?\d{1,2}[.):]\s+(.+?)\s*[:\-–—]\s*(?:requirements?\s*)?#?([\d,\s#and&]+?)\s*\.?\s*$""", RegexOption.IGNORE_CASE)
    private val NUM = Regex("""\d{1,2}""")
    private val FILES = Regex("""\s*[|;(]\s*(?:files?|owns|writes)\s*[:=]\s*(.*?)\)?\s*$""", RegexOption.IGNORE_CASE)

    /** The parts in a reply, with every requirement 1..[count] in exactly one part. Null when the reply has fewer than two parts. */
    fun parse(reply: String, count: Int): List<Part>? {
        val seen = HashSet<Int>()
        val parts = ArrayList<Part>()
        for (full in reply.replace("\r", "").lineSequence()) {
            val mm = MODEL.findAll(full).lastOrNull()
            val line = if (mm != null) full.removeRange(mm.range) else full
            val pick = mm?.groupValues?.get(1)?.toIntOrNull()
            val fm = FILES.find(line)
            val raw = if (fm != null) line.substring(0, fm.range.first) else line
            val m = LINE.matchEntire(raw) ?: continue
            val name = m.groupValues[1].trim().trim('*', ':', ' ')
            val nums = NUM.findAll(m.groupValues[2]).map { it.value.toInt() }.filter { it in 1..count && seen.add(it) }.toList()
            if (name.isNotEmpty() && nums.isNotEmpty()) parts.add(Part(name, nums, if (fm != null) splitFiles(fm.groupValues[1]) else emptyList(), pick))
            if (parts.size >= MAX_PARTS) break
        }
        if (parts.size < 2) return null
        val missing = (1..count).filter { it !in seen }
        if (missing.isEmpty()) return parts
        // anything the model forgot goes into the last part, so nothing is dropped
        val last = parts.removeAt(parts.size - 1)
        parts.add(Part(last.name, last.numbers + missing, last.files, last.model))
        return parts
    }

    /** When the model's grouping cannot be read: consecutive chunks of the list. */
    fun fallback(count: Int): List<Part> {
        val size = Math.ceil(count / Math.min(MAX_PARTS, Math.max(2, (count + 3) / 4)).toDouble()).toInt().coerceAtLeast(1)
        return (1..count).chunked(size).mapIndexed { i, nums -> Part("Part ${i + 1}", nums) }
    }

    /** One line per part for the approval card. */
    fun plan(parts: List<Part>, title: String): Plan =
        Plan(title.ifBlank { "Parts" }, parts.map { it.name + " (requirements " + it.numbers.sorted().joinToString(", ") + ")" }.toMutableList())

    /** The message that runs one part. [done] are the parts already built. */
    fun stepPrompt(task: String, reqs: List<String>, plan: Plan, index: Int, scope: String = ""): String {
        val sb = StringBuilder()
        sb.append(task).append("\n\nWe are building this in parts. The full approved requirements are:\n").append(Requirements.list(reqs)).append("\n\n")
        sb.append("Parts:\n").append(plan.checklist()).append("\n\n")
        sb.append("Now build part ").append(index + 1).append(" of ").append(plan.steps.size).append(" only: ").append(plan.steps[index]).append('\n')
        sb.append("Earlier parts are already in the project: read the files you need from them and use their names exactly, do not rewrite them. ")
        sb.append("Do not start later parts. Finish every requirement in this part. Keep each file under about ${OutputLimit.FILE_LINES} lines: if this part needs more, split it into several files (for example styles, engine, interface), and write each file in its own call.")
        if (scope.isNotBlank()) sb.append("\nWork only inside the folder \"").append(scope.trim('/')).append("\".")
        sb.append("\nEnd with one line that starts with \"Part done:\" summarising what you built and the file names.")
        return sb.toString()
    }

    // ---- parts built at the same time, each by its own model ----

    /** "a.js, b.css and src/" as clean project paths. */
    fun splitFiles(text: String): List<String> =
        text.split(',', ';', ' ', '\t').map { norm(it.trim().trim('`', '"', '\'', '*', '.', ':', '(', ')')) }
            .filter { it.isNotEmpty() && !it.equals("and", true) && !it.contains("..") }.distinct()

    fun norm(path: String): String = path.trim().replace('\\', '/').removePrefix("./").trimStart('/')

    private fun overlap(a: String, b: String) = a == b || (a.endsWith("/") && b.startsWith(a)) || (b.endsWith("/") && a.startsWith(b))

    /** True when the part may write [path]: a file it lists, or anything under a folder it lists (written with a trailing slash). */
    fun owns(files: List<String>, path: String): Boolean {
        val p = norm(path)
        return files.any { f -> val n = norm(f); n == p || (n.endsWith("/") && p.startsWith(n)) }
    }

    fun refusal(files: List<String>, path: String): String =
        "Not written: ${norm(path)} belongs to another part, which another helper is building right now. " +
            "Write only your own files: " + files.joinToString(", ") + ". If you need something from the other part, use the names in the agreement and leave it to them."

    /** Parts can be built at the same time when each lists files and no file (or folder) belongs to two parts. */
    fun independent(parts: List<Part>): Boolean {
        if (parts.size < 2 || parts.any { it.files.isEmpty() }) return false
        for (i in parts.indices) for (j in i + 1 until parts.size)
            for (a in parts[i].files) for (b in parts[j].files) if (overlap(norm(a), norm(b))) return false
        return true
    }

    /** The providers that can take a part, the main one first: same message format, a key (or none needed), and not a model on the phone. */
    fun lanes(main: String, usable: List<String>, kindOf: (String) -> String): List<String> {
        if (main !in usable) return emptyList()
        val kind = kindOf(main)
        return (listOf(main) + usable).distinct().filter { it in usable && kindOf(it) == kind }
    }

    /** Asked instead of [request] when the parts will be built at the same time. */
    fun requestParallel(task: String, reqs: List<String>): String =
        "Group these requirements into 2 to $MAX_PARTS parts that different helpers build AT THE SAME TIME, without seeing each other's work. " +
            "Every requirement number must be in exactly one part. Each part owns whole files: list the files only that part writes, and never put one file in two parts. A part that needs several files may own a whole folder: write it with a trailing slash, for example src/world/. " +
            "Then write the agreement all parts follow, in short lines: the file names and how they load each other (script order, imports), the names and parameters of the functions " +
            "and classes the parts share, the data shapes, and the ids and class names the page and the code share. Do not output code, files or tool calls.\n" +
            PartsContract.FORMAT + "\n" +
            "Reply in exactly this format:\nPARTS: <one-line name of the whole thing>\n1. <Part name>: <requirement numbers, e.g. 1, 2, 5> | files: <file1, file2>\n2. <Part name>: <numbers> | files: <files>\n...\n" +
            "CONTRACT:\n<the agreement>\n\nRequest: $task\n\nRequirements:\n" + Requirements.list(reqs)

    /** The agreement in a reply to [requestParallel]; empty when there is none. */
    fun contract(reply: String): String {
        val lines = reply.replace("\r", "").lines()
        val at = lines.indexOfFirst { Regex("""^\s*\W*CONTRACT\W*\s*:?.*$""", RegexOption.IGNORE_CASE).matches(it) }
        if (at < 0) return ""
        val first = lines[at].substringAfter(':', "").trim().trim('*')
        return (listOf(first) + lines.drop(at + 1)).joinToString("\n").trim().take(4000)
    }

    /** One line per part for the approval card, with the files each part owns. */
    fun planWithFiles(parts: List<Part>, title: String): Plan =
        Plan(title.ifBlank { "Parts" }, parts.map { it.name + " (requirements " + it.numbers.sorted().joinToString(", ") + "; files: " + it.files.joinToString(", ") + ")" }.toMutableList())

    /** The message that runs one part while the others are built by other helpers. */
    fun parallelPrompt(task: String, reqs: List<String>, parts: List<Part>, index: Int, contract: String, scope: String = ""): String {
        val me = parts[index]
        val sb = StringBuilder()
        sb.append(task).append("\n\nWe are building this in parts, and other helpers build the other parts at the same time as you. The full approved requirements are:\n")
            .append(Requirements.list(reqs)).append("\n\n")
        sb.append("Your part (").append(index + 1).append(" of ").append(parts.size).append("): ").append(me.name).append(". Requirements: ").append(me.numbers.sorted().joinToString(", ")).append(".\n")
        sb.append("Write only these files: ").append(me.files.joinToString(", ")).append(". Any other file is refused.\n")
        sb.append("The other parts, built right now by others:\n")
        for ((i, o) in parts.withIndex()) if (i != index) sb.append("- ").append(o.name).append(": ").append(o.files.joinToString(", ")).append('\n')
        if (contract.isNotBlank()) sb.append("\nThe agreement every part follows (use these names exactly):\n").append(contract.trim()).append('\n')
        sb.append("\nTheir files may not exist yet or may be half written: do not wait for them, read them, or change them. Build your part so it works with the names in the agreement. ")
        sb.append("Do not run commands; a check of the whole result runs after all parts are done. ")
        sb.append("Finish every requirement in your part. Keep each file under about ${OutputLimit.FILE_LINES} lines: if a file would be longer, split it into more files inside a folder you own (an entry ending in a slash), and write each file in its own call.")
        if (scope.isNotBlank()) sb.append("\nWork only inside the folder \"").append(scope.trim('/')).append("\".")
        sb.append("\nEnd with one line that starts with \"Part done:\" summarising what you built and the file names.")
        return sb.toString()
    }
    /**
     * After parts built at the same time: one normal run over the whole project. The parts could not run commands or checks,
     * so this run is where the tests, the game check, the play tests and the page look happen, and where seams between parts get fixed.
     */
    fun wholeCheckPrompt(task: String, reqs: List<String>, files: List<String>, scope: String = "", agreementProblems: List<String> = emptyList()): String {
        val sb = StringBuilder()
        sb.append(task).append("\n\nEvery part of this job was built at the same time by separate helpers, and none of them could run commands or tests. ")
        sb.append("Now check the whole result as one project and fix what does not work:\n")
        sb.append("- Read how the parts connect (imports, shared names, files that load other files) and fix any name or path that does not match.\n")
        sb.append("- Run the project's tests or the program, and for a page or game use look_at_preview (and the saved play tests, if there are any).\n")
        sb.append("- Fix every failure, then check again. Do not rewrite parts that work.\n")
        if (agreementProblems.isNotEmpty()) sb.append("\nThe app compared the files with the agreement between the parts and found these mismatches. They are real; fix each one first:\n")
            .append(agreementProblems.take(30).joinToString("\n") { "- $it" }).append('\n')
        if (reqs.isNotEmpty()) sb.append("\nThe approved requirements:\n").append(Requirements.list(reqs)).append('\n')
        if (files.isNotEmpty()) sb.append("\nFiles the parts wrote: ").append(files.take(60).joinToString(", ")).append(if (files.size > 60) ", and ${files.size - 60} more" else "").append(".\n")
        if (scope.isNotBlank()) sb.append("Work only inside the folder \"").append(scope.trim('/')).append("\".\n")
        sb.append("End with what you checked, what you fixed, and anything you could not check.")
        return sb.toString()
    }
}
