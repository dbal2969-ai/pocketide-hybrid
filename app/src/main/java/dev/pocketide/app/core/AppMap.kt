package dev.pocketide.app.core

/**
 * What PocketIDE shows, for the assistant (1.5.185): every setting, menu item and button with its exact label and the path to it.
 * The items are generated from the screens' source (scripts/gen_app_map.py → AppMapData), so the assistant answers "where is…"
 * from what the app really shows instead of guessing. AppHelp searches them; AppSettingsTool proposes changes to the settings.
 */
object AppMap {
    enum class Kind(val about: String) {
        TAB("tab"), MENU("menu item"), CHECKBOX("tick box"), FIELD("text box"), BUTTON("button"), NOTE("text on the screen"), CHOICE("choice")
    }

    /** [setting]: the stored setting a tick box or choice changes (AppSettings' name, or hybrid.* / mode), else null. */
    data class Item(val where: String, val label: String, val setting: String?, val kind: Kind)

    val ITEMS: List<Item> get() = AppMapData.ITEMS

    /** How to reach an item, in words: `⚙ Settings › Editor and layout › tick box "Word wrap in the editor"`. */
    fun path(i: Item): String = i.where + " › " + i.kind.about + " \"" + i.label + "\""

    /** The item that changes [setting] (the first, for a choice with several rows). */
    fun bySetting(setting: String): Item? = ITEMS.firstOrNull { it.setting == setting }

    /** Every label the app shows, for checking an answer's quoted labels (bench/hybrid/analyze.py does the same from the source). */
    val labels: Set<String> get() = ITEMS.map { it.label }.toSet()
}
