package dev.pocketide.app.core

/**
 * A tool call a model wrote as text in a chat that uses native tool calling. Llama 3.3 on Cloudflare often writes
 * `read_file(path="app.js")`, `{"type": "function", "name": "read_file", "parameters": {…}}` or `<function=read_file>{…}</function>`
 * instead of calling the tool, and asking it to call the tool for real rarely helps (hard suite, 1.5.89: 1 of 13). The first
 * such call in a reply is taken as if the model had called it.
 *
 * Only tools the app knows are taken, and only a call that parses completely; anything else is left to the ask in
 * Tools.TOOL_TEXT_ASK. Calls in Python form inside fenced code are code to read, not calls, so they are skipped.
 */
object TextCalls {
    private val FENCE = Regex("```[\\s\\S]*?(?:```|\\z)")
    // a call in Python form at the start of a line (after a bullet, a quote mark or a backtick)
    private val PY_START = Regex("""(?m)^[ \t]*(?:[-*>]\s*|\d+\.\s+)?[`\[]?([a-z][a-z0-9_]*)\s*\(""")
    private val FN_JSON = Regex("""<function=([A-Za-z_][\w.\-]*)>\s*(?=\{)""")
    private val HARMONY = Regex("""\bto=functions\.([A-Za-z_][\w.\-]*)""")
    private val ARG_KEYS = listOf("parameters", "arguments", "args", "input")
    private val NAME_KEYS = listOf("name", "function", "tool", "tool_name")
    private val KEY = Regex("""([A-Za-z_]\w*)\s*=(?!=)""")
    private val NUM = Regex("""-?\d+(\.\d+)?""")
    private val WORD = Regex("""(True|False|None)\b""")

    /** [re] matched at exactly [i], or null. */
    private fun at(re: Regex, s: String, i: Int): java.util.regex.Matcher? {
        val m = re.toPattern().matcher(s)
        m.region(i, s.length)
        return if (m.lookingAt()) m else null
    }

    /** A call found in a reply, and where it starts. */
    class Hit(val call: ToolCall, val at: Int)

    /** The first call to a known tool written in [text], or null. [types] gives a tool's parameters in order, null for an unknown tool. */
    fun first(text: String, types: (String) -> Map<String, String>?): Hit? {
        val found = ArrayList<Pair<Int, ToolCall>>()
        python(text, types)?.let { found.add(it) }
        json(text, types)?.let { found.add(it) }
        tagged(text, types)?.let { found.add(it) }
        return found.minByOrNull { it.first }?.let { Hit(it.second, it.first) }
    }

    private fun call(name: String, args: Map<String, Any?>): ToolCall =
        ToolCall("textcall_${System.nanoTime()}", name, MiniJson.write(args))

    // ---- name(arg, key=value) ----

    private fun python(text: String, types: (String) -> Map<String, String>?): Pair<Int, ToolCall>? {
        val masked = FENCE.replace(text) { m -> " ".repeat(m.value.length) }
        for (m in PY_START.findAll(masked)) {
            val name = m.groupValues[1]
            val params = types(name) ?: continue
            val parsed = PyArgs(text, m.range.last + 1).parse() ?: continue
            val args = LinkedHashMap<String, Any?>()
            val order = params.keys.toList()
            parsed.positional.forEachIndexed { i, v -> if (i < order.size) args[order[i]] = v }
            if (parsed.positional.size > order.size) continue
            args.putAll(parsed.named)
            return m.range.first to call(name, args)
        }
        return null
    }

    private class PyResult(val positional: List<Any?>, val named: Map<String, Any?>)

    /** Reads Python call arguments from just after "(" to the closing ")": strings, numbers, True/False/None, and JSON lists and objects. */
    private class PyArgs(val s: String, var i: Int) {
        fun parse(): PyResult? {
            val pos = ArrayList<Any?>()
            val named = LinkedHashMap<String, Any?>()
            ws()
            if (peek() == ')') return PyResult(pos, named)
            while (i < s.length) {
                ws()
                val key = at(KEY, s, i)
                if (key != null) i = key.end()
                ws()
                val v = value() ?: return null
                if (key != null) named[key.group(1)] = v.value else { if (named.isNotEmpty()) return null; pos.add(v.value) }
                ws()
                when (peek()) {
                    ',' -> { i++; ws(); if (peek() == ')') { i++; return PyResult(pos, named) } }
                    ')' -> { i++; return PyResult(pos, named) }
                    else -> return null
                }
            }
            return null
        }

        private class V(val value: Any?)

        private fun peek(): Char? = if (i < s.length) s[i] else null
        private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        private fun value(): V? {
            val c = peek() ?: return null
            return when {
                c == '"' || c == '\'' -> str()
                (c == 'r' || c == 'f') && (s.getOrNull(i + 1) == '"' || s.getOrNull(i + 1) == '\'') -> { val raw = c == 'r'; i++; str(raw) }
                c == '{' || c == '[' -> structure()
                s.startsWith("True", i) -> { i += 4; V(true) }
                s.startsWith("False", i) -> { i += 5; V(false) }
                s.startsWith("None", i) -> { i += 4; V(null) }
                c == '-' || c.isDigit() -> {
                    val m = at(NUM, s, i) ?: return null
                    i = m.end()
                    V(if (m.group(1) == null) m.group().toLongOrNull() else m.group().toDoubleOrNull())
                }
                else -> null
            }
        }

        private fun str(raw: Boolean = false): V? {
            val q = s[i]
            val triple = s.startsWith("$q$q$q", i)
            val end = if (triple) "$q$q$q" else q.toString()
            i += end.length
            val sb = StringBuilder()
            while (i < s.length) {
                if (s.startsWith(end, i)) { i += end.length; return V(sb.toString()) }
                val c = s[i]
                if (c == '\n' && !triple) return null
                if (c == '\\' && i + 1 < s.length) {
                    val n = s[i + 1]
                    if (raw) { sb.append(c).append(n); i += 2; continue }
                    when (n) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        '\\' -> sb.append('\\'); '"' -> sb.append('"'); '\'' -> sb.append('\''); '\n' -> {}
                        else -> sb.append(c).append(n)
                    }
                    i += 2; continue
                }
                sb.append(c); i++
            }
            return null
        }

        // a list or dict: JSON as written, or Python literals turned into JSON
        private fun structure(): V? {
            val end = balanced(s, i) ?: return null
            val raw = s.substring(i, end)
            val parsed = parseJson(raw) ?: parseJson(pyToJson(raw)) ?: return null
            i = end
            return V(parsed.value)
        }
    }

    private class Parsed(val value: Any?)

    private fun parseJson(raw: String): Parsed? = try { Parsed(MiniJson.parse(raw)) } catch (e: Exception) { null }

    // 'a' -> "a", True -> true, None -> null, outside of strings
    private fun pyToJson(raw: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '"' || c == '\'') {
                val q = c
                sb.append('"'); i++
                while (i < raw.length && raw[i] != q) {
                    val x = raw[i]
                    if (x == '\\' && i + 1 < raw.length) {
                        val n = raw[i + 1]
                        if (n == '\'') sb.append('\'') else sb.append(x).append(n)
                        i += 2; continue
                    }
                    if (x == '"') sb.append("\\\"") else sb.append(x)
                    i++
                }
                sb.append('"'); i++; continue
            }
            val word = at(WORD, raw, i)
            if (word != null) { sb.append(when (word.group()) { "True" -> "true"; "False" -> "false"; else -> "null" }); i = word.end(); continue }
            sb.append(c); i++
        }
        return sb.toString()
    }

    /** The index just past the bracket that closes the one at [start], skipping brackets inside strings; null when it never closes. */
    private fun balanced(s: String, start: Int): Int? {
        var depth = 0
        var i = start
        var quote: Char? = null
        while (i < s.length) {
            val c = s[i]
            if (quote != null) {
                if (c == '\\') { i += 2; continue }
                if (c == quote) quote = null
            } else when (c) {
                '"', '\'' -> quote = c
                '{', '[' -> depth++
                '}', ']' -> { depth--; if (depth == 0) return i + 1 }
            }
            i++
        }
        return null
    }

    // ---- {"name": …, "parameters": {…}} ----

    private fun json(text: String, types: (String) -> Map<String, String>?): Pair<Int, ToolCall>? {
        var from = 0
        var tries = 0
        while (tries++ < 40) {
            val start = text.indexOf('{', from)
            if (start < 0) return null
            from = start + 1
            val head = text.substring(start, minOf(text.length, start + 400))
            if (NAME_KEYS.none { head.contains("\"$it\"") }) continue
            val end = balanced(text, start) ?: continue
            val obj = MiniJson.obj(parseJson(text.substring(start, end))?.value) ?: continue
            fromObject(obj, types)?.let { return start to it }
        }
        return null
    }

    private fun fromObject(o: Map<String, Any?>, types: (String) -> Map<String, String>?): ToolCall? {
        MiniJson.obj(o["function"])?.let { f -> return fromObject(f, types) }
        // 1.5.176: {"function": "read_file", "arguments": {…}} and {"tool": "read_file", …}, as qwen2.5-coder 1.5B writes them
        val name = NAME_KEYS.firstNotNullOfOrNull { o[it] as? String } ?: return null
        if (types(name) == null) return null
        val key = ARG_KEYS.firstOrNull { o.containsKey(it) } ?: return null
        // only the keys a tool call has: a config file that happens to hold "name" is not a call
        if (o.keys.any { it !in setOf("type", "id", "call_id") + NAME_KEYS + ARG_KEYS }) return null
        val args = when (val a = o[key]) {
            is Map<*, *> -> MiniJson.obj(a)
            is String -> MiniJson.obj(parseJson(a)?.value)
            else -> null
        } ?: return null
        return call(name, args)
    }

    // ---- <function=name>{…}</function> (Llama's own format) and to=functions.name {…} (gpt-oss) ----

    private fun tagged(text: String, types: (String) -> Map<String, String>?): Pair<Int, ToolCall>? {
        val hits = (FN_JSON.findAll(text) + HARMONY.findAll(text)).sortedBy { it.range.first }
        for (m in hits) {
            val name = m.groupValues[1]
            if (types(name) == null) continue
            val start = text.indexOf('{', m.range.last + 1).takeIf { it >= 0 && it - m.range.last < 80 } ?: continue
            val end = balanced(text, start) ?: continue
            val args = MiniJson.obj(parseJson(text.substring(start, end))?.value) ?: continue
            return m.range.first to call(name, args)
        }
        if (text.contains("<function=") && text.contains("<parameter=")) {
            val p = TextTools.parse(text, types)
            p.calls.firstOrNull { types(it.name) != null }?.let { return text.indexOf("<function=") to it }
        }
        return null
    }
}
