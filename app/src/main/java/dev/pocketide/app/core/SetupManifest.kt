package dev.pocketide.app.core

/**
 * The project's setup steps in .pocketide/setup.json, run before the assistant starts work (GitHub Copilot's cloud agent
 * runs copilot-setup-steps.yml first; devcontainer.json has the same idea). Most agent failures in a fresh project are
 * missing packages, not bad code. Example:
 *
 *   { "steps": [ { "name": "Install packages", "run": "npm install" }, { "name": "Build", "run": "npm run build" } ] }
 *
 * The steps run again only when the file changes (the app remembers which version ran, outside the project). A failing step does not
 * stop the others or the request, as in Copilot: the assistant is told what failed and works with what is there.
 * They run in the GPL edition's Linux; the MIT edition tells the assistant they could not run here.
 */
object SetupManifest {
    const val FILE = ".pocketide/setup.json"
    const val MAX_STEPS = 8

    data class Step(val name: String, val run: String)
    data class Result(val step: Step, val code: Int, val out: String, val skipped: Boolean = false)

    fun parse(json: String?): List<Step> {
        if (json.isNullOrBlank()) return emptyList()
        val o = try { MiniJson.obj(MiniJson.parse(json)) } catch (e: Exception) { null } ?: return emptyList()
        return (o["steps"] as? List<*>).orEmpty().mapNotNull { x ->
            val m = x as? Map<*, *> ?: return@mapNotNull null
            val run = (m["run"] as? String)?.trim().orEmpty()
            if (run.isEmpty()) null else Step((m["name"] as? String)?.trim()?.ifEmpty { null } ?: run.take(40), run)
        }.take(MAX_STEPS)
    }

    /** A short, stable fingerprint of the file's text. */
    fun hash(json: String): String {
        var h = 1125899906842597L
        for (c in json.trim()) h = 31 * h + c.code
        return java.lang.Long.toHexString(h)
    }

    /** The steps to run now, or null when there are none or they already ran for this version of the file. */
    fun pending(json: String?, done: String?): List<Step>? {
        val steps = parse(json)
        if (steps.isEmpty()) return null
        if (done?.trim() == hash(json!!)) return null
        return steps
    }

    /** Added to the system prompt after the steps ran. */
    fun contextNote(results: List<Result>): String {
        if (results.isEmpty()) return ""
        val ok = results.count { !it.skipped && it.code == 0 }
        val sb = StringBuilder("\n\nThe project's setup steps ($FILE) ran before this request: $ok of ${results.size} worked.\n")
        for (r in results) {
            sb.append("- ").append(r.step.name).append(" (`").append(r.step.run).append("`): ")
            when {
                r.skipped -> sb.append("skipped by the person\n")
                r.code == 0 -> sb.append("worked\n")
                else -> sb.append("failed with exit code ").append(r.code).append(":\n").append(Verify.trim(r.out, 800)).append("\n")
            }
        }
        if (ok < results.size) sb.append("Work with the environment as it is. If a failed step matters for the task, fix its cause or tell the person what is missing.\n")
        return sb.toString()
    }

    /** For the person: one line. */
    fun summary(results: List<Result>): String {
        val ok = results.count { !it.skipped && it.code == 0 }
        val failed = results.filter { !it.skipped && it.code != 0 }.map { it.step.name }
        return "Setup: $ok of ${results.size} step(s) worked" + if (failed.isEmpty()) "." else "; failed: " + failed.joinToString(", ") + "."
    }

    /** For editions without Linux: the steps exist but cannot run here. */
    fun unavailableNote(json: String?): String {
        val steps = parse(json)
        if (steps.isEmpty()) return ""
        return "\n\nThis project has setup steps in $FILE (" + steps.joinToString(", ") { "`${it.run}`" } +
            "), but this edition cannot run them. Do not assume they ran. If the task needs them, suggest running the job on GitHub with /cloud.\n"
    }
}
