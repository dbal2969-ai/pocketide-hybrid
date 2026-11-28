package dev.pocketide.app.core

/**
 * 1.5.218 (reliability run 1127-2155): the screen went off twice during an unattended run, each time after about 27 minutes of
 * Gemini Nano working without a break (21:55 to 22:21, then 01:06 to about 01:34), and nothing in the results said why: the
 * keep-screen-on flag was set, no window of the app covered it, and the run kept no record of the phone's state. This is that
 * record: one line per event (the screen, the app leaving and coming back, window focus, heat, power saving) with the phone's
 * state beside it, written to events.txt in the run's folder, and the off-screen spans listed in the report.
 */
object BenchEvents {
    const val FILE = "events.txt"

    /** The phone's state at an event. [thermal]: PowerManager's status (0 none … 6 shutdown), -1 when the phone does not say. */
    data class Snap(val interactive: Boolean?, val thermal: Int, val batteryPct: Int, val tempC: Double, val charging: Boolean, val powerSave: Boolean,
                    val keepOnFlag: Boolean?, val screenLock: Boolean, val focus: Boolean?, val sinceTouchS: Long)

    private val THERMAL = listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown")
    fun thermalName(s: Int): String = if (s < 0) "unknown" else THERMAL.getOrElse(s) { "status $s" }

    private fun yn(b: Boolean?) = when (b) { true -> "yes"; false -> "no"; null -> "?" }

    fun snapText(s: Snap): String = "screen on ${yn(s.interactive)}, heat ${thermalName(s.thermal)}, battery ${s.batteryPct}% " +
        "%.1f°C".format(java.util.Locale.US, s.tempC) + (if (s.charging) " charging" else "") + (if (s.powerSave) ", power saving on" else "") +
        ", keep-on flag ${yn(s.keepOnFlag)}, screen lock held ${yn(s.screenLock)}, window focus ${yn(s.focus)}" +
        (if (s.sinceTouchS >= 0) ", last touch ${span(s.sinceTouchS)} ago" else "")

    /** [clock]: "MM-dd HH:mm:ss". */
    fun line(clock: String, what: String, snap: Snap?): String = "$clock  $what" + (snap?.let { " | " + snapText(it) } ?: "")

    fun span(seconds: Long): String = when {
        seconds < 90 -> "$seconds s"
        seconds < 3600 -> "${(seconds + 30) / 60} min"
        else -> "%d h %02d min".format(seconds / 3600, (seconds % 3600) / 60)
    }

    /** The battery this warm, or the phone reporting severe heat, and the run waits before the next task. Guesses until a run shows the real limit. */
    const val HOT_C = 46.0
    const val COOL_C = 43.0
    const val THERMAL_SEVERE = 3
    const val MAX_COOL_WAIT_MS = 15 * 60_000L

    fun tooHot(thermal: Int, tempC: Double): Boolean = thermal >= THERMAL_SEVERE || tempC >= HOT_C
    fun cooled(thermal: Int, tempC: Double): Boolean = thermal < THERMAL_SEVERE && tempC < COOL_C

    const val LEFT = "PocketIDE left the screen"
    const val BACK = "PocketIDE is back on screen"

    private val STAMP = Regex("""^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)  (.*)$""")
    private class Ev(val at: Long, val clock: String, val text: String)

    private fun parse(events: String): List<Ev> {
        val out = ArrayList<Ev>()
        var year = 0
        var lastMonth = -1
        for (l in events.lineSequence()) {
            val m = STAMP.find(l) ?: continue
            val (mo, d, h, mi, s) = m.destructured.toList().take(5).map { it.toInt() }
            if (lastMonth > mo) year++   // December to January
            lastMonth = mo
            // the record has no year: only differences inside a run matter (2024 so that a February 29 is a valid date)
            val at = try { java.time.LocalDateTime.of(2024 + year, mo, d, h, mi, s).toEpochSecond(java.time.ZoneOffset.UTC) } catch (e: Exception) { continue }
            out.add(Ev(at, l.substring(6, 14), m.groupValues[6]))
        }
        return out
    }

    /**
     * The spans of a minute or more that PocketIDE spent off screen, each with what was recorded in the 30 seconds before it left
     * (the screen turning off, focus lost, heat) and the phone's state when it left.
     */
    fun pauses(events: String): List<String> {
        val evs = parse(events)
        val out = ArrayList<String>()
        var i = 0
        while (i < evs.size) {
            val e = evs[i]
            if (!e.text.startsWith(LEFT)) { i++; continue }
            val j = (i + 1 until evs.size).firstOrNull { evs[it].text.startsWith(BACK) }
            val end = j?.let { evs[it] }
            val length = (end?.at ?: evs.last().at) - e.at
            if (length >= 60) {
                val lead = evs.subList(0, i).filter { e.at - it.at in 0..30 && !it.text.startsWith("task ") }.joinToString("; ") { it.clock + " " + it.text.substringBefore(" | ") }
                out.add("${e.clock} to ${end?.clock ?: "the end of the record"} (${span(length)}). When it left: ${e.text.substringAfter(" | ", "no state recorded")}." +
                    (if (lead.isNotEmpty()) " Just before: $lead." else ""))
            }
            i = (j ?: evs.size - 1) + 1
        }
        return out
    }

    /** The report's section, or "" when the run was never off screen for a minute. */
    fun section(events: String): String {
        val p = pauses(events)
        if (p.isEmpty()) return ""
        return "\n## Times PocketIDE was off screen\n\nGemini Nano only runs while PocketIDE is shown, so the run waited. The whole record is in ${FILE}.\n\n" +
            p.joinToString("\n") { "- $it" } + "\n"
    }
}
