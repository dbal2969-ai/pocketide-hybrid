package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 1.5.150 (Copilot, ANALYSIS-1.5.148 M1): the ak- key form is masked like sk-. */
class SecretMaskAkTest {
    @Test fun akKeysAreMasked() {
        val key = "ak-" + "Ab1".repeat(10)
        val shown = SecretMask.mask("HTTP 401: invalid key $key", emptyList())
        assertFalse(shown, shown.contains(key))
        assertEquals("HTTP 401: invalid key ${SecretMask.HIDDEN}", shown)
        assertEquals("a break-even point", SecretMask.mask("a break-even point", emptyList()))   // ordinary words stay
    }
}
