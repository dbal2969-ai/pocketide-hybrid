package dev.pocketide.app.bench

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.ReflectionHelpers

/** Live benchmarks must not persist the credentials supplied to AppSettings. */
@Implements(className = "android.app.SharedPreferencesImpl\$EditorImpl", isInAndroidSdk = false)
class BenchMemoryPreferences {
    @RealObject private lateinit var editor: Any

    @Implementation
    protected fun apply() {
        ReflectionHelpers.callInstanceMethod<Any>(editor, "commitToMemory")
    }

    @Implementation
    protected fun commit(): Boolean {
        apply()
        return true
    }
}
