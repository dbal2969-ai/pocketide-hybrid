package dev.pocketide.app

import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/** Debug builds: App Check with the debug provider. Register this install's debug token in the Firebase console
 *  (Hybrid AI screen → "Copy App Check debug token"). The hackathon accepts debug tokens. */
object AppCheckSetup {
    const val DEBUG = true
    fun install(app: FirebaseApp) {
        FirebaseAppCheck.getInstance(app).installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
    }
}
