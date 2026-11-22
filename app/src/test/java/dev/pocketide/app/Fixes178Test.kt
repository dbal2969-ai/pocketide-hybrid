package dev.pocketide.app

import dev.pocketide.app.core.BuildTag
import dev.pocketide.app.core.SseDecoder
import org.junit.Assert.*
import org.junit.Test

/** 1.5.178: Ollama Cloud answered every chat request from the phone with an HTML 403 page. */
class Fixes178Test {
    @Test fun requestsSayTheyArePocketIde() {
        val was = BuildTag.app
        try {
            BuildTag.app = ""
            assertEquals("PocketIDE (Android)", BuildTag.userAgent)
            BuildTag.app = "v1.5.178-gpl (227)"
            assertEquals("PocketIDE/1.5.178-gpl (Android)", BuildTag.userAgent)
            assertFalse(BuildTag.userAgent.contains("Dalvik"))   // the User-Agent Ollama Cloud's edge refuses
        } finally { BuildTag.app = was }
    }

    @Test fun aWebPageErrorIsShownAsText() {
        val page = "<!doctype html><meta charset=\"utf-8\"><meta name=viewport content=\"width=device-width, initial-scale=1\"><title>403</title>403 Forbidden (HTTP 403)"
        val msg = SseDecoder.errorMessage(page)!!
        assertTrue(msg, msg.startsWith("The server sent a web page instead of an answer: "))
        assertTrue(msg, msg.contains("403 Forbidden") && !msg.contains("<"))
        // JSON errors read as before
        assertEquals("Unauthorized", SseDecoder.errorMessage("{\"error\":{\"message\":\"Unauthorized\"}}"))
    }
}
