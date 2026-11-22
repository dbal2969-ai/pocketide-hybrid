// Same plugin versions as the author's MobileCoder project, which builds on GitHub Actions.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.0" apply false
    // #BuildHybridAI: reads app/google-services.json (Firebase AI Logic, App Check)
    id("com.google.gms.google-services") version "4.6.0" apply false
}
