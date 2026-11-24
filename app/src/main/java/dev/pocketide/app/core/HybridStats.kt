package dev.pocketide.app.core

import kotlin.math.roundToInt

/** A summary of hybrid AI requests (kept in Firestore): where they ran, how long they took, and why Gemini Nano passed some on. */
object HybridStats {
    const val PHONE = "phone"
    const val CLOUD = "cloud"
    const val STRONG = "strong"

    class Run(val where: String, val seconds: Double, val reason: String, val kind: String)

    fun summary(runs: List<Run>): String {
        if (runs.isEmpty()) return "No hybrid AI requests recorded yet."
        val n = runs.size
        val phone = runs.filter { it.where == PHONE }
        val cloud = runs.filter { it.where == CLOUD }
        val strong = runs.filter { it.where == STRONG }
        fun pct(k: Int) = (100.0 * k / n).roundToInt()
        fun avg(l: List<Run>): Double? = l.filter { it.seconds > 0 }.map { it.seconds }.takeIf { it.isNotEmpty() }?.average()
        val sb = StringBuilder("Last $n requests: ${phone.size} on this phone (${pct(phone.size)}%), ${cloud.size} in the cloud (${pct(cloud.size)}%)")
        if (strong.isNotEmpty()) sb.append(", ${strong.size} passed to your strong model (${pct(strong.size)}%)")
        sb.append(".")
        avg(phone)?.let { sb.append("\nAverage time on this phone: ${one(it)} s") }
        avg(cloud)?.let { sb.append("\nAverage time in the cloud: ${one(it)} s") }
        val reasons = runs.mapNotNull { r -> r.reason.takeIf { it.isNotBlank() }?.let { category(it) } }.groupingBy { it }.eachCount()
        if (reasons.isNotEmpty())
            sb.append("\nPassed on by Gemini Nano: " + reasons.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.value} ${it.key}" })
        return sb.toString()
    }

    /** A short name for why Nano passed a request on (the reasons come from HybridRoute, HybridAgent and HybridAi). */
    fun category(reason: String): String = when {
        reason.contains("web") -> "needed the web"
        reason.contains("limit") -> "too long"
        reason.contains("could not do") || reason.contains("could not run") -> "Nano could not do it"
        reason.contains("JSON") || reason.contains("does not exist") || reason.contains("no file was written") || reason.contains("empty") -> "unusable reply"
        else -> "other"
    }

    private fun one(x: Double): String = ((x * 10).roundToInt() / 10.0).toString()
}
