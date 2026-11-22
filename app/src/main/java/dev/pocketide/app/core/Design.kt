package dev.pocketide.app.core

/**
 * Building something from a short description ("make me a 3D open-world game like Skyrim", "a habit tracker app"): the steps
 * that keep a model from quietly dropping half of the request. It writes the design and a checklist that names every thing
 * asked for (PLAN.md), a play test for each item (tests/play.txt), starts from a kit when one fits, builds item by item and
 * ticks an item only after its test passes. The app enforces the last part: ticks made without a passing check are opened
 * again, and every saved test is played again before the work counts as done. Shaped after GitHub Spec Kit's spec-driven workflow
 * (MIT): independently testable slices in priority order, each with its acceptance check, open points written down as assumptions.
 */
object Design {
    private val MAKE = Regex("""\b(make|build|create|design|write|code|develop|generate|give me|i want|i need|can you do)\b""", RegexOption.IGNORE_CASE)
    private val THING = Regex("""\b(game|app|application|website|site|web ?page|page|tool|dashboard|tracker|calculator|clone|simulator|platformer|shooter|rpg|mmo|puzzle|quiz|survey|form|editor|player|planner|portfolio|store|shop|todo|to-do|landing|notes?|journal|diary|budget|inventory|crm|kanban|catalog)\b""", RegexOption.IGNORE_CASE)

    /** True for a request to build a whole thing (not a fix or a question). */
    fun wanted(request: String): Boolean {
        val r = request.take(4000)
        if (r.length < 12) return false
        if (Regex("""^\s*(why|what|how|where|when|who|is|are|does|do|can i|should)\b""", RegexOption.IGNORE_CASE).containsMatchIn(r) && !MAKE.containsMatchIn(r.take(80))) return false
        if (Regex("""\b(fix|bug|error|crash|broken|doesn'?t work|not working)\b""", RegexOption.IGNORE_CASE).containsMatchIn(r) && !Regex("""\b(make|build|create)\b.{0,40}\b(new|game|app)\b""", RegexOption.IGNORE_CASE).containsMatchIn(r)) return false
        return MAKE.containsMatchIn(r) && THING.containsMatchIn(r)
    }

    /** The named things in a request that a checklist must cover, for the note (lists after "with", "include", commas). */
    fun asks(request: String): List<String> {
        val parts = request.replace(Regex("""(?i)\b(make|build|create|me|a|an|the|that|is|it|like|with|include|including|and|also|plus|has|have|should|must|want|i|please)\b"""), ",")
            .split(Regex("""[,.;:!?\n]+""")).map { it.trim().trim('-', ' ') }.filter { it.length in 3..60 }
        return parts.distinct().take(25)
    }

    const val NOTE = "BUILDING FROM A DESCRIPTION. Work this way, without asking questions you can answer with a sensible default:\n" +
        "1. Write PLAN.md first: a short design (what it is, how it looks, the controls on a phone, the main loop), then a checklist \"- [ ] item\" with one item for EVERY thing the request names " +
        "(each place, creature, system and screen it mentions: e.g. villages, buildings with floors, dungeons, trolls, quests, a skill tree) and for what makes it usable (start screen, touch controls, saving). Never merge or drop asked-for items. " +
        "Order them so the first one is a small version that already runs end to end; each later item adds to it. Write down the choices you made for anything the request left open (\"Assumptions:\"), instead of asking.\n" +
        "2. Write tests/play.txt: for each item a \"# item\" line and look_at_preview steps that use it and check it with expect or until.\n" +
        "3. Start from a kit when one fits (game_assets action=kits lists them) instead of writing an engine; reuse its building blocks.\n" +
        "4. Build item by item (big jobs: split into parts). After each item run look_at_preview test=<item>; tick its box only when that passes.\n" +
        "5. Done means every box is ticked and look_at_preview test=all passes. If something cannot be done here, leave its box open and say why."

    private val KIT_WORDS = listOf(
        "rpg3d" to Regex("""\b(open[- ]?world|rpg|role[- ]?play|skyrim|elder scrolls|witcher|zelda breath|dungeons?|quests?|medieval|fantasy|dragons?|trolls?|villages?|kingdom|realm|adventure|mmo)\b""", RegexOption.IGNORE_CASE),
        "platformer" to Regex("""\b(platformer|platform game|side[- ]?scroll|mario|jump(ing)? game|metroidvania)\b""", RegexOption.IGNORE_CASE),
        "topdown" to Regex("""\b(top[- ]?down|2d rpg|tile[- ]?based|pokemon|zelda[- ]?like|farming|stardew)\b""", RegexOption.IGNORE_CASE),
        "walk3d" to Regex("""\b(3d (walk|explor)|walking sim|third[- ]person)\b""", RegexOption.IGNORE_CASE))
    private val APP_WORDS = listOf(
        "store" to Regex("""\b(store|shop|e-?commerce|cart|checkout|catalog(ue)?|marketplace|sell|products?)\b""", RegexOption.IGNORE_CASE),
        "dashboard" to Regex("""\b(dashboard|analytics|metrics|kpis?|charts?|graphs?|reports?|admin panel|statistics|visuali[sz]e)\b""", RegexOption.IGNORE_CASE),
        "landing" to Regex("""\b(landing|website|web ?site|home ?page|portfolio|business (site|page)|marketing|restaurant site|bakery|agency|startup page|one[- ]pager)\b""", RegexOption.IGNORE_CASE),
        "form" to Regex("""\b(quiz|survey|questionnaire|poll|forms?|sign[- ]?up|registration|feedback|trivia|test yourself)\b""", RegexOption.IGNORE_CASE),
        "notes" to Regex("""\b(notes?|note[- ]taking|journal|diary|wiki|markdown|writing app)\b""", RegexOption.IGNORE_CASE),
        "booking" to Regex("""\b(bookings?|appointments?|calendar|reservations?|salon|clinic|book (a|an|appointments?|visits?)|schedul(e|ing) (app|visits?))\b""", RegexOption.IGNORE_CASE),
        "recipe" to Regex("""\b(recipes?|recipe box|cookbook|cooking|meal plans?|ingredients)\b""", RegexOption.IGNORE_CASE),
        "assistant" to Regex("""\b(ai assistant|assistant|chatbot|ask (the )?ai|llm|gpt)\b""", RegexOption.IGNORE_CASE),
        "chat" to Regex("""\b(chat|chats|chat rooms?|chatroom|group messages?|messaging|forum|community board)\b""", RegexOption.IGNORE_CASE),
        "crud" to Regex("""\b(tracker|track|todo|to-do|tasks?|habits?|budget|expenses?|inventory|contacts|crm|kanban|planner|log|list app|manager|organi[sz]er|reading list|workouts?)\b""", RegexOption.IGNORE_CASE))
    private val GAMEY = Regex("""\b(game|play(er|able)?|rpg|platformer|skyrim|shooter|mmo)\b""", RegexOption.IGNORE_CASE)

    /** The starter kit that fits a game request best, or null. RuneScape-style requests are left to the cache recipe. */
    fun kitFor(request: String): String? {
        if (Regex("""\b(runescape|osrs|rs2|2009 ?scape|rsps)\b""", RegexOption.IGNORE_CASE).containsMatchIn(request)) return null
        // a game request picks among the game kits; anything else among the app kits (a "quiz game" is still a quiz: the form kit scores it)
        if (GAMEY.containsMatchIn(request)) {
            val quiz = Regex("""\b(quiz|trivia)\b""", RegexOption.IGNORE_CASE).containsMatchIn(request)
            if (quiz && !Regex("""\b(3d|open[- ]?world|rpg|platformer)\b""", RegexOption.IGNORE_CASE).containsMatchIn(request)) return "form"
            return KIT_WORDS.maxByOrNull { (_, r) -> r.findAll(request).count() }?.takeIf { (_, r) -> r.containsMatchIn(request) }?.first
        }
        return APP_WORDS.map { (k, r) -> k to r.findAll(request).count() }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }

    /** Marks a pre-build of a starter kit (the app scaffolds it before the model starts). */
    const val KIT = "kit:"
    /** Kits for apps rather than games; they go in app/, games in game/. */
    val APP_KITS = setOf("crud", "dashboard", "landing", "form", "store", "notes", "booking", "recipe", "assistant", "chat")
    fun folderFor(kind: String) = if (kind in APP_KITS) "app" else "game"

    /** The kit the app should set up itself, or null: a build request that fits a kit, in a project with no page to run yet. */
    fun preflight(request: String, files: List<String>): String? {
        if (!wanted(request)) return null
        val kit = kitFor(request) ?: return null
        if (files.any { it.endsWith(".html", true) && !it.startsWith("docs/") } || files.any { it == PlayTests.FILE }) return null
        return kit
    }

    /** Told to the model when the app already set the kit up. */
    fun builtNote(kind: String, dir: String, result: String): String =
        "\n\nTHE STARTER KIT IS ALREADY SET UP: before you started, the app ran game_assets action=scaffold kind=$kind out=$dir. " + result.lineSequence().first().take(400) +
            "\nIt runs, and its play tests in ${PlayTests.FILE} pass. Do not scaffold again or start over. Write PLAN.md for the request, shape the kit to it (its data files first: " +
            (if (kind == "rpg3d") "$dir/design.json and $dir/data/content.json" else if (kind in APP_KITS) "$dir/app.json" else "$dir/main.js") +
            "), add a play test for every new item, and finish when look_at_preview test=all passes."

    /** The note with the request's own items listed, so none is forgotten, and the kit to start from when one fits. */
    fun note(request: String): String {
        val a = asks(request)
        val kit = kitFor(request)
        return NOTE + (if (a.size >= 3) "\nThings this request names (each needs a checklist item): " + a.joinToString("; ") + "." else "") +
            (if (kit == "rpg3d") "\nSTART FROM THE OPEN-WORLD KIT: game_assets action=scaffold kind=rpg3d out=game. It already has terrain, villages with multi-floor houses, dungeons, creatures made in code (bandits, trolls, imps, vampires, skeletons, wolves, bears, dragons...), combat, levels, a branching skill tree, quests and dialogue, saving and 7 play tests. " +
                "Shape it to the request by editing game/design.json (world, villages, dungeons, creatures and their looks, wildlife, items, skill tree) and game/data/content.json (quests and dialogue); change code only for what those files cannot express, and add a play test for every new item."
            else if (kit != null) "\nStart from the $kit kit: game_assets action=scaffold kind=$kit out=game, then change it." else "")
    }
}
