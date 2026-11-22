package dev.pocketide.app

import dev.pocketide.app.core.ModelPurpose
import dev.pocketide.app.core.ModelPurpose.Group
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPurposeTest {
    @Test fun groupsByName() {
        assertEquals(Group.CODING, ModelPurpose.group("qwen/qwen3-coder:free"))
        assertEquals(Group.CODING, ModelPurpose.group("mistralai/devstral-small"))
        assertEquals(Group.CODING, ModelPurpose.group("claude-sonnet-5-5"))
        assertEquals(Group.REASONING, ModelPurpose.group("deepseek/deepseek-r1"))
        assertEquals(Group.REASONING, ModelPurpose.group("gpt-oss:120b"))
        assertEquals(Group.VISION, ModelPurpose.group("meta-llama/llama-3.2-11b-vision-instruct"))
        assertEquals(Group.FAST, ModelPurpose.group("llama-3.1-8b-instant"))
        assertEquals(Group.FAST, ModelPurpose.group("xingchen-agi/xing4.0-29b-a4b:free"))
        assertEquals(Group.GENERAL, ModelPurpose.group("@cf/meta/llama-3.3-70b-instruct-fp8-fast"))
        assertEquals(Group.NOT_CHAT, ModelPurpose.group("whisper-large-v3"))
        assertEquals(Group.NOT_CHAT, ModelPurpose.group("@cf/baai/bge-base-en-v1.5-embed"))
    }

    @Test fun openRouterMetaAndOrder() {
        val json = """{"data":[
            {"id":"a/plain-70b","supported_parameters":["temperature"],"architecture":{"input_modalities":["text"]},"pricing":{"prompt":"0.000001","completion":"0.000002"}},
            {"id":"b/tooly-70b:free","supported_parameters":["tools","tool_choice"],"architecture":{"input_modalities":["text","image"]},"pricing":{"prompt":"0","completion":"0"}}]}"""
        val meta = ModelPurpose.parseMeta(json)
        assertEquals(true, meta["b/tooly-70b:free"]!!.tools)
        assertEquals(false, meta["a/plain-70b"]!!.tools)
        val g = ModelPurpose.grouped(listOf("a/plain-70b", "b/tooly-70b:free", "whisper-1"), meta, includeNotChat = false)
        assertEquals(listOf(Group.VISION, Group.GENERAL), g.map { it.first })
        val tags = ModelPurpose.tags(g[0].second[0])
        assertTrue(tags.contains("tools") && tags.contains("free"))
        assertFalse(g.any { it.first == Group.NOT_CHAT })
        assertEquals(3, ModelPurpose.grouped(listOf("a/plain-70b", "b/tooly-70b:free", "whisper-1"), meta, includeNotChat = true).size)
    }
}
