package dev.pocketide.app

import org.robolectric.RuntimeEnvironment
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 1.5.131: the app code is shared by both editions, and the MIT edition was named "PocketIDE GPL" from 1.5.128 to 1.5.130.
 *  Each edition's build sets its own name; this checks the name matches the edition's app ID. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditionNameTest {
    @Test fun theNameMatchesTheEdition() {
        val ctx = RuntimeEnvironment.getApplication() as Context
        val expected = if (ctx.packageName == "dev.pocketide.gpl") "PocketIDE GPL" else "PocketIDE"
        assertEquals(expected, ctx.getString(R.string.app_name))
        assertEquals(expected, ctx.applicationInfo.loadLabel(ctx.packageManager).toString())
    }
}
