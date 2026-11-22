package dev.pocketide.app.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * OpenRouter extras on top of its OpenAI-style chat API:
 * - sign-in with OAuth PKCE, so a person connects their own account without copying a key
 *   (https://openrouter.ai/docs/guides/overview/auth/oauth);
 * - the current key's spend and free-model quota (GET /api/v1/key);
 * - request fields for model fallbacks and provider routing.
 */
object OpenRouter {
    const val ID = "openrouter"
    const val AUTH = "https://openrouter.ai/auth"
    const val EXCHANGE = "https://openrouter.ai/api/v1/auth/keys"
    const val KEY_INFO = "https://openrouter.ai/api/v1/key"
    const val LABEL = "PocketIDE"
    /** Codes expire 10 minutes after they are issued. */
    const val CODE_MINUTES = 10

    // ---- sign-in (PKCE) ----

    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** A random code_verifier: 32 bytes, base64url without padding (43 characters). */
    fun newVerifier(random: SecureRandom = SecureRandom()): String = ByteArray(32).also { random.nextBytes(it) }.let(::b64)

    /** S256 code_challenge for [verifier]. */
    fun challenge(verifier: String): String = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** A random state value, checked on the callback against forged redirects. */
    fun newState(random: SecureRandom = SecureRandom()): String = ByteArray(12).also { random.nextBytes(it) }.let(::b64)

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /**
     * The page the person signs in on. With [callback] (a localhost address the app listens on) OpenRouter redirects there with
     * ?code=…&state=…; without it OpenRouter shows the code on screen for the person to paste (headless mode, challenge required).
     */
    fun authUrl(challenge: String, callback: String?, state: String?): String {
        val sb = StringBuilder(AUTH).append('?')
        if (callback != null) sb.append("callback_url=").append(enc(callback)).append('&')
        sb.append("code_challenge=").append(enc(challenge)).append("&code_challenge_method=S256&key_label=").append(enc(LABEL))
        if (callback != null && state != null) sb.append("&state=").append(enc(state))
        return sb.toString()
    }

    /** The localhost address OpenRouter sends the person back to. Any port is allowed. */
    fun callbackUrl(port: Int): String = "http://localhost:$port/callback"

    /** The JSON body that trades the code for a key at [EXCHANGE]. */
    fun exchangeBody(code: String, verifier: String): String =
        MiniJson.write(mapOf("code" to code.trim(), "code_verifier" to verifier, "code_challenge_method" to "S256"))

    /** The key in the exchange reply, or null. */
    fun parseKey(json: String): String? = try {
        (MiniJson.obj(MiniJson.parse(json))?.get("key") as? String)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) { null }

    class Callback(val code: String?, val state: String?, val error: String?)

    /** Reads the first line of the request the browser makes to the callback ("GET /callback?code=…&state=… HTTP/1.1"). */
    fun parseCallback(requestLine: String): Callback? {
        val target = requestLine.trim().split(' ').getOrNull(1) ?: return null
        if (!target.startsWith("/callback")) return null
        val q = target.substringAfter('?', "")
        val params = q.split('&').filter { it.contains('=') }.associate {
            java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        return Callback(params["code"]?.takeIf { it.isNotBlank() }, params["state"], params["error"] ?: params["error_description"])
    }

    /** A code pasted by the person: the bare code, or the whole callback address it came in. */
    fun pastedCode(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (t.contains("code=")) return Regex("""[?&]code=([^&\s]+)""").find(t)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        return t.takeIf { !it.contains(' ') }
    }

    /** Readable reason for an exchange failure. */
    fun exchangeError(code: Int, body: String): String = when {
        code == 403 && body.contains("expired", true) -> "The sign-in code expired (codes last $CODE_MINUTES minutes). Sign in again."
        code == 403 -> "OpenRouter refused the sign-in code. Sign in again, and make sure you are logged in to OpenRouter in the browser."
        code == 400 -> "OpenRouter rejected the sign-in request (HTTP 400). Sign in again."
        else -> "Could not finish signing in (HTTP $code)" + (SseDecoder.errorMessage(body)?.let { ": $it" } ?: ".")
    }

    // ---- spend and quota of the current key ----

    class KeyInfo(
        val label: String,
        val limit: Double?, val remaining: Double?, val reset: String?,
        val usage: Double, val daily: Double, val monthly: Double,
        val freeTier: Boolean,
        val freeUsed: Int?, val freeLimit: Int?,
    ) {
        /** One short paragraph for the settings and usage screens. */
        fun summary(): String {
            val sb = StringBuilder()
            sb.append("OpenRouter key ").append(label).append(": spent ").append(usd(daily)).append(" today, ").append(usd(monthly)).append(" this month")
            if (limit != null) {
                sb.append("; ").append(usd(remaining ?: 0.0)).append(" left of its ").append(usd(limit)).append(" limit")
                if (!reset.isNullOrBlank()) sb.append(" (resets ").append(reset).append(')')
            }
            sb.append('.')
            if (freeLimit != null && freeLimit > 0) sb.append(" Free models: ").append(freeUsed ?: 0).append(" of ").append(freeLimit).append(" requests used today.")
            if (freeTier) sb.append(" This account has no credits yet, so only free models work.")
            return sb.toString()
        }
    }

    fun usd(v: Double): String = String.format(java.util.Locale.US, if (v != 0.0 && kotlin.math.abs(v) < 0.01) "$%.4f" else "$%.2f", v)

    /** Reads GET /api/v1/key. Null when the reply is not that shape. */
    fun parseKeyInfo(json: String): KeyInfo? = try {
        val d = MiniJson.obj(MiniJson.obj(MiniJson.parse(json))?.get("data")) ?: return null
        fun n(k: String) = (d[k] as? Number)?.toDouble()
        val free = MiniJson.obj(d["free_model_daily_requests"])
        KeyInfo(
            label = (d["label"] as? String).orEmpty(),
            limit = n("limit"), remaining = n("limit_remaining"), reset = d["limit_reset"] as? String,
            usage = n("usage") ?: 0.0, daily = n("usage_daily") ?: 0.0, monthly = n("usage_monthly") ?: 0.0,
            freeTier = d["is_free_tier"] == true,
            freeUsed = (free?.get("used") as? Number)?.toInt(), freeLimit = (free?.get("limit") as? Number)?.toInt(),
        )
    } catch (e: Exception) { null }

    /** A friendlier line for OpenRouter's 402 (no credit left on the account or key). */
    fun paymentNote(): String = "OpenRouter says this key is out of credit (HTTP 402): add credits at openrouter.ai/settings/credits, raise the key's limit, or pick a free model (ending in :free)."

    // ---- routing ----

    /** Provider routing presets the person can pick. */
    enum class Route(val label: String) { DEFAULT("OpenRouter's choice"), PRICE("Cheapest"), SPEED("Fastest"), PRIVATE("No data collection") }

    /** Fallback model names typed by the person (comma or newline separated), without the main model, at most [MAX_FALLBACKS]. */
    const val MAX_FALLBACKS = 3
    fun parseFallbacks(text: String, main: String): List<String> =
        text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() && it != main }.distinct().take(MAX_FALLBACKS)

    /**
     * Extra body fields for an OpenRouter chat request: a fallback chain (models, main model first) and the provider object.
     * With tools, require_parameters keeps the request away from providers that would drop the tool definitions.
     */
    fun routing(model: String, tools: Boolean, fallbacks: List<String>, route: Route): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        val chain = (listOf(model) + fallbacks).filter { it.isNotBlank() }.distinct()
        if (chain.size > 1) out["models"] = chain
        val provider = LinkedHashMap<String, Any?>()
        if (tools) provider["require_parameters"] = true
        when (route) {
            Route.PRICE -> provider["sort"] = "price"
            Route.SPEED -> provider["sort"] = "throughput"
            Route.PRIVATE -> provider["data_collection"] = "deny"
            Route.DEFAULT -> {}
        }
        if (provider.isNotEmpty()) out["provider"] = provider
        return out
    }
}
