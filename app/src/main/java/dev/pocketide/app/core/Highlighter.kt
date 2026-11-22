package dev.pocketide.app.core

enum class TokenKind { COMMENT, STRING, NUMBER, KEYWORD, TAG }

data class Span(val start: Int, val end: Int, val kind: TokenKind)

/** Simple regex-based syntax colouring. It is not a parser, so it can be fooled, but it is fast and never throws. */
object Highlighter {
    private class Lang(val regex: Regex, val kinds: Array<TokenKind>)

    private const val CLIKE_COMMENT = """(//[^\n]*|/\*[\s\S]*?\*/)"""
    private const val CLIKE_STRING = """("(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'|`(?:\\.|[^`\\])*`)"""
    private const val NUMBER = """(\b0x[0-9a-fA-F]+\b|\b\d[\d_]*(?:\.\d+)?(?:[eE][+-]?\d+)?\b)"""

    private fun clike(words: String) = Lang(
        Regex("$CLIKE_COMMENT|$CLIKE_STRING|$NUMBER|(\\b(?:$words)\\b)"),
        arrayOf(TokenKind.COMMENT, TokenKind.STRING, TokenKind.NUMBER, TokenKind.KEYWORD)
    )

    private val JS = clike("var|let|const|function|return|if|else|for|while|do|switch|case|break|continue|new|class|extends|import|export|from|default|async|await|try|catch|finally|throw|typeof|instanceof|in|of|this|null|undefined|true|false|void|yield|static|get|set|interface|type|enum")
    private val KOTLIN = clike("fun|val|var|class|object|interface|return|if|else|for|while|when|is|in|as|null|true|false|this|super|import|package|private|public|protected|internal|override|open|abstract|data|sealed|enum|companion|const|lateinit|by|try|catch|finally|throw|break|continue|suspend|inline|typealias")
    private val JAVA = clike("public|private|protected|static|final|abstract|class|interface|enum|extends|implements|import|package|new|return|if|else|for|while|do|switch|case|break|continue|try|catch|finally|throw|throws|void|int|long|double|float|boolean|char|byte|short|null|true|false|this|super|instanceof|var")
    private val PYTHON = Lang(
        Regex("""(#[^\n]*)|(\"\"\"[\s\S]*?\"\"\"|'''[\s\S]*?'''|"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*')|$NUMBER|(\b(?:def|class|return|if|elif|else|for|while|in|not|and|or|is|None|True|False|import|from|as|with|try|except|finally|raise|pass|break|continue|lambda|yield|global|nonlocal|async|await|self)\b)"""),
        arrayOf(TokenKind.COMMENT, TokenKind.STRING, TokenKind.NUMBER, TokenKind.KEYWORD)
    )
    private val SHELL = Lang(
        Regex("""(#[^\n]*)|("(?:\\.|[^"\\])*"|'[^']*')|$NUMBER|(\b(?:if|then|else|elif|fi|for|while|do|done|case|esac|in|function|return|export|local|echo|cd|exit)\b)"""),
        arrayOf(TokenKind.COMMENT, TokenKind.STRING, TokenKind.NUMBER, TokenKind.KEYWORD)
    )
    private val HTML = Lang(
        Regex("""(<!--[\s\S]*?-->)|("[^"\n]*"|'[^'\n]*')|((?!))|(</?[A-Za-z][\w:-]*|/?>)"""),
        arrayOf(TokenKind.COMMENT, TokenKind.STRING, TokenKind.NUMBER, TokenKind.TAG)
    )
    private val CSS = Lang(
        Regex("""(/\*[\s\S]*?\*/)|("[^"\n]*"|'[^'\n]*')|(#[0-9a-fA-F]{3,8}\b|\b\d+(?:\.\d+)?(?:px|r?em|vh|vw|ms|s|deg|fr|%)?)|(@[\w-]+)"""),
        arrayOf(TokenKind.COMMENT, TokenKind.STRING, TokenKind.NUMBER, TokenKind.KEYWORD)
    )
    private val MARKDOWN = Lang(
        Regex("""(?m)((?!))|(```[\s\S]*?```|`[^`\n]+`)|((?!))|(^#{1,6} [^\n]*)"""),
        arrayOf(TokenKind.COMMENT, TokenKind.STRING, TokenKind.NUMBER, TokenKind.KEYWORD)
    )
    private val JSON = Lang(
        Regex("""((?!))|("(?:\\.|[^"\\\n])*")|$NUMBER|(\b(?:true|false|null)\b)"""),
        arrayOf(TokenKind.COMMENT, TokenKind.STRING, TokenKind.NUMBER, TokenKind.KEYWORD)
    )

    private fun langFor(path: String): Lang? {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "js", "mjs", "cjs", "jsx", "ts", "tsx" -> JS
            "json", "webmanifest" -> JSON
            "kt", "kts" -> KOTLIN
            "java", "c", "h", "cpp", "hpp", "cs", "go", "rs", "swift" -> JAVA
            "py" -> PYTHON
            "sh", "bash", "zsh" -> SHELL
            "html", "htm", "xml", "svg" -> HTML
            "css", "scss" -> CSS
            "md", "markdown" -> MARKDOWN
            else -> null
        }
    }

    /** Colour spans for a file's text. Files over 120,000 characters are left plain to keep typing smooth. */
    fun spans(path: String, text: String): List<Span> {
        if (text.length > 120_000) return emptyList()
        val lang = langFor(path) ?: return emptyList()
        val out = ArrayList<Span>()
        try {
            for (m in lang.regex.findAll(text)) {
                for (g in 1..lang.kinds.size) {
                    val r = m.groups[g]?.range ?: continue
                    if (r.last >= r.first) out.add(Span(r.first, r.last + 1, lang.kinds[g - 1]))
                    break
                }
            }
        } catch (e: StackOverflowError) {
            return emptyList()
        }
        return out
    }
}
