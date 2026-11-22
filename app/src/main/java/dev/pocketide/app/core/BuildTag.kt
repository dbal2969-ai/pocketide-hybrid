package dev.pocketide.app.core

/**
 * A short tag naming the app build and the model, put on every request and reply (saved chats, exports and the
 * tool trace). A problem found in a log can then be traced to the version, and so to the fixes, that produced it.
 * Pure logic; MainActivity sets [app] once from the installed package.
 */
object BuildTag {
    /** "v1.5.69-gpl (118)", or "" until the app has set it. */
    @Volatile var app: String = ""

    /** "PocketIDE/1.5.178-gpl (Android)": the User-Agent of every request (1.5.178; Android's default "Dalvik/2.1.0 …" is refused by Ollama Cloud). */
    val userAgent: String get() = "PocketIDE" + (app.removePrefix("v").substringBefore(' ').takeIf { it.isNotBlank() }?.let { "/$it" } ?: "") + " (Android)"

    /** The app part from the package's version name and code; "" when the name is unknown. */
    fun app(versionName: String?, versionCode: Long): String =
        if (versionName.isNullOrBlank()) "" else "v$versionName ($versionCode)"

    /** "v1.5.69-gpl (118) · cloudflare/@cf/moonshotai/kimi-k2.6": the parts that are known, joined. */
    fun of(app: String, provider: String, model: String): String {
        val who = when {
            provider.isBlank() -> ""
            model.isBlank() -> provider
            else -> "$provider/$model"
        }
        return listOf(app, who).filter { it.isNotBlank() }.joinToString(" · ")
    }

    fun current(provider: String, model: String): String = of(app, provider, model)
}
