package dev.pocketide.app.core

/**
 * Pause-and-step debugger for the Preview (1.5.125). See DEBUGGER.md for why it works this way.
 *
 * Android's WebView gives an app no supported way to pause page JavaScript, but a method the app hands to the page with
 * addJavascriptInterface is called synchronously and runs on WebView's own background thread: while that method waits, the page's
 * JavaScript waits too, and the app's screen stays free. So the copy of a file the Preview loads gets, at the start of each line
 * where a statement starts, a cheap check; on a breakpoint line, or after Step, the check calls the app with the line and a
 * snapshot of values, and the app answers Continue, Step or Stop when the person taps. The project's files are never changed.
 *
 * Everything here is plain Kotlin so it can be tested on the JVM (and the rewritten code run under node, see StepDebugTest).
 */
object StepDebug {
    /** A paused page resumes on its own after this long, so a forgotten pause never holds the page for good. */
    const val PAUSE_TIMEOUT_MS = 5 * 60_000L
    const val MAX_LOCALS = 10
    const val MAX_WATCHES = 8
    /** Files bigger than this get only their breakpoint lines; a check on every line would make them too big to load quickly. */
    const val MAX_STEP_CHARS = 400_000
    const val CONTINUE = "continue"
    const val STEP = "step"
    const val STOP = "stop"

    class Plan(val breakpoints: List<Trace.Point>, val watches: List<String>, val error: String? = null)

    /** Same lines as log points ("game/main.js:248 sid, inv.slots"): each line is a breakpoint, and its values are watched. */
    fun parse(spec: String): Plan {
        val t = Trace.parse(spec)
        if (t.error != null) return Plan(emptyList(), emptyList(), t.error)
        if (t.points.isEmpty()) return Plan(emptyList(), emptyList(), "add at least one line to stop at, e.g. game/main.js:248")
        val watches = t.points.flatMap { it.exprs }.distinct()
        // the values are read inside a small arrow function, where await and yield do not parse
        if (watches.any { Regex("""\b(await|yield)\b|__pk""").containsMatchIn(it) }) return Plan(emptyList(), emptyList(), "a watched value cannot use await or yield")
        if (watches.size > MAX_WATCHES) return Plan(emptyList(), emptyList(), "watch at most $MAX_WATCHES values at a time")
        return Plan(t.points, watches)
    }

    // ---- reading the code: where statements start, and which names are visible there ----

    private enum class T { WORD, NUM, STR, PUNCT, REGEX, TPL_OPEN, TPL_EXPR_OPEN, TPL_EXPR_CLOSE, TPL_CLOSE }
    private class Tok(val type: T, val text: String, val line: Int) { var control = false }
    private enum class K { BLOCK, OBJ, CLASS, PAREN, BRACKET, TPL }
    private class Frame(val kind: K, val decl: Boolean = false) {
        val names = ArrayList<String>(); val paramNames = ArrayList<String>(); var params = false; var control = false
    }

    /** What a line looks like where it begins: the innermost bracket, the token before it, and the names declared so far. */
    class LineInfo(val line: Int, val statement: Boolean, val locals: List<String>)

    private val RESERVED = setOf("break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do", "else",
        "export", "extends", "finally", "for", "function", "if", "import", "in", "instanceof", "new", "return", "super", "switch", "this",
        "throw", "try", "typeof", "var", "void", "while", "with", "yield", "let", "static", "enum", "await", "implements", "package",
        "protected", "interface", "private", "public", "null", "true", "false", "undefined", "arguments", "eval", "of", "async", "get", "set")
    private val REGEX_AFTER = setOf("return", "typeof", "instanceof", "in", "of", "new", "delete", "void", "throw", "case", "do", "else", "yield", "await")
    private val CONTROL = setOf("if", "while", "for", "switch", "with")
    private val DECL = setOf("let", "const", "var")
    /** Words after which a new line starts a new statement when it begins with a name (the rule JavaScript uses when semicolons are left out). */
    private val ENDS_VALUE = setOf("this", "true", "false", "null", "undefined", "super")
    // a line that begins like this carries on the line before it
    private val CONTINUES = Regex("""^\s*(?:[.?:)\],}+*/|&=<>%^-]|else\b|catch\b|finally\b|case\b|default\b|in\b|instanceof\b|of\b|while\s*\(.*\)\s*;?\s*$)""")
    private val DIRECTIVE = Regex("""^\s*(['"])use \w+\1\s*;?\s*$""")

    private fun isIdent(s: String) = s.isNotEmpty() && (s[0].isLetter() || s[0] == '_' || s[0] == '$') && s !in RESERVED && !s.startsWith("__pk")

    private fun tokenize(s: String, inside: BooleanArray): List<Tok> {
        val out = ArrayList<Tok>()
        val tpl = ArrayList<Int>()
        var depth = 0; var i = 0; var line = 1; val n = s.length
        fun mark() { if (line < inside.size) inside[line] = true }
        fun regexAllowed(): Boolean {
            val p = out.lastOrNull() ?: return true
            return when (p.type) {
                T.WORD -> p.text in REGEX_AFTER
                T.PUNCT -> p.text != ")" && p.text != "]" && p.text != "++" && p.text != "--"
                T.TPL_EXPR_OPEN, T.TPL_OPEN -> true
                else -> false
            }
        }
        fun template(from: Int): Int {
            var j = from
            while (j < n) {
                val c = s[j]
                if (c == '\\') { if (j + 1 < n && s[j + 1] == '\n') { line++; mark() }; j += 2; continue }
                if (c == '\n') { line++; mark(); j++; continue }
                if (c == '`') { out.add(Tok(T.TPL_CLOSE, "`", line)); return j + 1 }
                if (c == '$' && j + 1 < n && s[j + 1] == '{') { out.add(Tok(T.TPL_EXPR_OPEN, "\${", line)); tpl.add(depth); return j + 2 }
                j++
            }
            return j
        }
        while (i < n) {
            val c = s[i]
            val nx = if (i + 1 < n) s[i + 1] else '\u0000'
            when {
                c == '\n' -> { line++; i++ }
                c.isWhitespace() -> i++
                c == '/' && nx == '/' -> { while (i < n && s[i] != '\n') i++ }
                c == '/' && nx == '*' -> {
                    i += 2
                    while (i < n && !(s[i] == '*' && i + 1 < n && s[i + 1] == '/')) { if (s[i] == '\n') { line++; mark() }; i++ }
                    i += 2
                }
                c == '\'' || c == '"' -> {
                    val at = line; i++
                    while (i < n && s[i] != c) {
                        if (s[i] == '\\') { if (i + 1 < n && s[i + 1] == '\n') { line++; mark() }; i += 2; continue }
                        if (s[i] == '\n') break
                        i++
                    }
                    i++
                    out.add(Tok(T.STR, "s", at))
                }
                c == '`' -> { out.add(Tok(T.TPL_OPEN, "`", line)); i = template(i + 1) }
                c == '/' && regexAllowed() -> {
                    val at = line; i++; var cls = false
                    while (i < n) {
                        val ch = s[i]
                        if (ch == '\\') { i += 2; continue }
                        if (ch == '\n') break
                        if (cls) { if (ch == ']') cls = false } else if (ch == '[') cls = true else if (ch == '/') break
                        i++
                    }
                    i++
                    while (i < n && (s[i].isLetterOrDigit())) i++
                    out.add(Tok(T.REGEX, "r", at))
                }
                c.isLetter() || c == '_' || c == '$' || c == '#' -> {
                    val st = i; i++
                    while (i < n && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '$')) i++
                    out.add(Tok(T.WORD, s.substring(st, i), line))
                }
                c.isDigit() || (c == '.' && nx.isDigit()) -> {
                    i++
                    while (i < n && (s[i].isLetterOrDigit() || s[i] == '.' || s[i] == '_')) i++
                    out.add(Tok(T.NUM, "0", line))
                }
                else -> {
                    val three = if (i + 3 <= n) s.substring(i, i + 3) else ""
                    val two = if (i + 2 <= n) s.substring(i, i + 2) else ""
                    val p = when {
                        three == "..." -> three
                        two == "=>" || two == "++" || two == "--" -> two
                        else -> c.toString()
                    }
                    if (p == "}" && tpl.isNotEmpty() && depth == tpl.last()) {
                        tpl.removeAt(tpl.size - 1)
                        out.add(Tok(T.TPL_EXPR_CLOSE, "}", line))
                        i = template(i + 1)
                        continue
                    }
                    if (p == "{") depth++ else if (p == "}") depth--
                    out.add(Tok(T.PUNCT, p, line))
                    i += p.length
                }
            }
        }
        return out
    }

    /** For each line of [js] (1-based) on which a token begins: whether a statement starts there, and the names visible. */
    fun analyze(js: String): Map<Int, LineInfo> {
        val lines = js.split("\n")
        val inside = BooleanArray(lines.size + 2)
        val toks = tokenize(js, inside)
        val stack = arrayListOf(Frame(K.BLOCK))
        fun block(): Frame = stack.lastOrNull { it.kind == K.BLOCK } ?: stack[0]
        fun popTo(vararg kinds: K): Frame? {
            if (stack.size <= 1 || stack.drop(1).none { it.kind in kinds }) return null
            while (stack.size > 1) { val f = stack.removeAt(stack.size - 1); if (f.kind in kinds) return f }
            return null
        }
        fun visible(): List<String> {
            val seen = LinkedHashSet<String>()
            for (f in stack.asReversed()) if (f.kind == K.BLOCK) for (nm in f.names) { seen.add(nm); if (seen.size >= MAX_LOCALS) return seen.toList() }
            return seen.toList()
        }
        val out = LinkedHashMap<Int, LineInfo>()
        var prev: Tok? = null
        var declNext = false
        var classAt = -1
        var pending: List<String>? = null
        var lastLine = 0
        for ((idx, t) in toks.withIndex()) {
            if (t.line != lastLine) {
                lastLine = t.line
                val ok = !inside[t.line] && stack.last().kind == K.BLOCK && startsHere(lines[t.line - 1], prev)
                out[t.line] = LineInfo(t.line, ok, if (ok) visible() else emptyList())
            }
            val next = toks.getOrNull(idx + 1)
            val carry = pending; pending = null
            when (t.type) {
                T.WORD -> {
                    val top = stack.last()
                    val nt = next?.text
                    if (top.decl && isIdent(t.text) && prev?.text in setOf("{", ",", "[", "...", ":") && nt in setOf(",", "}", "]", "=")) block().names.add(t.text)
                    else if (declNext && isIdent(t.text)) block().names.add(t.text)
                    if (top.kind == K.PAREN && top.params && isIdent(t.text) && prev?.text in setOf("(", ",", "...") && nt in setOf(",", ")", "=")) top.paramNames.add(t.text)
                    if ((prev?.text == "function" || prev?.text == "class" || (prev?.text == "*" && toks.getOrNull(idx - 2)?.text == "function")) && isIdent(t.text)) block().names.add(t.text)
                    if (t.text == "class" && prev?.text != ".") classAt = stack.size
                    declNext = t.text in DECL && prev?.text != "." && next != null && ((next.type == T.WORD && isIdent(next.text)) || nt == "{" || nt == "[")
                }
                T.PUNCT -> when (t.text) {
                    "{" -> {
                        val kind = braceKind(prev, stack.last().kind, classAt == stack.size, declNext)
                        if (kind == K.CLASS) classAt = -1
                        val f = Frame(kind, decl = declNext)
                        if (kind == K.BLOCK && carry != null) f.names.addAll(carry)
                        declNext = false
                        stack.add(f)
                    }
                    "[" -> { stack.add(Frame(K.BRACKET, decl = declNext)); declNext = false }
                    "(" -> {
                        val f = Frame(K.PAREN)
                        f.control = prev?.type == T.WORD && prev.text in CONTROL
                        f.params = !f.control
                        stack.add(f)
                    }
                    ")" -> {
                        val f = popTo(K.PAREN)
                        t.control = f?.control == true
                        if (f != null && f.params && (next?.text == "{" || next?.text == "=>")) pending = f.paramNames
                    }
                    "]" -> popTo(K.BRACKET)
                    "}" -> popTo(K.BLOCK, K.OBJ, K.CLASS)
                    "=>" -> pending = if (prev?.type == T.WORD && isIdent(prev.text)) listOf(prev.text) else if (prev?.text == ")") carry else null
                    else -> {}
                }
                T.TPL_EXPR_OPEN -> stack.add(Frame(K.TPL))
                T.TPL_EXPR_CLOSE -> popTo(K.TPL)
                else -> {}
            }
            prev = t
        }
        return out
    }

    private fun braceKind(prev: Tok?, top: K, classHere: Boolean, decl: Boolean): K {
        if (classHere) return K.CLASS
        if (decl) return K.OBJ
        val p = prev ?: return K.BLOCK
        return when (p.type) {
            T.PUNCT -> if (p.text in setOf(")", "=>", ";", "{", "}")) K.BLOCK else K.OBJ
            T.WORD -> when (p.text) {
                "else", "try", "finally", "do" -> K.BLOCK
                "static" -> if (top == K.CLASS) K.BLOCK else K.OBJ
                else -> K.OBJ
            }
            else -> K.OBJ
        }
    }

    /**
     * Whether a statement may be put in front of [line], given the token before it. Only where it is certain: after ; { or }, or
     * where JavaScript itself ends a statement because the line begins with a name and the line before ended with a value.
     * Never inside an object, a class body, brackets or a template, never in front of 'use strict', never before a line that
     * carries on the one above it.
     */
    private fun startsHere(line: String, prev: Tok?): Boolean {
        val t = line.trimStart()
        if (t.isEmpty() || t.startsWith("//") || t.startsWith("/*") || t.startsWith("#!") || t.startsWith("<")) return false
        if (CONTINUES.containsMatchIn(line) || DIRECTIVE.containsMatchIn(line)) return false
        val p = prev ?: return true
        val wordStart = t[0].isLetter() || t[0] == '_' || t[0] == '$'
        return when (p.type) {
            T.PUNCT -> when (p.text) {
                ";", "{" -> true
                "}" -> !(t.startsWith("(") || t.startsWith("[") || t.startsWith("`"))
                ")" -> !p.control && wordStart
                "]", "++", "--" -> wordStart
                else -> false
            }
            T.WORD -> wordStart && (p.text in ENDS_VALUE || isIdent(p.text))
            T.NUM, T.STR, T.REGEX, T.TPL_CLOSE -> wordStart
            else -> false
        }
    }

    // ---- rewriting the copy the Preview loads ----

    class Result(val text: String, val placed: Map<Int, Int?>, val stepLines: Int)

    /** JavaScript [text] of file [path] with a check in front of each statement line and a stop on each breakpoint. */
    fun instrument(text: String, path: String, breakpoints: List<Trace.Point>, watches: List<String>): Result =
        rewrite(text, path, breakpoints, watches, analyze(text))

    /** An HTML page: only its inline scripts are rewritten (each read on its own, as the browser runs them). */
    fun instrumentHtml(html: String, path: String, breakpoints: List<Trace.Point>, watches: List<String>): Result {
        val info = HashMap<Int, LineInfo>()
        val open = Regex("""<script(\s[^>]*)?>""", RegexOption.IGNORE_CASE)
        val tagLines = HashSet<Int>()
        var from = 0
        while (true) {
            val m = open.find(html, from) ?: break
            val attrs = m.groupValues[1].lowercase()
            val start = m.range.last + 1
            val end = html.indexOf("</script", start, ignoreCase = true).let { if (it < 0) html.length else it }
            tagLines.add(lineAt(html, m.range.first)); tagLines.add(lineAt(html, m.range.last))
            val type = Regex("""type\s*=\s*["']?([^"'\s>]+)""").find(attrs)?.groupValues?.get(1)
            val isJs = !attrs.contains("src=") && (type == null || type.contains("javascript") || type == "module" || type.contains("ecmascript"))
            if (isJs) {
                val masked = StringBuilder(html.length)
                for ((k, ch) in html.withIndex()) masked.append(if (ch == '\n' || k in start until end) ch else ' ')
                info.putAll(analyze(masked.toString()))
            }
            from = maxOf(end, start)
        }
        for (l in tagLines) info[l] = LineInfo(l, false, emptyList())
        return rewrite(html, path, breakpoints, watches, info)
    }

    private fun lineAt(s: String, idx: Int): Int { var n = 1; for (k in 0 until minOf(idx, s.length)) if (s[k] == '\n') n++; return n }

    private fun rewrite(text: String, path: String, breakpoints: List<Trace.Point>, watches: List<String>, info: Map<Int, LineInfo>): Result {
        val lines = text.split("\n").toMutableList()
        val placed = LinkedHashMap<Int, Int?>()
        val stops = HashSet<Int>()
        for (b in breakpoints) {
            // like log points: the breakpoint's own line, or the first line after it where a statement starts
            val at = (b.line..minOf(lines.size, b.line + 6)).firstOrNull { info[it]?.statement == true }
            placed[b.id] = at
            if (at != null) stops.add(at)
        }
        val every = text.length <= MAX_STEP_CHARS
        val file = MiniJson.quote(path)
        var stepLines = 0
        for ((n, li) in info) {
            if (!li.statement || n > lines.size) continue
            val stop = n in stops
            if (!stop && !every) continue
            val snap = snapshot(watches, li.locals)
            val call = if (stop) "typeof __pkD==='object'&&__pkD.h($file,$n,1,$snap);" else "typeof __pkD==='object'&&__pkD.s&&__pkD.h($file,$n,0,$snap);"
            val l = lines[n - 1]
            val indent = l.takeWhile { it == ' ' || it == '\t' }
            lines[n - 1] = indent + call + l.substring(indent.length)
            stepLines++
        }
        return Result(lines.joinToString("\n"), placed, stepLines)
    }

    /** An arrow function (so `this` is the page's own) that reads each watched value and each visible name, each on its own. */
    private fun snapshot(watches: List<String>, locals: List<String>): String {
        val sb = StringBuilder("()=>{var __pkW=[],__pkL={};")
        for ((i, w) in watches.withIndex()) sb.append("try{__pkW[$i]=($w)}catch(__pkE){__pkW[$i]={__pkErr:String(__pkE)}}")
        // a name declared further down (or not at all) throws when read; it is simply left out
        for (nm in locals) sb.append("try{__pkL.").append(nm).append("=").append(nm).append("}catch(__pkE){}")
        return sb.append("return {w:__pkW,l:__pkL}}").toString()
    }

    /**
     * Put at the top of the page. __pkD.h asks the app (window.PocketDebug.pause) only on a breakpoint or while stepping, with the
     * values made safe to send: depth, length and count limits, loops marked [circular], page elements shown as <tag#id>.
     */
    const val RUNTIME = "<script>(function(){if(window.__pkD)return;var D=window.__pkD={s:0,off:0,n:0};" +
        "function v(x,d,seen){if(++D.n>300)return '…';try{if(x===undefined)return 'undefined';if(x===null)return null;var t=typeof x;" +
        "if(t==='function')return '[function '+(x.name||'')+']';if(t==='string')return x.length>120?x.slice(0,120)+'…':x;" +
        "if(t==='number')return isFinite(x)?x:String(x);if(t==='bigint')return String(x)+'n';if(t!=='object')return t==='boolean'?x:String(x);" +
        "if(x.__pkErr)return 'error: '+x.__pkErr;if(seen.indexOf(x)>=0)return '[circular]';if(x===window)return '[window]';" +
        "if(x.nodeType===1)return '<'+x.tagName.toLowerCase()+(x.id?'#'+x.id:'')+'>';if(x instanceof Date)return 'Date '+(isNaN(x)?'invalid':x.toISOString());" +
        "if(d>=3)return Array.isArray(x)?'[array of '+x.length+']':'{…}';seen.push(x);var r,i,k;" +
        "if(Array.isArray(x)||ArrayBuffer.isView(x)){r=[];for(i=0;i<Math.min(x.length,20);i++)r.push(v(x[i],d+1,seen));if(x.length>20)r.push('… '+(x.length-20)+' more')}" +
        "else if(typeof Map!=='undefined'&&x instanceof Map){r={'(size)':x.size};i=0;x.forEach(function(y,key){if(i++<20)r[String(key)]=v(y,d+1,seen)})}" +
        "else if(typeof Set!=='undefined'&&x instanceof Set){r=[];x.forEach(function(y){if(r.length<20)r.push(v(y,d+1,seen))})}" +
        "else{r={};k=Object.keys(x);for(i=0;i<Math.min(k.length,20);i++){try{r[k[i]]=v(x[k[i]],d+1,seen)}catch(e){r[k[i]]='(unreadable)'}}if(k.length>20)r['…']=(k.length-20)+' more'}" +
        "seen.pop();return r}catch(e){return '(unreadable)'}}" +
        "D.h=function(f,n,bp,snap){if(D.off||!(bp||D.s))return;var b=window.PocketDebug;if(!b||!b.pause){D.off=1;return}" +
        "var out;D.n=0;try{var r=snap(),w=[],l={},k;for(var i=0;i<r.w.length;i++)w.push(v(r.w[i],0,[]));for(k in r.l)l[k]=v(r.l[k],0,[]);out=JSON.stringify({w:w,l:l})}catch(e){out='{}'}" +
        "var c;try{c=String(b.pause(f,n,out))}catch(e){c='continue'}" +
        "if(c==='step')D.s=1;else if(c==='stop'){D.off=1;D.s=0}else D.s=0};" +
        "})();</script>"

    /** [html] with [RUNTIME] first in its head, on the same line, so line numbers stay the same. */
    fun withRuntime(html: String): String {
        val i = Regex("""<head[^>]*>""", RegexOption.IGNORE_CASE).find(html)
        return if (i != null) html.substring(0, i.range.last + 1) + RUNTIME + html.substring(i.range.last + 1) else RUNTIME + html
    }

    // ---- what the panel shows ----

    /** The values sent at a pause, as lines for the panel: the watched values first, then the names visible at that line. */
    fun describe(values: String, watches: List<String>): String {
        val o = try { MiniJson.obj(MiniJson.parse(values)) } catch (e: Exception) { null } ?: return "(no values could be read)"
        val sb = StringBuilder()
        val w = MiniJson.list(o["w"]).orEmpty()
        if (watches.isNotEmpty()) {
            sb.append("Watched:")
            for ((i, e) in watches.withIndex()) sb.append("\n  ").append(e).append(" = ").append(show(w.getOrNull(i)))
        }
        val l = MiniJson.obj(o["l"]).orEmpty()
        if (l.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("Names here:")
            for ((k, v) in l) sb.append("\n  ").append(k).append(" = ").append(show(v))
        }
        return if (sb.isEmpty()) "(no values to show here)" else sb.toString()
    }

    private fun show(v: Any?): String = (if (v is String) v else MiniJson.write(v)).let { if (it.length > 400) it.take(400) + "…" else it }

    /** The line where the page stopped, with one line either side, the stopped one marked. */
    fun codeAround(text: String?, line: Int): String {
        val lines = text?.split("\n") ?: return "(file not readable)"
        return (maxOf(1, line - 1)..minOf(lines.size, line + 1)).joinToString("\n") { n ->
            (if (n == line) "▶ " else "  ") + n.toString().padStart(4) + "  " + lines[n - 1].trimEnd().let { if (it.length > 160) it.take(160) + "…" else it }
        }
    }

    // ---- the wait itself ----

    /**
     * Holds the page's JavaScript while it is paused. [pause] is called on WebView's bridge thread and waits for [answer] (a tap on
     * the screen), [close] (the panel or the preview closed), or [timeoutMs]. It refuses to wait on the screen's own thread, which
     * would freeze the app. Each pause has a number, so a late or doubled tap cannot resume a later pause.
     */
    class Gate(private val timeoutMs: Long = PAUSE_TIMEOUT_MS, private val onUiThread: () -> Boolean = { false }) {
        private val lock = Object()
        private var seq = 0
        private var reply: String? = null
        @Volatile var closed = false
            private set
        @Volatile var timedOut = 0
            private set

        /** Calls [show] with this pause's number, then waits; returns continue, step or stop. */
        fun pause(show: (Int) -> Unit): String {
            if (closed) return STOP
            if (onUiThread()) return CONTINUE
            val mine: Int
            synchronized(lock) { seq++; mine = seq; reply = null }
            try { show(mine) } catch (e: Exception) { return CONTINUE }
            val end = System.currentTimeMillis() + timeoutMs
            synchronized(lock) {
                while (reply == null && !closed) {
                    val left = end - System.currentTimeMillis()
                    if (left <= 0) { timedOut++; return CONTINUE }
                    try { lock.wait(left) } catch (e: InterruptedException) { return CONTINUE }
                }
                if (closed) return STOP
                return reply ?: CONTINUE
            }
        }

        /** The person's choice for pause number [pauseNo]; false if that pause is no longer the one waiting. */
        fun answer(pauseNo: Int, command: String): Boolean = synchronized(lock) {
            if (closed || pauseNo != seq || reply != null) return false
            reply = if (command == STEP || command == STOP) command else CONTINUE
            lock.notifyAll()
            true
        }

        /** Lets a waiting page go (it stops debugging) and makes every later pause return at once. */
        fun close() = synchronized(lock) { closed = true; lock.notifyAll() }
    }
}
