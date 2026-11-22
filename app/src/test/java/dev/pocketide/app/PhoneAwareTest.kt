package dev.pocketide.app

import dev.pocketide.app.core.ExportRequest
import dev.pocketide.app.core.Prompts
import dev.pocketide.app.core.StatusQuestion
import dev.pocketide.app.core.TouchCheck
import dev.pocketide.app.core.Unrequested
import org.junit.Test

class PhoneAwareTest {
    @Test
    fun phoneAwareChecks() {
        PhoneAwareSelfTest.runAll()
    }
}

/** Checks for the phone-aware prompt, the keyboard-only page check and the local answer to "are you still working?". */
object PhoneAwareSelfTest {
    private var fails = 0
    private var passes = 0

    private fun ok(c: Boolean, m: String) {
        if (c) passes++ else fails++
        println((if (c) "PASS  " else "FAIL  ") + m)
    }

    fun runAll() {
        prompt(); touch(); status(); exportAsk(); unrequested()
        println("\n$passes passed, $fails failed")
        check(fails == 0) { "$fails phone-aware checks failed" }
    }

    private fun prompt() {
        for ((name, text) in listOf("text-protocol prompt" to Prompts.system(true), "native-tools prompt" to Prompts.toolsSystem(true))) {
            ok("no keyboard" in text && "touch" in text, "$name tells the model the user has no keyboard and must use touch")
            ok("viewport" in text, "$name asks for a mobile viewport tag")
            ok("APK" in text, "$name says to point the user to the APK step")
            ok("did not ask for" in text && "Export or summarize" in text, "$name forbids unrequested files and points to the app's own tools")
        }
    }

    private fun touch() {
        val keysOnly = mapOf(
            "index.html" to "<canvas id=\"game\"></canvas><script src=\"snake.js\"></script>",
            "snake.js" to "window.addEventListener('keydown', e => { if (e.key === 'ArrowUp') dir = 'up' })"
        )
        ok(TouchCheck.problems(keysOnly).size == 1, "keyboard-only game is reported")
        ok(TouchCheck.problems(keysOnly).first() == TouchCheck.MESSAGE, "the report is the standard message")

        val withButtons = keysOnly + ("index.html" to "<canvas id=\"game\"></canvas><button id=\"up\">Up</button><script src=\"snake.js\"></script>")
        ok(TouchCheck.problems(withButtons).isEmpty(), "on-screen buttons count as touch controls")

        val withSwipe = keysOnly + ("snake.js" to "addEventListener('keydown', k); canvas.addEventListener('touchmove', m)")
        ok(TouchCheck.problems(withSwipe).isEmpty(), "touch handlers count as touch controls")

        val withPointer = mapOf("app.js" to "document.onkeydown = f; el.addEventListener('pointerdown', g)")
        ok(TouchCheck.problems(withPointer).isEmpty(), "pointer handlers count as touch controls")

        val withInput = mapOf("page.html" to "<input id=\"name\" onkeydown=\"go(event)\">")
        ok(TouchCheck.problems(withInput).isEmpty(), "a text box is usable on the phone's keyboard")

        ok(TouchCheck.problems(mapOf("index.html" to "<h1>Hello</h1>")).isEmpty(), "a page with no key handling is fine")
        ok(TouchCheck.problems(emptyMap()).isEmpty(), "no files, no report")
        ok(TouchCheck.problems(mapOf("notes.md" to "press keydown to start")).isEmpty(), "non-web files are ignored")
        ok(TouchCheck.isWebFile("a/b/Game.JS") && TouchCheck.isWebFile("x.html") && !TouchCheck.isWebFile("x.py"), "web file detection")
    }

    private fun status() {
        val yes = listOf("Are you still working?", "are you still working", "Are you finishing the snake game?", "Is it done?", "Are you done",
            "is the assistant stuck?", "Hey are you there", "still working?", "status", "Any progress?", "are you frozen?!")
        for (q in yes) ok(StatusQuestion.matches(q), "answered locally: \"$q\"")
        val no = listOf("Add a dark mode toggle", "Are you able to add a login page?", "Make the snake faster", "It doesnt work. Just flashing green and red squares on my preview screen",
            "Is it possible to use sqlite here?", "", "Are you still working on the page? " + "x".repeat(60), "what is the status code for not found", "Are you done with the header?\nAlso change the colour")
        for (q in no) ok(!StatusQuestion.matches(q), "goes to the model: \"${q.take(40).replace("\n", " ")}\"")
        ok(StatusQuestion.REPLY.startsWith("Nothing is running"), "the reply says nothing is running")
    }

    private fun exportAsk() {
        val yes = listOf("Save this entire chat as a .txt file", "save this chat", "Export our conversation", "Summarize this chat", "download the whole chat log",
            "Can you back up this conversation?", "save the entire conversation to a file")
        for (q in yes) ok(ExportRequest.matches(q), "opens the export menu: \"$q\"")
        val no = listOf("Add a chat feature to my app", "Save this chat app to git", "save the chat input to localStorage", "Make the chat window bigger",
            "Fix the chat bubble colours", "summarize the report in Report.docx", "", "Export the data as csv", "Build a chat server with sockets",
            "Save this chat\nand then add a header")
        for (q in no) ok(!ExportRequest.matches(q), "goes to the model: \"${q.take(40).replace("\n", " ")}\"")
    }

    private fun unrequested() {
        ok(Unrequested.refusal("Save this entire chat as a .txt file", "chat.txt") == Unrequested.CHAT_REFUSAL, "chat.txt is refused even when the user asked for it (the app does it)")
        ok(Unrequested.refusal("anything", "docs/chat-log.md") == Unrequested.CHAT_REFUSAL, "chat log in a folder is refused")
        ok(Unrequested.refusal("anything", "transcript.txt") == Unrequested.CHAT_REFUSAL, "transcript is refused")
        ok(Unrequested.refusal("Fix it", "README.md") != null, "README is left alone for \"Fix it\"")
        ok(Unrequested.refusal("Build a simple snake game", "README.md") != null, "no README for a build request")
        ok(Unrequested.refusal("Add a README explaining how to run it", "README.md") == null, "README allowed when asked")
        ok(Unrequested.refusal("update the documentation", "README.md") == null, "README allowed for documentation requests")
        ok(Unrequested.refusal("Fix it", "NOTES.txt") != null, "notes file is left alone")
        ok(Unrequested.refusal("keep my notes in a file", "notes.md") == null, "notes allowed when asked")
        ok(Unrequested.refusal("write a changelog entry", "CHANGELOG.md") == null, "changelog allowed when asked")
        ok(Unrequested.refusal("Fix it", "CHANGELOG.md") != null, "changelog left alone")
        ok(Unrequested.refusal("Fix it", "snake.js") == null, "code files are never blocked")
        ok(Unrequested.refusal("Fix it", "index.html") == null, "html is never blocked")
        ok(Unrequested.refusal("Fix it", "src/chat.js") == null, "chat.js is code, not a chat log")
        ok(Unrequested.refusal("Fix it", "data/chat.json") == null, "chat.json is data for an app, not a chat log")
        ok(Unrequested.refusal("Fix it", "readme.py") == null, "readme.py is not a document")
        ok(Unrequested.refusal("Fix it", "docs/guide.md") == null, "other markdown files are not blocked")
    }
}
