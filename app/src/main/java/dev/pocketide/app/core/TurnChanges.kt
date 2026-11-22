package dev.pocketide.app.core

enum class TurnChangeKind(val label: String) { NEW("new"), EDITED("edited"), DELETED("deleted"), SAME("same") }

/** What an assistant turn did to the files, for the "What changed" card. */
object TurnChanges {
    /** [before] and [after] are the file's bytes, null when the file did not exist. */
    fun kind(before: ByteArray?, after: ByteArray?): TurnChangeKind = when {
        before == null && after == null -> TurnChangeKind.SAME
        before == null -> TurnChangeKind.NEW
        after == null -> TurnChangeKind.DELETED
        before.contentEquals(after) -> TurnChangeKind.SAME
        else -> TurnChangeKind.EDITED
    }

    /** Heading for the card, e.g. "What changed: 2 new, 1 edited". Files that ended up unchanged are left out of the count. */
    fun summary(kinds: List<TurnChangeKind>): String {
        val parts = ArrayList<String>()
        for (k in listOf(TurnChangeKind.NEW, TurnChangeKind.EDITED, TurnChangeKind.DELETED)) {
            val n = kinds.count { it == k }
            if (n > 0) parts.add("$n ${k.label}")
        }
        return if (parts.isEmpty()) "No files changed" else "What changed: " + parts.joinToString(", ")
    }

    /** Lines added and removed between two texts, as "+12 −3". Null when the change is too large to count. */
    fun lineDelta(before: String?, after: String?): String? {
        val ops = Diff.lines(before ?: "", after ?: "") ?: return null
        val rows = Diff.compact(ops)
        val plus = rows.count { it.tag == '+' }
        val minus = rows.count { it.tag == '-' }
        return if (plus == 0 && minus == 0) null else "+$plus −$minus"
    }
}
