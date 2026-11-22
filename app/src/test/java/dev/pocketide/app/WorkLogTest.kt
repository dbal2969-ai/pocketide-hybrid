package dev.pocketide.app

import dev.pocketide.app.core.ChatEntry
import dev.pocketide.app.core.ChatExport
import dev.pocketide.app.core.ChatSession
import dev.pocketide.app.core.LocalModels
import dev.pocketide.app.core.ToolStream
import dev.pocketide.app.core.WorkLog
import dev.pocketide.app.core.WorkLog.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The work log (1.5.95): thinking, words and steps kept with a reply, as Copilot and Claude keep theirs. */
class WorkLogTest {
    @Test fun aMultiLineCommandIsOneLine() {
        val step = "Ran python3 -c \"\nimport json\nwith open('game/data/maps/m49_50.json') as f:\n    d = json.load(f)\n\""
        assertEquals("Ran python3 -c: import json; with open('game/data/maps/m49_50.json') as f:; d = json.load(f)", WorkLog.oneLine(step, 200))
        assertEquals("Read game/world.js", WorkLog.oneLine("Read game/world.js"))
        val long = WorkLog.oneLine("Ran " + "x".repeat(300))
        assertEquals(110, long.length); assertTrue(long.endsWith("…"))
    }

    @Test fun thoughtsAreCappedAndTheTotalIsKeptSmall() {
        val log = ArrayList<WorkLog.Item>()
        WorkLog.add(log, Kind.THINK, "a".repeat(50_000), 30)
        assertTrue(log[0].text.length < WorkLog.THOUGHT_CAP + 200)
        assertTrue(log[0].text.contains("characters not kept"))
        repeat(20) { WorkLog.add(log, Kind.THINK, "line one $it\nline two\n" + "b".repeat(11_000)) }
        assertTrue(log.sumOf { it.text.length } <= WorkLog.THINKING_TOTAL + 12_500)
        assertTrue("the newest thought is whole", log.last().text.length > 11_000)
        assertTrue("an old one keeps its first lines", log[1].text.startsWith("line one 0\nline two"))
        WorkLog.add(log, Kind.SAY, "   ")
        assertEquals(21, log.size)
    }

    @Test fun theSummaryCountsWhatHappened() {
        val log = ArrayList<WorkLog.Item>()
        WorkLog.add(log, Kind.THINK, "where are doors handled?", 12)
        WorkLog.add(log, Kind.STEP, "Read game/world.js")
        WorkLog.add(log, Kind.THINK, "isDoor misses types 1, 3 and 9", 68)
        WorkLog.add(log, Kind.SAY, "Now I understand the problem.")
        WorkLog.add(log, Kind.STEP, "Edited game/world.js")
        assertEquals("Thought 2 times (1m 20s) · 1 note · 2 steps", WorkLog.summary(log))
        assertEquals("Thought for 12s", WorkLog.thoughtTitle(log[0]))
    }

    @Test fun theLogIsSavedWithTheChatButNotSentToTheModel() {
        val log = listOf(WorkLog.Item(Kind.THINK, "the door has no wall bits", 9), WorkLog.Item(Kind.SAY, "Found it."), WorkLog.Item(Kind.STEP, "Ran ls"))
        val s = ChatSession("c1", "Doors", 1, 2)
        s.entries.add(ChatEntry("user", "fix the doors"))
        s.entries.add(ChatEntry("assistant", "Fixed the doors.", "Edited game/world.js", log = log))
        val back = ChatSession.fromJson(s.toJson())!!
        assertEquals(log, back.entries[1].log)
        assertEquals(emptyList<WorkLog.Item>(), back.entries[0].log)
        assertFalse(back.history().joinToString { it.second }.contains("wall bits"))
        val md = ChatExport.markdown(back)
        assertTrue(md, md.contains("<details><summary>How it worked: Thought once (9s) · 1 note · 1 step</summary>"))
        assertTrue(md, md.contains("> the door has no wall bits"))
        assertTrue(md, md.contains("- Ran ls"))
        assertFalse(ChatExport.markdown(back, commands = false).contains("- Ran ls"))
        // older chats have no log
        assertEquals(emptyList<WorkLog.Item>(), ChatSession.fromJson("""{"id":"c","title":"t","entries":[{"role":"assistant","content":"hi"}]}""")!!.entries[0].log)
    }

    @Test fun aRunThatWasCutOffKeepsItsLogAndCanContinue() {
        // 1.5.96: Android closed the app mid-run, and the thoughts were gone; only "Retry unanswered request" was left
        val s = ChatSession("c2", "Doors", 1, 2)
        s.entries.add(ChatEntry("user", "Fix opening doors"))
        s.running = listOf(WorkLog.Item(Kind.THINK, "isDoor misses types 1, 3 and 9", 40), WorkLog.Item(Kind.STEP, "Read game/world.js"),
            WorkLog.Item(Kind.SAY, "The door has no wall bits to remove."), WorkLog.Item(Kind.STEP, "Edited game/world.js"),
            WorkLog.Item(Kind.STEP, "Ran python3 -c \"\nimport json\n\""))
        s.runningSince = 1_762_440_000_000
        s.autoResumes = 1
        val back = ChatSession.fromJson(s.toJson())!!
        assertEquals("the automatic resumes are counted across restarts", 1, back.autoResumes)
        assertEquals(s.running, back.running)
        assertEquals(1_762_440_000_000, back.runningSince)
        assertEquals(0, back.unansweredRequestIndex())
        val r = WorkLog.resume(back.running)
        assertTrue(r, r.contains("Why it stopped: the run was cut off"))
        assertTrue(r, r.contains("Files changed: game/world.js."))
        assertTrue(r, r.contains("The door has no wall bits to remove."))
        assertTrue(r, r.contains("(last thought) isDoor misses types 1, 3 and 9"))
        assertTrue(r, r.contains("Ran python3 -c: import json"))
        assertFalse("a session with no run in progress saves no running key", ChatSession("c3", "t", 1, 2).also { it.entries.add(ChatEntry("user", "x")) }.toJson().contains("running"))
    }

    @Test fun theStreamKeepsTheThinkingText() {
        val oa = ToolStream(false)
        oa.feed("""data: {"choices":[{"delta":{"reasoning_content":"The door "}}]}""")
        oa.feed("""data: {"choices":[{"delta":{"reasoning":"has no collision."}}]}""")
        oa.feed("""data: {"choices":[{"delta":{"content":"Fixing it."},"finish_reason":"stop"}]}""")
        val t = oa.result()
        assertEquals("The door has no collision.", t.thinking)
        assertEquals("Fixing it.", t.text)
        assertTrue(t.thinkSecs >= 1)
        val an = ToolStream(true)
        an.feed("""data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Check z-index."}}""")
        an.feed("""data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Raised it."}}""")
        assertEquals("Check z-index.", an.result().thinking)
        assertEquals("", ToolStream(false).result().thinking)
        assertEquals("first\n\nsecond", LocalModels.thoughtsIn("<think>first</think>A<think> second </think>B"))
    }

    @Test fun aVeryLongThoughtKeepsBothEnds() {
        val ts = ToolStream(false)
        ts.feed("""data: {"choices":[{"delta":{"reasoning_content":"START "}}]}""")
        repeat(400) { ts.feed("""data: {"choices":[{"delta":{"reasoning_content":"${"m".repeat(100)}"}}]}""") }
        ts.feed("""data: {"choices":[{"delta":{"reasoning_content":" END"}}]}""")
        val th = ts.result().thinking
        assertTrue(th.startsWith("START")); assertTrue(th.endsWith("END"))
        assertTrue(th.length <= WorkLog.THOUGHT_CAP * 2 + 200)
    }
}
