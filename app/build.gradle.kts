plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
// #BuildHybridAI: Firebase is configured only when app/google-services.json exists (kept out of git; CI writes it from a
// secret). Without it the app builds and runs as before, with hybrid AI switched off.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

android {
    externalNativeBuild { ndkBuild { path = file("src/main/cpp/Android.mk") } }
    namespace = "dev.pocketide.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.pocketide.app"
        minSdk = 26
        // The name on the home screen and in Android's installer; the app code is shared, so each edition sets its own (1.5.131).
        resValue("string", "app_name", "PocketIDE")
        targetSdk = 35
        versionCode = 236
        versionName = "1.5.188-hybrid"
    }

    // A fixed debug key, so every build is signed the same way and a new APK
    // installs over the old one without uninstalling first. Debug builds only.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storeType = "pkcs12"
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            // #BuildHybridAI judges' build: one App Check debug token, registered once in the Firebase console, so every judge's
            // phone can reach the cloud model. Given at build time (-PjudgeAppCheckToken=… or JUDGE_APPCHECK_TOKEN), never in git.
            // The App Check SDK reads R.string.firebase_app_check_debug_token and ignores it in release builds.
            val judgeToken = (project.findProperty("judgeAppCheckToken") as String?) ?: System.getenv("JUDGE_APPCHECK_TOKEN") ?: ""
            if (judgeToken.isNotBlank()) resValue("string", "firebase_app_check_debug_token", judgeToken.trim())
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // the agent harness (test/.../bench) runs the real agent loop on the JVM through Robolectric
        unitTests.isIncludeAndroidResources = true
        // Robolectric runs offline from the Android jar Gradle downloads below (its own downloader ignores proxies)
        unitTests.all {
            it.dependsOn(robolectricJars)
            it.systemProperty("robolectric.offline", "true")
            it.systemProperty("robolectric.dependency.dir", robolectricDir.get().asFile.absolutePath)
        }
    }
}

// The licence texts shown in the app's Licenses screen (⋮ → Licenses; 1.5.128 in this edition).
android { sourceSets.getByName("main") { assets.srcDir(layout.buildDirectory.dir("generated/licenseAssets")) } }
// The built-in engine (1.5.182): llama-server (MIT) from engine/build-llama.sh, unpacked to nativeLibraryDir so it can run.
android {
    sourceSets.getByName("main") { jniLibs.srcDir(rootProject.file("engine/out/jniLibs")); assets.srcDir(rootProject.file("engine/out/assets")) }
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += setOf("**/libllama_server.so") } }
}
val copyLicenseAssets by tasks.registering(Copy::class) {
    from(rootProject.file("LICENSE")) { rename { "PocketIDE-LICENSE-MIT.txt" } }
    from(rootProject.file("NOTICE.md")) { rename { "PocketIDE-NOTICE.md" } }
    from(rootProject.file("LICENSES/Apache-2.0.txt")) { rename { "Apache-2.0.txt" } }
    from(rootProject.file("third_party/termux/LICENSE.md")) { rename { "termux-terminal-LICENSE.md" } }
    from(rootProject.file("third_party/termux/PERMISSION.md")) { rename { "termux-terminal-PERMISSION.md" } }
    from(rootProject.file("third_party/jsch")) { rename { "jsch-" + it } }
    from(rootProject.file("engine/SOURCES.json")) { rename { "engine-SOURCES.json" } }
    into(layout.buildDirectory.dir("generated/licenseAssets/licenses"))
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(copyLicenseAssets) }

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":terminal-view"))  // Termux terminal libraries (vendored, Apache-2.0 terms; 1.5.128)
    implementation("com.github.mwiede:jsch:0.2.20")  // BSD licence; SSH for the remote machine (RemoteDev, 1.5.112)
    // #BuildHybridAI: Firebase AI Logic hybrid inference (Gemini Nano on the phone, Gemini in the cloud) and App Check
    implementation("com.google.firebase:firebase-ai:18.2.0")
    implementation("com.google.firebase:firebase-ai-ondevice:16.0.0-beta08")
    implementation("com.google.firebase:firebase-appcheck-playintegrity:20.1.0")
    debugImplementation("com.google.firebase:firebase-appcheck-debug:20.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // #BuildHybridAI: JSON values for Firebase function calls (the version firebase-ai already brings)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    // #BuildHybridAI: Remote Config (hybrid defaults, cloud switch), Authentication (anonymous) and Firestore (usage), BoM 35.2.0 versions
    implementation("com.google.firebase:firebase-config:24.0.1")
    implementation("com.google.firebase:firebase-auth:25.0.0")
    implementation("com.google.firebase:firebase-firestore:27.2.0")
    // #BuildHybridAI: Crashlytics (crashes on testers' and judges' phones) and Performance Monitoring (phone vs cloud request times)
    implementation("com.google.firebase:firebase-crashlytics:21.1.0")
    implementation("com.google.firebase:firebase-perf:23.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")  // MIT licence; runs Android classes on the JVM for the agent harness
}


// The Android system jar Robolectric needs, fetched by Gradle (cached, through the proxy) instead of by Robolectric at test time.
val robolectricAndroid: Configuration by configurations.creating
// One jar only: the harness runs on SDK 35, the app's target (two versions of this module in one configuration keep only the newer).
dependencies { robolectricAndroid("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7") }
val robolectricDir = layout.buildDirectory.dir("robolectric-jars")
val robolectricJars = tasks.register<Copy>("robolectricJars") {
    from(robolectricAndroid)
    into(robolectricDir)
}
