package dev.pocketide.app.core

/** When a provider says the account is out of credit, the request is sent to another provider that has a key. */
object CreditSwitch {
    /** How long a provider that reported no credit is skipped. */
    const val SKIP_MS = 60 * 60_000L

    private val OUT = Regex("""\(http 402\)|\b402\b|no credits?\b|credit balance|out of credits?|insufficient (credit|balance|funds|quota)|payment (method|required)|add credits|exceeded your current quota|quota exceeded|billing""")

    /** True when the message says the account has no credit or quota left (not a short rate limit, which waiting fixes). */
    fun outOfCredit(message: String): Boolean {
        val m = message.lowercase()
        if (m.contains("could not reach") || m.contains("try again")) return false
        return OUT.containsMatchIn(m)
    }

    /** The next provider to try: has a key, is not local or custom, is not the one that failed, and did not recently report no credit. */
    fun next(current: String, providers: List<Provider>, hasKey: (String) -> Boolean, outRecently: (String) -> Boolean): Provider? =
        providers.firstOrNull { p -> p.id != current && p.kind != ProviderKind.DEMO && !p.custom && !p.keyless && hasKey(p.id) && !outRecently(p.id) }
}
