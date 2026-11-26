package dev.pocketide.app.core

/**
 * Reading Gemini Nano's actions when they are not quite JSON (1.5.188, reliability run 1125-2053). 16 of 19 unusable replies
 * were the right action in broken JSON, and in Private mode one unusable reply ended the request:
 * - a double quote inside a string left unescaped ("reason": "... repr(Foo("hello", bar=3)) ...");
 * - a Python raw string as a value (`"replace": r"r'(?P<url>...)'"`) and escapes JSON does not have (`\w`, `\d`);
 * - a line break typed inside a string;
 * - the action written as YAML (reason: ..., tool: read_file, tool_args: with indented keys).
 * [repair] only runs after strict parsing failed, so a valid reply is read exactly as before. Pure logic.
 */
object LenientJson {
    private const val ESCAPES = "\"\\/bfnrtu"

    /** [s] (one object, from its first { to its last }) with the usual small-model mistakes fixed; parse it with MiniJson. */
    fun repair(s: String): String {
        val out = StringBuilder(s.length + 32)
        var i = 0
        var inStr = false
        // what the next string outside a string is: a key (after { or ,) or a value (after :)
        var key = false
        val open = ArrayList<Char>()
        while (i < s.length) {
            val c = s[i]
            if (!inStr) {
                when {
                    c == '"' -> { inStr = true; out.append(c) }
                    // r"..." (a Python raw string) as a value: the r is dropped
                    (c == 'r' || c == 'R') && i + 1 < s.length && s[i + 1] == '"' && prevSignificant(out) in ":,[" -> { }
                    c == '{' || c == '[' -> { open.add(c); key = c == '{'; out.append(c) }
                    c == '}' || c == ']' -> { if (open.isNotEmpty()) open.removeAt(open.size - 1); out.append(c) }
                    c == ',' -> { key = open.lastOrNull() == '{'; out.append(c) }
                    c == ':' -> { key = false; out.append(c) }
                    else -> out.append(c)
                }
                i++
                continue
            }
            when {
                c == '\\' && i + 1 < s.length -> {
                    val n = s[i + 1]
                    if (n in ESCAPES && !(n == 'u' && !hex4(s, i + 2))) { out.append(c).append(n) } else out.append("\\\\").append(n)
                    i += 2; continue
                }
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append(String.format("\\u%04x", c.code))
                c == '"' -> if (closes(s, i + 1, key)) { inStr = false; out.append(c) } else out.append("\\\"")
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /** Whether the quote before [from] ends the string: what follows it is JSON structure, not more text. */
    private fun closes(s: String, from: Int, key: Boolean): Boolean {
        var j = from
        while (j < s.length && (s[j] == ' ' || s[j] == '\t' || s[j] == '\r' || s[j] == '\n')) j++
        if (j >= s.length) return true
        return when (s[j]) {
            '}', ']' -> true
            ':' -> key
            ',' -> {
                var k = j + 1
                while (k < s.length && s[k].isWhitespace()) k++
                // the next key or value: a quote, a nested object or list, a number, true, false or null
                k >= s.length || s[k] == '"' || s[k] == '{' || s[k] == '[' || s[k] == '}' || s[k] == ']' ||
                    s[k].isDigit() || s.startsWith("true", k) || s.startsWith("false", k) || s.startsWith("null", k)
            }
            else -> false
        }
    }

    private fun hex4(s: String, at: Int) = at + 4 <= s.length && s.substring(at, at + 4).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

    private fun prevSignificant(sb: StringBuilder): Char { var k = sb.length - 1; while (k >= 0 && sb[k].isWhitespace()) k--; return if (k >= 0) sb[k] else ' ' }

    private val FENCE = Regex("""^\s*```\s*[A-Za-z]*\s*\n?|\n?\s*```\s*$""")
    private val TOP = Regex("""^([A-Za-z_]+)\s*:\s?(.*)$""")
    private val INNER = Regex("""^(\s+)([A-Za-z_]+)\s*:\s?(.*)$""")

    /**
     * The action written as YAML: top-level `reason:`, `tool:` and `tool_args:` with the arguments indented under it (or as one
     * inline JSON object). A `|` value takes the more-indented lines after it; a quoted value is read as a JSON string. Null when
     * the reply is not that shape.
     */
    fun yaml(reply: String): Map<String, Any?>? {
        val body = reply.trim().let { t -> val m = Regex("""```[A-Za-z]*\s*\n([\s\S]*?)```""").find(t); m?.groupValues?.get(1) ?: t.replace(FENCE, "") }
        val lines = body.lines()
        val top = LinkedHashMap<String, Any?>()
        val args = LinkedHashMap<String, Any?>()
        var i = 0
        var section: String? = null
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) { i++; continue }
            val inner = INNER.find(line)
            if (section == "tool_args" && inner != null) {
                val ind = inner.groupValues[1].length
                val (v, next) = value(inner.groupValues[3], lines, i + 1, ind)
                args[inner.groupValues[2]] = v; i = next; continue
            }
            val m = TOP.find(line)
            if (m == null) {
                // a reason that runs onto more lines
                if (section == "reason") top["reason"] = (top["reason"] as? String).orEmpty() + " " + line.trim()
                i++; continue
            }
            val k = m.groupValues[1]; val rest = m.groupValues[2].trim()
            section = k
            if (k == "tool_args" || k == "args") {
                section = "tool_args"
                if (rest.startsWith("{")) (try { MiniJson.obj(MiniJson.parse(rest)) } catch (e: Exception) { try { MiniJson.obj(MiniJson.parse(repair(rest))) } catch (e2: Exception) { null } })?.let { args.putAll(it) }
                i++; continue
            }
            val (v, next) = value(rest, lines, i + 1, 0)
            top[k] = v; i = next
        }
        val tool = (top["tool"] as? String)?.trim()?.trim('"', '\'')
        if (tool.isNullOrEmpty() || !Regex("""^[a-z_]+$""").matches(tool)) return null
        return linkedMapOf("reason" to top["reason"], "tool" to tool, "tool_args" to args)
    }

    /** A YAML value starting [first], and the line after it; a `|` or `>` value takes the lines indented more than [indent]. */
    private fun value(first: String, lines: List<String>, next: Int, indent: Int): Pair<Any?, Int> {
        val f = first.trim()
        if (f == "|" || f == ">" || f == "|-" || f == ">-") {
            var j = next
            val block = ArrayList<String>()
            while (j < lines.size && (lines[j].isBlank() || lines[j].takeWhile { it == ' ' }.length > indent)) { block.add(lines[j]); j++ }
            while (block.isNotEmpty() && block.last().isBlank()) block.removeAt(block.size - 1)
            val cut = block.filter { it.isNotBlank() }.minOfOrNull { it.takeWhile { c -> c == ' ' }.length } ?: 0
            val text = block.joinToString(if (f.startsWith(">")) " " else "\n") { if (it.length >= cut) it.substring(cut) else it.trim() }
            return text + (if (f == "|") "\n" else "") to j
        }
        if (f.startsWith("\"")) return (try { MiniJson.parse(f) } catch (e: Exception) { try { MiniJson.parse(repair(f)) } catch (e2: Exception) { f.trim('"') } }) to next
        if (f.length >= 2 && f.startsWith("'") && f.endsWith("'")) return f.substring(1, f.length - 1).replace("''", "'") to next
        return when {
            f == "true" -> true; f == "false" -> false
            f.toLongOrNull() != null -> f.toLong().toDouble()
            else -> f
        } to next
    }
}
