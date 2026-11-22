package dev.pocketide.app.core

/**
 * Input variants written by the app (1.5.164; research handoff §3). The input-variety check (1.5.154) asked the model to imagine other
 * shapes of input, and showed no gain; self-written tests share the model's blind spots ("Rethinking Verification for LLM Code
 * Generation"; ACL 2025 self-debugging study). This version derives concrete files from the person's own sample, mechanically, and asks
 * the model to run its code on each. Off with the variety setting. Measuring note: the sealed suite's class B hidden checks use these
 * same shapes, so that suite cannot measure this fairly; new class-B tasks with other shapes are needed first.
 */
object InputVariants {
    class Variant(val path: String, val text: String, val what: String)

    private val SAMPLE = setOf("csv", "tsv", "txt", "html", "htm")

    /** Sample inputs in the project: data files of those kinds outside tests and the app's own folders, at most [max]. */
    fun samples(files: List<String>, max: Int = 2): List<String> = files.filter { p ->
        p.substringAfterLast('.', "").lowercase() in SAMPLE && !p.startsWith(".") && !p.contains("/.") && !p.startsWith("test") &&
            !RunTargets.ignored(p) && !p.substringAfterLast('/').equals("README.txt", true) && p.substringAfterLast('/').lowercase() != "requirements.txt"
    }.take(max)

    /** The variants of one sample, under .pocketide/variants/. Each keeps the sample's content; only its shape changes. */
    fun of(path: String, text: String): List<Variant> {
        val name = path.substringAfterLast('/')
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "").lowercase()
        fun v(tag: String, t: String, what: String) = Variant(".pocketide/variants/$base-$tag.$ext", t, what)
        val lines = text.replace("\r\n", "\n").trimEnd('\n').split('\n')
        val out = ArrayList<Variant>()
        out += v("crlf", lines.joinToString("\r\n") + "\r\n", "Windows line endings")
        out += v("bom-blank", "﻿" + lines.joinToString("\n") + "\n\n\n", "a byte order mark first and blank lines at the end")
        when (ext) {
            "csv", "tsv" -> {
                val sep = if (ext == "tsv") "\t" else ","
                val rows = lines.map { it.split(sep) }
                if (rows.size >= 2 && rows[0].size >= 2) {
                    val swapped = rows.map { r -> if (r.size >= 2) listOf(r[1], r[0]) + r.drop(2) else r }
                    out += v("columns", swapped.mapIndexed { i, r -> if (i == 0) r.map { it.uppercase() } else r }.joinToString("\n") { it.joinToString(sep) } + "\n",
                        "the first two columns swapped and the header in capitals")
                    out += v("quoted", rows.mapIndexed { i, r -> if (i == 0) r.joinToString(sep) else r.joinToString(sep) { f -> "\"" + f.replace("\"", "\"\"") + "\"" } }.joinToString("\n") + "\n",
                        "every field in double quotes")
                }
            }
            "txt" -> if (lines.any { Regex("""\S {2,}\S""").containsMatchIn(it) })
                out += v("tabs", lines.joinToString("\n") { it.replace(Regex(""" {2,}"""), "\t") } + "\n", "tabs instead of runs of spaces between columns")
            "html", "htm" -> {
                out += v("caps", text.replace(Regex("""</?([a-z][a-z0-9]*)""")) { m -> m.value.uppercase() }, "tag names in capitals")
                out += v("spread", text.replace(Regex("""(<t[dh][^>]*>)([^<]*)(</t[dh]>)""", RegexOption.IGNORE_CASE)) { m ->
                    m.groupValues[1] + "\n    " + m.groupValues[2].replace(" ", "&nbsp;") + "\n  " + m.groupValues[3] }, "cells over several lines with &nbsp; for spaces")
            }
        }
        return out
    }

    /** The message that lists the written variants. */
    fun ask(written: List<Pair<String, List<Variant>>>): String =
        "Before you finish: this code reads input from outside the project. The app wrote other shapes of the sample you were given, " +
            "with the same content:\n" + written.joinToString("\n") { (s, vs) -> vs.joinToString("\n") { "- ${it.path}: $s with ${it.what}" } } +
            "\nRun your code on each and compare with what it gives for the sample; it should read the same data. Fix what breaks, keep " +
            "what the person asked for working, then finish and say which ones now read the same."
}
