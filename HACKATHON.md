# PocketIDE Hybrid — Firebase #BuildHybridAI 2026

PocketIDE is a coding environment that runs on an Android phone: editor, terminal, git, previews and an AI agent.
This repository is the MIT edition of PocketIDE with **hybrid AI** added for the
[#BuildHybridAI hackathon](https://firebasehybridaihackathon.devpost.com/): quick coding help runs **on the phone with
Gemini Nano** through **Firebase AI Logic**, and falls back to a **cloud Gemini model** when Nano is missing, the device is
the request is too long for it, or the person prefers the cloud.

## What was there before the hackathon

The first commit (`Baseline: PocketIDE MIT edition 1.5.182…`) is the app exactly as it was before the submission period
(private repository `test-coder2.2`, commit `1013cb7`). Everything after it was built during the hackathon, so
`git log` and `git diff <baseline>..main` show precisely what changed.

## What was built during the hackathon

| Change | Files |
|---|---|
| Firebase AI Logic hybrid inference: one `GenerativeModel` per routing mode (`PREFER_ON_DEVICE`, `PREFER_IN_CLOUD`, `ONLY_ON_DEVICE`), cloud model `gemini-3.1-flash-lite` | `app/src/main/java/dev/pocketide/app/HybridAi.kt` |
| Routing rule on top of the SDK: requests over 4,000 characters go to the cloud even in on-phone-first mode (Gemini Nano is built for short single-turn prompts) | `HybridAi.route` |
| Every reply is labelled with where it ran (`inferenceSource`: on this phone or cloud) and how long it took | `HybridScreen.label` |
| Gemini Nano status and one-tap download through Android AICore, with progress | `HybridAi.nanoStatus`, `HybridAi.downloadNano`, Hybrid AI screen |
| Hybrid AI screen: Nano status, routing mode, a prompt to try | `HybridScreen.kt`, opened from Models → Hybrid AI |
| Editor **Quick assist** (⋮ menu): explain the selected lines, look for bugs, write a doc comment (insert above), draft a commit message — Nano first; “Hand to the agent” escalates the same request to PocketIDE's full agent | `EditorPane.kt`, `HybridScreen.quickAssist` |
| App Check: debug provider in debug builds (token can be copied from the Hybrid AI screen), Play Integrity in release builds | `app/src/debug/.../AppCheckSetup.kt`, `app/src/release/.../AppCheckSetup.kt` |
| Build: Firebase is applied only when `app/google-services.json` exists; CI writes it from the `GOOGLE_SERVICES_JSON` secret, so the config is not in git and the app still builds without it | `build.gradle.kts`, `app/build.gradle.kts`, `.github/workflows/build-apk.yml`, `.gitignore` |

## Why hybrid fits a phone IDE

- **Private by default.** Code under the cursor can be explained or reviewed without leaving the phone (`ONLY_ON_DEVICE` never sends it out).
- **Works offline.** On a train or a plane, quick help still answers.
- **Free and fast for small things.** Explaining ten lines does not need a cloud model or an API key.
- **The cloud is there when it matters.** Long files and phones without Nano still get an answer through the same API, and the
  full agent remains one tap away for multi-step work.

## Set up your own build

1. Create a Firebase project, add an Android app with package `dev.pocketide.app`, and download `google-services.json` into `app/`.
2. Firebase console → **AI Logic** → Get started → **Gemini Developer API**.
3. Firebase console → **App Check** → register the app (Play Integrity); for a debug build, add the debug token shown in the
   app (Models → Hybrid AI → Copy App Check debug token) under *Manage debug tokens*.
4. `./gradlew assembleDebug` (needs the Android SDK and NDK; see `.github/workflows/build-apk.yml`).

On-device inference needs a phone that supports Gemini Nano through Android AICore (for example recent Pixel and Samsung
Galaxy S flagships). Other phones use the cloud model automatically.

## Licence

MIT (see `LICENSE`); third-party notices in `NOTICE.md` and `LICENSES/`.
