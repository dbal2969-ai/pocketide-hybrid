package dev.pocketide.app.core

/**
 * "Make me an APK": the phone cannot build Android apps itself, so the app explains how to do it with a free GitHub repository
 * and GitHub Actions (the same route PocketIDE itself is built with), and writes the workflow file for the project.
 * Pure logic, no Android imports.
 */
object ApkGuide {
    enum class Kind { ANDROID_GRADLE, WEB, OTHER }

    const val WORKFLOW_PATH = ".github/workflows/build-apk.yml"

    private val NOT_APK = Regex("""\bapk\s+(add|del|update|upgrade|info|search|fix|cache|list|version|policy|stats|audit|verify|index)\b""")
    private val MAKE = Regex("""\b(make|build|create|compile|generate|package|export|turn|convert|produce|get|need|want|give)\b.{0,70}\b(apk|android app|android application|installable android)\b""", RegexOption.DOT_MATCHES_ALL)
    private val HOW = Regex("""\bhow\b.{0,40}\b(apk|android app)\b.{0,40}\b(make|build|create|get|install)\b|\b(apk|android app)\b.{0,30}\b(from|of|for)\b.{0,12}\b(this|my|the)\b.{0,12}\b(project|app|website|site|game|code)\b""", RegexOption.DOT_MATCHES_ALL)

    /** True when the message asks for an Android app file (and is not an Alpine "apk add" command). */
    fun isApkRequest(text: String): Boolean {
        val t = text.lowercase().trim()
        if (t.isEmpty() || t.startsWith("/")) return false
        if (NOT_APK.containsMatchIn(t) && !Regex("""\bandroid\b""").containsMatchIn(t)) return false
        return MAKE.containsMatchIn(t) || HOW.containsMatchIn(t)
    }

    fun kind(paths: Collection<String>): Kind {
        val gradle = paths.any { it == "gradlew" || it.endsWith("/gradlew") || it.endsWith("build.gradle") || it.endsWith("build.gradle.kts") }
        val manifest = paths.any { it.endsWith("AndroidManifest.xml") }
        if (gradle && manifest) return Kind.ANDROID_GRADLE
        if (paths.any { it == "index.html" || it.endsWith("/index.html") }) return Kind.WEB
        return Kind.OTHER
    }

    private fun appId(name: String): String {
        val s = name.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "app" }
        return "com.pocketide." + (if (s[0].isDigit()) "a$s" else s).take(30)
    }

    /** The workflow file for a project kind; null when there is no route for this kind of project. */
    const val EMULATOR_PATH = ".github/workflows/test-on-emulator.yml"

    /**
     * Runs the app's instrumented tests on an Android emulator on GitHub's machines, and keeps the
     * screenshots and logcat. A phone cannot run an emulator, so this is how a build gets tried on a
     * real Android system. Each run takes several minutes and uses Actions minutes on private repositories.
     */
    fun emulatorWorkflow(projectName: String): String = """
name: Test on an Android emulator

on:
  workflow_dispatch:
    inputs:
      api:
        description: Android API level
        default: "34"

permissions:
  contents: read

jobs:
  emulator:
    # A hardware-accelerated runner; the emulator is far too slow without it.
    runs-on: ubuntu-24.04
    timeout-minutes: 60
    steps:
      - uses: actions/checkout@v7

      - uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: 17

      - name: Allow KVM for the emulator
        run: |
          echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules
          sudo udevadm control --reload-rules
          sudo udevadm trigger --name-match=kvm

      - name: Make the wrapper runnable
        run: chmod +x gradlew

      - name: Run the instrumented tests on an emulator
        uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: ${'$'}{{ github.event.inputs.api || '34' }}
          arch: x86_64
          target: google_apis
          disable-animations: true
          emulator-options: -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim -camera-back none
          script: |
            adb logcat -c
            ./gradlew --no-daemon connectedDebugAndroidTest || echo "TESTS_FAILED=1" >> ${'$'}GITHUB_ENV
            adb exec-out screencap -p > screen.png || true
            adb logcat -d > logcat.txt || true

      - name: Keep the screenshots, logcat and reports
        if: always()
        uses: actions/upload-artifact@v7
        with:
          name: $projectName-emulator-results
          path: |
            screen.png
            logcat.txt
            app/build/reports/androidTests/**
          if-no-files-found: warn

      - name: Fail if the tests failed
        if: env.TESTS_FAILED == '1'
        run: exit 1
""".trimStart()

    /** What to tell the person about trying a build on a real Android system. */
    fun emulatorNote(): String =
        "A phone cannot run an Android emulator, so an app you build here is tried on GitHub's machines instead.\n\n" +
            "1. Add $EMULATOR_PATH to the project (the chip below writes it).\n" +
            "2. Push it, then open the repository on github.com → Actions → \"Test on an Android emulator\" → Run workflow.\n" +
            "3. When it finishes, download the results: a screenshot, the logcat and the test reports.\n\n" +
            "It only runs when you start it, because each run takes several minutes and uses Actions minutes on a private repository. " +
            "It runs the project's instrumented tests (androidTest); with none, it still boots the app's build and keeps the logcat."

    fun workflow(kind: Kind, projectName: String): String? = when (kind) {
        Kind.ANDROID_GRADLE -> """name: Build Android APK

on:
  push:
    branches: [main]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-24.04
    timeout-minutes: 40
    steps:
      - uses: actions/checkout@v7

      - uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: 17

      - name: Accept Android SDK licenses
        run: yes | "${'$'}ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null || true

      - name: Make the wrapper runnable
        run: chmod +x gradlew

      - name: Build the debug APK
        run: ./gradlew --no-daemon --stacktrace assembleDebug

      - name: Upload the APK
        uses: actions/upload-artifact@v7
        with:
          name: app-debug-apk
          path: "**/build/outputs/apk/debug/*.apk"
          if-no-files-found: error
"""
        Kind.WEB -> """name: Build Android APK (web app)

on:
  push:
    branches: [main]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-24.04
    timeout-minutes: 40
    steps:
      - uses: actions/checkout@v7

      - uses: actions/setup-node@v6
        with:
          node-version: 22

      - uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: 21

      - name: Accept Android SDK licenses
        run: yes | "${'$'}ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null || true

      # Capacitor wraps your web files (index.html and the rest) in an Android app.
      - name: Copy the web files into www
        run: |
          mkdir -p www
          rsync -a --exclude .git --exclude .github --exclude www --exclude node_modules --exclude android ./ www/

      - name: Create the Android wrapper
        run: |
          npm init -y > /dev/null
          npm install @capacitor/core @capacitor/cli @capacitor/android
          npx cap init "${projectName.replace("\"", "").replace("$", "").ifBlank { "My App" }}" "${appId(projectName)}" --web-dir www
          npx cap add android
          npx cap sync android

      - name: Build the debug APK
        run: |
          cd android
          chmod +x gradlew
          ./gradlew --no-daemon --stacktrace assembleDebug

      - name: Upload the APK
        uses: actions/upload-artifact@v7
        with:
          name: app-debug-apk
          path: android/app/build/outputs/apk/debug/*.apk
          if-no-files-found: error
"""
        Kind.OTHER -> null
    }

    /**
     * The step-by-step answer. repo is "owner/name" when the user already set one up in Git settings, else "".
     * hasWorkflow: the workflow file is already in the project.
     */
    fun steps(kind: Kind, projectName: String, repo: String, hasWorkflow: Boolean, hasLinux: Boolean): String {
        val r = repo.ifBlank { "YOUR-USERNAME/YOUR-REPO" }
        val sb = StringBuilder()
        sb.append("PocketIDE can't build an Android app on the phone (the Android build tools only run on a PC or a server), but GitHub can build it for free. ")
        sb.append("You put the project in a GitHub repository, add one workflow file, and GitHub hands you back an APK to install. Here is the whole route.\n\n")
        when (kind) {
            Kind.ANDROID_GRADLE -> sb.append("This project is an Android (Gradle) project, so the workflow builds it directly.\n\n")
            Kind.WEB -> sb.append("This project is a web app (it has an index.html). The workflow wraps it in an Android app with Capacitor and builds that.\n\n")
            Kind.OTHER -> sb.append("I can't see an Android project or an index.html here, so I can't write a workflow that fits. I can build the APK route for two kinds of project: an Android Gradle project, or a web app with an index.html. Ask me to turn this into a web app (HTML, CSS, JavaScript) and then ask for the APK again.\n\n")
        }
        if (kind == Kind.OTHER) return sb.toString().trimEnd()
        sb.append("STEP 1. Make an empty repository (once)\n")
        sb.append("On github.com tap + > New repository. Name it, leave \"Add a README\" off, and tap Create repository.\n\n")
        sb.append("STEP 2. Add the workflow file to this project\n")
        sb.append(if (hasWorkflow) "Done: $WORKFLOW_PATH is already in your project.\n\n" else "Tap \"Add workflow file\" below. It creates $WORKFLOW_PATH.\n\n")
        sb.append("STEP 3. Put the project on GitHub. Pick one way.\n\n")
        sb.append("Way A, inside PocketIDE (no terminal):\n")
        sb.append("1. On github.com: Settings > Developer settings > Personal access tokens > Fine-grained tokens > Generate new token. Choose only your new repository. Under Repository permissions set Contents: Read and write AND Workflows: Read and write. Without \"Workflows\" GitHub refuses the workflow file. Copy the token.\n")
        sb.append("2. In PocketIDE open Git > Setup. Repository: $r, Branch: main, paste the token, Save.\n")
        sb.append("3. Git > Push to GitHub > Push.\n\n")
        sb.append("Way B, with Termux (a terminal):\n")
        sb.append("1. Project menu > Export or import zip > Export project as zip. Save it to Downloads.\n")
        sb.append("2. In Termux, once: pkg install git unzip && termux-setup-storage\n")
        sb.append("3. Then run these, one line at a time:\n")
        sb.append(termuxCommands(projectName, r)).append("\n")
        sb.append("When git asks for a password, paste the token from Way A step 1 (it needs Contents and Workflows permissions). Nothing shows while you paste; that is normal.\n\n")
        sb.append("STEP 4. Let GitHub build it\n")
        sb.append("On github.com open your repository > Actions. The run named \"Build Android APK\" starts by itself after the push (or tap it > Run workflow). Wait for the green check, about 5 to 15 minutes the first time. A red X means the build failed: open the run, copy the red error lines, and paste them to me.\n\n")
        sb.append("STEP 5. Install it\n")
        sb.append("Open the finished run, scroll to Artifacts, tap app-debug-apk to download a zip, unzip it, and tap the .apk. Android asks to allow installing from this source the first time; allow it. This is a debug build for your own phone.\n\n")
        sb.append("After the first time, only step 3 and the download repeat: change the app, push, wait for the check, install the new APK.")
        return sb.toString()
    }

    /** The Termux commands for way B, ready to copy. */
    fun termuxCommands(projectName: String, repo: String): String {
        val dir = projectName.lowercase().replace(Regex("""[^a-z0-9]+"""), "-").trim('-').ifEmpty { "myapp" }
        val zip = projectName.replace(' ', '-').ifEmpty { "project" } + ".zip"
        val r = repo.ifBlank { "YOUR-USERNAME/YOUR-REPO" }
        return "cd ~\nmkdir $dir && cd $dir\nunzip -o ~/storage/downloads/$zip\ngit init -b main\ngit add -A\ngit commit -m \"First version\"\ngit remote add origin https://github.com/$r.git\ngit push -u origin main"
    }

    /** Added to the system prompt when an APK request goes to the model (long requests that are not answered by the guide). */
    const val PROMPT_NOTE =
        " If the user asks for an APK or Android app file: the phone cannot build one. Explain this route briefly and step by step: " +
            "make an empty GitHub repository; add a workflow file at .github/workflows/build-apk.yml that builds with Gradle (an Android project) or wraps index.html with Capacitor (a web app); " +
            "push the project (PocketIDE's Git > Setup with a fine-grained token that has Contents and Workflows read and write, or git commands in Termux: git init -b main, git add -A, git commit, git remote add origin, git push -u origin main); " +
            "then open the repository's Actions tab, wait for the build, download the app-debug-apk artifact and install the .apk. Never claim an APK was built on the phone."
}
