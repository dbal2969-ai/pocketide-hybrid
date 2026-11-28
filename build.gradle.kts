// Same plugin versions as the author's MobileCoder project, which builds on GitHub Actions.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.0" apply false
    // #BuildHybridAI 1.5.220: on-device structured output needs classes generated at build time (Firebase AI Logic's processor)
    id("com.google.devtools.ksp") version "2.3.2" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.0" apply false
    // #BuildHybridAI: reads app/google-services.json (Firebase AI Logic, App Check)
    id("com.google.gms.google-services") version "4.6.0" apply false
    // #BuildHybridAI: crash reports from testers' and judges' phones (Crashlytics), applied with google-services
    id("com.google.firebase.crashlytics") version "3.0.8" apply false
}
