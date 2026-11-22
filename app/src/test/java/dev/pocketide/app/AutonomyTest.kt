package dev.pocketide.app

import dev.pocketide.app.core.HistoryTrim
import dev.pocketide.app.core.MiniJson
import dev.pocketide.app.core.Rounds
import dev.pocketide.app.core.TaskTracker
import org.junit.Test

class AutonomyTest {
    @Test
    fun autonomyChecks() {
        AutonomySelfTest.runAll()
    }

    @Test
    fun otherRunsFollowTheRoundsSetting() {
        val d = Rounds.DEFAULT
        org.junit.Assert.assertEquals("default look rounds unchanged", dev.pocketide.app.core.AgentActions.MAX_ROUNDS, Rounds.lookRounds(d))
        org.junit.Assert.assertEquals("default total rounds unchanged", dev.pocketide.app.core.AgentActions.MAX_TOTAL_ROUNDS, Rounds.totalRounds(d))
        org.junit.Assert.assertEquals("default fix attempts unchanged", 3, Rounds.fixAttempts(d))
        org.junit.Assert.assertEquals("default part rounds unchanged", 30, Rounds.partRounds(d))
        org.junit.Assert.assertEquals("default explorer unchanged", 12, Rounds.helperRounds(12, d))
        org.junit.Assert.assertEquals("default reviewer unchanged", 10, Rounds.helperRounds(10, d))
        org.junit.Assert.assertEquals("default run time limit", 10, Rounds.timeLimitMinutes(12))
        org.junit.Assert.assertEquals("larger round setting gives more time", 16, Rounds.timeLimitMinutes(24))
        org.junit.Assert.assertEquals("large round setting gives more time", 32, Rounds.timeLimitMinutes(48))
        org.junit.Assert.assertEquals("custom run time is capped", Rounds.MAX_RUN_MINUTES, Rounds.timeLimitMinutes(500))
        org.junit.Assert.assertEquals("autonomous time remains unchanged", Rounds.AUTO_MINUTES, Rounds.timeLimitMinutes(Rounds.AUTONOMOUS))
        org.junit.Assert.assertFalse("time cap does not trigger early", Rounds.timeLimitReached(599_999L, 12))
        org.junit.Assert.assertTrue("time cap triggers at the boundary", Rounds.timeLimitReached(600_000L, 12))
        org.junit.Assert.assertTrue("autonomous boundary remains 90 minutes", Rounds.timeLimitReached(90 * 60_000L, Rounds.AUTONOMOUS))
        for (f in listOf<(Int) -> Int>(Rounds::lookRounds, Rounds::totalRounds, Rounds::fixAttempts, Rounds::partRounds, { Rounds.helperRounds(10, it) })) {
            org.junit.Assert.assertTrue("48 gives more than 12", f(48) > f(12))
            org.junit.Assert.assertTrue("autonomous gives more than 48", f(Rounds.AUTONOMOUS) > f(48))
            org.junit.Assert.assertTrue("a low custom value never goes below the default", f(Rounds.MIN) >= f(Rounds.DEFAULT) || f(Rounds.MIN) >= 3)
        }
        org.junit.Assert.assertTrue("total covers look rounds", Rounds.totalRounds(500) >= Rounds.lookRounds(500))
        org.junit.Assert.assertTrue("part rounds stay under the absolute ceiling", Rounds.partRounds(500) <= Rounds.AUTO_CEILING)
        org.junit.Assert.assertEquals("an autonomous part has its own budget", Rounds.AUTO_PART_ROUNDS, Rounds.partRounds(0))
        org.junit.Assert.assertTrue("a fixed setting never gives a part more than autonomous", (Rounds.MIN..Rounds.MAX).all { Rounds.partRounds(it) <= Rounds.partRounds(Rounds.AUTONOMOUS) })
        org.junit.Assert.assertTrue("stop hint names the limit", Rounds.stopHint(24).contains("24") && Rounds.stopHint(Rounds.AUTONOMOUS).contains("Autonomous"))
        val w = Rounds.wrapNote("24 rounds used", 24, 0)
        org.junit.Assert.assertTrue("wrap note names the limit and setting: $w", w.contains("24-round limit") && w.contains(Rounds.SETTING) && w.contains("Autonomous removes"))
        val p = Rounds.stoppedNote(32, 12, 30)
        org.junit.Assert.assertTrue("a part's stop names its own cap: $p", p.contains("30-round limit") && p.contains("round 32") && p.contains(Rounds.SETTING))
        org.junit.Assert.assertTrue("autonomous wrap note names the safety limits", Rounds.wrapNote("90 minutes used", Rounds.AUTONOMOUS, 0).contains("90 minutes"))
        org.junit.Assert.assertTrue("follow-up uses the given limit", dev.pocketide.app.core.AgentActions.followUp("t", emptyList(), 7, 20).contains("13 rounds left"))
    }
}

object AutonomySelfTest {
    private var fails = 0
    private var passes = 0

    private fun ok(c: Boolean, m: String) {
        if (c) passes++ else fails++
        println((if (c) "PASS  " else "FAIL  ") + m)
    }

    private fun eq(a: Any?, b: Any?, m: String) = ok(a == b, if (a == b) m else "$m  got <$a> expected <$b>")

    fun runAll() {
        rounds(); trim(); reminders()
        println("\n$passes passed, $fails failed")
        check(fails == 0) { "$fails autonomy checks failed" }
    }

    private fun rounds() {
        ok(Rounds.isAutonomous(Rounds.AUTONOMOUS) && !Rounds.isAutonomous(12), "autonomous is its own value")
        eq(Rounds.clamp(Rounds.AUTONOMOUS), Rounds.AUTONOMOUS, "clamp keeps autonomous")
        eq(Rounds.clamp(3), 6, "custom has a floor"); eq(Rounds.clamp(80), 80, "custom 80 is allowed"); eq(Rounds.clamp(9999), 500, "custom has a ceiling")
        eq(Rounds.limit(Rounds.AUTONOMOUS), Rounds.AUTO_CEILING, "autonomous still has an absolute ceiling")
        eq(Rounds.limit(24), 24, "fixed limits are unchanged")
        eq(Rounds.parseCustom(" 80 "), 80, "parse a number"); eq(Rounds.parseCustom("5"), null, "too small"); eq(Rounds.parseCustom("501"), null, "too big")
        eq(Rounds.parseCustom("abc"), null, "not a number"); eq(Rounds.parseCustom(""), null, "empty")
        ok(Rounds.label(Rounds.AUTONOMOUS).contains("Autonomous") && Rounds.label(12).contains("default") && Rounds.label(80) == "80 rounds", "labels")
        eq(Rounds.CHOICES, listOf(12, 24, 48), "the fixed choices are unchanged")
    }

    private fun reminders() {
        val steps = listOf("a" to "todo", "b" to "doing")
        val normal = TaskTracker(Rounds.reminders(12)).also { it.update(steps) }
        var n = 0
        while (normal.reminder() != null) n++
        eq(n, 2, "a normal run reminds twice")
        val auto = TaskTracker(Rounds.reminders(Rounds.AUTONOMOUS)).also { it.update(steps) }
        n = 0
        while (auto.reminder() != null) n++
        eq(n, Rounds.AUTO_REMINDERS, "autonomous keeps nudging an unfinished plan")
    }

    private fun big(n: Int) = "x".repeat(n)

    private fun trim() {
        // OpenAI style
        val openai = ArrayList<Map<String, Any?>>()
        openai.add(mapOf("role" to "user", "content" to big(5000)))
        for (i in 1..30) {
            openai.add(mapOf("role" to "assistant", "content" to null, "tool_calls" to listOf(mapOf("id" to "c$i", "type" to "function",
                "function" to mapOf("name" to "write_file", "arguments" to MiniJson.write(mapOf("path" to "f$i.js", "content" to big(4000))))))))
            openai.add(mapOf("role" to "tool", "tool_call_id" to "c$i", "content" to big(3000)))
        }
        val before = HistoryTrim.total(openai)
        val changed = HistoryTrim.compact(openai)
        val after = HistoryTrim.total(openai)
        ok(changed > 0 && after < before / 3, "a big conversation shrinks a lot ($before -> $after)")
        ok((openai[0]["content"] as String).length == 5000, "the user's request is never shortened")
        val last = openai.last()
        eq((last["content"] as String).length, 3000, "the newest tool result stays whole")
        val lastCall = MiniJson.obj(MiniJson.obj((openai[openai.size - 2]["tool_calls"] as List<*>)[0])!!["function"])!!
        ok((lastCall["arguments"] as String).length > 4000, "the newest call keeps its file text")
        val oldCall = MiniJson.obj(MiniJson.obj((openai[1]["tool_calls"] as List<*>)[0])!!["function"])!!
        val oldArgs = MiniJson.obj(MiniJson.parse(oldCall["arguments"] as String))!!
        ok(oldArgs["path"] == "f1.js" && (oldArgs["content"] as String).startsWith("[old text not kept"), "an old call keeps valid JSON, keeps the path, drops the text")
        ok((openai[2]["content"] as String).contains("older output removed"), "an old tool result is shortened with a note")
        eq(HistoryTrim.compact(openai), 0, "trimming twice changes nothing more")
        // under budget: untouched
        val small = arrayListOf<Map<String, Any?>>(mapOf("role" to "user", "content" to "hi"), mapOf("role" to "tool", "tool_call_id" to "a", "content" to big(2000)))
        eq(HistoryTrim.compact(small), 0, "small conversations are untouched")
        // Anthropic style
        val ant = ArrayList<Map<String, Any?>>()
        ant.add(mapOf("role" to "user", "content" to "task"))
        for (i in 1..40) {
            ant.add(mapOf("role" to "assistant", "content" to listOf(mapOf("type" to "text", "text" to "ok"), mapOf("type" to "tool_use", "id" to "t$i", "name" to "write_file", "input" to mapOf("path" to "p$i", "content" to big(3000))))))
            ant.add(mapOf("role" to "user", "content" to listOf(mapOf("type" to "tool_result", "tool_use_id" to "t$i", "content" to big(3000)))))
        }
        val b2 = HistoryTrim.total(ant)
        ok(HistoryTrim.compact(ant) > 0 && HistoryTrim.total(ant) < b2 / 3, "anthropic-style history shrinks too")
        val oldUse = MiniJson.obj(((ant[1]["content"] as List<*>)[1]))!!
        ok(MiniJson.obj(oldUse["input"])!!["path"] == "p1" && oldUse["id"] == "t1", "old tool_use keeps its id and path")
        val oldRes = MiniJson.obj(((ant[2]["content"] as List<*>)[0]))!!
        ok(oldRes["tool_use_id"] == "t1" && (oldRes["content"] as String).contains("older output removed"), "old tool_result keeps its id")
    }
}
