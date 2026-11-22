package dev.pocketide.app.core

/**
 * API keys and tokens at rest (1.5.133, analysis S6). They were plain text in the app's private storage, readable by anyone who
 * could read that storage (a debuggable build, a rooted phone, a backup tool). Now each one is sealed with an AES-GCM key held
 * in the Android Keystore, which never leaves the phone's secure hardware or keystore service.
 *
 * Old plain values keep working and are sealed the next time the app starts. If a sealed value cannot be opened (the keystore
 * key was lost, for example after moving data to another phone), the value counts as missing and the person enters it again:
 * never a crash. Where no keystore is available (unit tests), values stay as they are.
 */
object Vault {
    const val PREFIX = "enc1:"

    interface Cipher {
        /** Base64 of nonce + ciphertext. Throws when it cannot. */
        fun seal(plain: String): String
        /** Throws when it cannot. */
        fun open(sealed: String): String
    }

    @Volatile var cipher: Cipher? = null

    fun isSealed(stored: String) = stored.startsWith(PREFIX)

    /** The form to store. Empty stays empty; with no working cipher the value is stored as it is rather than lost. */
    fun seal(plain: String): String {
        if (plain.isEmpty() || isSealed(plain)) return plain
        val c = cipher ?: return plain
        return try { PREFIX + c.seal(plain) } catch (t: Throwable) { AppLog.add("vault", "could not seal: " + t.javaClass.simpleName); plain }
    }

    /** The value from its stored form: plain values as they are, sealed ones opened, or "" when they cannot be opened. */
    fun open(stored: String): String {
        if (!isSealed(stored)) return stored
        val c = cipher ?: return ""
        return try { c.open(stored.removePrefix(PREFIX)) } catch (t: Throwable) { AppLog.add("vault", "could not open a stored key: " + t.javaClass.simpleName); "" }
    }

    /** Settings that hold keys, passwords or tokens. */
    fun isSecretKey(name: String): Boolean =
        name.startsWith("key.") || name.startsWith("remote.pw.") || name in setOf("web.brave", "cf.deploy.token", "supa.refresh", "gh.token", "mcp.servers")

    /** Settings sealed as a whole that are not themselves one key (so they are not masked as one value). */
    fun isBundle(name: String): Boolean = name == "mcp.servers"
}
