package dev.pocketide.app.core

/**
 * A small JSON reader and writer for game files (maps, models, sprites, atlases) that keeps key order (1.5.88). Moved out of
 * the game-cache add-on so the generic game tools do not depend on it.
 */
object JsonText {
    /** Reads JSON: objects keep their key order, whole numbers become Int (or Long when larger), others Double. */
    fun parse(text: String): Any? { val p = JsonReader(text); val v = p.value(); p.end(); return v }
    private class JsonReader(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun end() { ws(); if (i != s.length) throw IllegalArgumentException("extra text after the JSON at $i") }
        fun value(): Any? { ws(); if (i >= s.length) throw IllegalArgumentException("JSON ends early")
            return when (val c = s[i]) {
                '{' -> { i++; val m = LinkedHashMap<String, Any?>(); ws(); if (s[i] == '}') { i++; return m }
                    while (true) { ws(); val k = str(); ws(); expect(':'); m[k] = value(); ws(); if (s[i] == ',') { i++; continue }; expect('}'); return m } ; @Suppress("UNREACHABLE_CODE") m }
                '[' -> { i++; val l = ArrayList<Any?>(); ws(); if (s[i] == ']') { i++; return l }
                    while (true) { l.add(value()); ws(); if (s[i] == ',') { i++; continue }; expect(']'); return l } ; @Suppress("UNREACHABLE_CODE") l }
                '"' -> str()
                't' -> { lit("true"); true }; 'f' -> { lit("false"); false }; 'n' -> { lit("null"); null }
                else -> { val st = i; while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
                    if (st == i) throw IllegalArgumentException("unexpected '$c' in JSON at $i")
                    val t = s.substring(st, i); t.toLongOrNull()?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it } ?: t.toDouble() } } }
        fun lit(w: String) { if (!s.startsWith(w, i)) throw IllegalArgumentException("bad JSON at $i"); i += w.length }
        fun expect(c: Char) { if (i >= s.length || s[i] != c) throw IllegalArgumentException("expected '$c' in JSON at $i"); i++ }
        fun str(): String { expect('"'); val sb = StringBuilder()
            while (true) { if (i >= s.length) throw IllegalArgumentException("JSON string is not closed"); val c = s[i++]
                if (c == '"') return sb.toString()
                if (c == '\\') { when (val e = s[i++]) { 'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }; else -> sb.append(e) } } else sb.append(c) } }
    }

    /** Writes [v] (maps, lists, strings, numbers, booleans, null) as compact JSON. */
    fun write(v: Any?): String = StringBuilder().also { write(it, v) }.toString()
    fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> { sb.append('"'); for (c in v) when {
                c == '"' -> sb.append("\\\""); c == '\\' -> sb.append("\\\\"); c == '\n' -> sb.append("\\n"); c < ' ' -> sb.append(String.format("\\u%04x", c.code)); else -> sb.append(c) }; sb.append('"') }
            is Number, is Boolean -> sb.append(v.toString())
            is Map<*, *> -> { sb.append('{'); var first = true; for ((k, x) in v) { if (!first) sb.append(','); first = false; write(sb, k.toString()); sb.append(':'); write(sb, x) }; sb.append('}') }
            is Iterable<*> -> { sb.append('['); var first = true; for (x in v) { if (!first) sb.append(','); first = false; write(sb, x) }; sb.append(']') }
            else -> write(sb, v.toString())
        }
    }
}
