package dev.pocketide.app.core

data class DiffOp(val tag: Char, val line: String)

/** One row of a compact diff: a changed or nearby line, or a note that some unchanged lines were skipped. */
data class DiffRow(val tag: Char, val text: String, val skipped: Int = 0)

object Diff {
    /** Line diff by longest common subsequence. Returns null when the change is too large to compare. */
    fun lines(a: String, b: String): List<DiffOp>? {
        val x = if (a.isEmpty()) emptyList() else a.removeSuffix("\n").split("\n")
        val y = if (b.isEmpty()) emptyList() else b.removeSuffix("\n").split("\n")
        val n = x.size
        val m = y.size
        if (n.toLong() * m > 300000L) return null
        val w = m + 1
        val dp = IntArray((n + 1) * w)
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            dp[i * w + j] = if (x[i] == y[j]) dp[(i + 1) * w + j + 1] + 1 else maxOf(dp[(i + 1) * w + j], dp[i * w + j + 1])
        }
        val out = ArrayList<DiffOp>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            if (x[i] == y[j]) { out.add(DiffOp(' ', x[i])); i++; j++ }
            else if (dp[(i + 1) * w + j] >= dp[i * w + j + 1]) { out.add(DiffOp('-', x[i])); i++ }
            else { out.add(DiffOp('+', y[j])); j++ }
        }
        while (i < n) { out.add(DiffOp('-', x[i])); i++ }
        while (j < m) { out.add(DiffOp('+', y[j])); j++ }
        return out
    }

    /** Keeps changed lines and one line of context around them; the rest becomes "N unchanged lines" rows. */
    fun compact(ops: List<DiffOp>): List<DiffRow> {
        val keep = BooleanArray(ops.size)
        ops.forEachIndexed { k, o ->
            if (o.tag != ' ') for (q in k - 1..k + 1) if (q >= 0 && q < ops.size) keep[q] = true
        }
        val rows = ArrayList<DiffRow>()
        var skipped = 0
        ops.forEachIndexed { k, o ->
            if (!keep[k]) { skipped++; return@forEachIndexed }
            if (skipped > 0) { rows.add(DiffRow('~', "", skipped)); skipped = 0 }
            rows.add(DiffRow(o.tag, o.line))
        }
        if (skipped > 0) rows.add(DiffRow('~', "", skipped))
        return rows
    }
}
