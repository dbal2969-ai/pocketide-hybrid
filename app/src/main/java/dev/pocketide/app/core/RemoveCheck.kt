package dev.pocketide.app.core

/**
 * "We dropped coupons. Remove the coupon feature completely" (1.5.175): before the work is called done, the project is searched for
 * the removed thing, and places that still mention it go back to the model once; any left at the end keep the verdict open.
 * Cohere North Mini Code (free, hard suite) deleted coupons.js, ran the tests, which passed, and answered "Nothing left references
 * coupons" while src/cart.js still did; the run ended "✓ Done and checked".
 */
object RemoveCheck {
    private val THING = """(feature|module|functionality|support|system|integration|option|setting|endpoint|route|page|screen|button|code|logic)s?"""
    private val ASKED = listOf(
        // "remove the coupon feature", "get rid of all the referral code", "rip out the newsletter module"
        Regex("""(?i)\b(?:remove|delete|get\s+rid\s+of|rip\s+out|take\s+out|strip\s+out)\s+(?:all\s+(?:of\s+)?)?(?:the\s+)?([A-Za-z][A-Za-z-]{2,})\s+$THING\b"""),
        // "we dropped coupons.", "we no longer support gift cards" is two words and left out on purpose
        Regex("""(?i)\bwe(?:'ve|\s+have)?\s+(?:dropped|removed|no\s+longer\s+(?:support|offer|use))\s+([A-Za-z][A-Za-z-]{2,})\s*(?:[.,;!]|$)"""),
    )
    // "only from cart.js", "just the button": the thing is meant to stay elsewhere
    private val NARROW = Regex("""(?i)\b(only|just)\b|\bfrom\s+(this|that|the)\s+(file|function|page|screen)\b|\bfrom\s+[\w./-]+\.\w{1,5}\b""")
    private val VAGUE = setOf("the", "this", "that", "old", "unused", "dead", "extra", "whole", "entire", "legacy", "broken", "duplicate",
        "duplicated", "same", "other", "new", "every", "each", "any", "some", "all", "that's", "its", "their", "our", "my", "your")
    /** Files that record the past, where the removed thing rightly stays. */
    private val HISTORY = Regex("""(?i)(^|/)(changelog|changes|history|news)[^/]*$""")
    const val SHOWN = 12

    /** The stem of the thing the request removes ("coupon" from "coupons"), or null. */
    fun asked(request: String): String? {
        if (NARROW.containsMatchIn(request)) return null
        for (re in ASKED) {
            val w = re.find(request)?.groupValues?.get(1)?.lowercase() ?: continue
            if (w in VAGUE) continue
            val stem = if (w.length > 4 && w.endsWith("s") && !w.endsWith("ss")) w.dropLast(1) else w
            if (stem.length >= 4) return stem
        }
        return null
    }

    /** "path:line: text" for each line that still mentions [stem] (any case, also inside names like couponDiscount), and how many. */
    fun left(stem: String, paths: Collection<String>, text: (String) -> String?): Pair<List<String>, Int> {
        val out = ArrayList<String>()
        var n = 0
        for (p in paths.sorted()) {
            if (HISTORY.containsMatchIn(p)) continue
            val t = text(p) ?: continue
            if (!t.contains(stem, ignoreCase = true)) continue
            for ((i, l) in t.split("\n").withIndex()) {
                if (!l.contains(stem, ignoreCase = true)) continue
                n++
                if (out.size < SHOWN) out.add("$p:${i + 1}: ${l.trim().take(160)}")
            }
        }
        return out to n
    }

    fun ask(stem: String, lines: List<String>, count: Int): String =
        "The removal is not finished: \"$stem\" is still mentioned in $count place(s):\n" + lines.joinToString("\n") +
            (if (count > lines.size) "\n… and ${count - lines.size} more (search for $stem to see them all)." else "") +
            "\nRemove each one that belongs to the removed feature (code, comments, routes, tests, docs), run the checks again, then give your " +
            "final answer. If one of them must stay, say which and why. Do not say nothing references it unless a search shows that."
}
