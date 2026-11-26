package dev.pocketide.app.core

/**
 * Checks a file right after the assistant writes it, so a broken edit is reported in the same round instead of three
 * rounds later as "the page shows nothing" (SWE-agent found linting on edit raised solved tasks noticeably).
 * JSON and CSS are checked here; JavaScript (also the inline scripts of an HTML page) is parsed by the Acorn parser in a
 * hidden browser, which this file only builds the call for. The file is always saved; the error is a warning to fix next.
 */
object SyntaxCheck {
    enum class Kind { SCRIPT, MODULE, JSON, CSS, HTML, PYTHON, NONE }

    private val MODULE_HINT = Regex("""(?m)^\s*(import\s+[\w{*'"]|import\(|export\s+(default|const|let|var|function|class|async|\{|\*))""")

    fun kind(path: String, text: String): Kind = when (path.substringAfterLast('.', "").lowercase()) {
        "mjs" -> Kind.MODULE
        "js", "cjs" -> if (MODULE_HINT.containsMatchIn(text)) Kind.MODULE else Kind.SCRIPT
        "json" -> Kind.JSON
        "css" -> Kind.CSS
        "html", "htm" -> Kind.HTML
        "py" -> if (Tuning.pythonSyntaxCheck) Kind.PYTHON else Kind.NONE
        else -> Kind.NONE
    }

    /** Files bigger than this are not checked (a bundled library, a data dump). */
    const val MAX_CHARS = 400_000

    /** JavaScript that parses [src] with Acorn and returns "ok" or "ERR line col message". */
    fun jsCall(src: String, module: Boolean): String =
        "(function(){if(typeof acorn==='undefined')return 'SKIP';try{acorn.parse(" + MiniJson.quote(src) +
            ",{ecmaVersion:'latest',sourceType:'" + (if (module) "module" else "script") + "',allowHashBang:true,allowReturnOutsideFunction:true,allowAwaitOutsideFunction:true});return 'ok';}" +
            "catch(e){return 'ERR '+(e.loc?e.loc.line+' '+(e.loc.column+1):'0 0')+' '+String(e.message).replace(/\\s*\\(\\d+:\\d+\\)\\s*$/,'');}})()"

    /** Reads what [jsCall] returned (a JSON string literal from evaluateJavascript). Null when the code parsed or could not be checked. */
    fun jsResult(raw: String?, lineOffset: Int = 0): String? {
        var s = raw?.trim() ?: return null
        if (s.startsWith("\"")) s = (try { MiniJson.parse(s) as? String } catch (e: Exception) { null }) ?: return null
        if (!s.startsWith("ERR ")) return null
        val parts = s.removePrefix("ERR ").split(" ", limit = 3)
        val line = (parts.getOrNull(0)?.toIntOrNull() ?: 0) + lineOffset
        val col = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val msg = parts.getOrNull(2).orEmpty()
        return if (line > 0) "line $line, column $col: $msg" else msg
    }

    fun json(text: String): String? {
        if (text.isBlank()) return null
        return try { MiniJson.parse(text); null } catch (e: Exception) {
            val m = e.message.orEmpty()
            val at = Regex("""at (\d+)$""").find(m)?.groupValues?.get(1)?.toIntOrNull()
            if (at != null) "line ${lineOf(text, at)}: ${m.substringBeforeLast(" at ")}" else m.ifEmpty { "not valid JSON" }
        }
    }

    /** Braces in CSS: an unclosed or extra one breaks every rule after it. Comments and strings are skipped. */
    fun css(text: String): String? {
        val open = ArrayList<Int>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> { val e = text.indexOf("*/", i + 2); if (e < 0) return "line ${lineOf(text, i)}: a comment is never closed"; i = e + 2; continue }
                c == '"' || c == '\'' -> { var j = i + 1; while (j < text.length && text[j] != c && text[j] != '\n') { if (text[j] == '\\') j++; j++ }; i = j + 1; continue }
                c == '{' -> open.add(i)
                c == '}' -> if (open.isEmpty()) return "line ${lineOf(text, i)}: a } with no { before it" else open.removeAt(open.size - 1)
            }
            i++
        }
        return if (open.isNotEmpty()) "line ${lineOf(text, open.last())}: a { that is never closed" else null
    }

    data class Script(val firstLine: Int, val code: String, val module: Boolean)

    private val SCRIPT = Regex("""<script\b([^>]*)>([\s\S]*?)</script\s*>""", RegexOption.IGNORE_CASE)

    /** Inline scripts of a page (not ones loaded with src, nor JSON or template blocks), with the page line each starts on. */
    fun inlineScripts(html: String): List<Script> = SCRIPT.findAll(html).mapNotNull { m ->
        val attrs = m.groupValues[1].lowercase()
        if (Regex("""\bsrc\s*=""").containsMatchIn(attrs)) return@mapNotNull null
        val type = Regex("""\btype\s*=\s*["']?([^"'\s>]+)""").find(attrs)?.groupValues?.get(1).orEmpty()
        if (type.isNotEmpty() && type != "module" && !type.contains("javascript") && !type.contains("ecmascript")) return@mapNotNull null
        val code = m.groupValues[2]
        if (code.isBlank()) return@mapNotNull null
        Script(lineOf(html, m.groups[2]!!.range.first), code, type == "module")
    }.toList()

    /** An HTML page with a <script> that is never closed swallows the rest of the page. */
    fun html(text: String): String? {
        val opens = Regex("""<script\b""", RegexOption.IGNORE_CASE).findAll(text).count()
        val closes = Regex("""</script\s*>""", RegexOption.IGNORE_CASE).findAll(text).count()
        return if (opens > closes) "a <script> tag is never closed with </script>" else null
    }

    fun lineOf(text: String, index: Int): Int { var n = 1; for (k in 0 until minOf(index, text.length)) if (text[k] == '\n') n++; return n }

    /** The line added to the tool result. */
    /** True for files a broken write is refused for: a script, a page or a data file (a half-written one breaks the page). */
    fun guarded(path: String): Boolean = path.substringAfterLast('.', "").lowercase().let { e ->
        e in setOf("js", "mjs", "cjs", "html", "htm", "json") || (e == "py" && Tuning.pythonSyntaxCheck) }

    /**
     * Python (1.5.188, reliability run 1125-2053): every Python edit that broke the tests broke the file's syntax (an unexpected
     * indent, 'return' outside function, an unclosed triple quote) and was saved anyway. The text is parsed with Python's own ast
     * module in PocketIDE's Linux; it goes in as base64 so no quoting can break the command, which caps it at [PY_MAX] characters
     * (one shell argument). Null when the file is too long to check this way.
     */
    const val PY_MAX = 90_000

    fun pyCommand(text: String): String? {
        if (text.length > PY_MAX) return null
        val b64 = java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
        return "printf '%s' '$b64' | base64 -d | python3 -c 'import ast,sys; ast.parse(sys.stdin.buffer.read().decode(\"utf-8\",\"replace\"))'"
    }

    private val PY_ERR = Regex("""(?m)^(SyntaxError|IndentationError|TabError): (.+)$""")
    private val PY_LINE = Regex("""line (\d+)""")

    /** What [pyCommand] printed, as "line N: SyntaxError: …"; null when it parsed or could not be checked (no Python). */
    fun pyResult(code: Int, out: String): String? {
        if (code == 0) return null
        val m = PY_ERR.findAll(out).lastOrNull() ?: return null
        val line = PY_LINE.findAll(out.substring(0, m.range.first)).lastOrNull()?.groupValues?.get(1)
        return (line?.let { "line $it: " } ?: "") + m.groupValues[1] + ": " + m.groupValues[2].trim()
    }

    /** True when the error says the text stops early: the reply was cut off while the file was being written. */
    fun cutOff(error: String): Boolean = error.contains("Unexpected end of input", true) || error.contains("Unterminated", true) || error.contains("end of data", true)

    /** The reply when a broken write was undone ([existed]: the old text was put back; otherwise the new file was not kept). */
    fun refused(path: String, error: String, existed: Boolean): String =
        "NOT SAVED: $path has a syntax error at $error" + (if (cutOff(error)) " (the text stops early: your reply was cut off while writing it)" else "") + ". " +
            (if (existed) "The file was put back exactly as it was before this write, so it still works." else "The new file was not kept.") +
            " Write it again correctly. For a long file, change it in small parts with edit_file or edit_lines instead of rewriting it whole."

    fun note(path: String, error: String): String =
        "Syntax check: $path has an error at $error. The file was saved as written; fix this before anything else."
}
