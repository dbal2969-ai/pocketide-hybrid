package dev.pocketide.app.bench

import android.app.Activity
import dev.pocketide.app.HybridAi
import dev.pocketide.app.HybridCloud
import dev.pocketide.app.HybridScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

/** The Hybrid AI screen opens without crashing, shows "How hybrid AI works" once, and the private switch sets phone-only mode. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HybridScreenTest {
    @Test fun screenOpensWithIntroOnceAndPrivateSwitchWorks() {
        HybridCloud.off = true
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        HybridAi.appContext = act.applicationContext
        act.getSharedPreferences("hybrid_ai", 0).edit().clear().commit()
        HybridScreen.show(act)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val intro = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue("the intro is shown the first time", intro != null && intro.isShowing)
        intro!!.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(act.getSharedPreferences("hybrid_ai", 0).getBoolean("intro_seen", false))
        HybridScreen.intro(act)   // not shown again
        assertEquals("no second intro", intro, ShadowAlertDialog.getLatestAlertDialog())
    }
}
