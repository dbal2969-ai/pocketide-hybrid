package dev.pocketide.app.bench

import dev.pocketide.app.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [BenchMemoryPreferences::class])
class BenchCredentialsTest {
    @Test fun credentialsAndAccountAddressStayInMemory() {
        val ctx = RuntimeEnvironment.getApplication()
        val settings = AppSettings(ctx)
        val fake = "benchmark-test-not-a-real-key"
        val address = "https://api.cloudflare.com/client/v4/accounts/benchmark-test-account/ai/v1"
        settings.setKey("cloudflare", fake)
        settings.setBaseFor("cloudflare", address)
        assertEquals(fake, settings.key("cloudflare"))
        assertEquals(address, settings.baseFor("cloudflare"))
        assertFalse(File(ctx.applicationInfo.dataDir, "shared_prefs/pocketide.xml").exists())
    }
}
