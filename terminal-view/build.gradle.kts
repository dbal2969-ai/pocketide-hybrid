// Vendored from termux/termux-app v0.118.3. The Termux maintainers confirmed (November 2026) that these libraries are not GPL and
// may be used in a non-GPL app; they carry the Apache-2.0 terms of the code they come from. See third_party/termux.
// Change from upstream: the three androidx.annotation annotations were removed so no AndroidX is needed.
plugins { id("com.android.library") }

android {
    namespace = "com.termux.view"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    api(project(":terminal-emulator"))
}
