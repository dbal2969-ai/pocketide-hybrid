package dev.pocketide.app.core

/** Tidies a pasted API key: no spaces or line breaks, no quotes, and no "Bearer " or "x-api-key:" label copied along with it. */
object KeyClean {
    private val LABEL = Regex("""^(?:authorization|x-api-key|api[_-]?key|anthropic_api_key|openai_api_key)\s*[:=]\s*""", RegexOption.IGNORE_CASE)

    fun clean(raw: String): String {
        var s = raw.trim()
        s = LABEL.replace(s, "")
        s = s.removePrefix("export ").trim()
        s = LABEL.replace(s, "")
        s = s.removePrefix("Bearer ").removePrefix("bearer ").trim()
        s = s.trim('"', '\'', '`', '“', '”', '‘', '’')
        return s.filter { !it.isWhitespace() && it != '​' && it != '﻿' }
    }
}
