package dev.pocketide.app

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 1.5.146: the ask checklist is off for everyone, including those whose Save stored the old default (on). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AskCheckSettingTest {
    @Test fun theOldStoredDefaultNoLongerTurnsItOn() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("pocketide", Context.MODE_PRIVATE).edit().putBoolean("ask.check", true).commit()
        val s = AppSettings(ctx)
        assertFalse(s.askCheck)
        s.askCheck = true
        assertTrue(AppSettings(ctx).askCheck)
    }

    // 1.5.162: the explore helper is off by default (the planned helper pair, BENCHMARKS.md)
    @Test fun theExploreHelperIsOffByDefault() {
        assertFalse(AppSettings(RuntimeEnvironment.getApplication()).exploreHelper)
    }
}
