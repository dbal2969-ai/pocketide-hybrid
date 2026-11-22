package dev.pocketide.app.core

/**
 * Log points (1.5.112): see what values a running page has at chosen lines without editing its files, as a debugger's log points
 * do. "game/main.js:248 sid, inv.slots" records sid and inv.slots each time line 248 is about to run. The files are changed only in
 * the copy the hidden browser loads; a recorder put at the top of the page collects up to [HITS] values per point, and errors are
 * kept with their stack. Used by look_at_preview (trace=…) and the Preview tab's Debug button.
 */
object Trace {
    const val HITS = 10
    const val MAX_POINTS = 12

    class Point(val id: Int, val path: String, val line: Int, val exprs: List<String>)
    class Plan(val points: List<Point>, val error: String? = null)

    /** "path:line expr, expr; path:line expr" (or one per line). An expression list may be empty (only the hit count is kept). */
    fun parse(spec: String): Plan {
        val parts = spec.split(Regex("""[;\n]""")).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size > MAX_POINTS) return Plan(emptyList(), "at most $MAX_POINTS log points at a time")
        val out = ArrayList<Point>()
        for ((i, p) in parts.withIndex()) {
            val m = Regex("""^([^\s:]+\.(?:m?js|html?)):(\d+)\s*(.*)$""", RegexOption.IGNORE_CASE).find(p)
                ?: return Plan(emptyList(), "\"$p\" is not a log point; write file.js:LINE then the values to record, e.g. game/main.js:248 sid, inv.slots")
            val exprs = splitExprs(m.groupValues[3])
            if (exprs.any { it.contains("\n") || it.length > 200 }) return Plan(emptyList(), "keep each value to one short expression")
            out.add(Point(i, m.groupValues[1].removePrefix("./").removePrefix("/"), m.groupValues[2].toInt(), exprs))
        }
        return Plan(out)
    }

    /** "a, b(1, 2), c[0]" split at top-level commas. */
    fun splitExprs(s: String): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder(); var depth = 0; var q: Char? = null
        for (c in s) {
            if (q != null) { cur.append(c); if (c == q) q = null; continue }
            when (c) {
                '\'', '"', '`' -> { q = c; cur.append(c) }
                '(', '[', '{' -> { depth++; cur.append(c) }
                ')', ']', '}' -> { depth--; cur.append(c) }
                ',' -> if (depth == 0) { out.add(cur.toString().trim()); cur.clear() } else cur.append(c)
                else -> cur.append(c)
            }
        }
        if (cur.isNotBlank()) out.add(cur.toString().trim())
        return out.filter { it.isNotEmpty() }
    }

    private val CONTINUES = Regex("""^\s*(?:[.?:)\],}+*/|&=<>-]|else\b|catch\b|finally\b|while\s*\(.*\)\s*;?\s*$)""")
    private val ENDS_STATEMENT = Regex("""[;{}]\s*(?://.*)?$""")
    private fun codeLine(l: String): Boolean = l.isNotBlank() && !l.trimStart().startsWith("//") && !l.trimStart().startsWith("*") && !l.trimStart().startsWith("/*")

    /**
     * [text] with a recorder call at the start of each point's line, or of the first line after it where a statement can start
     * (the line before ends with ; { or }). Returns the new text and, per point id, the line used (null when none was found).
     */
    fun instrument(text: String, points: List<Point>): Pair<String, Map<Int, Int?>> {
        val lines = text.split("\n").toMutableList()
        val used = LinkedHashMap<Int, Int?>()
        val at = HashMap<Int, MutableList<Point>>()
        for (p in points) {
            var placed: Int? = null
            var n = p.line
            while (n <= minOf(lines.size, p.line + 6)) {
                val prev = (n - 2 downTo 0).map { lines[it] }.firstOrNull { codeLine(it) }
                val ok = (prev == null || ENDS_STATEMENT.containsMatchIn(prev.trimEnd())) && !CONTINUES.containsMatchIn(lines[n - 1]) && codeLine(lines[n - 1])
                if (ok) { placed = n; break }
                n++
            }
            used[p.id] = placed
            if (placed != null) at.getOrPut(placed) { ArrayList() }.add(p)
        }
        for ((n, ps) in at) {
            val calls = ps.joinToString("") { p -> "typeof __pkT==='function'&&__pkT(${p.id},[" + p.exprs.joinToString(",") { e -> "()=>{try{return ($e)}catch(_e){return {__pkErr:String(_e)}}}" } + "]);" }
            val l = lines[n - 1]
            val indent = l.takeWhile { it == ' ' || it == '\t' }
            lines[n - 1] = indent + calls + l.substring(indent.length)
        }
        return lines.joinToString("\n") to used
    }

    /** Put at the top of the page: the recorder, and errors kept with their stack. */
    const val RECORDER = "<script>(function(){var T=window.__pkTrace={hits:{},count:{},errors:[]};" +
        "function v(x,d){try{if(x===undefined)return 'undefined';if(x===null)return null;var t=typeof x;" +
        "if(t==='function')return '[function '+(x.name||'')+']';if(t==='string')return x.length>200?x.slice(0,200)+'…':x;if(t!=='object')return x;" +
        "if(x.__pkErr)return 'error: '+x.__pkErr;if(x.nodeType===1)return '<'+x.tagName.toLowerCase()+(x.id?'#'+x.id:'')+(x.className&&typeof x.className==='string'?'.'+x.className.trim().split(/\\s+/).join('.'):'')+'>';" +
        "if(d>=2)return Array.isArray(x)?'[array of '+x.length+']':'{…}';" +
        "if(Array.isArray(x)){var a=x.slice(0,20).map(function(y){return v(y,d+1)});if(x.length>20)a.push('… '+(x.length-20)+' more');return a}" +
        "var o={},k=Object.keys(x).slice(0,20);for(var i=0;i<k.length;i++)o[k[i]]=v(x[k[i]],d+1);return o}catch(e){return '(unreadable)'}}" +
        "window.__pkT=function(id,fs){T.count[id]=(T.count[id]||0)+1;var h=T.hits[id]=T.hits[id]||[];if(h.length>=" + HITS + ")return;" +
        "h.push(fs.map(function(f){var r;try{r=f()}catch(e){r={__pkErr:String(e)}}return v(r,0)}))};" +
        "window.addEventListener('error',function(e){if(T.errors.length<10)T.errors.push(String(e.message)+(e.error&&e.error.stack?'\\n'+String(e.error.stack).split('\\n').slice(0,6).join('\\n'):''))});" +
        "window.addEventListener('unhandledrejection',function(e){if(T.errors.length<10)T.errors.push('Unhandled promise rejection: '+String(e.reason&&e.reason.stack||e.reason))});" +
        "})();</script>"

    fun withRecorder(html: String): String {
        val i = Regex("""<head[^>]*>""", RegexOption.IGNORE_CASE).find(html)
        return if (i != null) html.substring(0, i.range.last + 1) + RECORDER + html.substring(i.range.last + 1) else RECORDER + html
    }

    const val RESULTS = "(function(){return JSON.stringify(window.__pkTrace||null)})()"

    /** The report section: each point with how often it ran and the values seen, then the errors with their stacks. */
    fun summary(raw: Map<String, Any?>?, points: List<Point>, placed: Map<Int, Int?>): String {
        val sb = StringBuilder("Log points:")
        val hits = MiniJson.obj(raw?.get("hits")).orEmpty(); val count = MiniJson.obj(raw?.get("count")).orEmpty()
        for (p in points) {
            val where = "${p.path}:${p.line}"
            val pl = placed[p.id]
            sb.append("\n- ").append(where).append(if (p.exprs.isEmpty()) "" else " (" + p.exprs.joinToString(", ") + ")")
            if (!placed.containsKey(p.id)) { sb.append(": the page did not load this file"); continue }
            if (pl == null) { sb.append(": no line near it where a statement starts; pick the first line of a statement"); continue }
            if (pl != p.line) sb.append(" [placed at line $pl]")
            val n = (count[p.id.toString()] as? Number)?.toInt() ?: 0
            if (n == 0) { sb.append(": never ran"); continue }
            sb.append(": ran $n time${if (n == 1) "" else "s"}")
            val list = MiniJson.list(hits[p.id.toString()]).orEmpty()
            for ((i, h) in list.withIndex()) {
                val vals = MiniJson.list(h).orEmpty()
                sb.append("\n    ").append(i + 1).append(". ").append(if (p.exprs.isEmpty()) "(reached)" else p.exprs.indices.joinToString("; ") { k -> p.exprs[k] + " = " + MiniJson.write(vals.getOrNull(k)).take(300) })
            }
            if (n > list.size) sb.append("\n    … ${n - list.size} more times")
        }
        val errs = MiniJson.list(raw?.get("errors")).orEmpty().map { it.toString() }
        if (errs.isNotEmpty()) sb.append("\nErrors with their stack:\n").append(errs.joinToString("\n") { "  " + it.replace("\n", "\n    ") })
        return sb.toString()
    }
}
