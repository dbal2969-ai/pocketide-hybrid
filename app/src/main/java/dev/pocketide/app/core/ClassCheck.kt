package dev.pocketide.app.core

/**
 * A class the change adds or toggles (classList.add / classList.toggle) with no style rule for it anywhere in the project: the
 * script runs, but nothing on screen changes. gpt-oss-120b and Kimi K2.6 (standard suite, 1.5.90 candidate), asked for "a dark mode
 * button that toggles a 'dark' class on the body", wired the button and the toggle, wrote no .dark rule, and said the page goes dark.
 */
object ClassCheck {
    // classList.add('a', 'b'), classList.toggle('dark', on)
    private val CALL = Regex("""\.classList\s*\.\s*(add|toggle)\s*\(([^)]*)\)""")
    private val NAME = Regex("""(['"`])([A-Za-z_-][\w-]*)\1""")
    private val CODE = Regex("""(?i)\.(?:js|mjs|cjs|jsx|ts|tsx|html?|vue|svelte)$""")
    private val SOURCE = Regex("""(?i)\.(?:css|scss|sass|less|styl|js|mjs|cjs|jsx|ts|tsx|html?|vue|svelte)$""")
    private val SKIP = Regex("""(?i)(^|/)(node_modules|dist|build|vendor|\.git)/|\.min\.""")
    private val PAGE = Regex("""(?i)\.html?$""")
    private val TEST = Regex("""(?i)(^|/)(tests?|__tests__|spec)/|\.(test|spec)\.""")
    /** Classes that come from outside the project: a CSS framework, or a stylesheet loaded from the web. */
    private val FRAMEWORK_CONFIG = Regex("""(?i)(^|/)(tailwind|uno|windi)\.config\.""")
    private val FRAMEWORK_PAGE = Regex(
        """(?i)<link\b(?=[^>]*\bstylesheet\b)(?![^>]*\bfonts\.(?:googleapis|bunny)\.)[^>]*\bhref\s*=\s*["'](?:https?:)?//|<script\b[^>]*\bsrc\s*=\s*["'][^"']*(?:tailwind|bootstrap|bulma|uikit|materialize|foundation|twind|unocss|daisyui)""")
    private val FRAMEWORK_PACKAGE = Regex("""(?i)"(?:tailwindcss|bootstrap|bulma|daisyui|unocss|windicss|uikit|materialize-css|foundation-sites|@mui/[\w-]+|vuetify|antd)"\s*:""")
    private const val MAX_FILES = 400
    private const val MAX_CHARS = 400_000
    const val SHOWN = 6

    data class Miss(val cls: String, val file: String, val line: Int, val how: String)

    /**
     * Classes added or toggled in the [changed] files that no stylesheet, page or script in the project mentions as .name (a
     * selector) or looks up (classList.contains, getElementsByClassName). Empty when a CSS framework or web stylesheet may style them.
     */
    fun unstyled(paths: Collection<String>, changed: Collection<String>, text: (String) -> String?): List<Miss> {
        val all = paths.filter { SOURCE.containsMatchIn(it) && !SKIP.containsMatchIn(it) }.sorted()
        if (paths.any { FRAMEWORK_CONFIG.containsMatchIn(it) }) return emptyList()
        if (all.size > MAX_FILES) return emptyList()
        val texts = LinkedHashMap<String, String>()
        for (p in all) text(p)?.takeIf { it.length <= MAX_CHARS }?.let { texts[p] = it }
        if (texts.any { (p, t) -> PAGE.containsMatchIn(p) && FRAMEWORK_PAGE.containsMatchIn(t) }) return emptyList()
        if (paths.filter { it == "package.json" || it.endsWith("/package.json") }
                .any { p -> text(p)?.let { FRAMEWORK_PACKAGE.containsMatchIn(it) } == true }) return emptyList()
        val found = LinkedHashMap<String, Miss>()
        for (p in changed.sorted()) {
            if (!CODE.containsMatchIn(p) || SKIP.containsMatchIn(p) || TEST.containsMatchIn(p)) continue
            val t = texts[p] ?: continue
            for (m in CALL.findAll(t)) {
                val args = m.groupValues[2]
                val names = if (m.groupValues[1] == "toggle") listOfNotNull(NAME.find(args.substringBefore(','))) else NAME.findAll(args).toList()
                for (n in names) {
                    val cls = n.groupValues[2]
                    if (cls in found) continue
                    found[cls] = Miss(cls, p, t.substring(0, m.range.first).count { it == '\n' } + 1, m.groupValues[1])
                }
            }
        }
        if (found.isEmpty()) return emptyList()
        return found.values.filter { miss ->
            val selector = Regex("""\.""" + Regex.escape(miss.cls) + """(?![\w-])""")
            val lookup = Regex("""(?:contains|getElementsByClassName)\s*\(\s*['"`]""" + Regex.escape(miss.cls) + """['"`]""")
            texts.values.none { selector.containsMatchIn(it) || lookup.containsMatchIn(it) }
        }
    }

    fun ask(misses: List<Miss>): String {
        val shown = misses.take(SHOWN)
        return "Nothing on screen changes when these classes are set: the change adds them, but no stylesheet, <style> block or script in the project has a rule for them.\n" +
            shown.joinToString("\n") { "- ${it.file}:${it.line} ${if (it.how == "toggle") "toggles" else "adds"} '${it.cls}', and nothing styles .${it.cls}" } +
            (if (misses.size > shown.size) "\n… and ${misses.size - shown.size} more." else "") +
            "\nAdd the styles each class should apply (in the stylesheet the page uses), or say why none are needed, then give your final answer."
    }
}
