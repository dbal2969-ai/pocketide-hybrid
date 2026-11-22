package dev.pocketide.app.core

/**
 * An optional part of the app that adds tools, notes and run steps for one kind of project (1.5.88). The core never refers to an
 * add-on's code; it asks the registered add-ons ([AddOns]) through this interface. The first add-on is the RuneScape game-cache
 * kit (dev.pocketide.app.addon.osrs); the generic game tools (game_assets, play tests, the game check, starter kits) stay in the core
 * for every game.
 */
interface AddOn {
    val id: String
    val title: String
    /** One line for Settings. */
    val summary: String

    /** True when this project uses the add-on (its files are there). */
    fun applies(project: AddOnProject): Boolean

    /** Tools it adds while it applies. */
    fun tools(project: AddOnProject): List<Tools.Def> = emptyList()

    /** Its tools that are held back until the model asks for them (see Tools.ON_DEMAND). */
    val onDemand: Set<String> get() = emptySet()

    /** True when this call of one of its tools changes project files. */
    fun changes(name: String, args: Map<String, Any?>): Boolean = false

    /** Runs one of its tools. Called off the main thread. */
    fun run(name: String, args: Map<String, Any?>, env: AssetEnv): String = "$name is not available."

    /** Instructions for this request ("" for none). */
    fun note(request: String, project: AddOnProject): String = ""

    /** Work done before the first model call (for example a game folder built from the cache), or null. */
    fun prepare(request: String, project: AddOnProject): AddOnPrep? = null

    /** True when the project holds something this add-on built (so follow-ups keep its notes). */
    fun hasGame(files: Collection<String>): Boolean = false

    /** Run targets for a project of this kind (replacing the usual ones), or null. */
    fun runTargets(files: Set<String>, read: (String) -> String?, hasDir: (String) -> Boolean): List<RunTarget>? = null

    /** True when [targetId] is one of a fixed sequence of steps: the run button then always offers the next one. */
    fun isStep(targetId: String): Boolean = false

    /** Called just before one of its run targets runs; returns the files it updated (shown to the person). */
    fun beforeRun(targetId: String, read: (String) -> String?, write: (String, String) -> Unit): List<String> = emptyList()

    /** Project templates it offers. */
    val templates: List<Template> get() = emptyList()
}

/** What an add-on may know about the project. [folderWith]: the folder holding [name] (and [sibling] beside it), or null. */
class AddOnProject(val files: () -> List<String>, val folderWith: (name: String, sibling: String?) -> String?)

/** Work an add-on does before the model starts. [run] returns text for the model's instructions, or "" when it could not. */
class AddOnPrep(val label: String, val run: (AssetEnv) -> String)

object AddOns {
    private val registered = ArrayList<AddOn>()

    /** Add-on ids the person switched off in Settings. */
    @Volatile var disabled: Set<String> = emptySet()

    /** The add-ons that apply to the request being run (set before each request). */
    @Volatile var active: List<AddOn> = emptyList()

    @Synchronized fun register(a: AddOn) { if (registered.none { it.id == a.id }) registered.add(a) }
    @Synchronized fun all(): List<AddOn> = registered.toList()
    fun enabled(): List<AddOn> = all().filter { it.id !in disabled }

    /** The tools of the active add-ons, each with its add-on, worked out when they are activated. */
    @Volatile private var activeTools: List<Pair<AddOn, Tools.Def>> = emptyList()

    /** Sets [active] (and their tools) to the enabled add-ons that apply to [project]; none outside agent mode. */
    fun activate(project: AddOnProject, agent: Boolean): List<AddOn> {
        val on = if (agent) enabled().filter { try { it.applies(project) } catch (e: Exception) { false } } else emptyList()
        activeTools = on.flatMap { a -> (try { a.tools(project) } catch (e: Exception) { emptyList() }).map { a to it } }
        active = on
        return on
    }

    /** Clears the active add-ons (tests, and requests outside a project). */
    fun clear() { active = emptyList(); activeTools = emptyList() }

    private fun owner(name: String): AddOn? = activeTools.firstOrNull { it.second.name == name }?.first

    fun defs(): List<Tools.Def> = activeTools.map { it.second }
    fun handles(name: String): Boolean = owner(name) != null
    fun changes(name: String, args: Map<String, Any?>): Boolean = owner(name)?.changes(name, args) == true
    fun run(name: String, args: Map<String, Any?>, env: AssetEnv): String = owner(name)?.run(name, args, env) ?: "$name is not available."
    fun onDemand(): Set<String> = active.flatMap { it.onDemand }.toSet()
    fun hasGame(files: Collection<String>): Boolean = enabled().any { it.hasGame(files) }
    private fun applying(project: AddOnProject) = enabled().filter { try { it.applies(project) } catch (e: Exception) { false } }
    fun note(request: String, project: AddOnProject): String = applying(project).joinToString("") { try { it.note(request, project) } catch (e: Exception) { "" } }
    fun prepare(request: String, project: AddOnProject): AddOnPrep? = applying(project).firstNotNullOfOrNull { try { it.prepare(request, project) } catch (e: Exception) { null } }
    fun runTargets(files: Set<String>, read: (String) -> String?, hasDir: (String) -> Boolean): List<RunTarget>? =
        enabled().firstNotNullOfOrNull { it.runTargets(files, read, hasDir) }
    fun isStep(targetId: String): Boolean = enabled().any { it.isStep(targetId) }
    fun beforeRun(targetId: String, read: (String) -> String?, write: (String, String) -> Unit): List<String> =
        enabled().flatMap { try { it.beforeRun(targetId, read, write) } catch (e: Exception) { emptyList() } }
    fun templates(): List<Template> = enabled().flatMap { it.templates }
}
