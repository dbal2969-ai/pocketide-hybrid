package dev.pocketide.app.addon.osrs

import dev.pocketide.app.core.*

/**
 * Reads data tables out of Java and Kotlin source code: every enum becomes a table whose columns are its constructor's
 * parameter names and whose rows are its constants, with each argument turned into plain data. Game servers keep most of
 * their rules this way (recipes, levels, experience, tools, requirements), so this turns a server's source into JSON that a
 * game can load, without running or copying the code. Works on any Java/Kotlin project, not only game servers.
 *
 * Values: numbers, strings, booleans and null as themselves; `Items.LOGS_1511`-style constants (the id after the last
 * underscore) as numbers, with the column's kind noted (item, npc, object, anim, ...); `Skills.COOKING` as "cooking";
 * `Item(id, amount)` as {item, amount}; arrays and lists as lists; `a to b` as [a, b]; other constructor calls as
 * {call, args}; references to other enum constants as "Enum.NAME"; constants declared in the same file are resolved;
 * simple arithmetic is worked out. Anything else is kept as its source text.
 */
object CodeTables {
    class Table(val name: String, val file: String, val columns: List<String>, val rows: List<Map<String, Any?>>, val kinds: Map<String, String>)

    // ------------------------------------------------------------------ text helpers

    /** The source with comments removed (strings kept as they are). */
    fun strip(s: String): String {
        val b = StringBuilder(s.length); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '"' || c == '\'') { val e = stringEnd(s, i); b.append(s, i, e); i = e; continue }
            if (c == '/' && i + 1 < s.length && s[i + 1] == '/') { while (i < s.length && s[i] != '\n') i++; continue }
            if (c == '/' && i + 1 < s.length && s[i + 1] == '*') { val e = s.indexOf("*/", i + 2); i = if (e < 0) s.length else e + 2; b.append(' '); continue }
            b.append(c); i++
        }
        return b.toString()
    }

    /** Index just after the string or char literal starting at [i]. */
    fun stringEnd(s: String, i: Int): Int {
        val q = s[i]
        if (q == '"' && s.startsWith("\"\"\"", i)) { val e = s.indexOf("\"\"\"", i + 3); return if (e < 0) s.length else e + 3 }
        var j = i + 1
        while (j < s.length && s[j] != q) {
            if (s[j] == '\\') j++
            else if (q == '"' && s[j] == '$' && j + 1 < s.length && s[j + 1] == '{') { val e = close(s, j + 1); if (e > 0) j = e }   // "${ ... }" may hold quotes
            else if (s[j] == '\n' && q == '\'') return j
            j++
        }
        return minOf(j + 1, s.length)
    }

    /** Index of the bracket closing the one at [open] (any of ( [ {), or -1. */
    fun close(s: String, open: Int): Int {
        var depth = 0; var i = open
        while (i < s.length) {
            when (s[i]) {
                '"', '\'' -> { i = stringEnd(s, i); continue }
                '(', '[', '{' -> depth++
                ')', ']', '}' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return -1
    }

    /** First index of [c] outside brackets and strings, or -1. */
    fun topIndex(s: String, c: Char, from: Int = 0): Int {
        var depth = 0; var i = from
        while (i < s.length) {
            val ch = s[i]
            when {
                ch == '"' || ch == '\'' -> { i = stringEnd(s, i); continue }
                ch == '(' || ch == '[' || ch == '{' -> depth++
                ch == ')' || ch == ']' || ch == '}' -> depth--
                ch == c && depth == 0 -> return i
            }
            i++
        }
        return -1
    }

    /** [s] split at [sep] outside brackets and strings. */
    fun splitTop(s: String, sep: Char = ','): List<String> {
        val out = ArrayList<String>(); var start = 0
        while (true) { val k = topIndex(s, sep, start); if (k < 0) { out.add(s.substring(start)); return out }; out.add(s.substring(start, k)); start = k + 1 }
    }

    // ------------------------------------------------------------------ constants and values

    private val KT_CONST = Regex("""(?m)^[ \t]*(?:(?:private|public|internal|protected|const|override|final)\s+)*val\s+([A-Za-z_]\w*)\s*(?::\s*[\w.<>?]+\s*)?=\s*([^\n;]+)""")
    private val JAVA_CONST = Regex("""(?m)^[ \t]*(?:(?:private|public|protected|static|final)\s+)+[\w.<>\[\]]+\s+([A-Za-z_]\w*)\s*=\s*([^;\n]+);""")

    /** Simple constants (numbers and strings) declared anywhere in the file, by name. Later ones do not replace earlier ones. */
    fun constants(stripped: String): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (r in listOf(KT_CONST, JAVA_CONST)) for (m in r.findAll(stripped)) {
            val name = m.groupValues[1]; if (name in out) continue
            val v = value(m.groupValues[2].trim(), out)
            if (v is Number || v is String && !looksLikeSource(m.groupValues[2].trim(), v)) out[name] = v
        }
        return out
    }
    private fun looksLikeSource(src: String, v: String) = !src.startsWith("\"") && v == src

    val SKILLS = listOf("attack", "defence", "strength", "hitpoints", "ranged", "prayer", "magic", "cooking", "woodcutting", "fletching", "fishing", "firemaking",
        "crafting", "smithing", "mining", "herblore", "agility", "thieving", "slayer", "farming", "runecrafting", "hunter", "construction", "summoning")
    private val KIND_OF = mapOf("Items" to "item", "NPCs" to "npc", "Scenery" to "object", "Animations" to "anim", "Graphics" to "gfx", "Sounds" to "sound", "Components" to "interface", "Music" to "music", "Vars" to "var")
    private val ID_CONST = Regex("""^(?:[\w.]*\.)?(Items|NPCs|Scenery|Animations|Graphics|Sounds|Components|Music|Vars)\.(\w+?)_(\d+)$""")
    private val NUM = Regex("""^-?(?:0[xX][0-9a-fA-F_]+|\d[\d_]*(?:\.\d+)?(?:[eE][-+]?\d+)?|\.\d+)[LlFfDd]?$""")
    private val LISTS = setOf("arrayOf", "intArrayOf", "listOf", "mutableListOf", "arrayListOf", "setOf", "hashSetOf", "mutableSetOf", "doubleArrayOf", "longArrayOf",
        "shortArrayOf", "byteArrayOf", "floatArrayOf", "Arrays.asList", "List.of", "Set.of", "linkedSetOf")
    private val CALL = Regex("""^(?:new\s+)?([A-Za-z_][\w.]*)\s*(?:<[^()]*>)?\s*\(""")
    private val NEW_ARRAY = Regex("""^new\s+[\w.<>]+\s*(?:\[\s*\])+\s*\{""")

    private fun number(t: String): Number? {
        if (!NUM.matches(t)) return null
        val hex = Regex("""^-?0[xX]""").containsMatchIn(t)
        if (hex) return t.replace("_", "").trimEnd('L', 'l').replace(Regex("""0[xX]"""), "").toLongOrNull(16)?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
        val c = t.replace("_", "").trimEnd('L', 'l', 'F', 'f', 'D', 'd')
        if (c.contains('.') || c.contains('e') || c.contains('E') || t.endsWith("f") || t.endsWith("F") || t.endsWith("d") || t.endsWith("D")) return c.toDoubleOrNull()
        return c.toLongOrNull()?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
    }

    private fun unwrap(e: String): String {
        var s = e.trim()
        while (s.startsWith("(") && close(s, 0) == s.length - 1) s = s.substring(1, s.length - 1).trim()
        return s
    }

    fun unescape(body: String): String {
        val b = StringBuilder(); var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                when (val n = body[i + 1]) { 'n' -> b.append('\n'); 't' -> b.append('\t'); 'u' -> { body.substring(i + 2, minOf(i + 6, body.length)).toIntOrNull(16)?.let { b.append(it.toChar()) }; i += 4 }; else -> b.append(n) }
                i += 2; continue
            }
            b.append(c); i++
        }
        return b.toString()
    }

    /** A string literal's text, or null when [e] is not exactly one string literal. Kotlin templates become {expression}. */
    fun stringLiteral(e: String): String? {
        if (e.length < 2 || e[0] != '"' || stringEnd(e, 0) != e.length) return null
        if (e.startsWith("\"\"\"")) return e.substring(3, e.length - 3)
        val body = e.substring(1, e.length - 1)
        return unescape(body.replace(Regex("""\$\{([^}]*)\}"""), "{$1}").replace(Regex("""\$([A-Za-z_]\w*)"""), "{$1}"))
    }

    /**
     * Plain data for one Java/Kotlin expression. [c]: known constants. [kind] is told the kind of id when the value came from a
     * constants class such as Items.
     */
    fun value(e0: String, c: Map<String, Any?> = emptyMap(), kind: ((String) -> Unit)? = null, depth: Int = 0): Any? {
        val e = unwrap(e0)
        if (e.isEmpty()) return null
        if (depth > 24) return e
        stringLiteral(e)?.let { return it }
        if (e.length >= 3 && e[0] == '\'' && stringEnd(e, 0) == e.length) return unescape(e.substring(1, e.length - 1))
        number(e)?.let { return it }
        when (e) { "true" -> return true; "false" -> return false; "null" -> return null }
        ID_CONST.find(e)?.let { m -> kind?.invoke(KIND_OF[m.groupValues[1]] ?: "id"); return m.groupValues[3].toInt() }
        Regex("""^(?:[\w.]*\.)?Skills\.([A-Z_]+)$""").find(e)?.let { return it.groupValues[1].lowercase() }
        // "a" + "b", "a" + 5: joined; numbers are summed below
        val plus = splitTop(e, '+')
        if (plus.size > 1 && plus.any { stringLiteral(it.trim()) != null }) return plus.joinToString("") { p -> value(p, c, null, depth + 1).let { v -> if (v is String) v else if (v == null) "null" else fmt(v) } }
        arithmetic(e, c)?.let { return it }
        // pairs: a to b
        splitTop(e, ' ').filter { it.isNotBlank() }.let { parts ->
            if (parts.size == 3 && parts[1] == "to") return listOf(value(parts[0], c, kind, depth + 1), value(parts[2], c, kind, depth + 1))
        }
        // array initialisers: new int[]{...}, new Item[]{...}, {...}
        if (NEW_ARRAY.containsMatchIn(e) || e.startsWith("{") && close(e, 0) == e.length - 1) {
            val open = e.indexOf('{'); val end = close(e, open)
            if (end == e.length - 1) return items(e.substring(open + 1, end), c, kind, depth)
        }
        CALL.find(e)?.let { m ->
            val open = e.indexOf('(', m.range.first + m.groupValues[0].length - 1).let { if (it < 0) m.range.last else it }
            val end = close(e, open)
            if (end == e.length - 1) {
                val name = m.groupValues[1]; val inner = e.substring(open + 1, end)
                if (name in LISTS) return items(inner, c, kind, depth)
                val args = splitTop(inner).map { it.trim() }.filter { it.isNotEmpty() }
                val short = name.substringAfterLast('.')
                if (short == "Item" || short == "GroundItem") {
                    val id = value(args.getOrElse(0) { "" }, c, null, depth + 1)
                    return linkedMapOf("item" to id, "amount" to (args.getOrNull(1)?.let { value(it, c, null, depth + 1) } ?: 1)).also { m2 -> args.drop(2).takeIf { it.isNotEmpty() }?.let { m2["more"] = it.map { a -> value(a, c, null, depth + 1) } } }
                }
                return linkedMapOf("call" to short, "args" to args.map { value(it, c, null, depth + 1) })
            }
        }
        // EnumName.CONSTANT (and Outer.Enum.CONSTANT): kept as a reference
        if (Regex("""^(?:[A-Za-z_]\w*\.)*[A-Z]\w*\.[A-Z][A-Z0-9_]*$""").matches(e)) return e.split('.').takeLast(2).joinToString(".")
        if (Regex("""^[A-Za-z_]\w*$""").matches(e) && e in c) return c[e]
        if (e.startsWith("-")) { val v = value(e.substring(1), c, kind, depth + 1); if (v is Int) return -v; if (v is Double) return -v; if (v is Long) return -v }
        return e
    }

    private fun items(inner: String, c: Map<String, Any?>, kind: ((String) -> Unit)?, depth: Int): List<Any?> =
        splitTop(inner).map { it.trim() }.filter { it.isNotEmpty() }.map { value(it, c, kind, depth + 1) }

    private fun fmt(v: Any): String = if (v is Double && v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()

    /** + - * / % over numbers and known constants, with brackets; null when [e] is not such a sum. */
    private fun arithmetic(e: String, c: Map<String, Any?>): Number? {
        if (!Regex("""^[\w.\s+\-*/%()]+$""").matches(e) || !Regex("""[+\-*/%]""").containsMatchIn(e.trimStart('-'))) return null
        val toks = Regex("""\d[\d_]*(?:\.\d+)?[LlFfDd]?|[A-Za-z_][\w.]*|[+\-*/%()]""").findAll(e).map { it.value }.toList()
        val p = Sum(toks, c)
        val r = p.sum() ?: return null
        if (p.at != toks.size) return null
        return if (p.real) r else r.toLong().let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
    }

    private class Sum(val toks: List<String>, val c: Map<String, Any?>) {
        var at = 0; var real = false
        fun sum(): Double? {
            var v = term() ?: return null
            while (toks.getOrNull(at) == "+" || toks.getOrNull(at) == "-") { val op = toks[at++]; val r = term() ?: return null; v = if (op == "+") v + r else v - r }
            return v
        }
        fun term(): Double? {
            var v = atom() ?: return null
            while (toks.getOrNull(at) in setOf("*", "/", "%")) { val op = toks[at++]; val r = atom() ?: return null
                v = when (op) { "*" -> v * r; "/" -> if (real) v / r else Math.floor(v / r); else -> v % r } }
            return v
        }
        fun atom(): Double? {
            val t = toks.getOrNull(at++) ?: return null
            if (t == "(") { val v = sum(); if (toks.getOrNull(at++) != ")") return null; return v }
            if (t == "-") return atom()?.let { -it }
            number(t)?.let { if (it is Double) real = true; return it.toDouble() }
            val k = c[t] ?: ID_CONST.find(t)?.groupValues?.get(3)?.toInt()
            if (k is Number) { if (k is Double) real = true; return k.toDouble() }
            return null
        }
    }

    // ------------------------------------------------------------------ enums

    private class Param(val name: String, val vararg: Boolean, val optional: Boolean)

    /** A parameter list split at its commas, where type arguments (Pair<Int, Int>) count as brackets too. */
    private fun splitParams(s: String): List<String> {
        val out = ArrayList<String>(); var depth = 0; var start = 0; var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' || c == '\'' -> { i = stringEnd(s, i); continue }
                c == '(' || c == '[' || c == '{' || c == '<' -> depth++
                c == ')' || c == ']' || c == '}' || (c == '>' && (i == 0 || s[i - 1] != '-')) -> depth--
                c == ',' && depth == 0 -> { out.add(s.substring(start, i)); start = i + 1 }
            }
            i++
        }
        out.add(s.substring(start)); return out
    }

    private fun kotlinParams(s: String): List<Param> = splitParams(s).map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { p0 ->
        var p = p0.replace(Regex("""@\w+(?:\([^)]*\))?\s*"""), "")
        val vararg = Regex("""\bvararg\b""").containsMatchIn(p)
        p = p.replace(Regex("""\b(private|public|internal|protected|override|open|vararg|val|var|final)\b"""), "").trim()
        val eq = topIndex(p, '=')
        val name = (if (eq >= 0) p.substring(0, eq) else p).substringBefore(':').trim()
        if (name.isEmpty()) null else Param(name, vararg, eq >= 0)
    }

    private fun javaParams(s: String): List<Param> = splitParams(s).map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { p ->
        val name = Regex("""([A-Za-z_]\w*)\s*(?:\[\s*\])*$""").find(p.trim())?.groupValues?.get(1) ?: return@mapNotNull null
        Param(name, p.contains("..."), false)
    }

    private fun javaConstructors(members: String, name: String): List<List<Param>> {
        val out = ArrayList<List<Param>>()
        for (m in Regex("""(?:^|[\s;}])(?:(?:private|public|protected)\s+)?${Regex.escape(name)}\s*\(""").findAll(members)) {
            val open = members.indexOf('(', m.range.first); val end = close(members, open); if (end < 0) continue
            val after = members.substring(end + 1).trimStart()
            if (after.startsWith("{") || after.startsWith("throws")) out.add(javaParams(members.substring(open + 1, end)))
        }
        return out
    }

    private fun pick(ctors: List<List<Param>>, n: Int): List<Param>? =
        ctors.firstOrNull { it.size == n && it.none { p -> p.vararg } }
            ?: ctors.firstOrNull { it.isNotEmpty() && it.last().vararg && n >= it.size - 1 }
            ?: ctors.firstOrNull { n <= it.size && n >= it.count { p -> !p.optional && !p.vararg } }

    private val ENUM = Regex("""\benum\s+(?:class\s+)?([A-Za-z_]\w*)""")

    /** Every enum in one source file, as tables. [extra]: constants from elsewhere (other files) to resolve names with. */
    fun enums(src: String, file: String = "", extra: Map<String, Any?> = emptyMap()): List<Table> {
        val s = strip(src)
        val consts = extra + constants(s)
        val out = ArrayList<Table>()
        for (m in ENUM.findAll(s)) {
            val name = m.groupValues[1]
            var i = m.range.last + 1
            while (i < s.length && s[i].isWhitespace()) i++
            var ktParams: List<Param>? = null
            if (i < s.length && s[i] == '(') { val e = close(s, i); if (e < 0) continue; ktParams = kotlinParams(s.substring(i + 1, e)); i = e + 1 }
            val open = s.indexOf('{', i); if (open < 0) continue
            if (s.substring(i, open).contains(';') || s.substring(i, open).contains('=')) continue
            val end = close(s, open); if (end < 0) continue
            val body = s.substring(open + 1, end)
            val stop = topIndex(body, ';').let { if (it < 0) body.length else it }
            val ctors = if (ktParams != null) listOf(ktParams) else javaConstructors(body.substring(minOf(stop + 1, body.length)), name)
            val rows = ArrayList<Map<String, Any?>>(); val kinds = LinkedHashMap<String, String>(); val columns = LinkedHashSet<String>()
            for (piece in splitTop(body.substring(0, stop))) {
                var p = piece.trim().replace(Regex("""^(?:@\w+(?:\([^)]*\))?\s*)+"""), "")
                if (p.isEmpty()) continue
                val nm = Regex("""^([A-Za-z_]\w*)""").find(p) ?: continue
                var rest = p.substring(nm.range.last + 1).trim()
                var args = emptyList<String>()
                if (rest.startsWith("(")) { val e = close(rest, 0); if (e < 0) continue; args = splitTop(rest.substring(1, e)).map { it.trim() }.filter { it.isNotEmpty() }; rest = rest.substring(e + 1).trim() }
                if (rest.isNotEmpty() && !rest.startsWith("{")) continue
                val params = pick(ctors, args.size)
                val row = linkedMapOf<String, Any?>("key" to nm.groupValues[1])
                val varargFrom = if (params != null && params.isNotEmpty() && params.last().vararg) params.size - 1 else Int.MAX_VALUE
                args.forEachIndexed { k, a ->
                    if (k >= varargFrom) return@forEachIndexed
                    val pm = params?.getOrNull(k)
                    val col = (pm?.name ?: "arg$k").let { if (it == "key") "key_" else it }
                    columns.add(col)
                    row[col] = value(a, consts, { kinds.putIfAbsent(col, it) })
                }
                if (params != null && params.isNotEmpty() && params.last().vararg && args.size >= params.size - 1) {
                    val col = params.last().name; columns.add(col)
                    row[col] = args.drop(params.size - 1).map { value(it, consts, { k -> kinds.putIfAbsent(col, k) }) }
                }
                rows.add(row)
            }
            if (rows.isNotEmpty()) out.add(Table(name, file, columns.toList(), rows, kinds))
        }
        return out
    }

    /** A table as JSON: { name, file, columns, kinds, rows: [{ key, column: value, ... }] }. */
    fun toJson(t: Table): String = CacheDefs.json(linkedMapOf("name" to t.name, "file" to t.file, "columns" to t.columns, "kinds" to t.kinds, "rows" to t.rows))

    /** One line for a list: name (rows): columns, and the file. */
    fun describe(t: Table): String = "${t.name} (${t.rows.size} rows): ${t.columns.joinToString(", ") { c -> c + (t.kinds[c]?.let { "[$it]" } ?: "") }}  — ${t.file}"
}
