package dev.pocketide.app.core

/**
 * 1.5.213: the one-tap demo recording (DemoRun). The owner screen-records it for the #BuildHybridAI video. Every answer in it is live:
 * Gemini Nano on the phone or Gemini in the cloud, as it happens; nothing is replayed. The demo only does the tapping and typing a
 * person would. This file holds the scenes, their requests, and the scene log the captions are written from.
 */
object DemoScript {
    class Scene(val id: String, val title: String, val caption: String)

    val SCENES = listOf(
        Scene("welcome", "Fresh start", "A new user opens PocketIDE and picks Hybrid AI: no account, no key."),
        Scene("try", "On the phone", "A quick question, answered by Gemini Nano on this phone."),
        Scene("offline", "Offline", "Airplane mode on: Gemini Nano still answers."),
        Scene("build", "The agent builds", "Gemini Nano builds a snake game in one HTML file; then it is played in Preview."),
        Scene("python", "A real bug fix", "A real bug from the requests library: the app runs the test, Nano rewrites the function, the test decides."),
        Scene("aiweb", "Apps that use it too", "The agent turns the Hybrid AI web app starter into a recipe helper; in Preview the page asks Gemini Nano on this phone through PocketIDE's Firebase AI Logic."),
        Scene("cloud", "Cloud handoff", "A request that needs the web: Nano says why and hands it to Gemini in the cloud."),
        Scene("results", "Measured", "Results measured on this phone with the in-app benchmark."),
    )

    const val TRY_QUESTION = "In one sentence: what does JavaScript's ?? operator do?"
    const val OFFLINE_QUESTION = "In one sentence: what is a closure in JavaScript?"
    const val SNAKE = "Make a snake game in one HTML file with arrow-key and swipe controls, a score, and a restart button."
    const val CLOUD = "Look up the latest stable version of the Phaser game library on the web, and write it with its release date and the " +
        "source link into notes.md."
    const val AIWEB = "Make this page a recipe helper: the person types the ingredients they have, and it suggests one quick recipe. " +
        "Keep using ask() from ai.js for the AI, and keep the ids prompt, ask, answer and where."
    /** What the demo types into the finished page in Preview. */
    const val AIWEB_INPUT = "eggs, spinach, feta"

    /**
     * Types [text] into the page's first text box (#prompt if it is there) a few letters at a time, then presses #ask (or the first
     * button). Returns "typed", or why it could not.
     */
    fun typeIntoPage(text: String): String {
        val t = MiniJson.write(text)
        return "(function(){var b=document.getElementById('prompt')||document.querySelector('textarea,input[type=text],input:not([type])');" +
            "var k=document.getElementById('ask')||document.querySelector('button');if(!b||!k)return 'no text box or button';" +
            "var s=$t,i=0;b.focus();var h=setInterval(function(){i+=2;b.value=s.slice(0,i);b.dispatchEvent(new Event('input',{bubbles:true}));" +
            "if(i>=s.length){clearInterval(h);setTimeout(function(){k.click();},500);}},60);return 'typed';})()"
    }

    /** Reads the page's answer and where-it-ran lines (#answer, #where) as JSON. */
    const val READ_PAGE = "(function(){var a=document.getElementById('answer'),w=document.getElementById('where');" +
        "return JSON.stringify({answer:a?a.textContent:'',where:w?w.textContent:''});})()"

    /** A Python task Nano fixed in every measured run (requests 3 of 3 in runs 1126-2032 and 1126-2203; on its own in 1126-1710). */
    const val PYTHON_TASK = "requests_7315"

    /** The end card: measured numbers only, as in HACKATHON.md, each with what it measured. */
    val RESULTS = listOf(
        "JavaScript coding tasks, Gemini Nano alone on the phone: 114 of 132 (86%)",
        "Real Python bug fixes (rich, requests), Nano first with the cloud's help: 12 of 13",
        "Of those 13 fixes, Gemini Nano wrote 10 on the phone",
        "The same Python tasks with Nano alone, before the fix path: 1 of 13",
    )
    const val RESULTS_SOURCE = "Measured on a Galaxy S24+ with PocketIDE's in-app benchmark. Details: HACKATHON.md."
    const val REPO = "github.com/dbal2969-ai/pocketide-hybrid"

    /** How long the demo waits for one agent request before moving on (the scene log says it timed out). */
    const val AGENT_LIMIT_MS = 8 * 60_000L

    fun scene(id: String): Scene = SCENES.first { it.id == id }
    fun index(id: String): Int = SCENES.indexOfFirst { it.id == id }

    /** "m:ss" from the demo's start. */
    fun clock(ms: Long): String { val s = (ms.coerceAtLeast(0) / 1000); return "%d:%02d".format(s / 60, s % 60) }

    /** A route as recorded by HybridCloud.log, in words for the captions. */
    fun route(where: String, seconds: Double, reason: String, kind: String): String {
        val place = when (where) { "phone" -> "⚡ on the phone (Gemini Nano)"; "cloud" -> "☁ in the cloud (Gemini)"; "strong" -> "handed to the strong model"; else -> where }
        val what = when (kind) { "try" -> "Try it"; "agent" -> "agent step"; "chat" -> "chat reply"; "quick" -> "quick assist"; else -> kind }
        return "$what $place" + (if (seconds > 0) ", %.1f s".format(seconds) else "") + (if (reason.isNotBlank()) "; passed on because $reason" else "")
    }

    /** The scene log: what happened when, from the demo's start. Written to Downloads/PocketIDE for the captions. */
    class Log(private val now: () -> Long = { System.currentTimeMillis() }) {
        private val start = now()
        private val lines = ArrayList<String>()
        @Synchronized fun add(text: String) { lines.add(clock(now() - start) + "  " + text) }
        @Synchronized fun scene(s: Scene) { lines.add(""); add("SCENE ${index(s.id) + 1}: ${s.title}. ${s.caption}") }
        @Synchronized fun text(stamp: String, phone: String): String =
            "PocketIDE Hybrid demo recording, $stamp ($phone)\nTimes are from the moment the countdown ended. Every AI answer was live.\n" +
                lines.joinToString("\n") + "\n"
    }
}
