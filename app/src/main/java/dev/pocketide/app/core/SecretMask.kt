package dev.pocketide.app.core

/**
 * Hides keys and tokens in what the app writes down (1.5.133, analysis S6): the assistant's record of commands and tool calls
 * keeps whole command output, which can print a token (an `env`, a failing curl with a header, a config file), and "Save the
 * full record" exports it. Known values (the app's own keys) and well-known token shapes are replaced before anything is written.
 */
object SecretMask {
    const val HIDDEN = "[hidden]"

    /** The app's own keys and tokens, kept current by AppSettings. */
    @Volatile var known: List<String> = emptyList()

    private val SHAPES = listOf(
        Regex("""\bgh[pousr]_[A-Za-z0-9]{20,}\b"""),
        Regex("""\bgithub_pat_[A-Za-z0-9_]{20,}\b"""),
        Regex("""\bsk-(?:ant-|proj-|or-v1-)?[A-Za-z0-9_\-]{20,}\b"""),
        // 1.5.150 (Copilot, ANALYSIS-1.5.148 M1): the ak- form some providers use for access keys
        Regex("""\bak-[A-Za-z0-9_\-]{20,}\b"""),
        Regex("""\bAIza[0-9A-Za-z_\-]{30,}\b"""),
        Regex("""\bxox[abpr]-[A-Za-z0-9\-]{10,}\b"""),
        Regex("""(?i)(\bBearer\s+)[A-Za-z0-9._~+/=\-]{16,}"""),
        Regex("""(?i)(authorization:\s*(?:basic|token)\s+)[A-Za-z0-9._~+/=\-]{12,}"""),
        // 1.5.134, from Copilot's review: Groq, Stripe, Hugging Face, AWS, JWTs, PEM keys, and any KEY=value / "token": "value"
        Regex("""\bgsk_[A-Za-z0-9]{20,}\b"""),
        Regex("""\b[sr]k_(?:live|test)_[A-Za-z0-9]{16,}\b"""),
        Regex("""\bhf_[A-Za-z0-9]{20,}\b"""),
        Regex("""\b(?:AKIA|ASIA)[0-9A-Z]{16}\b"""),
        Regex("""\beyJ[A-Za-z0-9_\-]{8,}\.eyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}"""),
        Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----"""),
        // 1.5.135 (Copilot, ANALYSIS-1.5.134): more prefixes, passwords in addresses and query strings, and the named form from
        // 12 characters. A named value still needs a digit, so code such as token = readToken() stays readable.
        Regex("""\b(?:glpat|glptt|gldt)-[A-Za-z0-9_\-]{20,}"""),
        Regex("""\bnpm_[A-Za-z0-9]{30,}\b"""),
        Regex("""\bpypi-[A-Za-z0-9_\-]{40,}"""),
        Regex("""\bxapp-[A-Za-z0-9\-]{20,}"""),
        Regex("""\b(?:dop|doo|dor)_v1_[a-f0-9]{40,}\b"""),
        Regex("""\bshp(?:at|ca|pa|ss)_[A-Fa-f0-9]{24,}\b"""),
        Regex("""\bSG\.[A-Za-z0-9_\-]{16,}\.[A-Za-z0-9_\-]{16,}"""),
        Regex("""\bwhsec_[A-Za-z0-9+/=]{20,}"""),
        Regex("""\b(?:sbp|sbs)_[A-Za-z0-9]{30,}\b"""),
        Regex("""\bnfp_[A-Za-z0-9]{30,}\b"""),
        Regex("""\bAKIA[0-9A-Z]{16}\b|\bya29\.[A-Za-z0-9_\-]{20,}"""),
        Regex("""(?i)((?:AccountKey|SharedAccessKey|sig)=)[A-Za-z0-9+/%=]{16,}"""),
        Regex("""(\b[a-z][a-z0-9+.\-]*://[^\s:/@]+:)[^\s@/]{4,}(?=@)"""),
        Regex("""(?i)([?&](?:api[_-]?key|key|token|access[_-]?token|auth|secret|password|sig|signature)=)[^\s&#"']{8,}"""),
        Regex("""(?i)(\b[A-Z0-9_]*(?:API[_-]?KEY|TOKEN|SECRET|PASSWORD|PASSWD|ACCESS[_-]?KEY|PRIVATE[_-]?KEY|CLIENT[_-]?SECRET|AUTH)[A-Z0-9_]*["']?\s*[=:]\s*["']?)(?=[A-Za-z0-9_\-./+=]*\d)[A-Za-z0-9_\-./+=]{12,}"""),
    )

    /**
     * The same for a JSON document (a saved chat): only string values are changed, so a match can never cut across the JSON
     * itself. If [json] does not parse it is masked as text. 1.5.135: saved chats, the app log and crash reports.
     */
    fun maskJson(json: String, values: Collection<String> = known): String {
        val v = try { MiniJson.parse(json) } catch (e: Exception) { return mask(json, values) }
        fun walk(x: Any?): Any? = when (x) {
            is String -> mask(x, values)
            is Map<*, *> -> LinkedHashMap<String, Any?>().also { m -> for ((k, y) in x) m[k.toString()] = walk(y) }
            is List<*> -> x.map { walk(it) }
            else -> x
        }
        return MiniJson.write(walk(v))
    }

    fun mask(text: String, values: Collection<String> = known): String {
        var t = text
        for (v in values) if (v.length >= 8) t = t.replace(v, HIDDEN)
        for (r in SHAPES) t = r.replace(t) { m -> if (m.groupValues.size > 1 && m.groupValues[1].isNotEmpty()) m.groupValues[1] + HIDDEN else HIDDEN }
        return t
    }
}
