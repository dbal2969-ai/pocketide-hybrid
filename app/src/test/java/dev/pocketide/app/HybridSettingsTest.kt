package dev.pocketide.app

import dev.pocketide.app.core.HybridSettings
import org.junit.Test

class HybridSettingsTest {
    private val d = HybridSettings.DEFAULT

    @Test fun defaultsMatchTheOldBehaviour() {
        check(d.onDeviceMaxChars == 14000) { "default on-phone limit should stay 14000" }
        check(HybridSettings.warning(d) == null) { "the default should not warn" }
    }

    @Test fun valuesAreClampedToSafeRanges() {
        val s = HybridSettings.parse(d, "9.5", "0", "999999", "5")
        check(s.temperature == HybridSettings.TEMP_MAX) { "temperature clamped to ${HybridSettings.TEMP_MAX}, got ${s.temperature}" }
        check(s.topK == HybridSettings.TOP_K_MIN) { "top-K clamped, got ${s.topK}" }
        check(s.maxOutputTokens == HybridSettings.OUT_MAX) { "max output clamped, got ${s.maxOutputTokens}" }
        check(s.onDeviceMaxChars == HybridSettings.CHARS_MIN) { "limit clamped, got ${s.onDeviceMaxChars}" }
    }

    @Test fun badTextKeepsTheOldValue() {
        val s = HybridSettings.parse(d, "hot", "", "lots", "")
        check(s == d) { "non-numbers should keep the old values, got $s" }
    }

    @Test fun aLimitAboveTheNanoSafeLengthWarns() {
        val s = HybridSettings.parse(d, "0.2", "40", "1024", "20000")
        check(s.onDeviceMaxChars == 20000) { "20000 is inside the allowed range" }
        check(HybridSettings.warning(s) != null) { "a limit above 14000 should show a warning" }
    }

    @Test fun clampKeepsValuesInRange() {
        val c = HybridSettings.clamp(HybridSettings(temperature = -1f, topK = 500, maxOutputTokens = 1, onDeviceMaxChars = 100000))
        check(c.temperature == 0f && c.topK == 100 && c.maxOutputTokens == 64 && c.onDeviceMaxChars == 30000) { "clamp failed: $c" }
    }
}
