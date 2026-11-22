package dev.pocketide.app

import android.content.Context
import android.view.View
import android.widget.TextView
import dev.pocketide.app.core.A11yNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 1.5.133 (analysis U1-U3): symbol-only controls have spoken names, tap targets are at least 48 dp, the timer stays quiet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class A11yTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val dp48 get() = Ui.dp(ctx, 48)

    @Test fun symbolsGetNamesAndWordsDoNot() {
        assertEquals("Close", A11yNames.name("✕"))
        assertEquals("More", A11yNames.name(" ⋯ "))
        assertNull(A11yNames.name("Model"))
        assertNull(A11yNames.name("v2"))
        assertTrue(A11yNames.symbolOnly("↺"))
        assertFalse(A11yNames.symbolOnly("Run ▶"))
        assertEquals("Save a copy", A11yNames.name("⬇ Save a copy"))
        assertEquals("Run", A11yNames.name("▶ Run"))
        assertEquals("Reading", dev.pocketide.app.core.WorkingStatus.kind("Reading app.js…").replaceFirstChar { it.uppercase() })
        assertEquals(dev.pocketide.app.core.WorkingStatus.kind("Reading a.js"), dev.pocketide.app.core.WorkingStatus.kind("Reading b.js"))
    }

    @Test fun chipsAndButtonsAreBigEnoughAndNamed() {
        val chip = Ui.chip(ctx, "⋯") {}
        val button = Ui.button(ctx, "✕") {}
        val word = Ui.chip(ctx, "Chats") {}
        for (v in listOf(chip, button, word)) { assertTrue(v.minHeight >= dp48); assertTrue(v.minWidth >= dp48) }
        assertEquals("More", chip.contentDescription)
        assertEquals("Close", button.contentDescription)
        assertNull(word.contentDescription)
    }

    @Test fun aGivenNameWins() {
        val chip = Ui.chip(ctx, "✕") {}
        Ui.describe(chip, "✕", "Hide the server link")
        assertEquals("Hide the server link", chip.contentDescription)
        val t = TextView(ctx).also { it.text = "↺" }
        Ui.tapTarget(t, "Command history")
        assertEquals("Command history", t.contentDescription)
        assertTrue(t.minHeight >= dp48)
    }

    @Test fun theTimerIsHiddenFromScreenReaders() {
        val line = TextView(ctx)
        val ticker = WorkingTicker(line)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, line.importantForAccessibility)
        ticker.phase("Running a command")
        assertTrue(line.text.contains("Running a command"))
        ticker.stop()
    }

    @Test fun spokenUpdatesCanBeTurnedOff() {
        val line = TextView(ctx)
        val ticker = WorkingTicker(line, announce = false)
        ticker.phase("Reading app.js")
        assertTrue(line.text.contains("Reading app.js"))
        ticker.stop()
        assertTrue(AppSettings(ctx).workAnnounce)
    }
}
