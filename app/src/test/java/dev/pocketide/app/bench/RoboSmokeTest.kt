package dev.pocketide.app.bench

import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoboSmokeTest {
    @Test fun androidViewsWorkOnTheJvm() {
        val tv = TextView(RuntimeEnvironment.getApplication())
        tv.text = "hello"
        assertEquals("hello", tv.text.toString())
    }
}
