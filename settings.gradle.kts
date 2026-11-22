pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's mirror of Maven Central first: Maven Central answers 429 (too many requests) to busy build machines, which
        // failed the 1.5.98 build on the 300 MB Robolectric test jar. Same files; Maven Central stays as the fallback.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        mavenCentral()
    }
}

rootProject.name = "PocketIDENative"
include(":app")
// The Termux terminal libraries, vendored from termux-app v0.118.3 (1.5.128; see third_party/termux).
include(":terminal-emulator", ":terminal-view")
