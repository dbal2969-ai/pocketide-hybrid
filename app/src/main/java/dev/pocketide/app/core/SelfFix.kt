package dev.pocketide.app.core

/**
 * The parts of "the app fixes itself" that need no Android: writing a report the assistant can act on,
 * and reading GitHub's answers to find the newest build of the app.
 */
object SelfFix {
    /** A bug report the assistant can work from: what crashed, what the app was doing, and the device. */
    fun report(crash: String?, device: String, version: String, recentProblems: String, extra: String = ""): String {
        val sb = StringBuilder("A problem in PocketIDE itself. Here is everything the app knows.\n\n")
        sb.append("## Version and device\n").append(version).append("\n").append(device).append("\n")
        if (crash != null && crash.isNotBlank()) {
            sb.append("\n## Last crash\n```\n").append(Trim.middle(crash.trim(), 4000)).append("\n```\n")
        }
        if (recentProblems.isNotBlank()) sb.append("\n## Recent problems\n```\n").append(Trim.middle(recentProblems.trim(), 1500)).append("\n```\n")
        if (extra.isNotBlank()) sb.append("\n## What I was doing\n").append(extra.trim()).append("\n")
        sb.append("\nThis is the app's own source. Find the cause, name the file and line if you can, and make the smallest fix. ")
        sb.append("Only change the project's files if this project is PocketIDE's source; otherwise just explain the cause.")
        return ChatExport.redact(sb.toString())
    }

    class Run(val id: Long, val name: String, val status: String, val conclusion: String, val head: String, val title: String, val finished: String) {
        val ok: Boolean get() = status == "completed" && conclusion == "success"
    }

    /** Workflow runs from GET /repos/{repo}/actions/runs, newest first. */
    fun parseRuns(json: String): List<Run> {
        val o = try { MiniJson.obj(MiniJson.parse(json)) } catch (e: MiniJson.JsonError) { null } ?: return emptyList()
        val list = MiniJson.list(o["workflow_runs"]) ?: return emptyList()
        return list.mapNotNull { x ->
            val r = MiniJson.obj(x) ?: return@mapNotNull null
            val id = (r["id"] as? Number)?.toLong() ?: return@mapNotNull null
            Run(id, r["name"] as? String ?: "build", r["status"] as? String ?: "", r["conclusion"] as? String ?: "",
                (r["head_sha"] as? String ?: "").take(7), r["display_title"] as? String ?: r["head_commit"]?.let { MiniJson.obj(it)?.get("message") as? String }?.lines()?.firstOrNull() ?: "",
                r["updated_at"] as? String ?: "")
        }
    }

    /** The newest finished, successful run, or null. */
    fun newestGood(runs: List<Run>): Run? = runs.firstOrNull { it.ok }

    class Artifact(val id: Long, val name: String, val bytes: Long, val expired: Boolean)

    /** Artifacts from GET /repos/{repo}/actions/runs/{id}/artifacts. */
    fun parseArtifacts(json: String): List<Artifact> {
        val o = try { MiniJson.obj(MiniJson.parse(json)) } catch (e: MiniJson.JsonError) { null } ?: return emptyList()
        val list = MiniJson.list(o["artifacts"]) ?: return emptyList()
        return list.mapNotNull { x ->
            val a = MiniJson.obj(x) ?: return@mapNotNull null
            val id = (a["id"] as? Number)?.toLong() ?: return@mapNotNull null
            Artifact(id, a["name"] as? String ?: "", (a["size_in_bytes"] as? Number)?.toLong() ?: 0L, a["expired"] == true)
        }
    }

    /** The artifact most likely to hold the APK. */
    /** The Linux add-on's artifact in GPL builds (1.5.128); never taken for the app itself. */
    const val ADDON_ARTIFACT = "PocketIDE-Linux-addon"

    /** The app's artifact: named "...debug-apk" by both editions' build-apk.yml (1.5.133: a renamed artifact is no longer guessed
     *  from its size, so a build without the app's artifact finds none instead of offering the wrong file). */
    fun pickApk(artifacts: List<Artifact>): Artifact? =
        artifacts.filter { !it.expired && it.bytes > 0 && !it.name.startsWith(ADDON_ARTIFACT) && it.name.lowercase().endsWith("debug-apk") }
            .maxByOrNull { it.bytes }

    fun runsPath(repo: String, perPage: Int = 20): String = "/repos/$repo/actions/runs?per_page=$perPage"
    /** Successful runs of the APK build on main only (the add-on is built there; other workflows have no APK). */
    fun buildRunsPath(repo: String, perPage: Int = 10): String = "/repos/$repo/actions/workflows/build-apk.yml/runs?branch=main&status=success&per_page=$perPage"

    fun pickAddon(artifacts: List<Artifact>): Artifact? = artifacts.firstOrNull { !it.expired && it.bytes > 0 && it.name == ADDON_ARTIFACT }
    fun artifactsPath(repo: String, runId: Long): String = "/repos/$repo/actions/runs/$runId/artifacts"
    fun downloadPath(repo: String, artifactId: Long): String = "/repos/$repo/actions/artifacts/$artifactId/zip"

    /** Which file inside the artifact zip is the APK. */
    fun apkEntry(names: List<String>): String? =
        names.filter { it.lowercase().endsWith(".apk") }
            .sortedBy { if (it.lowercase().contains("debug")) 0 else 1 }
            .firstOrNull()

    /** A short name for the downloaded file. */
    fun fileName(version: String, head: String): String =
        "pocketide-" + version.replace(Regex("[^A-Za-z0-9.\\-]"), "") + (if (head.isNotBlank()) "-$head" else "") + ".apk"

    /** What to say about a run before downloading it. */
    fun describe(r: Run, currentVersion: String): String =
        "${r.title.ifBlank { r.name }}\nCommit ${r.head}, finished ${r.finished.replace('T', ' ').removeSuffix("Z")}\n" +
            "You are running $currentVersion. The app cannot tell whether this build is newer, so check the commit."

    const val INSTALL_NOTE =
        "Android will ask you to allow installing from PocketIDE, then show its own install screen. Nothing is installed without you tapping Install."
}
