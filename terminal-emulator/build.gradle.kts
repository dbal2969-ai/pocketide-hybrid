// Vendored from termux/termux-app v0.118.3. The Termux maintainers confirmed (November 2026) that these libraries are not GPL and
// may be used in a non-GPL app; they carry the Apache-2.0 terms of the code they come from. See third_party/termux.
plugins { id("com.android.library") }

android {
    namespace = "com.termux.terminal"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }  // the MIT edition runs on every phone ABI (1.5.128)
        externalNativeBuild {
            ndkBuild { cFlags += listOf("-std=c11", "-Wall", "-Wextra", "-Werror", "-Os", "-fno-stack-protector") }
        }
    }
    // Builds libtermux.so (the PTY helper) with the NDK.
    externalNativeBuild { ndkBuild { path = file("src/main/jni/Android.mk") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
