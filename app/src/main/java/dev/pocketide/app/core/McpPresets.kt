package dev.pocketide.app.core

/** Outside tool servers the app offers with one tap. The address is filled in; the Test on add checks that it answers. */
object McpPresets {
    data class Preset(val name: String, val url: String, val about: String, val needsToken: Boolean)

    val ALL = listOf(
        Preset("context7", "https://mcp.context7.com/mcp", "Current documentation for libraries and frameworks", false),
        Preset("github", "https://api.githubcopilot.com/mcp/", "Your GitHub repositories and issues (needs a token)", true),
        Preset("deepwiki", "https://mcp.deepwiki.com/mcp", "Questions about any public GitHub repository: how it is built and where things are (free, no key)", false),
        Preset("perplexity", "https://api.perplexity.ai/mcp", "Web search with written answers and sources (needs a Perplexity API key, paid)", true),
        Preset("ref", "https://api.ref.tools/mcp?apiKey=$TOKEN", "Search documentation for libraries and APIs (needs a Ref API key)", true)
    )

    /** In a preset address, where the key goes when the server takes it in the address instead of a header. */
    const val TOKEN = "{token}"

    /**
     * The address and token to save for a server: when the address has a [TOKEN] place, the key goes there (URL-encoded) and
     * no header token is kept; otherwise both stay as typed.
     */
    fun withToken(url: String, token: String): Pair<String, String> =
        if (url.contains(TOKEN) && token.isNotBlank()) url.replace(TOKEN, java.net.URLEncoder.encode(token.trim(), "UTF-8")) to "" else url to token

    /** The presets not added yet, by safe name. */
    fun missing(all: List<Preset>, existing: List<McpServer>): List<Preset> =
        all.filter { p -> existing.none { Mcp.safeName(it.name) == Mcp.safeName(p.name) } }
}
